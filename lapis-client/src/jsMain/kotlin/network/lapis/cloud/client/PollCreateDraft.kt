package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.isConsensus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/*
 * V1.9.41 -- the pure draft validation of "Neue Umfrage", split out of `PollCreateForm.kt`. The checks mirror `PollRules` and the server's
 * validation exactly, so an obviously invalid draft never makes a round trip -- the server stays the authority.
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

    /** V1.9.41: the explanation of option [index] (0-based) is longer than the limit. */
    data class ExplanationTooLong(
        val index: Int,
    ) : PollDraftError

    /** V1.9.41: the explanation of option [index] has forbidden characters or too many line breaks. */
    data class ExplanationInvalid(
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
    explanations: List<String> = emptyList(),
    kind: PollKind = PollKind.SINGLE_CHOICE,
): PollDraftError? {
    val normalizedQuestion = PollRules.normalizeText(question)
    val normalizedOptions = options.map { PollRules.normalizeText(it) }
    return when {
        normalizedQuestion.isEmpty() -> PollDraftError.QuestionMissing
        normalizedQuestion.length > PollRules.MAX_QUESTION_LENGTH -> PollDraftError.QuestionTooLong
        description.trim().length > PollRules.MAX_DESCRIPTION_LENGTH -> PollDraftError.DescriptionTooLong
        normalizedOptions.size < PollRules.MIN_OPTIONS -> PollDraftError.TooFewOptions
        normalizedOptions.size > PollRules.MAX_OPTIONS -> PollDraftError.TooManyOptions
        else ->
            optionProblem(normalizedOptions)
                ?: explanationProblem(explanations = explanations, kind = kind)
                ?: deadlineProblem(deadline = deadline, hasDeadline = hasDeadline, now = now)
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

/** Only the consensus kinds carry explanations, so only there they are checked (the server rejects them for a single choice). */
private fun explanationProblem(
    explanations: List<String>,
    kind: PollKind,
): PollDraftError? {
    if (!kind.isConsensus) return null
    explanations.forEachIndexed { index, raw ->
        when (PollRules.normalizeExplanation(raw)) {
            PublicTextNormalization.TooLong -> return PollDraftError.ExplanationTooLong(index)
            PublicTextNormalization.TooManyLineBreaks, PublicTextNormalization.ControlChars -> return PollDraftError.ExplanationInvalid(
                index,
            )
            else -> Unit
        }
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
        is PollDraftError.ExplanationTooLong ->
            gettext("Die Erklärung zu Option %1 ist zu lang (höchstens %2 Zeichen).", error.index + 1, PollRules.MAX_EXPLANATION_LENGTH)
        is PollDraftError.ExplanationInvalid ->
            gettext("Die Erklärung zu Option %1 enthält ungültige Zeichen oder zu viele Zeilenumbrüche.", error.index + 1)
        PollDraftError.DeadlineInvalid -> gettext("Bitte geben Sie für die Frist ein gültiges Datum mit Uhrzeit an.")
        PollDraftError.DeadlineTooSoon ->
            gettext("Die Frist muss mindestens %1 Minuten in der Zukunft liegen.", PollRules.MIN_DEADLINE_LEAD_MINUTES)
        PollDraftError.DeadlineTooFar -> gettext("Die Frist darf höchstens %1 Tage in der Zukunft liegen.", PollRules.MAX_DEADLINE_DAYS)
    }
