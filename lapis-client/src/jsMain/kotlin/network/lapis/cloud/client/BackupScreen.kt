package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.BackupOperationLogDto
import network.lapis.cloud.shared.rpc.IBackupService
import org.w3c.files.File

/**
 * Compliance UI wave, screen 2 of 5 -- "Backup & Wiederherstellung", per the approved plan + UI/UX-
 * Design-Team review on `feature/compliance-ui`. See plan "Screen 2 -- BackupScreen.kt" and design
 * decisions D3(b) (restore's irreversibility rigor), the "three distinct restore error paths" table,
 * and the export/operations-log notes.
 *
 * Role gating (verified against `BackupService.kt`/`BackupRoutes.kt`'s `requireRole` call sites,
 * plan "Role-gating per action"): every one of [IBackupService]'s methods AND both raw HTTP routes
 * (`/api/backup/export`, `/api/backup/restore`) require ADMIN, uniformly -- narrower than every
 * other Compliance screen in this wave, and the first ADMIN-only route/nav-entry in this client
 * (see `Routing.kt`/`App.kt`). There is therefore no `canManage`-style split anywhere on this
 * screen: every caller who can even reach `/backup` already has full access to every action on it.
 *
 * The full-organization export/restore bundle bytes never travel over Kilua RPC -- only
 * [IBackupService.listOperations]'s lightweight metadata listing does. The bundle itself moves over
 * the two dedicated HTTP routes ([BackupHttp]), same "large/streamed payload" reasoning
 * `IDocumentService`/[DocumentHttp] already establish.
 */
fun renderBackupScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1(tr("Backup & Wiederherstellung"))
    root.div(
        tr(
            "Vollständiger Export und Wiederherstellung aller Organisationsdaten (Datenbank + Dokumente) -- " +
                "nur für Administratorinnen und Administratoren.",
        ),
    ) { addCssClasses("text-muted small") }

    // ---- Export -------------------------------------------------------------------------------
    root.h2(tr("Export"))
    root.div(
        tr(
            "Lädt ein vollständiges ZIP-Backup aller Organisationsdaten herunter. Rein lesend und nicht " +
                "destruktiv -- daher ohne Bestätigungsdialog.",
        ),
    ) { addCssClasses("text-muted small") }
    root.link(tr("Backup exportieren (.zip)"), url = BackupHttp.EXPORT_URL, target = "_blank")

    // ---- Operations log (declared before the restore panel so its `loadOperations`/refresh
    // closure can be passed down as a callback) ------------------------------------------------
    root.h2(tr("Verlauf"))
    root.div(
        tr(
            "Protokoll aller bisherigen Export-/Wiederherstellungsversuche -- rein informativ, keine " +
                "Aktionen auf dieser Liste.",
        ),
    ) { addCssClasses("text-muted small") }
    val logPanel = root.vPanel(spacing = 6)

    fun loadOperations() {
        logPanel.removeAll()
        AppScope.launch {
            val operations = guarded { rpcService<IBackupService>().listOperations() } ?: return@launch
            if (operations.isEmpty()) {
                logPanel.p(tr("Noch keine Export-/Wiederherstellungsversuche protokolliert."))
            } else {
                operations.forEach { operation -> renderOperationRow(logPanel, operation) }
            }
        }
    }

    // ---- Restore --------------------------------------------------------------------------------
    root.h2(tr("Wiederherstellung"))
    renderRestorePanel(root, onCompleted = ::loadOperations)

    loadOperations()
}

// ================================================================================================
// Restore
// ================================================================================================

/**
 * D3(b): the `allowNonEmptyTarget` checkbox sits ABOVE the upload control, unchecked by default, so
 * the danger framing is read before the file picker even appears. The actual submit button opens
 * [restoreConfirmDialog] rather than restoring directly -- same two-step "trigger, then a bespoke
 * confirm modal" shape [LedgerScreen]'s `postingConfirmDialog` already establishes for its own
 * irreversible action.
 */
