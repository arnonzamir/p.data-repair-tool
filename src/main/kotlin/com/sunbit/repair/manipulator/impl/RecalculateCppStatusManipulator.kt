package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Triggers CPP status recalculation when the ChosenPaymentPlan status is
 * out of sync with reality (e.g., shows ON_SCHEDULE but has late payments,
 * or shows LATE but all payments are paid).
 */
@Component
class RecalculateCppStatusManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(RecalculateCppStatusManipulator::class.java)

    override val manipulatorId = "recalculate-cpp-status"
    override val name = "Recalculate CPP Status"
    override val description = "Trigger ChosenPaymentPlan status recalculation when status is out of sync"
    override val detailedDescription = """
        The ChosenPaymentPlan (CPP) status is the loan's overall status visible to customers and reporting systems (On_Schedule, Late, Paid_Off, etc.). Sometimes it gets out of sync with the actual payment states -- for example, showing On_Schedule when all payments are paid, or showing Late when the customer caught up. This manipulator calls the onschedule endpoint to trigger a fresh status calculation based on current payment data.
    """.trimIndent()
    override val category = ManipulatorCategory.FINANCIAL

    override val requiredParams: List<ParamSpec> = emptyList()

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        // Always applicable -- we can't fully detect status mismatch from Snowflake alone
        // since CPP status comes from the live DB
        return ApplicabilityResult(
            canApply = true,
            reason = "Current CPP status: ${snapshot.cppStatus}. Recalculation available.",
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will trigger CPP status recalculation. Current status: ${snapshot.cppStatus}",
            steps = listOf(
                PreviewStep(1, "RECALCULATE_CPP_STATUS", "Call onschedule endpoint to recalculate CPP status"),
            ),
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        log.info("[RecalculateCppStatusManipulator][execute] Recalculating CPP status for purchase {} on {}", snapshot.purchaseId, target)

        return try {
            val response = adminApiClient.recalculateCppStatus(snapshot.purchaseId, target)
            ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), true,
                listOf(ExecutionStep(1, "RECALCULATE_CPP_STATUS", true, response)))
        } catch (ex: Exception) {
            log.error("[RecalculateCppStatusManipulator][execute] Failed: {}", ex.message, ex)
            ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false,
                listOf(ExecutionStep(1, "RECALCULATE_CPP_STATUS", false, error = ex.message)),
                error = ex.message)
        }
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val changed = before.cppStatus != after.cppStatus
        return VerificationResult(
            passed = true, // Status recalculation itself always "passes"
            reason = if (changed) {
                "CPP status changed: ${before.cppStatus} -> ${after.cppStatus}"
            } else {
                "CPP status unchanged: ${after.cppStatus}"
            },
        )
    }
}
