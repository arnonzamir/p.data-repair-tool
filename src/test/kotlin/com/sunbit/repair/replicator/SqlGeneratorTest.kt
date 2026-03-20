package com.sunbit.repair.replicator

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SqlGeneratorTest {

    private val generator = SqlGenerator()

    @Test
    fun `generateInserts produces valid SQL with offset IDs`() {
        val data = mapOf(
            "purchases" to listOf(
                mapOf<String, Any?>(
                    "ID" to 12345L,
                    "CUSTOMER_RETAILER_ID" to 100L,
                    "TOTAL_AMOUNT" to BigDecimal("1500.00"),
                    "STATUS" to 4,
                    "EXTERNAL_ID" to "ext-123",
                )
            ),
        )

        val sql = generator.generateInserts(data, 100_000_000L)

        assertContains(sql, "INSERT INTO")
        assertContains(sql, "`purchases`")
        // ID should be offset: 12345 + 100000000 = 100012345
        assertContains(sql, "100012345")
        // TOTAL_AMOUNT is not an ID, should not be offset
        assertContains(sql, "1500.00")
        assertContains(sql, "SET FOREIGN_KEY_CHECKS = 0")
    }

    @Test
    fun `generateInserts handles NULL values`() {
        val data = mapOf(
            "payments" to listOf(
                mapOf<String, Any?>(
                    "ID" to 1L,
                    "PAYMENTPLAN_ID" to 10L,
                    "AMOUNT" to BigDecimal("150.00"),
                    "SPLIT_FROM" to null,
                )
            ),
        )

        val sql = generator.generateInserts(data, 100_000_000L)
        assertContains(sql, "NULL")
    }

    @Test
    fun `generateInserts escapes strings`() {
        val data = mapOf(
            "purchase_properties" to listOf(
                mapOf<String, Any?>(
                    "PURCHASE_ID" to 1L,
                    "PROPERTY_NAME" to "test's property",
                    "VALUE" to "value with 'quotes'",
                )
            ),
        )

        val sql = generator.generateInserts(data, 100_000_000L)
        assertContains(sql, "\\'")
    }

    @Test
    fun `generateRollback produces DELETE statements`() {
        val data = mapOf(
            "purchases" to listOf(
                mapOf<String, Any?>("ID" to 12345L)
            ),
            "payments" to listOf(
                mapOf<String, Any?>("ID" to 1L),
                mapOf<String, Any?>("ID" to 2L),
            ),
        )

        val sql = generator.generateRollback(data, 100_000_000L)
        assertContains(sql, "DELETE FROM")
        // payments should come before purchases in rollback (reverse FK order)
        val paymentsPos = sql.indexOf("`payments`")
        val purchasesPos = sql.indexOf("`purchases`")
        assertTrue(paymentsPos < purchasesPos, "payments DELETE should come before purchases DELETE")
    }

    @Test
    fun `generateInserts handles boolean values`() {
        val data = mapOf(
            "payment_profiles" to listOf(
                mapOf<String, Any?>(
                    "ID" to 1L,
                    "IS_DEFAULT" to true,
                    "IS_DELETED" to false,
                )
            ),
        )

        val sql = generator.generateInserts(data, 100_000_000L)
        assertContains(sql, "1")  // true -> 1
        assertContains(sql, "0")  // false -> 0
    }

    @Test
    fun `generateInserts handles anonymized profile IDs gracefully`() {
        val data = mapOf(
            "payment_profiles" to listOf(
                mapOf<String, Any?>(
                    "ID" to 42L,
                    "PAYMENT_PROFILE_ID" to "fake_pp_12345_42",
                )
            ),
        )

        // Should not throw -- non-numeric ID in PAYMENT_PROFILE_ID
        // should fall through to string escaping
        val sql = generator.generateInserts(data, 100_000_000L)
        assertContains(sql, "fake_pp_12345_42")
    }
}
