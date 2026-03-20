package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Checks for schedule gap (active payment totals vs plan total) and
 * money gap (net collected vs what should have been collected based on paid
 * payments). Severity depends on gap size and direction:
 *
 * Schedule gap (planTotal - scheduleTotal):
 *   Positive = customer pays less than plan (in customer's favor)
 *   Negative = customer pays more than plan (against customer)
 *
 *   < $0.05       -> no finding (rounding tolerance)
 *   $0.05 - $1.00 in customer's favor -> LOW
 *   $1.00 - $5.00 in customer's favor -> MEDIUM
 *   > $5.00 in customer's favor -> HIGH
 *   Any amount against customer -> HIGH
 *
 * Money gap: always HIGH (actual money discrepancy).
 */
@Component
class MoneyGapRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(MoneyGapRule::class.java)

    override val ruleId = "money-gap"
    override val ruleName = "Money Gap Detection"
    override val description = "Detects schedule or money gaps with severity based on gap size and direction"

    companion object {
        private val TOLERANCE = BigDecimal("0.05")
        private val ONE_DOLLAR = BigDecimal("1.00")
        private val FIVE_DOLLARS = BigDecimal("5.00")
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[MoneyGapRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()
        val bc = snapshot.balanceCheck

        if (bc.scheduleGap.abs() > TOLERANCE) {
            // Positive gap = customer pays less (in their favor)
            // Negative gap = customer pays more (against them)
            val inCustomerFavor = bc.scheduleGap > BigDecimal.ZERO
            val absGap = bc.scheduleGap.abs()

            val severity = if (!inCustomerFavor) {
                // Against customer -- always HIGH
                Severity.HIGH
            } else if (absGap <= ONE_DOLLAR) {
                Severity.LOW
            } else if (absGap <= FIVE_DOLLARS) {
                Severity.MEDIUM
            } else {
                Severity.HIGH
            }

            val direction = if (inCustomerFavor) "in customer's favor" else "against customer"

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = severity,
                    affectedPaymentIds = emptyList(),
                    description = "Schedule gap of \$${absGap} ($direction). " +
                        "Plan total=${bc.planTotal}, active schedule total=${bc.scheduleTotal}.",
                    evidence = mapOf(
                        "scheduleGap" to bc.scheduleGap,
                        "direction" to direction,
                        "planTotal" to bc.planTotal,
                        "scheduleTotal" to bc.scheduleTotal,
                        "checkAVerdict" to bc.checkAVerdict,
                    ),
                    suggestedRepairs = listOf(
                        SuggestedRepair(
                            action = RepairActionType.CHANGE_AMOUNT,
                            description = "Trigger rebalance via ChangeAmount to recalculate schedule",
                            parameters = mapOf(
                                "purchaseId" to snapshot.purchaseId,
                                "newAmount" to bc.planTotal,
                            ),
                            supportsDryRun = true,
                        )
                    ),
                )
            )
        }

        if (bc.moneyGap.abs() > TOLERANCE) {
            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.HIGH,
                    affectedPaymentIds = emptyList(),
                    description = "Money gap of \$${bc.moneyGap.abs()} detected. " +
                        "Net collected does not match expected based on paid payments.",
                    evidence = mapOf(
                        "moneyGap" to bc.moneyGap,
                        "moneyCollected" to bc.moneyCollected,
                        "moneyReturned" to bc.moneyReturned,
                        "netCollected" to bc.netCollected,
                        "paidInstallments" to bc.paidInstallments,
                        "checkBVerdict" to bc.checkBVerdict,
                    ),
                    suggestedRepairs = emptyList(),
                )
            )
        }

        return findings
    }
}
