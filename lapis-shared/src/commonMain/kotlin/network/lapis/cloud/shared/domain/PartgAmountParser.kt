package network.lapis.cloud.shared.domain

/** Why a typed-in or text-cell amount was refused (V1.9.225). */
enum class PartgAmountError { EMPTY, NEGATIVE, NOT_A_NUMBER, TOO_MANY_DECIMALS, OVERFLOW }

/** Result of [parseGermanAmount]. */
sealed interface AmountParse {
    data class Ok(
        val cents: Long,
    ) : AmountParse

    data class Error(
        val reason: PartgAmountError,
    ) : AmountParse
}

/** The largest amount of one position in cents (999.999.999.999,99 EUR) = the upper bound of the `amount_cents` CHECK. */
const val PARTG_MAX_AMOUNT_CENTS: Long = 99_999_999_999_999L

private val GERMAN_AMOUNT = Regex("""^(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d+))?$""")

/**
 * V1.9.225 -- the ONE parser of a German amount, used by the client input and by the text cells of the Excel import.
 *
 * Accepts `1234`, `1234,5`, `1.234,56` (thousands dots only in groups of three, comma as decimal separator) and an optional trailing `€`
 * (spaces allowed around it). Refuses `1,234.56`, exponents, NaN/Infinity, a leading `+` or `-`, a comma without decimals and anything
 * above 999.999.999.999,99. At most two decimals; the result is in cents.
 */
fun parseGermanAmount(text: String): AmountParse {
    var s = text.trim().replace(' ', ' ').trim()
    if (s.endsWith("€")) s = s.removeSuffix("€").trim()
    if (s.isEmpty()) return AmountParse.Error(PartgAmountError.EMPTY)
    if (s.startsWith("-") || s.startsWith("−")) return AmountParse.Error(PartgAmountError.NEGATIVE)
    val match = GERMAN_AMOUNT.matchEntire(s) ?: return AmountParse.Error(PartgAmountError.NOT_A_NUMBER)
    val integerDigits =
        match.groupValues[1]
            .replace(".", "")
            .trimStart('0')
            .ifEmpty { "0" }
    val fraction = match.groupValues[2]
    if (fraction.length > 2) return AmountParse.Error(PartgAmountError.TOO_MANY_DECIMALS)
    if (integerDigits.length > 12) return AmountParse.Error(PartgAmountError.OVERFLOW)
    val cents = integerDigits.toLong() * 100 + fraction.padEnd(2, '0').toLong()
    if (cents > PARTG_MAX_AMOUNT_CENTS) return AmountParse.Error(PartgAmountError.OVERFLOW)
    return AmountParse.Ok(cents)
}
