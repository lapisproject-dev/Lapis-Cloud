package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span
import io.kvision.i18n.I18n

/**
 * Accounting UI wave -- the single, shared monetary-amount display convention. Every screen calls
 * [formatMoney]/[moneySpan] (EUR) or [formatLtr]/[ltrSpan] (LTR) for every displayed amount; no screen interpolates a [Decimal]
 * into a string itself.
 *
 * **W6a revision of the original decision D5.** D5 said: `Decimal.toString()`'s digits are never touched -- no thousands
 * separator, no decimal comma, no padding -- so a displayed figure can never diverge from what the server computed. That rule
 * protected the DIGITS, but it was bought with a display nobody can read: `1234.5 €` is not something a treasurer can audit
 * at a glance, and `1234.5600000000001` (double noise, handled by [displayDigits] since W5) is a loss of trust right before an
 * irreversible booking. D5 therefore falls, and the digits' protection is kept differently:
 * - the transform is a pure STRING operation ([groupDecimalDigits]): grouping from the right over the integer part, the
 *   fraction padded to two places when shorter and left completely untouched when longer (a genuine sub-cent value such as
 *   `12.345` stays `12,345`, it is NEVER rounded); no `Double` arithmetic is involved, so no magnitude loses a digit;
 * - separators, currency placement and the minus sign (U+2212, display only, in front of a prefix currency too) follow the UI
 *   language ([MONEY_LOCALES]); an unknown language falls back to German.
 *
 * **Why no `Intl.NumberFormat` / `toLocaleString` / `toFixed`:** they round to two places by default -- `12.345` would silently
 * become `12,35`, exactly the failure D5 was meant to prevent -- and their output depends on the browser's ICU version, which
 * would make the golden tests flaky. A hard-coded table of eight locales is small and deterministic.
 *
 * **Display vs. machine-readable.** Everything on this page is DISPLAY. What prefills a field or is compared against an entered
 * amount ([displayDigits], [displayAmountValue], [exceedsDisplayedAmount]) stays ASCII, dot-decimal and ungrouped (audit fix M3);
 * a formatted amount must never be parsed back.
 *
 * **Widget content follows a language switch.** `I18n.language`'s setter restarts the root, which re-renders the EXISTING widget
 * tree; a plain string keeps its text. So amounts that are widget content are [moneyToken]s (a `###KvI18nS###` marker string the
 * renderer resolves on every render -- the mechanism [trFormat] uses). Amounts inside a `gettext(...)` message are plain
 * [formatMoney] strings and freeze at the language of the moment, like the rest of that message (documented gap, W4a).
 *
 * **LTR.** [formatLtr] gets the same treatment so a screen never shows a localized LTR amount next to a raw one. The currency
 * position: the euro sign is a prefix in `en`/`nl` (currency convention); `LTR` (and `USD`) is always a suffix -- a prefixed
 * "LTR" would be an invented convention.
 */
fun formatMoney(amount: Decimal): String = formatMoneyIn(I18n.language, amount)

/** [formatMoney] for an EXPLICIT language -- the seam tests use so they never have to set `I18n.language` (which restarts the root). */
internal fun formatMoneyIn(
    language: String,
    amount: Decimal,
): String = formatAmountDigits(displayDigits(amount), "€", moneyLocale(language))

/** LTR-denominated sibling of [formatMoney]; see there. */
fun formatLtr(amount: Decimal): String = formatLtrIn(I18n.language, amount)

internal fun formatLtrIn(
    language: String,
    amount: Decimal,
): String = formatAmountDigits(displayDigits(amount), "LTR", moneyLocale(language))

/** Unit-less sibling of [formatMoney]: same grouping/padding, no currency (combined trust weight). */
internal fun formatPlainAmountIn(
    language: String,
    amount: Decimal,
): String = formatAmountDigits(displayDigits(amount), "", moneyLocale(language))

