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
                        "differ by $difference percentage points, exceeding the 1.0pp threshold.",
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
