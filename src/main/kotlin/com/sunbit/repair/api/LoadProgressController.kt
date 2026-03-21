package com.sunbit.repair.api

import com.sunbit.repair.analyzer.engine.PurchaseAnalyzer
import com.sunbit.repair.domain.AuditAction
import com.sunbit.repair.audit.AuditService
import com.sunbit.repair.loader.PurchaseCacheService
import com.sunbit.repair.loader.SnowflakeLoader
import com.sunbit.repair.loader.UnifiedChargeEventBuilder
import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.Executors

/**
 * SSE endpoint that streams progress updates while loading a purchase from Snowflake.
 * Each query step emits a progress event so the frontend can show real-time status.
 */
@RestController
@RequestMapping("/api/v1/purchases")
@CrossOrigin(origins = ["*"])
class LoadProgressController(
    private val snowflakeLoader: SnowflakeLoader,
    private val cacheService: PurchaseCacheService,
    private val analyzer: PurchaseAnalyzer,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(LoadProgressController::class.java)
    private val executor = Executors.newCachedThreadPool()

    data class LoadStep(val name: String, val query: String)

    private val steps = listOf(
        LoadStep("Plan info", "Q-M1"),
        LoadStep("Payments", "Q-L1"),
        LoadStep("Charge transactions", "Q-M2a"),
        LoadStep("Money movement", "Q-M2b"),
        LoadStep("Balance check", "Q-M3"),
        LoadStep("Notifications", "Q-N1-N3"),
        LoadStep("Support tickets", "Q-N4"),
        LoadStep("Audit trail", "Q-A1"),
        LoadStep("Payment actions", "Q-L3"),
        LoadStep("Payment attempts", "Q-PA"),
        LoadStep("Charge service attempts", "Q-CSA"),
        LoadStep("Charge service statuses", "Q-CSS"),
        LoadStep("Loan transactions", "Q-LT"),
        LoadStep("Building unified charge view", "UCE"),
        LoadStep("Purchase properties", "Q-PP"),
        LoadStep("Disbursals", "Q-D1"),
        LoadStep("Offer disbursals", "Q-OD"),
        LoadStep("Cross-schema reconciliation", "Q-X1"),
        LoadStep("Analysis", "rules"),
    )

    @GetMapping("/{purchaseId}/load-stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun loadWithProgress(
        @PathVariable purchaseId: Long,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        @RequestHeader("X-Operator", defaultValue = "anonymous") operator: String,
    ): SseEmitter {
        val emitter = SseEmitter(180_000L) // 3 min timeout

        executor.submit {
            try {
                val startTime = System.currentTimeMillis()

                // Check cache first
                if (!refresh) {
                    val cached = try { cacheService.getSnapshot(purchaseId, forceRefresh = false) } catch (_: Exception) { null }
                    if (cached != null) {
                        emit(emitter, "progress", """{"step":0,"total":${steps.size},"name":"Cache hit","status":"complete","detail":"Loaded from cache"}""")
                        val analysis = analyzer.analyze(cached.snapshot)
                        val result = PurchaseAnalysisResponse(
                            snapshot = cached.snapshot,
                            analysis = analysis,
                            cachedAt = cached.cachedAt.toString(),
                            replications = cached.replications,
                        )
                        emit(emitter, "complete", com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
                            .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                            .writeValueAsString(result))
                        emitter.complete()
                        return@submit
                    }
                }

                emit(emitter, "progress", """{"step":0,"total":${steps.size},"name":"Connecting to Snowflake","status":"running","detail":"Authenticating..."}""")

                var currentStep = 0
                fun progress(name: String, status: String, detail: String = "") {
                    emit(emitter, "progress", """{"step":$currentStep,"total":${steps.size},"name":"$name","status":"$status","detail":"$detail"}""")
                }

                // Step 1: Plan info
                currentStep = 1
                progress("Plan info", "running")
                val planResult = snowflakeLoader.loadPlanInfo(purchaseId)
                progress("Plan info", "complete")

                // Step 2: Payments
                currentStep = 2
                progress("Payments", "running")
                val payments = snowflakeLoader.loadPayments(purchaseId)
                progress("Payments", "complete", "${payments.size} rows")

                // Step 3: Charge transactions
                currentStep = 3
                progress("Charge transactions", "running")
                val chargeTransactions = snowflakeLoader.loadChargeTransactions(purchaseId)
                progress("Charge transactions", "complete", "${chargeTransactions.size} rows")

                // Step 4: Money movement
                currentStep = 4
                progress("Money movement", "running")
                val moneyMovement = snowflakeLoader.loadMoneyMovement(purchaseId)
                progress("Money movement", "complete")

                // Step 5: Balance check
                currentStep = 5
                progress("Balance check", "running")
                val balanceCheck = snowflakeLoader.loadBalanceCheck(purchaseId)
                progress("Balance check", "complete")

                // Step 6: Notifications
                currentStep = 6
                progress("Notifications", "running")
                val notifications = snowflakeLoader.loadNotifications(purchaseId)
                progress("Notifications", "complete", "${notifications.sent.size} sent, ${notifications.missing.size} missing")

                // Step 7: Support tickets
                currentStep = 7
                progress("Support tickets", "running")
                val supportTickets = snowflakeLoader.loadSupportTickets(purchaseId)
                progress("Support tickets", "complete", "${supportTickets.size} tickets")

                // Step 8: Audit trail
                currentStep = 8
                progress("Audit trail", "running")
                val auditTrail = snowflakeLoader.loadAuditTrail(purchaseId)
                progress("Audit trail", "complete", "${auditTrail.size} records")

                // Step 9: Payment actions
                currentStep = 9
                progress("Payment actions", "running")
                val paymentActions = snowflakeLoader.loadPaymentActions(purchaseId)
                progress("Payment actions", "complete", "${paymentActions.size} actions")

                // Step 10: Payment attempts
                currentStep = 10
                progress("Payment attempts", "running")
                val paymentAttempts = snowflakeLoader.loadPaymentAttempts(purchaseId)
                progress("Payment attempts", "complete", "${paymentAttempts.size} attempts")

                // Step 11: Charge service attempts
                currentStep = 11
                progress("Charge service attempts", "running")
                val chargeServiceAttempts = snowflakeLoader.loadChargeServiceAttempts(purchaseId)
                progress("Charge service attempts", "complete", "${chargeServiceAttempts.size} attempts")

                // Step 12: Charge service statuses
                currentStep = 12
                progress("Charge service statuses", "running")
                val chargeServiceStatuses = if (chargeServiceAttempts.isNotEmpty()) {
                    snowflakeLoader.loadChargeServiceStatuses(chargeServiceAttempts.map { it.id })
                } else emptyList()
                progress("Charge service statuses", "complete", "${chargeServiceStatuses.size} statuses")

                // Step 13: Loan transactions
                currentStep = 13
                progress("Loan transactions", "running")
                val loanTransactions = snowflakeLoader.loadLoanTransactions(purchaseId)
                progress("Loan transactions", "complete", "${loanTransactions.size} transactions")

                // Step 14: Building unified charge view
                currentStep = 14
                progress("Building unified charge view", "running")
                val unifiedChargeEvents = UnifiedChargeEventBuilder.build(
                    purchaseId, chargeTransactions, loanTransactions, paymentAttempts,
                    chargeServiceAttempts, chargeServiceStatuses
                )
                progress("Building unified charge view", "complete", "${unifiedChargeEvents.size} events")

                // Step 15: Purchase properties
                currentStep = 15
                progress("Purchase properties", "running")
                val purchaseProperties = snowflakeLoader.loadPurchaseProperties(purchaseId)
                progress("Purchase properties", "complete", "${purchaseProperties.size} properties")

                // Step 16: Disbursals
                currentStep = 16
                progress("Disbursals", "running")
                val disbursals = snowflakeLoader.loadDisbursals(purchaseId)
                val disbursalDiffs = if (disbursals.isNotEmpty()) {
                    snowflakeLoader.loadDisbursalDiffs(purchaseId)
                } else emptyList()
                progress("Disbursals", "complete", "${disbursals.size} disbursals, ${disbursalDiffs.size} diffs")

                // Step 17: Offer disbursals
                currentStep = 17
                progress("Offer disbursals", "running")
                val offerDisbursals = snowflakeLoader.loadOfferDisbursals(purchaseId)
                val offerDisbursalMapping = if (offerDisbursals.isNotEmpty()) {
                    snowflakeLoader.loadOfferDisbursalMapping(purchaseId)
                } else emptyList()
                progress("Offer disbursals", "complete", "${offerDisbursals.size} planned, ${offerDisbursalMapping.size} mappings")

                // Step 18: Cross-schema
                currentStep = 18
                progress("Cross-schema reconciliation", "running")
                val crossSchema = snowflakeLoader.loadCrossSchemaReconciliation(purchaseId)
                progress("Cross-schema reconciliation", "complete")

                val snapshot = PurchaseSnapshot(
                    purchaseId = purchaseId,
                    loadedAt = Instant.now(),
                    snowflakeDataTimestamp = Instant.now(),
                    purchaseStatus = planResult.purchaseStatus,
                    cppStatus = planResult.cppStatus,
                    specialStatus = null,
                    plan = planResult.planInfo,
                    payments = payments,
                    chargeTransactions = chargeTransactions,
                    moneyMovement = moneyMovement,
                    balanceCheck = balanceCheck,
                    notifications = notifications,
                    supportTickets = supportTickets,
                    auditTrail = auditTrail,
                    paymentActions = paymentActions,
                    paymentAttempts = paymentAttempts,
                    crossSchemaReconciliation = crossSchema,
                    chargeServiceAttempts = chargeServiceAttempts,
                    chargeServiceStatuses = chargeServiceStatuses,
                    loanTransactions = loanTransactions,
                    unifiedChargeEvents = unifiedChargeEvents,
                    disbursals = disbursals,
                    disbursalDiffs = disbursalDiffs,
                    offerDisbursals = offerDisbursals,
                    offerDisbursalMapping = offerDisbursalMapping,
                    purchaseProperties = purchaseProperties,
                )

                // Cache the snapshot
                val cachedAt = Instant.now()
                cacheService.writeSnapshotToCache(purchaseId, snapshot, cachedAt)

                // Step 19: Analysis
                currentStep = 19
                progress("Analysis", "running", "Running ${steps.size - 1} rules")
                val analysis = analyzer.analyze(snapshot)
                progress("Analysis", "complete", "${analysis.findings.size} findings")

                val elapsed = System.currentTimeMillis() - startTime

                auditService.record(
                    operator = operator,
                    purchaseId = purchaseId,
                    action = AuditAction.ANALYZE,
                    output = mapOf(
                        "findingCount" to analysis.findings.size,
                        "overallSeverity" to (analysis.overallSeverity?.name ?: "NONE"),
                        "loadTimeMs" to elapsed,
                    ),
                    durationMs = elapsed,
                )

                val replications = cacheService.getReplications(purchaseId)
                val result = PurchaseAnalysisResponse(
                    snapshot = snapshot,
                    analysis = analysis,
                    cachedAt = cachedAt.toString(),
                    replications = replications,
                )

                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
                    .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

                emit(emitter, "complete", mapper.writeValueAsString(result))
                emitter.complete()

            } catch (e: Exception) {
                log.error("[LoadProgressController][loadWithProgress] Failed for purchase {}: {}", purchaseId, e.message, e)
                emit(emitter, "load-error", """{"message":"${e.message?.replace("\"", "'")}"}""")
                emitter.complete()
            }
        }

        return emitter
    }

    private fun emit(emitter: SseEmitter, event: String, data: String) {
        try {
            emitter.send(SseEmitter.event()
                .name(event)
                .data(data, org.springframework.http.MediaType.TEXT_PLAIN))
        } catch (_: Exception) {
            // Client disconnected
        }
    }
}
