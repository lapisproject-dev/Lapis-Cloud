package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.p
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyProposalInput
import network.lapis.cloud.shared.domain.MeetingRewardPolicyValuesDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyViewDto
import network.lapis.cloud.shared.domain.MeetingRewardResolutionOptionDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMeetingRewardService

/*
 * V1.9.316 -- the section "Anerkennung für Sitzungen" of the page Organisation (administrators only; the server checks every call).
 * The rules of the LTR recognition change only by a PROPOSAL that cites an adopted resolution of the general assembly and is CONFIRMED by a second
 * administrator; switching off needs one administrator. The wording is always "Anerkennung". The tax and legal classification has not been reviewed.
 */

private class SettingsData(
    val view: MeetingRewardPolicyViewDto,
    val committees: List<CommitteeDto>,
    val resolutions: List<MeetingRewardResolutionOptionDto>,
)

/** Renders the section into [root]; the caller guarantees the viewer is an administrator. */
internal fun renderMeetingAttendanceRecognitionSettings(root: SimplePanel) {
    val host = root.vPanel(spacing = 8)
    lateinit var section: DataSection
    section =
        host.dataSection<SettingsData>(
            isEmpty = { false },
            load = {
                val view = guarded { rpcService<IMeetingRewardService>().getMeetingRewardPolicy() }
                if (view == null) {
                    null
                } else {
                    val committees = guarded { rpcService<IGovernanceService>().listCommittees(activeOnly = true) } ?: emptyList()
                    val resolutions = guarded { rpcService<IMeetingRewardService>().listMeetingRewardResolutionOptions() } ?: emptyList()
                    SettingsData(view, committees, resolutions)
                }
            },
            render = { panel, data -> renderSettings(panel, data) { section.reload() } },
        )
    section.reload()
}

/** The one-line summary of a set of rules (figures only; the committees are named separately). */
internal fun meetingRewardValuesSummary(values: MeetingRewardPolicyValuesDto): String =
    gettext(
        "Grundbetrag %1 · Zuschläge: Vorsitz %2, Stellvertretung %3, Schriftführung %4, Geschäftsführung %5 · Obergrenzen: %6 je Person und Jahr, %7 je Sitzung",
        formatLtr(values.attendanceAmount),
        formatLtr(values.bonusChair),
        formatLtr(values.bonusDeputyChair),
        formatLtr(values.bonusSecretary),
        formatLtr(values.bonusManagingDirector),
        formatLtr(values.capPerPersonPerYear),
        formatLtr(values.capPerMeeting),
    )

private fun onOffText(enabled: Boolean): String = if (enabled) gettext("Eingeschaltet") else gettext("Ausgeschaltet")

private fun committeeNames(
    ids: List<String>,
    committees: List<CommitteeDto>,
): String {
    if (ids.isEmpty()) return gettext("Keine Gremien ausgewählt.")
    return sanitizeUntrustedI18nText(
        gettext(
            "Gremien: %1",
            ids.joinToString(", ") { id ->
                committees.firstOrNull { it.id == id }?.name
                    ?: "?"
            },
        ),
    )
}

