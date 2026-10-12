package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.TravelExpenseCalculator

/**
 * Welle V1.9.122 -- the sticky totals bar of the travel expense editor (right side on a desktop, bottom edge on a phone, see `.lapis-te-totals`
 * in `theme.css`): subtotal, deductions, advance and the amount that is paid out.
 *
 * It is the ONLY live region of the editor. The visible figures change at once (and are `aria-hidden`, so a screen reader is not flooded while
 * someone types); one visually hidden sentence carries the same numbers and is replaced [LIVE_DEBOUNCE_MS] after the last change -- a screen reader
 * announces the result of a pause, not every keystroke. While the figures are not saved yet ([dirty]) the bar says so ("Vorschau"): the server
 * recomputes on save and is the only authority.
 */
internal class TravelExpenseTotalsBar(
    host: Container,
) {
    private val root: Div = host.div(className = "lapis-te-totals border rounded p-2 bg-body")
    private val previewBadge: Span
    private val subtotal: Span
    private val deduction: Span
    private val advance: Span
    private val payout: Span
    private val advanceWarning: Div
    private val live: Span
    private var timer: Int? = null

    init {
        root.setAttribute("role", "status")
        root.setAttribute("aria-live", "polite")
        root.setAttribute("aria-atomic", "true")
        previewBadge = root.span(tr("Vorschau – nicht gespeichert"), className = "badge text-bg-warning mb-1") { hide() }
        subtotal = row(tr("Zwischensumme"))
        deduction = row(tr("Abzüge"))
        advance = row(tr("Vorschuss"))
        payout = row(tr("Auszahlungsbetrag"), strong = true)
        advanceWarning =
            root.div(
                tr("Der Vorschuss ist höher als die Abrechnung. Bitte wenden Sie sich an den Ansprechpartner."),
                className = "small text-danger",
            ) {
                hide()
            }
        live = root.span(className = "visually-hidden")
    }

    private fun row(
        label: String,
        strong: Boolean = false,
    ): Span {
        val line = root.div(className = "d-flex justify-content-between gap-3" + if (strong) " fw-bold border-top pt-1 mt-1" else "")
        line.span(label).apply { setAttribute("aria-hidden", "true") }
        return line.span(className = "lapis-tnum").apply { setAttribute("aria-hidden", "true") }
    }

    /** Shows [totals]; [dirty] marks the figures as an unsaved preview. */
    fun show(
        totals: TravelExpenseCalculator.Totals,
        dirty: Boolean,
    ) {
        subtotal.content = formatMoney(centsToDecimal(totals.subtotalCents))
        deduction.content = minusMoney(totals.deductionCents)
        advance.content = minusMoney(totals.advanceCents)
        payout.content = formatMoney(centsToDecimal(totals.payoutCents))
        if (dirty) previewBadge.show() else previewBadge.hide()
        if (totals.advanceExceedsTotal) advanceWarning.show() else advanceWarning.hide()
        timer?.let { window.clearTimeout(it) }
        val sentence = travelExpenseTotalsSentence(totals, dirty)
        timer = window.setTimeout({ live.content = sentence }, LIVE_DEBOUNCE_MS)
    }

    /** "− 5,60 €" for a positive amount (a deduction shown as a deduction, never in an error colour), the plain zero otherwise. */
    private fun minusMoney(cents: Long): String {
        val money = formatMoney(centsToDecimal(cents))
        return if (cents > 0) gettext("− %1", money) else money
    }

    private companion object {
        const val LIVE_DEBOUNCE_MS = 500
    }
}

/** The sentence the live region carries (resolved plain text, never a `tr()` marker). */
internal fun travelExpenseTotalsSentence(
    totals: TravelExpenseCalculator.Totals,
    dirty: Boolean,
): String {
    val base =
        gettext(
            "Zwischensumme %1, Abzüge %2, Vorschuss %3, Auszahlungsbetrag %4.",
            formatMoney(centsToDecimal(totals.subtotalCents)),
            formatMoney(centsToDecimal(totals.deductionCents)),
            formatMoney(centsToDecimal(totals.advanceCents)),
            formatMoney(centsToDecimal(totals.payoutCents)),
        )
    return if (dirty) gettext("Vorschau, nicht gespeichert. %1", base) else base
}
