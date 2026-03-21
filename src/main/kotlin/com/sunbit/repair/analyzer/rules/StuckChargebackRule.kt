package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A7 -- MEDIUM: Detects payments stuck in a chargeback re-charge cycle. When a chargeback
 * occurs, the system attempts to re-charge the customer. The chargeBackEnhancement field
 * tracks this lifecycle: "3" (FIRST_PAID) means the first re-charge succeeded, "5"
 * (SECOND_PAID) means the second attempt succeeded. If chargeBackEnhancement is "3" or "5"
 * but paidOffDate is NULL, the re-charge was attempted but the payment was never marked as
 * paid, leaving the chargeback resolution incomplete.
 */
@Component
class StuckChargebackRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(StuckChargebackRule::class.java)

    override val ruleId = "stuck-chargeback"
    override val ruleName = "Stuck Chargeback Re-charge Detection"
    override val description = "Detects payments where chargeback re-charge was attempted but payment remains unpaid"
    override val detailedDescription = """
        The chargeback lifecycle includes a re-charge step: after a dispute is resolved in Sunbit's favor, the system attempts to re-charge the customer. The chargeBackEnhancement field tracks this -- '3' (FIRST_PAID) or '5' (SECOND_PAID) means a re-charge was recorded. If the payment's paidOffDate is still null despite the re-charge enhancement being set, it means the re-charge attempt failed or the payment status was never updated. The payment is stuck between 'chargeback resolved' and 'paid'.

        Detection: Finds payments with chargeBackEnhancement in ('3','5') and paidOffDate is null.
    """.trimIndent()

    companion object {
        // chargeBackEnhancement values indicating a re-charge was attempted/succeeded
        private val PAID_ENHANCEMENT_VALUES = setOf("3", "5")

        private val ENHANCEMENT_LABELS = mapOf(
            "3" to "FIRST_PAID",
            "5" to "SECOND_PAID",
        )
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[StuckChargebackRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val stuckPayments = snapshot.payments.filter { payment ->
            payment.chargeBackEnhancement in PAID_ENHANCEMENT_VALUES &&
                payment.paidOffDate == null
        }

        if (stuckPayments.isEmpty()) return emptyList()

        log.info(
            "[StuckChargebackRule][analyze] Found {} stuck chargeback payments for purchaseId={}",
            stuckPayments.size, snapshot.purchaseId
        )

        return stuckPayments.map { payment ->
            val enhancementLabel = ENHANCEMENT_LABELS[payment.chargeBackEnhancement]
                ?: payment.chargeBackEnhancement
            Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.MEDIUM,
                affectedPaymentIds = listOf(payment.id),
                description = "Payment ${payment.id} (amount=${payment.amount}, dueDate=${payment.dueDate}) has " +
                    "chargeBackEnhancement=${payment.chargeBackEnhancement} ($enhancementLabel) but paidOffDate " +
                    "is null. " +
                    "Explanation: When a customer disputes a charge (chargeback), the system goes through a " +
                    "lifecycle to resolve it. Part of this lifecycle involves re-charging the customer after the " +
                    "dispute is resolved in our favor. The chargeBackEnhancement field tracks this: a value of " +
                    "'3' (FIRST_PAID) or '5' (SECOND_PAID) means the system recorded that a re-charge attempt " +
                    "was made. However, the payment's paidOffDate is still null, meaning the payment was never " +
                    "marked as successfully paid. This indicates the chargeback resolution is stuck in an " +
                    "intermediate state: the re-charge may have failed silently, or the payment status update " +
                    "did not complete.",
                evidence = mapOf(
                    "paymentId" to payment.id,
                    "amount" to payment.amount,
                    "dueDate" to payment.dueDate.toString(),
                    "chargeBackEnhancement" to (payment.chargeBackEnhancement ?: "null"),
                    "enhancementLabel" to (enhancementLabel ?: "UNKNOWN"),
                    "chargeBack" to (payment.chargeBack ?: "null"),
                    "isActive" to payment.isActive,
                ),
                suggestedRepairs = emptyList(),
            )
        }
    }
}
