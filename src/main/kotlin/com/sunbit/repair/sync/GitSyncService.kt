package com.sunbit.repair.sync

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant
import jakarta.annotation.PostConstruct

/**
 * Git-based sync for collaborative work across team members.
 * A single purchases.json file tracks per-purchase state (status, claims, notes).
 * Lists are local (SQLite), sync is purchase-level so moving purchases between
 * lists preserves their collaborative state.
 */
@Service
@ConditionalOnProperty("sync.enabled", havingValue = "true", matchIfMissing = false)
class GitSyncService(
    @Value("\${sync.repo-url}") private val repoUrl: String,
    @Value("\${sync.local-path}") private val localPath: String,
    @Value("\${sync.branch}") private val branch: String,
    @Value("\${sync.folder}") private val folder: String,
) {
    private val log = LoggerFactory.getLogger(GitSyncService::class.java)
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val repoDir = File(localPath)
    private val syncDir get() = File(repoDir, folder)
    private val purchasesFile get() = File(syncDir, "purchases.json")

    // In-memory state: purchaseId (as string) -> state
    @Volatile
    private var state: MutableMap<String, SyncPurchaseState> = mutableMapOf()

    @PostConstruct
    fun init() {
        log.info("[GitSyncService][init] repo={} local={} branch={} folder={}", repoUrl, localPath, branch, folder)
        try {
            ensureRepo()
            pull()
            loadState()
            log.info("[GitSyncService][init] Loaded sync state for {} purchases", state.size)
        } catch (e: Exception) {
            log.warn("[GitSyncService][init] Sync initialization failed (app will start without sync): {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun getPurchaseState(purchaseId: Long): SyncPurchaseState? {
        return state[purchaseId.toString()]
    }

    fun getPurchaseStates(purchaseIds: List<Long>): Map<Long, SyncPurchaseState> {
        val result = mutableMapOf<Long, SyncPurchaseState>()
        for (pid in purchaseIds) {
            state[pid.toString()]?.let { result[pid] = it }
        }
        return result
    }

    fun getAllStates(): Map<String, SyncPurchaseState> = state.toMap()

    fun updateStatus(purchaseId: Long, status: String, operator: String) {
        val ps = state.getOrPut(purchaseId.toString()) { SyncPurchaseState() }
        ps.status = status
        ps.statusUpdatedBy = operator
        ps.statusUpdatedAt = Instant.now().toString()
        writeAndPush("status: $purchaseId -> $status by $operator")
    }

    fun claimPurchase(purchaseId: Long, operator: String) {
        val ps = state.getOrPut(purchaseId.toString()) { SyncPurchaseState() }
        ps.claimedBy = operator
        ps.claimedAt = Instant.now().toString()
        writeAndPush("claim: $purchaseId by $operator")
    }

    fun unclaimPurchase(purchaseId: Long, operator: String) {
        val ps = state.getOrPut(purchaseId.toString()) { SyncPurchaseState() }
        ps.claimedBy = null
        ps.claimedAt = null
        writeAndPush("unclaim: $purchaseId by $operator")
    }

    fun addNote(purchaseId: Long, text: String, operator: String) {
        val ps = state.getOrPut(purchaseId.toString()) { SyncPurchaseState() }
        ps.notes.add(SyncNote(text = text, by = operator, at = Instant.now().toString()))
        writeAndPush("note on $purchaseId by $operator")
    }

    @Scheduled(fixedDelayString = "\${sync.poll-interval-seconds:30}000")
    fun pollAndRefresh() {
        try {
            pull()
            loadState()
        } catch (e: Exception) {
            log.warn("[GitSyncService][pollAndRefresh] Failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Git operations
    // ------------------------------------------------------------------

    private fun ensureRepo() {
        if (File(repoDir, ".git").exists()) {
            log.info("[GitSyncService][ensureRepo] Repo exists at {}", localPath)
            return
        }
        log.info("[GitSyncService][ensureRepo] Cloning {} to {}", repoUrl, localPath)
        repoDir.parentFile?.mkdirs()
        git("clone", "--branch", branch, "--single-branch", repoUrl, localPath)
    }

    private fun pull() {
        git("pull", "--rebase", "origin", branch, dir = repoDir)
    }

    private fun writeAndPush(commitMessage: String) {
        syncDir.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(purchasesFile, state)

        git("add", "$folder/purchases.json", dir = repoDir)

        val status = gitOutput("status", "--porcelain", dir = repoDir)
        if (status.isBlank()) {
            log.debug("[GitSyncService][writeAndPush] No changes to commit")
            return
        }

        git("commit", "-m", commitMessage, dir = repoDir)

        try {
            git("pull", "--rebase", "origin", branch, dir = repoDir)
        } catch (e: Exception) {
            log.warn("[GitSyncService][writeAndPush] Pull before push failed: {}", e.message)
        }

        try {
            git("push", "origin", branch, dir = repoDir)
        } catch (e: Exception) {
            log.error("[GitSyncService][writeAndPush] Push failed: {}. Changes are committed locally.", e.message)
        }
    }

    private fun loadState() {
        if (!purchasesFile.exists()) {
            // Migrate from old per-list files if they exist
            migrateFromPerListFiles()
            return
        }
        try {
            state = mapper.readValue(purchasesFile)
        } catch (e: Exception) {
            log.warn("[GitSyncService][loadState] Failed to parse purchases.json: {}", e.message)
        }
    }

    /**
     * One-time migration: merge all old per-list JSON files into a single purchases.json.
     * Per-purchase state is merged (last write wins if same purchase appears in multiple lists).
     */
    private fun migrateFromPerListFiles() {
        if (!syncDir.exists()) return
        val files = syncDir.listFiles { f -> f.extension == "json" && f.name != "purchases.json" } ?: return
        if (files.isEmpty()) return

        log.info("[GitSyncService][migrateFromPerListFiles] Migrating {} list files to purchases.json", files.size)
        val merged = mutableMapOf<String, SyncPurchaseState>()

        for (file in files) {
            try {
                val listState: SyncListState = mapper.readValue(file)
                for ((pid, ps) in listState.purchases) {
                    // Merge: keep the one with more data
                    val existing = merged[pid]
                    if (existing == null || (ps.status != null && existing.status == null)) {
                        merged[pid] = ps
                    } else {
                        // Merge notes
                        val existingNoteTexts = existing.notes.map { it.text }.toSet()
                        for (note in ps.notes) {
                            if (note.text !in existingNoteTexts) {
                                existing.notes.add(note)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("[GitSyncService][migrateFromPerListFiles] Failed to parse {}: {}", file.name, e.message)
            }
        }

        state = merged
        if (merged.isNotEmpty()) {
            syncDir.mkdirs()
            mapper.writerWithDefaultPrettyPrinter().writeValue(purchasesFile, state)
            // Delete old per-list files
            for (file in files) {
                git("rm", "$folder/${file.name}", dir = repoDir)
            }
            git("add", "$folder/purchases.json", dir = repoDir)
            git("commit", "-m", "Migrate sync from per-list to per-purchase format", dir = repoDir)
            try {
                git("push", "origin", branch, dir = repoDir)
            } catch (e: Exception) {
                log.warn("[GitSyncService][migrateFromPerListFiles] Push failed: {}", e.message)
            }
            log.info("[GitSyncService][migrateFromPerListFiles] Migrated {} purchases from {} list files", merged.size, files.size)
        }
    }

    private fun git(vararg args: String, dir: File = File(".")) {
        val cmd = listOf("git") + args.toList()
        val proc = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("git ${args.first()} failed (exit $exitCode): $output")
        }
    }

    private fun gitOutput(vararg args: String, dir: File = File(".")): String {
        val cmd = listOf("git") + args.toList()
        val proc = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return output.trim()
    }
}

// ------------------------------------------------------------------
// Data model
// ------------------------------------------------------------------

/** Old per-list format (for migration only) */
data class SyncListState(
    val purchases: MutableMap<String, SyncPurchaseState> = mutableMapOf(),
)

data class SyncPurchaseState(
    var status: String? = null,
    var statusUpdatedBy: String? = null,
    var statusUpdatedAt: String? = null,
    var claimedBy: String? = null,
    var claimedAt: String? = null,
    val notes: MutableList<SyncNote> = mutableListOf(),
)

data class SyncNote(
    val text: String,
    val by: String,
    val at: String,
)
