package com.sunbit.repair.analyzer.engine

import com.sunbit.repair.domain.Finding
import com.sunbit.repair.domain.PurchaseSnapshot

/**
 * Contract for a single analysis rule. Each rule inspects a PurchaseSnapshot
 * and returns zero or more Findings. Rules are auto-discovered by Spring and
 * registered in the RuleRegistry.
 */
interface AnalysisRule {
    /** Unique machine-readable identifier, e.g. "ghost-payment". */
    val ruleId: String

    /** Human-readable name shown in reports. */
    val ruleName: String

    /** Short explanation of what this rule detects. */
    val description: String

    /** Whether the rule is enabled when no explicit override exists. */
    val defaultEnabled: Boolean get() = true

    /**
     * Analyze the snapshot and return any findings.
     * Implementations must be thread-safe (rules execute in parallel).
     */
    fun analyze(snapshot: PurchaseSnapshot): List<Finding>
}
