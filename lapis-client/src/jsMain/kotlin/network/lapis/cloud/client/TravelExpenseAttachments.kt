package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.upload.Upload
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseAttachmentDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus

/*
 * V1.9.198: the "Anlagen" section of a travel-expense report -- documents that belong to the whole trip (an invitation, an itinerary), as
 * opposed to the receipts of single cost lines. Editable in a draft (add, remove), read-only afterwards (download only). Names of the
 * files are untrusted text: they only ever reach the DOM through the `untrusted*` helpers or `sanitizeUntrustedI18nText`.
 */

private const val ATTACHMENT_COLUMNS = 3

/** The section, in [host]. [editable] == the report is still a DRAFT of the viewer; [onChanged] reloads the screen. */
internal fun renderTravelExpenseAttachments(
    host: Container,
    report: TravelExpenseReportDto,
    editable: Boolean,
    onChanged: () -> Unit,
) {
    host.h2(tr("Anlagen")) { addCssClass("h6") }
    host.p(
        tr(
            "Anlagen gehören zur ganzen Reise, zum Beispiel eine Einladung oder ein Reiseplan. Belege zu einzelnen Kosten " +
                "laden Sie bei der jeweiligen Kostenzeile hoch.",
        ),
    ) { addCssClasses("text-muted small mb-1") }
    val attachments = report.attachments
    // A withdrawn or rejected report loses its files on purpose (the names remain): there is nothing left to download.
    val filesDeleted = report.status == TravelExpenseReportStatus.WITHDRAWN || report.status == TravelExpenseReportStatus.REJECTED
    if (attachments.isEmpty()) {
        host.p(tr("Keine Anlagen.")) { addCssClasses("text-muted small") }
    } else {
        val table = host.standardTable(emptyList())
        table.addCssClass("lapis-te-attachments")
        table.addHeaderCell(plainHeaderCell(TableHeader(tr("Datei"))))
        table.addHeaderCell(plainHeaderCell(TableHeader(tr("Größe"), numeric = true)))
        table.addHeaderCell(HeaderCell(content = tr("Aktionen"), scope = Scope.COL) { addCssClass("visually-hidden") })
        attachments.forEach { attachment ->
            table.row {
                cell {
                    untrustedDiv(attachment.originalFilename, className = "text-truncate") {
                        title = sanitizeUntrustedI18nText(attachment.originalFilename)
                    }
                }
                numCell { cellText(receiptSizeLabel(attachment.sizeBytes)) }
                cell {
                    val name = sanitizeUntrustedI18nText(attachment.originalFilename)
                    val actions = tableActionGroup()
                    if (filesDeleted) {
                        div(tr("Datei nach Rückzug/Ablehnung gelöscht"), className = "text-muted small")
                    } else {
                        renderDownloadLink(actions, attachment)
                    }
                    if (editable) {
                        val remove = actions.actionButton(ActionIcon.REMOVE, tr("Entfernen"), ButtonStyle.OUTLINEDANGER, small = true)
                        remove.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ entfernen", name)))
                        remove.onClick {
                            // No confirmation, like the receipts of a line: the file can simply be added again while the report is a draft.
                            runGuardedAction(remove) {
                                val error = TravelExpenseHttp.deleteAttachment(attachment.id)
                                if (error != null) notifyError(error) else onChanged()
                            }
                        }
                    }
                }
            }
        }
    }
    if (editable) {
        renderAttachmentUpload(host, report, onChanged)
    } else {
        host.p(tr("Nach dem Einreichen können Anlagen nicht mehr geändert werden.")) { addCssClasses("text-muted small") }
    }
}

/**
 * R58 exception (named in the guideline): the download is a real link (the server answers `Content-Disposition: attachment`, so the page
 * stays); it keeps its visible text, the file name goes into the accessible name.
 */
private fun renderDownloadLink(
    actions: Container,
    attachment: TravelExpenseAttachmentDto,
) {
    val link = actions.actionLink(ActionIcon.DOWNLOAD, tr("Herunterladen"), TravelExpenseHttp.attachmentDownloadUrl(attachment.id))
    link.setAttribute(
        "aria-label",
        resolvedAttributeText(gettext("„%1“ herunterladen", sanitizeUntrustedI18nText(attachment.originalFilename))),
    )
}

private fun renderAttachmentUpload(
    host: Container,
    report: TravelExpenseReportDto,
    onChanged: () -> Unit,
) {
    val max = TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT
    val free = max - report.attachments.size
    val block = host.vPanel(spacing = 4)
    block.div(
        gettext("PDF, JPEG oder PNG, höchstens 10 MB pro Datei, bis zu %1 Anlagen.", max),
        className = "text-muted small",
    )
    val status = UploadStatusRegion(block)
    val message = block.div(className = "text-danger small") { setAttribute("role", "alert") }
    val fileUpload: Upload = block.upload(label = tr("Anlage hinzufügen"), multiple = true)
    fileUpload.accept = TRAVEL_EXPENSE_FILE_TYPES
    if (free <= 0) {
        fileUpload.disabled = true
        block.div(gettext("Höchstens %1 Anlagen pro Abrechnung.", max), className = "text-muted small")
        return
    }
    var running = false
    fileUpload.onEvent {
        change = {
            val chosen = fileUpload.value.orEmpty().mapNotNull { fileUpload.getNativeFile(it) }
            fileUpload.clearInput()
            if (chosen.isNotEmpty() && running) {
                message.removeAll()
                message.div(tr("Ein Upload läuft noch. Bitte wählen Sie die Dateien danach erneut aus."))
            } else if (chosen.isNotEmpty()) {
                running = true
                AppScope.launchUploads(chosen, free, report.id, status, message) {
                    running = false
                    onChanged()
                }
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchUploads(
    chosen: List<org.w3c.files.File>,
    free: Int,
    reportId: String,
    status: UploadStatusRegion,
    message: io.kvision.html.Div,
    done: () -> Unit,
) {
    launch {
        message.removeAll()
        val accepted = chosen.take(free)
        if (chosen.size > free) {
            message.div(gettext("Es sind nur noch %1 Anlagen möglich; die übrigen Dateien wurden nicht übernommen.", free))
        }
        var uploaded = 0
        accepted.forEach { file ->
            val problem = travelExpenseFilePrecheck(file)
            if (problem != null) {
                status.set(file.name, gettext("Fehlgeschlagen: %1", problem), file)
                return@forEach
            }
            status.set(file.name, tr("Wird hochgeladen…"), file)
            when (val outcome = TravelExpenseHttp.uploadAttachment(reportId, file)) {
                is TravelExpenseUploadOutcome.Created -> {
                    uploaded++
                    status.set(file.name, tr("Hochgeladen"), file)
                }
                is TravelExpenseUploadOutcome.Refused ->
                    status.set(file.name, gettext("Fehlgeschlagen: %1", travelExpenseUploadRefusalText(outcome.status, file.name)), file)
            }
        }
        if (uploaded > 0) {
            notifySuccess(ngettext("%1 Anlage hochgeladen.", "%1 Anlagen hochgeladen.", uploaded, uploaded))
        }
        done()
    }
}
