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

/*
 * V1.9.202: THE Markdown text editor of the app (extracted from the V1.9.199 contact-hint editor of the travel-expense settings, which now
 * delegates here; two copies would drift). A text with the tabs "Schreiben" and "Vorschau" (same markup as the article editor). The preview is
 * rendered by THE SERVER (the [preview] lambda calls the RPC of the caller), never by the client, and shown through [renderAgreementBody] (the
 * one rich site); only the answer of the LATEST request is displayed. The same server pipeline renders what people finally see, so the preview
 * is exactly that.
 */

/** Handle on the editor: the field (for the value) and whether the text has to be saved as Markdown. */
internal class MarkdownTextEditorHandle(
    val field: LapisField,
    private val loadedValue: String,
    private val loadedIsMarkdown: Boolean,
) {
    /**
     * `true` (Markdown) as soon as the person changed the text (the new text is written as Markdown), otherwise the stored format: saving the
     * other fields of the page must NOT silently turn a legacy plain text into Markdown.
     */
    fun markdownToSend(): Boolean = if (normalize(field.value) != normalize(loadedValue)) true else loadedIsMarkdown

    private fun normalize(text: String): String = text.replace("\r\n", "\n").trim()
}

private fun counterText(
    text: String,
    maxLength: Int,
): String = gettext("%1 / %2 Zeichen", text.length, maxLength)

/**
 * Builds the editor into the form. [label] is the already translated label of the text area, [legacyHint] (a translated text, or `null`) is
 * shown while a legacy plain text is loaded, [preview] returns the sanitized HTML of a Markdown text (the server pipeline).
 */
internal fun LapisForm.markdownTextEditor(
    label: String,
    current: String,
    isMarkdown: Boolean,
    maxLength: Int,
    legacyHint: String?,
    rows: Int = 6,
    required: Boolean = false,
    preview: suspend (String) -> String,
): MarkdownTextEditorHandle {
    if (!isMarkdown && current.isNotBlank() && legacyHint != null) {
        panel.div(legacyHint, className = "alert alert-info small mb-0") { setAttribute("role", "note") }
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
            label = label,
            rows = rows,
            required = required,
            value = current,
            hint = counterText(current, maxLength),
            hintLive = true,
            host = writeHost,
            init = { it.maxlength = maxLength },
        )
    writeHost.p(
        tr(
            "Formatierung (Markdown): **fett**, *kursiv*, \"- \" für eine Aufzählung, [Linktext](https://beispiel.de). " +
                "Eine Leerzeile beginnt einen neuen Absatz. Bilder und eingebettetes HTML werden nicht dargestellt.",
        ),
    ) { addCssClasses("text-muted small mb-0") }
    field.subscribe { value -> field.setHintText(counterText(value, maxLength)) }

    val previewBox = previewHost.div(className = "lapis-agreement-preview border rounded p-2")
    var previewSerial = 0

    fun refreshPreview() {
        val text = field.value.replace("\r\n", "\n").trim()
        val mine = ++previewSerial
        previewBox.removeAll()
        when {
            text.isEmpty() -> previewBox.p(tr("Die Vorschau erscheint, sobald Sie Text eingeben.")) { addCssClasses("text-muted") }
            text.length > maxLength ->
                previewBox.p(tr("Für diesen Text ist keine Vorschau möglich. Bitte kürzen Sie ihn.")) { addCssClasses("text-muted") }
            else -> {
                previewBox.p(tr("Vorschau wird geladen…")) { addCssClasses("text-muted") }
                AppScope.launch {
                    val html =
                        try {
                            preview(text)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            null
                        }
                    // Only the answer of the LATEST request is shown.
                    if (mine == previewSerial) {
                        previewBox.removeAll()
                        if (html == null) {
                            previewBox.p(tr("Die Vorschau ist gerade nicht verfügbar.")) { addCssClasses("text-danger") }
                        } else {
                            previewBox.renderAgreementBody(text = text, format = AgreementTextFormat.MARKDOWN, renderedHtml = html)
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
    return MarkdownTextEditorHandle(field = field, loadedValue = current, loadedIsMarkdown = isMarkdown)
}
