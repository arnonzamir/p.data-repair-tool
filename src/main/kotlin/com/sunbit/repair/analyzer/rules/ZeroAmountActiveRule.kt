package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * HIGH: Finds active payments with amount <= 0. Active payments should always
 * carry a positive amount. A zero or negative amount on an active payment
 * indicates data corruption or an incomplete mutation that failed to
 * deactivate the record.
 */
@Component
class ZeroAmountActiveRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(ZeroAmountActiveRule::class.java)

    override val ruleId = "zero-amount-active"
    override val ruleName = "Zero Amount Active Payment Detection"
    override val description = "Finds active payments with amount less than or equal to zero"
    override val detailedDescription = """
        Active payments represent amounts the customer owes or has paid. An active payment with a zero or negative amount serves no purpose and indicates data corruption -- typically a mutation that created a ${'$'}0 child but failed to deactivate it. The charge system may attempt to process this payment, causing errors or confusing the schedule.

        Detection: Checks all active payments for amount <= 0.
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[ZeroAmountActiveRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()

        for (payment in snapshot.payments) {
            if (!payment.isActive) continue
            if (payment.amount > java.math.BigDecimal.ZERO) continue

            log.info(
                "[ZeroAmountActiveRule][analyze] Found zero/negative amount active payment {} with amount={} for purchaseId={}",
                payment.id, payment.amount, snapshot.purchaseId
            )

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.CRITICAL,
                    affectedPaymentIds = listOf(payment.id),
                    description = "Active payment ${payment.id} has amount=${payment.amount} " +
                        "(dueDate=${payment.dueDate}, CI=${payment.changeIndicatorName ?: payment.changeIndicator}, " +
                        "status=${payment.computedStatus.name}). " +
                        "An active payment represents something the customer owes or has paid. A \$0 (or negative) active " +
                        "payment serves no purpose and typically indicates a failed mutation that should have deactivated " +
                        "this record. For example, a rebalance or changeAmount operation may have zeroed out this payment's " +
                        "amount but failed to mark it as inactive, leaving a dead record in the active schedule. " +
                        "This can confuse the charge job and other downstream systems.",
                    evidence = mapOf(
                        "paymentId" to payment.id,
                        "amount" to payment.amount,
                        "dueDate" to payment.dueDate.toString(),
                        "computedStatus" to payment.computedStatus.name,
                        "changeIndicator" to payment.changeIndicator,
                        "type" to payment.type,
                    ),
                    suggestedRepairs = emptyList(), // Requires manual investigation
                )
            )
        }

        return findings
    }
}
