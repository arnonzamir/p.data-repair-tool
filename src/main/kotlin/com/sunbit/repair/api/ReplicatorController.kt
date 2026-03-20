package com.sunbit.repair.api

import com.sunbit.repair.audit.AuditService
import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.PurchaseCacheService
import com.sunbit.repair.replicator.MysqlExecutor
import com.sunbit.repair.replicator.ReplicatorService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/replicate")
@CrossOrigin(origins = ["*"])
class ReplicatorController(
    private val replicatorService: ReplicatorService,
    private val auditService: AuditService,
    private val cacheService: PurchaseCacheService,
    private val mysqlExecutor: MysqlExecutor,
) {
    private val log = LoggerFactory.getLogger(ReplicatorController::class.java)

    /**
     * Replicate a single purchase from Snowflake to local/staging MySQL.
     */
    @PostMapping("/single")
    fun replicateSingle(
        @RequestBody request: ReplicateRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<ReplicationResult> {
        log.info("[ReplicatorController][replicateSingle] purchaseId={} target={} execute={} operator={}",
            request.purchaseId, request.target, request.execute, operator)

        val replicationRequest = ReplicationRequest(
            purchaseId = request.purchaseId,
            target = request.target,
            execute = request.execute,
            idOffset = request.idOffset ?: 100_000_000L,
            customerRetailerId = request.customerRetailerId,
            paymentProfileId = request.paymentProfileId,
            namePrefix = request.namePrefix,
        )

        val result = replicatorService.replicate(replicationRequest)

        auditService.record(
            operator = operator,
            purchaseId = request.purchaseId,
            action = AuditAction.REPLICATE,
            input = mapOf(
                "target" to request.target.name,
                "execute" to request.execute,
            ),
            output = mapOf(
                "success" to result.success,
                "tableRowCounts" to result.tableRowCounts,
                "piiRedactions" to result.piiLog.size,
            ),
        )

        return if (result.success) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.unprocessableEntity().body(result)
        }
    }

    /**
     * Replicate multiple purchases in batch.
     */
    @PostMapping("/batch")
    fun replicateBatch(
        @RequestBody request: BatchReplicateRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<BatchReplicationResult> {
        log.info("[ReplicatorController][replicateBatch] count={} target={} operator={}",
            request.purchaseIds.size, request.target, operator)

        val requests = request.purchaseIds.map { purchaseId ->
            ReplicationRequest(
                purchaseId = purchaseId,
                target = request.target,
                execute = request.execute,
                idOffset = request.idOffset ?: 100_000_000L,
                customerRetailerId = request.customerRetailerId,
                paymentProfileId = request.paymentProfileId,
                namePrefix = request.namePrefix,
            )
        }

        val result = replicatorService.replicateBatch(requests)

        for (purchaseId in request.purchaseIds) {
            auditService.record(
                operator = operator,
                purchaseId = purchaseId,
                action = AuditAction.REPLICATE,
                input = mapOf(
                    "target" to request.target.name,
                    "batchSize" to request.purchaseIds.size,
                ),
            )
        }

        return ResponseEntity.ok(result)
    }

    /**
     * Generate SQL only (no execution) -- returns the INSERT and rollback SQL.
     */
    @PostMapping("/generate-sql")
    fun generateSql(
        @RequestBody request: ReplicateRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<SqlGenerationResponse> {
        log.info("[ReplicatorController][generateSql] purchaseId={} operator={}",
            request.purchaseId, operator)

        val replicationRequest = ReplicationRequest(
            purchaseId = request.purchaseId,
            target = request.target,
            execute = false,
            idOffset = request.idOffset ?: 100_000_000L,
            customerRetailerId = request.customerRetailerId,
            paymentProfileId = request.paymentProfileId,
            namePrefix = request.namePrefix,
        )

        val result = replicatorService.replicate(replicationRequest)

        return ResponseEntity.ok(SqlGenerationResponse(
            purchaseId = request.purchaseId,
            insertSql = result.insertSql,
            rollbackSql = result.rollbackSql,
            tableRowCounts = result.tableRowCounts,
            piiRedactions = result.piiLog.size,
        ))
    }

    /**
     * Check whether a purchase (with offset applied) already exists in the target DB.
     */
    @GetMapping("/check-exists/{purchaseId}")
    fun checkExists(
        @PathVariable purchaseId: Long,
        @RequestParam(defaultValue = "LOCAL") target: String,
        @RequestParam(defaultValue = "0") idOffset: Long,
    ): ResponseEntity<ExistsCheckResult> {
        log.info("[ReplicatorController][checkExists] purchaseId={} target={} idOffset={}", purchaseId, target, idOffset)

        val replicationTarget = ReplicationTarget.valueOf(target.uppercase())
        val defaults = replicatorService.getTargetDefaults(replicationTarget)
        val effectiveOffset = if (idOffset == 0L) defaults["idOffset"] as Long else idOffset
        val targetPurchaseId = purchaseId + effectiveOffset

        val existing = mysqlExecutor.checkExists(targetPurchaseId, replicationTarget)

        return ResponseEntity.ok(ExistsCheckResult(
            purchaseId = purchaseId,
            targetPurchaseId = targetPurchaseId,
            target = target,
            reachable = existing != null,
            exists = existing != null && existing.isNotEmpty(),
            rowCounts = existing ?: emptyMap(),
        ))
    }

    /**
     * Execute the stored rollback (DELETE) script to undo a replication.
     */
    @PostMapping("/rollback")
    fun rollbackReplication(
        @RequestBody request: RollbackRequestDto,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<RollbackResultDto> {
        log.info("[ReplicatorController][rollbackReplication] purchaseId={} target={} operator={}",
            request.purchaseId, request.target, operator)

        val target = ReplicationTarget.valueOf(request.target)
        val rollbackSql = cacheService.getRollbackSql(request.purchaseId, target)
            ?: return ResponseEntity.badRequest().body(RollbackResultDto(
                purchaseId = request.purchaseId,
                target = request.target,
                success = false,
                error = "No rollback script found for purchase ${request.purchaseId} on ${request.target}",
            ))

        return try {
            // Split rollback SQL into charge and purchase parts
            val parts = rollbackSql.split("-- ===== PURCHASE SCHEMA =====")
            val chargePart = parts[0]
            val purchasePart = if (parts.size > 1) parts[1] else ""

            val logs = StringBuilder()
            if (chargePart.isNotBlank()) {
                logs.appendLine(mysqlExecutor.execute(chargePart, target, "charge"))
            }
            if (purchasePart.isNotBlank()) {
                logs.appendLine(mysqlExecutor.execute(purchasePart, target, "purchase"))
            }
            val execLog = logs.toString()
            cacheService.removeReplication(request.purchaseId, target)

            auditService.record(
                operator = operator,
                purchaseId = request.purchaseId,
                action = AuditAction.REPLICATE,
                input = mapOf("action" to "rollback", "target" to request.target),
                output = mapOf("success" to true),
            )

            ResponseEntity.ok(RollbackResultDto(
                purchaseId = request.purchaseId,
                target = request.target,
                success = true,
                executionLog = execLog,
            ))
        } catch (e: Exception) {
            log.error("[ReplicatorController][rollbackReplication] Failed: {}", e.message, e)
            ResponseEntity.ok(RollbackResultDto(
                purchaseId = request.purchaseId,
                target = request.target,
                success = false,
                error = e.message,
            ))
        }
    }

    /**
     * Get the stored rollback SQL without executing it.
     */
    @GetMapping("/rollback-sql/{purchaseId}/{target}")
    fun getRollbackSql(
        @PathVariable purchaseId: Long,
        @PathVariable target: String,
    ): ResponseEntity<Map<String, Any?>> {
        val replicationTarget = ReplicationTarget.valueOf(target.uppercase())
        val sql = cacheService.getRollbackSql(purchaseId, replicationTarget)
        return ResponseEntity.ok(mapOf(
            "purchaseId" to purchaseId,
            "target" to target,
            "rollbackSql" to sql,
        ))
    }

    /**
     * Get preconfigured defaults for each target environment.
     */
    @GetMapping("/defaults")
    fun getDefaults(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "LOCAL" to replicatorService.getTargetDefaults(ReplicationTarget.LOCAL),
            "STAGING" to replicatorService.getTargetDefaults(ReplicationTarget.STAGING),
        ))
    }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

data class ReplicateRequestDto(
    val purchaseId: Long,
    val target: ReplicationTarget = ReplicationTarget.LOCAL,
    val execute: Boolean = false,
    val idOffset: Long? = null,
    val customerRetailerId: Long? = null,
    val paymentProfileId: Long? = null,
    val namePrefix: String? = null,
)

data class BatchReplicateRequestDto(
    val purchaseIds: List<Long>,
    val target: ReplicationTarget = ReplicationTarget.LOCAL,
    val execute: Boolean = false,
    val idOffset: Long? = null,
    val customerRetailerId: Long? = null,
    val paymentProfileId: Long? = null,
    val namePrefix: String? = null,
)

data class ExistsCheckResult(
    val purchaseId: Long,
    val targetPurchaseId: Long,
    val target: String,
    val reachable: Boolean,
    val exists: Boolean,
    val rowCounts: Map<String, Int>,
)

data class RollbackRequestDto(
    val purchaseId: Long,
    val target: String,
)

data class RollbackResultDto(
    val purchaseId: Long,
    val target: String,
    val success: Boolean,
    val error: String? = null,
    val executionLog: String? = null,
)

data class SqlGenerationResponse(
    val purchaseId: Long,
    val insertSql: String,
    val rollbackSql: String,
    val tableRowCounts: Map<String, Int>,
    val piiRedactions: Int,
)
