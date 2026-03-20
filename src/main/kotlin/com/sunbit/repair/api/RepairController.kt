package com.sunbit.repair.api

import com.sunbit.repair.audit.AuditService
import com.sunbit.repair.domain.*
import com.sunbit.repair.repairer.RepairService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/repairs")
@CrossOrigin(origins = ["*"])
class RepairController(
    private val repairService: RepairService,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(RepairController::class.java)

    /**
     * Dry-run a repair action (preview what would happen).
     */
    @PostMapping("/dry-run")
    fun dryRun(
        @RequestBody request: RepairRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<DryRunResult> {
        log.info("[RepairController][dryRun] action={} purchaseId={} operator={}",
            request.actionType, request.purchaseId, operator)

        val repairRequest = request.toDomain()
        val result = repairService.dryRun(repairRequest)
        return ResponseEntity.ok(result)
    }

    /**
     * Execute a single repair action.
     */
    @PostMapping("/execute")
    fun execute(
        @RequestBody request: RepairRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<RepairResult> {
        log.info("[RepairController][execute] action={} purchaseId={} operator={}",
            request.actionType, request.purchaseId, operator)
        val start = System.currentTimeMillis()

        val repairRequest = request.toDomain()
        val result = repairService.execute(repairRequest)

        auditService.record(
            operator = operator,
            purchaseId = request.purchaseId,
            action = AuditAction.REPAIR,
            input = mapOf(
                "actionType" to request.actionType.name,
                "reason" to request.reason,
            ),
            output = mapOf(
                "success" to result.success,
                "httpStatus" to (result.httpStatus ?: -1),
                "error" to (result.error ?: ""),
            ),
            snapshotBefore = result.snapshotBefore,
            snapshotAfter = result.snapshotAfter,
            durationMs = System.currentTimeMillis() - start,
        )

        return if (result.success) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.unprocessableEntity().body(result)
        }
    }

    /**
     * Execute a multi-step repair plan.
     */
    @PostMapping("/execute-plan")
    fun executePlan(
        @RequestBody request: RepairPlanDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<RepairPlanExecution> {
        log.info("[RepairController][executePlan] purchaseId={} steps={} operator={}",
            request.purchaseId, request.steps.size, operator)

        val plan = request.toDomain()
        val execution = repairService.executePlan(plan)

        auditService.record(
            operator = operator,
            purchaseId = request.purchaseId,
            action = AuditAction.REPAIR,
            input = mapOf(
                "planSteps" to request.steps.size,
                "description" to request.description,
            ),
            output = mapOf(
                "completedSteps" to execution.stepResults.size,
                "abortedAtStep" to (execution.abortedAtStep ?: -1),
            ),
        )

        return ResponseEntity.ok(execution)
    }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

data class RepairRequestDto(
    val purchaseId: Long,
    val actionType: RepairActionType,
    val reason: String,
    val paymentId: Long? = null,
    val newAmount: BigDecimal? = null,
    val newApr: BigDecimal? = null,
    val workoutParams: Map<String, Any>? = null,
    val settlementAmount: BigDecimal? = null,
    val cancellationReason: String? = null,
) {
    fun toDomain(): RepairRequest = when (actionType) {
        RepairActionType.UNPAY_WITH_REFUND -> RepairRequest.UnpayWithRefund(
            purchaseId = purchaseId,
            paymentId = requireNotNull(paymentId) { "paymentId required for UNPAY_WITH_REFUND" },
            reason = reason,
        )
        RepairActionType.UNPAY_WITHOUT_REFUND -> RepairRequest.UnpayWithoutRefund(
            purchaseId = purchaseId,
            paymentId = requireNotNull(paymentId) { "paymentId required for UNPAY_WITHOUT_REFUND" },
            reason = reason,
        )
        RepairActionType.CHANGE_AMOUNT -> RepairRequest.ChangeAmount(
            purchaseId = purchaseId,
            newAmount = requireNotNull(newAmount) { "newAmount required for CHANGE_AMOUNT" },
            reason = reason,
        )
        RepairActionType.REVERSAL_OF_ADJUSTMENT -> RepairRequest.ReversalOfAdjustment(
            purchaseId = purchaseId,
            reason = reason,
        )
        RepairActionType.CHANGE_APR -> RepairRequest.ChangeApr(
            purchaseId = purchaseId,
            newApr = requireNotNull(newApr) { "newApr required for CHANGE_APR" },
            reason = reason,
        )
        RepairActionType.CREATE_WORKOUT -> RepairRequest.CreateWorkout(
            purchaseId = purchaseId,
            workoutParams = workoutParams ?: emptyMap(),
            reason = reason,
        )
        RepairActionType.CREATE_SETTLEMENT -> RepairRequest.CreateSettlement(
            purchaseId = purchaseId,
            settlementAmount = requireNotNull(settlementAmount) { "settlementAmount required for CREATE_SETTLEMENT" },
            reason = reason,
        )
        RepairActionType.CANCEL_PURCHASE -> RepairRequest.CancelPurchase(
            purchaseId = purchaseId,
            cancellationReason = cancellationReason ?: "Admin cancellation",
            reason = reason,
        )
        RepairActionType.RESTORE_CANCELLATION -> RepairRequest.RestoreCancellation(
            purchaseId = purchaseId,
            reason = reason,
        )
        RepairActionType.REVERSE_CHARGEBACK -> RepairRequest.ReverseChargeback(
            purchaseId = purchaseId,
            reason = reason,
        )
    }
}

data class RepairPlanDto(
    val purchaseId: Long,
    val description: String,
    val steps: List<RepairStepDto>,
) {
    fun toDomain(): RepairPlan = RepairPlan(
        purchaseId = purchaseId,
        steps = steps.mapIndexed { index, step ->
            RepairStep(
                order = index + 1,
                request = step.request.toDomain(),
                verifyAfter = step.verifyAfter,
                expectedOutcome = step.expectedOutcome,
            )
        },
        createdAt = Instant.now(),
        description = description,
    )
}

data class RepairStepDto(
    val request: RepairRequestDto,
    val verifyAfter: Boolean = true,
    val expectedOutcome: String? = null,
)
