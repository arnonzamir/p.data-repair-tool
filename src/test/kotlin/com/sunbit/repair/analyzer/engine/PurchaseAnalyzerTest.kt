package com.sunbit.repair.analyzer.engine

import com.sunbit.repair.TestFixtures.payment
import com.sunbit.repair.TestFixtures.snapshot
import com.sunbit.repair.analyzer.rules.*
import com.sunbit.repair.domain.Severity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PurchaseAnalyzerTest {

    private val allRules: List<AnalysisRule> = listOf(
        GhostPaymentRule(),
        MoneyGapRule(),
        CrossSchemaDesyncRule(),
        MissingRebalanceRule(),
        ResidualPrincipalRule(),
        OrphanedPaymentRule(),
        DuplicateChargeRule(),
        MissingNotificationRule(),
        StaleManualUntilRule(),
        ChargebackWithoutResolutionRule(),
        AprMismatchRule(),
    )

    private fun buildAnalyzer(disabledRules: String = ""): PurchaseAnalyzer {
        val registry = RuleRegistry(allRules, disabledRules)
        return PurchaseAnalyzer(registry)
    }

    @Test
    fun `healthy purchase produces no findings`() {
        val analyzer = buildAnalyzer()
        val snap = snapshot(payments = listOf(
            payment(id = 1, changeIndicator = 0, principalBalance = java.math.BigDecimal("50.00")),
            payment(id = 2, changeIndicator = 0, principalBalance = java.math.BigDecimal("10.00")),
        ))
        val result = analyzer.analyze(snap)
        assertTrue(result.findings.isEmpty(), "Healthy purchase should have no findings but got: ${result.findings.map { "${it.ruleId}: ${it.description}" }}")
    }

    @Test
    fun `ghost payment triggers CRITICAL finding`() {
        val analyzer = buildAnalyzer()
        val snap = snapshot(payments = listOf(
            payment(id = 1, isActive = true),
            payment(id = 2, isActive = true, directParentId = 1, changeIndicator = 8),
        ))
        val result = analyzer.analyze(snap)
        assertTrue(result.findings.any { it.severity == Severity.CRITICAL },
            "Should have at least one CRITICAL finding")
        assertTrue(result.findings.any { it.ruleId == "ghost-payment" },
            "Ghost payment rule should have fired")
    }

    @Test
    fun `disabled rule does not execute`() {
        val analyzer = buildAnalyzer(disabledRules = "ghost-payment")
        val snap = snapshot(payments = listOf(
            payment(id = 1, isActive = true),
            payment(id = 2, isActive = true, directParentId = 1, changeIndicator = 8),
        ))
        val result = analyzer.analyze(snap)
        // Ghost payment rule is disabled, so no finding from it
        assertTrue(result.findings.none { it.ruleId == "ghost-payment" },
            "Disabled ghost-payment rule should not produce findings")
        // Enabled rule count should be 10 (11 - 1 disabled)
        assertEquals(10, result.ruleResults.size, "Should have 10 enabled rule results")
    }

    @Test
    fun `batch analysis produces summary`() {
        val analyzer = buildAnalyzer()
        val snap1 = snapshot(purchaseId = 1, payments = listOf(
            payment(id = 1, isActive = true),
            payment(id = 2, isActive = true, directParentId = 1, changeIndicator = 8),
        ))
        val snap2 = snapshot(purchaseId = 2, payments = listOf(
            payment(id = 3, changeIndicator = 0),
        ))

        val batch = analyzer.analyzeBatch(listOf(snap1, snap2))
        assertEquals(2, batch.results.size)
        assertEquals(2, batch.summary.total)
        // snap1 has findings (ghost payment), snap2 should be clean
        assertTrue(batch.summary.withFindings >= 1, "At least one purchase should have findings")
    }
}
