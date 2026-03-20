package com.sunbit.repair.replicator

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunbit.repair.domain.ReplicationTarget
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

@Service
class MysqlExecutor(
    private val objectMapper: ObjectMapper,
    @Value("\${mysql.local.host:sunbit-mysql}") private val localHost: String,
    @Value("\${mysql.local.port:30306}") private val localPort: Int,
    @Value("\${mysql.local.user:root}") private val localUser: String,
    @Value("\${mysql.local.password:root}") private val localPassword: String,
    @Value("\${mysql.local.database:purchase}") private val localDatabase: String,
    @Value("\${mysql.staging.credentials-path:\${user.home}/.sunbit/mysql-staging.json}") private val stagingCredsPath: String,
) {
    private val log = LoggerFactory.getLogger(MysqlExecutor::class.java)

    /**
     * Check whether a purchase ID already exists in the target MySQL.
     * Returns row counts per table, or null if the target is unreachable.
     */
    fun checkExists(purchaseId: Long, target: ReplicationTarget): Map<String, Int>? {
        log.info("[MysqlExecutor][checkExists] purchaseId={} target={}", purchaseId, target)
        return try {
            val (url, user, password) = connectionParams(target)
            DriverManager.getConnection(url, user, password).use { conn ->
                val counts = mutableMapOf<String, Int>()
                val queries = mapOf(
                    "purchases" to "SELECT COUNT(*) FROM purchases WHERE id = ?",
                    "paymentplans" to "SELECT COUNT(*) FROM paymentplans WHERE purchase_id = ?",
                    "payments" to "SELECT COUNT(*) FROM payments p JOIN paymentplans pp ON p.paymentplan_id = pp.id WHERE pp.purchase_id = ?",
                    "payment_actions" to "SELECT COUNT(*) FROM payment_actions WHERE purchase_id = ?",
                    "charge_transactions" to "SELECT COUNT(*) FROM charge_transactions WHERE purchase_id = ?",
                    "items" to "SELECT COUNT(*) FROM items WHERE purchase_id = ?",
                    "purchases_emails" to "SELECT COUNT(*) FROM purchases_emails WHERE purchase_id = ?",
                )
                for ((table, query) in queries) {
                    conn.prepareStatement(query).use { stmt ->
                        stmt.setLong(1, purchaseId)
                        val rs = stmt.executeQuery()
                        rs.next()
                        val count = rs.getInt(1)
                        if (count > 0) counts[table] = count
                    }
                }
                counts
            }
        } catch (e: Exception) {
            log.warn("[MysqlExecutor][checkExists] Cannot check target {}: {}", target, e.message)
            null
        }
    }

    fun connectionParams(target: ReplicationTarget, schema: String = "purchase"): Triple<String, String, String> {
        return when (target) {
            ReplicationTarget.LOCAL -> Triple("jdbc:mysql://$localHost:$localPort/$schema", localUser, localPassword)
            ReplicationTarget.STAGING -> {
                val creds = loadStagingCredentials()
                Triple("jdbc:mysql://${creds.uri}:3306/$schema", creds.username, creds.password)
            }
        }
    }

    fun execute(sql: String, target: ReplicationTarget, schema: String = "purchase"): String {
        log.info("[MysqlExecutor][execute] target={} schema={} sqlLength={}", target, schema, sql.length)

        val (url, user, password) = connectionParams(target, schema)

        val executionLog = StringBuilder()
        executionLog.appendLine("Connecting to MySQL [$target] ...")

        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val statements = sql.split(";")
                    .map { chunk ->
                        // Strip comment-only lines within each chunk, keep the SQL
                        chunk.lines()
                            .filter { line -> !line.trimStart().startsWith("--") }
                            .joinToString("\n")
                            .trim()
                    }
                    .filter { it.isNotBlank() }

                var executed = 0
                val stmt = conn.createStatement()
                for (sqlStmt in statements) {
                    stmt.execute("$sqlStmt;")
                    executed++
                }

                conn.commit()
                executionLog.appendLine("Executed $executed statements successfully.")
                log.info("[MysqlExecutor][execute] target={} statements={}", target, executed)
            } catch (e: Exception) {
                conn.rollback()
                val msg = "Execution failed, rolled back: ${e.message}"
                executionLog.appendLine(msg)
                log.error("[MysqlExecutor][execute] {}", msg, e)
                throw e
            }
        }

        return executionLog.toString()
    }

    private fun loadStagingCredentials(): StagingCredentials {
        val path = Path.of(stagingCredsPath)
        if (!path.toFile().exists()) {
            throw IllegalStateException(
                "[MysqlExecutor][loadStagingCredentials] Staging credentials not found at $path. " +
                    "Generate them via your Vault CLI or credentials tool."
            )
        }

        log.info("[MysqlExecutor][loadStagingCredentials] Reading staging credentials from {}", path)

        @Suppress("UNCHECKED_CAST")
        val creds = objectMapper.readValue(path.toFile(), Map::class.java) as Map<String, Any?>

        // Warn if credentials are old (may have expired)
        val createDate = creds["create_date"]?.toString()
        if (!createDate.isNullOrBlank()) {
            try {
                val created = java.time.OffsetDateTime.parse(createDate)
                val ageMinutes = java.time.Duration.between(created.toInstant(), Instant.now()).toMinutes()
                if (ageMinutes > 60) {
                    log.warn(
                        "[MysqlExecutor][loadStagingCredentials] Staging credentials are {} minutes old -- they may have expired. Regenerate if you get auth errors.",
                        ageMinutes,
                    )
                }
            } catch (e: Exception) {
                // Ignore parse errors on create_date
            }
        }

        return StagingCredentials(
            uri = creds["uri"] as? String
                ?: throw IllegalStateException("[MysqlExecutor][loadStagingCredentials] Missing 'uri' field in staging credentials"),
            username = creds["username"] as? String
                ?: throw IllegalStateException("[MysqlExecutor][loadStagingCredentials] Missing 'username' field in staging credentials"),
            password = creds["password"] as? String
                ?: throw IllegalStateException("[MysqlExecutor][loadStagingCredentials] Missing 'password' field in staging credentials"),
        )
    }

    private data class StagingCredentials(
        val uri: String,
        val username: String,
        val password: String,
    )
}
