package com.sunbit.repair.loader

import com.sunbit.repair.domain.BalanceCheck
import com.sunbit.repair.domain.ChangeIndicator
import com.sunbit.repair.domain.ChargeServiceAttempt
import com.sunbit.repair.domain.ChargeServiceAttemptStatus
import com.sunbit.repair.domain.ChargeTransaction
import com.sunbit.repair.domain.ChargeTransactionType
import com.sunbit.repair.domain.CppStatus
import com.sunbit.repair.domain.CrossSchemaReconciliation
import com.sunbit.repair.domain.EmailType
import com.sunbit.repair.domain.ErroneousNotification
import com.sunbit.repair.domain.LoanTransaction
import com.sunbit.repair.domain.MissingNotification
import com.sunbit.repair.domain.MoneyMovement
import com.sunbit.repair.domain.MoneyMovementEntry
import com.sunbit.repair.domain.NotificationRecord
import com.sunbit.repair.domain.NotificationSummary
import com.sunbit.repair.domain.Payment
import com.sunbit.repair.domain.PaymentAction
import com.sunbit.repair.domain.PaymentActionType
import com.sunbit.repair.domain.PaymentAttempt
import com.sunbit.repair.domain.PaymentAttemptSource
import com.sunbit.repair.domain.PaymentAuditRecord
import com.sunbit.repair.domain.PaymentType
import com.sunbit.repair.domain.PlanInfo
import com.sunbit.repair.domain.SupportTicket
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.ResultSet

