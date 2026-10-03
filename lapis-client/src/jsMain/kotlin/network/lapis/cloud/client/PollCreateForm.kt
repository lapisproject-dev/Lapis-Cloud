package network.lapis.cloud.client

import io.kvision.form.check.radioGroup
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.isConsensus
import network.lapis.cloud.shared.rpc.IPollService

/*
 * V1.9.31 "Umfragen" -- "Neue Umfrage". Offered only to those who may start polls (`canCreatePolls`, a hint: the server re-checks).
 * The draft checks live in `PollCreateDraft.kt` and mirror `PollRules` and the server's validation exactly, so an obviously invalid draft
 * never makes a round trip -- the server stays the authority. The option fields are real form fields (added and removed at run time), so
 * the form grammar (labels, errors at the field, single-shot submit) applies to them like to every other field.
 *
 * V1.9.40: the form is collapsed behind the "Neue Umfrage" button of the title row (rule R36B, see `PollListView.kt`).
 * V1.9.41: a kind radio (single choice / consensus decision / consensus ranking) and, for the consensus kinds only, an optional
 * explanation per option behind an "Erklärung hinzufügen" switch. A kind change hides the explanations WITHOUT deleting the typed text.
 */

private const val CHOICE_24H = "h24"
private const val CHOICE_3D = "h72"
private const val CHOICE_7D = "h168"
private const val CHOICE_14D = "h336"
private const val CHOICE_30D = "h720"
private const val CHOICE_CUSTOM = "custom"
private const val CHOICE_NONE = "none"

private fun deadlineChoiceOf(value: String): PollDeadlineChoice =
    when (value) {
        CHOICE_24H -> PollDeadlineChoice.Preset(24)
        CHOICE_3D -> PollDeadlineChoice.Preset(72)
        CHOICE_14D -> PollDeadlineChoice.Preset(336)
        CHOICE_30D -> PollDeadlineChoice.Preset(720)
        CHOICE_CUSTOM -> PollDeadlineChoice.Custom
        CHOICE_NONE -> PollDeadlineChoice.None
        else -> PollDeadlineChoice.Preset(168)
    }

private fun localNow(): LocalDateTime = organizationNow()

/** The sentence that explains the chosen kind (shown live under the radio, so only one sentence is on screen). */
private fun pollKindHint(kind: PollKind): String =
    when (kind) {
        PollKind.SINGLE_CHOICE -> gettext("Jede Person wählt eine Option.")
        PollKind.SK_DECISION ->
            gettext(
                "Jede Person bewertet jede Option von 0 (kein Widerstand) bis 10 (starker Widerstand). " +
                    "Vorn liegt die Option mit dem geringsten Widerstand. „Keine Änderung“ ist immer dabei.",
            )
        PollKind.SK_PRIORITY ->
            gettext(
                "Jede Person bewertet jede Option von 0 (kein Widerstand) bis 10 (starker Widerstand). " +
                    "Das Ergebnis ist eine Rangliste nach Widerstand.",
            )
    }

/** The label of a kind in the radio. */
internal fun pollKindChoiceLabel(kind: PollKind): String =
    when (kind) {
        PollKind.SINGLE_CHOICE -> gettext("Einzelauswahl")
        PollKind.SK_DECISION -> gettext("Konsensieren: Entscheidung")
        PollKind.SK_PRIORITY -> gettext("Konsensieren: Rangliste")
    }

/** One option block: the option field with its (optional) remove button, and the optional explanation behind a switch. */
private class OptionRow(
    val block: VPanel,
    val row: HPanel,
    val field: LapisField,
    val remove: Button?,
    val explainButton: Button,
    val explainField: LapisField,
    val explainCounter: io.kvision.html.Div,
) {
    /** Whether the member opened the explanation of this option (kept while the kind switches, so no typed text is lost). */
    var explainOpen = false
}

