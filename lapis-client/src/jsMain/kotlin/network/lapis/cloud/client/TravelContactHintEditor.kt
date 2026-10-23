package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.TravelContactHintFormat
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/*
 * V1.9.199: the editor of the contact hint ("Mehr erfahren") on the travel-expense settings page -- a Markdown text with the tabs
 * "Schreiben" and "Vorschau" (same markup as the article editor). The preview is rendered by THE SERVER (`previewTravelContactHint`), never
 * by the client, and shown through [renderAgreementBody] (the one rich site); only the answer of the LATEST request is displayed. The same
 * server pipeline renders what members finally see, so the preview is exactly that.
 */

/** Handle on the editor: the field (for the value) and the format the save has to send. */
internal class TravelContactHintEditor(
    val field: LapisField,
    private val loadedValue: String,
    private val loadedFormat: TravelContactHintFormat,
) {
    /**
     * MARKDOWN as soon as the administration changed the text (the new text is written as Markdown), otherwise the stored format: saving the
     * other fields of the page must NOT silently turn a legacy plain text into Markdown.
     */
    fun formatToSend(): TravelContactHintFormat =
        if (normalizeHint(field.value) != normalizeHint(loadedValue)) TravelContactHintFormat.MARKDOWN else loadedFormat

    private fun normalizeHint(text: String): String = text.replace("\r\n", "\n").trim()
}

private fun counterText(text: String): String = gettext("%1 / %2 Zeichen", text.length, TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH)

internal fun LapisForm.travelContactHintEditor(
    current: String,
    format: TravelContactHintFormat,
): TravelContactHintEditor {
    if (format == TravelContactHintFormat.PLAIN && current.isNotBlank()) {
        panel.div(
            tr(
                "Der bisherige Text ist einfacher Text. Sobald Sie ihn ändern, wird er als Markdown gespeichert: Absätze trennt eine " +
                    "Leerzeile, Aufzählungen beginnen mit \"- \".",
            ),
            className = "alert alert-info small mb-0",
        ) { setAttribute("role", "note") }
    }
    val tabRow = panel.hPanel(spacing = 8) { setAttribute("role", "tablist") }
    val writeTab = tabRow.actionButton(ActionIcon.EDIT, tr("Schreiben"), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") }
    val previewTab =
        tabRow.actionButton(
            ActionIcon.VIEW,
            tr("Vorschau"),
            style = ButtonStyle.OUTLINEPRIMARY,
        ) { setAttribute("role", "tab") }
    val writeHost = panel.vPanel(spacing = 4) { setAttribute("role", "tabpanel") }
    val previewHost =
        panel.vPanel(spacing = 4) {
            setAttribute("role", "tabpanel")
            hide()
        }
    val field =
        textAreaField(
            label = tr("Hinweistext (Mehr erfahren)"),
            rows = 6,
            value = current,
            hint = counterText(current),
            hintLive = true,
            host = writeHost,
            init = { it.maxlength = TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH },
        )
    writeHost.p(
        tr(
            "Formatierung (Markdown): **fett**, *kursiv*, \"- \" für eine Aufzählung, [Linktext](https://beispiel.de). " +
                "Eine Leerzeile beginnt einen neuen Absatz. Bilder und eingebettetes HTML werden nicht dargestellt.",
        ),
    ) { addCssClasses("text-muted small mb-0") }
    field.subscribe { value -> field.setHintText(counterText(value)) }

    val preview = previewHost.div(className = "lapis-agreement-preview border rounded p-2")
    var previewSerial = 0

    fun refreshPreview() {
        val text = field.value.replace("\r\n", "\n").trim()
        val mine = ++previewSerial
        preview.removeAll()
        when {
            text.isEmpty() -> preview.p(tr("Die Vorschau erscheint, sobald Sie Text eingeben.")) { addCssClasses("text-muted") }
            text.length > TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH ->
                preview.p(tr("Für diesen Text ist keine Vorschau möglich. Bitte kürzen Sie ihn.")) { addCssClasses("text-muted") }
            else -> {
                preview.p(tr("Vorschau wird geladen…")) { addCssClasses("text-muted") }
                AppScope.launch {
                    val html =
                        try {
                            rpcService<ITravelExpenseService>().previewTravelContactHint(text)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            null
                        }
                    // Only the answer of the LATEST request is shown.
                    if (mine == previewSerial) {
                        preview.removeAll()
                        if (html == null) {
                            preview.p(tr("Die Vorschau ist gerade nicht verfügbar.")) { addCssClasses("text-danger") }
                        } else {
                            preview.renderAgreementBody(text = text, format = AgreementTextFormat.MARKDOWN, renderedHtml = html)
                        }
                    }
                }
            }
        }
    }

    fun activate(showPreview: Boolean) {
        writeTab.setAttribute("aria-selected", (!showPreview).toString())
        previewTab.setAttribute("aria-selected", showPreview.toString())
        if (showPreview) {
            writeHost.hide()
            previewHost.show()
            refreshPreview()
        } else {
            previewHost.hide()
            writeHost.show()
        }
    }
    writeTab.onClick { activate(showPreview = false) }
    previewTab.onClick { activate(showPreview = true) }
    activate(showPreview = false)
    return TravelContactHintEditor(field = field, loadedValue = current, loadedFormat = format)
}
