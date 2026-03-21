package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * LOW: Reports any missing notifications from the snapshot's notification
 * summary. These are informational only -- no automated repair is possible
 * since notifications are fire-and-forget.
 */
@Component
class MissingNotificationRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(MissingNotificationRule::class.java)

    override val ruleId = "missing-notification"
    override val ruleName = "Missing Notification Detection"
    override val description = "Reports payments that should have triggered a notification but did not"
    override val detailedDescription = """
        After certain payment events (successful charge, failed charge, schedule change), the system should send the customer a notification (email or SMS) to keep them informed. This rule checks the notification summary for payments where an expected notification was never sent. Missing notifications can occur when the notification service was down, the event was not published to the message queue, or the notification template was misconfigured. While this does not affect the loan's financial state, the customer may be unaware of charges or changes to their payment schedule.

        Detection: Reads the pre-computed notification summary from the snapshot and reports any entries in the missing notifications list, including expected email type and payment context.
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[MissingNotificationRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val missing = snapshot.notifications.missing
        if (missing.isEmpty()) return emptyList()

        return listOf(
            Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.LOW,
                affectedPaymentIds = missing.map { it.paymentId },
                description = "Found ${missing.size} missing notification(s) for purchase ${snapshot.purchaseId}. " +
                    "After certain payment events (such as a successful charge, a failed charge, or a schedule change), " +
                    "the system should send the customer a notification (email or SMS). These notifications were expected " +
                    "but no record of them being sent was found. This can happen when the notification service was down, " +
                    "the event was not published to the message queue, or the notification template was misconfigured. " +
                    "While this does not affect the loan's financial state, the customer may be unaware of charges or " +
                    "changes to their payment schedule.",
                evidence = mapOf(
                    "missingCount" to missing.size,
                    "details" to missing.map { m ->
                        mapOf(
                            "paymentId" to m.paymentId,
                            "paidOffDate" to m.paidOffDate.toString(),
                            "expectedEmailType" to m.expectedEmailType,
                            "description" to m.description,
                        )
                    },
                ),
                suggestedRepairs = emptyList(), // Notification-only issue, no repair
            )
        )
    }
}
