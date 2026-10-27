package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.upload.Upload
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.i
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.MailingAttachmentDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.w3c.files.File

/*
 * V1.9.232: the "Anhänge" section of a mailing message. Editable while the message is a DRAFT (upload, remove), read-only with download
 * afterwards. Attachments are real MIME attachments of every recipient mail; the board downloads them here, members never do. File names
 * are untrusted text: they only reach the DOM through the `untrusted*` helpers or `sanitizeUntrustedI18nText`.
 */

private const val MIB = 1024L * 1024L

/** The section, in [host]. Only a DRAFT is editable; a sent message without attachments shows nothing at all. */
internal fun renderMailingAttachments(
    host: Container,
    message: MailingMessageDto,
    onChanged: () -> Unit,
) {
    val editable = message.status == MailingMessageStatus.DRAFT
    val attachments = message.attachments
    if (!editable && attachments.isEmpty()) return
    val section = host.vPanel(spacing = 4) { addCssClass("lapis-mailing-attachments-section") }
    section.h2(tr("Anhänge")) { addCssClass("h6") }
    if (attachments.isEmpty()) {
        section.p(tr("Keine Anhänge.")) { addCssClasses("text-muted small") }
    } else {
        renderAttachmentTable(section, attachments, editable, onChanged)
    }
    if (editable) {
        renderMailingAttachmentUpload(section, message, onChanged)
    } else {
        section.p(tr("Nach dem Senden können Anhänge nicht mehr geändert werden.")) { addCssClasses("text-muted small") }
    }
}

private fun renderAttachmentTable(
    section: Container,
    attachments: List<MailingAttachmentDto>,
    editable: Boolean,
    onChanged: () -> Unit,
) {
    val table = section.standardTable(emptyList())
    table.addCssClass("lapis-mailing-attachments")
    table.addHeaderCell(plainHeaderCell(TableHeader(tr("Datei"))))
    table.addHeaderCell(plainHeaderCell(TableHeader(tr("Größe"), numeric = true)))
    table.addHeaderCell(HeaderCell(content = tr("Aktionen"), scope = Scope.COL) { addCssClass("visually-hidden") })
    attachments.forEach { attachment ->
        table.row {
            cell {
                i(className = fileTypeIcon(attachment.mimeType) + " me-1") { setAttribute("aria-hidden", "true") }
                untrustedDiv(attachment.fileName, className = "text-truncate") { title = sanitizeUntrustedI18nText(attachment.fileName) }
            }
            numCell { cellText(formatFileSize(attachment.sizeBytes)) }
            cell {
                val name = sanitizeUntrustedI18nText(attachment.fileName)
                val actions = tableActionGroup()
                if (attachment.fileAvailable) {
                    // R58 exception (named in the guideline): a real link, the server answers `Content-Disposition: attachment`.
                    val link = actions.actionLink(ActionIcon.DOWNLOAD, tr("Herunterladen"), MailingHttp.downloadUrl(attachment.id))
                    link.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ herunterladen", name)))
                } else {
                    div(tr("Datei gelöscht"), className = "text-muted small")
                }
                if (editable) {
                    val remove = actions.actionButton(ActionIcon.REMOVE, tr("Entfernen"), ButtonStyle.OUTLINEDANGER, small = true)
                    remove.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ entfernen", name)))
                    remove.onClick {
                        // No confirmation: the file can simply be added again while the message is a draft.
                        runGuardedAction(remove) {
                            val code = MailingHttp.deleteAttachment(attachment.id)
                            if (code != null) notifyError(mailingAttachmentRefusalText(code, attachment.fileName)) else onChanged()
                        }
                    }
                }
            }
        }
    }
}

