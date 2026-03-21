package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A4 -- MEDIUM: Detects incomplete rebalance operations. After a rebalance (triggered by
 * pay-now, change-amount, workout, etc.), all active unpaid scheduled payments should share
 * the same paymentActionId. Multiple distinct non-null paymentActionId values among these
 * payments indicate a partial rebalance -- some payments were updated but others were not.
 */
@Component
class IncompleteRebalanceRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(IncompleteRebalanceRule::class.java)

    override val ruleId = "incomplete-rebalance"
    override val ruleName = "Incomplete Rebalance Detection"
    override val description = "Detects active unpaid scheduled payments with mixed paymentActionId values"
    override val detailedDescription = """
        When the system rebalances a loan (after pay-now, amount change, etc.), it creates new child payments for ALL remaining unpaid installments, all linked to the same paymentActionId. If some payments were updated but others weren't, the schedule contains a mix of old and new payments with different action IDs. This indicates the rebalance was interrupted mid-way, leaving the schedule in an inconsistent state where some payments reflect the old amounts and others the new.

        Detection: Groups active unpaid scheduled payments by paymentActionId. Flags when multiple distinct non-null action IDs exist (null is allowed for original pre-mutation payments).
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[IncompleteRebalanceRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        // Active unpaid scheduled payments (type=0)
        val activeUnpaidScheduled = snapshot.payments.filter { payment ->
            payment.isActive &&
                payment.paidOffDate == null &&
                payment.type == 0
        }

        if (activeUnpaidScheduled.size < 2) return emptyList()

        // Group by paymentActionId
        val groups = activeUnpaidScheduled.groupBy { it.paymentActionId }

        // Allow null paymentActionId (original payments before any mutation)
        // Only flag if there are multiple distinct non-null action IDs
        val nonNullGroups = groups.filterKeys { it != null }

        if (nonNullGroups.size <= 1) return emptyList()

        val actionIds = nonNullGroups.keys.filterNotNull()
        val hasNullGroup = groups.containsKey(null)

        log.info(
            "[IncompleteRebalanceRule][analyze] Found {} distinct paymentActionIds among {} active unpaid " +
                "scheduled payments for purchaseId={}",
            nonNullGroups.size, activeUnpaidScheduled.size, snapshot.purchaseId
        )

        val groupDetails = nonNullGroups.entries.joinToString("; ") { (actionId, payments) ->
            "actionId=$actionId -> ${payments.size} payments (IDs: ${payments.map { it.id }})"
        }
        val nullGroupDetail = if (hasNullGroup) {
            val nullPayments = groups[null]!!
            " Additionally, ${nullPayments.size} payments have no paymentActionId (original, never mutated): " +
                "IDs ${nullPayments.map { it.id }}."
        } else ""

        return listOf(
            Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.MEDIUM,
                affectedPaymentIds = activeUnpaidScheduled.map { it.id },
                description = "Found ${nonNullGroups.size} distinct paymentActionId values among " +
                    "${activeUnpaidScheduled.size} active unpaid scheduled payments: [$groupDetails]." +
                    nullGroupDetail +
                    " Explanation: When a loan is modified (e.g. a payment amount changes, a workout is created, " +
                    "or a pay-now triggers rebalancing), the system recalculates all remaining unpaid installments " +
                    "in a single operation called a 'rebalance'. All payments created or updated by that operation " +
                    "share the same paymentActionId. If active unpaid payments have different paymentActionIds, it " +
                    "means the rebalance only partially completed: some payments reflect the new terms while " +
                    "others still reflect the old terms. This can cause incorrect charge amounts and schedule " +
                    "inconsistencies.",
                evidence = mapOf(
                    "distinctActionIds" to actionIds,
                    "groupCount" to nonNullGroups.size,
                    "totalActiveUnpaid" to activeUnpaidScheduled.size,
                    "hasNullGroup" to hasNullGroup,
                    "groupBreakdown" to nonNullGroups.map { (k, v) -> mapOf("actionId" to k, "paymentIds" to v.map { it.id }) },
                ),
                suggestedRepairs = emptyList(),
            )
        )
    }
}
