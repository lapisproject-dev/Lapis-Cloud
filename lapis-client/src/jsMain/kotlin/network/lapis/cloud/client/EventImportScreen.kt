package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.text.TextArea
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventImportPreviewDto
import network.lapis.cloud.shared.domain.EventImportPreviewRowDto
import network.lapis.cloud.shared.domain.EventImportRowStatus
import network.lapis.cloud.shared.rpc.IEventImportService

/** Mirrors `EventImportPolicy.MAX_PAYLOAD_BYTES` -- only a convenience pre-check, the server (and its body-size guard) stay authoritative. */
internal const val EVENT_IMPORT_MAX_BYTES = 2 * 1024 * 1024

/**
 * Welle V1.9.82 "Veranstaltungs-Feed, Archiv und Import" -- `/events/import`, BOARD/ADMIN. Two views on ONE page, no stepper: (1) paste the
 * JSON and ask for a preview, (2) the preview table with the confirm button. The text and `payloadSha256` of the preview are held
 * unchanged and sent back with the commit (the server rejects the commit if the text changed in between). Nothing is written before the
 * confirm button; any error row blocks it (all or nothing).
 */
fun renderEventImportScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 1100.px
            marginTop = 24.px
        }
    root.pageHeader(
        tr("Frühere Veranstaltungen importieren"),
        subtitle =
            tr(
                "Fügen Sie eine JSON-Liste vergangener Veranstaltungen ein. Vor dem Import sehen Sie eine Vorschau, es wird noch nichts gespeichert.",
            ),
    )
    root.div(gettext("Zeiten in Ortszeit (%1). Titelbilder werden nicht importiert.", OrganizationTime.zoneId)) {
        addCssClasses("text-muted small")
    }

    val jsonInput: TextArea = root.textArea(rows = 14, label = tr("Import-Daten (JSON)"))
    jsonInput.setStyle("font-family", "monospace")
    val message: Div =
        root.div {
            addCssClasses("small")
            setAttribute("role", "status")
        }
    val previewHost = root.vPanel(spacing = 8)
    val previewButton = root.actionButton(ActionIcon.VIEW, tr("Vorschau prüfen"), style = ButtonStyle.PRIMARY)

    var committed = false

    fun showMessage(text: String?) {
        if (text == null) {
            message.content = ""
            message.hide()
        } else {
            message.content = text
            message.show()
        }
    }
    showMessage(null)

    fun backToEditing() {
        previewHost.removeAll()
        jsonInput.disabled = false
        previewButton.show()
        showMessage(null)
    }

    fun renderPreview(
        preview: EventImportPreviewDto,
        sentText: String,
    ) {
        previewHost.removeAll()
        jsonInput.disabled = true
        previewButton.hide()
        previewHost.div(
            gettext("%1 werden angelegt · %2 übersprungen · %3 Fehler", preview.createCount, preview.skipCount, preview.errorCount),
        ) { addCssClasses("fw-bold") }
        previewHost.plainDataTable(
            columns = eventImportColumns(),
            rows = preview.rows,
            label = tr("Vorschau des Imports"),
        )
        val buttons = previewHost.hPanel(spacing = 8)
        buttons.actionButton(
            ActionIcon.BACK,
            tr("Zurück zum Bearbeiten"),
            style = ButtonStyle.OUTLINESECONDARY,
        ) { onClick { backToEditing() } }
        val commitButton: Button =
            buttons.actionButton(
                ActionIcon.UPLOAD,
                gettext("%1 Veranstaltungen importieren", preview.createCount),
                style = ButtonStyle.PRIMARY,
            )
        commitButton.disabled = preview.errorCount > 0 || preview.createCount == 0 || committed
        commitButton.onClick {
            commitButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IEventImportService>().commitEventImport(sentText, preview.payloadSha256) }
                if (result == null) {
                    commitButton.disabled = false
                    return@launch
                }
                committed = true
                previewHost.removeAll()
                jsonInput.disabled = true
                showMessage(gettext("%1 Veranstaltungen angelegt, %2 übersprungen.", result.created, result.skipped))
                notifySuccess(gettext("%1 Veranstaltungen angelegt, %2 übersprungen.", result.created, result.skipped))
            }
        }
    }

    previewButton.onClick {
        // The text is sent EXACTLY as typed (no trim/normalize): the server hashes this very string and the commit must match it.
        val text = jsonInput.value.orEmpty()
        if (text.isBlank()) {
            showMessage(tr("Bitte zuerst die Import-Daten einfügen."))
            return@onClick
        }
        if (text.encodeToByteArray().size > EVENT_IMPORT_MAX_BYTES) {
            showMessage(tr("Die Daten sind zu groß (maximal 2 MiB)."))
            return@onClick
        }
        showMessage(null)
        previewButton.disabled = true
        AppScope.launch {
            val preview = guarded { rpcService<IEventImportService>().previewEventImport(text) }
            previewButton.disabled = false
            if (preview != null) renderPreview(preview, text)
        }
    }
}

private fun eventImportColumns(): List<DataColumn<EventImportPreviewRowDto>> =
    listOf(
        DataColumn(
            title = tr("Status"),
            primary = true,
            cell = { cell, row ->
                when (row.status) {
                    EventImportRowStatus.CREATE -> cell.importStatusCell("✓", tr("Wird angelegt"), "text-success")
                    EventImportRowStatus.SKIP_SLUG_EXISTS -> cell.importStatusCell("↷", tr("Übersprungen"), "text-secondary")
                    EventImportRowStatus.ERROR -> cell.importStatusCell("!", tr("Fehler"), "text-danger fw-bold")
                }
            },
        ),
        textColumn<EventImportPreviewRowDto>(title = tr("Beginn")) { row -> row.startsAt?.let { formatDateTime(it) }.orEmpty() },
        textColumn<EventImportPreviewRowDto>(title = tr("Titel")) { it.title.orEmpty() },
        textColumn<EventImportPreviewRowDto>(title = tr("Slug"), cssClasses = "text-muted small") { it.slug.orEmpty() },
        textColumn<EventImportPreviewRowDto>(title = tr("Hinweis")) { row ->
            when (row.status) {
                EventImportRowStatus.SKIP_SLUG_EXISTS -> trusted(tr("Slug existiert bereits, wird nicht verändert."))
                else -> (row.reasons + row.hints).joinToString(" ")
            }
        },
    )

/** Symbol AND word: the status is never carried by a symbol or a colour alone. Both texts are fixed developer texts, never data. */
private fun Container.importStatusCell(
    symbol: String,
    label: String,
    cssClasses: String,
) {
    span("") { addCssClasses(cssClasses) }.also {
        it.span(symbol) { setAttribute("aria-hidden", "true") }
        it.span(" ")
        it.span(label)
    }
}