private fun renderSettings(
    panel: SimplePanel,
    data: SettingsData,
    reload: () -> Unit,
) {
    val view = data.view
    panel.p(
        tr(
            "Wer an einer Sitzung teilnimmt, kann dafür LTR als Anerkennung erhalten. Die Ordnung ändert sich nur mit einem Beschluss der Mitgliederversammlung und der Bestätigung eines zweiten Admins; ausschalten kann ein einzelner Admin.",
        ),
        className = "text-muted small",
    )
    panel.p(tr("Die steuerliche und rechtliche Einordnung wurde nicht geprüft."), className = "text-muted small")
    panel.div(onOffText(view.current.enabled)) { addCssClasses("fw-bold") }
    panel.div(meetingRewardValuesSummary(view.current)) { addCssClasses("small") }
    panel.div(committeeNames(view.current.committeeIds, data.committees)) { addCssClasses("small") }
    view.resolution?.let {
        panel.div(sanitizeUntrustedI18nText(gettext("Beschluss: %1 – %2", it.number, it.title))) { addCssClasses("small") }
    }

    val open = view.openProposal
    if (open != null) {
        renderOpenProposal(panel, data, open, reload)
    } else {
        renderProposalForm(panel, data, reload)
    }
    if (view.current.enabled) {
        panel.actionButton(ActionIcon.CANCEL, tr("Ausschalten"), style = ButtonStyle.OUTLINEDANGER) {
            onClick {
                confirmDialog(
                    title = tr("Anerkennung ausschalten?"),
                    message = tr("Danach werden keine Sitzungen mehr abgerechnet. Bereits freigegebene Buchungen bleiben bestehen."),
                    confirmLabel = tr("Ausschalten"),
                ) {
                    AppScope.launch {
                        if (meetingRewardGuarded { rpcService<IMeetingRewardService>().disableMeetingReward() } != null) {
                            notifySuccess(tr("Die Anerkennung ist ausgeschaltet."))
                        }
                        reload()
                    }
                }
            }
        }
    }
    if (view.history.isNotEmpty()) {
        panel.h3(tr("Bisherige Änderungen")) { addCssClasses("h6 mt-3") }
        view.history.forEach { change ->
            panel.div(
                sanitizeUntrustedI18nText(
                    gettext(
                        "%1 · %2 · %3",
                        formatSystemDateTime(change.proposedAt),
                        meetingRewardChangeKindLabel(change.kind),
                        meetingRewardChangeStatusLabel(change.status),
                    ),
                ),
            ) { addCssClasses("small") }
        }
    }
}

private fun renderOpenProposal(
    panel: SimplePanel,
    data: SettingsData,
    open: MeetingRewardPolicyChangeDto,
    reload: () -> Unit,
) {
    val view = data.view
    panel.h3(tr("Offener Vorschlag")) { addCssClasses("h6 mt-3") }
    panel.div(
        sanitizeUntrustedI18nText(gettext("Vorgeschlagen von %1 – wartet auf Bestätigung durch einen anderen Admin", open.proposedByName)),
    )
    panel.div(sanitizeUntrustedI18nText(gettext("Bisher: %1", meetingRewardValuesSummary(open.old)))) { addCssClasses("small") }
    panel.div(sanitizeUntrustedI18nText(gettext("Neu: %1", meetingRewardValuesSummary(open.new)))) { addCssClasses("small") }
    panel.div(onOffText(open.new.enabled)) { addCssClasses("small") }
    panel.div(committeeNames(open.new.committeeIds, data.committees)) { addCssClasses("small") }
    open.resolution?.let {
        panel.div(sanitizeUntrustedI18nText(gettext("Beschluss: %1 – %2", it.number, it.title))) { addCssClasses("small") }
    }
    open.resolutionReference?.let { panel.div(sanitizeUntrustedI18nText(gettext("Hinweis: %1", it))) { addCssClasses("small") } }
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    if (view.canConfirmOpen) {
        row.actionButton(ActionIcon.APPROVE, tr("Bestätigen"), style = ButtonStyle.PRIMARY) {
            onClick {
                runGuardedAction(this) {
                    if (meetingRewardGuarded { rpcService<IMeetingRewardService>().confirmMeetingRewardPolicyChange(open.id) } != null) {
                        notifySuccess(tr("Die Änderung ist in Kraft."))
                    }
                    reload()
                }
            }
        }
    }
    row.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY) {
        onClick {
            runGuardedAction(this) {
                if (meetingRewardGuarded { rpcService<IMeetingRewardService>().withdrawMeetingRewardPolicyChange(open.id) } != null) {
                    notifySuccess(tr("Der Vorschlag wurde zurückgezogen."))
                }
                reload()
            }
        }
    }
    view.confirmBlockedReason?.let { panel.div(meetingRewardBlockedText(it)) { addCssClasses("text-muted small") } }
}

/** The text a field is pre-filled with: the amount in the notation of the UI language (what [parseAmountInput] reads back). */
private fun meetingRewardAmountText(value: Decimal): String = formatPlainAmountIn(I18n.language, value)

