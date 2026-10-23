package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.upload.Capture
import io.kvision.form.upload.Upload
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import org.w3c.files.File

/*
 * V1.9.198: receipt files chosen directly in the "Kosten hinzufügen" form. The files stay in memory until the line has been created
 * (`addLine` returns the new line id only then); `TravelExpenseExtraCosts.kt` uploads them one after the other afterwards. Client only --
 * the receipt route and its security checks are unchanged (the server sniffs the real type and enforces the caps).
 */

/** The accepted types of the receipt/attachment file inputs; mirrors the server allowlist (PDF, JPEG, PNG). */
internal val TRAVEL_EXPENSE_FILE_TYPES: List<String> = listOf("application/pdf", "image/jpeg", "image/png")

private var receiptPickerCounter = 0

/**
 * Receipt files that could not be uploaded after their line was created, per line id -- kept in memory only, so a reload loses them
 * (documented). Module-wide because the whole editor is rebuilt (`onSaved`) between the failure and the next render of the row.
 */
internal val pendingFailedReceiptUploads: MutableMap<String, MutableList<File>> = mutableMapOf()

/**
 * A polite live region with one text status per file ("Wird hochgeladen…", "Hochgeladen", "Fehlgeschlagen: ..."). Text only, no
 * animation. [set] keeps the order in which files were first reported.
 */
internal class UploadStatusRegion(
    private val host: Container,
) {
    // Created on the first status, not up front: the draft editor's totals bar is its ONE permanent live region.
    private var region: Div? = null
    private val statuses = LinkedHashMap<Any, Pair<String, String>>()

    /** [key] identifies the file (default: its name); pass the File itself so that two files with the same name keep two lines. */
    fun set(
        fileName: String,
        text: String,
        key: Any = fileName,
    ) {
        statuses[key] = fileName to text
        val target =
            region ?: host
                .div(className = "lapis-te-upload-status small") {
                    setAttribute("role", "status")
                    setAttribute("aria-live", "polite")
                }.also { region = it }
        target.removeAll()
        statuses.values.forEach { (name, status) -> target.untrustedDiv(gettext("„%1“: %2", sanitizeUntrustedI18nText(name), status)) }
    }

    fun clear() {
        statuses.clear()
        region?.removeAll()
    }
}

/**
 * The "Belege" group of the add form: a file chooser (several files), a camera chooser (shown on touch devices only, see `theme.css`), the
 * list of the chosen files with a remove button each, and the pre-check messages. [selected] is what gets uploaded after `addLine`.
 */
internal class ReceiptPicker(
    host: Container,
    private val maxFiles: Int = TravelExpenseAmountRules.MAX_RECEIPTS_PER_LINE,
) {
    private val files = mutableListOf<File>()
    val selected: List<File> get() = files.toList()

    private val group: SimplePanel
    private val list: Div
    private val message: Div
    private val filesUpload: Upload
    private val cameraUpload: Upload

    init {
        val labelId = "lapis-te-receipts-label-${receiptPickerCounter++}"
        group =
            host.vPanel(spacing = 4) {
                addCssClass("lapis-te-receipt-picker")
                setAttribute("role", "group")
                setAttribute("aria-labelledby", labelId)
            }
        group.div(tr("Belege"), className = "fw-bold") { setAttribute("id", labelId) }
        group.div(
            gettext("PDF, JPEG oder PNG, höchstens 10 MB pro Datei, bis zu %1 Belege.", maxFiles),
            className = "text-muted small",
        )
        filesUpload = group.upload(label = tr("Dateien auswählen"), multiple = true)
        filesUpload.accept = TRAVEL_EXPENSE_FILE_TYPES
        val cameraWrapper = group.div(className = "lapis-te-camera")
        cameraUpload = cameraWrapper.upload(label = tr("Foto aufnehmen"), multiple = false)
        cameraUpload.accept = listOf("image/*")
        cameraUpload.capture = Capture.ENVIRONMENT
        message = group.div(className = "text-danger small") { setAttribute("role", "alert") }
        list = group.div()
        group.div(
            tr("Ohne Beleg kann die Abrechnung nicht eingereicht werden. Sie können ihn auch später an der Zeile ergänzen."),
            className = "text-muted small",
        )
        filesUpload.onEvent { change = { takeFrom(filesUpload) } }
        cameraUpload.onEvent { change = { takeFrom(cameraUpload) } }
        renderList()
    }

    /** Takes the files of [upload] into the selection (pre-checked), then empties the control so the same file can be chosen again. */
    internal fun takeFrom(upload: Upload) {
        val chosen = upload.value.orEmpty().mapNotNull { upload.getNativeFile(it) }
        val refusals = mutableListOf<String>()
        chosen.forEach { file ->
            val problem = travelExpenseFilePrecheck(file)
            when {
                problem != null -> refusals += problem
                files.size >= maxFiles -> refusals += gettext("Höchstens %1 Belege pro Zeile.", maxFiles)
                else -> files += file
            }
        }
        upload.clearInput()
        showMessages(refusals)
        renderList()
    }

    fun reset() {
        files.clear()
        showMessages(emptyList())
        renderList()
    }

    private fun showMessages(texts: List<String>) {
        message.removeAll()
        texts.distinct().forEach { message.div(it) }
    }

    private fun renderList() {
        list.removeAll()
        files.toList().forEach { file ->
            val row = list.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
            row.untrustedDiv(file.name, className = "small")
            row.div(receiptSizeLabel(file.size.toLong()), className = "text-muted small")
            val remove = row.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER, small = true)
            remove.setAttribute(
                "aria-label",
                resolvedAttributeText(gettext("„%1“ aus der Auswahl entfernen", sanitizeUntrustedI18nText(file.name))),
            )
            remove.onClick {
                files.remove(file)
                showMessages(emptyList())
                renderList()
            }
        }
    }
}
