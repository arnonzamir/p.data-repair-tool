package com.sunbit.repair.api

import com.sunbit.repair.audit.AuditService
import com.sunbit.repair.domain.AuditEntry
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/audit")
@CrossOrigin(origins = ["*"])
class AuditController(
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(AuditController::class.java)

    @GetMapping("/recent")
    fun getRecent(
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<List<AuditEntry>> {
        log.info("[AuditController][getRecent] limit={}", limit)
        return ResponseEntity.ok(auditService.getRecent(limit))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<AuditEntry> {
        log.info("[AuditController][getById] id={}", id)
        val entry = auditService.getById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(entry)
    }
}
