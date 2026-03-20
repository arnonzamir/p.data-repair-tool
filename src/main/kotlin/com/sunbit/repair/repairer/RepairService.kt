package com.sunbit.repair.repairer

import com.sunbit.repair.analyzer.engine.PurchaseAnalyzer
import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.AdminApiClient
import com.sunbit.repair.loader.PurchaseLoaderService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service that executes repair actions against purchases via the Admin API.
 * Supports dry-run (preview), single-step execution, and multi-step repair plans
 * with verification gates between steps.
 */
@Service
class RepairService(
    private val adminApiClient: AdminApiClient,
    private val purchaseLoaderService: PurchaseLoaderService,
    private val purchaseAnalyzer: PurchaseAnalyzer,
) {
    private val log = LoggerFactory.getLogger(RepairService::class.java)

    /**
     * Preview what a repair action would do without executing it.
     * Only CHANGE_AMOUNT supports a real preview endpoint; all others
     * return supported=false with a description of the intended effect.
     */
    fun dryRun(request: RepairRequest): DryRunResult {
        log.info(
            "[RepairService][dryRun] purchaseId={} actionType={} reason={}",
            request.purchaseId, request.actionType, request.reason,
        )

        return try {
            when (request) {
                is RepairRequest.ChangeAmount -> {
                    val preview = adminApiClient.previewAmountChange(request.purchaseId, request.newAmount)
                    DryRunResult(
                        purchaseId = request.purchaseId,
                        request = request,
                        supported = true,
                        preview = preview,
                        description = "Change amount to ${request.newAmount}. Preview shows projected payment schedule.",
                    )
                }
                is RepairRequest.UnpayWithRefund -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would mark payment ${request.paymentId} as unpaid and issue a refund to the customer's card.",
                )
                is RepairRequest.UnpayWithoutRefund -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would mark payment ${request.paymentId} as unpaid without issuing a refund.",
                )
                is RepairRequest.ReversalOfAdjustment -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would reverse the last amount adjustment on purchase ${request.purchaseId}.",
                )
                is RepairRequest.ChangeApr -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would change the APR to ${request.newApr} and recalculate the payment schedule.",
                )
                is RepairRequest.CreateWorkout -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would create a workout plan with params: ${request.workoutParams}.",
                )
                is RepairRequest.CreateSettlement -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would create a settlement for amount ${request.settlementAmount}.",
                )
                is RepairRequest.CancelPurchase -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would cancel purchase ${request.purchaseId} with reason: ${request.cancellationReason}.",
                )
                is RepairRequest.RestoreCancellation -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would restore previously cancelled purchase ${request.purchaseId}.",
                )
                is RepairRequest.ReverseChargeback -> DryRunResult(
                    purchaseId = request.purchaseId,
                    request = request,
                    supported = false,
                    preview = null,
                    description = "Would reverse the chargeback on purchase ${request.purchaseId}.",
                )
            }
        } catch (ex: Exception) {
            log.error(
                "[RepairService][dryRun] Failed for purchaseId={} actionType={}: {}",
                request.purchaseId, request.actionType, ex.message, ex,
            )
            DryRunResult(
                purchaseId = request.purchaseId,
                request = request,
                supported = false,
                preview = null,
                description = "Dry run failed: ${ex.message}",
            )
        }
    }

    /**
     * Execute a single repair action. Takes a snapshot before and after the action,
     * then runs analysis to verify the repair's effect.
     */
    fun execute(request: RepairRequest): RepairResult {
        log.info(
            "[RepairService][execute] purchaseId={} actionType={} reason={}",
            request.purchaseId, request.actionType, request.reason,
        )

        val snapshotBefore = loadSnapshotSafely(request.purchaseId, "pre-repair")

        var httpStatus: Int? = null
        var responseBody: String? = null
        var success = false
        var error: String? = null

        try {
            val apiResponse = dispatchToAdminApi(request)
            // AdminApiClient returns Map<String, Any> on success.
            // If we reached here without exception, the call succeeded (HTTP 2xx).
            httpStatus = 200
            responseBody = apiResponse.toString()
            success = true
            log.info(
                "[RepairService][execute] Admin API call succeeded for purchaseId={} actionType={}",
                request.purchaseId, request.actionType,
            )
        } catch (ex: org.springframework.web.reactive.function.client.WebClientResponseException) {
            httpStatus = ex.statusCode.value()
            responseBody = ex.responseBodyAsString
            success = false
            error = "Admin API returned HTTP $httpStatus: $responseBody"
            log.warn(
                "[RepairService][execute] Non-success response for purchaseId={} actionType={}: HTTP {}",
                request.purchaseId, request.actionType, httpStatus,
            )
        } catch (ex: Exception) {
            success = false
            error = "Admin API call failed: ${ex.message}"
            log.error(
                "[RepairService][execute] Admin API call threw exception for purchaseId={} actionType={}: {}",
                request.purchaseId, request.actionType, ex.message, ex,
            )
        }

        val snapshotAfter = loadSnapshotSafely(request.purchaseId, "post-repair")

        val verificationResult = if (snapshotAfter != null) {
            try {
                purchaseAnalyzer.analyze(snapshotAfter)
            } catch (ex: Exception) {
                log.warn(
                    "[RepairService][execute] Post-repair analysis failed for purchaseId={}: {}",
                    request.purchaseId, ex.message,
                )
                null
            }
        } else {
            null
        }

        return RepairResult(
            purchaseId = request.purchaseId,
            request = request,
            executedAt = Instant.now(),
            success = success,
            httpStatus = httpStatus,
            responseBody = responseBody,
            error = error,
            snapshotBefore = snapshotBefore,
            snapshotAfter = snapshotAfter,
            verificationResult = verificationResult,
        )
    }

    /**
     * Execute a multi-step repair plan in order. After each step that has
     * verifyAfter=true, the purchase is re-loaded and re-analyzed. If new
     * CRITICAL findings appear that were not present before the step, the
     * plan is aborted.
     */
    fun executePlan(plan: RepairPlan): RepairPlanExecution {
        log.info(
            "[RepairService][executePlan] purchaseId={} steps={} description={}",
            plan.purchaseId, plan.steps.size, plan.description,
        )

        val startedAt = Instant.now()
        val stepResults = mutableListOf<RepairStepResult>()
        var abortedAtStep: Int? = null
        var abortReason: String? = null

        // Capture the initial set of critical finding rule IDs so we only detect NEW criticals
        val initialCriticalRuleIds = captureInitialCriticals(plan.purchaseId)

        for (step in plan.steps.sortedBy { it.order }) {
            log.info(
                "[RepairService][executePlan] Executing step {} of {} for purchaseId={}: {}",
                step.order, plan.steps.size, plan.purchaseId, step.request.actionType,
            )

            val result = execute(step.request)
            var verificationPassed: Boolean? = null

            if (!result.success) {
                abortedAtStep = step.order
                abortReason = "Step ${step.order} failed: ${result.error}"
                log.error(
                    "[RepairService][executePlan] Aborting plan at step {} for purchaseId={}: step execution failed",
                    step.order, plan.purchaseId,
                )
                stepResults.add(RepairStepResult(step, result, verificationPassed))
                break
            }

            if (step.verifyAfter) {
                val verification = result.verificationResult
                if (verification != null) {
                    val newCriticals = verification.findings
                        .filter { it.severity == Severity.CRITICAL }
                        .filter { it.ruleId !in initialCriticalRuleIds }

                    verificationPassed = newCriticals.isEmpty()

                    if (!verificationPassed) {
                        abortedAtStep = step.order
                        abortReason = "New CRITICAL findings detected after step ${step.order}: " +
                            newCriticals.joinToString(", ") { "${it.ruleId}: ${it.description}" }
                        log.error(
                            "[RepairService][executePlan] Aborting plan at step {} for purchaseId={}: {}",
                            step.order, plan.purchaseId, abortReason,
                        )
                        stepResults.add(RepairStepResult(step, result, verificationPassed))
                        break
                    }
                } else {
                    verificationPassed = null
                    log.warn(
                        "[RepairService][executePlan] Verification unavailable after step {} for purchaseId={}",
                        step.order, plan.purchaseId,
                    )
                }
            }

            stepResults.add(RepairStepResult(step, result, verificationPassed))
        }

        val completedAt = Instant.now()

        log.info(
            "[RepairService][executePlan] Plan completed for purchaseId={}: {} of {} steps executed, aborted={}",
            plan.purchaseId, stepResults.size, plan.steps.size, abortedAtStep != null,
        )

        return RepairPlanExecution(
            plan = plan,
            startedAt = startedAt,
            completedAt = completedAt,
            stepResults = stepResults,
            abortedAtStep = abortedAtStep,
            abortReason = abortReason,
        )
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun loadSnapshotSafely(purchaseId: Long, phase: String): PurchaseSnapshot? {
        return try {
            purchaseLoaderService.loadPurchase(purchaseId)
        } catch (ex: Exception) {
            log.warn(
                "[RepairService][loadSnapshotSafely] Failed to load {} snapshot for purchaseId={}: {}",
                phase, purchaseId, ex.message,
            )
            null
        }
    }

    private fun captureInitialCriticals(purchaseId: Long): Set<String> {
        return try {
            val initialSnapshot = purchaseLoaderService.loadPurchase(purchaseId)
            val initialAnalysis = purchaseAnalyzer.analyze(initialSnapshot)
            initialAnalysis.findings
                .filter { it.severity == Severity.CRITICAL }
                .map { it.ruleId }
                .toSet()
        } catch (ex: Exception) {
            log.warn(
                "[RepairService][captureInitialCriticals] Failed to capture initial analysis for purchaseId={}: {}",
                purchaseId, ex.message,
            )
            emptySet()
        }
    }

    /**
     * Dispatch to the correct AdminApiClient method based on the request subtype.
     * AdminApiClient methods return Map<String, Any> and throw WebClientResponseException
     * on non-2xx HTTP responses.
     */
    private fun dispatchToAdminApi(request: RepairRequest): Map<String, Any> {
        return when (request) {
            is RepairRequest.UnpayWithRefund ->
                adminApiClient.unpayWithRefund(request.purchaseId, request.paymentId)
            is RepairRequest.UnpayWithoutRefund ->
                adminApiClient.unpayWithoutRefund(request.purchaseId, request.paymentId)
            is RepairRequest.ChangeAmount ->
                adminApiClient.changeAmount(request.purchaseId, request.newAmount)
            is RepairRequest.ReversalOfAdjustment ->
                adminApiClient.reversalOfAdjustment(request.purchaseId)
            is RepairRequest.ChangeApr ->
                adminApiClient.changeApr(request.purchaseId, request.newApr)
            is RepairRequest.CreateWorkout ->
                adminApiClient.createWorkout(request.purchaseId, request.workoutParams)
            is RepairRequest.CreateSettlement ->
                adminApiClient.createSettlement(request.purchaseId, request.settlementAmount)
            is RepairRequest.CancelPurchase ->
                adminApiClient.cancelPurchase(request.purchaseId, request.cancellationReason)
            is RepairRequest.RestoreCancellation ->
                adminApiClient.restoreCancellation(request.purchaseId)
            is RepairRequest.ReverseChargeback ->
                adminApiClient.reverseChargeback(request.purchaseId)
        }
    }
}
