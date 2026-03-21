package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * MEDIUM: Finds active payments in CHARGEBACK status where no
 * REVERSE_CHARGEBACK charge transaction exists after the chargeback event.
 *
 * An unresolved chargeback means the dispute was never closed and the
 * payment is stuck in a limbo state.
 */
@Component
class ChargebackWithoutResolutionRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(ChargebackWithoutResolutionRule::class.java)

    override val ruleId = "chargeback-without-resolution"
    override val ruleName = "Chargeback Without Resolution"
    override val description = "Finds chargebacked payments with no subsequent reverse-chargeback transaction"
    override val detailedDescription = """
        A chargeback occurs when a customer disputes a charge with their bank. The payment enters a chargeback state and Sunbit can either accept the dispute or contest it. If the dispute is resolved in Sunbit's favor, a REVERSE_CHARGEBACK transaction is created. This rule finds payments still in chargeback status with no reverse-chargeback transaction, meaning the dispute was never formally resolved. The payment is stuck in limbo -- it won't be charged again but hasn't been written off.

        Detection: Finds active payments with chargeBack field set, then checks if any REVERSE_CHARGEBACK charge transaction exists for the purchase.
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[ChargebackWithoutResolutionRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()

        val reverseChargebackExists = snapshot.chargeTransactions.any { ct ->
            ct.typeName == ChargeTransactionType.REVERSE_CHARGEBACK
        }

        for (payment in snapshot.payments) {
            if (!payment.isActive) continue
            if (payment.chargeBack == null) continue

            if (!reverseChargebackExists) {
                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.MEDIUM,
                        affectedPaymentIds = listOf(payment.id),
                        description = "Payment ${payment.id} (amount=${payment.amount}, dueDate=${payment.dueDate}) " +
                            "has chargeBack='${payment.chargeBack}' but no REVERSE_CHARGEBACK transaction exists for this " +
                            "purchase. A chargeback means the customer disputed a payment with their bank and the funds were " +
                            "returned to them. This payment is in chargeback status but was never resolved: there is no " +
                            "record of the bank ruling in Sunbit's favor (reverse chargeback) or the dispute being closed. " +
                            "The payment is stuck in limbo: it shows as charged in the loan schedule but the money was " +
                            "clawed back by the bank. Until resolved, the loan balance is effectively understated.",
                        evidence = mapOf(
                            "paymentId" to payment.id,
                            "chargeBack" to (payment.chargeBack ?: ""),
                            "chargeBackEnhancement" to (payment.chargeBackEnhancement ?: ""),
                            "amount" to payment.amount,
                            "dueDate" to payment.dueDate.toString(),
                            "reverseChargebackTransactions" to 0,
                        ),
                        suggestedRepairs = listOf(
                            SuggestedRepair(
                                action = RepairActionType.REVERSE_CHARGEBACK,
                                description = "Execute ReverseChargeback if the dispute has been resolved in our favor",
                                parameters = mapOf(
                                    "purchaseId" to snapshot.purchaseId,
                                ),
                                supportsDryRun = false,
                            )
                        ),
                    )
                )
            }
        }

        return findings
    }
}
