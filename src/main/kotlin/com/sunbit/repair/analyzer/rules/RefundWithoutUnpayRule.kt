package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A12 -- MEDIUM: Detects payments with changeIndicator=32 (MARKED_AS_UNPAID) that have both
 * refundDate and paidOffDate set while still active. For CI=32 (unpay), the paidOffDate
 * should be cleared after the unpay operation. Having both dates set means the unpay did
 * not fully complete -- the refund was issued but the payment was not properly marked as
 * unpaid.
 *
 * Only flags CI=32. Other change indicators (e.g. CI=512 adjustment, CI=514 cancel) can
 * legitimately have both refundDate and paidOffDate set as part of their normal flow.
 */
@Component
class RefundWithoutUnpayRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(RefundWithoutUnpayRule::class.java)

    override val ruleId = "refund-without-unpay"
    override val ruleName = "Refund Without Unpay Completion Detection"
    override val description = "Detects CI=32 payments with both refundDate and paidOffDate set (incomplete unpay)"
    override val detailedDescription = """
        When a payment is unpaid (marked as not-paid, CI=32), the system should clear the paidOffDate and optionally issue a refund (setting refundDate). If both paidOffDate and refundDate are set on a CI=32 payment, it means the refund was issued but the payment was never properly marked as unpaid -- the system thinks the payment is both paid and refunded simultaneously. This does NOT apply to CI=512 (amount change) or CI=514 (cancellation) where having both dates is normal behavior.

        Detection: Finds active payments with changeIndicator=32 that have both paidOffDate and refundDate set.
    """.trimIndent()

    companion object {
        private const val MARKED_AS_UNPAID_CI = 32
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[RefundWithoutUnpayRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val problematic = snapshot.payments.filter { payment ->
            payment.changeIndicator == MARKED_AS_UNPAID_CI &&
                payment.refundDate != null &&
                payment.paidOffDate != null &&
                payment.isActive
        }

        if (problematic.isEmpty()) return emptyList()

        log.info(
            "[RefundWithoutUnpayRule][analyze] Found {} CI=32 payments with both refundDate and paidOffDate " +
                "for purchaseId={}",
            problematic.size, snapshot.purchaseId
        )

        return problematic.map { payment ->
            Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.MEDIUM,
                affectedPaymentIds = listOf(payment.id),
                description = "Payment ${payment.id} (amount=${payment.amount}, dueDate=${payment.dueDate}) has " +
                    "changeIndicator=32 (MARKED_AS_UNPAID) with both refundDate=${payment.refundDate} and " +
                    "paidOffDate=${payment.paidOffDate} while active. " +
                    "Explanation: An 'unpay' operation (changeIndicator=32, MARKED_AS_UNPAID) is used when a " +
                    "payment that was previously charged needs to be reversed (for example, if a charge was " +
                    "made in error or a customer's payment method was charged incorrectly). The unpay operation " +
                    "should: (1) issue a refund (setting refundDate), and (2) clear the paidOffDate so the " +
                    "payment returns to unpaid status and can be re-charged later. Having both dates set means " +
                    "the refund was issued but the paidOffDate was never cleared. The payment appears as both " +
                    "'paid' and 'refunded' simultaneously, which is contradictory for a CI=32 unpay. This can " +
                    "cause the payment to be skipped by the charge system (it looks paid) even though the money " +
                    "was returned to the customer.",
                evidence = mapOf(
                    "paymentId" to payment.id,
                    "amount" to payment.amount,
                    "dueDate" to payment.dueDate.toString(),
                    "changeIndicator" to payment.changeIndicator,
                    "refundDate" to payment.refundDate.toString(),
                    "paidOffDate" to payment.paidOffDate.toString(),
                    "isActive" to payment.isActive,
                ),
                suggestedRepairs = emptyList(),
            )
        }
    }
}
