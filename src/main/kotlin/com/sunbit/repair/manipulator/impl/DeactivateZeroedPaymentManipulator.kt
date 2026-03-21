package com.sunbit.repair.manipulator.impl

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.manipulator.LoanManipulator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * Deactivates active payments with zero or negative amounts that are artifacts
 * of failed mutations. These payments serve no purpose and may confuse the
 * charge system.
 *
 * IMPORTANT: This manipulator removes the payment from the active schedule
 * without any rebalance. The impact on downstream payment plan calculations
 * must be verified before use. If the zeroed payment was supposed to carry
 * a real amount, use the feb28-group2-waive-zeroed manipulator instead.
 */
@Component
class DeactivateZeroedPaymentManipulator(
    private val adminApiClient: AdminApiClient,
) : LoanManipulator {

    private val log = LoggerFactory.getLogger(DeactivateZeroedPaymentManipulator::class.java)

    override val manipulatorId = "deactivate-zeroed-payment"
    override val name = "Deactivate Zero-Amount Payment"
    override val description = "Deactivate active payments with zero or negative amounts (mutation artifacts)"
    override val detailedDescription = """
        Deactivates active payments that have zero or negative amounts. These are typically artifacts of failed mutations where the system zeroed out a payment but never deactivated it or restored its amount. Unlike the feb28-group2-waive-zeroed manipulator (which restores the original amount and waives), this simply removes the payment from the active schedule. IMPORTANT: This does not rebalance the schedule afterward. The impact on downstream payment plan calculations must be verified before use. If the payment was supposed to carry a real amount, use the feb28-group2-waive-zeroed manipulator instead.
    """.trimIndent()
    override val category = ManipulatorCategory.STRUCTURAL

    override val requiredParams = listOf(
        ParamSpec(
            name = "paymentId",
            type = ParamType.PAYMENT_ID,
            required = false,
            description = "Specific zeroed payment to deactivate. If omitted, deactivates all detected.",
        ),
    )

    override fun canApply(snapshot: PurchaseSnapshot): ApplicabilityResult {
        val zeroed = findZeroedPayments(snapshot)
        if (zeroed.isEmpty()) {
            return ApplicabilityResult(canApply = false, reason = "No active zero-amount payments detected")
        }
        return ApplicabilityResult(
            canApply = true,
            reason = "${zeroed.size} active zero-amount payment(s): ${zeroed.map { "${it.id} (due ${it.dueDate}, CI=${it.changeIndicator})" }}",
            suggestedParams = if (zeroed.size == 1) mapOf("paymentId" to zeroed.first().id) else emptyMap(),
        )
    }

    override fun preview(snapshot: PurchaseSnapshot, params: Map<String, Any>): ManipulatorPreview {
        val zeroed = findZeroedPayments(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) zeroed.filter { it.id == targetId } else zeroed

        return ManipulatorPreview(
            manipulatorId = manipulatorId,
            purchaseId = snapshot.purchaseId,
            supported = true,
            description = "Will deactivate ${targets.size} zero-amount payment(s) via unpay-without-refund",
            steps = targets.mapIndexed { idx, p ->
                PreviewStep(
                    order = idx + 1,
                    action = "UNPAY_WITHOUT_REFUND",
                    description = "Deactivate payment ${p.id} (amount=${p.amount}, due ${p.dueDate}, CI=${p.changeIndicatorName ?: p.changeIndicator})",
                    affectedPaymentIds = listOf(p.id),
                )
            },
            warnings = listOf(
                "IMPORTANT: Impact on downstream payment plan must be verified before use.",
                "This removes the payment from the active schedule without rebalancing.",
                "If this payment was supposed to carry a real amount (e.g., Feb28 incident), use the feb28-group2-waive-zeroed manipulator instead.",
            ),
        )
    }

    override fun execute(snapshot: PurchaseSnapshot, params: Map<String, Any>, target: String): ManipulatorExecutionResult {
        val zeroed = findZeroedPayments(snapshot)
        val targetId = (params["paymentId"] as? Number)?.toLong()
        val targets = if (targetId != null) zeroed.filter { it.id == targetId } else zeroed

        val executionSteps = mutableListOf<ExecutionStep>()

        for (payment in targets) {
            try {
                val response = adminApiClient.unpayWithoutRefund(snapshot.purchaseId, payment.id, target)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "UNPAY_WITHOUT_REFUND", true, response))
                log.info("[DeactivateZeroedPaymentManipulator][execute] Deactivated zero-amount payment {}", payment.id)
            } catch (ex: Exception) {
                log.error("[DeactivateZeroedPaymentManipulator][execute] Failed to deactivate {}: {}", payment.id, ex.message, ex)
                executionSteps.add(ExecutionStep(executionSteps.size + 1, "UNPAY_WITHOUT_REFUND", false, error = ex.message))
                return ManipulatorExecutionResult(manipulatorId, snapshot.purchaseId, Instant.now(), false, executionSteps,
                    "Failed to deactivate payment ${payment.id}: ${ex.message}")
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
                reason = "All ${zeroedBefore.size} zero-amount payment(s) deactivated. Verify payment plan impact manually.",
                resolvedFindings = resolved.map { "Zero-amount payment $it deactivated" },
            )
        } else {
            VerificationResult(
                passed = false,
                reason = "${zeroedAfter.size} zero-amount payment(s) remain: ${zeroedAfter.map { it.id }}",
                resolvedFindings = resolved.map { "Zero-amount payment $it deactivated" },
            )
        }
    }

    private fun findZeroedPayments(snapshot: PurchaseSnapshot): List<Payment> {
        return snapshot.payments.filter { p ->
            p.isActive &&
                p.amount <= BigDecimal.ZERO &&
                p.type != 30 // exclude down payments
        }
    }
}
