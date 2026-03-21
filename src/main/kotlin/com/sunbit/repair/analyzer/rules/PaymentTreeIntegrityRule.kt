package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Validates payment tree integrity. Each payment "tree" (payments sharing
 * the same originalPaymentId root, or linked via directParentId chains) should
 * have exactly one active member. Trees with zero active members are orphaned;
 * trees with more than one indicate ghost payments or incomplete mutations.
 *
 * Skips false positives:
 *  - Trees where all leaf payments have amount=0 (zero-amount pay-now artifacts)
 *  - All dead-end findings when CPP status is PAID_OFF or purchase status is 60/70 (terminal states)
 */
@Component
class PaymentTreeIntegrityRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(PaymentTreeIntegrityRule::class.java)

    override val ruleId = "payment-tree-integrity"
    override val ruleName = "Payment Tree Integrity Check"
    override val description = "Validates that each payment tree has exactly one active member"
    override val detailedDescription = """
        Every loan payment that gets modified creates a chain: original -> child -> grandchild, etc. Each chain should have exactly one active member (the current version of that payment). This rule checks all payment chains and flags two problems: (1) chains with ZERO active members -- the payment 'disappeared' from the schedule, and (2) chains with MULTIPLE active members -- duplicate payments for the same slot. Chains where all leaf payments have amount=0 are excluded (these are normal pay-now artifacts). Terminal-state loans (paid off, cancelled) are also excluded since all payments are deactivated by design.

        Detection: Groups payments by originalPaymentId root, counts active members per group. Skips zero-amount leaf chains and terminal CPP statuses.
    """.trimIndent()

    companion object {
        private val TERMINAL_PURCHASE_STATUSES = setOf(60, 70)
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[PaymentTreeIntegrityRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val isTerminalState = snapshot.cppStatus == CppStatus.PAID_OFF ||
            snapshot.purchaseStatus in TERMINAL_PURCHASE_STATUSES

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

            if (activeCount == 0) {
                // --- False positive suppression for orphaned trees ---

                // 1. Terminal state: loan is paid off or cancelled -- all chains deactivated by design
                if (isTerminalState) {
                    log.debug(
                        "[PaymentTreeIntegrityRule][analyze] Skipping orphaned tree rootId={} -- terminal state " +
                            "(cppStatus={}, purchaseStatus={}) for purchaseId={}",
                        rootId, snapshot.cppStatus, snapshot.purchaseStatus, snapshot.purchaseId
                    )
                    continue
                }

                // 2. Zero-amount leaf artifact: all leaf payments (those with no children) have amount=0
                val parentIds = members.mapNotNull { it.directParentId }.toSet()
                val leaves = members.filter { it.id !in parentIds }
                val allLeavesZeroAmount = leaves.isNotEmpty() && leaves.all {
                    it.amount.compareTo(java.math.BigDecimal.ZERO) == 0
                }
                if (allLeavesZeroAmount) {
                    log.debug(
                        "[PaymentTreeIntegrityRule][analyze] Skipping orphaned tree rootId={} -- all leaves have " +
                            "amount=0 (zero-amount artifact) for purchaseId={}",
                        rootId, snapshot.purchaseId
                    )
                    continue
                }

                // This is a real orphaned tree
                val leafDetails = leaves.joinToString("; ") { leaf ->
                    "paymentId=${leaf.id}, amount=${leaf.amount}, CI=${leaf.changeIndicator}, dueDate=${leaf.dueDate}"
                }

                log.info(
                    "[PaymentTreeIntegrityRule][analyze] Orphaned tree rootId={} has 0 active members out of {} total for purchaseId={}",
                    rootId, members.size, snapshot.purchaseId
                )

                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.HIGH,
                        affectedPaymentIds = members.map { it.id },
                        description = "Payment tree with root=$rootId: Orphaned tree, no active payment found " +
                            "among ${members.size} members. " +
                            "Leaf payments: [$leafDetails]. " +
                            "Explanation: When a loan payment gets modified (e.g. date change, amount change, pay-now), " +
                            "the system creates a new child payment and deactivates the parent, forming a chain of " +
                            "payments called a 'payment tree'. Each tree should have exactly one active payment at " +
                            "the end of the chain. This is the payment that appears on the customer's schedule and " +
                            "will be charged. 'Zero active members' means that every payment in this chain has been " +
                            "deactivated, so this payment slot has effectively disappeared from the loan schedule. " +
                            "The customer will not be charged for this installment, which may result in an underpayment " +
                            "on the loan.",
                        evidence = mapOf(
                            "rootPaymentId" to rootId,
                            "activeCount" to 0,
                            "totalMembers" to members.size,
                            "activeMemberIds" to emptyList<Long>(),
                            "allMemberIds" to members.map { it.id },
                            "leafPaymentIds" to leaves.map { it.id },
                            "leafAmounts" to leaves.map { it.amount },
                            "isMultiDisbursal" to snapshot.isMultiDisbursal,
                        ),
                        suggestedRepairs = emptyList(), // Requires manual investigation
                    )
                )
            } else {
                // Multiple active members -- CRITICAL
                val activeDetails = activeMembers.joinToString("; ") { p ->
                    "paymentId=${p.id}, amount=${p.amount}, dueDate=${p.dueDate}, CI=${p.changeIndicator}"
                }

                log.info(
                    "[PaymentTreeIntegrityRule][analyze] Tree rootId={} has {} active members out of {} total for purchaseId={}",
                    rootId, activeCount, members.size, snapshot.purchaseId
                )

                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.CRITICAL,
                        affectedPaymentIds = activeMembers.map { it.id },
                        description = "Payment tree with root=$rootId: Multiple active payments in tree: " +
                            "${activeMembers.map { it.id }}. " +
                            "Active payments: [$activeDetails]. " +
                            "Explanation: When a loan payment gets modified, the system creates a new child payment " +
                            "and deactivates the parent, forming a chain called a 'payment tree'. Each tree should " +
                            "have exactly one active payment. 'Multiple active members' means that $activeCount " +
                            "payments exist for the same installment slot: the parent was not properly deactivated " +
                            "when the child was created (typically caused by a database failure during a mutation " +
                            "retry). This creates a risk of double-charging the customer, because the automatic " +
                            "charge system sees multiple active payments and may attempt to charge each one.",
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
        }

        return findings
    }
}
