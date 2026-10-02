package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.rpc.IPollService
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/*
 * V1.9.31 "Umfragen" -- "Umfrage erstellen". Offered only to those who may start polls (`canCreatePolls`, a hint: the server re-checks).
 * The checks below mirror `PollRules` and the server's validation exactly, so an obviously invalid draft never makes a round trip -- the
 * server stays the authority. The option fields are real form fields (added and removed at run time), so the form grammar (labels, errors
 * at the field, single-shot submit) applies to them like to every other field.
 */

/** How the deadline is chosen: a preset in hours, a date of one's own, or none. */
internal sealed interface PollDeadlineChoice {
    data class Preset(
        val hours: Int,
    ) : PollDeadlineChoice

    data object Custom : PollDeadlineChoice

    data object None : PollDeadlineChoice
}

/** What is wrong with a draft; the first problem found, in the order the form reads. */
internal sealed interface PollDraftError {
    data object QuestionMissing : PollDraftError

    data object QuestionTooLong : PollDraftError

    data object DescriptionTooLong : PollDraftError

    data object TooFewOptions : PollDraftError

    data object TooManyOptions : PollDraftError

    data class OptionEmpty(
        val index: Int,
    ) : PollDraftError

    data class OptionTooLong(
        val index: Int,
    ) : PollDraftError

    data class DuplicateOption(
        val index: Int,
    ) : PollDraftError

    data object DeadlineInvalid : PollDraftError

    data object DeadlineTooSoon : PollDraftError

    data object DeadlineTooFar : PollDraftError
}

/** The deadline a [choice] stands for: `now` plus the preset hours, the typed date, or none. Wall-clock arithmetic (the browser's local time). */
internal fun pollDeadlineFor(
    choice: PollDeadlineChoice,
    customLocal: LocalDateTime?,
    now: LocalDateTime,
): LocalDateTime? =
    when (choice) {
        is PollDeadlineChoice.Preset -> now.plusWallClock(choice.hours.hours)
        PollDeadlineChoice.Custom -> customLocal
        PollDeadlineChoice.None -> null
    }

