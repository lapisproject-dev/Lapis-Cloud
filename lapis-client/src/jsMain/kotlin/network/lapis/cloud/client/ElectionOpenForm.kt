package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.rpc.IElectionService

/*
 * V1.9.22 -- "Wahl eröffnen", the entry into the elections screens, offered in the resolution section of a scheduled motion next to
 * the two existing ways to decide it. Only the three election types the server actually supports are offered (the two reserved
 * types are rejected by `openElection`).
 */

/** The offered election types, in the order of the form. */
private val OFFERED_TYPES = listOf(ElectionType.YES_NO, ElectionType.SINGLE_CHOICE, ElectionType.MULTI_CHOICE)

private const val MIN_SEATS = 2
private const val MAX_SEATS = 25
private const val MIN_APPROVALS = 2
private const val MAX_APPROVALS = 25
private const val DEFAULT_PERCENT = 50

internal fun renderOpenElectionForm(
    panel: SimplePanel,
    motion: MotionDto,
    committees: List<CommitteeDto>,
    onOpened: (ElectionDto) -> Unit,
) {
    val holder = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    holder.p(tr("Wahl eröffnen")) { addCssClass("fw-bold") }
    holder.p(
        tr("Eine Wahl entscheidet den Antrag mit einer Stimme je wahlberechtigtem Mitglied, als Ja/Nein-Wahl oder als Wahl von Personen."),
    ) {
        addCssClasses("text-muted small mb-0")
    }
    val form = holder.lapisForm()
    val typeField =
        form.selectField(
            label = tr("Wahlart"),
            options = OFFERED_TYPES.map { it.name to electionTypeLabel(it) },
            value = ElectionType.YES_NO.name,
            required = true,
        )
    val secretField = form.checkField(label = tr("Geheime Wahl"), value = true)
    val openWarning =
        form.panel.div(tr("Bei einer offenen Wahl werden die Stimmen mit Namen gespeichert und sind für alle Mitglieder sichtbar.")) {
            addCssClasses("alert alert-warning mb-0")
            hide()
        }
    val committeeField =
        form.searchableSelectField(
            label = tr("Zielgremium"),
            options = untrustedOptions(committees.filter { it.active }.map { it.id to it.name }),
            hint = tr("Das Gremium, in das die Gewählten aufgenommen werden."),
        )
    val roleField =
        form.searchableSelectField(
            label = tr("Rolle im Zielgremium"),
            options = CommitteeRole.entries.map { it.name to committeeRoleLabel(it) },
            value = CommitteeRole.MEMBER.name,
        )
    val fixedSeats = form.panel.div(tr("Zu besetzende Sitze: 1")) { addCssClasses("text-muted") }

    fun currentType(): ElectionType = ElectionType.valueOf(typeField.value.ifBlank { ElectionType.YES_NO.name })

    val seatsField =
        form.textField(
            label = tr("Sitze"),
            value = "2",
            rule = { if (currentType() == ElectionType.MULTI_CHOICE) FormRules.intInRange(it, MIN_SEATS, MAX_SEATS) else FieldCheck.Ok },
        )
    val percentField =
        form.textField(
            label = tr("Erforderlicher Anteil in Prozent"),
            value = DEFAULT_PERCENT.toString(),
            rule = { if (currentType() == ElectionType.MULTI_CHOICE) FieldCheck.Ok else FormRules.intInRange(it, 1, 100) },
        )
    val explanation = form.panel.vPanel(spacing = 2)
    val approvalsField =
        form.textField(
            label = tr("Erforderliche Freigaben der Auszählung"),
            value = MIN_APPROVALS.toString(),
            required = true,
            hint = tr("So viele Mitglieder des Wahlausschusses müssen die Auszählung freigeben (mindestens 2)."),
            rule = { FormRules.intInRange(it, MIN_APPROVALS, MAX_APPROVALS) },
        )

    fun update() {
        val type = currentType()
        val personnel = isPersonnelElection(type)
        committeeField.setVisible(personnel)
        roleField.setVisible(personnel)
        if (type == ElectionType.SINGLE_CHOICE) fixedSeats.show() else fixedSeats.hide()
        seatsField.setVisible(type == ElectionType.MULTI_CHOICE)
        percentField.setVisible(type != ElectionType.MULTI_CHOICE)
        if ((secretField.control as CheckBox).value) openWarning.hide() else openWarning.show()
        explanation.removeAll()
        val percent =
            percentField.value
                .trim()
                .toIntOrNull()
                ?.takeIf { it in 1..100 } ?: DEFAULT_PERCENT
        majorityExplanation(type, percent).forEach { line -> explanation.div(line) { addCssClasses("text-muted small") } }
    }
    typeField.subscribe { update() }
    secretField.subscribe { update() }
    percentField.subscribe { update() }
    form.crossFieldRule(focusOn = committeeField.control as? io.kvision.core.Widget) {
        if (isPersonnelElection(currentType()) && committeeField.value.isBlank()) {
            FieldCheck.Invalid(gettext("Bitte ein Zielgremium wählen."))
        } else {
            FieldCheck.Ok
        }
    }
    update()

    val openButton = Button(tr("Wahl eröffnen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = openButton)
    openButton.onClick {
        form.submit(openButton) {
            val type = currentType()
            val personnel = isPersonnelElection(type)
            val input =
                ElectionOpenInput(
                    motionId = motion.id,
                    electionType = type,
                    secret = (secretField.control as CheckBox).value,
                    seatCount = if (type == ElectionType.MULTI_CHOICE) seatsField.value.trim().toInt() else 1,
                    targetCommitteeId = if (personnel) committeeField.value else null,
                    targetRole = if (personnel) CommitteeRole.valueOf(roleField.value.ifBlank { CommitteeRole.MEMBER.name }) else null,
                    requiredMajorityPercent = if (type == ElectionType.MULTI_CHOICE) DEFAULT_PERCENT else percentField.value.trim().toInt(),
                    tallyThreshold = approvalsField.value.trim().toInt(),
                )
            val opened =
                electionGuarded(gettext("Die Wahl konnte nicht eröffnet werden. Bitte Ansicht aktualisieren.")) {
                    rpcService<IElectionService>().openElection(input)
                }
            if (opened != null) {
                notifySuccess(tr("Wahl eröffnet."))
                onOpened(opened)
            }
        }
    }
}
