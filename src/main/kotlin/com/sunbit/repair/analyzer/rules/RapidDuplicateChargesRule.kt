package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * MEDIUM: Detects suspicious rapid duplicate charges. If two or more active
 * PAID payments have similar amounts (within 5% of each other) and their
 * paidOffDate values are within 14 days, they are flagged as potentially
 * erroneous rapid charges that may indicate a retry bug or operator error.
 */
@Component
class RapidDuplicateChargesRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(RapidDuplicateChargesRule::class.java)

    override val ruleId = "rapid-duplicate-charges"
    override val ruleName = "Rapid Duplicate Charges Detection"
    override val description = "Finds active paid payments with similar amounts charged within a short time window"

    companion object {
        private const val SIMILARITY_THRESHOLD_PERCENT = 5.0
        private const val MAX_DAYS_APART = 14L
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[RapidDuplicateChargesRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val activePaid = snapshot.payments.filter { p ->
            p.isActive &&
                p.computedStatus == PaymentStatus.PAID &&
                p.paidOffDate != null
        }

        if (activePaid.size < 2) return emptyList()

        val findings = mutableListOf<Finding>()
        val alreadyReported = mutableSetOf<Set<Long>>()

        for (i in activePaid.indices) {
            for (j in i + 1 until activePaid.size) {
                val a = activePaid[i]
                val b = activePaid[j]

                // Check time proximity
                val daysBetween = abs(ChronoUnit.DAYS.between(a.paidOffDate, b.paidOffDate))
                if (daysBetween > MAX_DAYS_APART) continue

                // Check amount similarity
                val similarityPct = computeSimilarityPercent(a.amount, b.amount) ?: continue
                if (similarityPct > SIMILARITY_THRESHOLD_PERCENT) continue

                val pairKey = setOf(a.id, b.id)
                if (pairKey in alreadyReported) continue
                alreadyReported.add(pairKey)

                log.info(
                    "[RapidDuplicateChargesRule][analyze] Suspicious pair: payments {} and {} " +
                        "with {}% difference, {} days apart for purchaseId={}",
                    a.id, b.id, similarityPct, daysBetween, snapshot.purchaseId
                )

                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.MEDIUM,
                        affectedPaymentIds = listOf(a.id, b.id),
                        description = "Payments ${a.id} and ${b.id} have similar amounts " +
                            "(${a.amount} vs ${b.amount}, ${String.format("%.2f", similarityPct)}% difference) " +
                            "and were paid $daysBetween days apart. This may indicate a duplicate charge.",
                        evidence = mapOf(
                            "paymentIdA" to a.id,
                            "paymentIdB" to b.id,
                            "amountA" to a.amount,
                            "amountB" to b.amount,
                            "paidOffDateA" to a.paidOffDate.toString(),
                            "paidOffDateB" to b.paidOffDate.toString(),
                            "daysBetween" to daysBetween,
                            "similarityPercent" to similarityPct,
                            "thresholdPercent" to SIMILARITY_THRESHOLD_PERCENT,
                        ),
                        suggestedRepairs = emptyList(), // Requires manual investigation
                    )
                )
            }
        }

        return findings
    }

    /**
     * Returns the percentage difference between two amounts relative to their
     * average. Returns null if both amounts are zero (cannot compute ratio).
     */
    private fun computeSimilarityPercent(a: BigDecimal, b: BigDecimal): Double? {
        val avg = (a + b).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64)
        if (avg.compareTo(BigDecimal.ZERO) == 0) return null
        val diff = (a - b).abs()
        return diff.divide(avg, MathContext.DECIMAL64)
            .multiply(BigDecimal.valueOf(100))
            .toDouble()
    }
}
