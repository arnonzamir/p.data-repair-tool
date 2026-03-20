package com.sunbit.repair.analyzer.engine

import com.sunbit.repair.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Orchestrator that runs all enabled analysis rules against a PurchaseSnapshot
 * (or a batch of snapshots) and collects the results.
 */
@Service
class PurchaseAnalyzer(
    private val ruleRegistry: RuleRegistry,
) {
    private val log = LoggerFactory.getLogger(PurchaseAnalyzer::class.java)

    /**
     * Run every enabled rule against a single snapshot, in parallel.
     */
    fun analyze(snapshot: PurchaseSnapshot): AnalysisResult {
        val enabledRules = ruleRegistry.getEnabledRules()
        log.info(
            "[PurchaseAnalyzer][analyze] Starting analysis for purchaseId={} with {} enabled rules",
            snapshot.purchaseId,
            enabledRules.size,
        )

        val ruleResults = runBlocking {
            executeRulesInParallel(enabledRules, snapshot)
        }

        val findings = ruleResults.flatMap { it.second }
        val executionResults = ruleResults.map { it.first }

        log.info(
            "[PurchaseAnalyzer][analyze] Completed analysis for purchaseId={}: {} findings ({} critical, {} high)",
            snapshot.purchaseId,
            findings.size,
            findings.count { it.severity == Severity.CRITICAL },
            findings.count { it.severity == Severity.HIGH },
        )

        return AnalysisResult(
            purchaseId = snapshot.purchaseId,
            analyzedAt = Instant.now(),
            findings = findings,
            ruleResults = executionResults,
        )
    }

    /**
     * Analyze a batch of purchases and produce a summary.
     */
    fun analyzeBatch(snapshots: List<PurchaseSnapshot>): BatchAnalysisResult {
        log.info(
            "[PurchaseAnalyzer][analyzeBatch] Starting batch analysis for {} purchases",
            snapshots.size,
        )

        val results = snapshots.map { analyze(it) }

        val bySeverity = mutableMapOf<Severity, Int>()
        val byRule = mutableMapOf<String, Int>()
        var withFindings = 0

        for (result in results) {
            if (result.findings.isNotEmpty()) withFindings++
            for (finding in result.findings) {
                bySeverity.merge(finding.severity, 1, Int::plus)
                byRule.merge(finding.ruleId, 1, Int::plus)
            }
        }

        val summary = BatchSummary(
            total = snapshots.size,
            withFindings = withFindings,
            bySeverity = bySeverity,
            byRule = byRule,
        )

        log.info(
            "[PurchaseAnalyzer][analyzeBatch] Batch complete: {} total, {} with findings, severity breakdown={}",
            summary.total,
            summary.withFindings,
            summary.bySeverity,
        )

        return BatchAnalysisResult(
            purchaseIds = snapshots.map { it.purchaseId },
            analyzedAt = Instant.now(),
            results = results,
            summary = summary,
        )
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private suspend fun executeRulesInParallel(
        rules: List<AnalysisRule>,
        snapshot: PurchaseSnapshot,
    ): List<Pair<RuleExecutionResult, List<Finding>>> = coroutineScope {
        rules.map { rule ->
            async {
                executeRule(rule, snapshot)
            }
        }.awaitAll()
    }

    private fun executeRule(
        rule: AnalysisRule,
        snapshot: PurchaseSnapshot,
    ): Pair<RuleExecutionResult, List<Finding>> {
        val startMs = System.currentTimeMillis()
        return try {
            val findings = rule.analyze(snapshot)
            val elapsed = System.currentTimeMillis() - startMs
            log.debug(
                "[PurchaseAnalyzer][executeRule] Rule '{}' completed in {}ms with {} findings for purchaseId={}",
                rule.ruleId,
                elapsed,
                findings.size,
                snapshot.purchaseId,
            )
            Pair(
                RuleExecutionResult(
                    ruleId = rule.ruleId,
                    ruleName = rule.ruleName,
                    enabled = true,
                    executionTimeMs = elapsed,
                    findingCount = findings.size,
                ),
                findings,
            )
        } catch (ex: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            log.error(
                "[PurchaseAnalyzer][executeRule] Rule '{}' failed after {}ms for purchaseId={}: {}",
                rule.ruleId,
                elapsed,
                snapshot.purchaseId,
                ex.message,
                ex,
            )
            Pair(
                RuleExecutionResult(
                    ruleId = rule.ruleId,
                    ruleName = rule.ruleName,
                    enabled = true,
                    executionTimeMs = elapsed,
                    findingCount = 0,
                    error = ex.message,
                ),
                emptyList(),
            )
        }
    }
}
