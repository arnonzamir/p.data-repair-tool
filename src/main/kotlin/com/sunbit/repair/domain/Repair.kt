package com.sunbit.repair.domain

import java.math.BigDecimal
import java.time.Instant

// ---------------------------------------------------------------------------
// Repair action types -- each maps to an admin API endpoint
// ---------------------------------------------------------------------------

enum class RepairActionType {
    UNPAY_WITH_REFUND,
    UNPAY_WITHOUT_REFUND,
    CHANGE_AMOUNT,
    REVERSAL_OF_ADJUSTMENT,
    CHANGE_APR,
    CREATE_WORKOUT,
    CREATE_SETTLEMENT,
    CANCEL_PURCHASE,
    RESTORE_CANCELLATION,
    REVERSE_CHARGEBACK,
}

// ---------------------------------------------------------------------------
// Repair requests -- what the operator wants to do
// ---------------------------------------------------------------------------

sealed class RepairRequest {
    abstract val purchaseId: Long
    abstract val actionType: RepairActionType
    abstract val reason: String

    data class UnpayWithRefund(
        override val purchaseId: Long,
        val paymentId: Long,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.UNPAY_WITH_REFUND
    }

    data class UnpayWithoutRefund(
        override val purchaseId: Long,
        val paymentId: Long,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.UNPAY_WITHOUT_REFUND
    }

    data class ChangeAmount(
        override val purchaseId: Long,
        val newAmount: BigDecimal,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.CHANGE_AMOUNT
    }

    data class ReversalOfAdjustment(
        override val purchaseId: Long,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.REVERSAL_OF_ADJUSTMENT
    }

    data class ChangeApr(
        override val purchaseId: Long,
        val newApr: BigDecimal,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.CHANGE_APR
    }

    data class CreateWorkout(
        override val purchaseId: Long,
        val workoutParams: Map<String, Any>,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.CREATE_WORKOUT
    }

    data class CreateSettlement(
        override val purchaseId: Long,
        val settlementAmount: BigDecimal,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.CREATE_SETTLEMENT
    }

    data class CancelPurchase(
        override val purchaseId: Long,
        val cancellationReason: String,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.CANCEL_PURCHASE
    }

    data class RestoreCancellation(
        override val purchaseId: Long,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.RESTORE_CANCELLATION
    }

    data class ReverseChargeback(
        override val purchaseId: Long,
        override val reason: String,
    ) : RepairRequest() {
        override val actionType = RepairActionType.REVERSE_CHARGEBACK
    }
}

// ---------------------------------------------------------------------------
// Repair execution results
// ---------------------------------------------------------------------------

data class RepairResult(
    val purchaseId: Long,
    val request: RepairRequest,
    val executedAt: Instant,
    val success: Boolean,
    val httpStatus: Int?,
    val responseBody: String?,
    val error: String?,
    val snapshotBefore: PurchaseSnapshot?,
    val snapshotAfter: PurchaseSnapshot?,
    val verificationResult: AnalysisResult?,
)

data class DryRunResult(
    val purchaseId: Long,
    val request: RepairRequest,
    val supported: Boolean,
    val preview: Map<String, Any>?,
    val description: String,
)

// ---------------------------------------------------------------------------
// Repair plan -- ordered multi-step repairs with verification gates
// ---------------------------------------------------------------------------

data class RepairPlan(
    val purchaseId: Long,
    val steps: List<RepairStep>,
    val createdAt: Instant,
    val description: String,
)

data class RepairStep(
    val order: Int,
    val request: RepairRequest,
    val verifyAfter: Boolean = true,
    val expectedOutcome: String? = null,
)

data class RepairPlanExecution(
    val plan: RepairPlan,
    val startedAt: Instant,
    val completedAt: Instant?,
    val stepResults: List<RepairStepResult>,
    val abortedAtStep: Int? = null,
    val abortReason: String? = null,
)

data class RepairStepResult(
    val step: RepairStep,
    val result: RepairResult,
    val verificationPassed: Boolean?,
)
