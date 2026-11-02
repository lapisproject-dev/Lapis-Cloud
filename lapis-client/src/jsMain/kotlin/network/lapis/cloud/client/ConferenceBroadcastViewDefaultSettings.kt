package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonSize
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * V1.9.262 -- the ADMIN setting "Standardansicht bei Aufzeichnung und Stream" (Aufzeichnung & Übertragung). Two radios save at once through
 * their own RPC (ADMIN only, audited on change); a refusal puts the radio back. The value is copied into a room only when the room is
 * created and pre-fills the stream dialog from then on; existing rooms change only through the button below, which counts first and asks
 * before it applies. The button has a fixed text (no number, no icon) and is always enabled.
 */

private var broadcastViewDefaultSerial = 0

/** The question before the bulk change: how many rooms switch to which view, and that running recordings are not touched. */
internal fun broadcastViewApplyConfirmText(
    count: Int,
    view: ConferenceBroadcastView,
): String {
    val label = conferenceBroadcastViewLabel(view)
    return ngettext(
        "%1 Raum wird auf „%2“ umgestellt. Laufende Aufzeichnungen und Streams bleiben unverändert; die neue Ansicht gilt ab der nächsten Aufzeichnung bzw. dem nächsten Streamstart.",
        "%1 Räume werden auf „%2“ umgestellt. Laufende Aufzeichnungen und Streams bleiben unverändert; die neue Ansicht gilt ab der nächsten Aufzeichnung bzw. dem nächsten Streamstart.",
        count,
        count,
        label,
    )
}

/** The status line after the bulk change. */
internal fun broadcastViewAppliedStatus(count: Int): String = ngettext("%1 Raum umgestellt.", "%1 Räume umgestellt.", count, count)

internal fun renderBroadcastViewDefaultSection(
    panel: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val serial = ++broadcastViewDefaultSerial
    val rpc = rpcService<IOrganizationSettingsService>()
    var current = dto.conferenceBroadcastViewDefault
    val fieldset = panel.tag(TAG.FIELDSET, className = "mb-3")
    fieldset.tag(TAG.LEGEND, content = tr("Standardansicht bei Aufzeichnung und Stream"), className = "fs-6")
    val rasterInput = fieldset.radioRow("lapis-bvd-raster-$serial", serial, ConferenceBroadcastView.RASTER) { current }
    val speakerInput = fieldset.radioRow("lapis-bvd-speaker-$serial", serial, ConferenceBroadcastView.SPEAKER) { current }
    fieldset.p(
        tr("Gilt für neue Räume und als Vorbelegung beim Stream-Start. Bestehende Räume ändern Sie mit dem Knopf darunter."),
        className = "text-muted small",
    )
    val applyButton: Button =
        panel.button(tr("Auf alle bestehenden Räume anwenden"), style = ButtonStyle.OUTLINESECONDARY, className = "mb-2") {
            size = ButtonSize.SMALL
        }
    val status = panel.div("", className = "text-muted small mb-2 lapis-broadcast-view-default-status")
    status.setAttribute("aria-live", "polite")
    status.setAttribute("role", "status")

    fun syncRadios() {
        rasterInput.setAttribute("aria-checked", (current == ConferenceBroadcastView.RASTER).toString())
        speakerInput.setAttribute("aria-checked", (current == ConferenceBroadcastView.SPEAKER).toString())
        rasterInput.getElement()?.asDynamic().checked = current == ConferenceBroadcastView.RASTER
        speakerInput.getElement()?.asDynamic().checked = current == ConferenceBroadcastView.SPEAKER
    }

    fun choose(view: ConferenceBroadcastView) {
        if (view == current) return
        val previous = current
        current = view
        setRadiosDisabled(rasterInput, speakerInput, disabled = true)
        applyButton.disabled = true
        AppScope.launch {
            val saved = guarded { rpc.setConferenceBroadcastViewDefault(view) }
            if (saved == null) {
                current = previous
            } else {
                current = saved.conferenceBroadcastViewDefault
                status.content = gettext("Standardansicht „%1“ gespeichert.", conferenceBroadcastViewLabel(current))
                onChanged()
            }
            setRadiosDisabled(rasterInput, speakerInput, disabled = false)
            applyButton.disabled = false
            syncRadios()
        }
    }
    rasterInput.onEvent { change = { choose(ConferenceBroadcastView.RASTER) } }
    speakerInput.onEvent { change = { choose(ConferenceBroadcastView.SPEAKER) } }

    applyButton.onClick {
        runGuardedAction(applyButton) {
            // The server counts and applies against the STORED value, so the dialog must name that one, not this page's possibly stale copy.
            val stored = guarded { rpc.getOrganizationSettings() } ?: return@runGuardedAction
            val view = stored.conferenceBroadcastViewDefault
            if (view != current) {
                current = view
                syncRadios()
            }
            val count = guarded { rpc.countRoomsDifferingFromBroadcastViewDefault() } ?: return@runGuardedAction
            if (count > 0) {
                confirmDialog(
                    title = tr("Auf bestehende Räume anwenden?"),
                    message = broadcastViewApplyConfirmText(count, view),
                    confirmLabel = tr("Anwenden"),
                    confirmStyle = ButtonStyle.PRIMARY,
                    focusCancel = true,
                ) {
                    AppScope.launch {
                        val changed = guarded { rpc.applyBroadcastViewDefaultToExistingRooms() }
                        if (changed != null) status.content = broadcastViewAppliedStatus(changed)
                    }
                }
            } else {
                val modal = Modal(caption = tr("Auf bestehende Räume anwenden?"))
                modal.div(gettext("Alle bestehenden Räume nutzen bereits „%1“.", conferenceBroadcastViewLabel(view)))
                val close = newActionButton(ActionIcon.CANCEL, tr("Schließen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
                modal.addButton(close)
                modal.show()
            }
        }
    }
}

private fun setRadiosDisabled(
    vararg inputs: Tag,
    disabled: Boolean,
) = inputs.forEach { if (disabled) it.setAttribute("disabled", "") else it.removeAttribute("disabled") }

private fun Container.radioRow(
    id: String,
    serial: Int,
    view: ConferenceBroadcastView,
    current: () -> ConferenceBroadcastView,
): Tag {
    val row = div(className = "form-check")
    val input = row.tag(TAG.INPUT, className = "form-check-input")
    input.setAttribute("type", "radio")
    input.setAttribute("name", "lapis-broadcast-view-default-$serial")
    input.setAttribute("id", id)
    if (current() == view) input.setAttribute("checked", "")
    input.setAttribute("aria-checked", (current() == view).toString())
    row.tag(TAG.LABEL, content = conferenceBroadcastViewLabel(view), className = "form-check-label").setAttribute("for", id)
    return input
}