private fun renderRestorePanel(
    root: SimplePanel,
    onCompleted: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }

    val allowNonEmptyTargetCheck = panel.checkBox(label = tr("Ziel überschreiben (Zielorganisation enthält bereits Daten)"))
    panel.div(
        tr(
            "Ohne diese Option lehnt der Server die Wiederherstellung ab, sobald die Zielorganisation nicht " +
                "leer ist -- das ist der sichere Standardpfad. Aktivieren Sie diese Option nur, wenn Sie " +
                "absichtlich in eine bereits befüllte Organisation wiederherstellen wollen.",
        ),
    ) { addCssClasses("text-muted small") }

    val fileUpload = panel.upload(label = tr("Backup-Datei (.zip)"))

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val incompleteWarningPanel = panel.vPanel(spacing = 2)
    val successSummaryPanel = panel.vPanel(spacing = 2)

    // The "three distinct restore error paths" design decision, plus a success path this design
    // extends (not explicitly specified, but a direct application of the same "never one generic
    // toast" posture): every outcome renders in exactly the block its stakes require, matching
    // D3(b)'s "the RestoreIncompleteException path needs to outlast a page refresh's worth of
    // attention" rationale. Kept as an inline closure (not a top-level function taking `errorBox`
    // as a parameter) so its exact KVision widget type stays compiler-inferred, matching
    // `DocumentsScreen.renderVersionUpload`'s own local-`errorBox` idiom.
    fun handleRestoreOutcome(outcome: RestoreOutcome) {
        when (outcome) {
            is RestoreOutcome.Success -> {
                notifySuccess(tr("Wiederherstellung erfolgreich."))
                val box = successSummaryPanel.vPanel(spacing = 2) { addCssClasses("alert alert-success") }
                box.div(tr("Wiederherstellung erfolgreich abgeschlossen.")) { addCssClass("fw-bold") }
                box.div(
                    gettext(
                        "%1 Tabelle(n), %2 Zeile(n), %3 Datei(en) wiederhergestellt.",
                        outcome.result.tablesRestored,
                        outcome.result.totalRowCount,
                        outcome.result.blobsRestored,
                    ),
                )
                if (outcome.result.warnings.isNotEmpty()) {
                    outcome.result.warnings.forEach { warning ->
                        box.div(gettext("Hinweis: %1", warning)) { addCssClasses("small") }
                    }
                }
            }
            is RestoreOutcome.IncompatibleBundle -> {
                errorBox.content = gettext("Diese Datei passt nicht zum erwarteten Sicherungsformat: %1", outcome.message)
                errorBox.show()
            }
            is RestoreOutcome.NonEmptyTarget -> {
                errorBox.content =
                    gettext(
                        "Die Zielorganisation enthält bereits Daten. Aktivieren Sie „Ziel überschreiben\", falls " +
                            "das beabsichtigt ist: %1",
                        outcome.message,
                    )
                errorBox.show()
            }
            is RestoreOutcome.Incomplete -> {
                val box = incompleteWarningPanel.vPanel(spacing = 2) { addCssClasses("alert alert-danger") }
                box.div(
                    gettext(
                        "Die Wiederherstellung wurde nur teilweise durchgeführt -- einzelne Zeilen wurden " +
                            "möglicherweise bereits geschrieben, bevor der Fehler auftrat: %1. Prüfen " +
                            "Sie den Datenbestand und ziehen Sie bei Unsicherheit Ihre Entwicklerin oder Ihren " +
                            "Entwickler hinzu, bevor Sie es erneut versuchen.",
                        outcome.message,
                    ),
                ) { addCssClass("fw-bold") }
            }
            is RestoreOutcome.Other -> {
                errorBox.content = gettext("Unerwarteter Fehler (HTTP %1): %2", outcome.status, outcome.message)
                errorBox.show()
            }
        }
    }

    val restoreButton = panel.button(tr("Wiederherstellen"), style = ButtonStyle.PRIMARY)
    restoreButton.onClick {
        errorBox.hide()
        val selected = fileUpload.value?.firstOrNull()
        val nativeFile = selected?.let { fileUpload.getNativeFile(it) }
        if (nativeFile == null) {
            errorBox.content = tr("Bitte eine Datei auswählen.")
            errorBox.show()
            return@onClick
        }
        val allowNonEmptyTarget = allowNonEmptyTargetCheck.value

        restoreConfirmDialog(nativeFile, allowNonEmptyTarget) {
            // The confirm modal itself hides on the first click of "Endgültig wiederherstellen", which
            // leaves this "Wiederherstellen" button clickable again while the upload is still in
            // flight -- disable it for the duration so an impatient double-click cannot fire a second
            // concurrent restore attempt against the same target, same idiom `postingConfirmDialog`'s
            // call site already establishes.
            restoreButton.disabled = true
            incompleteWarningPanel.removeAll()
            successSummaryPanel.removeAll()
            AppScope.launch {
                val outcome = BackupHttp.restore(nativeFile, allowNonEmptyTarget)
                restoreButton.disabled = false
                handleRestoreOutcome(outcome)
                onCompleted()
                fileUpload.clearInput()
            }
        }
    }
}

