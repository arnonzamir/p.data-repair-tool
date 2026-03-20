package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Finds two or more active scheduled payments (type=0) on the same dueDate.
 *
 * Severity depends on whether the charges were automated:
 * - Both automated (JOB source) and both PAID -> CRITICAL (confirmed system double charge)
 * - One or both manual (ADMIN, SELF_SERVICE, etc.) and both PAID -> MEDIUM (suspected, may be intentional)
 * - Not all paid -> HIGH (duplicate schedule, not yet charged)
 */
@Component
class DuplicateActiveSameDateRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(DuplicateActiveSameDateRule::class.java)

    override val ruleId = "duplicate-active-same-date"
    override val ruleName = "Duplicate Active Same Date Detection"
    override val description = "Finds multiple active scheduled payments sharing the same due date"

    companion object {
        private val SOURCE_NAMES = mapOf(
            0 to "JOB", 1 to "ADMIN", 2 to "SELF_SERVICE",
            5 to "IVR", 6 to "APP", 8 to "LINK", 9 to "CHAT",
        )
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[DuplicateActiveSameDateRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val activeScheduled = snapshot.payments.filter { p ->
            p.isActive && p.type == PaymentType.SCHEDULED.code
        }

        // Build payment -> source lookup from payment attempts
        val sourceByPaymentId = mutableMapOf<Long, String>()
        for (attempt in snapshot.paymentAttempts) {
            if (attempt.status == 0) { // SUCCESS
                val source = attempt.source?.let { SOURCE_NAMES[it] } ?: "UNKNOWN"
                val paymentId = attempt.triggeringPaymentId ?: attempt.paymentId
                sourceByPaymentId[paymentId] = source
            }
        }

        val byDueDate = activeScheduled.groupBy { it.dueDate }
        val findings = mutableListOf<Finding>()

        for ((dueDate, payments) in byDueDate) {
            if (payments.size < 2) continue

            val paymentIds = payments.map { it.id }
            val allPaid = payments.all { it.computedStatus == PaymentStatus.PAID }

            // Determine sources for each payment
            val paymentSources = payments.map { p ->
                val source = sourceByPaymentId[p.id] ?: "UNKNOWN"
                PaymentSourceInfo(id = p.id, source = source, status = p.computedStatus.name, amount = p.amount)
            }

            val allAutomated = allPaid && paymentSources.all { it.source == "JOB" }
            val anyManual = paymentSources.any { it.source != "JOB" && it.source != "UNKNOWN" }

            val severity = when {
                allPaid && allAutomated -> Severity.CRITICAL
                allPaid && anyManual -> Severity.MEDIUM
                allPaid -> Severity.HIGH // unknown source
                else -> Severity.HIGH // not all paid yet
            }

            val sourceDesc = paymentSources.joinToString(", ") { "#${it.id} (${it.source})" }

            val description = when {
                allPaid && allAutomated ->
                    "Confirmed automated double charge on $dueDate: $sourceDesc. Both charged by JOB."
                allPaid && anyManual ->
                    "Suspected double charge on $dueDate: $sourceDesc. At least one is manual -- may be intentional."
                allPaid ->
                    "Double charge on $dueDate: $sourceDesc. Both PAID."
                else ->
                    "Duplicate active scheduled payments on $dueDate: $sourceDesc. Not all paid yet."
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
                        "payments" to paymentSources.map { mapOf(
                            "id" to it.id,
                            "source" to it.source,
                            "status" to it.status,
                            "amount" to it.amount,
                        )},
                        "allPaid" to allPaid,
                        "allAutomated" to allAutomated,
                        "anyManual" to anyManual,
                    ),
                    suggestedRepairs = if (allPaid) {
                        payments.drop(1).map { dup ->
                            SuggestedRepair(
                                action = RepairActionType.UNPAY_WITH_REFUND,
                                description = "Unpay duplicate paid payment ${dup.id} with refund",
                                parameters = mapOf(
                                    "purchaseId" to snapshot.purchaseId,
                                    "paymentId" to dup.id,
                                ),
                                supportsDryRun = false,
                            )
                        }
                    } else {
                        emptyList()
                    },
                )
            )
        }

        return findings
    }

    private data class PaymentSourceInfo(
        val id: Long,
        val source: String,
        val status: String,
        val amount: java.math.BigDecimal,
    )
}
