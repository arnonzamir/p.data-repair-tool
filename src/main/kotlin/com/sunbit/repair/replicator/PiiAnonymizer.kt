package com.sunbit.repair.replicator

import com.sunbit.repair.domain.AnonymizedIdentity
import com.sunbit.repair.domain.PiiRedaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDate

@Service
class PiiAnonymizer {
    private val log = LoggerFactory.getLogger(PiiAnonymizer::class.java)

    fun buildAnonymizedIdentity(purchaseId: Long, originalState: String?, namePrefix: String = "Test"): AnonymizedIdentity {
        val tag = "P$purchaseId"
        val seq = (purchaseId % 10000).toInt()
        val area = 900 + (purchaseId % 100).toInt()
        val group = 10 + (purchaseId % 90).toInt()
        val serial = 1000 + (purchaseId % 9000).toInt()

        return AnonymizedIdentity(
            firstName = "$namePrefix$tag",
            lastName = "User$tag",
            email = "${namePrefix.lowercase()}.user.$purchaseId@replicated.sunbit.invalid",
            phone = "5550${"%06d".format(seq)}",
            ssn = "$area-$group-$serial",
            dateOfBirth = null,
            drivingLicense = "FAKE-DL-$tag-0000",
            address = "${(purchaseId % 9999).toInt() + 1} Replicated Ln",
            city = "Testville",
            state = originalState,
            zipcode = "${90000 + (purchaseId % 10000).toInt()}",
            cardLast4 = "%04d".format(seq),
            cardNumberHash = md5("FAKE-CARD-$purchaseId"),
        )
    }