private fun LocalDateTime.plusWallClock(duration: kotlin.time.Duration): LocalDateTime =
    (toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

/**
 * The first problem of a draft, or `null` if it is valid. Texts are normalized like the server does (trim, collapse whitespace) before
 * their length is measured; duplicates are found by `PollRules.optionKey`. [deadline] is only looked at when [hasDeadline].
 */
internal fun validatePollDraft(
    question: String,
    description: String,
    options: List<String>,
    deadline: LocalDateTime?,
    hasDeadline: Boolean,
    now: LocalDateTime,
): PollDraftError? {
    val normalizedQuestion = PollRules.normalizeText(question)
    val normalizedOptions = options.map { PollRules.normalizeText(it) }
    return when {
        normalizedQuestion.isEmpty() -> PollDraftError.QuestionMissing
        normalizedQuestion.length > PollRules.MAX_QUESTION_LENGTH -> PollDraftError.QuestionTooLong
        description.trim().length > PollRules.MAX_DESCRIPTION_LENGTH -> PollDraftError.DescriptionTooLong
        normalizedOptions.size < PollRules.MIN_OPTIONS -> PollDraftError.TooFewOptions
        normalizedOptions.size > PollRules.MAX_OPTIONS -> PollDraftError.TooManyOptions
        else -> optionProblem(normalizedOptions) ?: deadlineProblem(deadline = deadline, hasDeadline = hasDeadline, now = now)
    }
}

private fun optionProblem(normalizedOptions: List<String>): PollDraftError? {
    normalizedOptions.forEachIndexed { index, text ->
        if (text.isEmpty()) return PollDraftError.OptionEmpty(index)
        if (text.length > PollRules.MAX_OPTION_LENGTH) return PollDraftError.OptionTooLong(index)
    }
    val seen = mutableSetOf<String>()
    normalizedOptions.forEachIndexed {
        index,
        text,
        ->
        if (!seen.add(PollRules.optionKey(text))) return PollDraftError.DuplicateOption(index)
    }
    return null
}

private fun deadlineProblem(
    deadline: LocalDateTime?,
    hasDeadline: Boolean,
    now: LocalDateTime,
): PollDraftError? {
    if (!hasDeadline) return null
    if (deadline == null) return PollDraftError.DeadlineInvalid
    return when {
        deadline < now.plusWallClock(PollRules.MIN_DEADLINE_LEAD_MINUTES.minutes) -> PollDraftError.DeadlineTooSoon
        deadline > now.plusWallClock(PollRules.MAX_DEADLINE_DAYS.days) -> PollDraftError.DeadlineTooFar
        else -> null
    }
}

internal fun pollDraftErrorText(error: PollDraftError): String =
    when (error) {
        PollDraftError.QuestionMissing -> gettext("Bitte geben Sie eine Frage ein.")
        PollDraftError.QuestionTooLong -> gettext("Die Frage ist zu lang (höchstens %1 Zeichen).", PollRules.MAX_QUESTION_LENGTH)
        PollDraftError.DescriptionTooLong ->
            gettext("Die Beschreibung ist zu lang (höchstens %1 Zeichen).", PollRules.MAX_DESCRIPTION_LENGTH)
        PollDraftError.TooFewOptions -> gettext("Bitte geben Sie mindestens %1 Optionen an.", PollRules.MIN_OPTIONS)
        PollDraftError.TooManyOptions -> gettext("Es sind höchstens %1 Optionen möglich.", PollRules.MAX_OPTIONS)
        is PollDraftError.OptionEmpty -> gettext("Option %1 ist leer.", error.index + 1)
        is PollDraftError.OptionTooLong ->
            gettext(
                "Option %1 ist zu lang (höchstens %2 Zeichen).",
                error.index + 1,
                PollRules.MAX_OPTION_LENGTH,
            )
        is PollDraftError.DuplicateOption -> gettext("Option %1 gibt es schon.", error.index + 1)
        PollDraftError.DeadlineInvalid -> gettext("Bitte geben Sie für die Frist ein gültiges Datum mit Uhrzeit an.")
        PollDraftError.DeadlineTooSoon ->
            gettext("Die Frist muss mindestens %1 Minuten in der Zukunft liegen.", PollRules.MIN_DEADLINE_LEAD_MINUTES)
        PollDraftError.DeadlineTooFar -> gettext("Die Frist darf höchstens %1 Tage in der Zukunft liegen.", PollRules.MAX_DEADLINE_DAYS)
    }

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

/** One option field with its row and (from the third option on) its remove button. */
private class OptionRow(
    val row: HPanel,
    val field: LapisField,
    val remove: Button?,
)

/**
 * Renders the create form into [host]. [onDone] gets the created poll, or `null` if the member cancelled; the caller decides where to go.
 */
internal fun renderPollCreateForm(
    host: SimplePanel,
    onDone: (PollDto?) -> Unit,
) {
    val holder = host.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
    holder.h2(tr("Umfrage erstellen")) { addCssClass("h5") }
    val form = holder.lapisForm()

    val questionField = form.textField(label = tr("Frage"), required = true)
    val questionCounter = form.panel.div("") { addCssClasses("text-muted small") }
    val descriptionField = form.textAreaField(label = tr("Beschreibung (optional)"), rows = 3)

    val optionsPanel = form.panel.vPanel(spacing = 6)
    val rows = mutableListOf<OptionRow>()
    val addOptionButton = Button(tr("Option hinzufügen"), style = ButtonStyle.OUTLINESECONDARY)
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
        )
    }

    fun update() {
        questionCounter.content = gettext("%1/%2", questionField.value.length, PollRules.MAX_QUESTION_LENGTH)
        val error = draft()
        submitButton.disabled = error != null
        reason.content = error?.let { pollDraftErrorText(it) }.orEmpty()
        addOptionButton.disabled = rows.size >= PollRules.MAX_OPTIONS
    }

    fun relabelRows() {
        rows.forEachIndexed { index, option ->
            option.field.relabel(gettext("Option %1", index + 1))
            option.remove?.tableActionTooltip(gettext("Option %1 entfernen", index + 1))
        }
    }

    fun addOptionRow(late: Boolean) {
        val number = rows.size + 1
        val row = optionsPanel.hPanel(spacing = 8) { addCssClasses("align-items-start") }
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
            if (rows.size >=
                PollRules.MIN_OPTIONS
            ) {
                row.tableActionButton("fas fa-xmark", gettext("Option %1 entfernen", number))
            } else {
                null
            }
        val option = OptionRow(row = row, field = field, remove = remove)
        rows += option
        field.subscribe { update() }
        remove?.onClick {
            form.unregister(option.field)
            optionsPanel.remove(option.row)
            rows.remove(option)
            relabelRows()
            update()
        }
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

    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)
    form.buttons(primary = submitButton, cancel = cancelButton)
    cancelButton.onClick { onDone(null) }
    submitButton.onClick {
        if (draft() != null || !form.validateAndReport()) return@onClick
        form.runBusy(submitButton, restoreDisabled = { draft() != null }) {
            val choice = deadlineChoiceOf(deadlineField.value)
            val custom = runCatching { LocalDateTime.parse(customField.value.trim()) }.getOrNull()
            val input =
                PollCreateInput(
                    question = questionField.value,
                    description = descriptionField.value.ifBlank { null },
                    options = rows.map { it.field.value },
                    closesAt = pollDeadlineFor(choice = choice, customLocal = custom, now = localNow()),
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
            if (created != null) onDone(created)
        }
    }
    update()
}
