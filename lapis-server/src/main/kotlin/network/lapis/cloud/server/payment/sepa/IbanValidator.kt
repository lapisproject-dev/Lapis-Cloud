package network.lapis.cloud.server.payment.sepa

/**
 * V1.2.2 "SEPA-Lastschriftmandate". ISO-7064-Mod-97-10 check of an IBAN plus a length check against
 * the country table. Self-written, no dependency -- see the vault plan "sepa_v1.2.2_plan.md" § 6.1/
 * D-9: this codebase's version catalog only ever takes a new JVM library with an extensive
 * justification comment, and an IBAN check digit is not one.
 *
 * **What this class does NOT do**: it does not say whether an account exists, whether it belongs to
 * the stated holder, or whether the bank accepts direct debits. It says only that the string is
 * formally a well-formed IBAN. A typo that happens to hit the check digit passes -- only the return
 * process (`MD01`/`MD06`) catches that.
 */
object IbanValidator {
    /** Country code -> exact IBAN length. Source: SWIFT IBAN Registry. Covers the EEA plus Switzerland/UK. */
    internal val COUNTRY_LENGTHS: Map<String, Int> =
        mapOf(
            "AD" to 24,
            "AT" to 20,
            "BE" to 16,
            "BG" to 22,
            "CH" to 21,
            "CY" to 28,
            "CZ" to 24,
            "DE" to 22,
            "DK" to 18,
            "EE" to 20,
            "ES" to 24,
            "FI" to 18,
            "FR" to 27,
            "GB" to 22,
            "GR" to 27,
            "HR" to 21,
            "HU" to 28,
            "IE" to 22,
            "IS" to 26,
            "IT" to 27,
            "LI" to 21,
            "LT" to 20,
            "LU" to 20,
            "LV" to 21,
            "MC" to 27,
            "MT" to 31,
            "NL" to 18,
            "NO" to 15,
            "PL" to 28,
            "PT" to 25,
            "RO" to 24,
            "SE" to 24,
            "SI" to 19,
            "SK" to 24,
            "SM" to 27,
        )

    /**
     * SEPA-Raum -- the countries whose IBANs are eligible for a SEPA direct debit. A subset of
     * [COUNTRY_LENGTHS]'s keys ("GB" is a formally valid IBAN country but has left SEPA's direct-debit
     * scope for retail use after Brexit for practical purposes here; kept out to fail closed).
     */
    internal val SEPA_COUNTRIES: Set<String> = COUNTRY_LENGTHS.keys - setOf("GB")

    /** Removes all whitespace and upper-cases. Never throws. */
    fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()

    /** `true` iff [raw], after [normalize], is a formally valid IBAN (country + length + Mod-97-10 check digit). */
    fun isValid(raw: String): Boolean {
        val iban = normalize(raw)
        if (iban.length < 15 || iban.length > 34) return false
        if (!iban.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        val countryCode = iban.substring(0, 2)
        if (countryCode.any { it !in 'A'..'Z' }) return false
        val expectedLength = COUNTRY_LENGTHS[countryCode] ?: return false
        if (iban.length != expectedLength) return false
        if (iban.substring(2, 4).any { it !in '0'..'9' }) return false
        return mod97Check(iban)
    }

    /**
     * Normalizes and validates; throws [IllegalArgumentException] with a message that does NOT
     * contain the IBAN itself (it is personal bank data -- an exception message ends up in logs and
     * potentially in a client-facing error).
     */
    fun requireValid(raw: String): String {
        val iban = normalize(raw)
        require(isValid(iban)) { "IBAN is not formally valid (failed length/country/check-digit validation)" }
        val countryCode = iban.substring(0, 2)
        require(countryCode in SEPA_COUNTRIES) { "IBAN country '$countryCode' is not part of the SEPA area" }
        return iban
    }

    /** The last four digits of the normalized IBAN -- for `sepa_mandate.debtor_iban_last4`. */
    fun last4(raw: String): String = normalize(raw).takeLast(4)

    /**
     * Rotates the first four characters to the end, maps letters to numbers (A=10..Z=35), and
     * computes the value mod 97 piecewise (never as one giant [java.math.BigInteger]/[Long] -- a
     * 34-character IBAN expands past 60 decimal digits, well beyond `Long` range).
     */
    private fun mod97Check(iban: String): Boolean {
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var remainder = 0
        for (ch in rearranged) {
            val value = if (ch in '0'..'9') (ch - '0') else (ch - 'A' + 10)
            if (value < 10) {
                remainder = (remainder * 10 + value) % 97
            } else {
                // Two-digit letter value (10-35): fold in both digits separately.
                remainder = (remainder * 10 + value / 10) % 97
                remainder = (remainder * 10 + value % 10) % 97
            }
        }
        return remainder == 1
    }
}