/**
 * A whole-number count stored in a [Decimal] (e.g. the guest trust weight, a plain vote count): grouped but NEVER padded to two
 * places -- `3` stays `3`, so it reads like the raw counts next to it. A fractional value falls back to [formatPlainAmountIn]'s form.
 */
internal fun formatCountIn(
    language: String,
    amount: Decimal,
): String = formatCountDigits(displayDigits(amount), moneyLocale(language))

internal fun formatCountDigits(
    digits: String,
    locale: MoneyLocale,
): String =
    if (PLAIN_DECIMAL.matches(digits) && !digits.contains('.')) {
        groupDecimalDigits(digits, locale).let { grouped ->
            grouped.substringBefore(locale.decimal)
        }
    } else {
        formatAmountDigits(digits, "", locale)
    }

internal const val NBSP = "\u00A0"
internal const val MINUS = "\u2212"

/** Group separator, decimal separator and euro-sign placement of one UI language. */
internal data class MoneyLocale(
    val group: String,
    val decimal: String,
    /** `true` = the euro sign stands BEFORE the number (only for `€`, see the file KDoc). */
    val prefix: Boolean,
    /** Between number and euro sign. */
    val gap: String,
)

/** The eight UI languages (`SUPPORTED_LANGUAGES` in App.kt). Group separator is U+00A0, never U+202F. */
internal val MONEY_LOCALES: Map<String, MoneyLocale> =
    mapOf(
        "de" to MoneyLocale(group = ".", decimal = ",", prefix = false, gap = NBSP),
        "en" to MoneyLocale(group = ",", decimal = ".", prefix = true, gap = ""),
        "fr" to MoneyLocale(group = NBSP, decimal = ",", prefix = false, gap = NBSP),
        "es" to MoneyLocale(group = ".", decimal = ",", prefix = false, gap = NBSP),
        "it" to MoneyLocale(group = ".", decimal = ",", prefix = false, gap = NBSP),
        "nl" to MoneyLocale(group = ".", decimal = ",", prefix = true, gap = NBSP),
        "pl" to MoneyLocale(group = NBSP, decimal = ",", prefix = false, gap = NBSP),
        "ru" to MoneyLocale(group = NBSP, decimal = ",", prefix = false, gap = NBSP),
    )

/** `I18n.language` may carry a region ("de-DE"); the table is keyed by the base tag. Unknown -> German. */
internal fun moneyLocale(language: String): MoneyLocale =
    MONEY_LOCALES[language.substringBefore('-').lowercase()] ?: MONEY_LOCALES.getValue("de")

private val PLAIN_DECIMAL = Regex("^-?[0-9]+(\\.[0-9]+)?$")

/**
 * [digits] is a plain positional decimal string ([plainDecimal]/[displayDigits] output: ASCII '-' and '.'). Grouping is a STRING
 * operation from the right over the integer part only; the fraction is padded to two places when shorter and left untouched when
 * longer. No `Double` is involved. Text that is not a plain decimal is returned as it is (fail closed, never an exception).
 */
internal fun groupDecimalDigits(
    digits: String,
    locale: MoneyLocale,
): String {
    if (!PLAIN_DECIMAL.matches(digits)) return digits
    val negative = digits.startsWith("-")
    val unsigned = digits.removePrefix("-")
    val whole = unsigned.substringBefore('.')
    val fraction = unsigned.substringAfter('.', "")
    val grouped = StringBuilder()
    whole.forEachIndexed { index, char ->
        if (index > 0 && (whole.length - index) % 3 == 0) grouped.append(locale.group)
        grouped.append(char)
    }
    val paddedFraction = if (fraction.length <= 2) fraction.padEnd(2, '0') else fraction
    val body = grouped.toString() + locale.decimal + paddedFraction
    val isZero = (whole + fraction).all { it == '0' }
    return (if (negative && !isZero) MINUS else "") + body
}

