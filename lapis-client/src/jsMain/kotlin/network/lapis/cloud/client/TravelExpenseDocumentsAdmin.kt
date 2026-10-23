package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.upload.Upload
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.TravelExpenseDocumentDto
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import org.w3c.files.File

/*
 * V1.9.199: the "Unterlagen für Mitglieder" section of the travel-expense settings page -- organisation-wide documents (Reisekostenordnung,
 * Beschlüsse, Formulare) that every member can download on the Reisekosten page. BOARD and ADMIN upload, rename and delete here; every
 * action takes effect at once (no "Speichern" of the form above is involved), which is why the section stands OUTSIDE the settings form.
 * Titles and file names are untrusted text: they reach the DOM only through `textColumn`, the `untrusted*` helpers or
 * `sanitizeUntrustedI18nText`.
 */

/** Content classes of the documents section (also read by the DOM tests). */
internal const val TE_DOCUMENTS_ADMIN_CLASS = "lapis-te-documents-admin"

/** Accepted `accept` values of the file input. */
internal val TRAVEL_EXPENSE_DOCUMENT_FILE_TYPES: List<String> = listOf(".pdf", ".xlsx", ".xls")

/** "PDF" or "Excel" for the type column; the MIME type is the sniffed one the server stored. */
internal fun travelExpenseDocumentTypeLabel(mimeType: String): String =
    if (mimeType == TravelExpenseDocumentRules.MIME_PDF) gettext("PDF") else gettext("Excel")

/** Locale-aware case-insensitive title order (the server sorts by plain lower-case title). */
internal fun sortTravelExpenseDocuments(documents: List<TravelExpenseDocumentDto>): List<TravelExpenseDocumentDto> =
    documents.sortedWith { a, b ->
        val result = a.title.asDynamic().localeCompare(b.title, undefined, js("({sensitivity: 'base'})")) as Int
        if (result != 0) result else a.id.compareTo(b.id)
    }

internal fun renderTravelExpenseDocumentsAdmin(
    panel: Container,
    rates: TravelExpenseRatesDto,
    reload: () -> Unit,
) {
    val section = panel.vPanel(spacing = 8) { addCssClass(TE_DOCUMENTS_ADMIN_CLASS) }
    section.h2(tr("Unterlagen für Mitglieder")) { addCssClass("h6") }
    section.p(
        tr(
            "Diese Dateien sehen alle Mitglieder auf der Seite Reisekosten, zum Beispiel die Reisekostenordnung, Beschlüsse oder Formulare.",
        ),
    ) { addCssClasses("text-muted small mb-0") }
    section.p(
        tr("Änderungen an dieser Stelle gelten sofort. Sie müssen dafür nicht oben auf Speichern klicken."),
    ) { addCssClasses("text-muted small mb-0") }

    val documents = sortTravelExpenseDocuments(rates.documents)
    val full = documents.size >= TravelExpenseDocumentRules.MAX_DOCUMENTS
    val addHost = section.vPanel(spacing = 6)
    val addButton = addHost.actionButton(ActionIcon.ADD, tr("Unterlage hinzufügen"), ButtonStyle.OUTLINEPRIMARY)
    val formHost = addHost.vPanel(spacing = 6) { hide() }
    if (full) {
        addButton.disabled = true
        addHost.div(
            gettext(
                "Es sind höchstens %1 Unterlagen möglich. Bitte löschen Sie zuerst eine.",
                TravelExpenseDocumentRules.MAX_DOCUMENTS,
            ),
            className = "text-muted small",
        )
    } else {
        buildDocumentUploadForm(formHost = formHost, addButton = addButton, reload = reload)
    }

    if (documents.isEmpty()) {
        section.p(tr("Noch keine Unterlagen.")) { addCssClasses("text-muted small") }
        return
    }
    section.dataTable(
        columns =
            listOf(
                textColumn<TravelExpenseDocumentDto>(title = tr("Titel"), primary = true) { it.title },
                DataColumn(title = tr("Typ")) { cell, document ->
                    val isPdf = document.mimeType == TravelExpenseDocumentRules.MIME_PDF
                    cell.icon(if (isPdf) "far fa-file-pdf fa-fw" else "far fa-file-excel fa-fw") { setAttribute("aria-hidden", "true") }
                    cell.span(" " + travelExpenseDocumentTypeLabel(document.mimeType))
                },
                textColumn<TravelExpenseDocumentDto>(title = tr("Größe"), numeric = true) { receiptSizeLabel(it.sizeBytes) },
                systemDateTimeColumn<TravelExpenseDocumentDto>(title = tr("Hochgeladen am")) { it.uploadedAt },
                textColumn<TravelExpenseDocumentDto>(title = tr("Hochgeladen von")) { document ->
                    document.uploaderName ?: trusted(tr("Ehemaliges Mitglied"))
                },
            ),
        rows = documents,
        actions = { cell, document -> renderDocumentActions(cell, document, reload) },
    )
}