@Service
class SnowflakeLoader(
    @Qualifier("snowflakeJdbcTemplate") private val jdbc: JdbcTemplate
) {

    private val log = LoggerFactory.getLogger(SnowflakeLoader::class.java)

    // -----------------------------------------------------------------------
    // Q-M1: Plan Financials (returns PlanInfo + purchase-level status fields)
    // -----------------------------------------------------------------------

    fun loadPlanInfo(purchaseId: Long): PlanQueryResult {
        log.info("[SnowflakeLoader][loadPlanInfo] Loading plan info for purchase {}", purchaseId)
        return jdbc.queryForObject(SQL_PLAN_INFO, { rs, _ -> mapPlanQueryResult(rs) }, purchaseId)
            ?: throw IllegalStateException("[SnowflakeLoader][loadPlanInfo] No plan found for purchase $purchaseId")
    }

    // -----------------------------------------------------------------------
    // Q-L1: All Payments (active + inactive)
    // -----------------------------------------------------------------------

    fun loadPayments(purchaseId: Long): List<Payment> {
        log.info("[SnowflakeLoader][loadPayments] Loading all payments for purchase {}", purchaseId)
        val payments = jdbc.query(SQL_ALL_PAYMENTS, { rs, _ -> mapPayment(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadPayments] Loaded {} payments for purchase {}", payments.size, purchaseId)
        return payments
    }

    // -----------------------------------------------------------------------
    // Q-M2: Charge Transactions
    // -----------------------------------------------------------------------

    fun loadChargeTransactions(purchaseId: Long): List<ChargeTransaction> {
        log.info("[SnowflakeLoader][loadChargeTransactions] Loading charge transactions for purchase {}", purchaseId)
        val txns = jdbc.query(SQL_CHARGE_TRANSACTIONS, { rs, _ -> mapChargeTransaction(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadChargeTransactions] Loaded {} charge transactions for purchase {}", txns.size, purchaseId)
        return txns
    }

    // -----------------------------------------------------------------------
    // Q-M2: Money Movement (aggregated)
    // -----------------------------------------------------------------------

    fun loadMoneyMovement(purchaseId: Long): MoneyMovement {
        log.info("[SnowflakeLoader][loadMoneyMovement] Loading money movement for purchase {}", purchaseId)
        val entries = jdbc.query(SQL_MONEY_MOVEMENT, { rs, _ -> mapMoneyMovementEntry(rs) }, purchaseId)
        return MoneyMovement(byType = entries)
    }

    // -----------------------------------------------------------------------
    // Q-M3: Balance Check
    // -----------------------------------------------------------------------

    fun loadBalanceCheck(purchaseId: Long): BalanceCheck {
        log.info("[SnowflakeLoader][loadBalanceCheck] Loading balance check for purchase {}", purchaseId)
        return jdbc.queryForObject(
            SQL_BALANCE_CHECK, { rs, _ -> mapBalanceCheck(rs) },
            purchaseId, purchaseId, purchaseId
        ) ?: throw IllegalStateException("[SnowflakeLoader][loadBalanceCheck] No balance check result for purchase $purchaseId")
    }

    // -----------------------------------------------------------------------
    // Q-N1: Notifications
    // -----------------------------------------------------------------------

    fun loadNotifications(purchaseId: Long): NotificationSummary {
        log.info("[SnowflakeLoader][loadNotifications] Loading notifications for purchase {}", purchaseId)
        val sent = jdbc.query(SQL_NOTIFICATIONS_SENT, { rs, _ -> mapNotificationRecord(rs) }, purchaseId)
        val missing = jdbc.query(SQL_NOTIFICATIONS_MISSING, { rs, _ -> mapMissingNotification(rs) }, purchaseId, purchaseId)
        val erroneous = jdbc.query(SQL_NOTIFICATIONS_ERRONEOUS, { rs, _ -> mapErroneousNotification(rs) }, purchaseId, purchaseId)

        // Enrich with GOLD email data (notification IDs, subjects, recipients, templates)
        val enriched = try {
            val goldEmails = jdbc.query(SQL_GOLD_EMAILS, { rs, _ ->
                GoldEmail(
                    notificationId = rs.str("notification_id") ?: "",
                    recipientAddress = rs.str("recipient_address"),
                    templateName = rs.str("template_name"),
                    subject = rs.str("subject"),
                    sentTimestamp = rs.ts("sent_timestamp_utc")?.toLocalDateTime(),
                )
            }, purchaseId)
            log.info("[SnowflakeLoader][loadNotifications] Loaded {} GOLD emails for purchase {}", goldEmails.size, purchaseId)

            // Match GOLD emails to BRONZE sent records by template type mapping.
            // BRONZE has numeric TYPE, GOLD has TEMPLATE_NAME string.
            val typeToTemplate = mapOf(
                1 to "Agreement",
                3 to "Agreement",        // Draft uses same template family
                4 to "RIC",              // Retailer charge notification
                5 to "OnSchedule",
                7 to "PaidOff",
                9 to "PaidPaymentLatePurchase",
                10 to "pay.now",
                12 to "ChargeFailed",
            )
            // Track consumed GOLD emails to avoid double-matching
            val consumedGold = mutableSetOf<String>()
            sent.map { notification ->
                val templateHint = typeToTemplate[notification.type] ?: ""
                val match = goldEmails.find { gold ->
                    gold.notificationId !in consumedGold &&
                        gold.templateName != null &&
                        gold.templateName.contains(templateHint, ignoreCase = true)
                }
                if (match != null) {
                    consumedGold.add(match.notificationId)
                    notification.copy(
                        notificationId = match.notificationId,
                        recipientAddress = match.recipientAddress,
                        templateName = match.templateName,
                        subject = match.subject,
                    )
                } else notification
            }
        } catch (e: Exception) {
            log.warn("[SnowflakeLoader][loadNotifications] Failed to load GOLD emails for purchase {}: {}", purchaseId, e.message)
            sent
        }

        log.info(
            "[SnowflakeLoader][loadNotifications] Loaded {} sent, {} missing, {} erroneous notifications for purchase {}",
            enriched.size, missing.size, erroneous.size, purchaseId
        )
        return NotificationSummary(sent = enriched, missing = missing, erroneous = erroneous)
    }

    private data class GoldEmail(
        val notificationId: String,
        val recipientAddress: String?,
        val templateName: String?,
        val subject: String?,
        val sentTimestamp: java.time.LocalDateTime?,
    )

    // -----------------------------------------------------------------------
    // Q-N4: Support Tickets
    // -----------------------------------------------------------------------

    fun loadSupportTickets(purchaseId: Long): List<SupportTicket> {
        log.info("[SnowflakeLoader][loadSupportTickets] Loading support tickets for purchase {}", purchaseId)
        val tickets = jdbc.query(SQL_SUPPORT_TICKETS, { rs, _ -> mapSupportTicket(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadSupportTickets] Loaded {} support tickets for purchase {}", tickets.size, purchaseId)
        return tickets
    }

    // -----------------------------------------------------------------------
    // Q-A1: Audit Trail
    // -----------------------------------------------------------------------

    fun loadAuditTrail(purchaseId: Long): List<PaymentAuditRecord> {
        log.info("[SnowflakeLoader][loadAuditTrail] Loading audit trail for purchase {}", purchaseId)
        val records = jdbc.query(SQL_AUDIT_TRAIL, { rs, _ -> mapAuditRecord(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadAuditTrail] Loaded {} audit records for purchase {}", records.size, purchaseId)
        return records
    }

    // -----------------------------------------------------------------------
    // Q-L3: Payment Actions
    // -----------------------------------------------------------------------

    fun loadPaymentActions(purchaseId: Long): List<PaymentAction> {
        log.info("[SnowflakeLoader][loadPaymentActions] Loading payment actions for purchase {}", purchaseId)
        val actions = jdbc.query(SQL_PAYMENT_ACTIONS, { rs, _ -> mapPaymentAction(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadPaymentActions] Loaded {} payment actions for purchase {}", actions.size, purchaseId)
        return actions
    }

    // -----------------------------------------------------------------------
    // Payment Attempts
    // -----------------------------------------------------------------------

    fun loadPaymentAttempts(purchaseId: Long): List<PaymentAttempt> {
        log.info("[SnowflakeLoader][loadPaymentAttempts] Loading payment attempts for purchase {}", purchaseId)
        val attempts = jdbc.query(SQL_PAYMENT_ATTEMPTS, { rs, _ -> mapPaymentAttempt(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadPaymentAttempts] Loaded {} payment attempts for purchase {}", attempts.size, purchaseId)
        return attempts
    }

    // -----------------------------------------------------------------------
    // Q-X1: Cross-Schema Reconciliation
    // -----------------------------------------------------------------------

    fun loadCrossSchemaReconciliation(purchaseId: Long): CrossSchemaReconciliation {
        log.info("[SnowflakeLoader][loadCrossSchemaReconciliation] Loading cross-schema reconciliation for purchase {}", purchaseId)
        val externalIdPattern = "${purchaseId}%"
        return jdbc.queryForObject(
            SQL_CROSS_SCHEMA_RECONCILIATION,
            { rs, _ -> mapCrossSchemaReconciliation(rs) },
            externalIdPattern, purchaseId
        ) ?: throw IllegalStateException(
            "[SnowflakeLoader][loadCrossSchemaReconciliation] No reconciliation result for purchase $purchaseId"
        )
    }

    // -----------------------------------------------------------------------
    // Charge Service Attempts (from BRONZE.CHARGE.PAYMENT_ATTEMPTS)
    // -----------------------------------------------------------------------

    fun loadChargeServiceAttempts(purchaseId: Long): List<ChargeServiceAttempt> {
        log.info("[SnowflakeLoader][loadChargeServiceAttempts] Loading charge service attempts for purchase {}", purchaseId)
        val externalIdPattern = "${purchaseId}%"
        val attempts = jdbc.query(SQL_CHARGE_SERVICE_ATTEMPTS, { rs, _ -> mapChargeServiceAttempt(rs) }, externalIdPattern)
        log.info("[SnowflakeLoader][loadChargeServiceAttempts] Loaded {} charge service attempts for purchase {}", attempts.size, purchaseId)
        return attempts
    }

    // -----------------------------------------------------------------------
    // Charge Service Attempt Statuses (from BRONZE.CHARGE.PAYMENT_ATTEMPT_STATUSES)
    // -----------------------------------------------------------------------

    fun loadChargeServiceStatuses(attemptIds: List<Long>): List<ChargeServiceAttemptStatus> {
        if (attemptIds.isEmpty()) return emptyList()
        log.info("[SnowflakeLoader][loadChargeServiceStatuses] Loading statuses for {} charge service attempts", attemptIds.size)
        val sql = """
            SELECT pas.ID, pas.PAYMENT_ATTEMPT_ID, pas.CREATION_TIME, pas.LAST_UPDATE_TIME_PST,
                   pas.CHARGE_STATUS, pas.CHARGE_STATUS_REASON, pas.SUMMARY
            FROM BRONZE.CHARGE.PAYMENT_ATTEMPT_STATUSES pas
            WHERE pas.PAYMENT_ATTEMPT_ID IN (${attemptIds.joinToString(",")})
            ORDER BY pas.CREATION_TIME
        """.trimIndent()
        val statuses = jdbc.query(sql) { rs, _ -> mapChargeServiceAttemptStatus(rs) }
        log.info("[SnowflakeLoader][loadChargeServiceStatuses] Loaded {} statuses for charge service attempts", statuses.size)
        return statuses
    }

    // -----------------------------------------------------------------------
    // Loan Transactions (from BRONZE.PURCHASE.LOAN_TRANSACTIONS)
    // -----------------------------------------------------------------------

    fun loadLoanTransactions(purchaseId: Long): List<LoanTransaction> {
        log.info("[SnowflakeLoader][loadLoanTransactions] Loading loan transactions for purchase {}", purchaseId)
        val transactions = jdbc.query(SQL_LOAN_TRANSACTIONS, { rs, _ -> mapLoanTransaction(rs) }, purchaseId)
        log.info("[SnowflakeLoader][loadLoanTransactions] Loaded {} loan transactions for purchase {}", transactions.size, purchaseId)
        return transactions
    }

    // -----------------------------------------------------------------------
    // Disbursals
    // -----------------------------------------------------------------------

    fun loadDisbursals(purchaseId: Long): List<com.sunbit.repair.domain.Disbursal> {
        log.info("[SnowflakeLoader][loadDisbursals] Loading disbursals for purchase {}", purchaseId)
        val disbursals = jdbc.query("""
            SELECT afd.ID, afd.PAYMENT_PLAN_ID, afd.DISBURSAL_DATE, afd.AMOUNT, afd.CREATION_TIME
            FROM BRONZE.PURCHASE.AMOUNT_FINANCED_DISBURSALS afd
            JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON afd.PAYMENT_PLAN_ID = pp.ID
            JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
            WHERE pp.PURCHASE_ID = ?
            ORDER BY afd.DISBURSAL_DATE
        """.trimIndent(), { rs, _ ->
            com.sunbit.repair.domain.Disbursal(
                id = rs.long_("id"),
                paymentPlanId = rs.long_("payment_plan_id"),
                disbursalDate = rs.dt("disbursal_date")?.toLocalDate(),
                amount = rs.bd("amount"),
                creationTime = rs.ts("creation_time")?.toLocalDateTime(),
            )
        }, purchaseId)
        log.info("[SnowflakeLoader][loadDisbursals] Loaded {} disbursals for purchase {}", disbursals.size, purchaseId)
        return disbursals
    }

    fun loadDisbursalDiffs(purchaseId: Long): List<com.sunbit.repair.domain.DisbursalDiff> {
        log.info("[SnowflakeLoader][loadDisbursalDiffs] Loading disbursal diffs for purchase {}", purchaseId)
        return jdbc.query("""
            SELECT afd.ID, afd.PAYMENT_PLAN_ID, afd.PAYMENT_ACTION_ID, afd.DISBURSAL_DATE, afd.AMOUNT_DIFF
            FROM BRONZE.PURCHASE.AMOUNT_FINANCED_DISBURSALS_DIFF afd
            JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON afd.PAYMENT_PLAN_ID = pp.ID
            JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
            WHERE pp.PURCHASE_ID = ?
            ORDER BY afd.DISBURSAL_DATE
        """.trimIndent(), { rs, _ ->
            com.sunbit.repair.domain.DisbursalDiff(
                id = rs.long_("id"),
                disbursalId = 0, // linked by date, not FK
                paymentActionId = rs.getLongOrNull("payment_action_id"),
                amountDiff = rs.bd("amount_diff"),
                disbursalDate = rs.dt("disbursal_date")?.toLocalDate()?.toString(),
            )
        }, purchaseId)
    }

    fun loadPurchaseProperties(purchaseId: Long): Map<String, String> {
        log.info("[SnowflakeLoader][loadPurchaseProperties] Loading properties for purchase {}", purchaseId)
        val props = mutableMapOf<String, String>()
        jdbc.query("""
            SELECT pp.PROPERTY_NAME, pp.VALUE
            FROM BRONZE.PURCHASE.PURCHASE_PROPERTIES pp
            WHERE pp.PURCHASE_ID = ?
        """.trimIndent(), { rs, _ ->
            val name = rs.str("property_name") ?: ""
            val value = rs.str("value") ?: ""
            props[name] = value
        }, purchaseId)
        log.info("[SnowflakeLoader][loadPurchaseProperties] Loaded {} properties for purchase {}", props.size, purchaseId)
        return props
    }

    // =======================================================================
    // Row mappers
    // =======================================================================

    private fun mapPlanQueryResult(rs: ResultSet): PlanQueryResult {
        val cppStatusCode = rs.int_("cpp_status")
        return PlanQueryResult(
            planInfo = PlanInfo(
                planId = rs.long_("plan_id"),
                totalAmount = rs.bd("total_purchase_amount"),
                amountFinanced = rs.bd("amount_financed"),
                totalOfPayments = rs.bd("total_of_payments"),
                financialCharge = rs.bd("financial_charge"),
                nominalApr = rs.bd("apr"),
                effectiveApr = rs.getBigDecimalOrNull("effective_apr"),
                dailyApr = rs.getBigDecimalOrNull("daily_apr"),
                monthlyApr = rs.getBigDecimalOrNull("monthly_apr"),
                numInstallments = rs.int_("num_installments"),
                paymentProfileId = rs.getLongOrNull("payment_profile_id"),
                paymentsInterval = rs.getIntOrNull("payments_interval"),
            ),
            purchaseStatus = rs.int_("purchase_status"),
            cppStatus = CppStatus.entries.firstOrNull { it.code == cppStatusCode }
                ?: CppStatus.ON_SCHEDULE,
        )
    }

    private fun mapPayment(rs: ResultSet): Payment {
        val typeCode = rs.int_("type")
        val ciCode = rs.int_("change_indicator")
        return Payment(
            id = rs.long_("payment_id"),
            paymentPlanId = rs.long_("paymentplan_id"),
            amount = rs.bd("amount"),
            interestAmount = rs.getBigDecimalOrNull("interest_amount"),
            interestCharge = rs.getBigDecimalOrNull("interest_charge"),
            principalBalance = rs.getBigDecimalOrNull("principal_balance"),
            dueDate = rs.dt("due_date")!!.toLocalDate(),
            effectiveDate = rs.dt("effective_date")?.toLocalDate(),
            paidOffDate = rs.ts("paid_off_date")?.toLocalDateTime(),
            refundDate = rs.ts("refund_date")?.toLocalDateTime(),
            type = typeCode,
            typeName = PaymentType.fromCodeOrNull(typeCode),
            changeIndicator = ciCode,
            changeIndicatorName = ChangeIndicator.fromCodeOrNull(ciCode),
            isActive = rs.bool_("is_active"),
            paymentActionId = rs.getLongOrNull("payment_action_id"),
            chargeBack = rs.str("charge_back"),
            chargeBackEnhancement = rs.str("charge_back_enhancement"),
            dispute = rs.str("dispute"),
            splitFrom = rs.getLongOrNull("split_from"),
            originalPaymentId = rs.getLongOrNull("original_payment_id"),
            directParentId = rs.getLongOrNull("direct_parent_id"),
            paymentProfileId = rs.getLongOrNull("payment_profile_id"),
            manualUntil = rs.ts("manual_until")?.toLocalDateTime(),
            creationDate = rs.ts("creation_date")?.toLocalDateTime(),
            amountPaid = rs.getBigDecimalOrNull("amount_paid"),
            initialAmount = rs.getBigDecimalOrNull("initial_amount"),
        )
    }

    private fun mapChargeTransaction(rs: ResultSet): ChargeTransaction {
        val typeCode = rs.int_("type")
        return ChargeTransaction(
            id = rs.long_("id"),
            purchaseId = rs.long_("purchase_id"),
            type = typeCode,
            typeName = ChargeTransactionType.fromCodeOrNull(typeCode),
            amount = rs.bd("amount"),
            chargeTime = rs.ts("charge_time")?.toLocalDateTime(),
            chargeback = rs.getBooleanOrNull("chargeback"),
            chargebackEnhancement = rs.getBooleanOrNull("chargeback_enhancement"),
            parentId = rs.getLongOrNull("parent_id"),
            paymentProfileId = rs.getLongOrNull("payment_profile_id"),
            manualAdjustment = rs.getBooleanOrNull("manual_adjustment"),
        )
    }

    private fun mapMoneyMovementEntry(rs: ResultSet): MoneyMovementEntry = MoneyMovementEntry(
        type = rs.int_("ct_type"),
        typeName = rs.str("ct_type_name") ?: "",
        count = rs.int_("tx_count"),
        totalAmount = rs.bd("total_amount"),
        earliest = rs.ts("earliest")?.toLocalDateTime(),
        latest = rs.ts("latest")?.toLocalDateTime(),
    )

    private fun mapBalanceCheck(rs: ResultSet): BalanceCheck = BalanceCheck(
        planTotal = rs.bd("plan_total"),
        moneyCollected = rs.bd("money_collected"),
        moneyReturned = rs.bd("money_returned"),
        netCollected = rs.bd("net_collected"),
        downPayment = rs.bd("down_payment"),
        paidInstallments = rs.bd("paid_installments"),
        unpaidInstallments = rs.bd("unpaid_installments"),
        scheduleTotal = rs.bd("schedule_total"),
        scheduleGap = rs.bd("schedule_gap"),
        moneyGap = rs.bd("money_gap"),
        checkAVerdict = rs.str("check_a_schedule") ?: "",
        checkBVerdict = rs.str("check_b_money") ?: "",
    )

    private fun mapNotificationRecord(rs: ResultSet): NotificationRecord {
        val typeCode = rs.int_("email_type")
        return NotificationRecord(
            id = rs.long_("email_id"),
            purchaseId = rs.long_("purchase_id"),
            type = typeCode,
            typeName = EmailType.fromCodeOrNull(typeCode),
            status = rs.getIntOrNull("send_status"),
            changeStatusTime = rs.ts("sent_at")?.toLocalDateTime(),
            purchaseStatusOnSend = null,
        )
    }

    private fun mapMissingNotification(rs: ResultSet): MissingNotification = MissingNotification(
        paymentId = rs.long_("payment_id"),
        paidOffDate = rs.ts("charge_time")!!.toLocalDateTime(),
        expectedEmailType = "PAYMENT_RECEIVED",
        description = rs.str("notification_check") ?: "",
    )

    private fun mapErroneousNotification(rs: ResultSet): ErroneousNotification = ErroneousNotification(
        notificationId = rs.long_("email_id"),
        type = rs.int_("email_type"),
        typeName = rs.str("email_type_name") ?: "",
        reason = rs.str("error_check") ?: "",
    )

    private fun mapSupportTicket(rs: ResultSet): SupportTicket = SupportTicket(
        id = rs.str("ticket_detail_id") ?: "",
        purchaseId = rs.long_("purchase_id"),
        subject = rs.str("topic"),
        status = null,
        createdAt = rs.ts("update_time")?.toLocalDateTime(),
        updatedAt = rs.ts("update_time")?.toLocalDateTime(),
        assignee = rs.str("representative_name"),
        category = rs.str("subtopic"),
        priority = null,
        description = rs.str("notes"),
        channel = rs.str("origin"),
    )

    private fun mapAuditRecord(rs: ResultSet): PaymentAuditRecord = PaymentAuditRecord(
        paymentId = rs.long_("payment_id"),
        rev = rs.long_("rev"),
        amount = rs.getBigDecimalOrNull("amount"),
        isActive = rs.getBooleanOrNull("is_active"),
        paidOffDate = rs.ts("paid_off_date")?.toLocalDateTime(),
        changeIndicator = rs.getIntOrNull("change_indicator"),
        rowTime = rs.ts("rowtime")?.toLocalDateTime(),
    )

    private fun mapPaymentAction(rs: ResultSet): PaymentAction {
        val typeCode = rs.int_("action_type")
        return PaymentAction(
            id = rs.long_("action_id"),
            type = typeCode,
            typeName = PaymentActionType.fromCodeOrNull(typeCode),
            purchaseId = rs.long_("purchase_id"),
            timeOfAction = rs.ts("time_of_action")?.toLocalDateTime(),
        )
    }

    private fun mapPaymentAttempt(rs: ResultSet): PaymentAttempt {
        val sourceCode = rs.getIntOrNull("source")
        return PaymentAttempt(
            id = rs.str("id") ?: "",
            paymentId = rs.long_("payment_id"),
            triggeringPaymentId = rs.getLongOrNull("triggering_payment_id"),
            source = sourceCode,
            sourceName = sourceCode?.let { PaymentAttemptSource.fromCodeOrNull(it) },
            dateTime = rs.ts("date_time")?.toLocalDateTime(),
            type = rs.getIntOrNull("type"),
            status = rs.getIntOrNull("status"),
            failMessage = rs.str("fail_message"),
            processorTxId = rs.str("processor_tx_id"),
            holdTransactionId = rs.str("hold_transaction_id"),
            chargeTransactionId = rs.str("charge_transaction_id"),
        )
    }

    private fun mapCrossSchemaReconciliation(rs: ResultSet): CrossSchemaReconciliation = CrossSchemaReconciliation(
        processorSuccessCount = rs.int_("processor_success_count"),
        processorSuccessAmount = rs.bd("processor_success_amount"),
        purchaseChargeCount = rs.int_("purchase_charge_count"),
        purchaseChargeAmount = rs.bd("purchase_charge_amount"),
        processorRefundCount = rs.int_("processor_refund_count"),
        processorRefundAmount = rs.bd("processor_refund_amount"),
        purchaseRefundCount = rs.int_("purchase_refund_count"),
        purchaseRefundAmount = rs.bd("purchase_refund_amount"),
        processorFailCount = rs.int_("processor_fail_count"),
        verdict = rs.str("reconciliation_verdict") ?: "",
    )

    private fun mapChargeServiceAttempt(rs: ResultSet): ChargeServiceAttempt = ChargeServiceAttempt(
        id = rs.long_("id"),
        dateTime = rs.ts("date_time")?.toLocalDateTime(),
        lastUpdateTime = rs.ts("last_update_time_pst")?.toLocalDateTime(),
        paymentProfileId = rs.getLongOrNull("payment_profile_id"),
        paymentProcessor = rs.str("payment_processor"),
        processorTxId = rs.str("processor_tx_id"),
        failMessage = rs.str("fail_message"),
        externalId = rs.str("external_id") ?: "",
        amount = rs.bd("amount"),
        status = rs.int_("status"),
        type = rs.int_("type"),
        origin = rs.str("origin"),
    )

    private fun mapChargeServiceAttemptStatus(rs: ResultSet): ChargeServiceAttemptStatus = ChargeServiceAttemptStatus(
        id = rs.long_("id"),
        paymentAttemptId = rs.long_("payment_attempt_id"),
        creationTime = rs.ts("creation_time")?.toLocalDateTime(),
        lastUpdateTime = rs.ts("last_update_time_pst")?.toLocalDateTime(),
        chargeStatus = rs.str("charge_status"),
        chargeStatusReason = rs.str("charge_status_reason"),
        summary = rs.str("summary"),
    )

    private fun mapLoanTransaction(rs: ResultSet): LoanTransaction = LoanTransaction(
        id = rs.long_("id"),
        paymentId = rs.long_("payment_id"),
        chargeTransactionId = rs.long_("charge_transaction_id"),
        amount = rs.bd("amount"),
        effectiveDate = rs.dt("effective_date")?.toLocalDate(),
        interestAmount = rs.getBigDecimalOrNull("interest_amount"),
        interestCharge = rs.getBigDecimalOrNull("interest_charge"),
        principalBalance = rs.getBigDecimalOrNull("principal_balance"),
    )

    // =======================================================================
    // Nullable-safe ResultSet extensions
    // Snowflake JDBC returns column names in UPPERCASE regardless of the
    // alias case in the SQL, so all column lookups go through uppercase().
    // =======================================================================

    private fun ResultSet.col(name: String): String = name.uppercase()

    private fun ResultSet.str(col: String): String? = getString(col.uppercase())
    private fun ResultSet.long_(col: String): Long = getLong(col.uppercase())
    private fun ResultSet.int_(col: String): Int = getInt(col.uppercase())
    private fun ResultSet.bool_(col: String): Boolean = getBoolean(col.uppercase())
    private fun ResultSet.bd(col: String): BigDecimal = getBigDecimal(col.uppercase())
    private fun ResultSet.ts(col: String): java.sql.Timestamp? = getTimestamp(col.uppercase())
    private fun ResultSet.dt(col: String): java.sql.Date? = getDate(col.uppercase())

    private fun ResultSet.getBigDecimalOrNull(col: String): BigDecimal? {
        val v = getBigDecimal(col.uppercase())
        return if (wasNull()) null else v
    }

    private fun ResultSet.getLongOrNull(col: String): Long? {
        val v = getLong(col.uppercase())
        return if (wasNull()) null else v
    }

    private fun ResultSet.getIntOrNull(col: String): Int? {
        val v = getInt(col.uppercase())
        return if (wasNull()) null else v
    }

    private fun ResultSet.getBooleanOrNull(col: String): Boolean? {
        val v = getBoolean(col.uppercase())
        return if (wasNull()) null else v
    }

    // =======================================================================
    // SQL constants
    // =======================================================================

    companion object {

        // -- Q-M1: Plan Financials ------------------------------------------------

        private const val SQL_PLAN_INFO = """
            SELECT
                pp.ID                    AS plan_id,
                p.STATUS                 AS purchase_status,
                p.TOTAL_AMOUNT           AS total_purchase_amount,
                pp.AMOUNT_FINANCED       AS amount_financed,
                pp.TOTAL_OF_PAYMENTS     AS total_of_payments,
                pp.FINANCIAL_CHARGE      AS financial_charge,
                pp.NOMINAL_APR           AS apr,
                pp.EFFECTIVE_APR         AS effective_apr,
                pp.DAILY_APR             AS daily_apr,
                pp.MONTHLY_APR           AS monthly_apr,
                pp.NUM_OF_PAYMENTS       AS num_installments,
                pp.PAYMENT_PROFILE_ID    AS payment_profile_id,
                pp.PAYMENTS_INTERVAL     AS payments_interval,
                cpp.STATUS               AS cpp_status
            FROM BRONZE.PURCHASE.PURCHASES p
            JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON p.ID = pp.PURCHASE_ID
            JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON pp.ID = cpp.PAYMENT_PLAN_ID
            WHERE p.ID = ?
        """

        // -- Q-L1: All Payments (active + inactive for full parent-child chains) --

        private const val SQL_ALL_PAYMENTS = """
            SELECT
                p.ID                     AS payment_id,
                p.PAYMENTPLAN_ID         AS paymentplan_id,
                p.AMOUNT,
                p.INTEREST_AMOUNT,
                p.INTEREST_CHARGE,
                p.PRINCIPAL_BALANCE,
                p.DUE_DATE,
                p.EFFECTIVE_DATE,
                p.PAID_OFF_DATE,
                p.REFUND_DATE,
                p.TYPE,
                p.CHANGE_INDICATOR,
                p.IS_ACTIVE,
                p.PAYMENT_ACTION_ID,
                p.CHARGE_BACK,
                p.CHARGE_BACK_ENHANCEMENT,
                p.DISPUTE,
                p.SPLIT_FROM,
                p.ORIGINAL_PAYMENT_ID,
                p.DIRECT_PARENT_ID,
                p.PAYMENT_PROFILE_ID,
                p.MANUAL_UNTIL,
                p.CREATION_DATE,
                p.AMOUNT_PAID,
                p.INITIAL_AMOUNT
            FROM BRONZE.PURCHASE.PAYMENTS p
            JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON p.PAYMENTPLAN_ID = pp.ID
            JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
            WHERE pp.PURCHASE_ID = ?
            ORDER BY p.DUE_DATE, p.ID
        """

        // -- Charge Transactions (individual rows) --------------------------------

        private const val SQL_CHARGE_TRANSACTIONS = """
            SELECT
                ct.ID,
                ct.PURCHASE_ID,
                ct.TYPE,
                ct.AMOUNT,
                ct.CHARGE_TIME,
                ct.CHARGEBACK,
                ct.CHARGEBACK_ENHANCEMENT,
                ct.PARENT_ID,
                ct.PAYMENT_PROFILE_ID,
                ct.MANUAL_ADJUSTMENT
            FROM BRONZE.PURCHASE.CHARGE_TRANSACTIONS ct
            WHERE ct.PURCHASE_ID = ?
            ORDER BY ct.CHARGE_TIME
        """

        // -- Q-M2: Money Movement (aggregated) ------------------------------------

        private const val SQL_MONEY_MOVEMENT = """
            SELECT
                ct.TYPE                                          AS ct_type,
                CASE ct.TYPE
                    WHEN 0 THEN 'SCHEDULED_PAYMENT'
                    WHEN 1 THEN 'UNSCHEDULED_PAYMENT'
                    WHEN 2 THEN 'DOWN_PAYMENT'
                    WHEN 3 THEN 'CHARGEBACK'
                    WHEN 4 THEN 'REVERSE_CHARGEBACK'
                    WHEN 5 THEN 'ADJUSTMENT_REFUND'
                    WHEN 6 THEN 'CANCEL_REFUND'
                    WHEN 7 THEN 'DP_ADJUSTMENT_REFUND'
                    WHEN 8 THEN 'UNPAID'
                    WHEN 9 THEN 'RETAILER_ADJUSTMENT'
                    WHEN 12 THEN 'INTEREST_ELIMINATION'
                    WHEN 13 THEN 'PAYMENT_REFUND'
                    ELSE 'TYPE_' || ct.TYPE
                END                                              AS ct_type_name,
                COUNT(*)                                         AS tx_count,
                ROUND(SUM(ct.AMOUNT), 2)                         AS total_amount,
                MIN(ct.CHARGE_TIME)                              AS earliest,
                MAX(ct.CHARGE_TIME)                              AS latest
            FROM BRONZE.PURCHASE.CHARGE_TRANSACTIONS ct
            WHERE ct.PURCHASE_ID = ?
            GROUP BY ct.TYPE
            ORDER BY ct.TYPE
        """

        // -- Q-M3: Balance Check --------------------------------------------------

        private const val SQL_BALANCE_CHECK = """
            WITH plan_info AS (
                SELECT pp.ID AS plan_id, pp.TOTAL_OF_PAYMENTS
                FROM BRONZE.PURCHASE.PAYMENTPLANS pp
                JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
            ),
            money_moved AS (
                SELECT
                    ROUND(SUM(CASE WHEN ct.TYPE IN (0, 1, 2) THEN ct.AMOUNT ELSE 0 END), 2)        AS collected,
                    ROUND(SUM(CASE WHEN ct.TYPE IN (5, 6, 7, 8, 13) THEN ct.AMOUNT ELSE 0 END), 2) AS returned,
                    ROUND(SUM(ct.AMOUNT), 2)                                                         AS net
                FROM BRONZE.PURCHASE.CHARGE_TRANSACTIONS ct
                WHERE ct.PURCHASE_ID = ?
            ),
            active_balance AS (
                SELECT
                    ROUND(SUM(CASE WHEN p.IS_ACTIVE = TRUE AND p.PAID_OFF_DATE IS NOT NULL AND p.TYPE != 30
                                   THEN p.AMOUNT ELSE 0 END), 2) AS paid_installments_sum,
                    ROUND(SUM(CASE WHEN p.IS_ACTIVE = TRUE AND p.PAID_OFF_DATE IS NULL AND p.TYPE != 30
                                   THEN p.AMOUNT ELSE 0 END), 2) AS unpaid_installments_sum,
                    ROUND(SUM(CASE WHEN p.IS_ACTIVE = TRUE AND p.TYPE = 30
                                   THEN p.AMOUNT ELSE 0 END), 2) AS dp_amount
                FROM BRONZE.PURCHASE.PAYMENTS p
                JOIN plan_info pi ON p.PAYMENTPLAN_ID = pi.plan_id
            )
            SELECT
                pi.TOTAL_OF_PAYMENTS                                                AS plan_total,
                mm.collected                                                        AS money_collected,
                mm.returned                                                         AS money_returned,
                mm.net                                                              AS net_collected,
                ab.dp_amount                                                        AS down_payment,
                ab.paid_installments_sum                                            AS paid_installments,
                ab.unpaid_installments_sum                                          AS unpaid_installments,
                ROUND(ab.paid_installments_sum + ab.unpaid_installments_sum, 2)     AS schedule_total,
                ROUND(pi.TOTAL_OF_PAYMENTS - ab.paid_installments_sum - ab.unpaid_installments_sum, 2) AS schedule_gap,
                CASE
                    WHEN ABS(pi.TOTAL_OF_PAYMENTS - ab.paid_installments_sum - ab.unpaid_installments_sum) < 0.05
                        THEN 'PASS'
                    ELSE 'FAIL: schedule gap of $' || ROUND(pi.TOTAL_OF_PAYMENTS - ab.paid_installments_sum - ab.unpaid_installments_sum, 2)
                END                                                                 AS check_a_schedule,
                ROUND(mm.net - ab.dp_amount - ab.paid_installments_sum, 2)          AS money_gap,
                CASE
                    WHEN ABS(mm.net - ab.dp_amount - ab.paid_installments_sum) < 0.05
                        THEN 'PASS'
                    ELSE 'FAIL: money gap of $' || ROUND(mm.net - ab.dp_amount - ab.paid_installments_sum, 2)
                END                                                                 AS check_b_money
            FROM plan_info pi, money_moved mm, active_balance ab
        """

        // -- Q-N1: All Emails Sent ------------------------------------------------

        private const val SQL_NOTIFICATIONS_SENT = """
            SELECT
                pe.ID                    AS email_id,
                pe.PURCHASE_ID           AS purchase_id,
                pe.CHANGE_STATUS_TIME    AS sent_at,
                pe.TYPE                  AS email_type,
                pe.STATUS                AS send_status
            FROM BRONZE.PURCHASE.PURCHASES_EMAILS pe
            WHERE pe.PURCHASE_ID = ?
            ORDER BY pe.CHANGE_STATUS_TIME
        """

        // -- GOLD: Rich email data with MongoDB notification IDs ------------------

        private const val SQL_GOLD_EMAILS = """
            SELECT
                se.NOTIFICATION_ID,
                se.RECIPIENT_ADDRESS,
                se.TEMPLATE_NAME,
                se.SUBJECT,
                se.SENT_TIMESTAMP_UTC
            FROM GOLD.COMMUNICATION_HUB.SUNBIT_EMAILS se
            WHERE se.PURCHASE_ID = ?
            ORDER BY se.SENT_TIMESTAMP_UTC
        """

        // -- Q-N2: Missing Notifications ------------------------------------------

        private const val SQL_NOTIFICATIONS_MISSING = """
            WITH successful_charges AS (
                SELECT
                    pa.ID                    AS attempt_id,
                    pa.PAYMENT_ID,
                    pa.DATE_TIME             AS charge_time,
                    DATE(pa.DATE_TIME)       AS charge_date,
                    pa.SOURCE
                FROM BRONZE.PURCHASE.PAYMENT_ATTEMPTS pa
                JOIN BRONZE.PURCHASE.PAYMENTS p ON pa.PAYMENT_ID = p.ID
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON p.PAYMENTPLAN_ID = pp.ID
                JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
                  AND pa.TYPE = 1
                  AND pa.STATUS = 0
            ),
            payment_emails AS (
                SELECT
                    pe.ID                    AS email_id,
                    pe.TYPE                  AS email_type,
                    pe.CHANGE_STATUS_TIME    AS email_time,
                    DATE(pe.CHANGE_STATUS_TIME) AS email_date
                FROM BRONZE.PURCHASE.PURCHASES_EMAILS pe
                WHERE pe.PURCHASE_ID = ?
                  AND pe.TYPE IN (5, 7, 9, 10)
            )
            SELECT
                sc.PAYMENT_ID            AS payment_id,
                sc.charge_time,
                CASE
                    WHEN pe.email_id IS NOT NULL THEN 'OK'
                    ELSE 'MISSING: charge succeeded but no payment email sent'
                END                      AS notification_check
            FROM successful_charges sc
            LEFT JOIN payment_emails pe ON pe.email_date = sc.charge_date
            WHERE pe.email_id IS NULL
            ORDER BY sc.charge_time
        """

        // -- Q-N3: Erroneous Notifications ----------------------------------------

        private const val SQL_NOTIFICATIONS_ERRONEOUS = """
            WITH daily_charge_success AS (
                SELECT
                    DATE(pa.DATE_TIME) AS charge_date,
                    MAX(CASE WHEN pa.STATUS = 0 THEN 1 ELSE 0 END) AS had_success
                FROM BRONZE.PURCHASE.PAYMENT_ATTEMPTS pa
                JOIN BRONZE.PURCHASE.PAYMENTS p ON pa.PAYMENT_ID = p.ID
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON p.PAYMENTPLAN_ID = pp.ID
                JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
                  AND pa.TYPE = 1
                GROUP BY DATE(pa.DATE_TIME)
            )
            SELECT
                pe.ID                    AS email_id,
                pe.CHANGE_STATUS_TIME    AS sent_at,
                pe.TYPE                  AS email_type,
                CASE pe.TYPE
                    WHEN 12 THEN 'ChargeFailed'
                    WHEN 2  THEN 'LateNotice'
                    WHEN 3  THEN 'DefaultNotice'
                    ELSE 'TYPE_' || pe.TYPE
                END                      AS email_type_name,
                dcs.had_success,
                CASE
                    WHEN pe.TYPE = 12 AND dcs.had_success = 1
                        THEN 'ERRONEOUS: ChargeFailed email sent but charge succeeded same day'
                    WHEN pe.TYPE IN (2, 3) AND pe.CHANGE_STATUS_TIME BETWEEN '2026-02-27' AND '2026-03-10'
                        THEN 'SUSPECT: Late/default notice during incident window -- may be infrastructure-caused'
                    ELSE 'OK'
                END                      AS error_check
            FROM BRONZE.PURCHASE.PURCHASES_EMAILS pe
            LEFT JOIN daily_charge_success dcs ON dcs.charge_date = DATE(pe.CHANGE_STATUS_TIME)
            WHERE pe.PURCHASE_ID = ?
              AND pe.TYPE IN (2, 3, 12)
              AND (
                  (pe.TYPE = 12 AND dcs.had_success = 1)
                  OR (pe.TYPE IN (2, 3) AND pe.CHANGE_STATUS_TIME BETWEEN '2026-02-27' AND '2026-03-10')
              )
            ORDER BY pe.CHANGE_STATUS_TIME
        """

        // -- Q-N4: Support Tickets ------------------------------------------------

        private const val SQL_SUPPORT_TICKETS = """
            SELECT
                td.ID                    AS ticket_detail_id,
                td.PURCHASE_ID           AS purchase_id,
                td.TICKET_ID,
                td.UPDATE_TIME,
                td.COMMUNICATION_TYPE,
                td.TOPIC,
                td.SUBTOPIC,
                td.ORIGIN,
                td.REPRESENTATIVE_NAME,
                td.NOTES
            FROM BRONZE.PURCHASE.TICKETS_DETAILS td
            WHERE td.PURCHASE_ID = ?
            ORDER BY td.UPDATE_TIME
        """

        // -- Q-A1: Audit Trail ----------------------------------------------------

        private const val SQL_AUDIT_TRAIL = """
            WITH plan_info AS (
                SELECT pp.ID AS plan_id
                FROM BRONZE.PURCHASE.PAYMENTPLANS pp
                JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
            )
            SELECT
                aud.ID                   AS payment_id,
                aud.REV,
                aud.AMOUNT,
                aud.IS_ACTIVE,
                aud.PAID_OFF_DATE,
                aud.CHANGE_INDICATOR,
                aud.ROWTIME
            FROM BRONZE.PURCHASE.PAYMENTS_AUD aud
            JOIN BRONZE.PURCHASE.PAYMENTS p ON aud.ID = p.ID
            JOIN plan_info pi ON p.PAYMENTPLAN_ID = pi.plan_id
            ORDER BY aud.ID, aud.REV
        """

        // -- Q-L3: Payment Actions ------------------------------------------------

        private const val SQL_PAYMENT_ACTIONS = """
            SELECT
                pa.ID                    AS action_id,
                pa.TIME_OF_ACTION,
                pa.TYPE                  AS action_type,
                pa.PURCHASE_ID           AS purchase_id
            FROM BRONZE.PURCHASE.PAYMENT_ACTIONS pa
            WHERE pa.PURCHASE_ID = ?
            ORDER BY pa.TIME_OF_ACTION
        """

        // -- Payment Attempts -----------------------------------------------------

        private const val SQL_PAYMENT_ATTEMPTS = """
            SELECT
                pa.ID,
                pa.PAYMENT_ID,
                pa.TRIGGERING_PAYMENT_ID,
                pa.SOURCE,
                pa.DATE_TIME,
                pa.TYPE,
                pa.STATUS,
                pa.FAIL_MESSAGE,
                pa.PROCESSOR_TX_ID,
                pa.HOLD_TRANSACTION_ID,
                pa.CHARGE_TRANSACTION_ID
            FROM BRONZE.PURCHASE.PAYMENT_ATTEMPTS pa
            JOIN BRONZE.PURCHASE.PAYMENTS pay ON pa.PAYMENT_ID = pay.ID
            JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON pay.PAYMENTPLAN_ID = pp.ID
            JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
            WHERE pp.PURCHASE_ID = ?
            ORDER BY pa.DATE_TIME
        """

        // -- Charge Service Attempts (from BRONZE.CHARGE.PAYMENT_ATTEMPTS) ---------

        private const val SQL_CHARGE_SERVICE_ATTEMPTS = """
            SELECT
                pa.ID,
                pa.DATE_TIME,
                pa.LAST_UPDATE_TIME_PST,
                pa.PAYMENT_PROFILE_ID,
                pa.PAYMENT_PROCESSOR,
                pa.PROCESSOR_TX_ID,
                pa.FAIL_MESSAGE,
                pa.EXTERNAL_ID,
                pa.AMOUNT,
                pa.STATUS,
                pa.TYPE,
                pa.ORIGIN
            FROM BRONZE.CHARGE.PAYMENT_ATTEMPTS pa
            WHERE pa.EXTERNAL_ID LIKE ?
            ORDER BY pa.DATE_TIME
        """

        // -- Loan Transactions (from BRONZE.PURCHASE.LOAN_TRANSACTIONS) -----------

        private const val SQL_LOAN_TRANSACTIONS = """
            SELECT
                lt.ID,
                lt.PAYMENT_ID,
                lt.CHARGE_TRANSACTION_ID,
                lt.AMOUNT,
                lt.EFFECTIVE_DATE,
                lt.INTEREST_AMOUNT,
                lt.INTEREST_CHARGE,
                lt.PRINCIPAL_BALANCE
            FROM BRONZE.PURCHASE.LOAN_TRANSACTIONS lt
            JOIN BRONZE.PURCHASE.PAYMENTS pay ON lt.PAYMENT_ID = pay.ID
            JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON pay.PAYMENTPLAN_ID = pp.ID
            JOIN BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp ON cpp.PAYMENT_PLAN_ID = pp.ID
            WHERE pp.PURCHASE_ID = ?
            ORDER BY lt.EFFECTIVE_DATE
        """

        // -- Q-X1: Cross-Schema Reconciliation ------------------------------------

        private const val SQL_CROSS_SCHEMA_RECONCILIATION = """
            WITH charge_schema_summary AS (
                SELECT
                    COUNT(CASE WHEN cpa.STATUS = 0 AND cpa.TYPE IN (1, 4) THEN 1 END)                                    AS processor_success_count,
                    ROUND(COALESCE(SUM(CASE WHEN cpa.STATUS = 0 AND cpa.TYPE IN (1, 4) THEN cpa.AMOUNT ELSE 0 END), 0), 2) AS processor_success_amount,
                    COUNT(CASE WHEN cpa.STATUS = 0 AND cpa.TYPE = 2 THEN 1 END)                                           AS processor_refund_count,
                    ROUND(COALESCE(SUM(CASE WHEN cpa.STATUS = 0 AND cpa.TYPE = 2 THEN cpa.AMOUNT ELSE 0 END), 0), 2)      AS processor_refund_amount,
                    COUNT(CASE WHEN cpa.STATUS = 1 THEN 1 END)                                                             AS processor_fail_count
                FROM BRONZE.CHARGE.PAYMENT_ATTEMPTS cpa
                WHERE cpa.EXTERNAL_ID LIKE ?
            ),
            purchase_schema_summary AS (
                SELECT
                    COUNT(CASE WHEN ct.TYPE IN (0, 1, 2) AND ct.AMOUNT > 0 THEN 1 END)                                    AS purchase_charge_count,
                    ROUND(COALESCE(SUM(CASE WHEN ct.TYPE IN (0, 1, 2) AND ct.AMOUNT > 0 THEN ct.AMOUNT ELSE 0 END), 0), 2) AS purchase_charge_amount,
                    COUNT(CASE WHEN ct.TYPE IN (5, 6, 7, 8, 13) THEN 1 END)                                                AS purchase_refund_count,
                    ROUND(COALESCE(SUM(CASE WHEN ct.TYPE IN (5, 6, 7, 8, 13) THEN ct.AMOUNT ELSE 0 END), 0), 2)           AS purchase_refund_amount
                FROM BRONZE.PURCHASE.CHARGE_TRANSACTIONS ct
                WHERE ct.PURCHASE_ID = ?
            )
            SELECT
                cs.processor_success_count,
                cs.processor_success_amount,
                ps.purchase_charge_count,
                ps.purchase_charge_amount,
                cs.processor_refund_count,
                cs.processor_refund_amount,
                ps.purchase_refund_count,
                ps.purchase_refund_amount,
                cs.processor_fail_count,
                CASE
                    WHEN cs.processor_success_count = ps.purchase_charge_count
                         AND ABS(cs.processor_success_amount - ps.purchase_charge_amount) < 0.05
                        THEN 'PASS: charge counts and amounts match'
                    WHEN cs.processor_success_count > ps.purchase_charge_count
                        THEN 'FAIL: processor has MORE successful charges than purchase-service recorded'
                    WHEN cs.processor_success_count < ps.purchase_charge_count
                        THEN 'FAIL: purchase-service has MORE charges than processor'
                    WHEN ABS(cs.processor_success_amount - ps.purchase_charge_amount) >= 0.05
                        THEN 'FAIL: charge amounts differ by $' || ROUND(cs.processor_success_amount - ps.purchase_charge_amount, 2)
                    ELSE 'CHECK: counts match but verify details'
                END AS reconciliation_verdict
            FROM charge_schema_summary cs, purchase_schema_summary ps
        """
    }
}

/**
 * Enriched result from the plan query that carries purchase-level status fields
 * alongside the PlanInfo domain object.
 */
data class PlanQueryResult(
    val planInfo: PlanInfo,
    val purchaseStatus: Int,
    val cppStatus: CppStatus,
)
