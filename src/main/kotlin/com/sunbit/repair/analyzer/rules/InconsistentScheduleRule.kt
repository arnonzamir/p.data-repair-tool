package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.temporal.ChronoUnit

/**
 * MEDIUM: Checks active unpaid scheduled payments (type=0) sorted by dueDate
 * for gaps exceeding 45 days between consecutive entries. A gap this large
 * typically indicates a missing payment in the schedule, which can result
 * from an incomplete rebalance or a failed mutation that dropped a row.
 */
@Component
class InconsistentScheduleRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(InconsistentScheduleRule::class.java)

    override val ruleId = "inconsistent-schedule"
    override val ruleName = "Inconsistent Schedule Gap Detection"
    override val description = "Finds gaps exceeding 45 days between consecutive active unpaid scheduled payments"
    override val detailedDescription = """
        A loan's payment schedule should have regular intervals between due dates (typically monthly, ~30 days). This rule sorts active unpaid scheduled payments by due date and checks for gaps exceeding 45 days between consecutive payments. A large gap usually means a payment was lost during a failed rebalance or mutation, leaving a hole in the schedule where the customer won't be charged.

        Detection: Sorts active unpaid scheduled payments (type=0) by dueDate, calculates days between consecutive payments, flags gaps > 45 days. Reports the expected interval based on the most common gap.
    """.trimIndent()

    companion object {
        private const val MAX_GAP_DAYS = 45L
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[InconsistentScheduleRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val activeUnpaidScheduled = snapshot.payments
            .filter { p ->
                p.isActive &&
                    p.computedStatus == PaymentStatus.UNPAID &&
                    p.type == PaymentType.SCHEDULED.code
            }
            .sortedBy { it.dueDate }

        if (activeUnpaidScheduled.size < 2) return emptyList()

        val findings = mutableListOf<Finding>()

        // Compute the most common interval to report as expected
        val intervals = (1 until activeUnpaidScheduled.size).map { i ->
            ChronoUnit.DAYS.between(activeUnpaidScheduled[i - 1].dueDate, activeUnpaidScheduled[i].dueDate)
        }
        val expectedInterval = intervals.groupBy { it }.maxByOrNull { it.value.size }?.key ?: 30L

        for (i in 1 until activeUnpaidScheduled.size) {
            val prev = activeUnpaidScheduled[i - 1]
            val curr = activeUnpaidScheduled[i]
            val gapDays = ChronoUnit.DAYS.between(prev.dueDate, curr.dueDate)

            if (gapDays <= MAX_GAP_DAYS) continue

            log.info(
                "[InconsistentScheduleRule][analyze] Gap of {} days between payments {} and {} for purchaseId={}",
                gapDays, prev.id, curr.id, snapshot.purchaseId
            )

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.MEDIUM,
                    affectedPaymentIds = listOf(prev.id, curr.id),
                    description = "Gap of $gapDays days between payment ${prev.id} " +
                        "(dueDate=${prev.dueDate}) and payment ${curr.id} (dueDate=${curr.dueDate}). " +
                        "Expected interval is approximately $expectedInterval days. " +
                        "The loan's payment schedule should have regular intervals (typically monthly). " +
                        "A gap of $gapDays days between consecutive unpaid payments suggests a payment was lost " +
                        "or accidentally deleted from the schedule. This can happen when an incomplete rebalance " +
                        "drops a row, a failed mutation deletes a payment without creating its replacement, or a " +
                        "cancellation partially completed. The customer may miss a payment period entirely, causing " +
                        "the loan to fall behind or the remaining payments to be miscalculated.",
                    evidence = mapOf(
                        "previousPaymentId" to prev.id,
                        "previousDueDate" to prev.dueDate.toString(),
                        "nextPaymentId" to curr.id,
                        "nextDueDate" to curr.dueDate.toString(),
                        "gapDays" to gapDays,
                        "expectedIntervalDays" to expectedInterval,
                        "maxAllowedGapDays" to MAX_GAP_DAYS,
                    ),
                    suggestedRepairs = emptyList(), // Requires investigation -- likely a missing payment
                )
            )
        }

        return findings
    }
}
