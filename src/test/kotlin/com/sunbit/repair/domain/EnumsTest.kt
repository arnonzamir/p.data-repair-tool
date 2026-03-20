package com.sunbit.repair.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnumsTest {

    @Test
    fun `ChangeIndicator fromCode maps known codes`() {
        assertEquals(ChangeIndicator.NONE, ChangeIndicator.fromCode(0))
        assertEquals(ChangeIndicator.PAY_NOW, ChangeIndicator.fromCode(8))
        assertEquals(ChangeIndicator.CANCEL, ChangeIndicator.fromCode(514))
        assertEquals(ChangeIndicator.WORKOUT_PAYMENT_PLAN, ChangeIndicator.fromCode(517))
    }

    @Test
    fun `ChangeIndicator fromCode throws on unknown code`() {
        assertThrows<IllegalArgumentException> { ChangeIndicator.fromCode(999) }
    }

    @Test
    fun `ChangeIndicator fromCodeOrNull returns null on unknown code`() {
        assertNull(ChangeIndicator.fromCodeOrNull(999))
    }

    @Test
    fun `CppStatus fromCode maps all statuses`() {
        assertEquals(CppStatus.PENDING_PAYMENTS, CppStatus.fromCode(0))
        assertEquals(CppStatus.ON_SCHEDULE, CppStatus.fromCode(1))
        assertEquals(CppStatus.LATE, CppStatus.fromCode(2))
        assertEquals(CppStatus.DEFAULT, CppStatus.fromCode(3))
        assertEquals(CppStatus.PAID_OFF, CppStatus.fromCode(4))
        assertEquals(CppStatus.CHARGE_FAILED, CppStatus.fromCode(5))
    }

    @Test
    fun `PaymentActionType fromCode maps all types`() {
        assertEquals(PaymentActionType.PAY_NOW, PaymentActionType.fromCode(0))
        assertEquals(PaymentActionType.CHANGE_AMOUNT, PaymentActionType.fromCode(2))
        assertEquals(PaymentActionType.CANCEL_PURCHASE, PaymentActionType.fromCode(3))
        assertEquals(PaymentActionType.WORKOUT, PaymentActionType.fromCode(9))
        assertEquals(PaymentActionType.SETTLEMENT, PaymentActionType.fromCode(12))
    }

    @Test
    fun `ChargeTransactionType fromCode maps all types`() {
        assertEquals(ChargeTransactionType.SCHEDULED_PAYMENT, ChargeTransactionType.fromCode(0))
        assertEquals(ChargeTransactionType.CHARGEBACK, ChargeTransactionType.fromCode(3))
        assertEquals(ChargeTransactionType.ADJUSTMENT_REFUND, ChargeTransactionType.fromCode(5))
        assertEquals(ChargeTransactionType.PAYMENT_REFUND, ChargeTransactionType.fromCode(13))
    }

    @Test
    fun `PaymentType fromCode maps all types`() {
        assertEquals(PaymentType.SCHEDULED, PaymentType.fromCode(0))
        assertEquals(PaymentType.UNSCHEDULED_PARTIAL, PaymentType.fromCode(10))
        assertEquals(PaymentType.UNSCHEDULED_PAYOFF, PaymentType.fromCode(20))
        assertEquals(PaymentType.DOWN_PAYMENT, PaymentType.fromCode(30))
    }

    @Test
    fun `PaymentAttemptSource fromCodeOrNull returns null for unknown`() {
        assertNull(PaymentAttemptSource.fromCodeOrNull(99))
        assertEquals(PaymentAttemptSource.JOB, PaymentAttemptSource.fromCodeOrNull(0))
    }

    @Test
    fun `EmailType fromCode maps all types`() {
        assertEquals(EmailType.AGREEMENT, EmailType.fromCode(1))
        assertEquals(EmailType.ON_SCHEDULE, EmailType.fromCode(5))
        assertEquals(EmailType.PAID_OFF, EmailType.fromCode(7))
        assertEquals(EmailType.CHARGE_FAILED, EmailType.fromCode(12))
    }
}
