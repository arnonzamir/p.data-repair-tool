package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.analyzer.engine.AnalysisRule
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A10 -- MEDIUM: Detects inconsistencies between the CPP (Chosen Payment Plan) status and
 * the actual state of payments:
 *  - CPP=PAID_OFF but active unpaid scheduled payments still exist
 *  - CPP=ON_SCHEDULE but all active scheduled payments are paid (should be PAID_OFF)
 */
@Component
class CppStatusInconsistencyRule : AnalysisRule {

    private val log = LoggerFactory.getLogger(CppStatusInconsistencyRule::class.java)

    override val ruleId = "cpp-status-inconsistency"
    override val ruleName = "CPP Status Inconsistency Detection"
    override val description = "Detects mismatches between CPP status and actual payment states"
    override val detailedDescription = """
        The ChosenPaymentPlan (CPP) status is the loan's overall status as seen by the customer and reporting systems. It should match the actual payment states: PAID_OFF means all payments are paid, ON_SCHEDULE means there are unpaid payments being charged on time. This rule detects two inconsistencies: (1) CPP says PAID_OFF but there are still active unpaid scheduled payments, and (2) CPP says ON_SCHEDULE but all active scheduled payments are already paid. This usually happens when a charge or mutation succeeded but the status update failed.

        Detection: Compares cppStatus against the count of active unpaid scheduled payments.
    """.trimIndent()

    override fun analyze(snapshot: PurchaseSnapshot): List<Finding> {
        log.debug("[CppStatusInconsistencyRule][analyze] Checking purchaseId={}", snapshot.purchaseId)

        val findings = mutableListOf<Finding>()

        // Active scheduled payments (type=0, excluding down payments type=30)
        val activeScheduled = snapshot.payments.filter { it.isActive && it.type == 0 }
        val activeUnpaidScheduled = activeScheduled.filter { it.paidOffDate == null }
        val activePaidScheduled = activeScheduled.filter { it.paidOffDate != null }

        // Case 1: CPP=PAID_OFF but active unpaid scheduled payments exist
        if (snapshot.cppStatus == CppStatus.PAID_OFF && activeUnpaidScheduled.isNotEmpty()) {
            log.info(
                "[CppStatusInconsistencyRule][analyze] CPP=PAID_OFF but {} active unpaid scheduled payments " +
                    "exist for purchaseId={}",
                activeUnpaidScheduled.size, snapshot.purchaseId
            )

            val paymentDetails = activeUnpaidScheduled.joinToString("; ") { p ->
                "paymentId=${p.id}, amount=${p.amount}, dueDate=${p.dueDate}"
            }

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.MEDIUM,
                    affectedPaymentIds = activeUnpaidScheduled.map { it.id },
                    description = "CPP status is PAID_OFF but ${activeUnpaidScheduled.size} active unpaid " +
                        "scheduled payments exist: [$paymentDetails]. " +
                        "Explanation: The CPP (Chosen Payment Plan) status is the high-level status of the loan " +
                        "as seen by the customer and internal systems. PAID_OFF means the loan is considered " +
                        "fully paid. However, there are still active unpaid installments on the schedule, which " +
                        "means the loan is not actually fully paid. This mismatch can cause the automatic charge " +
                        "system to skip payments it should be collecting, or cause incorrect reporting. The CPP " +
                        "status should be recalculated to reflect the true state of the loan.",
                    evidence = mapOf(
                        "cppStatus" to snapshot.cppStatus.name,
                        "activeUnpaidScheduledCount" to activeUnpaidScheduled.size,
                        "activeUnpaidPaymentIds" to activeUnpaidScheduled.map { it.id },
                        "activeUnpaidAmounts" to activeUnpaidScheduled.map { it.amount },
                    ),
                    suggestedRepairs = listOf(
                        SuggestedRepair(
                            action = RepairActionType.CHANGE_AMOUNT,
                            description = "Recalculate CPP status to reflect actual payment states " +
                                "(recalculate-cpp-status)",
                            parameters = mapOf(
                                "purchaseId" to snapshot.purchaseId,
                                "suggestedAction" to "recalculate-cpp-status",
                            ),
                            supportsDryRun = false,
                        )
                    ),
                )
            )
        }

        // Case 2: CPP=ON_SCHEDULE but all active scheduled are paid (should be PAID_OFF)
        if (snapshot.cppStatus == CppStatus.ON_SCHEDULE &&
            activeScheduled.isNotEmpty() &&
            activeUnpaidScheduled.isEmpty() &&
            activePaidScheduled.isNotEmpty()
        ) {
            log.info(
                "[CppStatusInconsistencyRule][analyze] CPP=ON_SCHEDULE but all {} active scheduled payments " +
                    "are paid for purchaseId={}",
                activePaidScheduled.size, snapshot.purchaseId
            )

            val paymentDetails = activePaidScheduled.joinToString("; ") { p ->
                "paymentId=${p.id}, amount=${p.amount}, dueDate=${p.dueDate}, paidOffDate=${p.paidOffDate}"
            }

            findings.add(
                Finding(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    severity = Severity.MEDIUM,
                    affectedPaymentIds = activePaidScheduled.map { it.id },
                    description = "CPP status is ON_SCHEDULE but all ${activePaidScheduled.size} active " +
                        "scheduled payments are already paid: [$paymentDetails]. Status should be PAID_OFF. " +
                        "Explanation: The CPP (Chosen Payment Plan) status is the high-level status of the loan. " +
                        "ON_SCHEDULE means the system believes the loan is still being repaid with future " +
                        "installments due. However, every active scheduled payment has already been paid, meaning " +
                        "the loan is actually fully paid off. This mismatch can prevent the customer from seeing " +
                        "a 'Paid Off' status in their account, and may cause unnecessary late-payment notifications " +
                        "or collection actions. The CPP status should be recalculated.",
                    evidence = mapOf(
                        "cppStatus" to snapshot.cppStatus.name,
                        "activePaidScheduledCount" to activePaidScheduled.size,
                        "activePaidPaymentIds" to activePaidScheduled.map { it.id },
                    ),
                    suggestedRepairs = listOf(
                        SuggestedRepair(
                            action = RepairActionType.CHANGE_AMOUNT,
                            description = "Recalculate CPP status to reflect actual payment states " +
                                "(recalculate-cpp-status)",
                            parameters = mapOf(
                                "purchaseId" to snapshot.purchaseId,
                                "suggestedAction" to "recalculate-cpp-status",
                            ),
                            supportsDryRun = false,
                        )
                    ),
                )
            )
        }

        return findings
    }
}
