package com.sunbit.repair.api

import com.sunbit.repair.sync.GitSyncService
import com.sunbit.repair.sync.SyncPurchaseState
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/sync")
@CrossOrigin(origins = ["*"])
@ConditionalOnBean(GitSyncService::class)
class SyncController(
    private val syncService: GitSyncService,
) {
    private val log = LoggerFactory.getLogger(SyncController::class.java)

    @GetMapping("/purchases/{purchaseId}")
    fun getPurchaseState(@PathVariable purchaseId: Long): SyncPurchaseState? =
        syncService.getPurchaseState(purchaseId)

    @PostMapping("/purchases/batch")
    fun getPurchaseStates(@RequestBody body: Map<String, List<Long>>): Map<Long, SyncPurchaseState> {
        val ids = body["purchaseIds"] ?: emptyList()
        return syncService.getPurchaseStates(ids)
    }

    @PutMapping("/purchases/{purchaseId}/status")
    fun updateStatus(
        @PathVariable purchaseId: Long,
        @RequestBody body: Map<String, String>,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ) {
        val status = body["status"] ?: throw IllegalArgumentException("status is required")
        log.info("[SyncController][updateStatus] purchase={} status={} operator={}", purchaseId, status, operator)
        syncService.updateStatus(purchaseId, status, operator)
    }

    @PutMapping("/purchases/{purchaseId}/claim")
    fun claim(
        @PathVariable purchaseId: Long,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ) {
        log.info("[SyncController][claim] purchase={} operator={}", purchaseId, operator)
        syncService.claimPurchase(purchaseId, operator)
    }

    @DeleteMapping("/purchases/{purchaseId}/claim")
    fun unclaim(
        @PathVariable purchaseId: Long,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ) {
        log.info("[SyncController][unclaim] purchase={} operator={}", purchaseId, operator)
        syncService.unclaimPurchase(purchaseId, operator)
    }

    @PostMapping("/purchases/{purchaseId}/notes")
    fun addNote(
        @PathVariable purchaseId: Long,
        @RequestBody body: Map<String, String>,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ) {
        val text = body["text"] ?: throw IllegalArgumentException("text is required")
        log.info("[SyncController][addNote] purchase={} operator={}", purchaseId, operator)
        syncService.addNote(purchaseId, text, operator)
    }

    @PostMapping("/pull")
    fun pull() {
        log.info("[SyncController][pull] Manual pull triggered")
        syncService.pollAndRefresh()
    }
}
