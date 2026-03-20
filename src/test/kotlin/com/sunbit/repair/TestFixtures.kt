package com.sunbit.repair

import com.sunbit.repair.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Shared test fixtures for building PurchaseSnapshot instances
 * with controllable payment states.
 */
object TestFixtures {

    fun payment(
        id: Long = 1L,
        amount: BigDecimal = BigDecimal("150.00"),
        dueDate: LocalDate = LocalDate.of(2026, 4, 15),
        type: Int = 0,
        changeIndicator: Int = 0,
        isActive: Boolean = true,
        paidOffDate: LocalDateTime? = null,
        directParentId: Long? = null,
        originalPaymentId: Long? = null,
        chargeBack: String? = null,
        chargeBackEnhancement: String? = null,
        dispute: String? = null,
        manualUntil: LocalDateTime? = null,
        paymentProfileId: Long? = 100L,
        principalBalance: BigDecimal? = BigDecimal("100.00"),
        splitFrom: Long? = null,
        paymentActionId: Long? = null,
    ): Payment = Payment(
        id = id,
        paymentPlanId = 1L,
        amount = amount,
        interestAmount = BigDecimal("10.00"),
        interestCharge = BigDecimal("10.00"),
        principalBalance = principalBalance,
        dueDate = dueDate,
        effectiveDate = dueDate,
        paidOffDate = paidOffDate,
        refundDate = null,
        type = type,
        typeName = PaymentType.fromCodeOrNull(type),
        changeIndicator = changeIndicator,
        changeIndicatorName = ChangeIndicator.fromCodeOrNull(changeIndicator),
        isActive = isActive,
        paymentActionId = paymentActionId,
        chargeBack = chargeBack,
        chargeBackEnhancement = chargeBackEnhancement,
        dispute = dispute,
        splitFrom = splitFrom,
        originalPaymentId = originalPaymentId,
        directParentId = directParentId,
        paymentProfileId = paymentProfileId,
        manualUntil = manualUntil,
        creationDate = LocalDateTime.of(2026, 1, 1, 0, 0),
        amountPaid = null,
        initialAmount = null,
    )

    fun plan(
        nominalApr: BigDecimal = BigDecimal("9.99"),
        effectiveApr: BigDecimal? = BigDecimal("10.45"),
    ): PlanInfo = PlanInfo(
        planId = 1L,
        totalAmount = BigDecimal("1500.00"),
        amountFinanced = BigDecimal("1350.00"),
        totalOfPayments = BigDecimal("1450.00"),
        financialCharge = BigDecimal("100.00"),
        nominalApr = nominalApr,
        effectiveApr = effectiveApr,
        dailyApr = null,
        monthlyApr = null,
        numInstallments = 12,
        paymentProfileId = 100L,
    )

    fun balanceCheck(
        scheduleGap: BigDecimal = BigDecimal.ZERO,
        moneyGap: BigDecimal = BigDecimal.ZERO,
    ): BalanceCheck = BalanceCheck(
        planTotal = BigDecimal("1450.00"),
        moneyCollected = BigDecimal("600.00"),
        moneyReturned = BigDecimal.ZERO,
        netCollected = BigDecimal("600.00"),
        downPayment = BigDecimal("150.00"),
        paidInstallments = BigDecimal("450.00"),
        unpaidInstallments = BigDecimal("1000.00"),
        scheduleTotal = BigDecimal("1450.00"),
        scheduleGap = scheduleGap,
        moneyGap = moneyGap,
        checkAVerdict = if (scheduleGap == BigDecimal.ZERO) "PASS" else "FAIL",
        checkBVerdict = if (moneyGap == BigDecimal.ZERO) "PASS" else "FAIL",
    )

    fun crossSchema(
        verdict: String = "PASS: charge counts and amounts match",
    ): CrossSchemaReconciliation = CrossSchemaReconciliation(
        processorSuccessCount = 4,
        processorSuccessAmount = BigDecimal("600.00"),
        purchaseChargeCount = 4,
        purchaseChargeAmount = BigDecimal("600.00"),
        processorRefundCount = 0,
        processorRefundAmount = BigDecimal.ZERO,
        purchaseRefundCount = 0,
        purchaseRefundAmount = BigDecimal.ZERO,
        processorFailCount = 0,
        verdict = verdict,
    )

    fun snapshot(
        purchaseId: Long = 12345L,
        payments: List<Payment> = emptyList(),
        chargeTransactions: List<ChargeTransaction> = emptyList(),
        balanceCheck: BalanceCheck = balanceCheck(),
        crossSchema: CrossSchemaReconciliation = crossSchema(),
        notifications: NotificationSummary = NotificationSummary(emptyList(), emptyList(), emptyList()),
        paymentActions: List<PaymentAction> = emptyList(),
        plan: PlanInfo = plan(),
    ): PurchaseSnapshot = PurchaseSnapshot(
        purchaseId = purchaseId,
        loadedAt = Instant.now(),
        purchaseStatus = 4,
        cppStatus = CppStatus.ON_SCHEDULE,
        plan = plan,
        payments = payments,
        chargeTransactions = chargeTransactions,
        moneyMovement = MoneyMovement(emptyList()),
        balanceCheck = balanceCheck,
        notifications = notifications,
        supportTickets = emptyList(),
        auditTrail = emptyList(),
        paymentActions = paymentActions,
        paymentAttempts = emptyList(),
        crossSchemaReconciliation = crossSchema,
    )
}
