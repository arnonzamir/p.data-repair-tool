package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * HIGH: Finds two or more PAID active scheduled payments (type=0) that share
 * the same dueDate. This indicates a double charge where the customer was
 * billed twice for the same installment slot.
 */
@Component
class DuplicateChargeRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(DuplicateChargeRule::class.java)

    override val ruleId = "duplicate-charge"
    override val ruleName = "Duplicate Charge Detection"
    override val description = "Finds multiple paid active scheduled payments with the same due date"

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[DuplicateChargeRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val paidScheduled = snapshot.payments.filter { p ->
            p.isActive &&
                p.computedStatus == PaymentStatus.PAID &&
                p.type == PaymentType.SCHEDULED.code
        }

        val byDueDate = paidScheduled.groupBy { it.dueDate }
        val findings = mutableListOf<Finding>()

        for ((dueDate, payments) in byDueDate) {
            if (payments.size < 2) continue

            val paymentIds = payments.map { it.id }
            val amounts = payments.map { it.amount }

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.HIGH,
                    affectedPaymentIds = paymentIds,
                    description = "Found ${payments.size} paid active scheduled payments " +
                        "with dueDate=$dueDate: $paymentIds. Customer may have been double-charged.",
                    evidence = mapOf(
                        "dueDate" to dueDate.toString(),
                        "paymentIds" to paymentIds,
                        "amounts" to amounts,
                        "duplicateCount" to payments.size,
                    ),
                    suggestedRepairs = payments.drop(1).map { dup ->
                        SuggestedRepair(
                            action = RepairActionType.UNPAY_WITH_REFUND,
                            description = "Unpay duplicate payment ${dup.id} with refund",
                            parameters = mapOf(
                                "purchaseId" to snapshot.purchaseId,
                                "paymentId" to dup.id,
                            ),
                            supportsDryRun = false,
                        )
                    },
                )
            )
        }

        return findings
    }
}
