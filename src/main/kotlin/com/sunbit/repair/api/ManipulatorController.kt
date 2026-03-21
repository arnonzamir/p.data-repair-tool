package com.sunbit.repair.api

import com.sunbit.repair.audit.AuditService
import com.sunbit.repair.domain.*
import com.sunbit.repair.manipulator.ManipulatorOrchestrator
import com.sunbit.repair.manipulator.ManipulatorRegistry
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/manipulators")
@CrossOrigin(origins = ["*"])
class ManipulatorController(
    private val registry: ManipulatorRegistry,
    private val orchestrator: ManipulatorOrchestrator,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(ManipulatorController::class.java)

    // -----------------------------------------------------------------------
    // Registry queries
    // -----------------------------------------------------------------------

    @GetMapping
    fun listManipulators(): List<Any> = registry.getInfo()

    @PutMapping("/toggle/{manipulatorId}/enable")
    fun enable(@PathVariable manipulatorId: String) {
        registry.enable(manipulatorId)
    }

    @PutMapping("/toggle/{manipulatorId}/disable")
    fun disable(@PathVariable manipulatorId: String) {
        registry.disable(manipulatorId)
    }

    @GetMapping("/{purchaseId}/applicable")
    fun getApplicable(@PathVariable purchaseId: Long) = orchestrator.getApplicable(purchaseId)

    // -----------------------------------------------------------------------
    // Preview
    // -----------------------------------------------------------------------

    @PostMapping("/{purchaseId}/preview")
    fun preview(
        @PathVariable purchaseId: Long,
        @RequestBody request: ManipulatorRequestDto,
    ): ManipulatorPreview {
        log.info(
            "[ManipulatorController][preview] purchaseId={} manipulatorId={}",
            purchaseId, request.manipulatorId,
        )
        return orchestrator.preview(purchaseId, request.manipulatorId, request.params)
    }

    // -----------------------------------------------------------------------
    // Execute single manipulator
    // -----------------------------------------------------------------------

    @PostMapping("/{purchaseId}/execute")
    fun execute(
        @PathVariable purchaseId: Long,
        @RequestBody request: ManipulatorRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
        @RequestHeader("X-Target", defaultValue = "PROD") target: String,
    ): Any {
        log.info(
            "[ManipulatorController][execute] purchaseId={} manipulatorId={} target={} operator={}",
            purchaseId, request.manipulatorId, target, operator,
        )

        val result = orchestrator.execute(purchaseId, request.manipulatorId, request.params, target)

        auditService.record(
            operator = operator,
            purchaseId = purchaseId,
            action = AuditAction.MANIPULATE,
            input = mapOf(
                "manipulatorId" to request.manipulatorId,
                "params" to request.params,
                "target" to target,
            ),
            output = mapOf(
                "success" to result.success,
                "verified" to result.verified,
                "stepsExecuted" to (result.execution?.stepsExecuted?.size ?: 0),
            ),
        )

        return result
    }

    // -----------------------------------------------------------------------
    // Execute flow (multi-step)
    // -----------------------------------------------------------------------

    @PostMapping("/{purchaseId}/execute-flow")
    fun executeFlow(
        @PathVariable purchaseId: Long,
        @RequestBody request: FlowRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
        @RequestHeader("X-Target", defaultValue = "PROD") target: String,
    ): FlowResult {
        log.info(
            "[ManipulatorController][executeFlow] purchaseId={} steps={} target={} operator={}",
            purchaseId, request.steps.size, target, operator,
        )

        val flow = ManipulatorFlow(
            purchaseId = purchaseId,
            description = request.description,
            steps = request.steps.mapIndexed { idx, step ->
                FlowStep(
                    order = idx + 1,
                    manipulatorId = step.manipulatorId,
                    params = step.params,
                    skipIfNotApplicable = step.skipIfNotApplicable,
                    abortOnVerifyFail = step.abortOnVerifyFail,
                )
            },
        )

        val result = orchestrator.executeFlow(flow, target)

        auditService.record(
            operator = operator,
            purchaseId = purchaseId,
            action = AuditAction.MANIPULATE_FLOW,
            input = mapOf(
                "description" to request.description,
                "steps" to request.steps.map { it.manipulatorId },
                "target" to target,
            ),
            output = mapOf(
                "success" to result.success,
                "stepsCompleted" to result.stepResults.size,
                "abortedAtStep" to (result.abortedAtStep ?: -1),
            ),
        )

        return result
    }
}

// -----------------------------------------------------------------------
// DTOs
// -----------------------------------------------------------------------

data class ManipulatorRequestDto(
    val manipulatorId: String,
    val params: Map<String, Any> = emptyMap(),
)

data class FlowRequestDto(
    val description: String,
    val steps: List<FlowStepDto>,
)

data class FlowStepDto(
    val manipulatorId: String,
    val params: Map<String, Any> = emptyMap(),
    val skipIfNotApplicable: Boolean = false,
    val abortOnVerifyFail: Boolean = true,
)
