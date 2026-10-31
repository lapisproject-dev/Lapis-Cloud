package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismLimits
import network.lapis.cloud.shared.domain.ActivismNoticeSettingsDto
import network.lapis.cloud.shared.domain.ActivismReportDto
import network.lapis.cloud.shared.rpc.IActivismService

/**
 * V1.9.246 "Aktionen A1" -- `/activism/settings`, for BOARD and ADMIN: the country and the eight notice texts, the switch "Ideen müssen freigegeben
 * werden" and the list of reported actions (no name of the reporting person). The default text of a kind is the translated standard text.
 */
fun renderActivismNoticeSettingsScreen(container: SimplePanel) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.overviewBackButton(Routes.ACTIVISM, tr("Zu den Aktionen"))
    root.pageHeader(tr("Einstellungen zu den Aktionen"))
    lateinit var data: DataSection
    data =
        root.dataSection<Pair<ActivismNoticeSettingsDto, List<ActivismReportDto>>>(
            isEmpty = { false },
            load = {
                val service = rpcService<IActivismService>()
                val settings = guarded { service.getNoticeSettings() }
                val reports = settings?.let { guarded { service.listReports() } }
                if (settings != null && reports != null) settings to reports else null
            },
            render = { panel, (settings, reports) -> renderSettings(panel, settings, reports) { data.reload() } },
        )
    data.reload()
}

private fun renderSettings(
    panel: SimplePanel,
    settings: ActivismNoticeSettingsDto,
    reports: List<ActivismReportDto>,
    reload: () -> Unit,
) {
    val review = panel.checkBox(value = settings.reviewRequired, label = tr("Ideen müssen von einer zweiten Person freigegeben werden"))
    review.onClick {
        val wanted = review.value == true
        review.disabled = true
        runGuardedAction(button = null) {
            guarded { rpcService<IActivismService>().setReviewRequired(wanted) }
            reload()
        }
    }
    panel.h2(tr("Hinweistexte")) { addCssClasses("h5 mt-3") }
    val countryForm = panel.lapisForm()
    val country =
        countryForm.selectField(label = tr("Land"), options = ActivismTexts.noticeCountryOptions(), value = settings.country)
    val saveCountry = newActionButton(ActionIcon.SAVE, tr("Land speichern"))
    countryForm.buttons(primary = saveCountry)
    saveCountry.onClick {
        countryForm.submit(saveCountry) {
            guarded { rpcService<IActivismService>().setNoticeCountry(country.value) }
            reload()
        }
    }
    ActivismKind.entries.forEach { kind ->
        val form = panel.lapisForm()
        val field =
            form.textAreaField(
                label = ActivismTexts.kind(kind),
                rows = 3,
                value = settings.texts[kind],
                hint = gettext("Leer lassen für den Standardtext."),
            )
        val save = newActionButton(ActionIcon.SAVE, tr("Speichern"))
        val reset = newActionButton(ActionIcon.REFRESH, tr("Auf Standardtext zurücksetzen"), ButtonStyle.OUTLINESECONDARY)
        form.buttons(primary = save, cancel = reset)
        save.onClick {
            form.submit(save) {
                if (field.value.length > ActivismLimits.NOTICE_TEXT_MAX) {
                    field.showError(gettext("Der Text ist zu lang."))
                    return@submit
                }
                guarded { rpcService<IActivismService>().setNoticeText(kind, field.value.ifBlank { null }) }
                reload()
            }
        }
        reset.onClick {
            runGuardedAction(reset) {
                guarded { rpcService<IActivismService>().setNoticeText(kind, null) }
                reload()
            }
        }
    }
    panel.h2(tr("Gemeldete Aktionen")) { addCssClasses("h5 mt-4") }
    if (reports.isEmpty()) panel.div(tr("Keine offenen Meldungen."), className = "text-muted")
    reports.forEach { report ->
        val row = panel.div(className = "border-bottom py-2 d-flex flex-wrap gap-2 align-items-start") { setAttribute("role", "group") }
        val info = row.div(className = "flex-grow-1")
        info.untrustedDiv(report.actionTitle, className = "fw-bold")
        info.untrustedDiv(report.reason, className = "lapis-pre-line small")
        info.div(formatSystemDateTime(report.createdAt), className = "small text-muted")
        row.actionButton(
            ActionIcon.VIEW,
            tr("Aktion öffnen"),
            small = true,
        ) { onClick { navigateTo("${Routes.ACTIVISM}/${report.actionId}") } }
        row.actionButton(ActionIcon.APPROVE, tr("Meldung erledigen"), small = true) {
            onClick {
                runGuardedAction(this) {
                    guarded { rpcService<IActivismService>().resolveReport(report.id) }
                    reload()
                }
            }
        }
    }
}
