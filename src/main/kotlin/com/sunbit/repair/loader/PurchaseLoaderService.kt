package com.sunbit.repair.loader

import com.sunbit.repair.domain.PurchaseSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PurchaseLoaderService(
    private val snowflakeLoader: SnowflakeLoader,
) {

    private val log = LoggerFactory.getLogger(PurchaseLoaderService::class.java)

    fun loadPurchase(purchaseId: Long): PurchaseSnapshot {
        log.info("[PurchaseLoaderService][loadPurchase] Starting load for purchase {}", purchaseId)
        val startTime = System.currentTimeMillis()

        val planResult = snowflakeLoader.loadPlanInfo(purchaseId)
        val payments = snowflakeLoader.loadPayments(purchaseId)
        val chargeTransactions = snowflakeLoader.loadChargeTransactions(purchaseId)
        val moneyMovement = snowflakeLoader.loadMoneyMovement(purchaseId)
        val balanceCheck = snowflakeLoader.loadBalanceCheck(purchaseId)
        val notifications = snowflakeLoader.loadNotifications(purchaseId)
        val supportTickets = snowflakeLoader.loadSupportTickets(purchaseId)
        val auditTrail = snowflakeLoader.loadAuditTrail(purchaseId)
        val paymentActions = snowflakeLoader.loadPaymentActions(purchaseId)
        val paymentAttempts = snowflakeLoader.loadPaymentAttempts(purchaseId)
        val chargeServiceAttempts = snowflakeLoader.loadChargeServiceAttempts(purchaseId)
        val chargeServiceStatuses = if (chargeServiceAttempts.isNotEmpty()) {
            snowflakeLoader.loadChargeServiceStatuses(chargeServiceAttempts.map { it.id })
        } else emptyList()
        val loanTransactions = snowflakeLoader.loadLoanTransactions(purchaseId)
        val purchaseProperties = snowflakeLoader.loadPurchaseProperties(purchaseId)
        val disbursals = snowflakeLoader.loadDisbursals(purchaseId)
        val disbursalDiffs = if (disbursals.isNotEmpty()) {
            snowflakeLoader.loadDisbursalDiffs(purchaseId)
        } else emptyList()
        val offerDisbursals = snowflakeLoader.loadOfferDisbursals(purchaseId)
        val offerDisbursalMapping = if (offerDisbursals.isNotEmpty()) {
            snowflakeLoader.loadOfferDisbursalMapping(purchaseId)
        } else emptyList()
        val crossSchema = snowflakeLoader.loadCrossSchemaReconciliation(purchaseId)

        val unifiedChargeEvents = UnifiedChargeEventBuilder.build(
            purchaseId, chargeTransactions, loanTransactions, paymentAttempts,
            chargeServiceAttempts, chargeServiceStatuses
        )

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

        val elapsed = System.currentTimeMillis() - startTime
        log.info(
            "[PurchaseLoaderService][loadPurchase] Completed load for purchase {} in {}ms " +
                "(payments={}, chargeTransactions={}, actions={}, attempts={}, " +
                "chargeServiceAttempts={}, loanTransactions={}, unifiedEvents={})",
            purchaseId, elapsed,
            payments.size, chargeTransactions.size, paymentActions.size, paymentAttempts.size,
            chargeServiceAttempts.size, loanTransactions.size, unifiedChargeEvents.size
        )
        return snapshot
    }

    fun loadPurchaseBatch(purchaseIds: List<Long>): List<PurchaseSnapshot> {
        log.info("[PurchaseLoaderService][loadPurchaseBatch] Starting batch load for {} purchases", purchaseIds.size)
        val startTime = System.currentTimeMillis()

        val snapshots = runBlocking {
            coroutineScope {
                purchaseIds.map { purchaseId ->
                    async(Dispatchers.IO) {
                        try {
                            loadPurchase(purchaseId)
                        } catch (e: Exception) {
                            log.error(
                                "[PurchaseLoaderService][loadPurchaseBatch] Failed to load purchase {}: {}",
                                purchaseId, e.message, e
                            )
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        log.info(
            "[PurchaseLoaderService][loadPurchaseBatch] Completed batch load: {}/{} succeeded in {}ms",
            snapshots.size, purchaseIds.size, elapsed
        )
        return snapshots
    }
}
