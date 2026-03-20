package com.sunbit.repair.analyzer.rules

import com.sunbit.repair.TestFixtures.balanceCheck
import com.sunbit.repair.TestFixtures.crossSchema
import com.sunbit.repair.TestFixtures.payment
import com.sunbit.repair.TestFixtures.plan
import com.sunbit.repair.TestFixtures.snapshot
import com.sunbit.repair.domain.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhostPaymentRuleTest {

    private val rule = GhostPaymentRule()

    @Test
    fun `no findings when no ghost payments exist`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, isActive = true, directParentId = null),
            payment(id = 2, isActive = true, directParentId = null),
        ))
        val findings = rule.analyze(snap)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `detects ghost when parent and child are both active`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, isActive = true),
            payment(id = 2, isActive = true, directParentId = 1, changeIndicator = 8),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertTrue(findings[0].affectedPaymentIds.containsAll(listOf(1L, 2L)))
    }

    @Test
    fun `no ghost when parent is inactive`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, isActive = false),
            payment(id = 2, isActive = true, directParentId = 1, changeIndicator = 8),
        ))
        val findings = rule.analyze(snap)
        assertTrue(findings.isEmpty())
    }
}

class MoneyGapRuleTest {

    private val rule = MoneyGapRule()

    @Test
    fun `no findings when gaps are zero`() {
        val snap = snapshot(balanceCheck = balanceCheck(
            scheduleGap = BigDecimal.ZERO,
            moneyGap = BigDecimal.ZERO,
        ))
        val findings = rule.analyze(snap)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `detects schedule gap`() {
        val snap = snapshot(balanceCheck = balanceCheck(
            scheduleGap = BigDecimal("65.76"),
            moneyGap = BigDecimal.ZERO,
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.HIGH, findings[0].severity)
        assertTrue(findings[0].description.contains("Schedule", ignoreCase = true))
    }

    @Test
    fun `detects money gap`() {
        val snap = snapshot(balanceCheck = balanceCheck(
            scheduleGap = BigDecimal.ZERO,
            moneyGap = BigDecimal("-376.01"),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.HIGH, findings[0].severity)
    }

    @Test
    fun `tolerates gaps under 0_05`() {
        val snap = snapshot(balanceCheck = balanceCheck(
            scheduleGap = BigDecimal("0.03"),
            moneyGap = BigDecimal("-0.02"),
        ))
        val findings = rule.analyze(snap)
        assertTrue(findings.isEmpty())
    }
}

class CrossSchemaDesyncRuleTest {

    private val rule = CrossSchemaDesyncRule()

    @Test
    fun `no findings when all events are matched`() {
        val snap = snapshot(crossSchema = crossSchema("PASS: charge counts and amounts match"))
        // No unified events = no findings
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects processor-only charge as CRITICAL`() {
        val snap = snapshot(
            crossSchema = crossSchema("FAIL"),
        ).copy(
            unifiedChargeEvents = listOf(
                com.sunbit.repair.domain.UnifiedChargeEvent(
                    chargeTransaction = null,
                    loanTransaction = null,
                    purchaseAttempt = null,
                    chargeServiceAttempt = com.sunbit.repair.domain.ChargeServiceAttempt(
                        id = 1L, dateTime = null, lastUpdateTime = null,
                        paymentProfileId = null, paymentProcessor = "CHECKOUT",
                        processorTxId = "tx1", failMessage = null,
                        externalId = "12345-1", amount = java.math.BigDecimal("100.00"),
                        status = 0, type = 4, origin = "LOAN",
                    ),
                    chargeServiceStatuses = emptyList(),
                    paymentId = 1L,
                    amount = java.math.BigDecimal("100.00"),
                    timestamp = null,
                    matchQuality = com.sunbit.repair.domain.MatchQuality.UNMATCHED,
                ),
            ),
        )
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.CRITICAL, findings[0].severity)
    }
}

class DuplicateChargeRuleTest {

    private val rule = DuplicateChargeRule()

    @Test
    fun `no findings when no duplicates`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, dueDate = LocalDate.of(2026, 3, 15),
                paidOffDate = LocalDateTime.of(2026, 3, 15, 10, 0)),
            payment(id = 2, dueDate = LocalDate.of(2026, 4, 15),
                paidOffDate = LocalDateTime.of(2026, 4, 15, 10, 0)),
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects duplicate paid payments on same due date`() {
        val dueDate = LocalDate.of(2026, 3, 15)
        val paidDate = LocalDateTime.of(2026, 3, 15, 10, 0)
        val snap = snapshot(payments = listOf(
            payment(id = 1, dueDate = dueDate, paidOffDate = paidDate, type = 0),
            payment(id = 2, dueDate = dueDate, paidOffDate = paidDate, type = 0),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.HIGH, findings[0].severity)
    }
}

class OrphanedPaymentRuleTest {

    private val rule = OrphanedPaymentRule()

    @Test
    fun `no findings for original payments`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, changeIndicator = 0, directParentId = null),
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects mutation payment with null parent`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, changeIndicator = 8, directParentId = null, isActive = true),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.MEDIUM, findings[0].severity)
    }

    @Test
    fun `detects mutation payment with nonexistent parent`() {
        val snap = snapshot(payments = listOf(
            payment(id = 2, changeIndicator = 8, directParentId = 999, isActive = true),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
    }
}

class StaleManualUntilRuleTest {

    private val rule = StaleManualUntilRule()

    @Test
    fun `no findings when manualUntil is null`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, manualUntil = null),
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects stale manualUntil in the past`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, manualUntil = LocalDateTime.of(2025, 1, 1, 0, 0)),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.MEDIUM, findings[0].severity)
    }

    @Test
    fun `no findings when manualUntil is in the future`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, manualUntil = LocalDateTime.of(2099, 1, 1, 0, 0)),
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }
}

