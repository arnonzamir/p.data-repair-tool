package com.sunbit.repair.replicator

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SnowflakeExtractor(
    @Qualifier("snowflakeJdbcTemplate") private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(SnowflakeExtractor::class.java)

    companion object {
        val EXTRACTION_QUERIES = mapOf(
            "purchases" to """
                SELECT p.ID, p.CUSTOMER_RETAILER_ID, p.TIME_ZONE, p.RETAILER_TIME_ZONE,
                       p.CREATED_AT, p.DATE_TIME, p.PRODUCT_AMOUNT_BEFORE_TAX, p.SERVICE_AMOUNT,
                       p.TOTAL_AMOUNT, p.TOTAL_AMOUNT_BEFORE_TAX, p.EXTERNAL_ID,
                       p.REPRESENTATIVE_ID, p.MAX_CREDIT_AMOUNT, p.STATUS, p.CHANGE_STATUS_TIME,
                       p.CANCELED_PURCHASE_ID, p.COUNTER_OFFER_ID, p.CHARGED_BY,
                       p.MERCHANT_FEE, p.ORIGIN, p.ORIGINATOR, p.SUNBIT_PARTICIPATION,
                       p.FORBEARANCE_ID, p.INPUT_METHOD, p.LAST_UPDATE_TIME_UTC
                FROM BRONZE.PURCHASE.PURCHASES p WHERE p.ID = ?
            """,
            "paymentplans" to """
                SELECT pp.ID, pp.PURCHASE_ID, pp.NOMINAL_APR, pp.NUM_OF_PAYMENTS,
                       pp.DOWN_PAYMENT_PERCENTAGE, pp.FINANCIAL_CHARGE, pp.AMOUNT_FINANCED,
                       pp.TOTAL_OF_PAYMENTS, pp.TOTAL_SALES_PRICE,
                       pp.PAYMENT_PROFILE_ID, pp.PAYMENTS_INTERVAL,
                       pp.DAILY_APR, pp.MONTHLY_APR, pp.EFFECTIVE_APR,
                       pp.ZERO_APR_ONE_MONTH_EXPIRATION, pp.IS_DEFAULT
                FROM BRONZE.PURCHASE.PAYMENTPLANS pp WHERE pp.PURCHASE_ID = ?
            """,
            "chosen_paymentplans" to """
                SELECT cpp.ID, cpp.PAYMENT_PLAN_ID, cpp.STATUS, cpp.SPECIAL_STATUS,
                       cpp.DATE_CHANGE_STATE, cpp.CHANGE_STATUS_DATE
                FROM BRONZE.PURCHASE.CHOSEN_PAYMENTPLANS cpp
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON cpp.PAYMENT_PLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
            """,
            "customers_retailers" to """
                SELECT cr.ID, cr.CUSTOMER_ID, cr.RETAILER_ID, cr.LAST_PURCHASE_ID
                FROM BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr
                JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                WHERE p.ID = ?
            """,
            "customers" to """
                SELECT c.ID, c.USER_ID, c.CREATION_TIME, c.IS_TEST
                FROM BRONZE.CUSTOMER.CUSTOMERS c
                JOIN BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr ON c.ID = cr.CUSTOMER_ID
                JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                WHERE p.ID = ?
            """,
            "customer_details" to """
                SELECT cd.ID, cd.FIRST_NAME, cd.LAST_NAME, cd.DATE_OF_BIRTH,
                       cd.PHONE_NUMBER AS PHONE, cd.SSN, cd.SSN_ORIGIN
                FROM BRONZE.CUSTOMER.CUSTOMER_DETAILS cd
                JOIN BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr ON cd.CUSTOMER_ID = cr.CUSTOMER_ID
                JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                WHERE p.ID = ?
            """,
            "customer_profiles" to """
                SELECT DISTINCT cp.ID, cp.CUSTOMER_ID, cp.CREATION_DATE, cp.CUSTOMER_PROFILE_ID
                FROM BRONZE.PURCHASE.CUSTOMER_PROFILES cp
                JOIN BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr ON cp.CUSTOMER_ID = cr.CUSTOMER_ID
                JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                WHERE p.ID = ?
            """,
            "payment_profiles" to """
                SELECT DISTINCT pp_prof.ID, pp_prof.PAYMENT_PROFILE_ID, pp_prof.CUSTOMER_PROFILE_ID,
                       pp_prof.IS_DEFAULT, pp_prof.IS_DELETED, pp_prof.IS_BLOCKED,
                       pp_prof.UPDATE_TIME, pp_prof.CREATION_DATE, pp_prof.PAYMENT_METHOD_ID
                FROM BRONZE.PURCHASE.PAYMENT_PROFILES pp_prof
                JOIN BRONZE.PURCHASE.PAYMENTS pay ON pay.PAYMENT_PROFILE_ID = pp_prof.ID
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON pay.PAYMENTPLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
            """,
            "card_payment_methods" to """
                SELECT DISTINCT cpm.ID, cpm.PAYMENT_PROFILE_ID,
                       NULL as CARD_LAST4DIGITS, cpm.CARD_EXPIRATION_MONTH, cpm.CARD_EXPIRATION_YEAR,
                       cpm.CARD_NUMBER, cpm.CARD_BIN_NUMBER,
                       cpm.CARD_HOLDER_FIRST_NAME, cpm.CARD_HOLDER_MIDDLE_NAME, cpm.CARD_HOLDER_LAST_NAME,
                       cpm.PAYMENT_PROCESSOR, cpm.DTYPE, cpm.CREATION_TIME
                FROM BRONZE.PURCHASE.CARD_PAYMENT_METHODS cpm
                JOIN BRONZE.PURCHASE.PAYMENT_PROFILES pp_prof ON cpm.PAYMENT_PROFILE_ID = pp_prof.ID
                JOIN BRONZE.PURCHASE.PAYMENTS pay ON pay.PAYMENT_PROFILE_ID = pp_prof.ID
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON pay.PAYMENTPLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ?
            """,
            "payment_actions" to """
                SELECT pa.ID, pa.TYPE, pa.PURCHASE_ID, pa.TIME_OF_ACTION
                FROM BRONZE.PURCHASE.PAYMENT_ACTIONS pa
                WHERE pa.PURCHASE_ID = ? ORDER BY pa.TIME_OF_ACTION
            """,
            "payments" to """
                SELECT p.ID, p.PAYMENTPLAN_ID, p.AMOUNT, p.INTEREST_AMOUNT, p.INTEREST_CHARGE,
                       p.PRINCIPAL_BALANCE, p.DUE_DATE, p.EFFECTIVE_DATE, p.PAID_OFF_DATE,
                       p.REFUND_DATE, p.PAYMENT_PROFILE_ID, p.LENDER_ID,
                       p.TYPE, p.CHANGE_INDICATOR, p.IS_ACTIVE, p.PAYMENT_ACTION_ID,
                       p.CHARGE_BACK, p.CHARGE_BACK_ENHANCEMENT, p.DISPUTE,
                       p.SPLIT_FROM, p.MANUAL_UNTIL, p.CREATION_DATE,
                       p.ORIGINAL_PAYMENT_ID, p.DIRECT_PARENT_ID,
                       p.AMOUNT_PAID, p.INITIAL_AMOUNT
                FROM BRONZE.PURCHASE.PAYMENTS p
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON p.PAYMENTPLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ? ORDER BY p.CREATION_DATE, p.EFFECTIVE_DATE
            """,
            "payment_attempts" to """
                SELECT pa.ID, pa.PAYMENT_ID, pa.TRIGGERING_PAYMENT_ID, pa.SOURCE, pa.DATE_TIME,
                       pa.TYPE, pa.STATUS, pa.FAIL_MESSAGE,
                       pa.CUSTOMER_PAYMENT_PROFILE_ID, pa.PROCESSOR_TX_ID,
                       pa.HOLD_TRANSACTION_ID, pa.CHARGE_TRANSACTION_ID,
                       pa.DOWN_PAYMENT_TRANSACTION_ID, pa.DELETED, pa.AVS_RESULT_CODE
                FROM BRONZE.PURCHASE.PAYMENT_ATTEMPTS pa
                JOIN BRONZE.PURCHASE.PAYMENTS pay ON pa.PAYMENT_ID = pay.ID
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON pay.PAYMENTPLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ? ORDER BY pa.DATE_TIME
            """,
            "charge_transactions" to """
                SELECT ct.ID, ct.PURCHASE_ID, ct.TYPE, ct.AMOUNT, ct.CHARGE_TIME,
                       ct.CHARGEBACK, ct.CHARGEBACK_ENHANCEMENT, ct.PARENT_ID,
                       ct.PAYMENT_PROFILE_ID, ct.MANUAL_ADJUSTMENT
                FROM BRONZE.PURCHASE.CHARGE_TRANSACTIONS ct
                WHERE ct.PURCHASE_ID = ? ORDER BY ct.CHARGE_TIME
            """,
            "loan_transactions" to """
                SELECT lt.ID, lt.PAYMENT_ID, lt.CHARGE_TRANSACTION_ID, lt.AMOUNT,
                       lt.EFFECTIVE_DATE, lt.INTEREST_AMOUNT, lt.INTEREST_CHARGE,
                       lt.PRINCIPAL_BALANCE
                FROM BRONZE.PURCHASE.LOAN_TRANSACTIONS lt
                JOIN BRONZE.PURCHASE.PAYMENTS pay ON lt.PAYMENT_ID = pay.ID
                JOIN BRONZE.PURCHASE.PAYMENTPLANS pp ON pay.PAYMENTPLAN_ID = pp.ID
                WHERE pp.PURCHASE_ID = ? ORDER BY lt.EFFECTIVE_DATE
            """,
            "items" to """
                SELECT i.ID, i.PURCHASE_ID, i.SERIAL_NUMBER, i.AMOUNT, i.TAX
                FROM BRONZE.PURCHASE.ITEMS i WHERE i.PURCHASE_ID = ?
            """,
            "purchases_emails" to """
                SELECT pe.ID, pe.PURCHASE_ID, pe.STATUS, pe.TYPE,
                       pe.CHANGE_STATUS_TIME, pe.PURCHASE_STATUS_ON_SEND
                FROM BRONZE.PURCHASE.PURCHASES_EMAILS pe
                WHERE pe.PURCHASE_ID = ? ORDER BY pe.CHANGE_STATUS_TIME
            """,
            "purchase_properties" to """
                SELECT pp.PURCHASE_ID, pp.PROPERTY_NAME, pp.VALUE
                FROM BRONZE.PURCHASE.PURCHASE_PROPERTIES pp WHERE pp.PURCHASE_ID = ?
            """,
        )

        /** Tables in FK-safe insertion order (purchase schema). */
        val TABLE_ORDER = listOf(
            "customers", "customer_details", "customers_retailers", "customer_profiles",
            "purchases", "paymentplans", "chosen_paymentplans", "payment_profiles",
            "card_payment_methods", "payment_actions", "payments", "payment_attempts",
            "charge_transactions", "loan_transactions", "items", "purchases_emails",
            "purchase_properties",
        )

        // =================================================================
        // Charge schema queries -- keyed as "charge_<table>"
        // =================================================================

        val CHARGE_EXTRACTION_QUERIES = mapOf(
            "charge_payment_profiles" to """
                SELECT pp.ID, pp.CUSTOMER_ID, pp.IS_DEFAULT, pp.IS_DISABLED, pp.IS_BLOCKED,
                       pp.CREATION_TIME, pp.LAST_UPDATE_TIME, pp.PAYMENT_METHOD
                FROM BRONZE.CHARGE.PAYMENT_PROFILES pp
                WHERE pp.CUSTOMER_ID IN (
                    SELECT cr.CUSTOMER_ID FROM BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr
                    JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                    WHERE p.ID = ?
                )
            """,
            "charge_debit_payment_methods" to """
                SELECT dm.ID, dm.PAYMENT_PROFILE_ID, dm.CARD_NUMBER, dm.BIN_NUMBER,
                       dm.LAST_4_DIGITS, dm.EXPIRATION_MONTH, dm.EXPIRATION_YEAR,
                       dm.CARD_HOLDER_FIRST_NAME, dm.CARD_HOLDER_LAST_NAME,
                       dm.PAYMENT_PROCESSOR, dm.PROCESSOR_PROFILE_ID,
                       dm.CREATION_TIME, dm.LAST_UPDATE_TIME
                FROM BRONZE.CHARGE.DEBIT_PAYMENT_METHODS dm
                WHERE dm.PAYMENT_PROFILE_ID IN (
                    SELECT pp.ID FROM BRONZE.CHARGE.PAYMENT_PROFILES pp
                    WHERE pp.CUSTOMER_ID IN (
                        SELECT cr.CUSTOMER_ID FROM BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr
                        JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                        WHERE p.ID = ?
                    )
                )
            """,
            "charge_ach_payment_methods" to """
                SELECT am.ID, am.PAYMENT_PROFILE_ID, am.ACCOUNT_NUMBER, am.ROUTING_NUMBER,
                       am.TYPE, am.ACCOUNT_NUMBER_LAST_4_DIGITS,
                       am.PAYMENT_PROCESSOR, am.PROCESSOR_PROFILE_ID,
                       am.CREATION_TIME, am.LAST_UPDATE_TIME
                FROM BRONZE.CHARGE.ACH_PAYMENT_METHODS am
                WHERE am.PAYMENT_PROFILE_ID IN (
                    SELECT pp.ID FROM BRONZE.CHARGE.PAYMENT_PROFILES pp
                    WHERE pp.CUSTOMER_ID IN (
                        SELECT cr.CUSTOMER_ID FROM BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr
                        JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                        WHERE p.ID = ?
                    )
                )
            """,
            "charge_rcc_payment_methods" to """
                SELECT rm.ID, rm.PAYMENT_PROFILE_ID, rm.ACCOUNT_NUMBER, rm.ROUTING_NUMBER,
                       rm.ACCOUNT_NUMBER_LAST_4_DIGITS, rm.CREATION_TIME
                FROM BRONZE.CHARGE.RCC_PAYMENT_METHODS rm
                WHERE rm.PAYMENT_PROFILE_ID IN (
                    SELECT pp.ID FROM BRONZE.CHARGE.PAYMENT_PROFILES pp
                    WHERE pp.CUSTOMER_ID IN (
                        SELECT cr.CUSTOMER_ID FROM BRONZE.PURCHASE.CUSTOMERS_RETAILERS cr
                        JOIN BRONZE.PURCHASE.PURCHASES p ON p.CUSTOMER_RETAILER_ID = cr.ID
                        WHERE p.ID = ?
                    )
                )
            """,
            "charge_payment_attempts" to """
                SELECT pa.ID, pa.DATE_TIME, pa.LAST_UPDATE_TIME_PST,
                       pa.PAYMENT_PROFILE_ID, pa.PAYMENT_PROCESSOR,
                       pa.PROCESSOR_TX_ID, pa.FAIL_MESSAGE,
                       pa.EXTERNAL_ID, pa.AMOUNT, pa.STATUS, pa.TYPE, pa.ORIGIN
                FROM BRONZE.CHARGE.PAYMENT_ATTEMPTS pa
                WHERE pa.EXTERNAL_ID LIKE ?
                ORDER BY pa.DATE_TIME
            """,
            "charge_payment_attempt_statuses" to """
                SELECT pas.ID, pas.PAYMENT_ATTEMPT_ID, pas.CREATION_TIME,
                       pas.LAST_UPDATE_TIME_PST, pas.CHARGE_STATUS,
                       pas.CHARGE_STATUS_REASON, pas.SUMMARY
                FROM BRONZE.CHARGE.PAYMENT_ATTEMPT_STATUSES pas
                WHERE pas.PAYMENT_ATTEMPT_ID IN (
                    SELECT pa.ID FROM BRONZE.CHARGE.PAYMENT_ATTEMPTS pa
                    WHERE pa.EXTERNAL_ID LIKE ?
                )
            """,
        )

        /** Charge schema tables in FK-safe order. */
        val CHARGE_TABLE_ORDER = listOf(
            "charge_payment_profiles",
            "charge_debit_payment_methods",
            "charge_ach_payment_methods",
            "charge_rcc_payment_methods",
            "charge_payment_attempts",
            "charge_payment_attempt_statuses",
        )

        /** Map from extraction key to actual MySQL table name in the charge schema. */
        val CHARGE_TABLE_MAP = mapOf(
            "charge_payment_profiles" to "payment_profiles",
            "charge_debit_payment_methods" to "debit_payment_methods",
            "charge_ach_payment_methods" to "ach_payment_methods",
            "charge_rcc_payment_methods" to "rcc_payment_methods",
            "charge_payment_attempts" to "payment_attempts",
            "charge_payment_attempt_statuses" to "payment_attempt_statuses",
        )
    }

    fun extract(purchaseId: Long): Map<String, List<Map<String, Any?>>> {
        log.info("[SnowflakeExtractor][extract] purchaseId={}", purchaseId)
        val result = mutableMapOf<String, List<Map<String, Any?>>>()

        for ((table, query) in EXTRACTION_QUERIES) {
            log.debug("[SnowflakeExtractor][extract] querying purchase table={}", table)
            val rows = jdbc.queryForList(query.trimIndent(), purchaseId)
            result[table] = rows
            log.info("[SnowflakeExtractor][extract] table={} rows={}", table, rows.size)
        }

        // Extract charge schema data
        val externalIdPattern = "${purchaseId}%"
        for ((table, query) in CHARGE_EXTRACTION_QUERIES) {
            log.debug("[SnowflakeExtractor][extract] querying charge table={}", table)
            // charge_payment_attempts and charge_payment_attempt_statuses use LIKE pattern
            // others use purchaseId for the customer join
            val param: Any = if (table.contains("payment_attempts")) externalIdPattern else purchaseId
            val rows = jdbc.queryForList(query.trimIndent(), param)
            result[table] = rows
            log.info("[SnowflakeExtractor][extract] table={} rows={}", table, rows.size)
        }

        return result
    }
}
