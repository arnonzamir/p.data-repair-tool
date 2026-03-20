package com.sunbit.repair.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// ---------------------------------------------------------------------------
// Core purchase snapshot -- assembled by the Loader from Snowflake + Admin API
// ---------------------------------------------------------------------------

data class PurchaseSnapshot(
    val purchaseId: Long,
    val loadedAt: Instant,
    val snowflakeDataTimestamp: Instant? = null,
    val purchaseStatus: Int,
    val cppStatus: CppStatus,
    val specialStatus: String? = null,
    val plan: PlanInfo,
    val payments: List<Payment>,
    val chargeTransactions: List<ChargeTransaction>,
    val moneyMovement: MoneyMovement,
    val balanceCheck: BalanceCheck,
    val notifications: NotificationSummary,
    val supportTickets: List<SupportTicket>,
    val auditTrail: List<PaymentAuditRecord>,
    val paymentActions: List<PaymentAction>,
    val paymentAttempts: List<PaymentAttempt>,
    val crossSchemaReconciliation: CrossSchemaReconciliation,
    val chargeServiceAttempts: List<ChargeServiceAttempt> = emptyList(),
    val chargeServiceStatuses: List<ChargeServiceAttemptStatus> = emptyList(),
    val loanTransactions: List<LoanTransaction> = emptyList(),
    val unifiedChargeEvents: List<UnifiedChargeEvent> = emptyList(),
)

// ---------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------

data class PlanInfo(
    val planId: Long,
    val totalAmount: BigDecimal,
    val amountFinanced: BigDecimal,
    val totalOfPayments: BigDecimal,
    val financialCharge: BigDecimal,
    val nominalApr: BigDecimal,
    val effectiveApr: BigDecimal?,
    val dailyApr: BigDecimal?,
    val monthlyApr: BigDecimal?,
    val numInstallments: Int,
    val paymentProfileId: Long?,
    val paymentsInterval: Int? = null,
)

// ---------------------------------------------------------------------------
// Payments
// ---------------------------------------------------------------------------

data class Payment(
    val id: Long,
    val paymentPlanId: Long,
    val amount: BigDecimal,
    val interestAmount: BigDecimal?,
    val interestCharge: BigDecimal?,
    val principalBalance: BigDecimal?,
    val dueDate: LocalDate,
    val effectiveDate: LocalDate?,
    val paidOffDate: LocalDateTime?,
    val refundDate: LocalDateTime?,
    val type: Int,
    val typeName: PaymentType?,
    val changeIndicator: Int,
    val changeIndicatorName: ChangeIndicator?,
    val isActive: Boolean,
    val paymentActionId: Long?,
    val chargeBack: String?,
    val chargeBackEnhancement: String?,
    val dispute: String?,
    val splitFrom: Long?,
    val originalPaymentId: Long?,
    val directParentId: Long?,
    val paymentProfileId: Long?,
    val manualUntil: LocalDateTime?,
    val creationDate: LocalDateTime?,
    val amountPaid: BigDecimal?,
    val initialAmount: BigDecimal?,
) {
    /** Runtime-derived status, matching the LMS PaymentStatus computation. */
    val computedStatus: PaymentStatus
        get() = when {
            !isActive -> PaymentStatus.INACTIVE
            dispute != null -> PaymentStatus.DISPUTED
            chargeBack != null || chargeBackEnhancement != null -> PaymentStatus.CHARGEBACK
            paidOffDate != null -> PaymentStatus.PAID
            else -> PaymentStatus.UNPAID
        }
}

enum class PaymentStatus {
    PAID,
    UNPAID,
    INACTIVE,
    CHARGEBACK,
    DISPUTED
}

// ---------------------------------------------------------------------------
// Charge transactions
// ---------------------------------------------------------------------------

data class ChargeTransaction(
    val id: Long,
    val purchaseId: Long,
    val type: Int,
    val typeName: ChargeTransactionType?,
    val amount: BigDecimal,
    val chargeTime: LocalDateTime?,
    val chargeback: Boolean?,
    val chargebackEnhancement: Boolean?,
    val parentId: Long?,
    val paymentProfileId: Long?,
    val manualAdjustment: Boolean?,
)

// ---------------------------------------------------------------------------
// Money movement (aggregated from charge_transactions)
// ---------------------------------------------------------------------------

data class MoneyMovement(
    val byType: List<MoneyMovementEntry>,
) {
    val totalCollected: BigDecimal
        get() = byType
            .filter { it.type in listOf(0, 1, 2) }
            .fold(BigDecimal.ZERO) { acc, e -> acc + e.totalAmount }

    val totalReturned: BigDecimal
        get() = byType
            .filter { it.type in listOf(5, 6, 7, 8, 13) }
            .fold(BigDecimal.ZERO) { acc, e -> acc + e.totalAmount }

    val net: BigDecimal
        get() = byType.fold(BigDecimal.ZERO) { acc, e -> acc + e.totalAmount }
}

data class MoneyMovementEntry(
    val type: Int,
    val typeName: String,
    val count: Int,
    val totalAmount: BigDecimal,
    val earliest: LocalDateTime?,
    val latest: LocalDateTime?,
)

// ---------------------------------------------------------------------------
// Balance check
// ---------------------------------------------------------------------------

data class BalanceCheck(
    val planTotal: BigDecimal,
    val moneyCollected: BigDecimal,
    val moneyReturned: BigDecimal,
    val netCollected: BigDecimal,
    val downPayment: BigDecimal,
    val paidInstallments: BigDecimal,
    val unpaidInstallments: BigDecimal,
    val scheduleTotal: BigDecimal,
    val scheduleGap: BigDecimal,
    val moneyGap: BigDecimal,
    val checkAVerdict: String,
    val checkBVerdict: String,
)

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

