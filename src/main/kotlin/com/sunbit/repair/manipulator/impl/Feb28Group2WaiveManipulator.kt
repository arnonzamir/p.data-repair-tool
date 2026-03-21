package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.loader.SnowflakeLoader
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Feb28 Group 2 remediation: fixes payments that were zeroed to $0 by the
 * early-charge DB failure incident (Feb 27 - Mar 4 2026).
 *
 * The fix restores the original amount from the audit trail, then waives the
 * payment (marks as paid without charging -- forgiving it to the customer).
 * This mirrors the approach in the purchase-service PurchaseRemediation class.
 *
 * Steps:
 * 1. Find zeroed payment (amount=0, active, unpaid, scheduled, due in incident window)
 * 2. Look up original amount from PAYMENTS_AUD (REVTYPE=0)
 * 3. Restore amount via updatePayment
 * 4. Waive the payment via waivePayment
 * 5. Verify CPP status via recalculate
 */
@Component
class Feb28Group2WaiveManipulator(
    private val adminApiClient: AdminApiClient,
    private val snowflakeLoader: SnowflakeLoader,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(Feb28Group2WaiveManipulator::class.java)

    override val manipulatorId = "feb28-group2-waive-zeroed"
    override val name = "Feb28 Group 2: Waive Zeroed Payment"
    override val description = "Restore original amount from audit trail and waive a zeroed payment from the Feb 27 early-charge incident"
    override val detailedDescription = """
        Specific remediation for the Feb 27 - Mar 4, 2026 early-charge DB failure incident (Group 2). During this incident, the early-charge flow zeroed a February payment's amount to ${'$'}0 and committed it, but the subsequent charge failed due to DB pool exhaustion. The restoration step never executed, leaving the payment at ${'$'}0, active, and unpaid. This manipulator: (1) recovers the original payment amount from the Snowflake audit trail (PAYMENTS_AUD, REVTYPE=0), (2) restores the amount on the payment, (3) waives the payment (marks as paid without charging -- forgiving it to the customer per business decision), and (4) recalculates the CPP status. Only applies to payments with amount=0, active, unpaid, scheduled, due between Feb 27 and Mar 4 2026.
    """.trimIndent()
    override val category = ManipulatorCategory.REMEDIATION

    override val requiredParams = listOf(
        ParamSpec(
            name = "paymentId",
            type = ParamType.PAYMENT_ID,
            required = false,
            description = "Specific zeroed payment to fix. If omitted, auto-detects from incident window.",
        ),
        ParamSpec(
            name = "originalAmount",
            type = ParamType.AMOUNT,
            required = false,
            description = "Original amount to restore. If omitted, looks up from PAYMENTS_AUD audit trail.",
        ),
    )

    companion object {
        private val INCIDENT_START = LocalDate.of(2026, 2, 27)
        private val INCIDENT_END = LocalDate.of(2026, 3, 4)
    }

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val zeroed = findZeroedPayments(snapshot)
        if (zeroed.isEmpty()) {
            return ApplicabilityResult(canApply = false, reason = "No zeroed payments in the Feb 27 incident window")
        }
        return ApplicabilityResult(
            canApply = true,
            reason = "${zeroed.size} zeroed payment(s) in incident window: ${zeroed.map { "${it.id} (due ${it.dueDate})" }}",
            suggestedParams = if (zeroed.size == 1) mapOf("paymentId" to zeroed.first().id) else emptyMap(),
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val zeroed = findZeroedPayments(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) zeroed.filter { it.id == targetId } else zeroed

        val steps = mutableListOf<PreviewStep>()
        for ((idx, payment) in targets.withIndex()) {
            val originalAmount = (params["originalAmount"] as? Number)?.let { BigDecimal(it.toString()) }
            val amountDesc = if (originalAmount != null) "\$$originalAmount (provided)" else "from audit trail"

            steps.add(PreviewStep(idx * 3 + 1, "RESTORE_AMOUNT",
                "Restore payment ${payment.id} amount from \$0 to $amountDesc",
                listOf(payment.id)))
            steps.add(PreviewStep(idx * 3 + 2, "WAIVE_PAYMENT",
                "Waive payment ${payment.id} (mark as paid without charging, forgiven to customer)",
                listOf(payment.id)))
            steps.add(PreviewStep(idx * 3 + 3, "VERIFY_CPP_STATUS",
                "Recalculate CPP status after waiver"))
        }

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will restore original amount and waive ${targets.size} zeroed Feb28 payment(s)",
            steps = steps,
            warnings = listOf(
                "This forgives the payment to the customer. No money will be collected",
                "Original amount will be recovered from the Snowflake audit trail (PAYMENTS_AUD)",
            ),
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val zeroed = findZeroedPayments(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) zeroed.filter { it.id == targetId } else zeroed

        if (targets.isEmpty()) {
            return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, emptyList(),
                "No zeroed payments found to fix")
        }

        val executionSteps = mutableListOf<ExecutionStep>()

        for (payment in targets) {
            // Step 1: Get original amount
            val originalAmount = (params["originalAmount"] as? Number)?.let { BigDecimal(it.toString()) }
                ?: snowflakeLoader.loadOriginalPaymentAmount(payment.id)

            if (originalAmount == null || originalAmount <= BigDecimal.ZERO) {
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "RESTORE_AMOUNT", false,
                    error = "Cannot recover original amount for payment ${payment.id} from audit trail"))
                return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps,
                    "Original amount not recoverable for payment ${payment.id}")
            }

            log.info("[Feb28Group2WaiveManipulator][execute] Restoring payment {} to \${} on {}", payment.id, originalAmount, target)

            // Step 2: Restore amount
            try {
                val response = adminApiClient.updatePaymentAmount(payment.id, originalAmount, target)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "RESTORE_AMOUNT", true, response))
            } catch (ex: Exception) {
                log.error("[Feb28Group2WaiveManipulator][execute] Failed to restore amount for {}: {}", payment.id, ex.message, ex)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "RESTORE_AMOUNT", false, error = ex.message))
                return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps,
                    "Failed to restore amount: ${ex.message}")
            }

            // Step 3: Waive
            try {
                val response = adminApiClient.waivePayment(payment.id, target)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "WAIVE_PAYMENT", true, response))
                log.info("[Feb28Group2WaiveManipulator][execute] Waived payment {}", payment.id)
            } catch (ex: Exception) {
                log.error("[Feb28Group2WaiveManipulator][execute] Failed to waive {}: {}", payment.id, ex.message, ex)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "WAIVE_PAYMENT", false, error = ex.message))
                return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps,
                    "Amount restored but waive failed: ${ex.message}")
            }

            // Step 4: Verify CPP status
            try {
                val response = adminApiClient.recalculateCppStatus(snapshot.purchaseId, target)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "VERIFY_CPP_STATUS", true, response))
            } catch (ex: Exception) {
                log.warn("[Feb28Group2WaiveManipulator][execute] CPP recalc failed (non-fatal): {}", ex.message)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "VERIFY_CPP_STATUS", false, error = ex.message))
                // Non-fatal -- waive succeeded
            }
        }

        return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), true, executionSteps)
    }

    override fun verify(before: PurchaseSnapshot, after: PurchaseSnapshot): VerificationResult {
        val zeroedBefore = findZeroedPayments(before)
        val zeroedAfter = findZeroedPayments(after)

        val resolved = zeroedBefore.map { it.id }.filter { id -> zeroedAfter.none { it.id == id } }

        return if (zeroedAfter.isEmpty()) {
            VerificationResult(
                passed = true,
                reason = "All ${zeroedBefore.size} zeroed payment(s) fixed (waived)",
                resolvedFindings = resolved.map { "Zeroed payment $it waived" },
            )
        } else {
            VerificationResult(
                passed = false,
                reason = "${zeroedAfter.size} zeroed payment(s) remain: ${zeroedAfter.map { it.id }}",
                resolvedFindings = resolved.map { "Zeroed payment $it waived" },
            )
        }
    }

    private fun findZeroedPayments(snapshot: PurchaseSnapshot): List<Payment> {
        return snapshot.payments.filter { p ->
            p.isActive &&
                p.amount == BigDecimal.ZERO &&
                p.paidOffDate == null &&
                p.type == 0 &&
                p.dueDate >= INCIDENT_START &&
                p.dueDate <= INCIDENT_END
        }
    }
}
