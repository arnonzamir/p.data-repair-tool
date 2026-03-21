package com.sunbit.repair.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunbit.repair.domain.AuditAction
import com.sunbit.repair.domain.AuditEntry
import com.sunbit.repair.domain.PurchaseSnapshot
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import jakarta.annotation.PostConstruct

/**
 * SQLite-backed audit log. Persists across restarts.
 */
@Service
class AuditService(
    private val objectMapper: ObjectMapper,
    @Value("\${cache.db-path:#{null}}") private val configuredDbPath: String?,
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val syncService: com.sunbit.repair.sync.GitSyncService? = null,
) {
    private val log = LoggerFactory.getLogger(AuditService::class.java)
    private lateinit var dbUrl: String

    @PostConstruct
    fun init() {
        val dbPath = configuredDbPath
            ?: Path.of(System.getProperty("user.home"), ".sunbit", "purchase-repair-cache.db").toString()
        dbUrl = "jdbc:sqlite:$dbPath"

        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id          TEXT    PRIMARY KEY,
                        timestamp   TEXT    NOT NULL,
                        operator    TEXT    NOT NULL,
                        purchase_id INTEGER NOT NULL,
                        action      TEXT    NOT NULL,
                        input_json  TEXT,
                        output_json TEXT,
                        duration_ms INTEGER
                    )
                """)
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_audit_purchase ON audit_log(purchase_id)
                """)
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp DESC)
                """)
            }
        }

        val count = getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM audit_log")
                rs.next(); rs.getInt(1)
            }
        }
        log.info("[AuditService][init] SQLite audit log ready ({} entries)", count)
    }

    private fun getConnection() = DriverManager.getConnection(dbUrl)

    fun record(
        operator: String,
        purchaseId: Long,
        action: AuditAction,
        input: Map<String, Any>? = null,
        output: Map<String, Any>? = null,
        snapshotBefore: PurchaseSnapshot? = null,
        snapshotAfter: PurchaseSnapshot? = null,
        durationMs: Long? = null,
    ): AuditEntry {
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            timestamp = Instant.now(),
            operator = operator,
            purchaseId = purchaseId,
            action = action,
            input = input,
            output = output,
            snapshotBefore = snapshotBefore,
            snapshotAfter = snapshotAfter,
            durationMs = durationMs,
        )

        // Persist to SQLite (skip snapshots -- too large, not needed in audit)
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO audit_log (id, timestamp, operator, purchase_id, action, input_json, output_json, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                stmt.setString(1, entry.id)
                stmt.setString(2, entry.timestamp.toString())
                stmt.setString(3, entry.operator)
                stmt.setLong(4, entry.purchaseId)
                stmt.setString(5, entry.action.name)
                stmt.setString(6, if (input != null) objectMapper.writeValueAsString(input) else null)
                stmt.setString(7, if (output != null) objectMapper.writeValueAsString(output) else null)
                stmt.setLong(8, durationMs ?: 0)
                stmt.executeUpdate()
            }
        }

        log.info("[AuditService][record] action={} purchaseId={} operator={} id={}",
            action, purchaseId, operator, entry.id)

        // Flag the audit log as dirty so the next sync poll exports it
        try {
            syncService?.markAuditDirty(operator)
        } catch (e: Exception) {
            log.warn("[AuditService][record] Failed to mark audit dirty for sync: {}", e.message)
        }

        return entry
    }

    fun getByPurchase(purchaseId: Long): List<AuditEntry> {
        return getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, timestamp, operator, purchase_id, action, input_json, output_json, duration_ms FROM audit_log WHERE purchase_id = ? ORDER BY timestamp DESC"
            ).use { stmt ->
                stmt.setLong(1, purchaseId)
                mapResults(stmt.executeQuery())
            }
        }
    }

    fun getRecent(limit: Int = 50, offset: Int = 0): List<AuditEntry> {
        return getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, timestamp, operator, purchase_id, action, input_json, output_json, duration_ms FROM audit_log ORDER BY timestamp DESC LIMIT ? OFFSET ?"
            ).use { stmt ->
                stmt.setInt(1, limit)
                stmt.setInt(2, offset)
                mapResults(stmt.executeQuery())
            }
        }
    }

    fun getById(id: String): AuditEntry? {
        return getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, timestamp, operator, purchase_id, action, input_json, output_json, duration_ms FROM audit_log WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, id)
                mapResults(stmt.executeQuery()).firstOrNull()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapResults(rs: java.sql.ResultSet): List<AuditEntry> {
        val entries = mutableListOf<AuditEntry>()
        while (rs.next()) {
            val inputJson = rs.getString("input_json")
            val outputJson = rs.getString("output_json")
            entries.add(AuditEntry(
                id = rs.getString("id"),
                timestamp = Instant.parse(rs.getString("timestamp")),
                operator = rs.getString("operator"),
                purchaseId = rs.getLong("purchase_id"),
                action = AuditAction.valueOf(rs.getString("action")),
                input = if (inputJson != null) objectMapper.readValue(inputJson, Map::class.java) as Map<String, Any> else null,
                output = if (outputJson != null) objectMapper.readValue(outputJson, Map::class.java) as Map<String, Any> else null,
                snapshotBefore = null,
                snapshotAfter = null,
                durationMs = rs.getLong("duration_ms").let { if (it == 0L) null else it },
            ))
        }
        return entries
    }
}
