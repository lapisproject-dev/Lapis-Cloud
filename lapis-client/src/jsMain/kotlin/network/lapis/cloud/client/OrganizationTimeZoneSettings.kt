package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.OrganizationTimeZoneDto
import network.lapis.cloud.shared.rpc.IOrganizationTimeZoneService
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * V1.9.38 "Einheitliche Zeitzonen" -- the ADMIN setting "Zeitzone der Organisation". It is the zone in which typed-in times and
 * deadlines are read and in which system timestamps are displayed. The zone is chosen from the server's allow-list (never typed as
 * free text) and saved through its own RPC, which validates again and writes an audit entry; the new zone reaches every other screen
 * through `SessionInfoDto.organizationTimeZone` (the session is refreshed after saving).
 */

/** The zones offered first -- the organization's usual ones. A zone the server does not offer is simply left out. */
internal val COMMON_ORGANIZATION_ZONES = listOf("Europe/Berlin", "Europe/Vienna", "Europe/Zurich", "Asia/Tbilisi", "UTC")

private const val SEPARATOR_LABEL = "──────────"

/**
 * The options of the zone select: the common zones that the server offers (in that fixed order), a separator that cannot be
 * chosen (its value is empty, which the `required` rule rejects), then every other offered zone, sorted. The ids come from the
 * server's allow-list and are plain technical ids, so they are shown as they are.
 */
internal fun zoneSelectOptions(available: List<String>): List<Pair<String, String>> {
    val offered = available.toSet()
    val common = COMMON_ORGANIZATION_ZONES.filter { it in offered }
    val rest = available.filter { it !in common }.sorted()
    val options =
        common.map { it to it } + (if (common.isNotEmpty() && rest.isNotEmpty()) listOf("" to SEPARATOR_LABEL) else emptyList()) +
            rest.map { it to it }
    return untrustedOptions(options)
}

/** "now" in [zoneId] with its abbreviation, e.g. `02.10.2026, 03:48 MESZ` -- the live preview under the select. Computed when the selection changes. */
internal fun organizationZonePreview(
    zoneId: String,
    at: Instant = Clock.System.now(),
): String = formatDateTime(at.toLocalDateTime(resolveZone(zoneId))) + NBSP + zoneAbbreviation(zoneId, at)

/** Renders the settings card into [root]; the caller guarantees the viewer is an ADMIN (the server enforces it on both calls). */
internal fun renderOrganizationTimeZoneCard(root: SimplePanel) {
    val section = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2") }
    section.h2(tr("Zeitzone der Organisation")) { addCssClass("h5") }
    section.p(
        tr(
            "Eingetragene Termine und Fristen bleiben unverändert. Systemzeitstempel und der Kalender-Feed werden " +
                "in der neuen Zeitzone angezeigt.",
        ),
    ) { addCssClasses("text-muted small") }
    val host = section.vPanel(spacing = 6)
    host
        .dataSection<OrganizationTimeZoneDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IOrganizationTimeZoneService>().getOrganizationTimeZone() } },
            render = { panel, dto -> renderZoneForm(panel, dto) },
        ).reload()
}

private fun renderZoneForm(
    panel: SimplePanel,
    dto: OrganizationTimeZoneDto,
) {
    val form = panel.lapisForm()
    val zoneField =
        form.selectField(
            label = tr("Zeitzone der Organisation"),
            options = zoneSelectOptions(dto.availableZoneIds),
            value = dto.zoneId,
            required = true,
            rule = { id -> if (id in dto.availableZoneIds) FieldCheck.Ok else FieldCheck.Invalid(gettext("Unbekannte Zeitzone")) },
        )
    val preview = form.panel.div { addCssClasses("text-muted small") }
    val seriesNote =
        form.panel.div {
            addCssClasses("text-muted small")
            content = gettext("Wiederkehrende Veranstaltungen verwenden weiterhin Europe/Berlin.")
        }

    fun refresh(zoneId: String) {
        preview.content = gettext("Jetzt: %1", organizationZonePreview(zoneId))
        if (zoneId == "Europe/Berlin" || zoneId.isEmpty()) seriesNote.hide() else seriesNote.show()
    }
    refresh(dto.zoneId)
    zoneField.subscribe { refresh(it) }

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val saved = guarded { rpcService<IOrganizationTimeZoneService>().updateOrganizationTimeZone(zoneField.value) }
            if (saved != null) {
                notifySuccess(tr("Zeitzone gespeichert."))
                // The zone travels with the session: refreshing it re-renders every screen in the new zone.
                refreshSessionFromServer()
            }
        }
    }
}
