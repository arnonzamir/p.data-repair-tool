package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * CRITICAL: Detects active payments whose direct parent is also active.
 *
 * This is the "ghost payment" pattern caused by reactivation bugs during DB
 * failures. When a payNow or changeAmount flow partially fails and retries,
 * the original payment may be reactivated while the child already exists,
 * leaving two active payments for the same slot.
 */
@Component
class GhostPaymentRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(GhostPaymentRule::class.java)

    override val ruleId = "ghost-payment"
    override val ruleName = "Ghost Payment Detection"
    override val description = "Detects active payments whose direct parent is also active (reactivation bug)"

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[GhostPaymentRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val activeById = snapshot.payments
            .filter { it.isActive }
            .associateBy { it.id }

        val findings = mutableListOf<Finding>()

        for (payment in activeById.values) {
            val parentId = payment.directParentId ?: continue
            val parent = activeById[parentId] ?: continue

            // Both parent and child are active -- this is a ghost
            val childHasCharge = snapshot.chargeTransactions.any { ct ->
                ct.paymentProfileId == payment.paymentProfileId &&
                    ct.typeName in listOf(
                        ChargeTransactionType.SCHEDULED_PAYMENT,
                        ChargeTransactionType.UNSCHEDULED_PAYMENT,
                    )
            }

            val suggestedAction = if (childHasCharge) {
                SuggestedRepair(
                    action = RepairActionType.UNPAY_WITH_REFUND,
                    description = "Ghost payment has been charged -- unpay with refund on the ghost parent",
                    parameters = mapOf(
                        "purchaseId" to snapshot.purchaseId,
                        "paymentId" to parent.id,
                    ),
                    supportsDryRun = false,
                )
            } else {
                SuggestedRepair(
                    action = RepairActionType.UNPAY_WITHOUT_REFUND,
                    description = "Ghost parent has no money movement -- unpay without refund",
                    parameters = mapOf(
                        "purchaseId" to snapshot.purchaseId,
                        "paymentId" to parent.id,
                    ),
                    supportsDryRun = false,
                )
            }

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.CRITICAL,
                    affectedPaymentIds = listOf(parent.id, payment.id),
                    description = "Active payment ${payment.id} has active parent ${parent.id}. " +
                        "Both are active, indicating a ghost from a failed mutation retry.",
                    evidence = mapOf(
                        "childPaymentId" to payment.id,
                        "parentPaymentId" to parent.id,
                        "childAmount" to payment.amount,
                        "parentAmount" to parent.amount,
                        "childDueDate" to payment.dueDate.toString(),
                        "parentDueDate" to parent.dueDate.toString(),
                        "childChangeIndicator" to payment.changeIndicator,
                        "parentChangeIndicator" to parent.changeIndicator,
                        "childHasCharge" to childHasCharge,
                    ),
                    suggestedRepairs = listOf(suggestedAction),
                )
            )
        }

        return findings
    }
}
