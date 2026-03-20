package com.sunbit.repair.loader

import com.sunbit.repair.domain.*
import org.slf4j.LoggerFactory

object UnifiedChargeEventBuilder {
    private val log = LoggerFactory.getLogger(UnifiedChargeEventBuilder::class.java)

    fun build(
        purchaseId: Long,
        chargeTransactions: List<ChargeTransaction>,
        loanTransactions: List<LoanTransaction>,
        purchaseAttempts: List<PaymentAttempt>,
        chargeServiceAttempts: List<ChargeServiceAttempt>,
        chargeServiceStatuses: List<ChargeServiceAttemptStatus>,
    ): List<UnifiedChargeEvent> {
        // Index data
        val loanTxByCTId = loanTransactions.associateBy { it.chargeTransactionId }

        // purchase.payment_attempts.chargeTransactionId is a String (was changed from Long)
        // Group by chargeTransactionId -- multiple purchase attempts can share the same CT
        val purchaseAttemptByCTId = mutableMapOf<Long, PaymentAttempt>()
        for (pa in purchaseAttempts) {
            val ctId = pa.chargeTransactionId?.toLongOrNull()
            if (ctId != null) {
                purchaseAttemptByCTId[ctId] = pa
            }
        }

        val statusesByAttemptId = chargeServiceStatuses.groupBy { it.paymentAttemptId }

        // Track which charge-service attempts are consumed
        val consumedCSAttemptIds = mutableSetOf<Long>()
        val events = mutableListOf<UnifiedChargeEvent>()

        // Phase 1: Start from charge_transactions
        for (ct in chargeTransactions) {
            val loanTx = loanTxByCTId[ct.id]
            val purchaseAttempt = purchaseAttemptByCTId[ct.id]
            val paymentId = purchaseAttempt?.triggeringPaymentId ?: loanTx?.paymentId

            // Try to match a charge-service attempt
            var matchedCSA: ChargeServiceAttempt? = null
            var matchQuality = MatchQuality.UNMATCHED

            // Match by processorTxId (EXACT)
            if (purchaseAttempt?.processorTxId != null) {
                matchedCSA = chargeServiceAttempts.find {
                    it.id !in consumedCSAttemptIds && it.processorTxId == purchaseAttempt.processorTxId
                }
                if (matchedCSA != null) matchQuality = MatchQuality.EXACT
            }

            // Fallback: match by externalId containing the purchaseId and amount
            if (matchedCSA == null && paymentId != null) {
                val expectedExternalId = "$purchaseId-$paymentId"
                matchedCSA = chargeServiceAttempts.find {
                    it.id !in consumedCSAttemptIds
                        && it.externalId == expectedExternalId
                        && it.status == 0  // SUCCESS
                        && (it.amount - ct.amount).abs() < java.math.BigDecimal("0.01")
                }
                if (matchedCSA != null) matchQuality = MatchQuality.AMOUNT_MATCH
            }

            // Fallback: match by purchaseId-only externalId (down payments, early format)
            if (matchedCSA == null) {
                val expectedExternalId = "$purchaseId"
                matchedCSA = chargeServiceAttempts.find {
                    it.id !in consumedCSAttemptIds
                        && it.externalId == expectedExternalId
                        && it.status == 0
                        && (it.amount - ct.amount).abs() < java.math.BigDecimal("0.01")
                }
                if (matchedCSA != null) matchQuality = MatchQuality.AMOUNT_MATCH
            }

            if (matchedCSA != null) {
                consumedCSAttemptIds.add(matchedCSA.id)
            }

            events.add(UnifiedChargeEvent(
                chargeTransaction = ct,
                loanTransaction = loanTx,
                purchaseAttempt = purchaseAttempt,
                chargeServiceAttempt = matchedCSA,
                chargeServiceStatuses = matchedCSA?.let { statusesByAttemptId[it.id] ?: emptyList() } ?: emptyList(),
                paymentId = paymentId,
                amount = ct.amount,
                timestamp = ct.chargeTime,
                matchQuality = matchQuality,
            ))
        }

        // Phase 2: Orphan charge-service attempts (not matched to any charge_transaction)
        for (csa in chargeServiceAttempts) {
            if (csa.id in consumedCSAttemptIds) continue
            // Include all -- failed attempts with no purchase record are also interesting

            // Parse paymentId from externalId
            val parts = csa.externalId.split("-", limit = 2)
            val parsedPaymentId = if (parts.size > 1) parts[1].toLongOrNull() else null

            events.add(UnifiedChargeEvent(
                chargeTransaction = null,
                loanTransaction = null,
                purchaseAttempt = null,
                chargeServiceAttempt = csa,
                chargeServiceStatuses = statusesByAttemptId[csa.id] ?: emptyList(),
                paymentId = parsedPaymentId,
                amount = csa.amount,
                timestamp = csa.dateTime,
                matchQuality = MatchQuality.UNMATCHED,
            ))
        }

        // Sort by timestamp
        events.sortBy { it.timestamp }

        log.info("[UnifiedChargeEventBuilder][build] purchaseId={} events={} (matched={}, unmatched={})",
            purchaseId, events.size,
            events.count { it.matchQuality != MatchQuality.UNMATCHED },
            events.count { it.matchQuality == MatchQuality.UNMATCHED })

        return events
    }
}
