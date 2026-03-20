package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * HIGH: Validates payment tree integrity. Each payment "tree" (payments sharing
 * the same originalPaymentId root, or linked via directParentId chains) should
 * have exactly one active member. Trees with zero active members are orphaned;
 * trees with more than one indicate ghost payments or incomplete mutations.
 */
@Component
class PaymentTreeIntegrityRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(PaymentTreeIntegrityRule::class.java)

    override val ruleId = "payment-tree-integrity"
    override val ruleName = "Payment Tree Integrity Check"
    override val description = "Validates that each payment tree has exactly one active member"

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[PaymentTreeIntegrityRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()

        // Group payments by their root: if originalPaymentId is set, that is the root;
        // otherwise the payment itself is the root.
        val treeGroups = snapshot.payments.groupBy { payment ->
            payment.originalPaymentId ?: payment.id
        }

        for ((rootId, members) in treeGroups) {
            // Skip single-member trees that are original payments (no mutation history)
            if (members.size == 1 && members[0].originalPaymentId == null) continue

            val activeMembers = members.filter { it.isActive }
            val activeCount = activeMembers.size

            if (activeCount == 1) continue // healthy tree

            val issue = when (activeCount) {
                0 -> "Orphaned tree -- no active payment found among ${members.size} members"
                else -> "Multiple active payments in tree: ${activeMembers.map { it.id }}"
            }

            val severity = if (activeCount > 1) Severity.HIGH else Severity.HIGH

            log.info(
                "[PaymentTreeIntegrityRule][analyze] Tree rootId={} has {} active members out of {} total for purchaseId={}",
                rootId, activeCount, members.size, snapshot.purchaseId
            )

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = severity,
                    affectedPaymentIds = if (activeCount == 0) members.map { it.id } else activeMembers.map { it.id },
                    description = "Payment tree with root=$rootId: $issue",
                    evidence = mapOf(
                        "rootPaymentId" to rootId,
                        "activeCount" to activeCount,
                        "totalMembers" to members.size,
                        "activeMemberIds" to activeMembers.map { it.id },
                        "allMemberIds" to members.map { it.id },
                    ),
                    suggestedRepairs = emptyList(), // Requires manual investigation
                )
            )
        }

        return findings
    }
}
