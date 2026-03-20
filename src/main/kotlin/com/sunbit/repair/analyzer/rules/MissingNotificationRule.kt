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
                description = "Found ${missing.size} missing notification(s) for purchase ${snapshot.purchaseId}.",
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
