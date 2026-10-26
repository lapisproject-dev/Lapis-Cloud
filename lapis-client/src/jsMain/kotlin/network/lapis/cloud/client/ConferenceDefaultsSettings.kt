package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * V1.9.124 -- the ADMIN setting "Rauschunterdrückung vorgeben" (the instance default of the optional noise suppression in video
 * conferences). One switch, no dialog: a person's own remembered choice in the browser still wins, so the setting only decides what
 * people see who never chose. It is saved at once through its own RPC (ADMIN only, audited on change); a refusal puts the switch back.
 * The pulpit of the encounter room is not affected.
 *
 * V1.9.221 -- the card is split into the sections of the page Organisation (`OrganizationScreen.kt`).
 *
 * V1.9.129 -- the same card also offers the video overlays (logos, the person's name, a frame in the own camera image): the switch "Overlays
 * anbieten" and the library of logos and frames (`ConferenceVideoOverlayLibrarySettings.kt`).
 */

private var defaultsSwitchSerial = 0

/** Loads the organization settings into a section of its own below [root]; [render] builds the content, [reload] is handed back to it. */
private fun settingsSection(
    root: SimplePanel,
    render: (SimplePanel, OrganizationSettingsDto, reload: () -> Unit) -> Unit,
) {
    val host = root.vPanel(spacing = 6)
    lateinit var data: DataSection
    data =
        host.dataSection<OrganizationSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
            render = { panel, dto -> render(panel, dto) { data.reload() } },
        )
    data.reload()
}

/**
 * V1.9.221 -- the "Videokonferenz" section of the page Organisation: the noise-suppression default, the offer of the video overlays and
 * the overlay library. The caller guarantees the viewer is an ADMIN (the server enforces it on every call). No frame of its own: the
 * `<details>` of the page carries it.
 */
internal fun renderConferenceVideoSettings(
    root: SimplePanel,
    onChanged: () -> Unit = {},
) {
    settingsSection(root) { panel, dto, _ ->
        renderNoiseDefaultSwitch(panel, dto.noiseSuppressionDefault, onChanged)
        // V1.9.129 -- the video overlays: the switch and the library (see ConferenceVideoOverlayLibrarySettings.kt)
        renderVideoOverlaysOfferedSwitch(panel, dto.videoOverlaysOffered, onChanged)
        renderVideoOverlayLibrary(panel)
    }
}

/**
 * V1.9.221 -- the "Aufzeichnung & Übertragung" section: the pulpit recording (V1.9.143), the recording and live streaming in event rooms
 * (V1.9.156) and a link to the stream destinations. A change rebuilds the section; [onChanged] lets the page refresh its summary line.
 */
internal fun renderConferenceRecordingSettings(
    root: SimplePanel,
    onChanged: () -> Unit = {},
) {
    settingsSection(root) { panel, dto, reload ->
        val changed = {
            reload()
            onChanged()
        }
        renderPulpitRecordingSection(panel, dto, changed)
        renderEventCaptureSection(panel, dto, changed)
        panel.link(tr("Stream-Ziele verwalten"), url = "#${Routes.CONFERENCE_STREAM_DESTINATIONS}", className = "small")
    }
}

/** V1.9.221 -- the "Mitglieder-Finder" section (V1.9.145, see MemberFinderSettings.kt). A change rebuilds the section. */
internal fun renderMemberFinderSettings(
    root: SimplePanel,
    onChanged: () -> Unit = {},
) {
    settingsSection(root) { panel, dto, reload ->
        renderMemberFinderSection(panel, dto) {
            reload()
            onChanged()
        }
    }
}

private fun renderNoiseDefaultSwitch(
    panel: SimplePanel,
    initial: Boolean,
    onChanged: () -> Unit = {},
) {
    val serial = ++defaultsSwitchSerial
    val labelId = "lapis-noise-admin-label-$serial"
    val helpId = "lapis-noise-admin-help-$serial"
    val line = panel.div(className = "lapis-noise-suppression-line")
    val label = line.span(tr("Rauschunterdrückung vorgeben"))
    label.setAttribute("id", labelId)
    val switch: Button = line.button("", className = "lapis-noise-switch")
    switch.setAttribute("type", "button")
    switch.setAttribute("role", "switch")
    switch.setAttribute("aria-checked", initial.toString())
    switch.setAttribute("aria-labelledby", labelId)
    switch.setAttribute("aria-describedby", helpId)
    val help =
        panel.p(
            tr(
                "Wer die Rauschunterdrückung noch nie selbst eingestellt hat, startet in Videokonferenzen mit eingeschaltetem Filter. Was eine Person selbst eingestellt hat, bleibt immer erhalten.",
            ),
            className = "text-muted small",
        )
    help.setAttribute("id", helpId)
    switch.onClick {
        val wanted = switch.getAttribute("aria-checked") != "true"
        runGuardedAction(switch) {
            val saved = guarded { rpcService<IOrganizationSettingsService>().updateNoiseSuppressionDefault(wanted) }
            if (saved == null) {
                // refused or failed: the switch stays where the server has it
                switch.setAttribute("aria-checked", (!wanted).toString())
            } else {
                switch.setAttribute("aria-checked", saved.noiseSuppressionDefault.toString())
                notifySuccess(
                    if (saved.noiseSuppressionDefault) {
                        gettext("Rauschunterdrückung wird vorgegeben.")
                    } else {
                        gettext("Rauschunterdrückung wird nicht mehr vorgegeben.")
                    },
                )
                onChanged()
            }
        }
    }
}
