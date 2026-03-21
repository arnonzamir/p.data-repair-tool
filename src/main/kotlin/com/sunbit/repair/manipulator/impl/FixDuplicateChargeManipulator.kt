package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Fixes duplicate active charges: two or more active paid payments on the same
 * due date. Unpays all but the one specified (or the earliest-created by default).
 */
@Component
class FixDuplicateChargeManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(FixDuplicateChargeManipulator::class.java)

    override val manipulatorId = "fix-duplicate-charge"
    override val name = "Fix Duplicate Charge"
    override val description = "Unpay duplicate active paid payments on the same due date, keeping the earliest"
    override val detailedDescription = """
        Fixes cases where two or more active paid scheduled payments exist for the same due date, indicating the customer was charged multiple times for the same installment. The manipulator keeps the earliest-created payment (or a specific one if provided) and unpays all others with refund. This is a financial correction -- the customer gets refunded for the duplicate charge(s).
    """.trimIndent()
    override val category = ManipulatorCategory.STRUCTURAL

    override val requiredParams = listOf(
        ParamSpec(
            name = "keepPaymentId",
            type = ParamType.PAYMENT_ID,
            required = false,
            description = "Which payment to keep. If omitted, keeps the earliest-created duplicate.",
        ),
    )

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val groups = findDuplicateGroups(snapshot)
        if (groups.isEmpty()) {
            return ApplicabilityResult(canApply = false, reason = "No duplicate active charges detected")
        }
        val totalDuplicates = groups.values.sumOf { it.size - 1 }
        return ApplicabilityResult(
            canApply = true,
            reason = "$totalDuplicates duplicate(s) across ${groups.size} date(s): ${groups.keys}",
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val groups = findDuplicateGroups(snapshot)
        val keepId = (params["keepPaymentId"] as? Number)?.toLong()

        val steps = mutableListOf<PreviewStep>()
        var stepOrder = 0

        for ((date, payments) in groups) {
            val toKeep = if (keepId != null && payments.any { it.id == keepId }) {
                payments.first { it.id == keepId }
            } else {
                payments.minByOrNull { it.creationDate ?: it.dueDate.atStartOfDay() }!!
            }
            val toRemove = payments.filter { it.id != toKeep.id }

            for (dup in toRemove) {
                stepOrder++
                steps.add(PreviewStep(
                    order = stepOrder,
                    action = "UNPAY_WITH_REFUND",
                    description = "Unpay duplicate payment ${dup.id} on $date (amount=${dup.amount}), keeping ${toKeep.id}",
                    affectedPaymentIds = listOf(dup.id),
                ))
            }
        }

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will unpay ${steps.size} duplicate payment(s) across ${groups.size} date(s)",
            steps = steps,
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val groups = findDuplicateGroups(snapshot)
        val keepId = (params["keepPaymentId"] as? Number)?.toLong()
        val executionSteps = mutableListOf<ExecutionStep>()
        var stepOrder = 0

        for ((date, payments) in groups) {
            val toKeep = if (keepId != null && payments.any { it.id == keepId }) {
                payments.first { it.id == keepId }
            } else {
                payments.minByOrNull { it.creationDate ?: it.dueDate.atStartOfDay() }!!
            }
            val toRemove = payments.filter { it.id != toKeep.id }

            for (dup in toRemove) {
                stepOrder++
                try {
                    val response = adminApiClient.unpayWithRefund(snapshot.purchaseId, dup.id, target)
                    executionSteps.add(ExecutionStep(stepOrder, "UNPAY_WITH_REFUND", true, response))
                    log.info("[FixDuplicateChargeManipulator][execute] Unpaid duplicate {} on {} (kept {})", dup.id, date, toKeep.id)
                } catch (ex: Exception) {
                    log.error("[FixDuplicateChargeManipulator][execute] Failed to unpay {}: {}", dup.id, ex.message, ex)
                    executionSteps.add(ExecutionStep(stepOrder, "UNPAY_WITH_REFUND", false, error = ex.message))
                    return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps, "Failed on payment ${dup.id}: ${ex.message}")
                }
            }
        }

        return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), true, executionSteps)
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val groupsBefore = findDuplicateGroups(before)
        val groupsAfter = findDuplicateGroups(after)

        return if (groupsAfter.isEmpty()) {
            VerificationResult(
                passed = true,
                reason = "All ${groupsBefore.values.sumOf { it.size - 1 }} duplicates resolved",
                resolvedFindings = groupsBefore.keys.map { "Duplicates on $it" },
            )
        } else {
            VerificationResult(
                passed = false,
                reason = "${groupsAfter.values.sumOf { it.size - 1 }} duplicate(s) remain on dates: ${groupsAfter.keys}",
            )
        }
    }

    private fun findDuplicateGroups(snapshot: PurchaseSnapshot): Map<String, List<Payment>> {
        return snapshot.payments
            .filter { it.isActive && it.paidOffDate != null && it.type == 0 }
            .groupBy { it.dueDate.toString() }
            .filter { it.value.size > 1 }
    }
}
