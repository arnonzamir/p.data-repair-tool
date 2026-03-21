package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Fixes suspicious overlapping payments: deactivates the redundant payment
 * and triggers a rebalance to fix the schedule gap.
 *
 * For one-paid-one-unpaid: unpays the unpaid one (without refund), then rebalances.
 * For both-paid: unpays the later one (with refund), then rebalances.
 */
@Component
class FixSuspiciousOverlapManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(FixSuspiciousOverlapManipulator::class.java)

    override val manipulatorId = "fix-suspicious-overlap"
    override val name = "Fix Suspicious Overlap"
    override val description = "Remove redundant overlapping payment and rebalance the schedule"
    override val detailedDescription = """
        Detects and fixes overlapping payments: pairs of active payments with similar amounts (within 10%) on nearby dates (within 5 days) that suggest one should have been deactivated. Common scenario: a pay-now on day N creates an unscheduled payment, but the original scheduled payment on day N+1 was never deactivated by the rebalance. This manipulator removes the redundant payment (the unpaid one if one is paid, or the later one if both are paid) and then triggers a schedule rebalance to fix the resulting gap.
    """.trimIndent()
    override val category = ManipulatorCategory.STRUCTURAL

    override val requiredParams = listOf(
        ParamSpec(
            name = "removePaymentId",
            type = ParamType.PAYMENT_ID,
            required = false,
            description = "Which payment to remove. If omitted, removes the unpaid one (or the later one if both paid).",
        ),
    )

    companion object {
        private const val MAX_DAYS = 5L
        private const val AMOUNT_TOLERANCE_PCT = 10.0
    }

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val overlaps = findOverlaps(snapshot)
        if (overlaps.isEmpty()) {
            return ApplicabilityResult(canApply = false, reason = "No suspicious overlapping payments detected")
        }
        val ids = overlaps.flatMap { listOf(it.first.id, it.second.id) }.distinct()
        return ApplicabilityResult(
            canApply = true,
            reason = "${overlaps.size} overlapping pair(s) found involving payments $ids",
            suggestedParams = if (overlaps.size == 1) {
                val (a, b) = overlaps.first()
                val toRemove = pickRemoval(a, b)
                mapOf("removePaymentId" to toRemove.id)
            } else emptyMap(),
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val overlaps = findOverlaps(snapshot)
        val removeId = (params["removePaymentId"] as? Number)?.toLong()

        val steps = mutableListOf<PreviewStep>()
        val toRemove = mutableSetOf<Long>()

        for ((a, b) in overlaps) {
            val removal = if (removeId != null && (a.id == removeId || b.id == removeId)) {
                if (a.id == removeId) a else b
            } else {
                pickRemoval(a, b)
            }
            if (removal.id in toRemove) continue
            toRemove.add(removal.id)

            val isPaid = removal.paidOffDate != null
            steps.add(PreviewStep(
                order = steps.size + 1,
                action = if (isPaid) "UNPAY_WITH_REFUND" else "UNPAY_WITHOUT_REFUND",
                description = "Remove overlapping payment ${removal.id} (\$${removal.amount}, due ${removal.dueDate}, ${if (isPaid) "PAID" else "UNPAID"})",
                affectedPaymentIds = listOf(removal.id),
            ))
        }

        steps.add(PreviewStep(
            order = steps.size + 1,
            action = "REBALANCE",
            description = "Rebalance schedule to fix gap (change amount to ${snapshot.plan.totalAmount})",
        ))

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will remove ${toRemove.size} overlapping payment(s) and rebalance the schedule",
            steps = steps,
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val overlaps = findOverlaps(snapshot)
        val removeId = (params["removePaymentId"] as? Number)?.toLong()
        val executionSteps = mutableListOf<ExecutionStep>()
        val removed = mutableSetOf<Long>()

        // Step 1: Remove overlapping payments
        for ((a, b) in overlaps) {
            val removal = if (removeId != null && (a.id == removeId || b.id == removeId)) {
                if (a.id == removeId) a else b
            } else {
                pickRemoval(a, b)
            }
            if (removal.id in removed) continue
            removed.add(removal.id)

            val isPaid = removal.paidOffDate != null
            try {
                val response = if (isPaid) {
                    adminApiClient.unpayWithRefund(snapshot.purchaseId, removal.id, target)
                } else {
                    adminApiClient.unpayWithoutRefund(snapshot.purchaseId, removal.id, target)
                }
                executionSteps.add(ExecutionStep(
                    executionSteps.size + 1,
                    if (isPaid) "UNPAY_WITH_REFUND" else "UNPAY_WITHOUT_REFUND",
                    true, response,
                ))
                log.info("[FixSuspiciousOverlapManipulator][execute] Removed overlap payment {} (paid={})", removal.id, isPaid)
            } catch (ex: Exception) {
                log.error("[FixSuspiciousOverlapManipulator][execute] Failed to remove {}: {}", removal.id, ex.message, ex)
                executionSteps.add(ExecutionStep(executionSteps.size + 1,
                    if (isPaid) "UNPAY_WITH_REFUND" else "UNPAY_WITHOUT_REFUND",
                    false, error = ex.message))
                return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps,
                    "Failed to remove payment ${removal.id}: ${ex.message}")
            }
        }

        // Step 2: Rebalance
        try {
            val response = adminApiClient.changeAmount(snapshot.purchaseId, snapshot.plan.totalAmount, target)
            executionSteps.add(ExecutionStep(executionSteps.size + 1, "REBALANCE", true, response))
            log.info("[FixSuspiciousOverlapManipulator][execute] Rebalanced purchase {}", snapshot.purchaseId)
        } catch (ex: Exception) {
            log.error("[FixSuspiciousOverlapManipulator][execute] Rebalance failed: {}", ex.message, ex)
            executionSteps.add(ExecutionStep(executionSteps.size + 1, "REBALANCE", false, error = ex.message))
            return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps,
                "Overlap removed but rebalance failed: ${ex.message}")
        }

        return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), true, executionSteps)
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val overlapsBefore = findOverlaps(before)
        val overlapsAfter = findOverlaps(after)
        val gapAfter = after.balanceCheck.scheduleGap.abs()

        val resolved = mutableListOf<String>()
        if (overlapsAfter.size < overlapsBefore.size) {
            resolved.add("${overlapsBefore.size - overlapsAfter.size} overlap(s) resolved")
        }
        if (gapAfter <= BigDecimal("0.05")) {
            resolved.add("Schedule gap resolved")
        }

        val passed = overlapsAfter.isEmpty() && gapAfter <= BigDecimal("0.05")
        return VerificationResult(
            passed = passed,
            reason = if (passed) "All overlaps removed and schedule balanced"
            else "Overlaps remaining: ${overlapsAfter.size}, schedule gap: \$$gapAfter",
            resolvedFindings = resolved,
        )
    }

    private fun findOverlaps(snapshot: PurchaseSnapshot): List<Pair<Payment, Payment>> {
        val active = snapshot.payments.filter { it.isActive && it.type != 30 }
        val parentChild = mutableSetOf<Set<Long>>()
        for (p in snapshot.payments) {
            if (p.directParentId != null) parentChild.add(setOf(p.id, p.directParentId))
        }

        val results = mutableListOf<Pair<Payment, Payment>>()
        val reported = mutableSetOf<Set<Long>>()

        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                val key = setOf(a.id, b.id)
                if (key in parentChild || key in reported) continue

                val days = abs(ChronoUnit.DAYS.between(a.dueDate, b.dueDate))
                if (days == 0L || days > MAX_DAYS) continue

                val pctDiff = amountDiffPct(a.amount, b.amount) ?: continue
                if (pctDiff > AMOUNT_TOLERANCE_PCT) continue

                reported.add(key)
                results.add(Pair(a, b))
            }
        }
        return results
    }

    private fun pickRemoval(a: Payment, b: Payment): Payment {
        val aPaid = a.paidOffDate != null
        val bPaid = b.paidOffDate != null
        return when {
            aPaid && !bPaid -> b  // remove the unpaid one
            bPaid && !aPaid -> a
            else -> if (a.dueDate.isAfter(b.dueDate)) a else b  // remove the later one
        }
    }

    private fun amountDiffPct(a: BigDecimal, b: BigDecimal): Double? {
        val avg = (a + b).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64)
        if (avg.compareTo(BigDecimal.ZERO) == 0) return null
        return (a - b).abs().divide(avg, MathContext.DECIMAL64)
            .multiply(BigDecimal.valueOf(100)).toDouble()
    }
}
