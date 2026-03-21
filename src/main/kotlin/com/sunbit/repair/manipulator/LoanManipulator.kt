package com.sunbit.repair.manipulator

import com.sunbit.repair.domain.*

/**
 * Contract for a loan manipulator. Each manipulator encapsulates a composite
 * fix operation that may involve multiple API calls with logic between them.
 *
 * Manipulators are auto-discovered by Spring and registered in the
 * ManipulatorRegistry. They must be thread-safe.
 *
 * Lifecycle for a single run:
 *   1. canApply()  -- check preconditions
 *   2. preview()   -- describe what would happen (no side effects)
 *   3. execute()   -- perform the fix (calls Admin API, etc.)
 *   4. verify()    -- confirm the fix worked by comparing before/after
 */
interface LoanManipulator {

    /** Unique machine-readable identifier, e.g. "fix-ghost-payment". */
    val manipulatorId: String

    /** Human-readable name shown in UI. */
    val name: String

    /** Short explanation of what this manipulator does. */
    val description: String

    /** Category for grouping in UI. */
    val category: ManipulatorCategory

    /** Detailed explanation of what this manipulator does, for non-experts. */
    val detailedDescription: String get() = description

    /** Parameters the user must/can provide. */
    val requiredParams: List<ParamSpec>

    /** Whether this manipulator is enabled by default. */
    val defaultEnabled: Boolean get() = true

    /**
     * Check whether this manipulator can apply to the given purchase.
     * Returns why it can or cannot apply, plus suggested parameter values
     * (e.g., the payment ID that should be unpaid).
     */
    fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult

    /**
     * Describe what would happen if this manipulator were executed.
     * Must not have side effects.
     */
    fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview

    /**
     * Execute the fix. May call multiple Admin API endpoints in sequence.
     * Returns the result of each step.
     * @param target Which environment to execute against (LOCAL, STAGING, PROD).
     */
    fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String = "LOCAL"): ManipulatorExecutionResult

    /**
     * Verify that the fix worked by comparing the state before and after.
     * Typically re-runs the relevant analysis rule(s) and checks that
     * the targeted findings are gone without introducing new ones.
     */
    fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult
}