/** The whole display transform for an already-cleaned digit string: group, pad, sign, currency ("€", "LTR", "USD" or "" for none). */
internal fun formatAmountDigits(
    digits: String,
    currency: String,
    locale: MoneyLocale,
): String {
    val grouped = groupDecimalDigits(digits, locale)
    val sign = if (grouped.startsWith(MINUS)) MINUS else ""
    val body = grouped.removePrefix(MINUS)
    return when {
        currency.isEmpty() -> grouped
        currency == "€" && locale.prefix -> sign + currency + locale.gap + body
        currency == "€" -> sign + body + locale.gap + currency
        else -> sign + body + NBSP + currency
    }
}

/**
 * W5 (V1.4.31): the ONE exception to "`Decimal.toString()`'s own digits are never touched" -- pure floating-point NOISE. A
 * `Decimal` is backed by a double, so a sum can arrive as `1234.5600000000001` or `99.99999999999999`; that is not a figure the
 * server computed, it is the representation error of one. The digits are replaced ONLY when the value lies within [noiseTolerance] of
 * a whole cent AND the rendered text has more than two fractional digits. A genuine sub-cent value (`0.005`, `12.345`, `1234567.891`,
 * `1.5e-7`) is far outside that tolerance and stays exactly as it is -- so the display still can never diverge from what the
 * server computed. (Audit fix M3: the tolerance used to be an absolute 1e-6, which turned the genuine `1.5e-7` into "0". It is
 * [NOISE_TOLERANCE_ABSOLUTE] (1e-9) for everyday amounts and grows with the magnitude, [NOISE_TOLERANCE_RELATIVE] (1e-14) of the
 * value, because the representation error of a double does: 1e-14 of 1e9 is still 1e-5, far below a cent. Values below the absolute
 * tolerance around ZERO, e.g. the `5.55e-17` left over by `0.1 + 0.2 - 0.3`, are noise by construction and render "0".)
 *
 * Exponent notation (`1.5e-7`, `1e21`) never reaches the screen: [plainDecimal] expands it to positional digits, so "1.5e-7 EUR"
 * cannot happen and the fraction-digit count below is a real count. A cleaned value renders without a trailing `.0` ("100", not
 * "100.0"), exactly like an exact `100` does.
 */
internal fun displayDigits(amount: Decimal): String {
    val text = plainDecimal(amount.toString())
    val fractionDigits = text.substringAfter('.', "").length
    if (fractionDigits <= 2) return text
    val value = amount.toDouble()
    // Above 2^53/100 cents `round(value*100)/100` is meaningless (the double has no cent resolution left): the text stays as it is.
    if (kotlin.math.abs(value) > CENT_SMOOTHING_MAX) return text
    val nearestCent = kotlin.math.round(value * 100.0) / 100.0
    if (kotlin.math.abs(value - nearestCent) >= noiseTolerance(value)) return text
    return plainDecimal(nearestCent.toString()).removeSuffix(".0").let { if (it == "-0") "0" else it }
}

/** See [displayDigits]: no cent smoothing above this magnitude. */
internal const val CENT_SMOOTHING_MAX = 9.0e13

/** Absolute part of the noise tolerance of [displayDigits] around a whole cent (see there). */
internal const val NOISE_TOLERANCE_ABSOLUTE = 1e-9

/** Relative part (of the value's magnitude) of the noise tolerance of [displayDigits] (see there). */
internal const val NOISE_TOLERANCE_RELATIVE = 1e-14

internal fun noiseTolerance(value: Double): Double = maxOf(NOISE_TOLERANCE_ABSOLUTE, NOISE_TOLERANCE_RELATIVE * kotlin.math.abs(value))

/**
 * [text] (a `Double`/`Decimal` rendering) in positional notation: `1.5e-7` -> `0.00000015`, `1E21` -> `1000000000000000000000`.
 * Text without an exponent is returned as it is. Pure string arithmetic -- no floating-point involved, so no digit changes.
 */
