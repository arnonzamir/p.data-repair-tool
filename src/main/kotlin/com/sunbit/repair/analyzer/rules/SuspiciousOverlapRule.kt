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
 * Detects pairs of active payments that are suspiciously close in date and
 * amount, suggesting one should have been deactivated. Covers cases that
 * slip through other rules:
 *
 * - A pay-now on day N and an unpaid scheduled payment on day N+1 with the
 *   same amount (rebalance should have deactivated the scheduled one)
 * - Two job-charged payments a few days apart for the same amount
 *   (retry or race condition)
 *
 * Severity:
 * - CRITICAL: both paid by automated job (confirmed system double charge)
 * - HIGH: one paid, one unpaid with similar amount (missed deactivation)
 * - MEDIUM: both paid but at least one manual source
 */
@Component
class SuspiciousOverlapRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(SuspiciousOverlapRule::class.java)

    override val ruleId = "suspicious-overlap"
    override val ruleName = "Suspicious Payment Overlap"
    override val description = "Detects active payments with similar amounts on nearby dates that suggest a missed deactivation or double charge"
    override val detailedDescription = """
        When a customer makes an early payment (pay-now), the system should deactivate the original scheduled payment and rebalance the remaining schedule. Similarly, the automated charge system should never charge two payments with similar amounts just days apart. This rule finds pairs of active payments where: (1) the due dates are within 5 days of each other, (2) the amounts are within 10% of each other, and (3) neither is a down payment. It then checks whether the charges were automated (JOB source) or manual (ADMIN, SELF_SERVICE, etc.) to determine severity. Parent-child pairs are excluded since those are handled by the ghost-payment rule.

        Detection: Compares all pairs of active non-down-payment payments. Flags pairs where dates are within 5 days and amounts differ by less than 10%. Cross-references payment attempts to determine charge source.
    """.trimIndent()

    companion object {
        private const val MAX_DAYS_APART = 5L
        private const val AMOUNT_TOLERANCE_PERCENT = 10.0

        private val SOURCE_NAMES = mapOf(
            0 to "JOB", 1 to "ADMIN", 2 to "SELF_SERVICE",
            5 to "IVR", 6 to "APP", 8 to "LINK", 9 to "CHAT",
        )

        private val AUTOMATED_SOURCES = setOf("JOB")
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[SuspiciousOverlapRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        // All active payments excluding down payments (type=30)
        val active = snapshot.payments.filter { it.isActive && it.type != 30 }
        if (active.size < 2) return emptyList()

        // Build payment -> source lookup
        val sourceByPaymentId = buildSourceMap(snapshot)

        // Build parent-child set to skip known relationships
        val parentChildPairs = mutableSetOf<Set<Long>>()
        for (p in snapshot.payments) {
            if (p.directParentId != null) {
                parentChildPairs.add(setOf(p.id, p.directParentId))
            }
        }

        val findings = mutableListOf<Finding>()
        val reported = mutableSetOf<Set<Long>>()

        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]

                // Skip parent-child (handled by ghost-payment)
                val pairKey = setOf(a.id, b.id)
                if (pairKey in parentChildPairs) continue
                if (pairKey in reported) continue

                // Check date proximity
                val daysBetween = abs(ChronoUnit.DAYS.between(a.dueDate, b.dueDate))
                if (daysBetween > MAX_DAYS_APART) continue
                if (daysBetween == 0L) continue // exact same date handled by duplicate-active-same-date

                // Check amount similarity
                val pctDiff = amountDiffPercent(a.amount, b.amount) ?: continue
                if (pctDiff > AMOUNT_TOLERANCE_PERCENT) continue

                reported.add(pairKey)

                val sourceA = sourceByPaymentId[a.id] ?: "UNKNOWN"
                val sourceB = sourceByPaymentId[b.id] ?: "UNKNOWN"
                val aPaid = a.paidOffDate != null
                val bPaid = b.paidOffDate != null
                val bothPaid = aPaid && bPaid
                val onePaidOneNot = aPaid != bPaid
                val bothAutomated = bothPaid &&
                    sourceA in AUTOMATED_SOURCES && sourceB in AUTOMATED_SOURCES

                val severity = when {
                    bothAutomated -> Severity.CRITICAL
                    onePaidOneNot -> Severity.CRITICAL
                    bothPaid -> Severity.HIGH
                    else -> Severity.HIGH
                }

                val paidPayment = if (aPaid && !bPaid) a else if (bPaid && !aPaid) b else null
                val unpaidPayment = if (aPaid && !bPaid) b else if (bPaid && !aPaid) a else null

                val description = when {
                    onePaidOneNot && paidPayment != null && unpaidPayment != null ->
                        "Payment ${paidPayment.id} (${paidPayment.typeName ?: "type=${paidPayment.type}"}, " +
                            "\$${paidPayment.amount}, due ${paidPayment.dueDate}, PAID on ${paidPayment.paidOffDate.toString().substring(0, 10)}) " +
                            "and payment ${unpaidPayment.id} (${unpaidPayment.typeName ?: "type=${unpaidPayment.type}"}, " +
                            "\$${unpaidPayment.amount}, due ${unpaidPayment.dueDate}, UNPAID) are $daysBetween day(s) apart " +
                            "with ${String.format("%.1f", pctDiff)}% amount difference. " +
                            "The unpaid payment likely should have been deactivated when the paid one was charged. " +
                            "This typically happens when a pay-now or early charge doesn't properly rebalance the " +
                            "remaining schedule, leaving a redundant payment that the customer may be charged for again."

                    bothAutomated ->
                        "Payments ${a.id} and ${b.id} (\$${a.amount} and \$${b.amount}) were both charged by " +
                            "the automated system $daysBetween day(s) apart (${a.dueDate} and ${b.dueDate}). " +
                            "This strongly suggests a system-level double charge caused by a retry or race condition. " +
                            "The customer was likely charged twice for the same installment."

                    bothPaid ->
                        "Payments ${a.id} (\$${a.amount}, ${sourceA}) and ${b.id} (\$${b.amount}, ${sourceB}) " +
                            "were both charged $daysBetween day(s) apart (${a.dueDate} and ${b.dueDate}) " +
                            "with ${String.format("%.1f", pctDiff)}% amount difference. " +
                            "At least one charge was manual. This may be intentional but should be verified."

                    else ->
                        "Active payments ${a.id} (\$${a.amount}, due ${a.dueDate}) and ${b.id} " +
                            "(\$${b.amount}, due ${b.dueDate}) are $daysBetween day(s) apart with similar amounts. " +
                            "One may be redundant."
                }

                val suggestedRepairs = if (onePaidOneNot && unpaidPayment != null) {
                    listOf(SuggestedRepair(
                        action = RepairActionType.UNPAY_WITHOUT_REFUND,
                        description = "Deactivate the redundant unpaid payment ${unpaidPayment.id}",
                        parameters = mapOf(
                            "purchaseId" to snapshot.purchaseId,
                            "paymentId" to unpaidPayment.id,
                        ),
                        supportsDryRun = false,
                    ))
                } else if (bothPaid) {
                    // Suggest unpaying the later one
                    val later = if (a.dueDate.isAfter(b.dueDate)) a else b
                    listOf(SuggestedRepair(
                        action = RepairActionType.UNPAY_WITH_REFUND,
                        description = "Unpay the later duplicate payment ${later.id} with refund",
                        parameters = mapOf(
                            "purchaseId" to snapshot.purchaseId,
                            "paymentId" to later.id,
                        ),
                        supportsDryRun = false,
                    ))
                } else {
                    emptyList()
                }

                findings.add(Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = severity,
                    affectedPaymentIds = listOf(a.id, b.id),
                    description = description,
                    evidence = mapOf(
                        "paymentA" to mapOf(
                            "id" to a.id,
                            "amount" to a.amount,
                            "dueDate" to a.dueDate.toString(),
                            "type" to a.type,
                            "typeName" to (a.typeName?.name ?: ""),
                            "changeIndicator" to a.changeIndicator,
                            "paid" to aPaid,
                            "source" to sourceA,
                        ),
                        "paymentB" to mapOf(
                            "id" to b.id,
                            "amount" to b.amount,
                            "dueDate" to b.dueDate.toString(),
                            "type" to b.type,
                            "typeName" to (b.typeName?.name ?: ""),
                            "changeIndicator" to b.changeIndicator,
                            "paid" to bPaid,
                            "source" to sourceB,
                        ),
                        "daysBetween" to daysBetween,
                        "amountDiffPercent" to pctDiff,
                    ),
                    suggestedRepairs = suggestedRepairs,
                ))
            }
        }

        return findings
    }

    private fun buildSourceMap(snapshot: PurchaseSnapshot): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        for (attempt in snapshot.paymentAttempts) {
            if (attempt.status == 0) {
                val source = attempt.source?.let { SOURCE_NAMES[it] } ?: "UNKNOWN"
                val paymentId = attempt.triggeringPaymentId ?: attempt.paymentId
                map[paymentId] = source
            }
        }
        return map
    }

    private fun amountDiffPercent(a: BigDecimal, b: BigDecimal): Double? {
        val avg = (a + b).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64)
        if (avg.compareTo(BigDecimal.ZERO) == 0) return null
        val diff = (a - b).abs()
        return diff.divide(avg, MathContext.DECIMAL64)
            .multiply(BigDecimal.valueOf(100))
            .toDouble()
    }
}
