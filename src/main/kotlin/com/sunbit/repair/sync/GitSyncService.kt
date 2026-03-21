package com.sunbit.repair.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

    // Dirty flag: set to true when AuditService writes a new entry, cleared after export
    private val auditDirty = AtomicBoolean(false)
    // The operator name from the most recent local audit write
    private val lastAuditOperator = AtomicReference<String>(null)

    // Tables we sync (each gets a .sql file)
    private val syncTables = listOf(
        SyncTable("purchase_lists", "id", listOf("id", "name", "created_at")),
        SyncTable("purchase_list_items", "list_id,purchase_id", listOf("list_id", "purchase_id", "added_at")),
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
            // Export audit log if dirty (before pull, so we can commit+push together)
            val operator = lastAuditOperator.get()
            if (auditDirty.compareAndSet(true, false) && operator != null) {
                try {
                    exportAuditLog(operator)
                    git("add", "$folder/audit/", dir = repoDir)
                    val status = gitOutput("status", "--porcelain", dir = repoDir)
                    if (status.isNotBlank()) {
                        git("commit", "-m", "sync: audit log for $operator", dir = repoDir)
                        git("push", "origin", branch, dir = repoDir)
                    }
                } catch (e: Exception) {
                    log.warn("[GitSyncService][pollAndRefresh] Audit export/push failed: {}", e.message)
                }
            }

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

            // Import audit logs from other operators after pulling
            try {
                importAuditLogs(operator)
            } catch (e: Exception) {
                log.warn("[GitSyncService][pollAndRefresh] Audit import failed: {}", e.message)
            }
        } catch (e: Exception) {
            log.warn("[GitSyncService][pollAndRefresh] Failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Audit log sync
    // ------------------------------------------------------------------

    /**
     * Called by AuditService after writing a new entry.
     * Sets the dirty flag so the next poll cycle exports.
     */
    fun markAuditDirty(operator: String) {
        lastAuditOperator.set(operator)
        auditDirty.set(true)
        log.info("[GitSyncService][markAuditDirty] Flagged audit export for operator={}", operator)
    }

    /**
     * Export the current month's audit entries for the given operator
     * to {syncDir}/audit/{operator}/{YYYY-MM}.sql (full rewrite of current month).
     */
    fun exportAuditLog(operator: String) {
        val now = YearMonth.now(ZoneOffset.UTC)
        val monthStart = now.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
        val nextMonthStart = now.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()

        val auditDir = File(syncDir, "audit/$operator")
        auditDir.mkdirs()

        val columns = listOf("id", "timestamp", "operator", "purchase_id", "action", "input_json", "output_json", "duration_ms")
        val columnsStr = columns.joinToString(",")

        val db = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val lines = mutableListOf<String>()
        try {
            val stmt = db.prepareStatement(
                "SELECT $columnsStr FROM audit_log WHERE operator = ? AND timestamp >= ? AND timestamp < ? ORDER BY timestamp, id"
            )
            stmt.setString(1, operator)
            stmt.setString(2, monthStart)
            stmt.setString(3, nextMonthStart)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val values = columns.map { col ->
                    val value = rs.getString(col)
                    if (value == null) "NULL" else "'" + value.replace("'", "''").replace("\n", "\\n") + "'"
                }
                lines.add("INSERT INTO audit_log($columnsStr) VALUES(${values.joinToString(",")});")
            }
        } catch (e: Exception) {
            log.warn("[GitSyncService][exportAuditLog] Failed to export audit for {}: {}", operator, e.message)
            return
        } finally {
            db.close()
        }

        val file = File(auditDir, "${now}.sql")
        val header = "-- audit_log for $operator (${now})"
        file.writeText(header + "\n" + lines.joinToString("\n") + "\n")

        log.info("[GitSyncService][exportAuditLog] Exported {} entries for operator={} month={}", lines.size, operator, now)
    }

    /**
     * Import audit entries from all other operators' directories.
     * Uses INSERT OR IGNORE so UUID primary key deduplicates.
     */
    fun importAuditLogs(currentOperator: String?) {
        val auditBaseDir = File(syncDir, "audit")
        if (!auditBaseDir.exists() || !auditBaseDir.isDirectory) return

        val operatorDirs = auditBaseDir.listFiles()?.filter { it.isDirectory } ?: return

        val db = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        try {
            db.autoCommit = false
            var totalImported = 0

            for (opDir in operatorDirs) {
                // Skip the current operator's directory (local DB is authoritative for own entries)
                if (currentOperator != null && opDir.name == currentOperator) continue

                val sqlFiles = opDir.listFiles()?.filter { it.extension == "sql" } ?: continue
                for (sqlFile in sqlFiles) {
                    val lines = sqlFile.readLines().filter { it.startsWith("INSERT INTO") }
                    for (line in lines) {
                        try {
                            // Convert INSERT INTO to INSERT OR IGNORE INTO for idempotent import
                            val ignoreLine = line.replaceFirst("INSERT INTO", "INSERT OR IGNORE INTO")
                            db.createStatement().execute(ignoreLine.replace("\\n", "\n"))
                            totalImported++
                        } catch (e: Exception) {
                            log.warn("[GitSyncService][importAuditLogs] Failed to execute line from {}: {}", sqlFile.name, e.message)
                        }
                    }
                }
            }

            db.commit()
            if (totalImported > 0) {
                log.info("[GitSyncService][importAuditLogs] Processed {} INSERT OR IGNORE statements from other operators", totalImported)
            }
        } catch (e: Exception) {
            log.error("[GitSyncService][importAuditLogs] Import failed: {}", e.message)
            try { db.rollback() } catch (_: Exception) {}
        } finally {
            db.autoCommit = true
            db.close()
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

        // Deduplicate by primary key: if two lines have the same PK value(s),
        // keep the one with the latest timestamp (last column for review_status)
        val deduped = deduplicateByPrimaryKey(table, merged)

        // Sort for deterministic output
        val sortedMerged = deduped.sorted()

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

    /**
     * For tables with a unique primary key (like purchase_review_status keyed by purchase_id),
     * if multiple lines share the same PK value, keep only the one with the latest timestamp.
     * This handles the case where two users update the same purchase's status concurrently.
     */
    private fun deduplicateByPrimaryKey(table: SyncTable, lines: Set<String>): Set<String> {
        // Only deduplicate tables where the sort key is a single column (the PK)
        if (table.sortKey.contains(",")) return lines

        val pkIndex = table.columns.indexOf(table.sortKey)
        if (pkIndex < 0) return lines

        // Group by PK value extracted from the INSERT statement
        val byPk = mutableMapOf<String, MutableList<String>>()
        for (line in lines) {
            val pk = extractColumnValue(line, pkIndex)
            if (pk != null) {
                byPk.getOrPut(pk) { mutableListOf() }.add(line)
            }
        }

        // For PKs with multiple lines, keep the one with the latest timestamp
        // (timestamp is typically the last column)
        val result = mutableSetOf<String>()
        for ((_, pkLines) in byPk) {
            if (pkLines.size == 1) {
                result.add(pkLines[0])
            } else {
                // Pick the line with the latest timestamp value (lexicographic comparison works for ISO timestamps)
                val lastTimestampIndex = table.columns.size - 1
                val best = pkLines.maxByOrNull { extractColumnValue(it, lastTimestampIndex) ?: "" } ?: pkLines[0]
                result.add(best)
            }
        }
        return result
    }

    /**
     * Extract the Nth column value from an INSERT statement like:
     * INSERT INTO table(col1,col2) VALUES('val1','val2');
     */
    private fun extractColumnValue(insertLine: String, index: Int): String? {
        val valuesStart = insertLine.indexOf("VALUES(")
        if (valuesStart < 0) return null
        val valuesPart = insertLine.substring(valuesStart + 7).trimEnd(')', ';')
        // Simple split on comma, respecting quoted values
        val values = mutableListOf<String>()
        var current = StringBuilder()
        var inQuote = false
        for (ch in valuesPart) {
            when {
                ch == '\'' && !inQuote -> { inQuote = true }
                ch == '\'' && inQuote -> { inQuote = false }
                ch == ',' && !inQuote -> { values.add(current.toString()); current = StringBuilder() }
                else -> current.append(ch)
            }
        }
        values.add(current.toString())
        return values.getOrNull(index)
    }

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

        // Import audit logs from other operators
        try {
            importAuditLogs(lastAuditOperator.get())
        } catch (e: Exception) {
            log.warn("[GitSyncService][initialImport] Audit import failed: {}", e.message)
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
