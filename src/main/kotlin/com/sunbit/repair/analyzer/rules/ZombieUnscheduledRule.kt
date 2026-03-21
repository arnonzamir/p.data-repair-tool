package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A6 -- HIGH: Detects active unscheduled payments (type=10 or 20) that are NOT paid
 * (paidOffDate IS NULL). These are created by pay-now or early-charge flows and should
 * be either charged and marked paid, or deactivated. If they linger in active+unpaid
 * state, the automatic charge system may attempt to charge them again.
 */
@Component
class ZombieUnscheduledRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(ZombieUnscheduledRule::class.java)

    override val ruleId = "zombie-unscheduled"
    override val ruleName = "Zombie Unscheduled Payment Detection"
    override val description = "Detects active unscheduled payments that remain unpaid"
    override val detailedDescription = """
        When a customer pays early (pay-now) or the system initiates an early charge, an unscheduled payment (type 10 = partial, type 20 = payoff) is created and should be charged immediately. If the charge fails, the payment should be deactivated. This rule finds unscheduled payments that are still active but were never paid -- they are 'zombies' that the automatic charge system may attempt to process again, potentially causing an unintended charge.

        Detection: Finds active payments with type 10 or 20 where paidOffDate is null.
    """.trimIndent()

    companion object {
        private val UNSCHEDULED_TYPES = listOf(10, 20)
    }

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[ZombieUnscheduledRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val zombies = snapshot.payments.filter { payment ->
            payment.isActive &&
                payment.type in UNSCHEDULED_TYPES &&
                payment.paidOffDate == null
        }

        if (zombies.isEmpty()) return emptyList()

        log.info(
            "[ZombieUnscheduledRule][analyze] Found {} zombie unscheduled payments for purchaseId={}",
            zombies.size, snapshot.purchaseId
        )

        return zombies.map { payment ->
            val typeName = PaymentType.fromCodeOrNull(payment.type)?.name ?: "UNKNOWN(${payment.type})"
            Finding(
                ruleId = ruleId,
                ruleName = ruleName,
                severity = Severity.HIGH,
                affectedPaymentIds = listOf(payment.id),
                description = "Active unscheduled payment ${payment.id} (type=$typeName, amount=${payment.amount}, " +
                    "dueDate=${payment.dueDate}, CI=${payment.changeIndicator}) is unpaid. " +
                    "Explanation: An unscheduled payment is created when a customer pays early (pay-now) or the " +
                    "system initiates an early charge. It should be charged immediately. If it remains active but " +
                    "unpaid, the automatic charge system may try to charge it again, potentially causing an " +
                    "unintended charge.",
                evidence = mapOf(
                    "paymentId" to payment.id,
                    "paymentType" to payment.type,
                    "paymentTypeName" to typeName,
                    "amount" to payment.amount,
                    "dueDate" to payment.dueDate.toString(),
                    "changeIndicator" to payment.changeIndicator,
                    "creationDate" to (payment.creationDate?.toString() ?: "unknown"),
                ),
                suggestedRepairs = emptyList(),
            )
        }
    }
}
