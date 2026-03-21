package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Fixes orphaned payments: mutation-created payments (CI=8) of scheduled type
 * (type=0) that have no parent. These are usually leftover from failed or partial
 * mutations. The fix is to unpay them without refund (they shouldn't have been
 * charged).
 *
 * Note: CI=8 + type=10/20 (standalone pay-now) are NOT orphaned -- they have
 * no parent by design.
 */
@Component
class FixOrphanedPaymentManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(FixOrphanedPaymentManipulator::class.java)

    override val manipulatorId = "fix-orphaned-payment"
    override val name = "Fix Orphaned Payment"
    override val description = "Deactivate orphaned scheduled payments created by failed mutations (CI=8, type=0, no parent)"
    override val detailedDescription = """
        Orphaned payments are scheduled payments (type=0) created by a mutation (change indicator=8, PAY_NOW) that have no link back to a parent payment. They were likely created by a failed or partial mutation that didn't complete properly. Since they have no parent, they represent extra payments that shouldn't exist in the schedule. This manipulator deactivates them via unpay-without-refund (no money to refund since they weren't charged).
    """.trimIndent()
    override val category = ManipulatorCategory.STRUCTURAL

    override val requiredParams = listOf(
        ParamSpec(
            name = "paymentId",
            type = ParamType.PAYMENT_ID,
            required = false,
            description = "Specific orphan to fix. If omitted, fixes all detected orphans.",
        ),
    )

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val orphans = findOrphans(snapshot)
        if (orphans.isEmpty()) {
            return ApplicabilityResult(canApply = false, reason = "No orphaned payments detected")
        }
        return ApplicabilityResult(
            canApply = true,
            reason = "${orphans.size} orphaned payment(s): ${orphans.map { it.id }}",
            suggestedParams = if (orphans.size == 1) mapOf("paymentId" to orphans.first().id) else emptyMap(),
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val orphans = findOrphans(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) orphans.filter { it.id == targetId } else orphans

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will unpay ${targets.size} orphaned payment(s) without refund",
            steps = targets.mapIndexed { idx, orphan ->
                PreviewStep(
                    order = idx + 1,
                    action = "UNPAY_WITHOUT_REFUND",
                    description = "Unpay orphan ${orphan.id} (amount=${orphan.amount}, dueDate=${orphan.dueDate}, CI=${orphan.changeIndicator})",
                    affectedPaymentIds = listOf(orphan.id),
                )
            },
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val orphans = findOrphans(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) orphans.filter { it.id == targetId } else orphans

        val executionSteps = mutableListOf<ExecutionStep>()

        for ((idx, orphan) in targets.withIndex()) {
            try {
                val response = adminApiClient.unpayWithoutRefund(snapshot.purchaseId, orphan.id, target)
                executionSteps.add(ExecutionStep(idx + 1, "UNPAY_WITHOUT_REFUND", true, response))
                log.info("[FixOrphanedPaymentManipulator][execute] Unpaid orphan {}", orphan.id)
            } catch (ex: Exception) {
                log.error("[FixOrphanedPaymentManipulator][execute] Failed to unpay {}: {}", orphan.id, ex.message, ex)
                executionSteps.add(ExecutionStep(idx + 1, "UNPAY_WITHOUT_REFUND", false, error = ex.message))
                return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps, "Failed on payment ${orphan.id}: ${ex.message}")
            }
        }

        return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), true, executionSteps)
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val orphansBefore = findOrphans(before)
        val orphansAfter = findOrphans(after)

        return if (orphansAfter.isEmpty()) {
            VerificationResult(passed = true, reason = "All ${orphansBefore.size} orphan(s) resolved",
                resolvedFindings = orphansBefore.map { "Orphan payment ${it.id}" })
        } else {
            VerificationResult(passed = false, reason = "${orphansAfter.size} orphan(s) remain: ${orphansAfter.map { it.id }}")
        }
    }

    private fun findOrphans(snapshot: PurchaseSnapshot): List<Payment> {
        return snapshot.payments.filter { p ->
            p.isActive &&
                p.changeIndicator == 8 &&
                p.type == 0 &&
                p.directParentId == null &&
                p.originalPaymentId == null
        }
    }
}
