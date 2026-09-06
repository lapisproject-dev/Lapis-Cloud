package network.lapis.cloud.shared.domain

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)" -- the `LC-XXXXXX` payment-reference grammar a
 * treasurer can ask a member to put in their bank transfer's Verwendungszweck, and that
 * `network.lapis.cloud.server.payment.bankstatement.BankStatementMatcher`'s "R1" rule then finds
 * again in an imported bank line's purpose text. `commonMain` so both `lapis-server` (allocation,
 * matching) and `lapis-client` (displaying/validating a hand-typed reference) share exactly one
 * canonicalization implementation -- same reasoning [EventTicketCode] already establishes for its
 * own shared grammar.
 *
 * **Alphabet: Crockford-Base32**, the SAME alphabet [EventTicketCode.ALPHABET] uses (`0-9`, `A-Z`
 * minus `I`, `L`, `O`, `U`) -- no visually-confusable letters, for the same "a human may need to
 * type this by hand" reasoning.
 *
 * **Shape: `LC-` + 6 canonical characters** -- 5 payload symbols ([PAYLOAD_LENGTH], 25 bits) plus
 * one check symbol, `CANONICAL_LENGTH` = 6. [encode] returns the bare 6-character canonical body
 * (no prefix); [PREFIX] is prepended separately wherever the reference is displayed/stored/matched
 * against, so `PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(payload)` is the full
 * `"LC-7K3M9Q"` form that ends up in `contribution.payment_reference` (VARCHAR(12), comfortably
 * fits `"LC-" + 6` = 9 characters).
 *
 * **Check symbol: a GF(2⁵)-weighted checksum, not a plain XOR.** A design-team specification for
 * this grammar called for "5 bit Prüfwert = XOR der fünf 5-bit-Gruppen" while ALSO requiring the
 * check symbol to catch a transposition of two adjacent payload symbols -- a contradiction, since
 * XOR is commutative and therefore blind to any reordering of its own inputs. This implementation
 * resolves that without changing the alphabet, length, or payload size: over the finite field
 * GF(2⁵) (elements 0..31, addition = XOR, multiplication reduced modulo the PRIMITIVE polynomial
 * `x⁵+x²+1`), the check symbol is
 * ```
 * check = α¹·d₀ ⊕ α²·d₁ ⊕ α³·d₂ ⊕ α⁴·d₃ ⊕ α⁵·d₄        (α = the generator 2 = "x")
 * ```
 * Because the chosen polynomial is primitive, `α` generates every nonzero element of GF(32) (order
 * 31), so every `αᵏ` for `k` in `1..5` is both nonzero (an invertible unit of the field) AND
 * pairwise distinct. Those two algebraic facts are exactly what a checksum needs to guarantee:
 * - **every single-symbol error is detected** -- changing digit `dᵢ` alone changes the check by
 *   `αⁱ⁺¹ · Δ` for some nonzero `Δ`, and `αⁱ⁺¹` is never the zero element, so the check value
 *   always changes too.
 * - **every transposition of two adjacent (or any two) payload symbols is detected** -- swapping
 *   `dᵢ` and `dⱼ` changes the check by `(αⁱ⁺¹ ⊕ αʲ⁺¹) · (dᵢ ⊕ dⱼ)` (in GF(2⁵) arithmetic), which is
 *   only zero if `dᵢ == dⱼ` (nothing actually changed) or `αⁱ⁺¹ == αʲ⁺¹` -- impossible for `i != j`
 *   since the five powers used here are pairwise distinct.
 *
 * PLAN note: this is a DELIBERATE, DOCUMENTED deviation from the design-team's literal "XOR"
 * wording -- the grammar (alphabet, `LC-` prefix, 6-character canonical body) is unchanged.
 */
object PaymentReferenceCode {
    const val PREFIX: String = "LC-"

    /** Number of base-32 payload symbols -- 5 symbols * 5 bits = 25 bits of payload. */
    const val PAYLOAD_LENGTH: Int = 5

    /** [PAYLOAD_LENGTH] payload symbols + 1 check symbol. Does NOT include [PREFIX]. */
    const val CANONICAL_LENGTH: Int = PAYLOAD_LENGTH + 1

    /** Same 32-character Crockford-Base32 alphabet as [EventTicketCode.ALPHABET] -- kept as an independent constant (not a shared reference) so the two grammars can diverge later without coupling. */
    internal const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val PAYLOAD_BITS = PAYLOAD_LENGTH * 5
    private const val FIELD_MASK = 0x1F

    /** GF(2^5) reduction constant for the primitive polynomial x^5+x^2+1 (low 5 bits: 0b00101). */
    private const val REDUCTION = 0x05
    private const val GENERATOR = 2

