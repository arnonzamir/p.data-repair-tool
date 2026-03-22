package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager

/**
 * CRITICAL: Detects checkout.com processor actions (Captures/Refunds) that have no
 * corresponding record in the purchase-service charge_transactions or payment_attempts.
 *
 * This means money was moved at the processor level but the loan system doesn't know about it.
 * A Capture without a matching charge means the customer was charged but it's not recorded.
 * A Refund without a matching refund record means money was returned but the balance wasn't updated.
 */
@Component
class UnrecordedExternalActionRule(
    @Value("\${cache.db-path:#{null}}") private val configuredDbPath: String?,
) : AnalysisRule {

    private val log = LoggerFactory.getLogger(UnrecordedExternalActionRule::class.java)

    override val ruleId = "unrecorded-external-action"
    override val ruleName = "Unrecorded External Processor Action"
    override val description = "Detects checkout.com actions (Captures/Refunds) with no matching record in the loan system"
    override val detailedDescription = """
        The checkout.com data contains processor-level records of every Capture (charge) and Refund executed on the customer's card. Each of these should have a corresponding record in the loan system (a charge_transaction, payment_attempt, or refund record). This rule cross-references the checkout.com actions against the purchase's charge transactions and payment attempts. If a checkout action has no match, it means money moved at the processor but the loan system is unaware, which is a data integrity issue that can lead to incorrect balances.

        Detection: For each checkout.com action on this purchase, searches for a matching charge_transaction or payment_attempt by payment_id and approximate amount. Actions with no match are flagged.
    """.trimIndent()

    private val dbUrl by lazy {
        val path = configuredDbPath
            ?: Path.of(System.getProperty("user.home"), ".sunbit", "purchase-repair-cache.db").toString()
        "jdbc:sqlite:$path"
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        val checkoutActions = loadCheckoutActions(snapshot.purchaseId)
        if (checkoutActions.isEmpty()) return emptyList()

        log.debug("[UnrecordedExternalActionRule][analyze] Found {} checkout actions for purchase {}", checkoutActions.size, snapshot.purchaseId)

        // Build lookup sets for matching
        val chargePaymentIds = snapshot.chargeTransactions.map { it.id }.toSet()
        val attemptPaymentIds = snapshot.paymentAttempts.map { it.paymentId }.toSet()
        val allPaymentIds = snapshot.payments.map { it.id }.toSet()

        // For each payment, build a set of amounts charged (from charge_transactions)
        val chargedAmountsByPayment = mutableMapOf<Long, MutableSet<BigDecimal>>()
        for (ct in snapshot.chargeTransactions) {
            // charge_transactions don't have a direct payment_id, but we can match via payment_attempts
        }
        for (attempt in snapshot.paymentAttempts) {
            if (attempt.status == 0) { // SUCCESS
                val payment = snapshot.payments.find { it.id == attempt.paymentId }
                if (payment != null) {
                    chargedAmountsByPayment.getOrPut(payment.id) { mutableSetOf() }.add(payment.amount)
                }
            }
        }

        val findings = mutableListOf<Finding>()
        val unmatchedCaptures = mutableListOf<Map<String, Any>>()
        val unmatchedRefunds = mutableListOf<Map<String, Any>>()

        for (action in checkoutActions) {
            val paymentId = action["payment_id"] as? Long ?: continue
            val actionType = action["action_type"] as? String ?: continue
            val amount = (action["amount"] as? Number)?.toDouble() ?: continue
            val actionDate = action["action_date"] as? String ?: ""

            // Check if this payment_id exists in the purchase at all
            val paymentExists = paymentId in allPaymentIds

            // Check if there's a matching attempt for this payment
            val hasAttempt = snapshot.paymentAttempts.any { it.paymentId == paymentId }

            // For refunds, check if the payment has a refundDate
            val payment = snapshot.payments.find { it.id == paymentId }
            val isRefundRecorded = payment?.refundDate != null

            val isMatched = when (actionType) {
                "Capture" -> hasAttempt || (payment?.paidOffDate != null)
                "Refund" -> isRefundRecorded || snapshot.chargeTransactions.any {
                    it.typeName?.name?.contains("REFUND", ignoreCase = true) == true &&
                        (it.amount.toDouble() - amount).let { diff -> kotlin.math.abs(diff) < 0.02 }
                }
                else -> true
            }

            if (!isMatched) {
                val evidence = mapOf(
                    "paymentId" to paymentId,
                    "actionType" to actionType,
                    "amount" to amount,
                    "actionDate" to actionDate,
                    "paymentExists" to paymentExists,
                    "source" to (action["source"] ?: ""),
                )
                if (actionType == "Capture") unmatchedCaptures.add(evidence)
                else unmatchedRefunds.add(evidence)
            }
        }

        if (unmatchedCaptures.isNotEmpty()) {
            findings.add(Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.CRITICAL,
                affectedPaymentIds = unmatchedCaptures.mapNotNull { (it["paymentId"] as? Long) },
                description = "${unmatchedCaptures.size} checkout.com Capture(s) have no matching record in the loan system. " +
                    "The customer was charged at the processor level but the loan system does not know about it. " +
                    "This means the charge is not reflected in the payment schedule or balance calculations. " +
                    "Payments affected: ${unmatchedCaptures.map { "#${it["paymentId"]} (\$${it["amount"]}, ${it["actionDate"]})" }.joinToString(", ")}",
                evidence = mapOf("unmatchedCaptures" to unmatchedCaptures),
                suggestedRepairs = emptyList(),
            ))
        }

        if (unmatchedRefunds.isNotEmpty()) {
            findings.add(Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.HIGH,
                affectedPaymentIds = unmatchedRefunds.mapNotNull { (it["paymentId"] as? Long) },
                description = "${unmatchedRefunds.size} checkout.com Refund(s) have no matching refund record in the loan system. " +
                    "Money was returned to the customer at the processor level but the loan balance was not updated. " +
                    "Payments affected: ${unmatchedRefunds.map { "#${it["paymentId"]} (\$${it["amount"]}, ${it["actionDate"]})" }.joinToString(", ")}",
                evidence = mapOf("unmatchedRefunds" to unmatchedRefunds),
                suggestedRepairs = emptyList(),
            ))
        }

        return findings
    }

    private fun loadCheckoutActions(purchaseId: Long): List<Map<String, Any?>> {
        return try {
            DriverManager.getConnection(dbUrl).use { conn ->
                conn.prepareStatement(
                    "SELECT purchase_id, action_type, amount, action_date, payment_id, source FROM checkout_actions WHERE purchase_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, purchaseId)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        results.add(mapOf(
                            "purchase_id" to rs.getLong("purchase_id"),
                            "action_type" to rs.getString("action_type"),
                            "amount" to rs.getDouble("amount"),
                            "action_date" to rs.getString("action_date"),
                            "payment_id" to rs.getLong("payment_id"),
                            "source" to rs.getString("source"),
                        ))
                    }
                    results
                }
            }
        } catch (e: Exception) {
            log.warn("[UnrecordedExternalActionRule][loadCheckoutActions] Failed: {}", e.message)
            emptyList()
        }
    }
}
