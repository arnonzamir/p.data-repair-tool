package com.sunbit.repair.api

import com.sunbit.repair.loader.PurchaseCacheService
import com.sunbit.repair.loader.PurchaseList
import com.sunbit.repair.loader.PurchaseSummary
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/lists")
@CrossOrigin(origins = ["*"])
class ListsController(
    private val cacheService: PurchaseCacheService,
) {
    private val log = LoggerFactory.getLogger(ListsController::class.java)

    @GetMapping
    fun getLists(): ResponseEntity<List<PurchaseList>> {
        log.info("[ListsController][getLists]")
        return ResponseEntity.ok(cacheService.getLists())
    }

    @PostMapping
    fun createList(@RequestBody request: CreateListRequest): ResponseEntity<PurchaseList> {
        log.info("[ListsController][createList] name={}", request.name)
        return ResponseEntity.ok(cacheService.createList(request.name))
    }

    @GetMapping("/{listId}")
    fun getList(@PathVariable listId: Long): ResponseEntity<PurchaseList> {
        log.info("[ListsController][getList] listId={}", listId)
        val list = cacheService.getList(listId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(list)
    }

    @DeleteMapping("/{listId}")
    fun deleteList(@PathVariable listId: Long): ResponseEntity<Map<String, Any>> {
        log.info("[ListsController][deleteList] listId={}", listId)
        cacheService.deleteList(listId)
        return ResponseEntity.ok(mapOf("deleted" to listId))
    }

    @PostMapping("/{listId}/purchases/{purchaseId}")
    fun addToList(
        @PathVariable listId: Long,
        @PathVariable purchaseId: Long,
    ): ResponseEntity<Map<String, Any>> {
        log.info("[ListsController][addToList] listId={} purchaseId={}", listId, purchaseId)
        cacheService.addToList(listId, purchaseId)
        return ResponseEntity.ok(mapOf("listId" to listId, "purchaseId" to purchaseId, "added" to true))
    }

    @DeleteMapping("/{listId}/purchases/{purchaseId}")
    fun removeFromList(
        @PathVariable listId: Long,
        @PathVariable purchaseId: Long,
    ): ResponseEntity<Map<String, Any>> {
        log.info("[ListsController][removeFromList] listId={} purchaseId={}", listId, purchaseId)
        cacheService.removeFromList(listId, purchaseId)
        return ResponseEntity.ok(mapOf("listId" to listId, "purchaseId" to purchaseId, "removed" to true))
    }

    /** Get summary info for a purchase (cache + replication status) without loading from Snowflake. */
    @GetMapping("/purchase-summary/{purchaseId}")
    fun getPurchaseSummary(@PathVariable purchaseId: Long): ResponseEntity<PurchaseSummary> {
        return ResponseEntity.ok(cacheService.getPurchaseSummary(purchaseId))
    }

    /** Get all list names a purchase belongs to. */
    @GetMapping("/for-purchase/{purchaseId}")
    fun getListsForPurchase(@PathVariable purchaseId: Long): ResponseEntity<List<String>> {
        return ResponseEntity.ok(cacheService.getListsForPurchase(purchaseId))
    }

    // -----------------------------------------------------------------------
    // Notes (immutable, append-only)
    // -----------------------------------------------------------------------

    @GetMapping("/notes/{purchaseId}")
    fun getNotes(@PathVariable purchaseId: Long): ResponseEntity<List<com.sunbit.repair.loader.PurchaseNote>> {
        return ResponseEntity.ok(cacheService.getNotes(purchaseId))
    }

    @PostMapping("/notes/{purchaseId}")
    fun addNote(
        @PathVariable purchaseId: Long,
        @RequestBody request: AddNoteRequest,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<com.sunbit.repair.loader.PurchaseNote> {
        val note = cacheService.addNote(purchaseId, operator, request.content)
        return ResponseEntity.ok(note)
    }

    // -----------------------------------------------------------------------
    // Review status
    // -----------------------------------------------------------------------

    @GetMapping("/review-status/{purchaseId}")
    fun getReviewStatus(@PathVariable purchaseId: Long): ResponseEntity<com.sunbit.repair.loader.ReviewStatus> {
        return ResponseEntity.ok(cacheService.getReviewStatus(purchaseId))
    }

    @PutMapping("/review-status/{purchaseId}")
    fun setReviewStatus(
        @PathVariable purchaseId: Long,
        @RequestBody request: SetReviewStatusRequest,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): ResponseEntity<com.sunbit.repair.loader.ReviewStatus> {
        return ResponseEntity.ok(cacheService.setReviewStatus(purchaseId, request.status, operator))
    }

    @PostMapping("/review-statuses")
    fun getReviewStatuses(@RequestBody request: ReviewStatusesRequest): ResponseEntity<Map<Long, com.sunbit.repair.loader.ReviewStatus>> {
        return ResponseEntity.ok(cacheService.getReviewStatuses(request.purchaseIds))
    }
}

data class CreateListRequest(val name: String)
data class AddNoteRequest(val content: String)
data class SetReviewStatusRequest(val status: String)
data class ReviewStatusesRequest(val purchaseIds: List<Long>)