private fun buildDocumentUploadForm(
    formHost: io.kvision.panel.VPanel,
    addButton: io.kvision.html.Button,
    reload: () -> Unit,
) {
    val form = formHost.lapisForm()
    val titleField =
        form.textField(
            label = tr("Titel"),
            hint = tr("Optional. Ohne Titel wird der Dateiname verwendet."),
            init = { it.maxlength = TravelExpenseDocumentRules.MAX_TITLE },
        )
    val message = form.panel.div(className = "text-danger small") { setAttribute("role", "alert") }
    val fileUpload: Upload = form.panel.upload(label = tr("Datei (PDF oder Excel, höchstens 10 MB)"), multiple = false)
    fileUpload.accept = TRAVEL_EXPENSE_DOCUMENT_FILE_TYPES
    var chosen: File? = null
    fileUpload.onEvent {
        change = {
            message.removeAll()
            val file = fileUpload.value.orEmpty().firstNotNullOfOrNull { fileUpload.getNativeFile(it) }
            val problem = file?.let { travelExpenseDocumentPrecheck(it.name, it.size.toLong()) }
            if (file != null && problem == null) {
                chosen = file
                if (titleField.value.isBlank()) titleField.setValue(TravelExpenseDocumentRules.titleFromFilename(file.name))
            } else {
                chosen = null
                if (problem != null) message.untrustedDiv(problem)
            }
        }
    }
    val uploadButton = newActionButton(ActionIcon.UPLOAD, tr("Hochladen"), ButtonStyle.PRIMARY)
    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY)
    form.buttons(primary = uploadButton, cancel = cancelButton)
    cancelButton.onClick {
        formHost.hide()
        addButton.show()
    }
    addButton.onClick {
        formHost.show()
        addButton.hide()
    }
    uploadButton.onClick {
        val file = chosen
        if (file == null) {
            message.removeAll()
            message.div(tr("Bitte wählen Sie eine Datei aus."))
            return@onClick
        }
        runGuardedAction(uploadButton) {
            when (val outcome = TravelExpenseHttp.uploadTravelExpenseDocument(file, titleField.value)) {
                is TravelExpenseUploadOutcome.Created -> {
                    notifySuccess(gettext("„%1“ wurde hochgeladen.", sanitizeUntrustedI18nText(titleField.value.ifBlank { file.name })))
                    reload()
                }
                is TravelExpenseUploadOutcome.Refused ->
                    notifyError(travelExpenseDocumentRefusalText(outcome.status, outcome.body, file.name))
            }
        }
    }
}

private fun renderDocumentActions(
    cell: Container,
    document: TravelExpenseDocumentDto,
    reload: () -> Unit,
) {
    val name = sanitizeUntrustedI18nText(document.title)
    val actions = cell.tableActionGroup()
    // R58 exception (named in the guideline): the download is a real link; the title goes into the accessible name.
    val link = actions.actionLink(ActionIcon.DOWNLOAD, tr("Herunterladen"), TravelExpenseHttp.documentDownloadUrl(document.id))
    link.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ herunterladen", name)))
    link.setAttribute("download", "")
    link.title = gettext("„%1“ herunterladen", name)
    actions.tableActionButton(ActionIcon.EDIT, gettext("„%1“ umbenennen", name)).onClick { renameDocumentDialog(document, reload) }
    actions
        .tableActionButton(ActionIcon.DELETE, gettext("„%1“ löschen", name), ButtonStyle.OUTLINEDANGER)
        .onClick { deleteDocumentDialog(document, reload) }
}

private fun renameDocumentDialog(
    document: TravelExpenseDocumentDto,
    reload: () -> Unit,
) {
    val modal = Modal(caption = tr("Unterlage umbenennen"))
    val form = modal.lapisForm()
    val titleField =
        form.textField(
            label = tr("Titel"),
            value = document.title,
            required = true,
            requiredMessage = tr("Bitte geben Sie einen Titel ein."),
            init = { it.maxlength = TravelExpenseDocumentRules.MAX_TITLE },
        )
    form.finish()
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    save.onClick {
        form.submit(save) {
            val title = TravelExpenseDocumentRules.normalizeTitle(titleField.value)
            if (title.isEmpty()) {
                form.showFormError(tr("Bitte geben Sie einen Titel ein."))
                return@submit
            }
            when (TravelExpenseHttp.renameTravelExpenseDocument(document.id, title)) {
                in 200..299 -> {
                    modal.hide()
                    notifySuccess(tr("Der Titel wurde geändert."))
                    reload()
                }
                404 -> {
                    modal.hide()
                    notifyError(tr("Die Unterlage gibt es nicht mehr."))
                    reload()
                }
                else -> notifyError(tr("Umbenennen fehlgeschlagen."))
            }
        }
    }
    modal.addButton(save)
    modal.show()
}

private fun deleteDocumentDialog(
    document: TravelExpenseDocumentDto,
    reload: () -> Unit,
) {
    val modal = Modal(caption = tr("Unterlage löschen"))
    modal.div(gettext("„%1“ wirklich löschen?", sanitizeUntrustedI18nText(document.title))) { addCssClass("fw-bold") }
    modal.div(tr("Die Datei verschwindet sofort für alle Mitglieder. Das lässt sich nicht rückgängig machen.")) {
        addCssClasses("text-muted small mt-1")
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val confirm = newActionButton(ActionIcon.DELETE, tr("Löschen"), ButtonStyle.DANGER)
    confirm.onClick {
        runGuardedAction(confirm) {
            when (TravelExpenseHttp.deleteTravelExpenseDocument(document.id)) {
                in 200..299, 404 -> {
                    modal.hide()
                    notifySuccess(gettext("„%1“ wurde gelöscht.", sanitizeUntrustedI18nText(document.title)))
                    reload()
                }
                else -> notifyError(tr("Löschen fehlgeschlagen."))
            }
        }
    }
    modal.addButton(confirm)
    modal.show()
}
