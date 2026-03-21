package com.sunbit.repair.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import jakarta.annotation.PostConstruct

/**
 * Git-based sync for collaborative data (lists, notes, review statuses).
 *
 * Each collaborative SQLite table is exported to a sorted text SQL file.
 * On pull, a 3-way merge reconciles local and remote changes:
 *   base (last synced) vs remote (what others changed) vs current (what I changed)
 *
 * Line-level merge: each INSERT is one line, sorted by primary key.
 * Lines added by either side are kept. Lines removed by either side are removed.
 * Same-line conflicts: last write wins (based on timestamp in the data).
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
    private val repoDir = File(localPath)
    private val syncDir get() = File(repoDir, folder)
    private val baseDir = File("$localPath-base") // stores last-synced copies for 3-way merge

    // The SQLite DB path (same as PurchaseCacheService uses)
    private val dbPath get() = System.getProperty("user.home") + "/.sunbit/purchase-repair-cache.db"

    // Tables we sync (each gets a .sql file)
    private val syncTables = listOf(
        SyncTable("purchase_lists", "id", listOf("id", "name", "created_at")),
        SyncTable("purchase_list_items", "list_id,purchase_id", listOf("list_id", "purchase_id")),
        SyncTable("purchase_notes", "id", listOf("id", "purchase_id", "author", "content", "created_at")),
        SyncTable("purchase_review_status", "purchase_id", listOf("purchase_id", "status", "updated_by", "updated_at")),
    )

    @PostConstruct
    fun init() {
        log.info("[GitSyncService][init] repo={} local={} branch={} folder={}", repoUrl, localPath, branch, folder)
        try {
            ensureRepo()
            pull()
            baseDir.mkdirs()
            initialImport()
            log.info("[GitSyncService][init] Sync initialized")
        } catch (e: Exception) {
            log.warn("[GitSyncService][init] Sync initialization failed (app will start without sync): {}", e.message)
        }
    }

    /**
     * Called after any local write to a synced table.
     * Exports the table, commits, and pushes.
     */
    fun onLocalWrite(tableName: String) {
        try {
            val table = syncTables.find { it.name == tableName } ?: return
            exportTable(table)
            val file = File(syncDir, "${table.name}.sql")
            git("add", "$folder/${table.name}.sql", dir = repoDir)

            val status = gitOutput("status", "--porcelain", dir = repoDir)
            if (status.isBlank()) return

            git("commit", "-m", "sync: $tableName", dir = repoDir)

            try {
                git("pull", "--rebase", "origin", branch, dir = repoDir)
            } catch (e: Exception) {
                log.warn("[GitSyncService][onLocalWrite] Pull before push had conflicts, resolving: {}", e.message)
                // On rebase conflict, abort and try merge approach
                try { git("rebase", "--abort", dir = repoDir) } catch (_: Exception) {}
                git("pull", "--no-rebase", "origin", branch, dir = repoDir)
            }

            git("push", "origin", branch, dir = repoDir)

            // Update base after successful push
            updateBase(table)
        } catch (e: Exception) {
            log.error("[GitSyncService][onLocalWrite] Failed to sync {}: {}", tableName, e.message)
        }
    }

    /**
     * Periodic poll: pull remote changes, 3-way merge, import into SQLite.
     */
    @Scheduled(fixedDelayString = "\${sync.poll-interval-seconds:30}000")
    fun pollAndRefresh() {
        try {
            // Snapshot current files before pull
            val beforeHashes = syncTables.associate { it.name to fileHash(File(syncDir, "${it.name}.sql")) }

            pull()

            // Check which files changed
            for (table in syncTables) {
                val file = File(syncDir, "${table.name}.sql")
                val afterHash = fileHash(file)
                if (afterHash != beforeHashes[table.name]) {
                    log.info("[GitSyncService][pollAndRefresh] Remote change detected in {}", table.name)
                    threeWayMerge(table)
                    updateBase(table)
                }
            }
        } catch (e: Exception) {
            log.warn("[GitSyncService][pollAndRefresh] Failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // 3-way merge
    // ------------------------------------------------------------------

    /**
     * 3-way merge:
     *   base  = what we last synced (stored in baseDir)
     *   remote = what git just pulled (in syncDir)
     *   current = what our local SQLite has (export fresh)
     *
     * Lines added by remote but not in base = others added -> keep
     * Lines in base but not in remote = others deleted -> remove
     * Lines added by current but not in base = we added -> keep
     * Lines in base but not in current = we deleted -> remove
     * Both added the same line = deduplicate
     */
    private fun threeWayMerge(table: SyncTable) {
        val baseFile = File(baseDir, "${table.name}.sql")
        val remoteFile = File(syncDir, "${table.name}.sql")

        val baseLines = if (baseFile.exists()) baseFile.readLines().filter { it.isNotBlank() && !it.startsWith("--") }.toSet() else emptySet()
        val remoteLines = remoteFile.readLines().filter { it.isNotBlank() && !it.startsWith("--") }.toSet()

        // Export current state from SQLite
        val currentLines = exportTableToLines(table).toSet()

        // Compute changes
        val remoteAdded = remoteLines - baseLines
        val remoteRemoved = baseLines - remoteLines
        val localAdded = currentLines - baseLines
        val localRemoved = baseLines - currentLines

        // Merge: start from base, apply both sides
        val merged = mutableSetOf<String>()
        merged.addAll(baseLines)
        merged.addAll(remoteAdded)     // add what remote added
        merged.addAll(localAdded)      // add what we added
        merged.removeAll(remoteRemoved) // remove what remote removed
        merged.removeAll(localRemoved)  // remove what we removed

        // Sort for deterministic output
        val sortedMerged = merged.sorted()

        // Write merged result to sync file
        val header = "-- ${table.name} (synced)"
        remoteFile.writeText(header + "\n" + sortedMerged.joinToString("\n") + "\n")

        // Import merged result into SQLite
        importLines(table, sortedMerged)

        // If we added anything that remote didn't have, push
        if (localAdded.isNotEmpty() || localRemoved.isNotEmpty()) {
            try {
                git("add", "$folder/${table.name}.sql", dir = repoDir)
                val status = gitOutput("status", "--porcelain", dir = repoDir)
                if (status.isNotBlank()) {
                    git("commit", "-m", "sync: merge ${table.name}", dir = repoDir)
                    git("push", "origin", branch, dir = repoDir)
                }
            } catch (e: Exception) {
                log.warn("[GitSyncService][threeWayMerge] Push after merge failed: {}", e.message)
            }
        }

        log.info("[GitSyncService][threeWayMerge] {} merged: base={} remote+{}/-{} local+{}/-{} result={}",
            table.name, baseLines.size, remoteAdded.size, remoteRemoved.size,
            localAdded.size, localRemoved.size, sortedMerged.size)
    }

    // ------------------------------------------------------------------
    // Export / Import
    // ------------------------------------------------------------------

    private fun exportTable(table: SyncTable) {
        val lines = exportTableToLines(table)
        val file = File(syncDir, "${table.name}.sql")
        syncDir.mkdirs()
        val header = "-- ${table.name} (synced)"
        file.writeText(header + "\n" + lines.joinToString("\n") + "\n")
    }

    private fun exportTableToLines(table: SyncTable): List<String> {
        val columns = table.columns.joinToString(",")
        val db = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val lines = mutableListOf<String>()
        try {
            val rs = db.createStatement().executeQuery(
                "SELECT $columns FROM ${table.name} ORDER BY ${table.sortKey}"
            )
            while (rs.next()) {
                val values = table.columns.map { col ->
                    val value = rs.getString(col)
                    if (value == null) "NULL" else "'" + value.replace("'", "''").replace("\n", "\\n") + "'"
                }
                lines.add("INSERT INTO ${table.name}($columns) VALUES(${values.joinToString(",")});")
            }
        } catch (e: Exception) {
            log.warn("[GitSyncService][exportTableToLines] Failed to export {}: {}", table.name, e.message)
        } finally {
            db.close()
        }
        return lines
    }

    private fun importLines(table: SyncTable, lines: List<String>) {
        val db = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        try {
            db.autoCommit = false
            db.createStatement().execute("DELETE FROM ${table.name}")
            for (line in lines) {
                if (line.startsWith("INSERT INTO")) {
                    try {
                        db.createStatement().execute(line.replace("\\n", "\n"))
                    } catch (e: Exception) {
                        log.warn("[GitSyncService][importLines] Failed to execute: {}", e.message)
                    }
                }
            }
            db.commit()
        } catch (e: Exception) {
            log.error("[GitSyncService][importLines] Import failed for {}: {}", table.name, e.message)
            try { db.rollback() } catch (_: Exception) {}
        } finally {
            db.autoCommit = true
            db.close()
        }
    }

    private fun initialImport() {
        for (table in syncTables) {
            val remoteFile = File(syncDir, "${table.name}.sql")
            val baseFile = File(baseDir, "${table.name}.sql")

            if (remoteFile.exists()) {
                // Remote has data: do a 3-way merge (base may or may not exist)
                threeWayMerge(table)
                updateBase(table)
            } else {
                // No remote file: export local state as the initial version
                exportTable(table)
                updateBase(table)
            }
        }

        // Commit any new exports
        try {
            for (table in syncTables) {
                git("add", "$folder/${table.name}.sql", dir = repoDir)
            }
            val status = gitOutput("status", "--porcelain", dir = repoDir)
            if (status.isNotBlank()) {
                git("commit", "-m", "sync: initial export", dir = repoDir)
                git("push", "origin", branch, dir = repoDir)
            }
        } catch (e: Exception) {
            log.warn("[GitSyncService][initialImport] Push failed: {}", e.message)
        }
    }

    private fun updateBase(table: SyncTable) {
        val src = File(syncDir, "${table.name}.sql")
        val dst = File(baseDir, "${table.name}.sql")
        if (src.exists()) {
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun fileHash(file: File): String {
        return if (file.exists()) file.readText().hashCode().toString() else ""
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

data class SyncTable(
    val name: String,
    val sortKey: String,
    val columns: List<String>,
)