/**
 * Builds the create form into the receiver (the host of the collapsed form) and returns its [FormSnapshot]. [close] collapses the form
 * (`close(true)` after a successful save, `close(false)` for Cancel); [onCreated] gets the created poll after the form has closed.
 */
internal fun SimplePanel.renderPollCreateForm(
    close: (saved: Boolean) -> Unit,
    onCreated: (PollDto) -> Unit,
): FormSnapshot {
    val holder = vPanel(spacing = 8)
    val form = holder.lapisForm()

    val questionField = form.textField(label = tr("Frage"), required = true)
    val questionCounter = form.panel.div("") { addCssClasses("text-muted small") }
    val descriptionField = form.textAreaField(label = tr("Beschreibung (optional)"), rows = 3)

    val kindRadio =
        form.panel.radioGroup(
            options = PollKind.entries.map { it.name to pollKindChoiceLabel(it) },
            value = PollKind.SINGLE_CHOICE.name,
            label = tr("Art der Umfrage"),
        )
    form.register(control = kindRadio, label = tr("Art der Umfrage"), required = true)
    val kindHint = form.panel.div("") { addCssClasses("text-muted small") }
    val passiveHint =
        form.panel.div(tr("„Keine Änderung“ ist immer dabei und zählt nicht zu den Optionen.")) {
            addCssClasses("text-muted small")
        }

    fun kind(): PollKind = PollKind.entries.firstOrNull { it.name == kindRadio.value } ?: PollKind.SINGLE_CHOICE

    val optionsPanel = form.panel.vPanel(spacing = 6)
    val rows = mutableListOf<OptionRow>()
    var explanationCounter = 0
    val addOptionButton = newActionButton(ActionIcon.ADD, tr("Option hinzufügen"), ButtonStyle.OUTLINESECONDARY)
    val reason = form.panel.div("") { addCssClasses("text-muted small") }
    val submitButton = Button(tr("Umfrage starten"), style = ButtonStyle.PRIMARY)

    val deadlineField =
        form.selectField(
            label = tr("Frist"),
            options =
                listOf(
                    CHOICE_24H to gettext("24 Stunden"),
                    CHOICE_3D to gettext("3 Tage"),
                    CHOICE_7D to gettext("7 Tage"),
                    CHOICE_14D to gettext("14 Tage"),
                    CHOICE_30D to gettext("30 Tage"),
                    CHOICE_CUSTOM to gettext("Eigenes Datum"),
                    CHOICE_NONE to gettext("Ohne Frist"),
                ),
            value = CHOICE_7D,
            required = true,
        )
    val customField =
        form.textField(
            label = tr("Datum und Uhrzeit der Frist"),
            type = InputType.DATETIME_LOCAL,
            rule = { value ->
                if (deadlineChoiceOf(deadlineField.value) != PollDeadlineChoice.Custom) {
                    FieldCheck.Ok
                } else {
                    FormRules.localDateTime(value)
                }
            },
        )
    customField.setVisible(false)
    val noDeadlineHint = form.panel.div(tr("Sie müssen die Umfrage dann selbst schließen.")) { addCssClasses("text-muted small") }
    noDeadlineHint.hide()

    fun draft(): PollDraftError? {
        val choice = deadlineChoiceOf(deadlineField.value)
        val custom = runCatching { LocalDateTime.parse(customField.value.trim()) }.getOrNull()
        val now = localNow()
        return validatePollDraft(
            question = questionField.value,
            description = descriptionField.value,
            options = rows.map { it.field.value },
            deadline = pollDeadlineFor(choice = choice, customLocal = custom, now = now),
            hasDeadline = choice != PollDeadlineChoice.None,
            now = now,
            explanations = rows.map { it.explainField.value },
            kind = kind(),
        )
    }

    /** Switches the kind-dependent parts: the hints and, per option, the explanation switch and (if opened) its field. */
    fun paintKind() {
        val consensus = kind().isConsensus
        kindHint.content = pollKindHint(kind())
        if (kind() == PollKind.SK_DECISION) passiveHint.show() else passiveHint.hide()
        rows.forEach { option ->
            if (consensus) option.explainButton.show() else option.explainButton.hide()
            val showField = consensus && option.explainOpen
            option.explainField.setVisible(showField)
            if (showField) option.explainCounter.show() else option.explainCounter.hide()
        }
    }

    fun update() {
        questionCounter.content = gettext("%1/%2", questionField.value.length, PollRules.MAX_QUESTION_LENGTH)
        val error = draft()
        submitButton.disabled = error != null
        reason.content = error?.let { pollDraftErrorText(it) }.orEmpty()
        addOptionButton.disabled = rows.size >= PollRules.MAX_OPTIONS
        rows.forEach { option ->
            val length = option.explainField.value.length
            option.explainCounter.content =
                if (length >= PollRules.EXPLANATION_COUNTER_FROM) gettext("%1/%2", length, PollRules.MAX_EXPLANATION_LENGTH) else ""
        }
    }

    fun relabelRows() {
        rows.forEachIndexed { index, option ->
            option.field.relabel(gettext("Option %1", index + 1))
            option.explainField.relabel(gettext("Erklärung zu Option %1 (optional)", index + 1))
            option.remove?.tableActionTooltip(gettext("Option %1 entfernen", index + 1))
        }
    }

    fun addOptionRow(late: Boolean) {
        val number = rows.size + 1
        val block = optionsPanel.vPanel(spacing = 4)
        val row = block.hPanel(spacing = 8) { addCssClasses("align-items-start") }
        val holderField = arrayOfNulls<LapisField>(1)
        val field =
            form.textField(
                label = gettext("Option %1", number),
                required = true,
                host = row,
                rule = { value ->
                    val mine = rows.indexOfFirst { it.field === holderField[0] }
                    val key = PollRules.optionKey(PollRules.normalizeText(value))
                    when {
                        PollRules.normalizeText(value).length > PollRules.MAX_OPTION_LENGTH ->
                            FieldCheck.Invalid(gettext("Die Option ist zu lang (höchstens %1 Zeichen).", PollRules.MAX_OPTION_LENGTH))
                        key.isNotEmpty() &&
                            mine > 0 &&
                            rows.take(mine).any { PollRules.optionKey(PollRules.normalizeText(it.field.value)) == key } ->
                            FieldCheck.Invalid(gettext("Diese Option gibt es schon."))
                        else -> FieldCheck.Ok
                    }
                },
            )
        holderField[0] = field
        if (late) field.appendRequiredMark()
        val remove =
            if (rows.size >= PollRules.MIN_OPTIONS) {
                row.tableActionButton(ActionIcon.REMOVE, gettext("Option %1 entfernen", number))
            } else {
                null
            }

        // The optional explanation: a switch under the option, the field only once the switch was used.
        explanationCounter++
        val explainHostId = "lapis-poll-explanation-$explanationCounter"
        val explainButton = newActionButton(ActionIcon.ADD, tr("Erklärung hinzufügen"), style = ButtonStyle.OUTLINESECONDARY)
        explainButton.addCssClass("btn-sm")
        explainButton.setAttribute("aria-expanded", "false")
        explainButton.setAttribute("aria-controls", explainHostId)
        block.add(explainButton)
        val explainHost = block.vPanel(spacing = 4)
        explainHost.id = explainHostId
        val holderExplain = arrayOfNulls<LapisField>(1)
        val explainField =
            form.textAreaField(
                label = gettext("Erklärung zu Option %1 (optional)", number),
                rows = 3,
                host = explainHost,
                rule = { value ->
                    val mine = rows.indexOfFirst { it.explainField === holderExplain[0] }
                    if (kind().isConsensus && PollRules.normalizeExplanation(value) == PublicTextNormalization.TooLong) {
                        FieldCheck.Invalid(
                            gettext(
                                "Die Erklärung zu Option %1 ist zu lang (höchstens %2 Zeichen).",
                                mine + 1,
                                PollRules.MAX_EXPLANATION_LENGTH,
                            ),
                        )
                    } else {
                        FieldCheck.Ok
                    }
                },
            )
        holderExplain[0] = explainField
        val explainCounter = explainHost.div("") { addCssClasses("text-muted small") }
        explainField.setVisible(false)
        explainCounter.hide()
        explainButton.hide()

        val option =
            OptionRow(
                block = block,
                row = row,
                field = field,
                remove = remove,
                explainButton = explainButton,
                explainField = explainField,
                explainCounter = explainCounter,
            )
        rows += option
        field.subscribe { update() }
        explainField.subscribe { update() }
        explainButton.onClick {
            option.explainOpen = !option.explainOpen
            explainButton.setAttribute("aria-expanded", option.explainOpen.toString())
            explainButton.text = if (option.explainOpen) tr("Erklärung ausblenden") else tr("Erklärung hinzufügen")
            paintKind()
            update()
        }
        remove?.onClick {
            form.unregister(option.field)
            form.unregister(option.explainField)
            optionsPanel.remove(option.block)
            rows.remove(option)
            relabelRows()
            update()
        }
        paintKind()
        update()
    }

    repeat(PollRules.MIN_OPTIONS) { addOptionRow(late = false) }
    form.panel.add(addOptionButton)
    addOptionButton.onClick {
        if (rows.size < PollRules.MAX_OPTIONS) addOptionRow(late = true)
    }

    questionField.subscribe { update() }
    descriptionField.subscribe { update() }
    customField.subscribe { update() }
    kindRadio.subscribe {
        paintKind()
        update()
    }
    deadlineField.subscribe {
        val choice = deadlineChoiceOf(deadlineField.value)
        customField.setVisible(choice == PollDeadlineChoice.Custom)
        if (choice == PollDeadlineChoice.None) noDeadlineHint.show() else noDeadlineHint.hide()
        update()
    }

    form.panel.div(
        tr(
            "Unverbindliche Umfrage – ein Stimmungsbild, kein Beschluss. " +
                "Antworten sind anonym; das Ergebnis ist erst nach dem Ende sichtbar.",
        ),
    ) { addCssClasses("alert alert-info mb-0") }

    form.buttons(primary = submitButton, cancel = collapseCancelButton(close))
    submitButton.onClick {
        if (draft() != null || !form.validateAndReport()) return@onClick
        form.runBusy(submitButton, restoreDisabled = { draft() != null }) {
            val choice = deadlineChoiceOf(deadlineField.value)
            val custom = runCatching { LocalDateTime.parse(customField.value.trim()) }.getOrNull()
            val chosenKind = kind()
            val input =
                PollCreateInput(
                    question = questionField.value,
                    description = descriptionField.value.ifBlank { null },
                    options = rows.map { it.field.value },
                    closesAt = pollDeadlineFor(choice = choice, customLocal = custom, now = localNow()),
                    kind = chosenKind,
                    // The classic kind never sends an explanation (the server rejects one); the consensus kinds send one entry per option.
                    optionExplanations =
                        if (chosenKind.isConsensus) rows.map { it.explainField.value.ifBlank { null } } else emptyList(),
                )
            val created =
                pollGuarded(
                    conflictMessage =
                        gettext(
                            "Zurzeit können Sie keine weitere Umfrage starten – zu viele sind offen oder wurden kürzlich gestartet. " +
                                "Bitte versuchen Sie es später erneut.",
                        ),
                    badRequestMessage = gettext("Bitte prüfen Sie die Angaben, insbesondere die Frist."),
                ) {
                    rpcService<IPollService>().createPoll(input)
                }
            if (created != null) {
                close(true)
                onCreated(created)
            }
        }
    }
    paintKind()
    update()
    return form.snapshot()
}
