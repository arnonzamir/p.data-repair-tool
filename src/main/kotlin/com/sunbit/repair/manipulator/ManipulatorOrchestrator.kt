package com.sunbit.repair.manipulator

import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.PurchaseCacheService
import com.sunbit.repair.loader.PurchaseLoaderService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Orchestrates manipulator execution: single runs and multi-step flows.
 * Uses cached snapshots for read operations (applicability, preview).
 * Only loads fresh from Snowflake when executing and verifying.
 */
@Service
class ManipulatorOrchestrator(
    private val registry: ManipulatorRegistry,
    private val loaderService: PurchaseLoaderService,
    private val cacheService: PurchaseCacheService,
) {
    private val log = LoggerFactory.getLogger(ManipulatorOrchestrator::class.java)

    /**
     * Check which manipulators can apply to a given purchase.
     */
    fun getApplicable(purchaseId: Long): List<ApplicableManipulator> {
        log.info("[ManipulatorOrchestrator][getApplicable] Checking applicability for purchase {}", purchaseId)
        val snapshot = getCachedOrLoad(purchaseId)
        return registry.getEnabled().mapNotNull { m ->
            try {
                val result = m.canApply(snapshot)
                ApplicableManipulator(
                    manipulatorId = m.manipulatorId,
                    name = m.name,
                    category = m.category,
                    applicability = result,
                )
            } catch (ex: Exception) {
                log.warn(
                    "[ManipulatorOrchestrator][getApplicable] Manipulator '{}' threw during canApply for purchase {}: {}",
                    m.manipulatorId, purchaseId, ex.message,
                )
                null
            }
        }.filter { it.applicability.canApply }
    }

    /**
     * Preview what a manipulator would do.
     */
    fun preview(purchaseId: Long, manipulatorId: String, params: Map<String, Any>): ManipulatorPreview {
        log.info(
            "[ManipulatorOrchestrator][preview] purchaseId={} manipulatorId={}",
            purchaseId, manipulatorId,
        )
        val manipulator = registry.getById(manipulatorId)
        val snapshot = getCachedOrLoad(purchaseId)
        return manipulator.preview(snapshot, params)
    }

    /**
     * Execute a single manipulator: load -> canApply -> execute -> reload -> verify.
     * @param target Which environment to execute against (LOCAL, STAGING, PROD).
     */
    fun execute(
        purchaseId: Long,
        manipulatorId: String,
        params: Map<String, Any>,
        target: String = "LOCAL",
    ): ManipulatorRunResult {
        log.info(
            "[ManipulatorOrchestrator][execute] purchaseId={} manipulatorId={} params={}",
            purchaseId, manipulatorId, params,
        )
        val manipulator = registry.getById(manipulatorId)
        val snapshotBefore = getCachedOrLoad(purchaseId)

        // Pre-check
        val applicability = manipulator.canApply(snapshotBefore)
        if (!applicability.canApply) {
            log.warn(
                "[ManipulatorOrchestrator][execute] Manipulator '{}' not applicable for purchase {}: {}",
                manipulatorId, purchaseId, applicability.reason,
            )
            return ManipulatorRunResult(
                purchaseId = purchaseId,
                manipulatorId = manipulatorId,
                applicability = applicability,
                execution = null,
                verification = null,
            )
        }

        // Execute
        val execution = manipulator.execute(snapshotBefore, params, target)

        // Reload and verify
        val verification = if (execution.success) {
            try {
                val snapshotAfter = loaderService.loadPurchase(purchaseId)
                manipulator.verify(snapshotBefore, snapshotAfter)
            } catch (ex: Exception) {
                log.warn(
                    "[ManipulatorOrchestrator][execute] Post-execution verify failed for purchase {}: {}",
                    purchaseId, ex.message,
                )
                VerificationResult(
                    passed = false,
                    reason = "Failed to reload purchase for verification: ${ex.message}",
                )
            }
        } else {
            null
        }

        log.info(
            "[ManipulatorOrchestrator][execute] Completed purchaseId={} manipulatorId={}: success={} verified={}",
            purchaseId, manipulatorId, execution.success, verification?.passed,
        )

        return ManipulatorRunResult(
            purchaseId = purchaseId,
            manipulatorId = manipulatorId,
            applicability = applicability,
            execution = execution,
            verification = verification,
        )
    }

    /**
     * Execute a multi-manipulator flow with verification gates.
     * @param target Which environment to execute against (LOCAL, STAGING, PROD).
     */
    fun executeFlow(flow: ManipulatorFlow, target: String = "LOCAL"): FlowResult {
        log.info(
            "[ManipulatorOrchestrator][executeFlow] purchaseId={} steps={} description={}",
            flow.purchaseId, flow.steps.size, flow.description,
        )
        val startedAt = Instant.now()
        val stepResults = mutableListOf<FlowStepResult>()
        var abortedAtStep: Int? = null
        var abortReason: String? = null

        for (step in flow.steps.sortedBy { it.order }) {
            log.info(
                "[ManipulatorOrchestrator][executeFlow] Step {}/{}: manipulatorId={}",
                step.order, flow.steps.size, step.manipulatorId,
            )

            val manipulator = registry.getById(step.manipulatorId)
            val snapshot = getCachedOrLoad(flow.purchaseId)

            // Check applicability
            val applicability = manipulator.canApply(snapshot)
            if (!applicability.canApply) {
                if (step.skipIfNotApplicable) {
                    log.info(
                        "[ManipulatorOrchestrator][executeFlow] Skipping step {} (not applicable): {}",
                        step.order, applicability.reason,
                    )
                    stepResults.add(FlowStepResult(step, applicability, null, null))
                    continue
                } else {
                    abortedAtStep = step.order
                    abortReason = "Not applicable: ${applicability.reason}"
                    stepResults.add(FlowStepResult(step, applicability, null, null))
                    break
                }
            }

            // Execute
            val execution = manipulator.execute(snapshot, step.params, target)
            if (!execution.success) {
                abortedAtStep = step.order
                abortReason = "Execution failed: ${execution.error}"
                stepResults.add(FlowStepResult(step, applicability, execution, null))
                break
            }

            // Verify
            val verification = try {
                val snapshotAfter = loaderService.loadPurchase(flow.purchaseId)
                manipulator.verify(snapshot, snapshotAfter)
            } catch (ex: Exception) {
                VerificationResult(
                    passed = false,
                    reason = "Verification failed: ${ex.message}",
                )
            }

            stepResults.add(FlowStepResult(step, applicability, execution, verification))

            if (!verification.passed && step.abortOnVerifyFail) {
                abortedAtStep = step.order
                abortReason = "Verification failed after step ${step.order}: ${verification.reason}"
                break
            }
        }

        val completedAt = Instant.now()

        log.info(
            "[ManipulatorOrchestrator][executeFlow] Flow completed for purchaseId={}: {}/{} steps, aborted={}",
            flow.purchaseId, stepResults.size, flow.steps.size, abortedAtStep != null,
        )

        return FlowResult(
            purchaseId = flow.purchaseId,
            startedAt = startedAt,
            completedAt = completedAt,
            stepResults = stepResults,
            abortedAtStep = abortedAtStep,
            abortReason = abortReason,
        )
    }

    /**
     * Use cached snapshot if available, otherwise load from Snowflake.
     * For read-only operations (applicability, preview) the cache is sufficient.
     */
    private fun getCachedOrLoad(purchaseId: Long): PurchaseSnapshot {
        return try {
            val cached = cacheService.getSnapshot(purchaseId, forceRefresh = false)
            if (cached != null) {
                log.debug("[ManipulatorOrchestrator][getCachedOrLoad] Using cached snapshot for purchase {}", purchaseId)
                cached.snapshot
            } else {
                log.info("[ManipulatorOrchestrator][getCachedOrLoad] No cache for purchase {}, loading from Snowflake", purchaseId)
                loaderService.loadPurchase(purchaseId)
            }
        } catch (e: Exception) {
            log.info("[ManipulatorOrchestrator][getCachedOrLoad] Cache miss for purchase {}, loading from Snowflake", purchaseId)
            loaderService.loadPurchase(purchaseId)
        }
    }
}

data class ApplicableManipulator(
    val manipulatorId: String,
    val name: String,
    val category: ManipulatorCategory,
    val applicability: ApplicabilityResult,
)

data class ManipulatorRunResult(
    val purchaseId: Long,
    val manipulatorId: String,
    val applicability: ApplicabilityResult,
    val execution: ManipulatorExecutionResult?,
    val verification: VerificationResult?,
) {
    val success: Boolean get() = execution?.success == true
    val verified: Boolean get() = verification?.passed == true
}
