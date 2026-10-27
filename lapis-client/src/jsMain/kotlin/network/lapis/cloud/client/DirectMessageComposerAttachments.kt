package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachmentRules
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

/*
 * V1.9.233: the attachments of the direct-message composer. Uploads are PENDING on the server until the message is sent; the files are
 * chosen with a hidden multi-file fileInput behind the "Datei anhängen" button. The STATE lives in a [DmComposerAttachments] that the
 * partner list keeps per partner, NOT in the form: a reload of the list rebuilds the form, and a pending upload must survive that (and
 * closing and reopening the conversation). File names are untrusted text: they only reach the DOM through the `untrusted*` helpers or
 * `sanitizeUntrustedI18nText`, never a toast, a URL or a console.
 */

/** The state of one chosen file. [Failed] carries the fixed German reason (a refusal CODE already turned into text). */
internal sealed interface DmDraftState {
    data object Uploading : DmDraftState

    data class Uploaded(
        val id: String,
    ) : DmDraftState

    data class Failed(
        val reason: String,
    ) : DmDraftState
}

internal class DmAttachmentDraft(
    val localKey: Int,
    val fileLabel: String,
    val sizeBytes: Long,
    var state: DmDraftState,
) {
    val mimeType: String get() = AttachmentRules.ALLOWED[AttachmentRules.extensionOf(fileLabel)] ?: "application/octet-stream"
}

/** The per-partner state of the composer's attachments. UI-free, so it can be driven from a DOM test. */
internal class DmComposerAttachments(
    private val recipientId: String,
    private val http: DirectMessageAttachmentHttp = DirectMessageHttp,
) {
    private var nextKey = 0
    private val items = mutableListOf<DmAttachmentDraft>()

    /** Called after every change; the view that is currently bound re-renders. A rebuilt form simply replaces it. */
    var onChange: () -> Unit = {}

    val drafts: List<DmAttachmentDraft> get() = items.toList()
    val isUploading: Boolean get() = items.any { it.state is DmDraftState.Uploading }
    val hasFailed: Boolean get() = items.any { it.state is DmDraftState.Failed }

    /** `true` while a send must wait: a file is still uploading, or a failed one is still listed. */
    val blocksSend: Boolean get() = isUploading || hasFailed

    /** `true` while a send is in flight: the list is frozen (no new file, no removal) so the send and the list cannot diverge. */
    var sending: Boolean = false
        private set

    /** Freezes or releases the list around a send; the view re-renders. */
    fun setSending(value: Boolean) {
        if (sending == value) return
        sending = value
        onChange()
    }

    /** The ids of the uploaded files, in the order they were chosen. */
    val readyIds: List<String> get() = items.mapNotNull { (it.state as? DmDraftState.Uploaded)?.id }

    /** After a successful send: the server has the files [sentIds] now; only those leave the list (anything else was not sent). */
    fun clearAfterSend(sentIds: Collection<String>) {
        items.removeAll { draft -> (draft.state as? DmDraftState.Uploaded)?.id?.let { it in sentIds } == true }
        onChange()
    }

    /**
     * Brings the list in line with the server's pending uploads for this partner: pending files the list does not know (a reload, another
     * tab) appear as uploaded, uploaded drafts the server no longer has (expired, sent elsewhere) disappear. Skipped while a file uploads
     * or a send runs (their outcome is not on the server yet), and when the server cannot be read.
     */
    fun syncWithServer() {
        AppScope.launch {
            val onServer = http.listPending(recipientId) ?: return@launch
            if (isUploading || sending) return@launch
            val known = items.mapNotNull { (it.state as? DmDraftState.Uploaded)?.id }.toSet()
            val serverIds = onServer.map { it.id }.toSet()
            items.removeAll { (it.state as? DmDraftState.Uploaded)?.id.let { id -> id != null && id !in serverIds } }
            onServer.filter { it.id !in known }.forEach { file ->
                items += DmAttachmentDraft(nextKey++, file.fileName, file.sizeBytes, DmDraftState.Uploaded(file.id))
            }
            onChange()
        }
    }

    /** Pre-checks and uploads [files] one after another. Fires and forgets in the app scope (the view may be closed meanwhile). */
    fun addFiles(files: List<File>) {
        if (files.isEmpty() || sending) return
        // All chosen files appear in the list at once (pre-checked against the earlier ones); the uploads then run one after another.
        val queued = mutableListOf<Pair<DmAttachmentDraft, File>>()
        for (file in files) {
            val draft =
                DmAttachmentDraft(
                    localKey = nextKey++,
                    fileLabel = file.name,
                    sizeBytes = file.size.toLong(),
                    state = DmDraftState.Uploading,
                )
            val counted = items.filter { it.state !is DmDraftState.Failed }.map { Pair(it.fileLabel, it.sizeBytes) }
            val problem = attachmentPrecheck(name = file.name, sizeBytes = file.size.toLong(), existing = counted)
            items += draft
            if (problem != null) draft.state = DmDraftState.Failed(problem) else queued += draft to file
        }
        onChange()
        if (queued.isEmpty()) return
        AppScope.launch {
            for ((draft, file) in queued) {
                draft.state =
                    when (val outcome = http.upload(recipientId, file)) {
                        is MailingUploadOutcome.Created ->
                            if (outcome.id.isEmpty()) {
                                DmDraftState.Failed(attachmentRefusalText("", file.name))
                            } else {
                                DmDraftState.Uploaded(outcome.id)
                            }
                        is MailingUploadOutcome.Refused -> DmDraftState.Failed(attachmentRefusalText(outcome.code, file.name))
                    }
                onChange()
            }
        }
    }

    /** Removes [draft]; an uploaded one is deleted on the server (it is still pending there). A running upload cannot be removed. */
    fun remove(draft: DmAttachmentDraft) {
        val state = draft.state
        if (state is DmDraftState.Uploading || sending) return
        items.remove(draft)
        onChange()
        if (state is DmDraftState.Uploaded) {
            AppScope.launch {
                val code = http.delete(state.id)
                if (code != null && code != "NOT_FOUND") notifyError(attachmentRefusalText(code, ""))
            }
        }
    }
}

