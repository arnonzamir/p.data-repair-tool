package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext

/**
 * Finds two or more PAID active scheduled payments (type=0) that share
 * the same dueDate. Severity depends on context:
 *
 * - HIGH: same or similar amounts, or automated (JOB) source = likely real duplicate
 * - LOW: different amounts AND manual source (ADMIN/call center) = likely intentional
 *   (e.g., split payment, partial collection, delayed charge adjustment)
 */
@Component
class DuplicateChargeRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(DuplicateChargeRule::class.java)

    override val ruleId = "duplicate-charge"
    override val ruleName = "Duplicate Charge Detection"
    override val description = "Finds multiple paid active scheduled payments with the same due date"
    override val detailedDescription = """
        This rule looks for PAID duplicates: two or more active scheduled payments on the same due date that were both charged. It considers context to determine severity. If the amounts are similar (within 20%) or the charges were automated (JOB source), this is likely a real duplicate and gets HIGH severity. If the amounts are significantly different and both charges were manual (call center, admin), this is more likely intentional (e.g., a split payment or delayed charge adjustment) and gets LOW severity.

        Detection: Filters active scheduled payments (type=0) that are PAID, groups by dueDate, flags groups with 2+ members. Cross-references payment attempts for charge source.
    """.trimIndent()

    companion object {
        private const val AMOUNT_SIMILARITY_THRESHOLD = 20.0 // percent

        private val SOURCE_NAMES = mapOf(
            0 to "JOB", 1 to "ADMIN", 2 to "SELF_SERVICE",
            5 to "IVR", 6 to "APP", 8 to "LINK", 9 to "CHAT",
        )
        private val MANUAL_SOURCES = setOf("ADMIN", "SELF_SERVICE", "IVR", "APP", "LINK", "CHAT")
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[DuplicateChargeRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val paidScheduled = snapshot.payments.filter { p ->
            p.isActive &&
                p.computedStatus == PaymentStatus.PAID &&
                p.type == PaymentType.SCHEDULED.code
        }

        // Build source lookup
        val sourceByPaymentId = mutableMapOf<Long, String>()
        for (attempt in snapshot.paymentAttempts) {
            if (attempt.status == 0) {
                val source = attempt.source?.let { SOURCE_NAMES[it] } ?: "UNKNOWN"
                val paymentId = attempt.triggeringPaymentId ?: attempt.paymentId
                sourceByPaymentId[paymentId] = source
            }
        }

        val byDueDate = paidScheduled.groupBy { it.dueDate }
        val findings = mutableListOf<Finding>()

        for ((dueDate, payments) in byDueDate) {
            if (payments.size < 2) continue

            val paymentIds = payments.map { it.id }
            val amounts = payments.map { it.amount }
            val sources = payments.map { sourceByPaymentId[it.id] ?: "UNKNOWN" }

            // Check if amounts are similar (all pairs within threshold)
            val amountsSimilar = areAmountsSimilar(amounts)
            val allManual = sources.all { it in MANUAL_SOURCES }
            val anyAutomated = sources.any { it == "JOB" }

            // Different amounts + all manual = likely intentional (split, partial, delayed charge)
            val likelyIntentional = !amountsSimilar && allManual

            val severity = when {
                anyAutomated -> Severity.HIGH
                amountsSimilar -> Severity.HIGH
                likelyIntentional -> Severity.LOW
                else -> Severity.MEDIUM
            }

            val sourceDesc = payments.mapIndexed { i, p ->
                "#${p.id} (\$${p.amount}, ${sources[i]})"
            }.joinToString(", ")

            val description = when {
                likelyIntentional ->
                    "Multiple paid scheduled payments on $dueDate with different amounts: $sourceDesc. " +
                        "All charges were manual (call center/admin) and amounts differ significantly, " +
                        "so this is likely intentional (e.g., split payment, partial collection, or " +
                        "delayed charge adjustment). Verify with the agent who initiated the charges."

                anyAutomated ->
                    "Found ${payments.size} paid active scheduled payments with dueDate=$dueDate: $sourceDesc. " +
                        "At least one was charged by the automated system (JOB), indicating a likely system-level " +
                        "duplicate. The customer was probably charged twice for the same installment."

                amountsSimilar ->
                    "Found ${payments.size} paid active scheduled payments with dueDate=$dueDate: $sourceDesc. " +
                        "The amounts are similar, suggesting a real duplicate charge. The customer likely paid " +
                        "the same installment multiple times."

                else ->
                    "Found ${payments.size} paid active scheduled payments with dueDate=$dueDate: $sourceDesc. " +
                        "This may indicate a duplicate charge. Review the charge sources and amounts to determine " +
                        "if this was intentional."
            }

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = severity,
                    affectedPaymentIds = paymentIds,
                    description = description,
                    evidence = mapOf(
                        "dueDate" to dueDate.toString(),
                        "paymentIds" to paymentIds,
                        "amounts" to amounts,
                        "sources" to sources,
                        "amountsSimilar" to amountsSimilar,
                        "allManual" to allManual,
                        "likelyIntentional" to likelyIntentional,
                        "duplicateCount" to payments.size,
                    ),
                    suggestedRepairs = if (severity == Severity.HIGH) {
                        payments.drop(1).map { dup ->
                            SuggestedRepair(
                                action = RepairActionType.UNPAY_WITH_REFUND,
                                description = "Unpay duplicate payment ${dup.id} with refund",
                                parameters = mapOf(
                                    "purchaseId" to snapshot.purchaseId,
                                    "paymentId" to dup.id,
                                ),
                                supportsDryRun = false,
                            )
                        }
                    } else {
                        emptyList() // Low severity = likely intentional, no auto-repair
                    },
                )
            )
        }

        return findings
    }

    /**
     * Returns true if all payment amounts are within the similarity threshold
     * of each other (pairwise comparison).
     */
    private fun areAmountsSimilar(amounts: List<BigDecimal>): Boolean {
        if (amounts.size < 2) return true
        for (i in amounts.indices) {
            for (j in i + 1 until amounts.size) {
                val avg = (amounts[i] + amounts[j]).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64)
                if (avg.compareTo(BigDecimal.ZERO) == 0) continue
                val pctDiff = (amounts[i] - amounts[j]).abs()
                    .divide(avg, MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100))
                    .toDouble()
                if (pctDiff > AMOUNT_SIMILARITY_THRESHOLD) return false
            }
        }
        return true
    }
}
