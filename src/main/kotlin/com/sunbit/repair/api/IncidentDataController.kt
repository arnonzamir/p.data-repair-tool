package com.sunbit.repair.api

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import java.nio.file.Path
import java.sql.DriverManager

@RestController
@RequestMapping("/api/v1/incident-data")
@CrossOrigin(origins = ["*"])
class IncidentDataController(
    @Value("\${cache.db-path:#{null}}") private val configuredDbPath: String?,
) {
    private val log = LoggerFactory.getLogger(IncidentDataController::class.java)

    private val dbUrl by lazy {
        val path = configuredDbPath
            ?: Path.of(System.getProperty("user.home"), ".sunbit", "purchase-repair-cache.db").toString()
        "jdbc:sqlite:$path"
    }

    @GetMapping("/loan-performance/{purchaseId}")
    fun getLoanPerformance(@PathVariable purchaseId: Long): Map<String, Any?>? {
        return try {
            DriverManager.getConnection(dbUrl).use { conn ->
                conn.prepareStatement(
                    "SELECT * FROM loan_performance WHERE purchase_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, purchaseId)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        val meta = rs.metaData
                        val map = mutableMapOf<String, Any?>()
                        for (i in 1..meta.columnCount) {
                            map[meta.getColumnName(i)] = rs.getObject(i)
                        }
                        map
                    } else null
                }
            }
        } catch (e: Exception) {
            log.warn("[IncidentDataController][getLoanPerformance] Failed for {}: {}", purchaseId, e.message)
            null
        }
    }

    @GetMapping("/checkout-actions/{purchaseId}")
    fun getCheckoutActions(@PathVariable purchaseId: Long): List<Map<String, Any?>> {
        return try {
            DriverManager.getConnection(dbUrl).use { conn ->
                conn.prepareStatement(
                    "SELECT * FROM checkout_actions WHERE purchase_id = ? ORDER BY action_date"
                ).use { stmt ->
                    stmt.setLong(1, purchaseId)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<Map<String, Any?>>()
                    val meta = rs.metaData
                    while (rs.next()) {
                        val map = mutableMapOf<String, Any?>()
                        for (i in 1..meta.columnCount) {
                            map[meta.getColumnName(i)] = rs.getObject(i)
                        }
                        results.add(map)
                    }
                    results
                }
            }
        } catch (e: Exception) {
            log.warn("[IncidentDataController][getCheckoutActions] Failed for {}: {}", purchaseId, e.message)
            emptyList()
        }
    }
}
