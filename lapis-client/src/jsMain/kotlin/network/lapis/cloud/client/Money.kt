package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span

/**
 * Accounting UI wave -- the single, shared monetary-amount display convention, design decision D5
 * ("highest-risk formatting decision", per the approved design document). Every Accounting screen
 * calls [formatMoney]/[moneySpan] for every displayed amount; no screen interpolates a [Decimal]
 * into a string itself.
 *
 * [formatMoney] is deliberately the entire transform: `Decimal.toString()`'s own digits are never
 * touched -- no thousands-separator insertion, no decimal-comma localization, no rounding, so a
 * displayed figure can never silently diverge from what `AccountingService` actually computed (the
 * task's own "no client-side re-rounding/re-deriving" requirement). This mirrors
 * `ContributionsScreen.kt`'s existing bare-`Decimal`-interpolation precedent, with one deliberate
 * addition: a trailing `" €"` suffix. Rejected alternatives (so nobody re-litigates this later):
 * - Thousands separators / German decimal-comma -- would require either parsing the string
 *   (re-deriving, exactly what this wave forbids) or assuming a specific decimal-point format
 *   `Decimal.toString()` does not formally guarantee.
 * - No suffix at all (matching `ContributionsScreen`'s bare precedent exactly) -- fine for one
 *   field on one screen; rejected here because five screens show dozens of report totals, and an
 *   unlabelled number compounds "what unit is this" ambiguity.
 */
fun formatMoney(amount: Decimal): String = "${displayDigits(amount)} €"

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
    val nearestCent = kotlin.math.round(value * 100.0) / 100.0
    if (kotlin.math.abs(value - nearestCent) >= noiseTolerance(value)) return text
    return plainDecimal(nearestCent.toString()).removeSuffix(".0").let { if (it == "-0") "0" else it }
}

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
 * D6: no sign transform, no parentheses, no string inspection -- `Decimal.toString()`'s own
 * leading `-` (entirely server-controlled) is left exactly as-is inside [formatMoney]. The optional
 * red highlight is driven by a **typed** numeric comparison ([Decimal.toDouble] against `0.0`),
 * never by regex/string-inspecting the rendered text. [warnIfNegative] must only be passed `true`
 * for a field the underlying DTO's own KDoc documents as "may legitimately be negative" (e.g. a
 * GuV/Bilanz/four-sphere/cost-center result, a §62 AO reserve `allocated` delta) -- never for a
 * pure-magnitude field (`fundsReceived`, `annualTotal`, ...) that is never legitimately negative.
 */
fun Container.moneySpan(
    amount: Decimal,
    warnIfNegative: Boolean = false,
): Span =
    span(formatMoney(amount)) {
        if (warnIfNegative && amount.toDouble() < 0.0) addCssClass("text-danger")
    }

/**
 * LTR-Wirtschaft UI wave -- the LTR-denominated sibling of [formatMoney], design decision D2 (UI/UX
 * design review: "give LTR its own unmistakable visual identity ... too easy to misread as a
 * currency amount if someone's skimming" on a screen that also shows EUR, e.g. the Price-Oracle
 * donation-conversion result or Accounting cross-links). Same "never re-round/re-derive
 * `Decimal.toString()`" rule as [formatMoney] -- the transform is purely the trailing `" LTR"`
 * suffix, nothing else.
 */
fun formatLtr(amount: Decimal): String = "$amount LTR"

/**
 * D2: unlike [moneySpan] (plain text), [ltrSpan] renders as a bordered pill with a leading glyph --
 * reusing `StatusBadge.kt`'s existing `typeBadge` outline-pill grammar (LTR amounts don't
 * "progress" like a lifecycle status, so the outline-not-filled convention applies) rather than
 * inventing a new visual component, but adding the `◆` glyph so an LTR figure is never mistakable
 * for an adjacent [moneySpan] EUR figure at a glance, even with color perception impaired (the
 * glyph is a second, non-color channel, same WCAG 1.4.1 reasoning `StatusBadge.kt`'s own KDoc
 * documents for badge color). [warnIfNegative] mirrors [moneySpan]'s: a typed numeric comparison
 * against `0.0`, never string-inspecting the rendered text, and must only be passed `true` for a
 * field the underlying DTO documents as "may legitimately be negative" (e.g. a signed
 * [network.lapis.cloud.shared.domain.LtrLedgerEntryDto.amountLtr] row, never a pure-magnitude
 * balance).
 */
fun Container.ltrSpan(
    amount: Decimal,
    warnIfNegative: Boolean = false,
): Span {
    val color = if (warnIfNegative && amount.toDouble() < 0.0) "danger" else "primary"
    return span("◆ ${formatLtr(amount)}") {
        addCssClasses("badge rounded-pill border border-$color text-$color fw-bold")
    }
}
