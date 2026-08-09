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
fun formatMoney(amount: Decimal): String = "$amount €"

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
