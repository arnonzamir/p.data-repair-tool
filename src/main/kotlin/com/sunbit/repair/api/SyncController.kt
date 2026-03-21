package com.sunbit.repair.api

import com.sunbit.repair.sync.GitSyncService
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

    @PostMapping("/pull")
    fun pull() {
        log.info("[SyncController][pull] Manual pull triggered")
        syncService.pollAndRefresh()
    }
}
