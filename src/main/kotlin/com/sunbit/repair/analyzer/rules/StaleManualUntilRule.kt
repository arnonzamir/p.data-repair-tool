package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * MEDIUM: Finds active unpaid payments where manualUntil is in the past.
 *
 * The manualUntil flag prevents automatic charging until a given date.
 * If it is in the past and the payment is still unpaid, the flag was never
 * cleared and the auto-charge job may be skipping this payment.
 */
@Component
class StaleManualUntilRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(StaleManualUntilRule::class.java)

    override val ruleId = "stale-manual-until"
    override val ruleName = "Stale ManualUntil Flag"
    override val description = "Finds unpaid payments with an expired manualUntil date that was never cleared"
    override val detailedDescription = """
        The manualUntil field on a payment tells the automatic charge system "do not charge this payment until this date." It's set when a customer requests a payment delay or during certain mutations. Once the date passes, the charge system should pick up the payment and charge it. This rule finds payments where manualUntil is in the past but the payment is still unpaid, meaning the delay expired but the payment was never charged. This usually happens when the charge job skipped the payment or the manualUntil was set incorrectly.

        Detection: Checks active unpaid payments for manualUntil < now.
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[StaleManualUntilRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val now = LocalDateTime.now()
        val findings = mutableListOf<Finding>()

        for (payment in snapshot.payments) {
            if (!payment.isActive) continue
            if (payment.computedStatus != PaymentStatus.UNPAID) continue

            val manualUntil = payment.manualUntil ?: continue
            if (manualUntil.isBefore(now)) {
                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.MEDIUM,
                        affectedPaymentIds = listOf(payment.id),
                        description = "Payment ${payment.id} (amount=${payment.amount}, dueDate=${payment.dueDate}) " +
                            "has manualUntil=$manualUntil which is in the past. " +
                            "The manualUntil flag tells the automatic charge system 'do not charge this payment until this " +
                            "date'. The date has passed but the payment was never charged, suggesting the charge process " +
                            "skipped it. This can happen when the manualUntil date was set during a delayed charge or " +
                            "workout flow, but the follow-up charge never occurred because the auto-charge job does not " +
                            "retroactively pick up payments whose manualUntil has expired. The payment is effectively stuck " +
                            "and will not be charged unless manually triggered or the flag is cleared.",
                        evidence = mapOf(
                            "paymentId" to payment.id,
                            "manualUntil" to manualUntil.toString(),
                            "dueDate" to payment.dueDate.toString(),
                            "amount" to payment.amount,
                            "changeIndicator" to payment.changeIndicator,
                        ),
                        suggestedRepairs = emptyList(), // Requires investigation
                    )
                )
            }
        }

        return findings
    }
}
