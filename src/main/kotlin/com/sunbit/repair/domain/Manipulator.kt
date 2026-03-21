package com.sunbit.repair.domain

import java.time.Instant

// ---------------------------------------------------------------------------
// Manipulator categories
// ---------------------------------------------------------------------------

enum class ManipulatorCategory {
    STRUCTURAL,   // fix parent-child, ghost payments, orphans
    FINANCIAL,    // rebalance, amount change, refund
    REMEDIATION,  // one-off fixes (feb28, migration artifacts)
    CHARGEBACK,   // chargeback lifecycle
}

// ---------------------------------------------------------------------------
// Parameter specification -- describes what the user must provide
// ---------------------------------------------------------------------------

enum class ParamType {
    PAYMENT_ID,
    AMOUNT,
    DATE,
    ENUM,
    TEXT,
    BOOLEAN,
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String,
    val defaultValue: Any? = null,
    val enumValues: List<String>? = null,
)

// ---------------------------------------------------------------------------
// Applicability -- can this manipulator run on this purchase?
// ---------------------------------------------------------------------------

data class ApplicabilityResult(
    val canApply: Boolean,
    val reason: String,
    val suggestedParams: Map<String, Any> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Preview -- what would happen if we ran this?
// ---------------------------------------------------------------------------

data class ManipulatorPreview(
    val manipulatorId: String,
    val purchaseId: Long,
    val supported: Boolean,
    val description: String,
    val steps: List<PreviewStep> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class PreviewStep(
    val order: Int,
    val action: String,
    val description: String,
    val affectedPaymentIds: List<Long> = emptyList(),
)

// ---------------------------------------------------------------------------
// Execution result -- what happened when we ran it
// ---------------------------------------------------------------------------

data class ManipulatorExecutionResult(
    val manipulatorId: String,
    val purchaseId: Long,
    val executedAt: Instant,
    val success: Boolean,
    val stepsExecuted: List<ExecutionStep>,
    val error: String? = null,
)

data class ExecutionStep(
    val order: Int,
    val action: String,
    val success: Boolean,
    val apiResponse: Map<String, Any>? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Verification -- did the fix actually work?
// ---------------------------------------------------------------------------

data class VerificationResult(
    val passed: Boolean,
    val reason: String,
    val remainingFindings: List<Finding> = emptyList(),
    val resolvedFindings: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Flow -- multi-manipulator sequence
// ---------------------------------------------------------------------------

data class ManipulatorFlow(
    val purchaseId: Long,
    val description: String,
    val steps: List<FlowStep>,
)

data class FlowStep(
    val order: Int,
    val manipulatorId: String,
    val params: Map<String, Any> = emptyMap(),
    val skipIfNotApplicable: Boolean = false,
    val abortOnVerifyFail: Boolean = true,
)

data class FlowResult(
    val purchaseId: Long,
    val startedAt: Instant,
    val completedAt: Instant,
    val stepResults: List<FlowStepResult>,
    val abortedAtStep: Int? = null,
    val abortReason: String? = null,
) {
    val success: Boolean get() = abortedAtStep == null
}

data class FlowStepResult(
    val step: FlowStep,
    val applicability: ApplicabilityResult,
    val execution: ManipulatorExecutionResult?,
    val verification: VerificationResult?,
)
