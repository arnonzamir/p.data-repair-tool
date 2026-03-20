package com.sunbit.repair.replicator

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunbit.repair.domain.BatchReplicationResult
import com.sunbit.repair.domain.ReplicationRequest
import com.sunbit.repair.domain.ReplicationResult
import com.sunbit.repair.domain.ReplicationTarget
import com.sunbit.repair.loader.PurchaseCacheService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class ReplicatorService(
    private val extractor: SnowflakeExtractor,
    private val anonymizer: PiiAnonymizer,
    private val sqlGenerator: SqlGenerator,
    private val mysqlExecutor: MysqlExecutor,
    private val objectMapper: ObjectMapper,
    private val cacheService: PurchaseCacheService,
    @Value("\${replication.id-offset:100000000}") private val defaultIdOffset: Long,
    @Value("\${replication.local.retailer-id:14}") private val localRetailerId: Long,
    @Value("\${replication.staging.retailer-id:14}") private val stagingRetailerId: Long,
) {
    private val log = LoggerFactory.getLogger(ReplicatorService::class.java)

    /**
     * Returns the preconfigured defaults for a given target environment.
     */
    fun getTargetDefaults(target: ReplicationTarget): Map<String, Any> {
        return when (target) {
            ReplicationTarget.LOCAL -> mapOf(
                "retailerId" to localRetailerId,
                "idOffset" to defaultIdOffset,
            )
            ReplicationTarget.STAGING -> mapOf(
                "retailerId" to stagingRetailerId,
                "idOffset" to defaultIdOffset,
            )
        }
    }

    fun replicate(request: ReplicationRequest): ReplicationResult {
        val retailerId = when (request.target) {
            ReplicationTarget.LOCAL -> localRetailerId
            ReplicationTarget.STAGING -> stagingRetailerId
        }
        val idOffset = if (request.idOffset == 0L) defaultIdOffset else request.idOffset

        log.info("[ReplicatorService][replicate] purchaseId={} target={} execute={} retailerId={} offset={}",
            request.purchaseId, request.target, request.execute, retailerId, idOffset)

        val appliedDefaults = mapOf<String, Any>(
            "retailerId" to retailerId,
            "idOffset" to idOffset,
        )

        try {
            // 1. Extract from Snowflake
            val rawData = extractor.extract(request.purchaseId)

            // 2. Determine original state for geographic preservation
            val originalState = rawData["customer_details"]
                ?.firstOrNull()
                ?.get("STATE")
                ?.toString()

            // 3. Build anonymized identity
            val identity = anonymizer.buildAnonymizedIdentity(request.purchaseId, originalState, request.namePrefix ?: "Test")

            // 4. Anonymize PII
            val (anonymizedData, piiLog) = anonymizer.anonymize(rawData, identity, request.purchaseId)

            // 5. Apply overrides (retailer ID, external IDs, processor tx IDs)
            val overriddenData = applyOverrides(anonymizedData, request, retailerId)

            // 5b. Fill missing NOT NULL columns with sensible defaults
            val finalData = fillMissingDefaults(overriddenData)

            val isLocal = request.target == ReplicationTarget.LOCAL

            // 7. Generate purchase schema SQL (no skipped tables -- everything is replicated)
            val purchaseInsertSql = sqlGenerator.generateInserts(finalData, idOffset, emptySet(), emptyMap(), isLocal)
            val purchaseRollbackSql = sqlGenerator.generateRollback(finalData, idOffset, emptySet(), isLocal)

            // 8. Generate charge schema SQL
            val chargeInsertSql = sqlGenerator.generateChargeInserts(finalData, idOffset)
            val chargeRollbackSql = sqlGenerator.generateChargeRollback(finalData, idOffset)

            // 9. Combined SQL
            val insertSql = purchaseInsertSql + "\n-- ===== CHARGE SCHEMA =====\n\n" + chargeInsertSql
            val rollbackSql = chargeRollbackSql + "\n-- ===== PURCHASE SCHEMA =====\n\n" + purchaseRollbackSql

            // 10. Count rows per table (excluding skipped)
            val tableRowCounts = mutableMapOf<String, Int>()
            finalData.filterKeys { !it.startsWith("charge_") }
                .forEach { (k, v) -> tableRowCounts[k] = v.size }
            finalData.filterKeys { it.startsWith("charge_") }
                .forEach { (k, v) -> tableRowCounts[k] = v.size }

            // 11. Save artifacts
            val runDir = saveArtifacts(request, insertSql, rollbackSql, piiLog, tableRowCounts, appliedDefaults)

            // 12. Optionally execute against both schemas
            var executionError: String? = null
            if (request.execute) {
                try {
                    // Execute purchase schema
                    val purchaseLog = mysqlExecutor.execute(purchaseInsertSql, request.target, "purchase")
                    // Execute charge schema
                    val chargeLog = mysqlExecutor.execute(chargeInsertSql, request.target, "charge")
                    val execLogPath = runDir.resolve("execution.log")
                    Files.writeString(execLogPath, purchaseLog + "\n" + chargeLog)
                } catch (e: Exception) {
                    executionError = e.message
                    log.error("[ReplicatorService][replicate] execution failed: {}", e.message)
                }
            }

            val success = executionError == null

            // Record successful replications in the cache DB with rollback script
            if (success && request.execute) {
                cacheService.recordReplication(request.purchaseId, request.target, idOffset, rollbackSql)
            }

            // Cache the purchase snapshot so it's available instantly without another Snowflake load.
            // The Snowflake connection is already warm from the extraction above.
            if (success) {
                try {
                    cacheService.getSnapshot(request.purchaseId, forceRefresh = true)
                    log.info("[ReplicatorService][replicate] Cached snapshot for purchase {}", request.purchaseId)
                } catch (e: Exception) {
                    log.warn("[ReplicatorService][replicate] Failed to cache snapshot for purchase {}: {}",
                        request.purchaseId, e.message)
                }
            }

            return ReplicationResult(
                purchaseId = request.purchaseId,
                target = request.target,
                success = success,
                insertSql = insertSql,
                rollbackSql = rollbackSql,
                piiLog = piiLog,
                tableRowCounts = tableRowCounts,
                skippedTables = emptyList(),
                appliedDefaults = appliedDefaults,
                executed = request.execute,
                executionError = executionError,
                runDirectory = runDir.toString(),
                completedAt = Instant.now(),
            )
        } catch (e: Exception) {
            log.error("[ReplicatorService][replicate] failed: {}", e.message, e)
            return ReplicationResult(
                purchaseId = request.purchaseId,
                target = request.target,
                success = false,
                insertSql = "",
                rollbackSql = "",
                piiLog = emptyList(),
                tableRowCounts = emptyMap(),
                skippedTables = emptyList(),
                appliedDefaults = appliedDefaults,
                executed = false,
                executionError = e.message,
                runDirectory = null,
                completedAt = Instant.now(),
            )
        }
    }

    fun replicateBatch(requests: List<ReplicationRequest>): BatchReplicationResult {
        log.info("[ReplicatorService][replicateBatch] count={}", requests.size)

        val results = requests.map { replicate(it) }
        val successCount = results.count { it.success }

        return BatchReplicationResult(
            purchaseIds = requests.map { it.purchaseId },
            target = requests.firstOrNull()?.target ?: ReplicationTarget.LOCAL,
            results = results,
            successCount = successCount,
            failureCount = results.size - successCount,
            completedAt = Instant.now(),
        )
    }

    private fun applyOverrides(
        data: Map<String, List<Map<String, Any?>>>,
        request: ReplicationRequest,
        retailerId: Long,
    ): Map<String, List<Map<String, Any?>>> {
        val result = data.toMutableMap()

        // Override RETAILER_ID on customers_retailers rows (keep original CR structure,
        // just point to the local/staging retailer)
        result["customers_retailers"] = data["customers_retailers"]?.map { row ->
            row.toMutableMap().also { it["RETAILER_ID"] = retailerId }
        } ?: emptyList()

        // Anonymize EXTERNAL_ID on the purchase row
        for (table in listOf("purchases", "purchase_plan")) {
            result[table] = (result[table] ?: emptyList()).map { row ->
                val mutable = row.toMutableMap()
                if (mutable["EXTERNAL_ID"] != null) {
                    mutable["EXTERNAL_ID"] = "FAKE-RPL-${request.purchaseId}"
                }
                mutable
            }
        }

        // Null out PROCESSOR_TX_ID on payment_attempts
        result["payment_attempts"] = (result["payment_attempts"] ?: emptyList()).map { row ->
            val mutable = row.toMutableMap()
            mutable["PROCESSOR_TX_ID"] = null
            mutable
        }

        return result
    }

    /**
     * Fill in default values for known NOT NULL columns that the Snowflake extraction
     * may not include. This prevents MySQL INSERT failures on required columns.
     */
    private fun fillMissingDefaults(
        data: Map<String, List<Map<String, Any?>>>,
    ): Map<String, List<Map<String, Any?>>> {
        val result = data.toMutableMap()
        val now = "2026-01-01 00:00:00" // safe default timestamp

        // card_payment_methods: creation_time, last_update_time, dtype are NOT NULL
        result["card_payment_methods"] = (result["card_payment_methods"] ?: emptyList()).map { row ->
            val m = row.toMutableMap()
            m.putIfAbsent("CREATION_TIME", m["CREATION_TIME"] ?: now)
            m.putIfAbsent("LAST_UPDATE_TIME", m["CREATION_TIME"] ?: now)
            m.putIfAbsent("DTYPE", "CardPaymentMethod")
            m
        }

        // customers: ssn_origin, is_test, creation_time are NOT NULL
        result["customers"] = (result["customers"] ?: emptyList()).map { row ->
            val m = row.toMutableMap()
            m.putIfAbsent("SSN_ORIGIN", m["SSN_ORIGIN"] ?: 0)
            m.putIfAbsent("IS_TEST", m["IS_TEST"] ?: 1)
            m.putIfAbsent("CREATION_TIME", m["CREATION_TIME"] ?: now)
            m
        }

        // customer_details: ssn_origin NOT NULL default 0
        result["customer_details"] = (result["customer_details"] ?: emptyList()).map { row ->
            val m = row.toMutableMap()
            m.putIfAbsent("SSN_ORIGIN", m["SSN_ORIGIN"] ?: 0)
            m
        }

        return result
    }

    private fun saveArtifacts(
        request: ReplicationRequest,
        insertSql: String,
        rollbackSql: String,
        piiLog: List<com.sunbit.repair.domain.PiiRedaction>,
        tableRowCounts: Map<String, Int>,
        appliedDefaults: Map<String, Any>,
    ): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val dirName = "${request.purchaseId}_${request.target.name.lowercase()}_$timestamp"
        val runDir = Path.of("runs", dirName)
        Files.createDirectories(runDir)

        Files.writeString(runDir.resolve("inserts.sql"), insertSql)
        Files.writeString(runDir.resolve("rollback.sql"), rollbackSql)

        val runLog = mapOf(
            "purchaseId" to request.purchaseId,
            "target" to request.target.name,
            "idOffset" to (if (request.idOffset == 0L) defaultIdOffset else request.idOffset),
            "appliedDefaults" to appliedDefaults,
            "timestamp" to timestamp,
            "tableRowCounts" to tableRowCounts,
            "piiRedactionCount" to piiLog.size,
        )
        Files.writeString(runDir.resolve("run.log.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runLog))

        log.info("[ReplicatorService][saveArtifacts] saved to {}", runDir)
        return runDir
    }
}
