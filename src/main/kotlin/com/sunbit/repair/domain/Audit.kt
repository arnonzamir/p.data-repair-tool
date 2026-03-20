package com.sunbit.repair.domain

import java.time.Instant

data class AuditEntry(
    val id: String,
    val timestamp: Instant,
    val operator: String,
    val purchaseId: Long,
    val action: AuditAction,
    val input: Map<String, Any>?,
    val output: Map<String, Any>?,
    val snapshotBefore: PurchaseSnapshot?,
    val snapshotAfter: PurchaseSnapshot?,
    val durationMs: Long?,
)
