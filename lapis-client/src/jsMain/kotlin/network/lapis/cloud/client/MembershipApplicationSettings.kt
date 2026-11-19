package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * V1.9.315 -- the admin switch "Online-Mitgliedsantrag anbieten" (section "Allgemein" of the page Organisation). Same shape as the other
 * instance switches (`role=switch`, `aria-checked`). Writes ONLY through `updateMembershipApplicationEnabled` (ADMIN, audited on change);
 * the generic settings update never touches the column.
 */

private var membershipSwitchSerial = 0

/** Reads the current value itself (own data section) and renders the switch; [onChanged] lets the page refresh its summary line. */
internal fun renderMembershipApplicationSwitch(
    panel: SimplePanel,
    onChanged: () -> Unit = {},
) {
    lateinit var data: DataSection
    data =
        panel.vPanel(spacing = 6).dataSection<OrganizationSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
            render = { body, settings -> renderMembershipApplicationSwitchBody(body, settings.membershipApplicationEnabled, onChanged) },
        )
    data.reload()
}

private fun renderMembershipApplicationSwitchBody(
    panel: SimplePanel,
    initial: Boolean,
    onChanged: () -> Unit,
) {
    val serial = ++membershipSwitchSerial
    val labelId = "lapis-membership-application-label-$serial"
    val helpId = "lapis-membership-application-help-$serial"
    val line = panel.div(className = "lapis-noise-suppression-line")
    val label = line.span(tr("Online-Mitgliedsantrag anbieten („Mitglied werden“)"))
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
                "Wenn ausgeschaltet, verschwinden alle Links „Mitglied werden“ (Anmeldeseite, öffentliche Startseite, Einbettungs-Widget auf fremden Webseiten), und der Server nimmt keine Online-Anträge mehr an. Die Registrierung als Freund oder Gast bleibt möglich. Bereits eingegangene Anträge bleiben unverändert.",
            ),
            className = "text-muted small",
        )
    help.setAttribute("id", helpId)
    switch.onClick {
        val wanted = switch.getAttribute("aria-checked") != "true"
        runGuardedAction(switch) {
            val saved = guarded { rpcService<IOrganizationSettingsService>().updateMembershipApplicationEnabled(wanted) }
            if (saved == null) {
                switch.setAttribute("aria-checked", (!wanted).toString())
            } else {
                switch.setAttribute("aria-checked", saved.membershipApplicationEnabled.toString())
                notifySuccess(
                    if (saved.membershipApplicationEnabled) {
                        gettext("Der Online-Mitgliedsantrag wird angeboten.")
                    } else {
                        gettext("Der Online-Mitgliedsantrag wird nicht mehr angeboten.")
                    },
                )
                onChanged()
            }
        }
    }
}