class AprMismatchRuleTest {

    private val rule = AprMismatchRule()

    @Test
    fun `no findings when APRs are close`() {
        val snap = snapshot(plan = plan(
            nominalApr = BigDecimal("9.99"),
            effectiveApr = BigDecimal("10.45"),
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects APR mismatch exceeding 1 point`() {
        val snap = snapshot(plan = plan(
            nominalApr = BigDecimal("9.99"),
            effectiveApr = BigDecimal("11.50"),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.LOW, findings[0].severity)
    }

    @Test
    fun `no findings when effectiveApr is null`() {
        val snap = snapshot(plan = plan(
            nominalApr = BigDecimal("9.99"),
            effectiveApr = null,
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }
}

class MissingNotificationRuleTest {

    private val rule = MissingNotificationRule()

    @Test
    fun `no findings when no missing notifications`() {
        val snap = snapshot(notifications = NotificationSummary(emptyList(), emptyList(), emptyList()))
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects missing notifications`() {
        val snap = snapshot(notifications = NotificationSummary(
            sent = emptyList(),
            missing = listOf(MissingNotification(
                paymentId = 1L,
                paidOffDate = LocalDateTime.of(2026, 3, 15, 10, 0),
                expectedEmailType = "OnSchedule",
                description = "Payment 1 was charged but no email sent",
            )),
            erroneous = emptyList(),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.LOW, findings[0].severity)
    }
}

class ResidualPrincipalRuleTest {

    private val rule = ResidualPrincipalRule()

    @Test
    fun `no findings when last payment has normal principal`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, dueDate = LocalDate.of(2026, 3, 15),
                paidOffDate = LocalDateTime.of(2026, 3, 15, 10, 0),
                amount = BigDecimal("150.00"), principalBalance = BigDecimal("140.00")),
            payment(id = 2, dueDate = LocalDate.of(2026, 4, 15),
                paidOffDate = LocalDateTime.of(2026, 4, 15, 10, 0),
                amount = BigDecimal("150.00"), principalBalance = BigDecimal("140.00")),
            payment(id = 3, dueDate = LocalDate.of(2026, 5, 15),
                amount = BigDecimal("150.00"), principalBalance = BigDecimal("10.00")),
        ))
        assertTrue(rule.analyze(snap).isEmpty())
    }

    @Test
    fun `detects residual principal on last payment`() {
        val snap = snapshot(payments = listOf(
            payment(id = 1, dueDate = LocalDate.of(2026, 3, 15),
                paidOffDate = LocalDateTime.of(2026, 3, 15, 10, 0),
                amount = BigDecimal("150.00"), principalBalance = BigDecimal("140.00")),
            payment(id = 2, dueDate = LocalDate.of(2026, 4, 15),
                paidOffDate = LocalDateTime.of(2026, 4, 15, 10, 0),
                amount = BigDecimal("150.00"), principalBalance = BigDecimal("140.00")),
            payment(id = 3, dueDate = LocalDate.of(2026, 5, 15),
                amount = BigDecimal("150.00"), principalBalance = BigDecimal("100.00")),
        ))
        val findings = rule.analyze(snap)
        assertEquals(1, findings.size)
        assertEquals(Severity.MEDIUM, findings[0].severity)
    }
}
