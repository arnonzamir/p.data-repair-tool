package com.sunbit.repair.domain

import java.time.Instant

// ---------------------------------------------------------------------------
// Replication -- extract from Snowflake, anonymize, insert to local/staging
// ---------------------------------------------------------------------------

enum class ReplicationTarget {
    LOCAL,
    STAGING
}

data class ReplicationRequest(
    val purchaseId: Long,
    val target: ReplicationTarget,
    val execute: Boolean = false,
    val idOffset: Long = 100_000_000L,
    val customerRetailerId: Long? = null,
    val paymentProfileId: Long? = null,
    val namePrefix: String? = null,
)

data class ReplicationResult(
    val purchaseId: Long,
    val target: ReplicationTarget,
    val success: Boolean,
    val insertSql: String,
    val rollbackSql: String,
    val piiLog: List<PiiRedaction>,
    val tableRowCounts: Map<String, Int>,
    val skippedTables: List<String>,
    val appliedDefaults: Map<String, Any>,
    val executed: Boolean,
    val executionError: String? = null,
    val runDirectory: String? = null,
    val completedAt: Instant,
)

data class PiiRedaction(
    val table: String,
    val field: String,
    val originalHash: String,
    val replacement: String,
)

data class BatchReplicationResult(
    val purchaseIds: List<Long>,
    val target: ReplicationTarget,
    val results: List<ReplicationResult>,
    val successCount: Int,
    val failureCount: Int,
    val completedAt: Instant,
)

// ---------------------------------------------------------------------------
// Anonymized identity -- deterministic fake identity per purchase
// ---------------------------------------------------------------------------

data class AnonymizedIdentity(
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val ssn: String,
    val dateOfBirth: String?,
    val drivingLicense: String,
    val address: String,
    val city: String,
    val state: String?,
    val zipcode: String,
    val cardLast4: String,
    val cardNumberHash: String,
)
