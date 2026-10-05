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
import network.lapis.cloud.shared.domain.DisclosureRules
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
private const val NEUTRAL_PERCENT = 50
private const val MAX_DENOMINATOR = 100

/** The offered required majorities. The first four are fractions of the decisive votes; [CUSTOM] shows two number fields. */
private enum class MajorityPreset(
    val numerator: Int,
    val denominator: Int,
) {
    HALF(1, 2),
    TWO_THIRDS(2, 3),
    THREE_QUARTERS(3, 4),
    CUSTOM(0, 0),
}

private fun majorityPresetLabel(preset: MajorityPreset): String =
    when (preset) {
        MajorityPreset.HALF -> gettext("Einfache Mehrheit (mehr Ja als Nein)")
        MajorityPreset.TWO_THIRDS -> gettext("Zwei Drittel")
        MajorityPreset.THREE_QUARTERS -> gettext("Drei Viertel")
        MajorityPreset.CUSTOM -> gettext("Andere Mehrheit")
    }

internal fun renderOpenElectionForm(
    panel: SimplePanel,
    motion: MotionDto,
    committees: List<CommitteeDto>,
    onConflict: () -> Unit = {},
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
    // V1.9.53: the minimum-participation rule, shown while "Geheime Wahl" is ticked.
    val minimumParticipationHint =
        form.panel.div(
            gettext(
                "Bei weniger als %1 abgegebenen Stimmzetteln werden nur das Ergebnis und die Beteiligung angezeigt, keine Stimmenzahlen.",
                DisclosureRules.MIN_ANONYMOUS_RESPONSES,
            ),
        ) { addCssClasses("text-muted small") }
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
    // A select cannot be hidden on its own (LapisField.setVisible is for text controls), so it lives in a box that is shown or hidden.
    val majorityBox = form.panel.vPanel(spacing = 2)
    val majorityField =
        form.selectField(
            host = majorityBox,
            label = tr("Erforderliche Mehrheit"),
            options = MajorityPreset.entries.map { it.name to majorityPresetLabel(it) },
            value = MajorityPreset.HALF.name,
            required = true,
        )

    fun currentPreset(): MajorityPreset = MajorityPreset.valueOf(majorityField.value.ifBlank { MajorityPreset.HALF.name })

    fun customActive(): Boolean = currentType() != ElectionType.MULTI_CHOICE && currentPreset() == MajorityPreset.CUSTOM

    val customNumeratorField =
        form.textField(
            label = tr("Mindestens"),
            value = "3",
            rule = { if (customActive()) FormRules.intInRange(it, 1, MAX_DENOMINATOR) else FieldCheck.Ok },
        )
    val customDenominatorField =
        form.textField(
            label = tr("von"),
            value = "5",
            hint = tr("Zum Beispiel mindestens 3 von 5 Stimmen."),
            rule = { if (customActive()) FormRules.intInRange(it, 1, MAX_DENOMINATOR) else FieldCheck.Ok },
        )

    /** The chosen majority as numerator/denominator; `null` for a multiple-choice election (plurality) and for unusable custom input. */
    fun currentFraction(): Pair<Int, Int>? {
        if (currentType() == ElectionType.MULTI_CHOICE) return null
        val preset = currentPreset()
        if (preset != MajorityPreset.CUSTOM) return preset.numerator to preset.denominator
        val numerator = customNumeratorField.value.trim().toIntOrNull() ?: return null
        val denominator = customDenominatorField.value.trim().toIntOrNull() ?: return null
        return if (numerator in 1..denominator && denominator <= MAX_DENOMINATOR && 2 * numerator >= denominator) {
            numerator to denominator
        } else {
            null
        }
    }
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
        if (type == ElectionType.MULTI_CHOICE) majorityBox.hide() else majorityBox.show()
        customNumeratorField.setVisible(customActive())
        customDenominatorField.setVisible(customActive())
        if ((secretField.control as CheckBox).value) {
            openWarning.hide()
            minimumParticipationHint.show()
        } else {
            openWarning.show()
            minimumParticipationHint.hide()
        }
        explanation.removeAll()
        val (numerator, denominator) = currentFraction() ?: (MajorityPreset.HALF.numerator to MajorityPreset.HALF.denominator)
        majorityExplanation(type, numerator, denominator).forEach { line -> explanation.div(line) { addCssClasses("text-muted small") } }
    }
    typeField.subscribe { update() }
    secretField.subscribe { update() }
    majorityField.subscribe { update() }
    customNumeratorField.subscribe { update() }
    customDenominatorField.subscribe { update() }
    form.crossFieldRule(focusOn = committeeField.control as? io.kvision.core.Widget) {
        if (isPersonnelElection(currentType()) && committeeField.value.isBlank()) {
            FieldCheck.Invalid(gettext("Bitte ein Zielgremium wählen."))
        } else {
            FieldCheck.Ok
        }
    }
    form.crossFieldRule(focusOn = customNumeratorField.control as? io.kvision.core.Widget) {
        val numerator = customNumeratorField.value.trim().toIntOrNull()
        val denominator = customDenominatorField.value.trim().toIntOrNull()
        if (customActive() && numerator != null && denominator != null && (numerator > denominator || 2 * numerator < denominator)) {
            FieldCheck.Invalid(gettext("Die Mehrheit muss mindestens die Hälfte betragen."))
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
            val fraction = currentFraction()
            val input =
                ElectionOpenInput(
                    motionId = motion.id,
                    electionType = type,
                    secret = (secretField.control as CheckBox).value,
                    seatCount = if (type == ElectionType.MULTI_CHOICE) seatsField.value.trim().toInt() else 1,
                    targetCommitteeId = if (personnel) committeeField.value else null,
                    targetRole = if (personnel) CommitteeRole.valueOf(roleField.value.ifBlank { CommitteeRole.MEMBER.name }) else null,
                    // The percent is only the legacy display value for old clients; the fraction is what counts.
                    requiredMajorityPercent = fraction?.let { majorityPercentCeil(it.first, it.second) } ?: NEUTRAL_PERCENT,
                    requiredMajorityNumerator = fraction?.first,
                    requiredMajorityDenominator = fraction?.second,
                    tallyThreshold = approvalsField.value.trim().toInt(),
                )
            val opened =
                electionGuarded(
                    conflictMessage = gettext("Der Antrag wurde inzwischen anders bearbeitet. Die Ansicht wurde aktualisiert."),
                    onConflict = onConflict,
                ) {
                    rpcService<IElectionService>().openElection(input)
                }
            if (opened != null) {
                notifySuccess(tr("Wahl eröffnet."))
                onOpened(opened)
            }
        }
    }
}
