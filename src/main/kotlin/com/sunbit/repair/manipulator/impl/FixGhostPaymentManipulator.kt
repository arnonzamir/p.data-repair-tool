package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Fixes ghost payments: active payments whose direct parent is also active.
 *
 * The ghost child was created by a mutation (date change, rebalance, etc.) but
 * the parent was never deactivated, leaving two active payments where there
 * should be one. This manipulator unpays the ghost (the child that shouldn't
 * be active), optionally with refund if it was charged.
 *
 * Detection logic mirrors the ghost-payment analysis rule.
 */
@Component
class FixGhostPaymentManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(FixGhostPaymentManipulator::class.java)

    override val manipulatorId = "fix-ghost-payment"
    override val name = "Fix Ghost Payment"
    override val description = "Unpay ghost payments (active children whose parent is also active)"
    override val detailedDescription = """
        Ghost payments occur when a loan modification (date change, rebalance, etc.) creates a new child payment but fails to deactivate the original parent. Both remain active, meaning the customer has two payments for the same installment and may be charged twice. This manipulator finds all ghost children (active payments whose parent is also active) and unpays them. If the ghost was already charged, it unpays with refund; if not charged, it unpays without refund. After execution, only the parent payment remains active for each affected installment.
    """.trimIndent()
    override val category = ManipulatorCategory.STRUCTURAL

    override val requiredParams = listOf(
        ParamSpec(
            name = "paymentId",
            type = ParamType.PAYMENT_ID,
            required = false,
            description = "Specific ghost payment to fix. If omitted, fixes all detected ghosts.",
        ),
        ParamSpec(
            name = "refund",
            type = ParamType.BOOLEAN,
            required = false,
            description = "Whether to refund charged ghost payments. Defaults to true for paid ghosts.",
            defaultValue = true,
        ),
    )

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val ghosts = findGhosts(snapshot)
        if (ghosts.isEmpty()) {
            return ApplicabilityResult(
                canApply = false,
                reason = "No ghost payments detected",
            )
        }
        return ApplicabilityResult(
            canApply = true,
            reason = "${ghosts.size} ghost payment(s) detected: ${ghosts.map { it.id }}",
            suggestedParams = if (ghosts.size == 1) {
                mapOf("paymentId" to ghosts.first().id)
            } else {
                emptyMap()
            },
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val ghosts = findGhosts(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) {
            ghosts.filter { it.id == targetId }
        } else {
            ghosts
        }

        if (targets.isEmpty()) {
            return ManipulatorPreview(
                manipulatorId = manipulatorId,
                purchaseId = snapshot.purchaseId,
                supported = true,
                description = "No ghost payments to fix" + if (targetId != null) " (payment $targetId is not a ghost)" else "",
            )
        }

        val steps = targets.mapIndexed { idx, ghost ->
            val isPaid = ghost.paidOffDate != null
            val refund = params["refund"] as? Boolean ?: isPaid
            PreviewStep(
                order = idx + 1,
                action = if (isPaid && refund) "UNPAY_WITH_REFUND" else "UNPAY_WITHOUT_REFUND",
                description = "Unpay ghost payment ${ghost.id} (amount=${ghost.amount}, " +
                    "parent=${ghost.directParentId}, paid=${isPaid}, refund=$refund)",
                affectedPaymentIds = listOfNotNull(ghost.id, ghost.directParentId),
            )
        }

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will unpay ${targets.size} ghost payment(s)",
            steps = steps,
            warnings = if (targets.any { it.paidOffDate != null }) {
                listOf("Some ghost payments have been charged. Refunds will be issued")
            } else {
                emptyList()
            },
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val ghosts = findGhosts(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) {
            ghosts.filter { it.id == targetId }
        } else {
            ghosts
        }

        log.info(
            "[FixGhostPaymentManipulator][execute] Fixing {} ghost(s) on purchase {}",
            targets.size, snapshot.purchaseId,
        )

        val executionSteps = mutableListOf<ExecutionStep>()

        for ((idx, ghost) in targets.withIndex()) {
            val isPaid = ghost.paidOffDate != null
            val refund = params["refund"] as? Boolean ?: isPaid

            try {
                val response = if (isPaid && refund) {
                    adminApiClient.unpayWithRefund(snapshot.purchaseId, ghost.id, target)
                } else {
                    adminApiClient.unpayWithoutRefund(snapshot.purchaseId, ghost.id, target)
                }

                executionSteps.add(ExecutionStep(
                    order = idx + 1,
                    action = if (isPaid && refund) "UNPAY_WITH_REFUND" else "UNPAY_WITHOUT_REFUND",
                    success = true,
                    apiResponse = response,
                ))

                log.info(
                    "[FixGhostPaymentManipulator][execute] Successfully unpaid ghost payment {} (refund={})",
                    ghost.id, isPaid && refund,
                )
            } catch (ex: Exception) {
                log.error(
                    "[FixGhostPaymentManipulator][execute] Failed to unpay ghost payment {}: {}",
                    ghost.id, ex.message, ex,
                )
                executionSteps.add(ExecutionStep(
                    order = idx + 1,
                    action = if (isPaid && refund) "UNPAY_WITH_REFUND" else "UNPAY_WITHOUT_REFUND",
                    success = false,
                    error = ex.message,
                ))
                // Abort on first failure
                return ManipulatorExecutionResult(
                    manipulatorId = manipulatorId,
                    purchaseId = snapshot.purchaseId,
                    executedAt = Instant.now(),
                    success = false,
                    stepsExecuted = executionSteps,
                    error = "Failed to unpay ghost payment ${ghost.id}: ${ex.message}",
                )
            }
        }

        return ManipulatorExecutionResult(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            executedAt = Instant.now(),
            success = true,
            stepsExecuted = executionSteps,
        )
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val ghostsBefore = findGhosts(before)
        val ghostsAfter = findGhosts(after)

        val resolved = ghostsBefore.map { it.id }.filter { id ->
            ghostsAfter.none { it.id == id }
        }

        return if (ghostsAfter.isEmpty()) {
            VerificationResult(
                passed = true,
                reason = "All ${ghostsBefore.size} ghost payment(s) resolved",
                resolvedFindings = resolved.map { "Ghost payment $it" },
            )
        } else {
            VerificationResult(
                passed = false,
                reason = "${ghostsAfter.size} ghost payment(s) remain: ${ghostsAfter.map { it.id }}",
                resolvedFindings = resolved.map { "Ghost payment $it" },
            )
        }
    }

    // ------------------------------------------------------------------
    // Internal: detect ghost payments (mirrors ghost-payment rule logic)
    // ------------------------------------------------------------------

    private fun findGhosts(snapshot: PurchaseSnapshot): List<Payment> {
        val activeById = snapshot.payments
            .filter { it.isActive }
            .associateBy { it.id }

        return snapshot.payments.filter { payment ->
            payment.isActive &&
                payment.directParentId != null &&
                activeById.containsKey(payment.directParentId)
        }
    }
}
