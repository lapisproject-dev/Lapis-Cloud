package network.lapis.cloud.server.pdf

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Hand-written German cardinal-number-to-words rendering for a monetary [BigDecimal] amount, used
 * by [SpendenbescheinigungPdfGenerator] to print an amount "in Worten" (in figures AND words) --
 * the official BMF Muster for a Zuwendungsbestaetigung (§50 EStDV donation receipt) requires both.
 *
 * **This is hand-written natural-language generation embedded in a legally-sensitive tax
 * document -- spot-check the exact wording against a human/tax advisor before this is used to
 * issue a real receipt to a real donor.** Green unit tests ([format] is covered exhaustively by
 * `GermanAmountInWordsTest`) prove internal consistency of the algorithm below, not legal
 * correctness of German orthography in every edge case.
 *
 * Grammar convention adopted here (documented because German number-to-words has a genuine
 * ambiguity around the digit 1): the digit 1 is spelled `"ein"` (not `"eins"`) exactly in the two
 * places where German grammar requires the bound/adjectival form --
 *  - as a compound-tens prefix (`21` -> `"einundzwanzig"`, never `"einsundzwanzig"`), and
 *  - when it is the *entire* euro or cent amount on its own (`1` -> `"ein"`, matching the
 *    idiomatic `"ein Euro"`/`"ein Cent"`, not `"eins Euro"`).
 * Everywhere else the digit 1 keeps the full cardinal form `"eins"` (`101` ->
 * `"einhunderteins"`, `1001` -> `"eintausendeins"`) -- this matches how German is actually written
 * for page/room/amount numbers ending in 1 that are not a direct compound-tens formation.
 *
 * Supports non-negative amounts up to 999999.99 (six-digit Euro amounts) -- comfortably beyond
 * any single membership-dues invoice or donation this codebase's non-profit/political-party
 * domain is expected to need in one line item.
 */
object GermanAmountInWords {
    private val ones = listOf("null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")
    private val teens =
        listOf("zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn")
    private val tens =
        mapOf(
            2 to "zwanzig",
            3 to "dreißig",
            4 to "vierzig",
            5 to "fünfzig",
            6 to "sechzig",
            7 to "siebzig",
            8 to "achtzig",
            9 to "neunzig",
        )

    /** Renders [amount] as e.g. `"eintausendzweihundertvierunddreißig Euro und sechsundfünfzig Cent"`. */
    fun format(amount: BigDecimal): String {
        val scaled = amount.setScale(2, RoundingMode.HALF_UP).abs()
        val euros = scaled.toBigInteger().toLong()
        val cents = scaled.subtract(BigDecimal(euros)).movePointRight(2).toInt()
        val euroWords = wordsFor(euros).let { if (it == "eins") "ein" else it }
        val centWords = wordsFor(cents.toLong()).let { if (it == "eins") "ein" else it }
        return "$euroWords Euro und $centWords Cent"
    }

    /** Cardinal spelling of [n] (0..999999), standalone form -- digit 1 alone renders as `"eins"`, see class KDoc. */
    private fun wordsFor(n: Long): String {
        require(n in 0..999_999) { "GermanAmountInWords only supports 0..999999, got $n" }
        if (n == 0L) return "null"
        val thousands = (n / 1000).toInt()
        val remainder = (n % 1000).toInt()
        val sb = StringBuilder()
        if (thousands > 0) {
            sb.append(if (thousands == 1) "ein" else threeDigitWords(thousands)).append("tausend")
        }
        if (remainder > 0) {
            sb.append(threeDigitWords(remainder))
        }
        return sb.toString()
    }

    /** Cardinal spelling of [n] (1..999) as a bound component -- never called with 0. */
    private fun threeDigitWords(n: Int): String {
        val hundreds = n / 100
        val rest = n % 100
        val sb = StringBuilder()
        if (hundreds > 0) {
            sb.append(if (hundreds == 1) "ein" else ones[hundreds]).append("hundert")
        }
        if (rest > 0) {
            sb.append(twoDigitWords(rest))
        }
        return sb.toString()
    }

    /** Cardinal spelling of [n] (1..99). */
    private fun twoDigitWords(n: Int): String {
        if (n < 10) return ones[n]
        if (n < 20) return teens[n - 10]
        val tensDigit = n / 10
        val onesDigit = n % 10
        val tensWord = tens.getValue(tensDigit)
        if (onesDigit == 0) return tensWord
        val onesPrefix = if (onesDigit == 1) "ein" else ones[onesDigit]
        return "${onesPrefix}und$tensWord"
    }
}