/** The part of the composer that sits in the form: the hidden file input, the list, the hint lines. Returns the "Datei anhängen" button. */
internal class DmComposerAttachmentsView(
    host: Container,
    private val state: DmComposerAttachments,
    /** Called after every state change (the form uses it to enable/disable the send button and to show its hint). */
    private val afterChange: () -> Unit,
) {
    private val list: Tag
    private val fileInput: Tag
    val attachButton: Button

    init {
        fileInput = host.tag(TAG.INPUT, className = "d-none")
        fileInput.setAttribute("type", "file")
        fileInput.setAttribute("multiple", "multiple")
        fileInput.setAttribute("accept", AttachmentRules.acceptAttribute())
        fileInput.setAttribute("tabindex", "-1")
        fileInput.setAttribute("aria-hidden", "true")
        fileInput.onEvent {
            change = {
                val element = fileInput.getElement() as? HTMLInputElement
                val chosen = element?.files?.let { files -> (0 until files.length).mapNotNull { files.item(it) } }.orEmpty()
                // Clear the input so choosing the same file again fires `change` again.
                element?.value = ""
                state.addFiles(chosen)
            }
        }
        attachButton = newActionButton(ActionIcon.ATTACH, tr("Datei anhängen"), ButtonStyle.OUTLINESECONDARY)
        attachButton.onClick { (fileInput.getElement() as? HTMLInputElement)?.click() }
        host.div(
            tr("Bis zu 5 Dateien, je höchstens 10 MB, zusammen höchstens 20 MB."),
            className = "text-muted small",
        )
        host.div(
            tr("Anhänge sieht nur Ihr Gesprächspartner. Lapis prüft Dateien nicht auf Viren."),
            className = "text-muted small",
        )
        list = host.tag(TAG.UL, className = "lapis-dm-composer-attachments list-unstyled mb-0")
        list.setAttribute("aria-live", "polite")
        state.onChange = {
            render()
            afterChange()
        }
        render()
    }

    private fun render() {
        list.removeAll()
        attachButton.disabled = state.sending
        state.drafts.forEach { draft -> renderRow(draft) }
    }

    private fun renderRow(draft: DmAttachmentDraft) {
        val item = list.tag(TAG.LI, className = "d-flex flex-wrap align-items-center gap-2")
        item.tag(TAG.I, className = fileTypeIcon(draft.mimeType)) { setAttribute("aria-hidden", "true") }
        item.untrustedSpan(draft.fileLabel, className = "text-truncate") { title = sanitizeUntrustedI18nText(draft.fileLabel) }
        item.span(formatFileSize(draft.sizeBytes), className = "text-muted small")
        when (val current = draft.state) {
            DmDraftState.Uploading -> item.span(tr("Wird hochgeladen…"), className = "text-muted small") { setAttribute("role", "status") }
            is DmDraftState.Uploaded -> Unit
            is DmDraftState.Failed ->
                item.span(gettext("Fehlgeschlagen: %1", current.reason), className = "text-danger small") { setAttribute("role", "alert") }
        }
        val remove = item.actionButton(ActionIcon.REMOVE, tr("Entfernen"), ButtonStyle.OUTLINESECONDARY, small = true)
        remove.setAttribute("aria-label", resolvedAttributeText(gettext("„%1“ entfernen", sanitizeUntrustedI18nText(draft.fileLabel))))
        remove.disabled = draft.state is DmDraftState.Uploading || state.sending
        remove.onClick {
            state.remove(draft)
            attachButton.focus()
        }
    }
}

/** The note below the form while a send must wait; empty when nothing blocks. Pure. */
internal fun dmComposerBlockHint(state: DmComposerAttachments): String? =
    when {
        state.isUploading -> gettext("Bitte warten, bis alle Dateien hochgeladen sind.")
        state.hasFailed -> gettext("Bitte entfernen Sie fehlgeschlagene Dateien.")
        else -> null
    }

/** The hint's host, kept next to the form so the form can show/hide it. */
internal fun Container.dmComposerBlockHintHost(): Div = div(className = "text-muted small") { setAttribute("role", "status") }
