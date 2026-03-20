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
                        description = "Payment ${payment.id} has manualUntil=$manualUntil which is in the past. " +
                            "Auto-charge may be blocked for this payment.",
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
