package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Finds active payments where a parent reference is expected but missing.
 *
 * Parent IS expected for mutation-created payments:
 *   - CI=4 (DATE_CHANGE), CI=512 (CHANGE_AMOUNT), CI=514 (CANCEL),
 *     CI=515 (DELAYED_CHARGE), CI=517 (WORKOUT), CI=518 (APR_CHANGE),
 *     CI=32 (MARKED_AS_UNPAID)
 *   - CI=8 (PAY_NOW) with type=0 (SCHEDULED) -- these are rebalanced children
 *
 * Parent is NOT expected for:
 *   - CI=0 (NONE) -- original payment
 *   - CI=8 (PAY_NOW) with type=10/20 (UNSCHEDULED) -- standalone ad-hoc pay-now
 *
 * Also flags payments whose directParentId points to a non-existent payment.
 */
@Component
class OrphanedPaymentRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(OrphanedPaymentRule::class.java)

    override val ruleId = "orphaned-payment"
    override val ruleName = "Orphaned Payment Detection"
    override val description = "Finds active mutation-created payments with missing or invalid parent references"

    /** Change indicators where a parent is always expected. */
    private val PARENT_REQUIRED_CIS = setOf(4, 32, 512, 514, 515, 517, 518)

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[OrphanedPaymentRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val allPaymentIds = snapshot.payments.map { it.id }.toSet()
        val findings = mutableListOf<Finding>()

        for (payment in snapshot.payments) {
            if (!payment.isActive) continue
            if (payment.changeIndicator == 0) continue

            // Determine if a parent is expected
            val parentExpected = when {
                payment.changeIndicator in PARENT_REQUIRED_CIS -> true
                // PAY_NOW (CI=8): parent expected only for scheduled (type=0) rebalanced children,
                // NOT for unscheduled (type=10/20) standalone pay-now payments
                payment.changeIndicator == 8 && payment.type == 0 -> true
                payment.changeIndicator == 8 && payment.type in listOf(10, 20) -> false
                else -> true // Unknown CI -- flag if no parent
            }

            if (!parentExpected) continue

            val orphanReason = when {
                payment.directParentId == null ->
                    "Mutation-created payment (CI=${payment.changeIndicatorName ?: payment.changeIndicator}) " +
                        "has no directParentId -- expected for type=${payment.typeName ?: payment.type}"
                payment.directParentId !in allPaymentIds ->
                    "directParentId=${payment.directParentId} does not exist in snapshot"
                else -> null
            }

            if (orphanReason != null) {
                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.MEDIUM,
                        affectedPaymentIds = listOf(payment.id),
                        description = "Orphaned payment ${payment.id}: $orphanReason",
                        evidence = mapOf(
                            "paymentId" to payment.id,
                            "changeIndicator" to payment.changeIndicator,
                            "changeIndicatorName" to (payment.changeIndicatorName?.name ?: "UNKNOWN"),
                            "type" to payment.type,
                            "typeName" to (payment.typeName?.name ?: "UNKNOWN"),
                            "directParentId" to (payment.directParentId?.toString() ?: "null"),
                            "amount" to payment.amount,
                            "dueDate" to payment.dueDate.toString(),
                        ),
                        suggestedRepairs = emptyList(),
                    )
                )
            }
        }

        return findings
    }
}
