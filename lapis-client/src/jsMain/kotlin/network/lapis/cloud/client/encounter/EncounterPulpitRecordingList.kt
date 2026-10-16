package network.lapis.cloud.client.encounter

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.DataSection
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.confirmDialog
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.formatFileSize
import network.lapis.cloud.client.formatSystemDateTime
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.notifySuccess
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.untrustedSpan
import network.lapis.cloud.shared.domain.PulpitRecordingDocumentDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPulpitRecordingService

/** What the list needs: the stored recordings and whether the instance offers the recording at all (an instance that never did keeps a clean page). */
private class PulpitRecordingListData(
    val items: List<PulpitRecordingDocumentDto>,
    val offered: Boolean,
)

/**
 * V1.9.143 -- the stored pulpit recordings, for BOARD/ADMIN: room, moment, size and "Löschen". The region stays EMPTY (no heading, no text) while the
 * instance does not offer the recording and nothing was ever stored. Deleting is irrevocable (bytes and rows), so the confirmation puts the focus on
 * "Abbrechen" and says that older backups may still hold a copy. The list names no person: the server stores none.
 */
internal fun SimplePanel.pulpitRecordingList() {
    val host = vPanel(spacing = 6)
    lateinit var section: DataSection
    section =
        host.dataSection<PulpitRecordingListData>(
            isEmpty = { false },
            load = {
                guarded {
                    val items = rpcService<IPulpitRecordingService>().listPulpitRecordings(null)
                    val offered =
                        runCatching { rpcService<IOrganizationSettingsService>().getOrganizationSettings().pulpitRecordingEnabled }
                    PulpitRecordingListData(items = items, offered = offered.getOrDefault(false))
                }
            },
        ) { panel, data ->
            if (data.items.isEmpty() && !data.offered) return@dataSection
            val box = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3 mt-3") }
            box.h2(tr("Aufzeichnungen")) { addCssClass("h5") }
            if (data.items.isEmpty()) box.div(tr("Noch keine Aufzeichnungen."), className = "text-muted small")
            data.items.forEach { item ->
                val row = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
                row.untrustedSpan(item.spaceTitle)
                row.div(formatSystemDateTime(item.recordedAt), className = "text-muted small")
                row.div(formatFileSize(item.fileSizeBytes), className = "text-muted small")
                row.actionButton(ActionIcon.DELETE, tr("Löschen"), style = ButtonStyle.OUTLINEDANGER, small = true).onClick {
                    confirmDialog(
                        title = tr("Aufzeichnung löschen?"),
                        message = gettext("Die Aufzeichnung wird endgültig gelöscht. Ältere Sicherungen können sie noch enthalten."),
                        confirmLabel = tr("Löschen"),
                        confirmIcon = ActionIcon.DELETE,
                        focusCancel = true,
                    ) {
                        AppScope.launch {
                            val deleted = guarded { rpcService<IPulpitRecordingService>().deletePulpitRecording(item.documentId) }
                            if (deleted == true) {
                                notifySuccess(tr("Die Aufzeichnung wurde gelöscht."))
                                section.reload()
                            }
                        }
                    }
                }
            }
        }
    section.reload()
}
