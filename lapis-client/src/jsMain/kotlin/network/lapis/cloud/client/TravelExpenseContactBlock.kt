package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.TravelContactHintFormat
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto

/** Binds to the JS global `encodeURIComponent` -- no Kotlin/JS stdlib wrapper (same one-liner as `LoginScreen.kt`). */
private external fun encodeURIComponent(value: String): String

/**
 * Welle V1.9.122 -- the "Ihr Ansprechpartner für Reisekosten" block at the top of the travel expense
 * screen. The contact person is entered by the instance administration (route
 * `/travel-expense-settings`); nothing is ever pre-filled or invented here.
 *
 * Every operator-entered value is rendered as TEXT (the `untrusted*` helpers), the hint text with its
 * line breaks preserved by CSS (`white-space: pre-line`), never as markup. EXCEPTION (V1.9.199): a MARKDOWN hint is shown as the
 * server-rendered, sanitized HTML through [renderAgreementBody] -- the one rich site, no own `rich = true` here. The `mailto:` address is
 * percent-encoded, the `tel:` link carries only `+` and digits.
 */
internal fun renderTravelExpenseContactBlock(
    panel: SimplePanel,
    rates: TravelExpenseRatesDto,
) {
    val contact = rates.contact
    val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3 lapis-te-contact") }
    box.h2(tr("Ihr Ansprechpartner für Reisekosten")) { addCssClass("h6") }
    if (travelExpenseContactIsEmpty(contact)) {
        box.p(tr("Kontaktdaten werden noch ergänzt.")) { addCssClasses("text-muted small mb-0") }
        if (rates.canEditSettings) {
            box
                .button(
                    tr("In den Einstellungen eintragen"),
                    style = ButtonStyle.LINK,
                ).onClick { navigateTo(Routes.TRAVEL_EXPENSE_SETTINGS) }
        }
    } else {
        contact.name?.let { box.untrustedDiv(it, className = "fw-bold") }
        contact.email?.let { email ->
            box.untrustedLink(email, url = "mailto:${encodeURIComponent(email)}", className = "small")
        }
        contact.phone?.let { phone ->
            box.untrustedLink(phone, url = "tel:${travelExpensePhoneHref(phone)}", className = "small")
        }
    }
    box.p(tr("Reisekosten rechnen Sie vorrangig hier ab.")) { addCssClasses("small mb-0 mt-1") }
    val details = Tag(TAG.DETAILS, className = "small")
    details.add(Tag(TAG.SUMMARY, tr("Mehr erfahren"), className = "text-primary"))
    val hint = contact.hint
    if (hint != null) {
        val html = contact.hintHtml
        if (contact.hintFormat == TravelContactHintFormat.MARKDOWN && html != null && html.startsWith("<")) {
            // V1.9.199: the server-rendered, sanitized Markdown goes through the ONE rich site for agreement texts (agreementHtml).
            val hintBox = Div(className = "lapis-te-contact-hint lapis-te-contact-hint--md mt-1")
            hintBox.renderAgreementBody(text = hint, format = AgreementTextFormat.MARKDOWN, renderedHtml = html)
            details.add(hintBox)
        } else {
            val hintBox = Div(className = "lapis-te-contact-hint mt-1")
            untrustedContent(hintBox, hint)
            details.add(hintBox)
        }
    } else {
        details.add(Div(tr("Alternativ: alte Excel-Vorlage oder E-Mail an den Finanzvorstand."), className = "mt-1"))
    }
    box.add(details)
}

internal fun travelExpenseContactIsEmpty(contact: TravelExpenseContactDto): Boolean =
    contact.name == null && contact.email == null && contact.phone == null

/** Only `+` and digits survive (`+49 (531) 12 34` -> `+4953112 34` without the blank), so the `tel:` link can never carry a payload. */
internal fun travelExpensePhoneHref(phone: String): String = phone.filter { it == '+' || it.isDigit() }
