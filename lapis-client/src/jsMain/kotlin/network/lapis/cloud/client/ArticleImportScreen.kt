package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.text.Text
import io.kvision.form.text.TextArea
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
import network.lapis.cloud.shared.domain.ArticleImportPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportPreviewRowDto
import network.lapis.cloud.shared.domain.ArticleImportRowStatus
import network.lapis.cloud.shared.rpc.IArticleImportService

/** Mirrors `ArticleImportPolicy.MAX_PAYLOAD_BYTES` -- only a convenience pre-check, the server (and its body-size guard) stay authoritative. */
internal const val ARTICLE_IMPORT_MAX_BYTES = 2 * 1024 * 1024

/** Mirrors `ArticleImportPolicy.MAX_ORGANIZATION_LABELS`. */
internal const val ARTICLE_IMPORT_MAX_LABELS = 5

/**
 * Splits the organization-name input ("PdV, Partei der Vernunft") into at most [ARTICLE_IMPORT_MAX_LABELS] distinct, trimmed, non-empty names of
 * at most [ARTICLE_BYLINE_MAX] characters. Pure and unit-testable. Names beyond the limit are dropped here only for display; the screen refuses
 * to ask for a preview while the input holds too many.
 */
internal fun parseOrganizationLabels(raw: String): List<String> =
    raw
        .split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * Welle V1.9.105 "Artikel-Import" -- `/articles/import`, BOARD/ADMIN. Same two views on ONE page as [renderEventImportScreen]: paste the JSON
 * and ask for a preview, then the preview table with the confirm button. The text and `payloadSha256` of the preview are held unchanged and
 * sent back with the commit (the server rejects the commit if the text changed in between). Nothing is written before the confirm button;
 * any error row blocks it (all or nothing).
 *
 * The board names the organization names it accepts as an author line (default: the organization's own name from the branding): a line
 * that equals none of them is flagged "may be a personal name -- clarify consent" in the preview.
 */
fun renderArticleImportScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 1100.px
            marginTop = 24.px
        }
    root.pageHeader(
        tr("Frühere Artikel importieren"),
        subtitle =
            tr(
                "Fügen Sie eine JSON-Liste früherer Artikel ein. Vor dem Import sehen Sie eine Vorschau, es wird noch nichts gespeichert.",
            ),
    )
    root.div(gettext("Veröffentlichungsdaten in Ortszeit (%1). Titelbilder werden nicht importiert.", OrganizationTime.zoneId)) {
        addCssClasses("text-muted small")
    }

    // The two inputs are fields of the form grammar (labelled, wired for errors and aria); the preview and commit buttons are page actions of
    // their own, so the form is only closed (`finish()`), not submitted.
    val form = root.lapisForm()
    val jsonField = form.textAreaField(label = tr("Import-Daten (JSON)"), rows = 14)
    val jsonInput = jsonField.control as TextArea
    jsonInput.setStyle("font-family", "monospace")

    val labelsBox = form.panel.vPanel(spacing = 4)
    val labelsField =
        form.textField(
            label = tr("Organisationsnamen als Autorenzeile (durch Komma getrennt, höchstens 5)"),
            value = Branding.title,
            host = labelsBox,
            init = { it.maxlength = ARTICLE_IMPORT_MAX_LABELS * (ARTICLE_BYLINE_MAX + 2) },
        )
    val labelsInput = labelsField.control as Text
    val labelChips = labelsBox.hPanel(spacing = 6) { addCssClasses("flex-wrap") }
    form.finish()

    fun renderChips() {
        labelChips.removeAll()
        parseOrganizationLabels(labelsField.value).forEach { labelChips.typeBadge(sanitizeUntrustedI18nText(it), "secondary") }
    }
    renderChips()
    labelsField.subscribe { renderChips() }

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
        labelsInput.disabled = false
        previewButton.show()
        showMessage(null)
    }

    fun renderPreview(
        preview: ArticleImportPreviewDto,
        sentText: String,
    ) {
        previewHost.removeAll()
        jsonInput.disabled = true
        labelsInput.disabled = true
        previewButton.hide()
        previewHost.div(
            gettext("%1 werden angelegt · %2 übersprungen · %3 Fehler", preview.createCount, preview.skipCount, preview.errorCount),
        ) { addCssClasses("fw-bold") }
        previewHost.plainDataTable(
            columns = articleImportColumns(),
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
                gettext("%1 Artikel importieren", preview.createCount),
                style = ButtonStyle.PRIMARY,
            )
        commitButton.disabled = preview.errorCount > 0 || preview.createCount == 0 || committed
        commitButton.onClick {
            commitButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IArticleImportService>().commitArticleImport(sentText, preview.payloadSha256) }
                if (result == null) {
                    commitButton.disabled = false
                    return@launch
                }
                committed = true
                previewHost.removeAll()
                jsonInput.disabled = true
                showMessage(gettext("%1 Artikel angelegt, %2 übersprungen.", result.created, result.skipped))
                notifySuccess(gettext("%1 Artikel angelegt, %2 übersprungen.", result.created, result.skipped))
            }
        }
    }

    previewButton.onClick {
        // The text is sent EXACTLY as typed (no trim/normalize): the server hashes this very string and the commit must match it.
        val text = jsonField.value
        if (text.isBlank()) {
            showMessage(tr("Bitte zuerst die Import-Daten einfügen."))
            return@onClick
        }
        if (text.encodeToByteArray().size > ARTICLE_IMPORT_MAX_BYTES) {
            showMessage(tr("Die Daten sind zu groß (maximal 2 MiB)."))
            return@onClick
        }
        val labels = parseOrganizationLabels(labelsField.value)
        if (labels.size > ARTICLE_IMPORT_MAX_LABELS || labels.any { it.length > ARTICLE_BYLINE_MAX }) {
            showMessage(tr("Bitte höchstens 5 Organisationsnamen mit jeweils höchstens 120 Zeichen angeben."))
            return@onClick
        }
        showMessage(null)
        previewButton.disabled = true
        AppScope.launch {
            val preview = guarded { rpcService<IArticleImportService>().previewArticleImport(text, labels) }
            previewButton.disabled = false
            if (preview != null) renderPreview(preview, text)
        }
    }
}