data class NotificationSummary(
    val sent: List<NotificationRecord>,
    val missing: List<MissingNotification>,
    val erroneous: List<ErroneousNotification>,
)

data class NotificationRecord(
    val id: Long,
    val purchaseId: Long,
    val type: Int,
    val typeName: EmailType?,
    val status: Int?,
    val changeStatusTime: LocalDateTime?,
    val purchaseStatusOnSend: Int?,
    val notificationId: String? = null,
    val recipientAddress: String? = null,
    val templateName: String? = null,
    val subject: String? = null,
)

data class MissingNotification(
    val paymentId: Long,
    val paidOffDate: LocalDateTime,
    val expectedEmailType: String,
    val description: String,
)

data class ErroneousNotification(
    val notificationId: Long,
    val type: Int,
    val typeName: String,
    val reason: String,
)

// ---------------------------------------------------------------------------
// Support tickets (call-center)
// ---------------------------------------------------------------------------

data class SupportTicket(
    val id: String,
    val purchaseId: Long,
    val subject: String?,
    val status: String?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val assignee: String?,
    val category: String?,
    val priority: String?,
    val description: String?,
    val channel: String?,
)

// ---------------------------------------------------------------------------
// Audit trail
// ---------------------------------------------------------------------------

data class PaymentAuditRecord(
    val paymentId: Long,
    val rev: Long,
    val amount: BigDecimal?,
    val isActive: Boolean?,
    val paidOffDate: LocalDateTime?,
    val changeIndicator: Int?,
    val rowTime: LocalDateTime?,
)

// ---------------------------------------------------------------------------
// Payment actions (flow timeline)
// ---------------------------------------------------------------------------

data class PaymentAction(
    val id: Long,
    val type: Int,
    val typeName: PaymentActionType?,
    val purchaseId: Long,
    val timeOfAction: LocalDateTime?,
)

// ---------------------------------------------------------------------------
// Payment attempts
// ---------------------------------------------------------------------------

data class PaymentAttempt(
    val id: String,
    val paymentId: Long,
    val triggeringPaymentId: Long?,
    val source: Int?,
    val sourceName: PaymentAttemptSource?,
    val dateTime: LocalDateTime?,
    val type: Int?,
    val status: Int?,
    val failMessage: String?,
    val processorTxId: String?,
    val holdTransactionId: String?,
    val chargeTransactionId: String?,
)

// ---------------------------------------------------------------------------
// Cross-schema reconciliation
// ---------------------------------------------------------------------------

data class CrossSchemaReconciliation(
    val processorSuccessCount: Int,
    val processorSuccessAmount: BigDecimal,
    val purchaseChargeCount: Int,
    val purchaseChargeAmount: BigDecimal,
    val processorRefundCount: Int,
    val processorRefundAmount: BigDecimal,
    val purchaseRefundCount: Int,
    val purchaseRefundAmount: BigDecimal,
    val processorFailCount: Int,
    val verdict: String,
)

// ---------------------------------------------------------------------------
// Charge-service payment attempts (from BRONZE.CHARGE.PAYMENT_ATTEMPTS)
// ---------------------------------------------------------------------------

data class ChargeServiceAttempt(
    val id: Long,
    val dateTime: LocalDateTime?,
    val lastUpdateTime: LocalDateTime?,
    val paymentProfileId: Long?,
    val paymentProcessor: String?,
    val processorTxId: String?,
    val failMessage: String?,
    val externalId: String,
    val amount: BigDecimal,
    val status: Int,
    val type: Int,
    val origin: String?,
)

// ---------------------------------------------------------------------------
// Enriched status from Checkout.com webhooks (from BRONZE.CHARGE.PAYMENT_ATTEMPT_STATUSES)
// ---------------------------------------------------------------------------

data class ChargeServiceAttemptStatus(
    val id: Long,
    val paymentAttemptId: Long,
    val creationTime: LocalDateTime?,
    val lastUpdateTime: LocalDateTime?,
    val chargeStatus: String?,
    val chargeStatusReason: String?,
    val summary: String?,
)

// ---------------------------------------------------------------------------
// Financial breakdown per charge (from BRONZE.PURCHASE.LOAN_TRANSACTIONS)
// ---------------------------------------------------------------------------

data class LoanTransaction(
    val id: Long,
    val paymentId: Long,
    val chargeTransactionId: Long,
    val amount: BigDecimal,
    val effectiveDate: LocalDate?,
    val interestAmount: BigDecimal?,
    val interestCharge: BigDecimal?,
    val principalBalance: BigDecimal?,
)

// ---------------------------------------------------------------------------
// Charge event matching quality
// ---------------------------------------------------------------------------

enum class MatchQuality {
    EXACT,         // processorTxId match
    AMOUNT_MATCH,  // amount + externalId match
    UNMATCHED,     // no match found
}

// ---------------------------------------------------------------------------
// Unified view merging all 5 source tables for one money movement event
// ---------------------------------------------------------------------------

data class UnifiedChargeEvent(
    val chargeTransaction: ChargeTransaction?,
    val loanTransaction: LoanTransaction?,
    val purchaseAttempt: PaymentAttempt?,
    val chargeServiceAttempt: ChargeServiceAttempt?,
    val chargeServiceStatuses: List<ChargeServiceAttemptStatus>,
    val paymentId: Long?,
    val amount: BigDecimal,
    val timestamp: LocalDateTime?,
    val matchQuality: MatchQuality,
)
