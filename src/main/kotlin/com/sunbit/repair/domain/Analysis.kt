package com.sunbit.repair.domain

import java.time.Instant

// ---------------------------------------------------------------------------
// Analysis findings -- produced by the rules engine
// ---------------------------------------------------------------------------

data class Finding(
    val ruleId: String,
    val ruleName: String,
    val severity: Severity,
    val affectedPaymentIds: List<Long>,
    val description: String,
    val evidence: Map<String, Any>,
    val suggestedRepairs: List<SuggestedRepair>,
)

data class SuggestedRepair(
    val action: RepairActionType,
    val description: String,
    val parameters: Map<String, Any>,
    val supportsDryRun: Boolean,
)

data class AnalysisResult(
    val purchaseId: Long,
    val analyzedAt: Instant,
    val findings: List<Finding>,
    val ruleResults: List<RuleExecutionResult>,
) {
    val criticalCount: Int get() = findings.count { it.severity == Severity.CRITICAL }
    val highCount: Int get() = findings.count { it.severity == Severity.HIGH }
    val mediumCount: Int get() = findings.count { it.severity == Severity.MEDIUM }
    val lowCount: Int get() = findings.count { it.severity == Severity.LOW }
    val overallSeverity: Severity?
        get() = findings.maxByOrNull { it.severity.ordinal }?.severity
}

data class RuleExecutionResult(
    val ruleId: String,
    val ruleName: String,
    val enabled: Boolean,
    val executionTimeMs: Long,
    val findingCount: Int,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Batch analysis
// ---------------------------------------------------------------------------

data class BatchAnalysisResult(
    val purchaseIds: List<Long>,
    val analyzedAt: Instant,
    val results: List<AnalysisResult>,
    val summary: BatchSummary,
)

data class BatchSummary(
    val total: Int,
    val withFindings: Int,
    val bySeverity: Map<Severity, Int>,
    val byRule: Map<String, Int>,
)
