package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * Welle V1.9.10 "Mitgliederzahl-Sichtbarkeit" -- the setting `showPublicMemberCount` (does the number of active members appear on the public
 * start page and the transparency page). V1.9.221 split the old toggle in two:
 *
 *  - [renderPublicMemberCountSwitch] (WRITING, ADMIN only) is a section of the page Organisation (`OrganizationScreen.kt`).
 *  - [renderPublicMemberCountStatusLine] (READ-ONLY) stays on the member administration for TREASURER/BOARD/ADMIN: the same read gate that
 *    `IOrganizationSettingsService.getOrganizationSettings` enforces on the server, so nobody loses the information they had before.
 *
 * Exactly the wholesale-replace pattern of `renderPoliticianRankingToggle`: ADMIN replaces the FULL `OrganizationSettingsDto`, copying every
 * existing field and flipping ONLY `showPublicMemberCount` ([toInputWithShowPublicMemberCount]). BOARD is deliberately NOT given write access:
 * `updateOrganizationSettings` replaces the ENTIRE DTO (IBAN, DATEV numbers, account assignment), which would open that whole surface to BOARD
 * for one click.
 */

/** Read-only: the label and the badge, then (ADMIN) a link to the page Organisation or (everyone else) the sentence who may change it. */
internal fun renderPublicMemberCountStatusLine(
    root: SimplePanel,
    canAdmin: Boolean,
) {
    val section = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2") }
    section.h2(tr("Öffentliche Sichtbarkeit")) { addCssClass("h5") }
    section.p(
        tr(
            "Legt fest, ob die Zahl der aktiven Mitglieder auf der öffentlichen Startseite und der " +
                "Transparenzseite erscheint. Beiträge und LTR-Kennzahlen bleiben davon unberührt.",
        ),
    ) { addCssClasses("text-muted small") }
    section
        .vPanel(spacing = 6)
        .dataSection<OrganizationSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
            render = { panel, settings ->
                panel.publicMemberCountStatusRow(settings.showPublicMemberCount)
                if (canAdmin) {
                    panel.link(tr("Unter Organisation ändern"), url = "#${Routes.ORGANIZATION}?section=mitgliederzahl", className = "small")
                } else {
                    panel.p(tr("Ein ADMIN kann diese Einstellung ändern.")) { addCssClasses("text-muted small") }
                }
            },
        ).reload()
}

private fun SimplePanel.publicMemberCountStatusRow(visible: Boolean) {
    val statusRow = hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("Mitgliederzahl öffentlich:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (visible) tr("Sichtbar") else tr("Verborgen"),
        if (visible) "success" else "secondary",
    )
}

/**
 * Writing (ADMIN only; the server enforces it): the status and the button that flips the setting after a confirmation. [onChanged] gets the new
 * value so the page can refresh its summary line.
 */
internal fun renderPublicMemberCountSwitch(
    root: SimplePanel,
    onChanged: (Boolean) -> Unit = {},
) {
    root.p(
        tr(
            "Legt fest, ob die Zahl der aktiven Mitglieder auf der öffentlichen Startseite und der " +
                "Transparenzseite erscheint. Beiträge und LTR-Kennzahlen bleiben davon unberührt.",
        ),
    ) { addCssClasses("text-muted small") }
    lateinit var data: DataSection
    data =
        root.vPanel(spacing = 6).dataSection<OrganizationSettingsDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } },
            render = { panel, settings -> renderMemberCountSwitchBody(panel, settings, onChanged) { data.reload() } },
        )
    data.reload()
}

private fun renderMemberCountSwitchBody(
    panel: SimplePanel,
    settings: OrganizationSettingsDto,
    onChanged: (Boolean) -> Unit,
    reload: () -> Unit,
) {
    panel.publicMemberCountStatusRow(settings.showPublicMemberCount)
    val toggleButton =
        panel.button(
            if (settings.showPublicMemberCount) tr("Verbergen") else tr("Anzeigen"),
            style = if (settings.showPublicMemberCount) ButtonStyle.OUTLINESECONDARY else ButtonStyle.PRIMARY,
        )
    toggleButton.onClick {
        val newValue = !settings.showPublicMemberCount
        confirmDialog(
            title = if (newValue) tr("Mitgliederzahl öffentlich anzeigen") else tr("Mitgliederzahl verbergen"),
            message =
                if (newValue) {
                    tr(
                        "Die aktuelle Zahl der aktiven Mitglieder ist dann für alle Besucher ohne " +
                            "Anmeldung sichtbar. Die Änderung wird innerhalb weniger Minuten wirksam.",
                    )
                } else {
                    tr(
                        "Die Mitgliederzahl wird auf der öffentlichen Startseite und der " +
                            "Transparenzseite nicht mehr angezeigt. Die Änderung wird innerhalb " +
                            "weniger Minuten wirksam.",
                    )
                },
            confirmLabel = if (newValue) tr("Anzeigen") else tr("Verbergen"),
        ) {
            runGuardedAction(toggleButton) {
                val result =
                    guarded {
                        rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                            settings.toInputWithShowPublicMemberCount(newValue),
                        )
                    }
                if (result != null) {
                    notifySuccess(
                        if (newValue) {
                            tr("Mitgliederzahl wird öffentlich angezeigt.")
                        } else {
                            tr("Mitgliederzahl wird öffentlich nicht mehr angezeigt.")
                        },
                    )
                    onChanged(newValue)
                    reload()
                }
            }
        }
    }
}
