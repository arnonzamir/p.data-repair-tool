package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * MEDIUM: Detects the SBT-52760 pattern where the last active unpaid payment
 * carries a principalBalance that is disproportionately large relative to
 * its amount. This happens when a prior rebalance failed to properly
 * redistribute principal across the remaining schedule.
 */
@Component
class ResidualPrincipalRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(ResidualPrincipalRule::class.java)

    override val ruleId = "residual-principal"
    override val ruleName = "Residual Principal Mismatch"
    override val description = "Detects last unpaid payment with principalBalance exceeding 50% of its amount (SBT-52760 pattern)"
    override val detailedDescription = """
        On a standard amortizing loan, the principal balance on the last payment should be close to zero (the loan is nearly paid off by then). If the last unpaid payment's principal balance exceeds 50% of its amount, it suggests the interest/principal split was not recalculated properly after a prior change (amount change, rebalance, or APR modification). This can lead to incorrect interest charges on the final payment.

        Detection: Finds the last active unpaid scheduled payment (by due date). Checks if its principalBalance > 50% of its amount. Only runs if all prior payments are paid.
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[ResidualPrincipalRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val activePayments = snapshot.payments
            .filter { it.isActive && it.type == PaymentType.SCHEDULED.code }
            .sortedBy { it.dueDate }

        if (activePayments.size < 2) return emptyList()

        val unpaid = activePayments.filter { it.computedStatus == PaymentStatus.UNPAID }
        if (unpaid.isEmpty()) return emptyList()

        // Check that all payments before the last unpaid are paid
        val lastUnpaid = unpaid.last()
        val allPriorPaid = activePayments
            .filter { it.dueDate < lastUnpaid.dueDate }
            .all { it.computedStatus == PaymentStatus.PAID }

        if (!allPriorPaid) return emptyList()

        val principalBalance = lastUnpaid.principalBalance ?: return emptyList()
        val threshold = lastUnpaid.amount.multiply(BigDecimal("0.5"))

        if (principalBalance > threshold) {
            return listOf(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.MEDIUM,
                    affectedPaymentIds = listOf(lastUnpaid.id),
                    description = "Last unpaid payment ${lastUnpaid.id} has principalBalance=" +
                        "$principalBalance which exceeds 50% of amount=${lastUnpaid.amount} " +
                        "(dueDate=${lastUnpaid.dueDate}). " +
                        "The principal balance on the last payment should be close to zero, meaning the loan is nearly " +
                        "paid off by the time the final installment is due. A principal balance that is more than half " +
                        "the payment amount (\$${principalBalance} > \$${threshold}) suggests the interest/principal " +
                        "split was not recalculated properly after a prior change (such as a pay-now, rebalance, or " +
                        "amount change). This means the amortization schedule is broken: the customer's payments are " +
                        "not correctly reducing the principal, and the final payment may not fully pay off the loan.",
                    evidence = mapOf(
                        "paymentId" to lastUnpaid.id,
                        "amount" to lastUnpaid.amount,
                        "principalBalance" to principalBalance,
                        "dueDate" to lastUnpaid.dueDate.toString(),
                        "threshold" to threshold,
                        "priorPaidCount" to activePayments.count { it.computedStatus == PaymentStatus.PAID },
                    ),
                    suggestedRepairs = listOf(
                        SuggestedRepair(
                            action = RepairActionType.CHANGE_AMOUNT,
                            description = "ChangeAmount to force principal recalculation across remaining schedule",
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

        return emptyList()
    }
}