internal fun plainDecimal(text: String): String {
    val marker = text.indexOfFirst { it == 'e' || it == 'E' }
    if (marker < 0) return text
    val exponent = text.substring(marker + 1).removePrefix("+").toIntOrNull() ?: return text
    val mantissa = text.substring(0, marker)
    val negative = mantissa.startsWith("-")
    val unsigned = mantissa.removePrefix("-")
    val whole = unsigned.substringBefore('.')
    val fraction = unsigned.substringAfter('.', "")
    val digits = whole + fraction
    val pointAt = whole.length + exponent
    val body =
        when {
            pointAt <= 0 -> "0." + "0".repeat(-pointAt) + digits
            pointAt >= digits.length -> digits + "0".repeat(pointAt - digits.length)
            else -> digits.substring(0, pointAt) + "." + digits.substring(pointAt)
        }
    // trailing zeros only after a decimal point ("1E3" -> "1000" must keep its zeros)
    val trimmed = if (body.contains('.')) body.trimEnd('0').removeSuffix(".") else body
    val plain = trimmed.ifEmpty { "0" }
    return (if (negative && plain != "0") "-" else "") + plain
}

/**
 * The amount as a person reads it on screen ([displayDigits]) as a number -- the ONE value that prefills a field, and against
 * which an entered amount is checked. Prefill, the visible "Offen: 100 EUR" and the check must all come from this same cleaned
 * view: with the raw `99.99999999999999` behind a screen that says "100", entering exactly that "100" was rejected as "more than
 * the open amount" (audit fix M3).
 */
internal fun displayAmountValue(amount: Decimal): Double = displayDigits(amount).toDouble()

/** Half a cent: an entered amount (at most two fractional digits) may exceed the cleaned [limit] by less than this. */
internal const val CENT_TOLERANCE = 0.005

/** `true` iff [entered] is more than a cent tolerance above the on-screen ([displayDigits]) view of [limit]. */
internal fun exceedsDisplayedAmount(
    entered: Decimal,
    limit: Decimal,
): Boolean = entered.toDouble() > displayAmountValue(limit) + CENT_TOLERANCE

/**
 * D6: no sign transform beyond the display minus of [formatMoney], no parentheses, no string inspection. The optional red
 * highlight is driven by a **typed** numeric comparison ([Decimal.toDouble] against `0.0`), never by regex/string-inspecting the
 * rendered text. [warnIfNegative] must only be passed `true` for a field the underlying DTO's own KDoc documents as "may
 * legitimately be negative" (e.g. a GuV/Bilanz/four-sphere/cost-center result) -- never for a pure-magnitude field.
 *
 * The content is a [moneyToken]: it follows a language switch without a screen rebuild.
 */
fun Container.moneySpan(
    amount: Decimal,
    warnIfNegative: Boolean = false,
): Span =
    span(moneyToken(amount)) {
        if (warnIfNegative && amount.toDouble() < 0.0) addCssClass("text-danger")
    }

/**
 * LTR-Wirtschaft UI wave -- D2: unlike [moneySpan] (plain text), [ltrSpan] renders as a bordered pill with a leading `◆` glyph
 * (a second, non-color channel, WCAG 1.4.1), reusing `StatusBadge.kt`'s outline-pill grammar. [warnIfNegative] mirrors
 * [moneySpan]'s: a typed numeric comparison against `0.0`. The glyph is part of the [ltrToken] (see [formatMoneyToken]), so the
 * token is never concatenated (marker leak, audit V1.4.30).
 */
fun Container.ltrSpan(
    amount: Decimal,
    warnIfNegative: Boolean = false,
): Span {
    val color = if (warnIfNegative && amount.toDouble() < 0.0) "danger" else "primary"
    return span(ltrToken(amount)) {
        addCssClasses("badge rounded-pill border border-$color text-$color fw-bold")
    }
}

