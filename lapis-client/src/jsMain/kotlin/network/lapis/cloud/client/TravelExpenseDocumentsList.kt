package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Link
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto

/*
 * V1.9.199: the "Unterlagen und Beschlüsse" block beside the contact person on the travel-expense page -- one download link per
 * organisation-wide document. Plain information for every member: no uploader and no date, a glyph plus a text label for the type
 * (never colour alone). Titles are untrusted text and go through `untrusted*`/`sanitizeUntrustedI18nText`. Without documents the block
 * does not exist (BOARD/ADMIN get the "Unterlagen verwalten" link instead, so they can add the first one).
 */

/** Modifier class of the list block (read by the DOM tests). */
internal const val TE_DOCUMENTS_LIST_CLASS = "lapis-te-documents"

internal fun renderTravelExpenseDocumentsList(
    panel: Container,
    rates: TravelExpenseRatesDto,
) {
    val documents = sortTravelExpenseDocuments(rates.documents)
    if (documents.isEmpty() && !rates.canManageDocuments) return
    val box = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-3 $TE_DOCUMENTS_LIST_CLASS") }
    if (documents.isNotEmpty()) {
        box.h2(tr("Unterlagen und Beschlüsse")) { addCssClass("h6") }
        documents.forEach { document ->
            val isPdf = document.mimeType == TravelExpenseDocumentRules.MIME_PDF
            val typeLabel = travelExpenseDocumentTypeLabel(document.mimeType)
            val size = receiptSizeLabel(document.sizeBytes)
            val title = sanitizeUntrustedI18nText(document.title)
            val link = Link(label = "", url = TravelExpenseHttp.documentDownloadUrl(document.id), className = "lapis-te-doc-link")
            link.setAttribute("download", "")
            link.setAttribute("aria-label", resolvedAttributeText(gettext("%1 herunterladen (%2, %3)", title, typeLabel, size)))
            link.icon(if (isPdf) "far fa-file-pdf fa-fw" else "far fa-file-excel fa-fw") { setAttribute("aria-hidden", "true") }
            val text = link.div(className = "lapis-te-doc-text")
            text.span(title, className = "d-block")
            text.span("$typeLabel · $size", className = "d-block small text-muted")
            box.add(link)
        }
    }
    if (rates.canManageDocuments) {
        box
            .button(tr("Unterlagen verwalten"), style = ButtonStyle.LINK)
            .onClick { navigateTo(Routes.TRAVEL_EXPENSE_SETTINGS) }
    }
}
