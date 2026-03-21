package com.sunbit.repair.loader

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunbit.repair.domain.PurchaseSnapshot
import com.sunbit.repair.domain.ReplicationTarget
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import jakarta.annotation.PostConstruct

data class CachedSnapshot(
    val snapshot: PurchaseSnapshot,
    val cachedAt: Instant,
    val replications: List<ReplicationRecord> = emptyList(),
)

data class ReplicationRecord(
    val target: String,
    val replicatedAt: String,
    val replicatedPurchaseId: Long,
    val idOffset: Long,
    val hasRollback: Boolean = false,
)

@Service
class PurchaseCacheService(
    private val loaderService: PurchaseLoaderService,
    private val objectMapper: ObjectMapper,
    @Value("\${cache.db-path:#{null}}") private val configuredDbPath: String?,
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val syncService: com.sunbit.repair.sync.GitSyncService? = null,
) {
    private val log = LoggerFactory.getLogger(PurchaseCacheService::class.java)
    private lateinit var dbUrl: String

    @PostConstruct
    fun init() {
        val dbPath = configuredDbPath
            ?: Path.of(System.getProperty("user.home"), ".sunbit", "purchase-repair-cache.db").toString()

        val parent = Path.of(dbPath).parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        dbUrl = "jdbc:sqlite:$dbPath"

        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS snapshot_cache (
                        purchase_id   INTEGER PRIMARY KEY,
                        snapshot_json TEXT    NOT NULL,
                        cached_at     TEXT    NOT NULL
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS replications (
                        purchase_id          INTEGER NOT NULL,
                        target               TEXT    NOT NULL,
                        replicated_at        TEXT    NOT NULL,
                        replicated_purchase_id INTEGER NOT NULL,
                        id_offset            INTEGER NOT NULL,
                        rollback_sql         TEXT,
                        PRIMARY KEY (purchase_id, target)
                    )
                """)
                // Migration: add rollback_sql column if upgrading from older schema
                try {
                    stmt.execute("ALTER TABLE replications ADD COLUMN rollback_sql TEXT")
                } catch (_: Exception) {
                    // Column already exists
                }

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS purchase_review_status (
                        purchase_id  INTEGER PRIMARY KEY,
                        status       TEXT    NOT NULL DEFAULT 'not-seen',
                        updated_at   TEXT    NOT NULL,
                        updated_by   TEXT    NOT NULL
                    )
                """)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS purchase_notes (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        purchase_id INTEGER NOT NULL,
                        author      TEXT    NOT NULL,
                        content     TEXT    NOT NULL,
                        created_at  TEXT    NOT NULL
                    )
                """)
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_notes_purchase ON purchase_notes(purchase_id)
                """)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS purchase_lists (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        name       TEXT    NOT NULL UNIQUE,
                        created_at TEXT    NOT NULL
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS purchase_list_items (
                        list_id     INTEGER NOT NULL,
                        purchase_id INTEGER NOT NULL,
                        added_at    TEXT    NOT NULL,
                        PRIMARY KEY (list_id, purchase_id),
                        FOREIGN KEY (list_id) REFERENCES purchase_lists(id) ON DELETE CASCADE
                    )
                """)
            }
        }

        val count = getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM snapshot_cache")
                rs.next()
                rs.getInt(1)
            }
        }

        log.info("[PurchaseCacheService][init] SQLite cache at {} ({} cached snapshots)", dbPath, count)
    }

    private fun getConnection() = DriverManager.getConnection(dbUrl)

    // -----------------------------------------------------------------------
    // Snapshot cache
    // -----------------------------------------------------------------------

    fun getSnapshot(purchaseId: Long, forceRefresh: Boolean = false): CachedSnapshot {
        if (!forceRefresh) {
            val cached = readFromCache(purchaseId)
            if (cached != null) {
                log.info("[PurchaseCacheService][getSnapshot] Cache HIT for purchase {} (cached at {})",
                    purchaseId, cached.cachedAt)
                return cached
            }
        }

        log.info("[PurchaseCacheService][getSnapshot] Cache {} for purchase {} -- loading from Snowflake",
            if (forceRefresh) "REFRESH" else "MISS", purchaseId)

        val snapshot = loaderService.loadPurchase(purchaseId)
        val cachedAt = Instant.now()
        writeSnapshotToCache(purchaseId, snapshot, cachedAt)
        val replications = getReplications(purchaseId)
        return CachedSnapshot(snapshot = snapshot, cachedAt = cachedAt, replications = replications)
    }

    fun getSnapshots(purchaseIds: List<Long>, forceRefresh: Boolean = false): List<CachedSnapshot> {
        return purchaseIds.mapNotNull { id ->
            try {
                getSnapshot(id, forceRefresh)
            } catch (e: Exception) {
                log.error("[PurchaseCacheService][getSnapshots] Failed to load purchase {}: {}", id, e.message)
                null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Replication tracking
    // -----------------------------------------------------------------------

    fun recordReplication(purchaseId: Long, target: ReplicationTarget, idOffset: Long, rollbackSql: String) {
        val replicatedPurchaseId = purchaseId + idOffset
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO replications (purchase_id, target, replicated_at, replicated_purchase_id, id_offset, rollback_sql)
                VALUES (?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.setString(2, target.name)
                stmt.setString(3, Instant.now().toString())
                stmt.setLong(4, replicatedPurchaseId)
                stmt.setLong(5, idOffset)
                stmt.setString(6, rollbackSql)
                stmt.executeUpdate()
            }
        }
        log.info("[PurchaseCacheService][recordReplication] Recorded replication with rollback script: purchase {} -> {} as {}",
            purchaseId, target, replicatedPurchaseId)
    }

    fun getRollbackSql(purchaseId: Long, target: ReplicationTarget): String? {
        return getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT rollback_sql FROM replications WHERE purchase_id = ? AND target = ?"
            ).use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.setString(2, target.name)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString("rollback_sql") else null
            }
        }
    }

    fun removeReplication(purchaseId: Long, target: ReplicationTarget) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM replications WHERE purchase_id = ? AND target = ?").use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.setString(2, target.name)
                stmt.executeUpdate()
            }
        }
        log.info("[PurchaseCacheService][removeReplication] Removed replication record: purchase {} target {}", purchaseId, target)
    }

    fun getReplications(purchaseId: Long): List<ReplicationRecord> {
        return getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT target, replicated_at, replicated_purchase_id, id_offset, rollback_sql FROM replications WHERE purchase_id = ?"
            ).use { stmt ->
                stmt.setLong(1, purchaseId)
                val rs = stmt.executeQuery()
                val records = mutableListOf<ReplicationRecord>()
                while (rs.next()) {
                    records.add(ReplicationRecord(
                        target = rs.getString("target"),
                        replicatedAt = rs.getString("replicated_at"),
                        replicatedPurchaseId = rs.getLong("replicated_purchase_id"),
                        idOffset = rs.getLong("id_offset"),
                        hasRollback = rs.getString("rollback_sql") != null,
                    ))
                }
                records
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cache management
    // -----------------------------------------------------------------------

    fun evict(purchaseId: Long) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM snapshot_cache WHERE purchase_id = ?").use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.executeUpdate()
            }
        }
        log.info("[PurchaseCacheService][evict] Evicted purchase {} from cache", purchaseId)
    }

    fun clear() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val deleted = stmt.executeUpdate("DELETE FROM snapshot_cache")
                log.info("[PurchaseCacheService][clear] Cleared {} entries from cache", deleted)
            }
        }
    }

    fun stats(): CacheStats {
        val entries = mutableListOf<CacheEntryInfo>()
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT sc.purchase_id, sc.cached_at, LENGTH(sc.snapshot_json) as size_bytes,
                           GROUP_CONCAT(r.target) as replicated_to
                    FROM snapshot_cache sc
                    LEFT JOIN replications r ON sc.purchase_id = r.purchase_id
                    GROUP BY sc.purchase_id
                    ORDER BY sc.cached_at DESC
                """)
                while (rs.next()) {
                    entries.add(CacheEntryInfo(
                        purchaseId = rs.getLong("purchase_id"),
                        cachedAt = rs.getString("cached_at"),
                        sizeBytes = rs.getInt("size_bytes"),
                        replicatedTo = rs.getString("replicated_to")?.split(",") ?: emptyList(),
                    ))
                }
            }
        }
        return CacheStats(size = entries.size, entries = entries)
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private fun readFromCache(purchaseId: Long): CachedSnapshot? {
        return getConnection().use { conn ->
            conn.prepareStatement("SELECT snapshot_json, cached_at FROM snapshot_cache WHERE purchase_id = ?").use { stmt ->
                stmt.setLong(1, purchaseId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val json = rs.getString("snapshot_json")
                    val cachedAt = Instant.parse(rs.getString("cached_at"))
                    val snapshot = objectMapper.readValue(json, PurchaseSnapshot::class.java)
                    val replications = getReplications(purchaseId)
                    CachedSnapshot(snapshot = snapshot, cachedAt = cachedAt, replications = replications)
                } else {
                    null
                }
            }
        }
    }

    fun writeSnapshotToCache(purchaseId: Long, snapshot: PurchaseSnapshot, cachedAt: Instant) {
        val json = objectMapper.writeValueAsString(snapshot)
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO snapshot_cache (purchase_id, snapshot_json, cached_at)
                VALUES (?, ?, ?)
            """).use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.setString(2, json)
                stmt.setString(3, cachedAt.toString())
                stmt.executeUpdate()
            }
        }
        log.info("[PurchaseCacheService][writeSnapshotToCache] Cached purchase {} ({} bytes)", purchaseId, json.length)
    }

    // -----------------------------------------------------------------------
    // Purchase lists
    // -----------------------------------------------------------------------

    fun createList(name: String): PurchaseList {
        val now = Instant.now().toString()
        getConnection().use { conn ->
            conn.prepareStatement("INSERT INTO purchase_lists (name, created_at) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, name)
                stmt.setString(2, now)
                stmt.executeUpdate()
            }
        }
        val id = getConnection().use { conn ->
            conn.prepareStatement("SELECT id FROM purchase_lists WHERE name = ?").use { stmt ->
                stmt.setString(1, name)
                val rs = stmt.executeQuery()
                rs.next()
                rs.getLong("id")
            }
        }
        log.info("[PurchaseCacheService][createList] Created list '{}' id={}", name, id)
        triggerSync("purchase_lists")
        return PurchaseList(id = id, name = name, createdAt = now, purchaseIds = emptyList())
    }

    fun deleteList(listId: Long) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM purchase_list_items WHERE list_id = ?").use { it.setLong(1, listId); it.executeUpdate() }
            conn.prepareStatement("DELETE FROM purchase_lists WHERE id = ?").use { it.setLong(1, listId); it.executeUpdate() }
        }
        log.info("[PurchaseCacheService][deleteList] Deleted list {}", listId)
        triggerSync("purchase_lists")
        triggerSync("purchase_list_items")
    }

    fun getLists(): List<PurchaseList> {
        return getConnection().use { conn ->
            val lists = mutableListOf<PurchaseList>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT id, name, created_at FROM purchase_lists ORDER BY created_at DESC")
                while (rs.next()) {
                    val id = rs.getLong("id")
                    lists.add(PurchaseList(
                        id = id,
                        name = rs.getString("name"),
                        createdAt = rs.getString("created_at"),
                        purchaseIds = emptyList(),
                    ))
                }
            }
            // Fill purchase IDs
            for (list in lists) {
                val ids = mutableListOf<Long>()
                conn.prepareStatement("SELECT purchase_id FROM purchase_list_items WHERE list_id = ? ORDER BY added_at").use { stmt ->
                    stmt.setLong(1, list.id)
                    val rs = stmt.executeQuery()
                    while (rs.next()) ids.add(rs.getLong("purchase_id"))
                }
                lists[lists.indexOf(list)] = list.copy(purchaseIds = ids)
            }
            lists
        }
    }

    fun getList(listId: Long): PurchaseList? {
        return getConnection().use { conn ->
            val list = conn.prepareStatement("SELECT id, name, created_at FROM purchase_lists WHERE id = ?").use { stmt ->
                stmt.setLong(1, listId)
                val rs = stmt.executeQuery()
                if (rs.next()) PurchaseList(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    createdAt = rs.getString("created_at"),
                    purchaseIds = emptyList(),
                ) else null
            }
            if (list != null) {
                val ids = mutableListOf<Long>()
                conn.prepareStatement("SELECT purchase_id FROM purchase_list_items WHERE list_id = ? ORDER BY added_at").use { stmt ->
                    stmt.setLong(1, list.id)
                    val rs = stmt.executeQuery()
                    while (rs.next()) ids.add(rs.getLong("purchase_id"))
                }
                list.copy(purchaseIds = ids)
            } else null
        }
    }

    fun addToList(listId: Long, purchaseId: Long) {
        getConnection().use { conn ->
            conn.prepareStatement("INSERT OR IGNORE INTO purchase_list_items (list_id, purchase_id, added_at) VALUES (?, ?, ?)").use { stmt ->
                stmt.setLong(1, listId)
                stmt.setLong(2, purchaseId)
                stmt.setString(3, Instant.now().toString())
                stmt.executeUpdate()
            }
        }
        triggerSync("purchase_list_items")
    }

    fun removeFromList(listId: Long, purchaseId: Long) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM purchase_list_items WHERE list_id = ? AND purchase_id = ?").use { stmt ->
                stmt.setLong(1, listId)
                stmt.setLong(2, purchaseId)
                stmt.executeUpdate()
            }
        }
        triggerSync("purchase_list_items")
    }

    /**
     * Get summary info for a purchase without deserializing the full snapshot.
     * Returns cache status, replication status, and basic plan info.
     */
    fun getPurchaseSummary(purchaseId: Long): PurchaseSummary {
        val cacheInfo = getConnection().use { conn ->
            conn.prepareStatement("SELECT cached_at, LENGTH(snapshot_json) as size_bytes FROM snapshot_cache WHERE purchase_id = ?").use { stmt ->
                stmt.setLong(1, purchaseId)
                val rs = stmt.executeQuery()
                if (rs.next()) CacheInfo(cachedAt = rs.getString("cached_at"), sizeBytes = rs.getInt("size_bytes"))
                else null
            }
        }
        val replications = getReplications(purchaseId)
        return PurchaseSummary(
            purchaseId = purchaseId,
            cached = cacheInfo != null,
            cachedAt = cacheInfo?.cachedAt,
            replications = replications,
        )
    }

    // -----------------------------------------------------------------------
    // Purchase notes (immutable -- append only)
    // -----------------------------------------------------------------------

    fun addNote(purchaseId: Long, author: String, content: String): PurchaseNote {
        val now = Instant.now().toString()
        getConnection().use { conn ->
            conn.prepareStatement("INSERT INTO purchase_notes (purchase_id, author, content, created_at) VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.setString(2, author)
                stmt.setString(3, content)
                stmt.setString(4, now)
                stmt.executeUpdate()
            }
        }
        val id = getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT last_insert_rowid()")
                rs.next(); rs.getLong(1)
            }
        }
        log.info("[PurchaseCacheService][addNote] Added note {} for purchase {} by {}", id, purchaseId, author)
        triggerSync("purchase_notes")
        return PurchaseNote(id = id, purchaseId = purchaseId, author = author, content = content, createdAt = now)
    }

    fun getNotes(purchaseId: Long): List<PurchaseNote> {
        return getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, purchase_id, author, content, created_at FROM purchase_notes WHERE purchase_id = ? ORDER BY created_at DESC"
            ).use { stmt ->
                stmt.setLong(1, purchaseId)
                val rs = stmt.executeQuery()
                val notes = mutableListOf<PurchaseNote>()
                while (rs.next()) {
                    notes.add(PurchaseNote(
                        id = rs.getLong("id"),
                        purchaseId = rs.getLong("purchase_id"),
                        author = rs.getString("author"),
                        content = rs.getString("content"),
                        createdAt = rs.getString("created_at"),
                    ))
                }
                notes
            }
        }
    }

    /** Get all list names a purchase belongs to. */
    fun getListsForPurchase(purchaseId: Long): List<String> {
        return getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT pl.name FROM purchase_lists pl
                JOIN purchase_list_items pli ON pl.id = pli.list_id
                WHERE pli.purchase_id = ?
                ORDER BY pl.name
            """).use { stmt ->
                stmt.setLong(1, purchaseId)
                val rs = stmt.executeQuery()
                val names = mutableListOf<String>()
                while (rs.next()) names.add(rs.getString("name"))
                names
            }
        }
    }

    // -----------------------------------------------------------------------
    // Purchase review status
    // -----------------------------------------------------------------------

    fun getReviewStatus(purchaseId: Long): ReviewStatus {
        return getConnection().use { conn ->
            conn.prepareStatement("SELECT status, updated_at, updated_by FROM purchase_review_status WHERE purchase_id = ?").use { stmt ->
                stmt.setLong(1, purchaseId)
                val rs = stmt.executeQuery()
                if (rs.next()) ReviewStatus(
                    purchaseId = purchaseId,
                    status = rs.getString("status"),
                    updatedAt = rs.getString("updated_at"),
                    updatedBy = rs.getString("updated_by"),
                ) else ReviewStatus(purchaseId = purchaseId, status = "not-seen", updatedAt = null, updatedBy = null)
            }
        }
    }

    fun setReviewStatus(purchaseId: Long, status: String, operator: String): ReviewStatus {
        val now = Instant.now().toString()
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO purchase_review_status (purchase_id, status, updated_at, updated_by)
                VALUES (?, ?, ?, ?)
            """).use { stmt ->
                stmt.setLong(1, purchaseId)
                stmt.setString(2, status)
                stmt.setString(3, now)
                stmt.setString(4, operator)
                stmt.executeUpdate()
            }
        }
        log.info("[PurchaseCacheService][setReviewStatus] purchase={} status={} by={}", purchaseId, status, operator)
        triggerSync("purchase_review_status")
        return ReviewStatus(purchaseId = purchaseId, status = status, updatedAt = now, updatedBy = operator)
    }

    fun getReviewStatuses(purchaseIds: List<Long>): Map<Long, ReviewStatus> {
        if (purchaseIds.isEmpty()) return emptyMap()
        return getConnection().use { conn ->
            val sql = "SELECT purchase_id, status, updated_at, updated_by FROM purchase_review_status WHERE purchase_id IN (${purchaseIds.joinToString(",")})"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                val map = mutableMapOf<Long, ReviewStatus>()
                while (rs.next()) {
                    val pid = rs.getLong("purchase_id")
                    map[pid] = ReviewStatus(pid, rs.getString("status"), rs.getString("updated_at"), rs.getString("updated_by"))
                }
                map
            }
        }
    }

    // -----------------------------------------------------------------------
    // Sync trigger
    // -----------------------------------------------------------------------

    private fun triggerSync(tableName: String) {
        try {
            syncService?.onLocalWrite(tableName)
        } catch (e: Exception) {
            log.warn("[PurchaseCacheService][triggerSync] Sync failed for {}: {}", tableName, e.message)
        }
    }
}

data class PurchaseNote(
    val id: Long,
    val purchaseId: Long,
    val author: String,
    val content: String,
    val createdAt: String,
)

data class ReviewStatus(
    val purchaseId: Long,
    val status: String,
    val updatedAt: String?,
    val updatedBy: String?,
)

data class CacheStats(
    val size: Int,
    val entries: List<CacheEntryInfo>,
)

data class CacheEntryInfo(
    val purchaseId: Long,
    val cachedAt: String,
    val sizeBytes: Int,
    val replicatedTo: List<String> = emptyList(),
)

data class PurchaseList(
    val id: Long,
    val name: String,
    val createdAt: String,
    val purchaseIds: List<Long>,
)

data class PurchaseSummary(
    val purchaseId: Long,
    val cached: Boolean,
    val cachedAt: String?,
    val replications: List<ReplicationRecord>,
)

data class CacheInfo(
    val cachedAt: String,
    val sizeBytes: Int,
)
