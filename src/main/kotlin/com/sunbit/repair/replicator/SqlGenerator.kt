package com.sunbit.repair.replicator

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.Temporal

@Service
class SqlGenerator {
    private val log = LoggerFactory.getLogger(SqlGenerator::class.java)

    companion object {
        private val ID_COLUMNS = setOf(
            "ID", "PURCHASE_ID", "PAYMENT_PLAN_ID", "PAYMENTPLAN_ID",
            "PAYMENT_ID", "TRIGGERING_PAYMENT_ID", "CHARGE_TRANSACTION_ID", "HOLD_TRANSACTION_ID",
            "DOWN_PAYMENT_TRANSACTION_ID", "PARENT_ID", "SPLIT_FROM", "ORIGINAL_PAYMENT_ID",
            "DIRECT_PARENT_ID", "PAYMENT_ACTION_ID", "LAST_PURCHASE_ID",
            "CANCELED_PURCHASE_ID", "COUNTER_OFFER_ID", "FORBEARANCE_ID",
            "CUSTOMER_ID", "CUSTOMER_RETAILER_ID", "CUSTOMER_PROFILE_ID",
        )

        private val EXTERNAL_ID_COLUMNS = setOf(
            "RETAILER_ID", "REPRESENTATIVE_ID", "LENDER_ID",
            "PAYMENT_METHOD_ID",
        )

        private val PROFILE_FK_COLUMNS = setOf(
            "PAYMENT_PROFILE_ID", "CUSTOMER_PAYMENT_PROFILE_ID",
        )

        /** Default Snowflake-to-MySQL table name mapping. */
        private val MYSQL_TABLE_MAP = mapOf(
            "purchases" to "purchases",
            "paymentplans" to "paymentplans",
            "chosen_paymentplans" to "chosen_paymentplans",
            "customers_retailers" to "customers_retailers",
            "customers" to "customers",
            "customer_details" to "customers_details",
            "customer_profiles" to "customer_profiles",
            "payment_profiles" to "payment_profiles",
            "card_payment_methods" to "card_payment_methods",
            "payment_actions" to "payment_actions",
            "payments" to "payments",
            "payment_attempts" to "payment_attempts",
            "charge_transactions" to "charge_transactions",
            "loan_transactions" to "loan_transactions",
            "items" to "items",
            "purchases_emails" to "purchases_emails",
            "purchase_properties" to "purchase_properties",
        )

        /** Local MySQL uses _deprecated tables for customer data. */
        val LOCAL_TABLE_OVERRIDES = mapOf(
            "customers" to "customers_deprecated",
            "customer_details" to "customers_details_deprecated",
        )

        /** Shared entity tables that use INSERT IGNORE to avoid duplicates when the same
         *  customer/profile appears across multiple replicated purchases. */
        private val IDEMPOTENT_TABLES = setOf(
            "customers", "customer_details", "customers_retailers",
            "customer_profiles", "payment_profiles", "card_payment_methods",
        )

        /** Charge schema tables that use INSERT IGNORE for the same reason. */
        private val CHARGE_IDEMPOTENT_TABLES = setOf(
            "charge_payment_profiles", "charge_debit_payment_methods",
            "charge_ach_payment_methods", "charge_rcc_payment_methods",
        )

        /** Charge schema ID columns that need offset. */
        private val CHARGE_ID_COLUMNS = setOf(
            "ID", "PAYMENT_PROFILE_ID", "PAYMENT_ATTEMPT_ID", "CUSTOMER_ID",
        )

        /** Charge schema columns that should NOT be offset (string-typed external IDs). */
        private val CHARGE_EXTERNAL_COLUMNS = setOf(
            "PROCESSOR_PROFILE_ID", "PROCESSOR_TX_ID", "EXTERNAL_ID",
            "ANET_CUSTOMER_PROFILE_ID", "CONNECTED_ACCOUNT_ID",
        )

        fun resolveTableName(snowflakeTable: String, isLocal: Boolean): String {
            if (isLocal) {
                LOCAL_TABLE_OVERRIDES[snowflakeTable]?.let { return it }
            }
            return MYSQL_TABLE_MAP[snowflakeTable] ?: snowflakeTable
        }
    }

