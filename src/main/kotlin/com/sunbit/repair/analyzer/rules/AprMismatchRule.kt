package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * LOW: Compares the plan's nominalApr vs effectiveApr. If the difference
 * exceeds 1.0 percentage point, flags it as the SBT-52758 pattern where
 * APR divergence indicates a calculation error in the origination or
 * a failed APR change flow.
 */
@Component
class AprMismatchRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(AprMismatchRule::class.java)

    override val ruleId = "apr-mismatch"
    override val ruleName = "APR Mismatch Detection"
    override val description = "Flags loans where nominal and effective APR differ by more than 1 percentage point (SBT-52758 pattern)"
    override val detailedDescription = """
        The nominal APR is the interest rate agreed upon at loan origination. The effective APR is calculated from the actual payment amounts and timing in the current schedule. If these two diverge by more than 1 percentage point, it means the interest the customer is actually paying does not match their contract. This can happen after a failed APR change flow (where the rate was updated in the plan but the schedule was not recalculated), or after mutations that altered payment amounts without adjusting the interest split. The customer may be overpaying or underpaying interest relative to their agreement.

        Detection: Compares plan.nominalApr vs plan.effectiveApr, flags if absolute difference exceeds 1.0 percentage point.
    """.trimIndent()

    companion object {
        private val THRESHOLD = BigDecimal("1.0")
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[AprMismatchRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val effectiveApr = snapshot.plan.effectiveApr ?: return emptyList()
        val nominalApr = snapshot.plan.nominalApr

        val difference = (nominalApr - effectiveApr).abs()

        if (difference > THRESHOLD) {
            return listOf(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.LOW,
                    affectedPaymentIds = emptyList(),
                    description = "Nominal APR ($nominalApr) and effective APR ($effectiveApr) " +
                        "differ by $difference percentage points, exceeding the 1.0pp threshold. " +
                        "The nominal APR is the rate agreed upon at loan origination, while the effective APR is the " +
                        "rate actually implied by the current payment schedule (calculated from the actual payment " +
                        "amounts and timing). A significant divergence means the interest the customer is actually " +
                        "paying does not match what was agreed. This can happen after a failed APR change flow " +
                        "(where the rate was updated but the schedule was not recalculated), or after mutations that " +
                        "altered payment amounts without adjusting the interest split. The customer may be overpaying " +
                        "or underpaying interest relative to their contract.",
                    evidence = mapOf(
                        "nominalApr" to nominalApr,
                        "effectiveApr" to effectiveApr,
                        "difference" to difference,
                        "threshold" to THRESHOLD,
                        "planId" to snapshot.plan.planId,
                    ),
                    suggestedRepairs = emptyList(), // No direct repair -- requires APR investigation
                )
            )
        }

        return emptyList()
    }
}