/** Date part only for a midnight value ("2024-05-05T00:00" -> "2024-05-05"), else "2024-05-05 19:30". The value is a naive organization wall-clock string. */
internal fun articleImportDateText(naive: String?): String =
    when {
        naive == null -> ""
        naive.endsWith("T00:00") -> naive.removeSuffix("T00:00")
        else -> naive.replace('T', ' ')
    }

private fun articleImportColumns(): List<DataColumn<ArticleImportPreviewRowDto>> =
    listOf(
        DataColumn(
            title = tr("Status"),
            primary = true,
            cell = { cell, row ->
                when (row.status) {
                    ArticleImportRowStatus.CREATE -> cell.articleImportStatusCell("✓", tr("Wird angelegt"), "text-success")
                    ArticleImportRowStatus.SKIP_SLUG_EXISTS -> cell.articleImportStatusCell("↷", tr("Übersprungen"), "text-secondary")
                    ArticleImportRowStatus.ERROR -> cell.articleImportStatusCell("!", tr("Fehler"), "text-danger fw-bold")
                }
            },
        ),
        textColumn<ArticleImportPreviewRowDto>(title = tr("Veröffentlicht am")) { articleImportDateText(it.publishedAt) },
        textColumn<ArticleImportPreviewRowDto>(title = tr("Titel")) { it.title.orEmpty() },
        textColumn<ArticleImportPreviewRowDto>(title = tr("Autorenzeile")) { it.byline.orEmpty() },
        textColumn<ArticleImportPreviewRowDto>(title = tr("Slug"), cssClasses = "text-muted small") { it.slug.orEmpty() },
        DataColumn(
            title = tr("Hinweis"),
            cell = { cell, row ->
                // One line per sentence: a translated sentence is a `tr()` marker and must stay the whole content of its own element.
                (row.reasons + row.hints).forEach { sentence ->
                    cell.cellText(translateArticleImportSentence(sentence), "d-block")
                }
            },
        ),
    )

/** The fixed sentences `ArticleImportPolicy`/`ArticleImporter` send as hints, shown in the viewer's language. Anything else stays German text. */
internal fun translateArticleImportSentence(sentence: String): Any =
    when (sentence) {
        "Slug existiert bereits, wird nicht verändert." -> trusted(tr("Slug existiert bereits, wird nicht verändert."))
        "Ohne Titelbild – nach dem Import einzeln hochladen." -> trusted(tr("Ohne Titelbild – nach dem Import einzeln hochladen."))
        "Autorenzeile ist kein Organisationsname – Einwilligung klären." ->
            trusted(tr("Autorenzeile ist kein Organisationsname – Einwilligung klären."))
        "Enthält HTML – wird als Text angezeigt." -> trusted(tr("Enthält HTML – wird als Text angezeigt."))
        else -> sentence
    }

/** Symbol AND word: the status is never carried by a symbol or a colour alone. Both texts are fixed developer texts, never data. */
private fun Container.articleImportStatusCell(
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