private fun renderMailingAttachmentUpload(
    section: Container,
    message: MailingMessageDto,
    onChanged: () -> Unit,
) {
    val max = AttachmentRules.MAX_FILES
    val free = max - message.attachments.size
    val total = message.attachments.sumOf { it.sizeBytes }
    section.div(
        gettext("%1 von 5 Dateien, zusammen %2 von 20 MB.", message.attachments.size, formatFileSize(total)),
        className = "small",
    )
    section.div(
        tr(
            "Erlaubt sind PDF, Bilder (PNG, JPEG, GIF, WebP), Word, Excel, PowerPoint, OpenDocument, TXT und CSV: " +
                "höchstens 5 Dateien, 10 MB pro Datei, zusammen 20 MB.",
        ),
        className = "text-muted small",
    )
    section.div(
        tr("Anhänge gehen an alle Empfänger dieser Nachricht. Lapis löscht die Dateien 180 Tage nach dem Senden."),
        className = "text-muted small",
    )
    section.div(
        tr("Lapis prüft Anhänge nicht auf Viren. Bitte laden Sie nur Dateien hoch, denen Sie vertrauen."),
        className = "text-muted small",
    )
    val status = UploadStatusRegion(section)
    val problems = section.div(className = "text-danger small") { setAttribute("role", "alert") }
    val fileUpload: Upload = section.upload(label = tr("Dateien anhängen"), multiple = true)
    fileUpload.accept = AttachmentRules.acceptAttribute().split(',')
    if (free <= 0) {
        fileUpload.disabled = true
        section.div(gettext("Höchstens %1 Anhänge pro Nachricht.", max), className = "text-muted small")
        return
    }
    var running = false
    fileUpload.onEvent {
        change = {
            val chosen = fileUpload.value.orEmpty().mapNotNull { fileUpload.getNativeFile(it) }
            fileUpload.clearInput()
            if (chosen.isNotEmpty() && running) {
                problems.removeAll()
                problems.div(tr("Ein Upload läuft noch. Bitte wählen Sie die Dateien danach erneut aus."))
            } else if (chosen.isNotEmpty()) {
                running = true
                AppScope.launchMailingUploads(chosen, message, status, problems) {
                    running = false
                    onChanged()
                }
            }
        }
    }
}

private fun CoroutineScope.launchMailingUploads(
    chosen: List<File>,
    message: MailingMessageDto,
    status: UploadStatusRegion,
    problems: Div,
    done: () -> Unit,
) {
    launch {
        problems.removeAll()
        val free = AttachmentRules.MAX_FILES - message.attachments.size
        val accepted = chosen.take(free)
        if (chosen.size > free) {
            problems.div(gettext("Es sind nur noch %1 Anhänge möglich; die übrigen Dateien wurden nicht übernommen.", free))
        }
        val batch = mutableListOf<Pair<String, Long>>()
        var uploaded = 0
        accepted.forEach { file ->
            val problem = mailingAttachmentPrecheck(file.name, file.size.toLong(), message.attachments, batch)
            if (problem != null) {
                status.set(file.name, gettext("Fehlgeschlagen: %1", problem), file)
                return@forEach
            }
            status.set(file.name, tr("Wird hochgeladen…"), file)
            when (val outcome = MailingHttp.uploadAttachment(message.id, file)) {
                is MailingUploadOutcome.Created -> {
                    uploaded++
                    batch += file.name to file.size.toLong()
                    status.set(file.name, tr("Hochgeladen"), file)
                }
                is MailingUploadOutcome.Refused ->
                    status.set(file.name, gettext("Fehlgeschlagen: %1", mailingAttachmentRefusalText(outcome.code, file.name)), file)
            }
        }
        if (uploaded > 0) {
            notifySuccess(ngettext("%1 Anhang hinzugefügt.", "%1 Anhänge hinzugefügt.", uploaded, uploaded))
        }
        done()
    }
}

/** Mailing wrapper of the shared [attachmentPrecheck]: the message's attachments plus the files of this selection uploaded so far. */
internal fun mailingAttachmentPrecheck(
    name: String,
    sizeBytes: Long,
    existing: List<MailingAttachmentDto>,
    batchSoFar: List<Pair<String, Long>>,
): String? =
    attachmentPrecheck(
        name = name,
        sizeBytes = sizeBytes,
        existing =
            existing.map {
                Pair(it.fileName, it.sizeBytes)
            } + batchSoFar,
    )

/** Mailing wrapper of the shared [attachmentRefusalText] (kept for the call sites and tests of V1.9.232). */
internal fun mailingAttachmentRefusalText(
    code: String,
    fileName: String,
): String = attachmentRefusalText(code = code, fileName = fileName)

/** The extra line of the send confirmation: only when the message carries attachments (singular/plural). */
internal fun mailingSendAttachmentsLine(attachments: List<MailingAttachmentDto>): String? {
    if (attachments.isEmpty()) return null
    val size = formatFileSize(attachments.sumOf { it.sizeBytes })
    return ngettext("Mit %1 Anhang (%2).", "Mit %1 Anhängen (%2).", attachments.size, attachments.size, size)
}
