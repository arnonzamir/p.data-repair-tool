package com.sunbit.repair.domain

/**
 * PAYMENTS.CHANGE_INDICATOR -- identifies which flow created a payment.
 */
enum class ChangeIndicator(val code: Int) {
    NONE(0),
    ALL_PAYMENTS_DATE_CHANGE(4),
    PAY_NOW(8),
    MARKED_AS_UNPAID(32),
    CHANGE_AMOUNT(512),
    CANCEL(514),
    DELAYED_CHARGE_DATE(515),
    WORKOUT_PAYMENT_PLAN(517),
    APR_CHANGE(518);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ChangeIndicator = byCode[code]
            ?: throw IllegalArgumentException("Unknown ChangeIndicator code: $code")

        fun fromCodeOrNull(code: Int): ChangeIndicator? = byCode[code]
    }
}

/**
 * CHOSEN_PAYMENTPLANS.STATUS
 */
enum class CppStatus(val code: Int) {
    PENDING_PAYMENTS(0),
    ON_SCHEDULE(1),
    LATE(2),
    DEFAULT(3),
    PAID_OFF(4),
    CHARGE_FAILED(5);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): CppStatus = byCode[code]
            ?: throw IllegalArgumentException("Unknown CppStatus code: $code")
    }
}

/**
 * PAYMENT_ACTIONS.TYPE
 */
enum class PaymentActionType(val code: Int) {
    PAY_NOW(0),
    MOVE_PAYMENTS(1),
    CHANGE_AMOUNT(2),
    CANCEL_PURCHASE(3),
    UNPAID(4),
    SPLIT_PAYMENT(6),
    DELAYED_CHARGE_DATE(7),
    RESTORE_CANCELLATION(8),
    WORKOUT(9),
    APR_CHANGE(10),
    REVERSAL_OF_ADJUSTMENTS(11),
    SETTLEMENT(12),
    UNPAID_WITH_REFUND(13);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PaymentActionType = byCode[code]
            ?: throw IllegalArgumentException("Unknown PaymentActionType code: $code")

        fun fromCodeOrNull(code: Int): PaymentActionType? = byCode[code]
    }
}

/**
 * CHARGE_TRANSACTIONS.TYPE
 */
enum class ChargeTransactionType(val code: Int) {
    SCHEDULED_PAYMENT(0),
    UNSCHEDULED_PAYMENT(1),
    DOWN_PAYMENT(2),
    CHARGEBACK(3),
    REVERSE_CHARGEBACK(4),
    ADJUSTMENT_REFUND(5),
    CANCEL_REFUND(6),
    DP_ADJUSTMENT_REFUND(7),
    UNPAID(8),
    RETAILER_ADJUSTMENT(9),
    INTEREST_ELIMINATION(12),
    PAYMENT_REFUND(13);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ChargeTransactionType = byCode[code]
            ?: throw IllegalArgumentException("Unknown ChargeTransactionType code: $code")

        fun fromCodeOrNull(code: Int): ChargeTransactionType? = byCode[code]
    }
}

/**
 * PAYMENTS.TYPE
 */
enum class PaymentType(val code: Int) {
    SCHEDULED(0),
    UNSCHEDULED_PARTIAL(10),
    UNSCHEDULED_PAYOFF(20),
    DOWN_PAYMENT(30);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PaymentType = byCode[code]
            ?: throw IllegalArgumentException("Unknown PaymentType code: $code")

        fun fromCodeOrNull(code: Int): PaymentType? = byCode[code]
    }
}

/**
 * PAYMENT_ATTEMPTS.SOURCE
 */
enum class PaymentAttemptSource(val code: Int) {
    JOB(0),
    ADMIN(1),
    SELF_SERVICE(2),
    IVR(5),
    MY_SUNBIT_APP(6),
    PAYMENT_LINK(8),
    CHAT(9);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PaymentAttemptSource = byCode[code]
            ?: throw IllegalArgumentException("Unknown PaymentAttemptSource code: $code")

        fun fromCodeOrNull(code: Int): PaymentAttemptSource? = byCode[code]
    }
}

/**
 * PURCHASES_EMAILS.TYPE
 */
enum class EmailType(val code: Int) {
    AGREEMENT(1),
    AGREEMENT_DRAFT(3),
    RETAILER_CHARGE_NOTIFICATION(4),
    ON_SCHEDULE(5),
    PAID_OFF(7),
    PAID_PAYMENT_LATE_PURCHASE(9),
    PAY_NOW_PAYMENT(10),
    CHARGE_FAILED(12);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): EmailType = byCode[code]
            ?: throw IllegalArgumentException("Unknown EmailType code: $code")

        fun fromCodeOrNull(code: Int): EmailType? = byCode[code]
    }
}

/**
 * Severity levels for analysis findings.
 */
enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Audit action types.
 */
enum class AuditAction {
    LOAD,
    ANALYZE,
    REPAIR,
    REPLICATE,
    VERIFY
}
