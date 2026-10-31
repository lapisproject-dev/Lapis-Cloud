package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismActionInput
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismLimits
import network.lapis.cloud.shared.rpc.IActivismService

/**
 * V1.9.246 "Aktionen A1" -- `/activism/new` and `/activism/{id}/edit`: ONE form, no wizard. Only the title is mandatory. The forms (appointment,
 * progress, "Ich komme") are tick boxes; the fields that belong to a form appear when it is ticked. Above "Speichern" stands a static reminder:
 * no check, no tick box, no block.
 */
fun renderActivismFormScreen(
    container: SimplePanel,
    actionId: String? = null,
) {
    val root = container.pageShell(PageWidth.FORM, spacing = 14)
    root.overviewBackButton(
        if (actionId == null) Routes.ACTIVISM else "${Routes.ACTIVISM}/$actionId",
        if (actionId == null) tr("Zu den Aktionen") else tr("Zur Aktion"),
    )
    root.pageHeader(if (actionId == null) tr("Neue Aktion") else tr("Aktion bearbeiten"))
    if (actionId == null) {
        renderActivismForm(root, null)
        return
    }
    lateinit var data: DataSection
    data =
        root.dataSection<ActivismActionDetailDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IActivismService>().getAction(actionId) } },
            render = { panel, detail -> renderActivismForm(panel, detail) },
        )
    data.reload()
}

private fun renderActivismForm(
    panel: SimplePanel,
    existing: ActivismActionDetailDto?,
) {
    val form = panel.lapisForm()
    val title =
        form.textField(
            label = tr("Titel"),
            required = true,
            value = existing?.title,
            rule = {
                if (it.trim().length >
                    ActivismLimits.TITLE_MAX
                ) {
                    FieldCheck.Invalid(gettext("Der Titel ist zu lang."))
                } else {
                    FieldCheck.Ok
                }
            },
        )
    val kind =
        form.selectField(
            label = tr("Art der Aktion"),
            options = ActivismKind.entries.map { it.name to ActivismTexts.kind(it) },
            value = (existing?.kind ?: ActivismKind.OTHER).name,
        )
    val description = form.textAreaField(label = tr("Beschreibung"), rows = 4, value = existing?.description)
    val goalText = form.textAreaField(label = tr("Ziel"), rows = 2, value = existing?.goalText)
    val location = form.textField(label = tr("Ort"), value = existing?.location)
    val formAppointment = form.checkField(label = tr("Mit Termin"), value = existing?.formAppointment ?: false)
    val starts = form.localDateTimeFields(label = gettext("Beginn"), value = existing?.startsAt)
    val ends = form.localDateTimeFields(label = gettext("Ende"), value = existing?.endsAt)
    val formProgress = form.checkField(label = tr("Mit Fortschritt (Menge melden)"), value = existing?.formProgress ?: false)
    val goal =
        form.textField(
            label = tr("Zielmenge"),
            value = existing?.goalQuantity?.toString(),
            hint = tr("Eine ganze Zahl, zum Beispiel 500."),
        )
    val unit = form.textField(label = tr("Einheit"), value = existing?.unit, hint = tr("Zum Beispiel Plakate oder Unterschriften."))
    val confirm = form.checkField(label = tr("Gemeldete Mengen müssen bestätigt werden"), value = existing?.confirmContributions ?: false)
    val formSignal = form.checkField(label = tr("Mit Zusage (Ich komme)"), value = existing?.formSignal ?: false)
    val ownNotice = form.textAreaField(label = tr("Eigener Hinweis zu den Regeln"), rows = 3, value = existing?.ownNotice)

    fun sync() {
        val appointment = formAppointment.value == "true"
        starts.setVisible(appointment)
        ends.setVisible(appointment)
        val progress = formProgress.value == "true"
        goal.setVisible(progress)
        unit.setVisible(progress)
        confirm.setVisible(progress)
    }
    formAppointment.subscribe { sync() }
    formProgress.subscribe { sync() }
    sync()

    form.panel.div(ActivismTexts.warningSentence(), className = "alert alert-warning small mb-0") { setAttribute("role", "note") }
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = save)
    save.onClick {
        val goalValue = goal.value.trim().takeIf { it.isNotEmpty() }
        val parsedGoal = goalValue?.let { ActivismLogic.parseWhole(it, ActivismLimits.GOAL_QUANTITY_MIN, ActivismLimits.GOAL_QUANTITY_MAX) }
        if (goalValue != null && parsedGoal == null) {
            goal.showError(gettext("Bitte geben Sie eine ganze Zahl zwischen 1 und 1.000.000 ein."))
            return@onClick
        }
        val appointment = formAppointment.value == "true"
        val startsAt = if (appointment) starts.dateTimeOrNull() else null
        val endsAt = if (appointment) ends.dateTimeOrNull() else null
        val input =
            ActivismActionInput(
                title = title.value,
                description = description.value,
                goalText = goalText.value,
                location = location.value,
                startsAt = startsAt,
                endsAt = endsAt,
                kind = ActivismKind.valueOf(kind.value),
                formAppointment = appointment,
                formProgress = formProgress.value == "true",
                formSignal = formSignal.value == "true",
                goalQuantity = parsedGoal,
                unit = unit.value,
                confirmContributions = confirm.value == "true",
                ownNotice = ownNotice.value,
            )
        form.submit(save) {
            val service = rpcService<IActivismService>()
            val saved =
                guarded {
                    if (existing == null) service.createAction(input) else service.updateAction(existing.id, input, existing.revision)
                } ?: return@submit
            navigateTo("${Routes.ACTIVISM}/${saved.id}")
        }
    }
}
