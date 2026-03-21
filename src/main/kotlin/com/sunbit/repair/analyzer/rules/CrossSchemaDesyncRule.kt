package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Detects cross-schema desynchronization using row-level matching from
 * the unified charge events. Replaces the old aggregate-count-based check.
 *
 * Three categories of findings:
 * - CRITICAL: Processor charged but purchase-service has no record (money collected without accounting)
 * - MEDIUM: Purchase-service has a charge record but no processor record (pre-migration, expected for older charges)
 * - HIGH: Amount mismatch between matched charge_transaction and processor attempt
 */
@Component
class CrossSchemaDesyncRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(CrossSchemaDesyncRule::class.java)

    override val ruleId = "cross-schema-desync"
    override val ruleName = "Cross-Schema Desynchronization"
    override val description = "Detects mismatches between processor and purchase-service charge records using row-level matching"
    override val detailedDescription = """
        Sunbit has two systems that track charges: the purchase-service (charge_transactions table) and the charge-service (payment_attempts table, introduced later). This rule uses the unified charge event view to compare them row-by-row. A processor charge with no purchase-service record means money was taken from the customer but not accounted for in the loan. A purchase-service record with no processor match is normal for charges that predate the charge-service migration. Amount mismatches between matched records indicate a recording error.

        Detection: Iterates unified charge events looking for UNMATCHED entries (processor-only or purchase-only) and amount discrepancies on matched entries.
    """.trimIndent()

    /** Charge transaction types that represent actual money collection (not internal adjustments). */
    private val MONEY_MOVEMENT_CT_TYPES = setOf(0, 1, 2) // SCHEDULED, UNSCHEDULED, DOWN_PAYMENT

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[CrossSchemaDesyncRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val events = snapshot.unifiedChargeEvents
        if (events.isEmpty()) return emptyList()

        val findings = mutableListOf<Finding>()

        // 1. Processor charged but no purchase-service charge_transaction (CRITICAL)
        val processorOnlySuccesses = events.filter {
            it.chargeTransaction == null
                && it.chargeServiceAttempt != null
                && it.chargeServiceAttempt.status == 0 // SUCCESS
        }
        if (processorOnlySuccesses.isNotEmpty()) {
            findings.add(Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.CRITICAL,
                affectedPaymentIds = processorOnlySuccesses.mapNotNull { it.paymentId },
                description = "${processorOnlySuccesses.size} successful processor charge(s) have no matching " +
                    "purchase-service charge_transaction. This means money was taken from the customer's card " +
                    "but the loan system (purchase-service) does not know about it. The payment processor (charge-service) " +
                    "successfully charged the customer, but no corresponding charge_transaction was created in the " +
                    "purchase schema. This typically happens when the charge-service callback fails or the " +
                    "purchase-service crashes after the charge succeeds but before recording it.",
                evidence = mapOf(
                    "unmatchedProcessorCharges" to processorOnlySuccesses.map { e ->
                        mapOf(
                            "chargeServiceAttemptId" to e.chargeServiceAttempt!!.id,
                            "amount" to e.amount,
                            "dateTime" to (e.chargeServiceAttempt.dateTime?.toString() ?: ""),
                            "externalId" to e.chargeServiceAttempt.externalId,
                            "processor" to (e.chargeServiceAttempt.paymentProcessor ?: ""),
                        )
                    },
                ),
                suggestedRepairs = emptyList(),
            ))
        }

        // 2. Purchase-service charge_transaction with no processor record (pre-migration, informational)
        val purchaseOnlyCharges = events.filter {
            it.chargeTransaction != null
                && it.chargeServiceAttempt == null
                && it.chargeTransaction.type in MONEY_MOVEMENT_CT_TYPES
        }
        if (purchaseOnlyCharges.isNotEmpty()) {
            findings.add(Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.LOW,
                affectedPaymentIds = purchaseOnlyCharges.mapNotNull { it.paymentId },
                description = "${purchaseOnlyCharges.size} purchase-service charge_transaction(s) have no matching " +
                    "processor record. These likely predate the charge-service migration and are expected.",
                evidence = mapOf(
                    "preMigrationCharges" to purchaseOnlyCharges.map { e ->
                        mapOf(
                            "chargeTransactionId" to e.chargeTransaction!!.id,
                            "type" to (e.chargeTransaction.typeName?.name ?: e.chargeTransaction.type),
                            "amount" to e.amount,
                            "chargeTime" to (e.chargeTransaction.chargeTime?.toString() ?: ""),
                        )
                    },
                ),
                suggestedRepairs = emptyList(),
            ))
        }

        // 3. Amount mismatch between matched records
        val amountMismatches = events.filter {
            it.chargeTransaction != null
                && it.chargeServiceAttempt != null
                && it.chargeServiceAttempt.status == 0
                && (it.chargeTransaction.amount - it.chargeServiceAttempt.amount).abs() > java.math.BigDecimal("0.01")
        }
        if (amountMismatches.isNotEmpty()) {
            findings.add(Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.HIGH,
                affectedPaymentIds = amountMismatches.mapNotNull { it.paymentId },
                description = "${amountMismatches.size} matched charge(s) have amount discrepancies between " +
                    "purchase-service and processor records. The loan system recorded a different amount than what " +
                    "the payment processor actually charged to the customer's card. This means the financial records " +
                    "are inconsistent: the customer was charged one amount but the loan schedule reflects another. " +
                    "Common causes include a payment amount being modified after the charge was initiated, or a partial " +
                    "charge that was recorded as the full amount.",
                evidence = mapOf(
                    "amountMismatches" to amountMismatches.map { e ->
                        mapOf(
                            "chargeTransactionId" to e.chargeTransaction!!.id,
                            "purchaseAmount" to e.chargeTransaction.amount,
                            "processorAmount" to e.chargeServiceAttempt!!.amount,
                            "difference" to (e.chargeTransaction.amount - e.chargeServiceAttempt.amount),
                        )
                    },
                ),
                suggestedRepairs = emptyList(),
            ))
        }

        return findings
    }
}
