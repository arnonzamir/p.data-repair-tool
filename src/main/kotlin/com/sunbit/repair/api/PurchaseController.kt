package com.sunbit.repair.api

import com.sunbit.repair.analyzer.engine.PurchaseAnalyzer
import com.sunbit.repair.audit.AuditService
import com.sunbit.repair.domain.*
import com.sunbit.repair.loader.CacheStats
import com.sunbit.repair.loader.PurchaseCacheService
import com.sunbit.repair.loader.PurchaseLoaderService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/v1/purchases")
@CrossOrigin(origins = ["*"])
class PurchaseController(
    private val cacheService: PurchaseCacheService,
    private val loaderService: PurchaseLoaderService,
    private val analyzer: PurchaseAnalyzer,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(PurchaseController::class.java)

    /**
     * Load a single purchase snapshot. Serves from SQLite cache unless refresh=true.
     */
    @GetMapping("/{purchaseId}")
    fun loadPurchase(
        @PathVariable purchaseId: Long,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<CachedPurchaseResponse> {
        log.info("[PurchaseController][loadPurchase] purchaseId={} refresh={} operator={}", purchaseId, refresh, operator)
        val start = System.currentTimeMillis()

        val cached = cacheService.getSnapshot(purchaseId, forceRefresh = refresh)

        auditService.record(
            operator = operator,
            purchaseId = purchaseId,
            action = AuditAction.LOAD,
            input = mapOf("refresh" to refresh, "cachedAt" to cached.cachedAt.toString()),
            durationMs = System.currentTimeMillis() - start,
        )

        return ResponseEntity.ok(CachedPurchaseResponse(
            snapshot = cached.snapshot,
            cachedAt = cached.cachedAt.toString(),
            replications = cached.replications,
        ))
    }

    /**
     * Load and analyze a single purchase. Serves snapshot from cache unless refresh=true.
     * Analysis always runs fresh against the (possibly cached) snapshot.
     */
    @GetMapping("/{purchaseId}/analyze")
    fun analyzePurchase(
        @PathVariable purchaseId: Long,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<PurchaseAnalysisResponse> {
        log.info("[PurchaseController][analyzePurchase] purchaseId={} refresh={} operator={}", purchaseId, refresh, operator)
        val start = System.currentTimeMillis()

        val cached = cacheService.getSnapshot(purchaseId, forceRefresh = refresh)
        val analysis = analyzer.analyze(cached.snapshot)

        auditService.record(
            operator = operator,
            purchaseId = purchaseId,
            action = AuditAction.ANALYZE,
            output = mapOf(
                "findingCount" to analysis.findings.size,
                "overallSeverity" to (analysis.overallSeverity?.name ?: "NONE"),
                "cachedAt" to cached.cachedAt.toString(),
                "refresh" to refresh,
            ),
            durationMs = System.currentTimeMillis() - start,
        )

        return ResponseEntity.ok(PurchaseAnalysisResponse(
            snapshot = cached.snapshot,
            analysis = analysis,
            cachedAt = cached.cachedAt.toString(),
            replications = cached.replications,
        ))
    }

    /**
     * Batch load and analyze multiple purchases.
     */
    @PostMapping("/batch/analyze")
    fun analyzeBatch(
        @RequestBody request: BatchAnalyzeRequest,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<BatchAnalysisResult> {
        log.info("[PurchaseController][analyzeBatch] count={} refresh={} operator={}",
            request.purchaseIds.size, refresh, operator)
        val start = System.currentTimeMillis()

        val cachedSnapshots = cacheService.getSnapshots(request.purchaseIds, forceRefresh = refresh)
        val snapshots = cachedSnapshots.map { it.snapshot }
        val batchResult = analyzer.analyzeBatch(snapshots)

        for (purchaseId in request.purchaseIds) {
            auditService.record(
                operator = operator,
                purchaseId = purchaseId,
                action = AuditAction.ANALYZE,
                input = mapOf("batchSize" to request.purchaseIds.size),
                durationMs = System.currentTimeMillis() - start,
            )
        }

        return ResponseEntity.ok(batchResult)
    }

    /**
     * Get audit trail for a purchase.
     */
    @GetMapping("/{purchaseId}/audit")
    fun getAuditTrail(@PathVariable purchaseId: Long): ResponseEntity<List<AuditEntry>> {
        log.info("[PurchaseController][getAuditTrail] purchaseId={}", purchaseId)
        return ResponseEntity.ok(auditService.getByPurchase(purchaseId))
    }

    /**
     * Cache management: stats, evict, clear.
     */
    @GetMapping("/cache/stats")
    fun cacheStats(): ResponseEntity<CacheStats> {
        return ResponseEntity.ok(cacheService.stats())
    }

    @DeleteMapping("/cache/{purchaseId}")
    fun cacheEvict(@PathVariable purchaseId: Long): ResponseEntity<Map<String, Any>> {
        cacheService.evict(purchaseId)
        return ResponseEntity.ok(mapOf("evicted" to purchaseId))
    }

    @DeleteMapping("/cache")
    fun cacheClear(): ResponseEntity<Map<String, Any>> {
        cacheService.clear()
        return ResponseEntity.ok(mapOf("cleared" to true))
    }
}

// ---------------------------------------------------------------------------
// Request / Response DTOs
// ---------------------------------------------------------------------------

data class CachedPurchaseResponse(
    val snapshot: PurchaseSnapshot,
    val cachedAt: String,
    val replications: List<com.sunbit.repair.loader.ReplicationRecord> = emptyList(),
)

data class PurchaseAnalysisResponse(
    val snapshot: PurchaseSnapshot,
    val analysis: AnalysisResult,
    val cachedAt: String,
    val replications: List<com.sunbit.repair.loader.ReplicationRecord> = emptyList(),
)

data class BatchAnalyzeRequest(
    val purchaseIds: List<Long>,
)