    /**
     * Generate INSERT SQL. Tables in [skipTables] are commented out with a note.
     * Columns in [fixedValues] are written with the given value instead of offset-applied data.
     * fixedValues keys are uppercase column names, values are the literal SQL value to insert.
     */
    fun generateInserts(
        data: Map<String, List<Map<String, Any?>>>,
        idOffset: Long,
        skipTables: Set<String> = emptySet(),
        fixedValues: Map<String, Any> = emptyMap(),
        isLocal: Boolean = true,
    ): String {
        log.info("[SqlGenerator][generateInserts] tables={} offset={} skipTables={} fixedValues={}",
            data.size, idOffset, skipTables, fixedValues.keys)

        val sb = StringBuilder()
        sb.appendLine("-- Generated INSERT statements for purchase replication")
        sb.appendLine("-- ID offset: $idOffset")
        if (skipTables.isNotEmpty()) {
            sb.appendLine("-- Skipped tables (reusing existing records): ${skipTables.joinToString(", ")}")
        }
        if (fixedValues.isNotEmpty()) {
            sb.appendLine("-- Fixed values: ${fixedValues.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
        sb.appendLine("SET FOREIGN_KEY_CHECKS = 0;")
        sb.appendLine()

        for (table in SnowflakeExtractor.TABLE_ORDER) {
            val rows = data[table] ?: continue

            if (table in skipTables) {
                sb.appendLine("-- $table: SKIPPED (reusing existing record)")
                sb.appendLine()
                continue
            }

            if (rows.isEmpty()) continue

            val mysqlTable = resolveTableName(table, isLocal)
            sb.appendLine("-- $mysqlTable: ${rows.size} rows")

            for (row in rows) {
                val columns = row.keys.toList()
                val values = columns.map { col ->
                    val upperCol = col.uppercase()
                    // Apply fixed value override if present
                    if (upperCol in fixedValues) {
                        val fixed = fixedValues[upperCol]
                        if (fixed == null) "NULL" else fixed.toString()
                    } else {
                        formatValue(row[col], col, idOffset)
                    }
                }
                val insertCmd = if (table in IDEMPOTENT_TABLES) "INSERT IGNORE INTO" else "INSERT INTO"
                sb.appendLine("$insertCmd `$mysqlTable` (${columns.joinToString(", ") { "`$it`" }}) VALUES (${values.joinToString(", ")});")
            }
            sb.appendLine()
        }

        sb.appendLine("SET FOREIGN_KEY_CHECKS = 1;")
        return sb.toString()
    }

    fun generateRollback(
        data: Map<String, List<Map<String, Any?>>>,
        idOffset: Long,
        skipTables: Set<String> = emptySet(),
        isLocal: Boolean = true,
    ): String {
        log.info("[SqlGenerator][generateRollback] tables={} skipTables={}", data.size, skipTables)
        val sb = StringBuilder()
        sb.appendLine("-- Generated ROLLBACK (DELETE) statements")
        sb.appendLine("SET FOREIGN_KEY_CHECKS = 0;")
        sb.appendLine()

        for (table in SnowflakeExtractor.TABLE_ORDER.reversed()) {
            if (table in skipTables) continue
            val rows = data[table] ?: continue
            if (rows.isEmpty()) continue

            val mysqlTable = resolveTableName(table, isLocal)

            val deleteCmd = if (table in IDEMPOTENT_TABLES) "DELETE IGNORE FROM" else "DELETE FROM"
            for (row in rows) {
                val id = row["ID"]
                if (id != null) {
                    val offsetId = applyOffset(id, idOffset)
                    sb.appendLine("$deleteCmd `$mysqlTable` WHERE `ID` = $offsetId;")
                } else if (table == "purchase_properties") {
                    val purchaseId = row["PURCHASE_ID"]
                    val propName = row["PROPERTY_NAME"]
                    if (purchaseId != null && propName != null) {
                        val offsetPurchaseId = applyOffset(purchaseId, idOffset)
                        sb.appendLine("DELETE FROM `$mysqlTable` WHERE `PURCHASE_ID` = $offsetPurchaseId AND `PROPERTY_NAME` = ${escapeString(propName.toString())};")
                    }
                }
            }
        }

        sb.appendLine()
        sb.appendLine("SET FOREIGN_KEY_CHECKS = 1;")
        return sb.toString()
    }

    private fun formatValue(value: Any?, column: String, idOffset: Long): String {
        if (value == null) return "NULL"

        val upperCol = column.uppercase()

        if (upperCol in ID_COLUMNS || upperCol in PROFILE_FK_COLUMNS) {
            return try {
                applyOffset(value, idOffset).toString()
            } catch (e: IllegalArgumentException) {
                escapeString(value.toString())
            }
        }

        if (upperCol in EXTERNAL_ID_COLUMNS) {
            return when (value) {
                is Number -> value.toString()
                else -> escapeString(value.toString())
            }
        }

        return when (value) {
            is Number -> value.toString()
            is Boolean -> if (value) "1" else "0"
            is BigDecimal -> value.toPlainString()
            is LocalDate -> "'$value'"
            is LocalDateTime -> "'$value'"
            is Temporal -> "'$value'"
            is java.sql.Timestamp -> "'${value.toLocalDateTime()}'"
            is java.sql.Date -> "'${value.toLocalDate()}'"
            is ByteArray -> "X'${value.joinToString("") { "%02x".format(it) }}'"
            else -> {
                val str = value.toString()
                // Handle ISO timestamps from Snowflake (e.g., "2026-03-12T15:30:24Z")
                // Convert to MySQL-compatible format: "2026-03-12 15:30:24"
                if (str.matches(Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}.*"))) {
                    val cleaned = str
                        .replace('T', ' ')
                        .replace(Regex("Z$"), "")
                        .replace(Regex("\\.\\d+Z?$"), "")
                        .replace(Regex("\\+\\d{2}:\\d{2}$"), "")
                    escapeString(cleaned)
                } else {
                    escapeString(str)
                }
            }
        }
    }

    private fun applyOffset(value: Any, offset: Long): Long {
        return when (value) {
            is Number -> value.toLong() + offset
            else -> value.toString().toLongOrNull()?.plus(offset)
                ?: throw IllegalArgumentException("Cannot apply offset to non-numeric ID: $value")
        }
    }

    private fun escapeString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u0000", "")
        return "'$escaped'"
    }

    // =================================================================
    // Charge schema SQL generation
    // =================================================================

    fun generateChargeInserts(
        data: Map<String, List<Map<String, Any?>>>,
        idOffset: Long,
    ): String {
        log.info("[SqlGenerator][generateChargeInserts] offset={}", idOffset)
        val sb = StringBuilder()
        sb.appendLine("-- Generated INSERT statements for charge schema replication")
        sb.appendLine("-- ID offset: $idOffset")
        sb.appendLine("SET FOREIGN_KEY_CHECKS = 0;")
        sb.appendLine()

        for (table in SnowflakeExtractor.CHARGE_TABLE_ORDER) {
            val rows = data[table] ?: continue
            if (rows.isEmpty()) continue

            val mysqlTable = SnowflakeExtractor.CHARGE_TABLE_MAP[table] ?: table
            sb.appendLine("-- $mysqlTable: ${rows.size} rows")

            for (row in rows) {
                val columns = row.keys.toList()
                val values = columns.map { col ->
                    formatChargeValue(row[col], col, idOffset)
                }
                val insertCmd = if (table in CHARGE_IDEMPOTENT_TABLES) "INSERT IGNORE INTO" else "INSERT INTO"
                sb.appendLine("$insertCmd `$mysqlTable` (${columns.joinToString(", ") { "`$it`" }}) VALUES (${values.joinToString(", ")});")
            }
            sb.appendLine()
        }

        sb.appendLine("SET FOREIGN_KEY_CHECKS = 1;")
        return sb.toString()
    }

    fun generateChargeRollback(
        data: Map<String, List<Map<String, Any?>>>,
        idOffset: Long,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("-- Generated ROLLBACK (DELETE) for charge schema")
        sb.appendLine("SET FOREIGN_KEY_CHECKS = 0;")
        sb.appendLine()

        for (table in SnowflakeExtractor.CHARGE_TABLE_ORDER.reversed()) {
            val rows = data[table] ?: continue
            if (rows.isEmpty()) continue

            val mysqlTable = SnowflakeExtractor.CHARGE_TABLE_MAP[table] ?: table

            val deleteCmd = if (table in CHARGE_IDEMPOTENT_TABLES) "DELETE IGNORE FROM" else "DELETE FROM"
            for (row in rows) {
                val id = row["ID"]
                if (id != null) {
                    val offsetId = applyOffset(id, idOffset)
                    sb.appendLine("$deleteCmd `$mysqlTable` WHERE `ID` = $offsetId;")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("SET FOREIGN_KEY_CHECKS = 1;")
        return sb.toString()
    }

    private fun formatChargeValue(value: Any?, column: String, idOffset: Long): String {
        if (value == null) return "NULL"

        val upperCol = column.uppercase()

        // Charge schema IDs to offset
        if (upperCol in CHARGE_ID_COLUMNS) {
            return try {
                applyOffset(value, idOffset).toString()
            } catch (e: IllegalArgumentException) {
                escapeString(value.toString())
            }
        }

        // External string IDs -- anonymize processor_profile_id, null out processor_tx_id
        if (upperCol == "PROCESSOR_PROFILE_ID") {
            return escapeString("fake_charge_${value.toString().takeLast(8)}")
        }
        if (upperCol == "PROCESSOR_TX_ID") {
            return "NULL"
        }

        // External ID on payment_attempts -- keep the offset purchaseId reference
        if (upperCol == "EXTERNAL_ID") {
            val str = value.toString()
            // Format: "purchaseId" or "purchaseId-paymentId"
            return try {
                val parts = str.split("-", limit = 2)
                val offsetPurchaseId = parts[0].toLong() + idOffset
                if (parts.size > 1) {
                    val offsetPaymentId = parts[1].toLong() + idOffset
                    escapeString("$offsetPurchaseId-$offsetPaymentId")
                } else {
                    escapeString(offsetPurchaseId.toString())
                }
            } catch (e: NumberFormatException) {
                escapeString(str)
            }
        }

        // Everything else -- same as purchase schema
        if (upperCol in CHARGE_EXTERNAL_COLUMNS) {
            return escapeString(value.toString())
        }

        return when (value) {
            is Number -> value.toString()
            is Boolean -> if (value) "1" else "0"
            is java.math.BigDecimal -> value.toPlainString()
            is java.time.LocalDate -> "'$value'"
            is java.time.LocalDateTime -> "'$value'"
            is java.time.temporal.Temporal -> "'$value'"
            is java.sql.Timestamp -> "'${value.toLocalDateTime()}'"
            is java.sql.Date -> "'${value.toLocalDate()}'"
            else -> {
                val str = value.toString()
                if (str.matches(Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}.*"))) {
                    val cleaned = str.replace('T', ' ').replace(Regex("Z$"), "")
                        .replace(Regex("\\.\\d+Z?$"), "").replace(Regex("\\+\\d{2}:\\d{2}$"), "")
                    escapeString(cleaned)
                } else {
                    escapeString(str)
                }
            }
        }
    }
}
