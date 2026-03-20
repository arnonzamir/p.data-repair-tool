package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * HIGH: Detects a paid unscheduled payment (type 10 or 20) that has no
 * corresponding rebalanced children among the remaining active payments.
 *
 * After a payNow, the system should rebalance remaining installments via
 * a ChangeAmount-like flow (CI=PAY_NOW). If the rebalance never happened,
 * the remaining schedule does not account for the extra payment.
 */
@Component
class MissingRebalanceRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(MissingRebalanceRule::class.java)

    override val ruleId = "missing-rebalance"
    override val ruleName = "Missing Rebalance After PayNow"
    override val description = "Detects paid unscheduled payments with no subsequent rebalance of remaining installments"

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[MissingRebalanceRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()

        val paidUnscheduled = snapshot.payments.filter { p ->
            p.isActive &&
                p.computedStatus == PaymentStatus.PAID &&
                p.type in listOf(PaymentType.UNSCHEDULED_PARTIAL.code, PaymentType.UNSCHEDULED_PAYOFF.code)
        }

        if (paidUnscheduled.isEmpty()) return emptyList()

        val activePayments = snapshot.payments.filter { it.isActive }

        for (unscheduled in paidUnscheduled) {
            val creationThreshold = unscheduled.paidOffDate ?: unscheduled.creationDate ?: continue

            // Look for any active payment with CI=PAY_NOW created after the unscheduled payment
            val hasRebalancedChildren = activePayments.any { p ->
                p.changeIndicatorName == ChangeIndicator.PAY_NOW &&
                    p.creationDate != null &&
                    p.creationDate.isAfter(creationThreshold.minusMinutes(1))
            }

            if (!hasRebalancedChildren) {
                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.HIGH,
                        affectedPaymentIds = listOf(unscheduled.id),
                        description = "Paid unscheduled payment ${unscheduled.id} (type=${unscheduled.type}) " +
                            "has no corresponding rebalance among active payments.",
                        evidence = mapOf(
                            "unscheduledPaymentId" to unscheduled.id,
                            "unscheduledType" to unscheduled.type,
                            "unscheduledAmount" to unscheduled.amount,
                            "paidOffDate" to (unscheduled.paidOffDate?.toString() ?: "null"),
                            "activePaymentCount" to activePayments.size,
                            "activePayNowCount" to activePayments.count {
                                it.changeIndicatorName == ChangeIndicator.PAY_NOW
                            },
                        ),
                        suggestedRepairs = listOf(
                            SuggestedRepair(
                                action = RepairActionType.CHANGE_AMOUNT,
                                description = "Trigger rebalance via ChangeAmount (same total) to force schedule recalculation",
                                parameters = mapOf(
                                    "purchaseId" to snapshot.purchaseId,
                                    "newAmount" to snapshot.plan.totalAmount,
                                ),
                                supportsDryRun = true,
                            )
                        ),
                    )
                )
            }
        }

        return findings
    }
}
