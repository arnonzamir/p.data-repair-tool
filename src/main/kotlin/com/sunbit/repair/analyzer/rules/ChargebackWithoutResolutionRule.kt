package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * MEDIUM: Finds active payments in CHARGEBACK status where no
 * REVERSE_CHARGEBACK charge transaction exists after the chargeback event.
 *
 * An unresolved chargeback means the dispute was never closed and the
 * payment is stuck in a limbo state.
 */
@Component
class ChargebackWithoutResolutionRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(ChargebackWithoutResolutionRule::class.java)

    override val ruleId = "chargeback-without-resolution"
    override val ruleName = "Chargeback Without Resolution"
    override val description = "Finds chargebacked payments with no subsequent reverse-chargeback transaction"

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[ChargebackWithoutResolutionRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()

        val reverseChargebackExists = snapshot.chargeTransactions.any { ct ->
            ct.typeName == ChargeTransactionType.REVERSE_CHARGEBACK
        }

        for (payment in snapshot.payments) {
            if (!payment.isActive) continue
            if (payment.chargeBack == null) continue

            if (!reverseChargebackExists) {
                findings.add(
                    Finding(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        severity = Severity.MEDIUM,
                        affectedPaymentIds = listOf(payment.id),
                        description = "Payment ${payment.id} has chargeBack='${payment.chargeBack}' " +
                            "but no REVERSE_CHARGEBACK transaction exists for this purchase.",
                        evidence = mapOf(
                            "paymentId" to payment.id,
                            "chargeBack" to (payment.chargeBack ?: ""),
                            "chargeBackEnhancement" to (payment.chargeBackEnhancement ?: ""),
                            "amount" to payment.amount,
                            "dueDate" to payment.dueDate.toString(),
                            "reverseChargebackTransactions" to 0,
                        ),
                        suggestedRepairs = listOf(
                            SuggestedRepair(
                                action = RepairActionType.REVERSE_CHARGEBACK,
                                description = "Execute ReverseChargeback if the dispute has been resolved in our favor",
                                parameters = mapOf(
                                    "purchaseId" to snapshot.purchaseId,
                                ),
                                supportsDryRun = false,
                            )
                        ),
                    )
                )
            }
        }

        return findings
    }
}
