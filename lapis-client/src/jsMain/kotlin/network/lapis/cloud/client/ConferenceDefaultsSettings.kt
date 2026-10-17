package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
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
 * V1.9.129 -- the same card also offers the video overlays (logos, the person's name, a frame in the own camera image): the switch "Overlays
 * anbieten" and the library of logos and frames (`ConferenceVideoOverlayLibrarySettings.kt`).
 */

private var defaultsSwitchSerial = 0

/** Renders the card into [root]; the caller guarantees the viewer is an ADMIN (the server enforces it on the call). */
internal fun renderConferenceDefaultsCard(root: SimplePanel) {
    val section = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2") }
    section.h2(tr("Videokonferenz")) { addCssClass("h5") }
    val host = section.vPanel(spacing = 6)
    lateinit var data: DataSection
    data =
        host
            .dataSection<OrganizationSettingsDto>(
                isEmpty = { false },
                load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
                render = { panel, dto ->
                    renderNoiseDefaultSwitch(panel, dto.noiseSuppressionDefault)
                    // V1.9.129 -- the video overlays: the switch and the library (see ConferenceVideoOverlayLibrarySettings.kt)
                    renderVideoOverlaysOfferedSwitch(panel, dto.videoOverlaysOffered)
                    renderVideoOverlayLibrary(panel)
                    // V1.9.143 -- the optional recording of the pulpit of the encounter room (see PulpitRecordingSettings.kt). A change rebuilds the card.
                    renderPulpitRecordingSection(panel, dto) { data.reload() }
                    // V1.9.156 -- the optional recording and live streaming in event rooms (see EventCaptureSettings.kt). A change rebuilds the card.
                    renderEventCaptureSection(panel, dto) { data.reload() }
                    // V1.9.145 -- the optional member finder (see MemberFinderSettings.kt). A change rebuilds the card as well.
                    renderMemberFinderSection(panel, dto) { data.reload() }
                },
            )
    data.reload()
}

private fun renderNoiseDefaultSwitch(
    panel: SimplePanel,
    initial: Boolean,
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
            }
        }
    }
}
