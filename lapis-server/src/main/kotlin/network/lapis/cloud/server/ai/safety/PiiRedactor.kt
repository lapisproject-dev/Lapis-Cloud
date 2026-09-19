package network.lapis.cloud.server.ai.safety

/**
 * Removes obvious personal identifiers from a member's question **before** it leaves the server.
 *
 * **Simple patterns, no anonymization promise.** Exactly four are recognized -- e-mail addresses,
 * IBANs, long digit runs (>= 9 digits, matched before the telephone pattern) and telephone numbers. Names, addresses and free-text
 * personal data are NOT detected; the member-facing opt-in text and the operator documentation say
 * so. Only the redacted text is ever sent to the provider, hashed for the audit log, or used as
 * the retrieval query.
 *
 * **Deliberately conservative against over-redaction.** A statute question stands or falls with
 * its references: `§ 12 Abs. 3`, `Art. 5`, `2026` or `v2` must survive untouched, otherwise the
 * search for a statute passage is functionally dead (and the failure would only surface in
 * operation). The telephone pattern therefore requires at least [MIN_PHONE_DIGITS] digits, which no
 * paragraph/article/year reference reaches.
 */
internal object PiiRedactor {
    data class Result(
        val text: String,
        val redactionCount: Int,
    )

    private const val MIN_PHONE_DIGITS = 7
    private const val MIN_IBAN_CHARS = 15

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val IBAN = Regex("""\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{2,4}){3,8}\b""")
    private val PHONE =
        Regex(
            // The lookahead (placed after the optional prefix and "(") keeps a year range ("2024-2025",
            // "(2026 2027)") from reading as a phone number, with or without surrounding parentheses.
            """(?<![\w§])(?:\+\d{1,3}[ /-]?)?\(?(?!(?:19|20)\d{2}[ /-](?:19|20)\d{2}(?!\d))""" +
                """\d{2,5}\)?[ /-]?\d{3,}(?:[ /-]?\d+)*""",
        )
    private val LONG_NUMBER = Regex("""\b\d{9,}\b""")

    fun redact(input: String): Result {
        var count = 0
        var text = input

        text =
            EMAIL.replace(text) {
                count++
                "[EMAIL]"
            }
        text =
            IBAN.replace(text) { match ->
                if (match.value.count { it.isLetterOrDigit() } >= MIN_IBAN_CHARS) {
                    count++
                    "[IBAN]"
                } else {
                    match.value
                }
            }
        text =
            LONG_NUMBER.replace(text) {
                count++
                "[NUMBER]"
            }
        text =
            PHONE.replace(text) { match ->
                if (match.value.count { it.isDigit() } >= MIN_PHONE_DIGITS) {
                    count++
                    "[PHONE]"
                } else {
                    match.value
                }
            }
        return Result(text = text, redactionCount = count)
    }
}