    /** Multiplies [a] and [b] in GF(2^5) (Russian-peasant carry-less multiplication, reduced modulo the primitive polynomial x^5+x^2+1). Pure, no allocation, safe to call from anywhere -- unlike a cryptographic primitive this carries no key material and needs no thread-safety caveat. */
    internal fun gfMul(
        a: Int,
        b: Int,
    ): Int {
        var result = 0
        var multiplicand = a and FIELD_MASK
        var multiplier = b and FIELD_MASK
        repeat(5) {
            if (multiplier and 1 != 0) result = result xor multiplicand
            val carry = multiplicand and 0x10 != 0
            multiplicand = (multiplicand shl 1) and FIELD_MASK
            if (carry) multiplicand = multiplicand xor REDUCTION
            multiplier = multiplier shr 1
        }
        return result
    }

    /** `alphaPowers[k]` = generator^k in GF(32) for `k` in `1..PAYLOAD_LENGTH`; index 0 unused. */
    private val alphaPowers: IntArray by lazy {
        val powers = IntArray(PAYLOAD_LENGTH + 1)
        powers[1] = GENERATOR
        for (k in 2..PAYLOAD_LENGTH) powers[k] = gfMul(a = powers[k - 1], b = GENERATOR)
        powers
    }

    private fun checkSymbolFor(payloadDigits: IntArray): Int {
        var acc = 0
        for (i in 0 until PAYLOAD_LENGTH) {
            acc = acc xor gfMul(a = alphaPowers[i + 1], b = payloadDigits[i])
        }
        return acc
    }

    /** Encodes [payload] (must be in `[0, 2^25)`) into the bare 6-character canonical body (no [PREFIX]). */
    fun encode(payload: Int): String {
        require(payload in 0 until (1 shl PAYLOAD_BITS)) { "payload must be in [0, 2^$PAYLOAD_BITS)" }
        val digits =
            IntArray(PAYLOAD_LENGTH) { i ->
                (payload shr ((PAYLOAD_LENGTH - 1 - i) * 5)) and FIELD_MASK
            }
        val check = checkSymbolFor(digits)
        val builder = StringBuilder(CANONICAL_LENGTH)
        digits.forEach { builder.append(ALPHABET[it]) }
        builder.append(ALPHABET[check])
        return builder.toString()
    }

    /** `true` iff [canonicalBody] is exactly [CANONICAL_LENGTH] characters, every one drawn from [ALPHABET], with a check symbol matching the GF(32) formula above. Bare body -- no [PREFIX]. */
    fun isValid(canonicalBody: String): Boolean {
        if (canonicalBody.length != CANONICAL_LENGTH) return false
        val values = IntArray(CANONICAL_LENGTH)
        for (i in canonicalBody.indices) {
            val idx = ALPHABET.indexOf(canonicalBody[i])
            if (idx < 0) return false
            values[i] = idx
        }
        val expected = checkSymbolFor(values.copyOfRange(0, PAYLOAD_LENGTH))
        return expected == values[PAYLOAD_LENGTH]
    }

    /**
     * Canonicalizes free-form user input -- with or without [PREFIX], with or without surrounding
     * whitespace, repairing `O`->`0`/`I`,`L`->`1` the same way [EventTicketCode.canonicalize] does
     * -- into the full `"LC-XXXXXX"` form, or `null` if the result is not [CANONICAL_LENGTH]
     * characters, not drawn from [ALPHABET], or fails the check-symbol validation.
     */
    fun normalize(raw: String): String? {
        var stripped = raw.trim().uppercase().filterNot { it.isWhitespace() }
        stripped =
            when {
                stripped.startsWith(PREFIX) -> stripped.substring(PREFIX.length)
                stripped.startsWith("LC") -> stripped.substring(2).trimStart('-')
                else -> stripped
            }
        val repaired =
            stripped
                .map { c ->
                    when (c) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> c
                    }
                }.joinToString(separator = "")
        if (!isValid(repaired)) return null
        return PREFIX + repaired
    }

    /**
     * First checksum-valid `"LC-XXXXXX"` reference found anywhere inside free-form [text] (e.g. a
     * bank transfer's Verwendungszweck), or `null` if none is found. A `LC-`-prefixed substring
     * whose 6 following characters do not pass [isValid] is skipped, NOT returned -- the checksum
     * is what keeps an incidental `"LC-"` occurrence (e.g. inside an unrelated reference number)
     * from being mistaken for a real payment reference.
     */
    fun findIn(text: String): String? {
        val upper = text.uppercase()
        val regex = Regex("""LC-?([0-9A-Z]{$CANONICAL_LENGTH})""")
        for (match in regex.findAll(upper)) {
            val candidate = match.groupValues[1]
            val repaired =
                candidate
                    .map { c ->
                        when (c) {
                            'O' -> '0'
                            'I', 'L' -> '1'
                            else -> c
                        }
                    }.joinToString(separator = "")
            if (isValid(repaired)) return PREFIX + repaired
        }
        return null
    }
}
