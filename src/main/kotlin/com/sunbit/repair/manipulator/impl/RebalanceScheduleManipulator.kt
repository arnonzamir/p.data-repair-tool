package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Forces a schedule rebalance by changing the purchase amount to its current value.
 *
 * This triggers the purchase-service to deactivate all unpaid payments and create
 * a fresh schedule with recalculated interest and amounts. Useful when the schedule
 * has drifted due to partial mutations, ghost payments, or other structural issues.
 *
 * The net effect is: same amount owed, but clean payment schedule.
 */
@Component
class RebalanceScheduleManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(RebalanceScheduleManipulator::class.java)

    override val manipulatorId = "rebalance-schedule"
    override val name = "Rebalance Schedule"
    override val description = "Force schedule recalculation by re-setting the purchase amount to its current value"
    override val detailedDescription = """
        Forces the loan schedule to be recalculated from scratch by calling the change-amount API with the current total amount. This doesn't change what the customer owes -- it just triggers the system to deactivate all unpaid payments and create fresh ones with recalculated interest and amounts. Useful when the schedule has drifted due to partial mutations, ghost payments, or other structural issues that left the active payment totals out of sync with the loan plan.
    """.trimIndent()
    override val category = ManipulatorCategory.FINANCIAL

    override val requiredParams = listOf(
        ParamSpec(
            name = "targetAmount",
            type = ParamType.AMOUNT,
            required = false,
            description = "Amount to set. Defaults to current plan total (no change, just rebalance).",
        ),
    )

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val scheduleGap = snapshot.balanceCheck.scheduleGap
        val moneyGap = snapshot.balanceCheck.moneyGap

        // Applicable if there's a schedule gap or money gap
        val hasGap = scheduleGap.abs() > java.math.BigDecimal("0.01") ||
            moneyGap.abs() > java.math.BigDecimal("0.01")

        // Also applicable if there are active unpaid payments (always safe to rebalance)
        val hasUnpaid = snapshot.payments.any { it.isActive && it.paidOffDate == null && it.type != 30 }

        if (!hasUnpaid) {
            return ApplicabilityResult(
                canApply = false,
                reason = "No active unpaid payments to rebalance",
            )
        }

        return ApplicabilityResult(
            canApply = true,
            reason = if (hasGap) {
                "Schedule gap=$scheduleGap, money gap=$moneyGap. Rebalance may resolve"
            } else {
                "Schedule is balanced but rebalance is available"
            },
            suggestedParams = mapOf("targetAmount" to snapshot.plan.totalAmount),
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val targetAmount = extractAmount(params, snapshot)

        val currentUnpaid = snapshot.payments
            .filter { it.isActive && it.paidOffDate == null && it.type != 30 }

        // Try the preview endpoint
        val previewData = try {
            adminApiClient.previewAmountChange(snapshot.purchaseId, targetAmount)
        } catch (_: Exception) {
            null
        }

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will change amount to $targetAmount (current: ${snapshot.plan.totalAmount}), " +
                "triggering full schedule recalculation. ${currentUnpaid.size} unpaid payments will be replaced.",
            steps = listOf(
                PreviewStep(
                    order = 1,
                    action = "CHANGE_AMOUNT",
                    description = "Set purchase amount to $targetAmount",
                    affectedPaymentIds = currentUnpaid.map { it.id },
                ),
            ),
            warnings = if (targetAmount != snapshot.plan.totalAmount) {
                listOf("Target amount differs from current (${snapshot.plan.totalAmount} -> $targetAmount)")
            } else {
                emptyList()
            },
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val targetAmount = extractAmount(params, snapshot)

        log.info(
            "[RebalanceScheduleManipulator][execute] Rebalancing purchase {} with amount {}",
            snapshot.purchaseId, targetAmount,
        )

        return try {
            val response = adminApiClient.changeAmount(snapshot.purchaseId, targetAmount, target)

            ManipulatorExecutionResult(
                manipulatorId = manipulatorId,
                purchaseId = snapshot.purchaseId,
                executedAt = Instant.now(),
                success = true,
                stepsExecuted = listOf(
                    ExecutionStep(
                        order = 1,
                        action = "CHANGE_AMOUNT",
                        success = true,
                        apiResponse = response,
                    ),
                ),
            )
        } catch (ex: Exception) {
            log.error(
                "[RebalanceScheduleManipulator][execute] Failed to rebalance purchase {}: {}",
                snapshot.purchaseId, ex.message, ex,
            )
            ManipulatorExecutionResult(
                manipulatorId = manipulatorId,
                purchaseId = snapshot.purchaseId,
                executedAt = Instant.now(),
                success = false,
                stepsExecuted = listOf(
                    ExecutionStep(
                        order = 1,
                        action = "CHANGE_AMOUNT",
                        success = false,
                        error = ex.message,
                    ),
                ),
                error = "Change amount failed: ${ex.message}",
            )
        }
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val gapBefore = before.balanceCheck.scheduleGap.abs()
        val gapAfter = after.balanceCheck.scheduleGap.abs()
        val moneyGapBefore = before.balanceCheck.moneyGap.abs()
        val moneyGapAfter = after.balanceCheck.moneyGap.abs()

        val scheduleImproved = gapAfter < gapBefore || gapAfter <= java.math.BigDecimal("0.01")
        val moneyImproved = moneyGapAfter <= moneyGapBefore

        val resolved = mutableListOf<String>()
        if (gapBefore > java.math.BigDecimal("0.01") && gapAfter <= java.math.BigDecimal("0.01")) {
            resolved.add("Schedule gap resolved ($gapBefore -> $gapAfter)")
        }
        if (moneyGapBefore > java.math.BigDecimal("0.01") && moneyGapAfter <= java.math.BigDecimal("0.01")) {
            resolved.add("Money gap resolved ($moneyGapBefore -> $moneyGapAfter)")
        }

        val passed = gapAfter <= java.math.BigDecimal("0.01")

        return VerificationResult(
            passed = passed,
            reason = if (passed) {
                "Schedule gap resolved (was $gapBefore, now $gapAfter)"
            } else {
                "Schedule gap reduced but not resolved ($gapBefore -> $gapAfter), money gap: $moneyGapBefore -> $moneyGapAfter"
            },
            resolvedFindings = resolved,
        )
    }

    private fun extractAmount(params: Map<String, Any>, snapshot: PurchaseSnapshot): java.math.BigDecimal {
        val raw = params["targetAmount"]
        return when (raw) {
            is Number -> java.math.BigDecimal(raw.toString())
            is String -> java.math.BigDecimal(raw)
            else -> snapshot.plan.totalAmount
        }
    }
}