private fun meetingRewardAmountRule(raw: String): FieldCheck =
    when (val parsed = parseAmountInput(raw, allowZero = true, enforceMaxAmount = false)) {
        is AmountInput.Valid -> FieldCheck.Ok
        is AmountInput.Invalid -> FieldCheck.Invalid(parsed.reason)
        AmountInput.Empty -> FieldCheck.Invalid(gettext("Bitte einen Betrag eingeben."))
    }

private fun renderProposalForm(
    panel: SimplePanel,
    data: SettingsData,
    reload: () -> Unit,
) {
    val current = data.view.current
    panel.h3(tr("Änderung vorschlagen")) { addCssClasses("h6 mt-3") }
    if (data.resolutions.isEmpty()) {
        panel.p(tr("Es gibt noch keinen angenommenen Beschluss einer Mitgliederversammlung."), className = "text-muted small")
        return
    }
    val form = panel.lapisForm()
    val enabled = form.checkField(label = tr("Anerkennung einschalten"), value = current.enabled)
    val base =
        form.textField(
            label = tr("Grundbetrag"),
            value = meetingRewardAmountText(current.attendanceAmount),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    val chair =
        form.textField(
            label = tr("Zuschlag Vorsitz"),
            value = meetingRewardAmountText(current.bonusChair),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    val deputy =
        form.textField(
            label = tr("Zuschlag Stellvertretung"),
            value = meetingRewardAmountText(current.bonusDeputyChair),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    val secretary =
        form.textField(
            label = tr("Zuschlag Schriftführung"),
            value = meetingRewardAmountText(current.bonusSecretary),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    val director =
        form.textField(
            label = tr("Zuschlag Geschäftsführung"),
            value = meetingRewardAmountText(current.bonusManagingDirector),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    val capYear =
        form.textField(
            label = tr("Obergrenze je Person und Jahr"),
            value = meetingRewardAmountText(current.capPerPersonPerYear),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    val capMeeting =
        form.textField(
            label = tr("Obergrenze je Sitzung"),
            value = meetingRewardAmountText(current.capPerMeeting),
            required = true,
            rule = ::meetingRewardAmountRule,
        )
    form.panel.p(tr("Für diese Gremien gilt die Anerkennung:"), className = "mb-1")
    val committeeFields =
        data.committees.map { committee ->
            committee to form.checkField(label = sanitizeUntrustedI18nText(committee.name), value = committee.id in current.committeeIds)
        }
    val resolution =
        form.selectField(
            label = tr("Beschluss der Mitgliederversammlung"),
            options = data.resolutions.map { it.resolutionId to sanitizeUntrustedI18nText("${it.number} – ${it.title}") },
            value = data.view.resolution?.resolutionId ?: data.resolutions.first().resolutionId,
            required = true,
        )
    val reference = form.textField(label = tr("Hinweis zum Beschluss (optional, z. B. TOP 7)"))
    val propose = newActionButton(ActionIcon.SAVE, tr("Änderung vorschlagen"), ButtonStyle.PRIMARY)
    form.buttons(primary = propose)
    propose.onClick {
        form.submit(propose) {
            fun amount(field: LapisField): Decimal =
                (parseAmountInput(field.value, allowZero = true, enforceMaxAmount = false) as AmountInput.Valid).value
            val input =
                MeetingRewardPolicyProposalInput(
                    values =
                        MeetingRewardPolicyValuesDto(
                            enabled = enabled.value == "true",
                            attendanceAmount = amount(base),
                            bonusChair = amount(chair),
                            bonusDeputyChair = amount(deputy),
                            bonusSecretary = amount(secretary),
                            bonusManagingDirector = amount(director),
                            capPerPersonPerYear = amount(capYear),
                            capPerMeeting = amount(capMeeting),
                            committeeIds = committeeFields.filter { it.second.value == "true" }.map { it.first.id },
                        ),
                    resolutionId = resolution.value.orEmpty(),
                    resolutionReference = reference.value.trim().takeIf { it.isNotEmpty() },
                )
            if (meetingRewardGuarded { rpcService<IMeetingRewardService>().proposeMeetingRewardPolicyChange(input) } != null) {
                notifySuccess(tr("Der Vorschlag wurde gespeichert."))
                reload()
            }
        }
    }
}
