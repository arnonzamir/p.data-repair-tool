package com.sunbit.repair.replicator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiiAnonymizerTest {

    private val anonymizer = PiiAnonymizer()

    @Test
    fun `identity is deterministic for same purchaseId`() {
        val id1 = anonymizer.buildAnonymizedIdentity(12345L, "CA")
        val id2 = anonymizer.buildAnonymizedIdentity(12345L, "CA")
        assertEquals(id1, id2)
    }

    @Test
    fun `identity differs for different purchaseIds`() {
        val id1 = anonymizer.buildAnonymizedIdentity(12345L, "CA")
        val id2 = anonymizer.buildAnonymizedIdentity(12346L, "CA")
        assertTrue(id1.firstName != id2.firstName)
        assertTrue(id1.ssn != id2.ssn)
    }

    @Test
    fun `state is preserved from original`() {
        val id = anonymizer.buildAnonymizedIdentity(12345L, "TX")
        assertEquals("TX", id.state)
    }

    @Test
    fun `null state stays null`() {
        val id = anonymizer.buildAnonymizedIdentity(12345L, null)
        assertNull(id.state)
    }

    @Test
    fun `identity fields have expected format`() {
        val id = anonymizer.buildAnonymizedIdentity(12345L, "CA")
        assertTrue(id.firstName.startsWith("TestP"))
        assertTrue(id.lastName.startsWith("UserP"))
        assertTrue(id.email.endsWith("@replicated.sunbit.invalid"))
        assertTrue(id.phone.startsWith("5550"))
        assertTrue(id.ssn.matches(Regex("\\d{3}-\\d{2}-\\d{4}")))
        assertEquals(4, id.cardLast4.length)
    }

    @Test
    fun `anonymize replaces PII in customer_details`() {
        val data = mapOf(
            "customer_details" to listOf(
                mapOf<String, Any?>(
                    "ID" to 1L,
                    "FIRST_NAME" to "John",
                    "LAST_NAME" to "Doe",
                    "PHONE" to "5551234567",
                    "SSN" to "123-45-6789",
                    "CITY" to "San Francisco",
                    "ZIPCODE" to "94101",
                )
            )
        )
        val identity = anonymizer.buildAnonymizedIdentity(12345L, "CA")
        val (anonymized, redactions) = anonymizer.anonymize(data, identity, 12345L)

        val row = anonymized["customer_details"]!!.first()
        assertEquals(identity.firstName, row["FIRST_NAME"])
        assertEquals(identity.lastName, row["LAST_NAME"])
        assertEquals(identity.phone, row["PHONE"])
        assertEquals(identity.ssn, row["SSN"])
        assertTrue(redactions.isNotEmpty())
        assertTrue(redactions.any { it.field == "first_name" })
    }

    @Test
    fun `anonymize replaces card payment method PII`() {
        val data = mapOf(
            "customer_details" to emptyList<Map<String, Any?>>(),
            "card_payment_methods" to listOf(
                mapOf<String, Any?>(
                    "ID" to 1L,
                    "CARD_HOLDER_FIRST_NAME" to "John",
                    "CARD_HOLDER_LAST_NAME" to "Doe",
                    "CARD_NUMBER" to "abcdef123456",
                    "CARD_BIN_NUMBER" to "411111",
                )
            )
        )
        val identity = anonymizer.buildAnonymizedIdentity(12345L, null)
        val (anonymized, _) = anonymizer.anonymize(data, identity, 12345L)

        val row = anonymized["card_payment_methods"]!!.first()
        assertEquals(identity.firstName, row["CARD_HOLDER_FIRST_NAME"])
        assertEquals("000000", row["CARD_BIN_NUMBER"])
    }

    @Test
    fun `anonymize replaces payment profile external IDs`() {
        val data = mapOf(
            "customer_details" to emptyList<Map<String, Any?>>(),
            "payment_profiles" to listOf(
                mapOf<String, Any?>(
                    "ID" to 42L,
                    "PAYMENT_PROFILE_ID" to "src_abc123",
                )
            )
        )
        val identity = anonymizer.buildAnonymizedIdentity(12345L, null)
        val (anonymized, redactions) = anonymizer.anonymize(data, identity, 12345L)

        val row = anonymized["payment_profiles"]!!.first()
        assertEquals("fake_pp_12345_42", row["PAYMENT_PROFILE_ID"])
        assertTrue(redactions.any { it.table == "payment_profiles" })
    }
}
