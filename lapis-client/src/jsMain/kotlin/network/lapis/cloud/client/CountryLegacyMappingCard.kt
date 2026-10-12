package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CountryLegacyMappingReportDto
import network.lapis.cloud.shared.domain.CountryLegacyMappingResultDto
import network.lapis.cloud.shared.domain.LegacyOutcome
import network.lapis.cloud.shared.domain.LegacySectionDto
import network.lapis.cloud.shared.rpc.IMemberService

/** The two calls of the reconciliation card -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface CountryLegacyMappingRpc {
    suspend fun preview(): CountryLegacyMappingReportDto

    suspend fun apply(fingerprint: String): CountryLegacyMappingResultDto
}

internal fun liveCountryLegacyMappingRpc(): CountryLegacyMappingRpc =
    object : CountryLegacyMappingRpc {
        override suspend fun preview() = rpcService<IMemberService>().previewCountryLegacyMapping()

        override suspend fun apply(fingerprint: String) = rpcService<IMemberService>().applyCountryLegacyMapping(fingerprint)
    }

/**
 * Welle V1.9.121 -- the board/admin card "Land und Staatsangehörigkeit abgleichen" on the member administration: first a dry run that
 * writes nothing and shows what would be assigned, then -- only after the report is loaded and after an explicit confirmation naming
 * the totals -- the apply of exactly that plan. The server refuses a plan that changed in between.
 *
 * Values of the report are member-typed free text: they are rendered as plain text only ([untrustedSpan]), never as HTML. A value shared
 * by fewer than three members is never listed (only counted), so the card cannot be used to read individual entries.
 */
internal fun renderCountryLegacyMappingCard(
    root: SimplePanel,
    rpc: CountryLegacyMappingRpc = liveCountryLegacyMappingRpc(),
    toastError: (String) -> Unit = { notifyError(it) },
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
    confirm: AdminActionConfirm = liveAdminActionConfirm,
) {
    if (!AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) return
    val section = root.vPanel(spacing = 8) { addCssClasses("border rounded p-3 mt-2 lapis-country-mapping") }
    section.h2(tr("Land und Staatsangehörigkeit abgleichen")) { addCssClass("h5") }
    section.p(
        tr(
            "Frühere Freitexte zu Land und Staatsangehörigkeit werden mit der Auswahlliste abgeglichen. Der Trockenlauf ändert nichts. " +
                "Beim Anwenden bleibt der alte Text neben dem neuen Eintrag erhalten. Konten von Administratoren und Texte, die keinem " +
                "Land eindeutig zugeordnet werden können, bleiben unberührt und müssen einzeln bearbeitet werden.",
        ),
    ) { addCssClasses("text-muted small") }
    val startButton = newActionButton(ActionIcon.VIEW, tr("Trockenlauf starten"), ButtonStyle.PRIMARY)
    section.add(startButton)
    val resultHost = section.vPanel(spacing = 8)

    fun renderOutcome(
        host: SimplePanel,
        result: CountryLegacyMappingResultDto,
    ) {
        host.removeAll()
        host.div(
            gettext(
                "Angewendet: Land %1 von %2 erkannten Einträgen, Staatsangehörigkeit %3 von %4. Offen: %5 beziehungsweise %6.",
                result.country.applied,
                result.country.recognised,
                result.nationality.applied,
                result.nationality.recognised,
                result.country.open,
                result.nationality.open,
            ),
        ) { setAttribute("role", "status") }
    }

    fun renderReport(
        host: SimplePanel,
        report: CountryLegacyMappingReportDto,
    ) {
        host.removeAll()
        listOf(tr("Land") to report.country, tr("Staatsangehörigkeit") to report.nationality).forEach { (title, data) ->
            renderLegacySection(host, title, data)
        }
        val totalRecognised = report.country.recognisedMembers + report.nationality.recognisedMembers
        val applyButton = newActionButton(ActionIcon.APPROVE, tr("Anwenden"), ButtonStyle.PRIMARY)
        applyButton.disabled = totalRecognised == 0
        host.add(applyButton)
        applyButton.onClick {
            if (applyButton.disabled) return@onClick
            confirm.show(
                gettext("Abgleich anwenden"),
                gettext(
                    "Es werden %1 Einträge für das Land und %2 Einträge für die Staatsangehörigkeit zugeordnet. Die alten Texte bleiben erhalten. Fortfahren?",
                    report.country.recognisedMembers,
                    report.nationality.recognisedMembers,
                ),
                gettext("Anwenden"),
            ) {
                runGuardedAction(applyButton, restoreDisabled = { totalRecognised == 0 }) {
                    var result: CountryLegacyMappingResultDto? = null
                    val ok =
                        runOrConflict(
                            conflictMessage = gettext("Der Bericht ist veraltet. Bitte den Trockenlauf erneut starten."),
                            toast = toastError,
                        ) { result = rpc.apply(report.fingerprint) }
                    val applied = result
                    if (ok && applied != null) {
                        toastSuccess(gettext("Der Abgleich wurde angewendet."))
                        renderOutcome(host, applied)
                    } else if (!ok) {
                        host.removeAll()
                    }
                }
            }
        }
    }

    // The dry run loads through the shared loading/error states of `dataSection` (one loading text, one error with "Erneut versuchen").
    startButton.onClick {
        resultHost.removeAll()
        lateinit var report: DataSection
        report =
            resultHost.dataSection<CountryLegacyMappingReportDto>(
                isEmpty = { false },
                load = { guarded { rpc.preview() } },
                render = { panel, data -> renderReport(panel, data) },
            )
        report.reload()
    }
}

private fun renderLegacySection(
    host: SimplePanel,
    title: String,
    data: LegacySectionDto,
) {
    host.h3(title) { addCssClass("h6") }
    host.div(
        gettext(
            "%1 Mitglieder mit altem Text: %2 erkannt, %3 mehrdeutig, %4 nicht erkannt. Geschützt übersprungen: %5.",
            data.totalMembersWithLegacy,
            data.recognisedMembers,
            data.ambiguousMembers,
            data.unrecognisedMembers,
            data.protectedSkippedMembers,
        ),
    )
    if (data.lines.isNotEmpty()) {
        val list = Tag(TAG.UL, className = "mb-1")
        data.lines.forEach { line ->
            val item = Tag(TAG.LI)
            val target =
                when (line.outcome) {
                    LegacyOutcome.RECOGNISED -> line.targets.joinToString(", ") { countryDisplayName(it, io.kvision.i18n.I18n.language) }
                    LegacyOutcome.AMBIGUOUS -> gettext("mehrdeutig")
                    LegacyOutcome.UNRECOGNISED -> gettext("nicht erkannt")
                }
            item.untrustedSpan(line.value)
            item.add(Tag(TAG.SPAN, content = " → "))
            item.untrustedSpan(target)
            item.add(Tag(TAG.SPAN, content = " (${line.count})"))
            list.add(item)
        }
        host.add(list)
    }
    host.div(
        gettext(
            "Seltene Werte (weniger als drei Mitglieder): %1 Mitglieder, davon %2 erkannt. Sie werden nicht einzeln angezeigt.",
            data.rare.members,
            data.rare.recognised,
        ),
    ) { addCssClasses("text-muted small") }
}