/**
 * Control character, never typeable through a keyboard and never rendered as visible glyphs: marks a
 * `###KvI18nS###` string as an AMOUNT, not a msgid.
 *
 * Security audit W6b follow-up (round 2) corrected an earlier version of this KDoc that claimed "server text cannot
 * forge it" -- that is FALSE and was exactly the mistaken assumption the whole W6b wave had to correct. A control
 * character is trivially representable in a `String` field (a DTO, a display name, free text from a form) even
 * though no keyboard shortcut types it directly -- nothing on the server or in transport strips non-printable
 * bytes by default. [MONEY_SENTINEL]'s safety comes ENTIRELY from where it is checked: only
 * [I18nCatalogManager.gettext] resolves a string starting with it into a formatted amount, and only [moneyToken] /
 * this module's own helpers ever construct one -- callers must never trust a sentinel's mere PRESENCE in
 * server-/user-controlled text as proof of legitimacy. Untrusted text is unconditionally stripped of this
 * character (and [KV_I18N_MARKER] / [I18N_ARG_SEPARATOR]) before ever reaching a widget or [trFormat] argument --
 * see [sanitizeUntrustedI18nText] -- precisely because it CAN be forged.
 */
internal const val MONEY_SENTINEL = "\u0002"
internal const val MONEY_KIND_EUR = 'E'
internal const val MONEY_KIND_LTR = 'L'

/** Unit-less amount, grouped and padded to two places ([formatPlainAmountIn]). */
internal const val MONEY_KIND_PLAIN = 'P'

/** Unit-less whole count, grouped, never padded ([formatCountIn]). */
internal const val MONEY_KIND_COUNT = 'N'

/**
 * An amount as LIVE-translatable widget content: `###KvI18nS###` + sentinel + kind + cleaned digits. The widget re-resolves it on
 * every render, so the amount follows a language switch (the mechanism [trFormat] uses). NEVER concatenate it into another
 * string (marker leak); pass it as a [trFormat] argument or as a widget's whole content. The payload carries no
 * [I18N_ARG_SEPARATOR], so it also survives being a [trFormat] argument.
 */
internal fun moneyToken(amount: Decimal): String = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_EUR + displayDigits(amount)

internal fun ltrToken(amount: Decimal): String = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + displayDigits(amount)

internal fun plainAmountToken(amount: Decimal): String = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_PLAIN + displayDigits(amount)

internal fun countToken(amount: Decimal): String = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_COUNT + displayDigits(amount)

/** Unit-less amount as live-translatable widget content (a [plainAmountToken]); follows a language switch. */
fun Container.plainAmountSpan(amount: Decimal): Span = span(plainAmountToken(amount))

/** Whole count as live-translatable widget content (a [countToken]); follows a language switch. */
fun Container.countSpan(amount: Decimal): Span = span(countToken(amount))

/** Resolves a [moneyToken] payload (marker already stripped, starts with [MONEY_SENTINEL]) in [language]. Fails closed: an unknown kind returns the raw digits, never an exception. */
internal fun formatMoneyTokenIn(
    language: String,
    payload: String,
): String {
    val rest = payload.removePrefix(MONEY_SENTINEL)
    val digits = rest.drop(1)
    val locale = moneyLocale(language)
    return when (rest.firstOrNull()) {
        MONEY_KIND_EUR -> formatAmountDigits(digits, "€", locale)
        MONEY_KIND_LTR -> "◆ " + formatAmountDigits(digits, "LTR", locale)
        MONEY_KIND_PLAIN -> formatAmountDigits(digits, "", locale)
        MONEY_KIND_COUNT -> formatCountDigits(digits, locale)
        else -> digits
    }
}

/** [formatMoneyTokenIn] in the CURRENT language. */
internal fun formatMoneyToken(payload: String): String = formatMoneyTokenIn(I18n.language, payload)

// ---- cent-exact sums (S7) ------------------------------------------------------------------------------------------------------