/**
 * D3(b): bespoke modal, same shape as `LedgerScreen.postingConfirmDialog` and NOT the generic
 * [confirmDialog] (too thin for this stakes level -- a single plain sentence, no file/target
 * preview). Every line of copy below is the design review's exact wording.
 */
private fun restoreConfirmDialog(
    file: File,
    allowNonEmptyTarget: Boolean,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Wiederherstellung bestätigen"))
    modal.div(tr("Diese Wiederherstellung ist NICHT rückgängig zu machen.")) { addCssClasses("fw-bold text-danger") }
    modal.div(
        tr(
            "Sie überschreibt Daten mit dem Stand aus der hochgeladenen Datei. Bereits vorhandene Daten in der " +
                "Zielorganisation können dabei verloren gehen.",
        ),
    )

    val detailRow = modal.hPanel(spacing = 8) { addCssClasses("border rounded p-2 mt-2 mb-2 small") }
    detailRow.div(tr("Datei:")) { addCssClasses("text-muted") }
    detailRow.div(gettext("%1 (%2 Bytes)", file.name, file.size.toLong())) { addCssClass("flex-grow-1") }

    if (allowNonEmptyTarget) {
        modal.div(
            tr(
                "„Ziel überschreiben\" ist aktiviert: diese Organisation hat bereits Daten. Sie werden mit den " +
                    "Daten aus der Datei zusammengeführt/überschrieben.",
            ),
        ) { addCssClasses("fw-bold text-danger mt-1") }
    }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Endgültig wiederherstellen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Operations log -- rein informativ, keine Aktionen auf dieser Liste
// ================================================================================================

private fun renderOperationRow(
    panel: SimplePanel,
    operation: BackupOperationLogDto,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.typeBadge(backupOperationTypeLabel(operation.operationType), backupOperationTypeColor(operation.operationType))
    headerRow.statusBadge(backupOperationStatusLabel(operation.status), backupOperationStatusColor(operation.status))
    headerRow.div(operation.actorMemberDisplayName ?: gettext("Mitglied %1", operation.actorMemberId)) {
        addCssClasses("flex-grow-1 text-muted small")
    }
    headerRow.div(gettext("%1 – %2", operation.startedAt, operation.finishedAt)) { addCssClasses("text-muted small") }

    row.div(
        gettext(
            "%1 Tabelle(n) · %2 Zeile(n) · %3 Datei(en) (%4 Bytes) · Bundle-Größe: %5 Bytes · Format-Version %6",
            operation.tableCount,
            operation.totalRowCount,
            operation.blobCount,
            operation.blobBytesTotal,
            operation.bundleSizeBytes,
            operation.bundleFormatVersion,
        ),
    ) { addCssClasses("small") }

    operation.errorMessage?.let { message ->
        row.div(gettext("Fehler: %1", message)) { addCssClasses("text-danger small") }
    }
}