    fun anonymize(
        data: Map<String, List<Map<String, Any?>>>,
        identity: AnonymizedIdentity,
        purchaseId: Long,
    ): Pair<Map<String, List<Map<String, Any?>>>, List<PiiRedaction>> {
        log.info("[PiiAnonymizer][anonymize] purchaseId={}", purchaseId)
        val redactions = mutableListOf<PiiRedaction>()
        val result = data.toMutableMap()

        // Anonymize customer_details -- replace existing PII and ensure all required
        // columns exist (Snowflake extraction may not include all MySQL NOT NULL columns)
        result["customer_details"] = data["customer_details"]?.map { row ->
            val mutable = row.toMutableMap()
            setField(mutable, "FIRST_NAME", identity.firstName, "customer_details", redactions)
            setField(mutable, "LAST_NAME", identity.lastName, "customer_details", redactions)
            setField(mutable, "PHONE", identity.phone, "customer_details", redactions)
            setField(mutable, "SSN", identity.ssn, "customer_details", redactions)
            setField(mutable, "ADDRESS", identity.address, "customer_details", redactions)
            setField(mutable, "CITY", identity.city, "customer_details", redactions)
            setField(mutable, "STATE", identity.state ?: "CA", "customer_details", redactions)
            setField(mutable, "ZIPCODE", identity.zipcode, "customer_details", redactions)
            setField(mutable, "DRIVING_LICENSE_NUMBER", identity.drivingLicense, "customer_details", redactions)
            // Default for driving_license_state if not present
            if ("DRIVING_LICENSE_STATE" !in mutable) {
                mutable["DRIVING_LICENSE_STATE"] = identity.state ?: "CA"
            }

            // Date of birth: keep year, set to Jan 1
            val dob = mutable["DATE_OF_BIRTH"]
            if (dob != null) {
                val original = when (dob) {
                    is LocalDate -> dob
                    is java.sql.Date -> dob.toLocalDate()
                    is String -> LocalDate.parse(dob.toString().take(10))
                    else -> null
                }
                if (original != null) {
                    val anonymized = LocalDate.of(original.year, 1, 1)
                    redactions.add(PiiRedaction("customer_details", "date_of_birth", sha256(dob.toString()), anonymized.toString()))
                    mutable["DATE_OF_BIRTH"] = anonymized
                }
            }
            mutable
        } ?: emptyList()

        // Anonymize card_payment_methods
        result["card_payment_methods"] = data["card_payment_methods"]?.map { row ->
            val mutable = row.toMutableMap()
            anonymizeField(mutable, "CARD_HOLDER_FIRST_NAME", identity.firstName, "card_payment_methods", redactions)
            anonymizeField(mutable, "CARD_HOLDER_MIDDLE_NAME", null, "card_payment_methods", redactions)
            anonymizeField(mutable, "CARD_HOLDER_LAST_NAME", identity.lastName, "card_payment_methods", redactions)
            anonymizeField(mutable, "CARD_LAST4DIGITS", identity.cardLast4, "card_payment_methods", redactions)
            anonymizeField(mutable, "CARD_NUMBER", identity.cardNumberHash, "card_payment_methods", redactions)
            anonymizeField(mutable, "CARD_BIN_NUMBER", "000000", "card_payment_methods", redactions)
            mutable
        } ?: emptyList()

        // Anonymize payment_profiles (external processor IDs)
        result["payment_profiles"] = data["payment_profiles"]?.map { row ->
            val mutable = row.toMutableMap()
            val profileId = mutable["ID"]
            val orig = mutable["PAYMENT_PROFILE_ID"]
            if (orig != null) {
                val replacement = "fake_pp_${purchaseId}_$profileId"
                redactions.add(PiiRedaction("payment_profiles", "payment_profile_id", sha256(orig.toString()), replacement))
                mutable["PAYMENT_PROFILE_ID"] = replacement
            }
            mutable
        } ?: emptyList()

        // Anonymize customer_profiles (external processor profile IDs)
        result["customer_profiles"] = data["customer_profiles"]?.map { row ->
            val mutable = row.toMutableMap()
            val profileId = mutable["ID"]
            val orig = mutable["CUSTOMER_PROFILE_ID"]
            if (orig != null) {
                val replacement = "fake_cp_${purchaseId}_$profileId"
                redactions.add(PiiRedaction("customer_profiles", "customer_profile_id", sha256(orig.toString()), replacement))
                mutable["CUSTOMER_PROFILE_ID"] = replacement
            }
            mutable
        } ?: emptyList()

        // Anonymize charge schema: debit_payment_methods (card holder names, card numbers)
        result["charge_debit_payment_methods"] = data["charge_debit_payment_methods"]?.map { row ->
            val mutable = row.toMutableMap()
            anonymizeField(mutable, "CARD_HOLDER_FIRST_NAME", identity.firstName, "charge_debit_payment_methods", redactions)
            anonymizeField(mutable, "CARD_HOLDER_LAST_NAME", identity.lastName, "charge_debit_payment_methods", redactions)
            anonymizeField(mutable, "CARD_NUMBER", identity.cardNumberHash, "charge_debit_payment_methods", redactions)
            anonymizeField(mutable, "BIN_NUMBER", "000000", "charge_debit_payment_methods", redactions)
            anonymizeField(mutable, "LAST_4_DIGITS", identity.cardLast4, "charge_debit_payment_methods", redactions)
            mutable
        } ?: emptyList()

        // Anonymize charge schema: ach_payment_methods (account/routing numbers)
        result["charge_ach_payment_methods"] = data["charge_ach_payment_methods"]?.map { row ->
            val mutable = row.toMutableMap()
            anonymizeField(mutable, "ACCOUNT_NUMBER", "fake_acct_${purchaseId}", "charge_ach_payment_methods", redactions)
            anonymizeField(mutable, "ROUTING_NUMBER", "fake_rtn_${purchaseId}", "charge_ach_payment_methods", redactions)
            anonymizeField(mutable, "ACCOUNT_NUMBER_LAST_4_DIGITS", identity.cardLast4, "charge_ach_payment_methods", redactions)
            mutable
        } ?: emptyList()

        // Anonymize charge schema: rcc_payment_methods (account/routing numbers)
        result["charge_rcc_payment_methods"] = data["charge_rcc_payment_methods"]?.map { row ->
            val mutable = row.toMutableMap()
            anonymizeField(mutable, "ACCOUNT_NUMBER", "fake_rcc_acct_${purchaseId}", "charge_rcc_payment_methods", redactions)
            anonymizeField(mutable, "ROUTING_NUMBER", "fake_rcc_rtn_${purchaseId}", "charge_rcc_payment_methods", redactions)
            anonymizeField(mutable, "ACCOUNT_NUMBER_LAST_4_DIGITS", identity.cardLast4, "charge_rcc_payment_methods", redactions)
            mutable
        } ?: emptyList()

        log.info("[PiiAnonymizer][anonymize] purchaseId={} redactions={}", purchaseId, redactions.size)
        return result to redactions
    }

    /** Replace an existing field value (only if field is already in the row). */
    private fun anonymizeField(
        row: MutableMap<String, Any?>,
        field: String,
        replacement: String?,
        table: String,
        redactions: MutableList<PiiRedaction>,
    ) {
        val original = row[field]
        if (original != null) {
            redactions.add(PiiRedaction(table, field.lowercase(), sha256(original.toString()), replacement ?: "NULL"))
            row[field] = replacement
        }
    }

    /** Set a field value -- replaces if exists, adds if missing. Used for NOT NULL columns
     *  that may not be in the Snowflake extraction but are required by the MySQL schema. */
    private fun setField(
        row: MutableMap<String, Any?>,
        field: String,
        replacement: String?,
        table: String,
        redactions: MutableList<PiiRedaction>,
    ) {
        val original = row[field]
        if (original != null) {
            redactions.add(PiiRedaction(table, field.lowercase(), sha256(original.toString()), replacement ?: "NULL"))
        }
        row[field] = replacement
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