/** Max scale of a cent-exact sum: more decimals than any amount this app accepts. */
internal const val MONEY_MAX_SCALE = 9

/** Largest cent magnitude that round-trips through a double (2^53 - 1 = 9007199254740991). */
private const val MAX_EXACT_UNITS = 9_007_199_254_740_991L

/** `-12.345` -> (-1, "12", "345"). `null` when [text] is not a plain positional decimal. */
internal fun decimalParts(text: String): Triple<Int, String, String>? {
    if (!PLAIN_DECIMAL.matches(text)) return null
    val sign = if (text.startsWith("-")) -1 else 1
    val unsigned = text.removePrefix("-")
    return Triple(sign, unsigned.substringBefore('.'), unsigned.substringAfter('.', ""))
}

/** [amount] in units of 10^-[scale], string-based. `null` on a longer fraction, overflow or an unparsable value -- never a silent Double fallback. */
internal fun scaledUnitsOf(
    amount: Decimal,
    scale: Int,
): Long? {
    val (sign, whole, fraction) = decimalParts(displayDigits(amount)) ?: return null
    if (fraction.length > scale) return null
    val units = (whole + fraction.padEnd(scale, '0')).trimStart('0').ifEmpty { "0" }
    if (units.length > 16) return null
    val value = units.toLongOrNull() ?: return null
    if (value > MAX_EXACT_UNITS) return null
    return sign * value
}

/**
 * The cent-exact sum of [amounts]: common scale = the longest fraction of the cleaned ([displayDigits], not `toString()`) summands,
 * at most [MONEY_MAX_SCALE]. Throws on overflow or an unparsable summand -- the message names scale and count, never an amount.
 * The result is exact or the call fails: the decimal string is turned into a `Decimal` (a double) and [displayDigits] must map that
 * back to exactly the same string ([unitsToDecimal] checks it). Up to about 3.5e13 with two places (far above any amount the app accepts)
 * that always holds; beyond it the double has no cent resolution left and the sum throws instead of showing a wrong figure.
 */
internal fun sumExact(amounts: List<Decimal>): Decimal {
    val parts =
        amounts.map {
            decimalParts(
                displayDigits(it),
            ) ?: throw IllegalArgumentException("Unparsable amount among ${amounts.size} summands")
        }
    val scale = parts.maxOfOrNull { it.third.length } ?: 0
    require(scale <= MONEY_MAX_SCALE) { "Amount scale $scale exceeds $MONEY_MAX_SCALE" }
    var sum = 0L
    parts.forEach { (sign, whole, fraction) ->
        val units = (whole + fraction.padEnd(scale, '0')).trimStart('0').ifEmpty { "0" }
        check(units.length <= 16) { "Amount overflow among ${amounts.size} summands" }
        sum += sign * units.toLong()
        check(kotlin.math.abs(sum) <= MAX_EXACT_UNITS) { "Sum overflow among ${amounts.size} summands" }
    }
    return unitsToDecimal(sum, scale)
}

/** [cents] as a [Decimal]; the sole bridge from `Long` cents back to a [Decimal]. */
internal fun centsToDecimal(cents: Long): Decimal = unitsToDecimal(cents, 2)

private fun unitsToDecimal(
    units: Long,
    scale: Int,
): Decimal {
    val negative = units < 0
    val digits =
        kotlin.math
            .abs(units)
            .toString()
            .padStart(scale + 1, '0')
    val whole = digits.substring(0, digits.length - scale)
    val fraction = digits.substring(digits.length - scale).trimEnd('0')
    val text = (if (negative) "-" else "") + whole + (if (fraction.isEmpty()) "" else ".$fraction")
    val decimal = text.toDouble().toDecimal()
    // Exact or nothing: never a figure that differs from the string sum in its last digit.
    check(displayDigits(decimal) == text) { "Amount of scale $scale is not representable exactly" }
    return decimal
}
