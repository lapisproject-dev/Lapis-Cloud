package network.lapis.cloud.server.payment.sepa

/**
 * V1.9.328 -- finds a real IBAN inside free text (moved here from `TravelExpenseSepaExport`, used by the SEPA re-export reason, the travel expense LTR payout
 * reason and the reference-date override reason: a justification text must never carry an account number).
 */
internal object IbanText {
    // Lookahead => a candidate starts at EVERY "XX99" position (a return code like "AC04" in front of an IBAN must not hide it).
    // Whitespace is only tolerated between the groups of at most four characters, never glued across words.
    private val IBAN_CANDIDATE = Regex("(?=([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{1,4}){3,10}))")

    /** Texts longer than this are never scanned (callers bound their input first; this keeps the regex scan cheap even on a missed bound). */
    private const val MAX_SCAN_LENGTH = 2_000

    /** `true` when [text] contains a real IBAN (country length + mod 97), not merely something that looks like one. */
    fun containsIban(text: String): Boolean =
        text.length <= MAX_SCAN_LENGTH &&
            IBAN_CANDIDATE.findAll(text.uppercase()).any { match ->
                val compact = match.groupValues[1].filterNot { it.isWhitespace() }
                val length = IbanValidator.COUNTRY_LENGTHS[compact.take(2)] ?: return@any false
                compact.length >= length && IbanValidator.isValid(compact.take(length))
            }
}
