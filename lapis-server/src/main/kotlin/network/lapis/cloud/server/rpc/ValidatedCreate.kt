package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.isConsensus
import network.lapis.cloud.shared.rpc.BadRequestException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

internal class ValidatedCreate(
    val question: String,
    val description: String?,
    val options: List<String>,
    val closesAt: LocalDateTime?,
    val kind: PollKind,
    /** Same size as [options]; all `null` for [PollKind.SINGLE_CHOICE]. */
    val explanations: List<String?>,
)

internal fun validateCreateInput(
    input: PollCreateInput,
    wallNow: LocalDateTime,
): ValidatedCreate {
    // Bound the work before any per-element processing.
    if (input.options.size > PollRules.MAX_OPTIONS) {
        throw BadRequestException("A poll has at most ${PollRules.MAX_OPTIONS} options")
    }
    if (input.optionExplanations.isNotEmpty() && input.optionExplanations.size != input.options.size) {
        throw BadRequestException("The explanations must match the options")
    }
    val question = PollRules.normalizeText(input.question)
    if (question.isEmpty() || question.length > PollRules.MAX_QUESTION_LENGTH || question.any { it.isISOControl() }) {
        throw BadRequestException("The question must have 1..${PollRules.MAX_QUESTION_LENGTH} characters")
    }
    val rawDescription = input.description?.trim().orEmpty()
    if (rawDescription.length > PollRules.MAX_DESCRIPTION_LENGTH ||
        rawDescription.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
    ) {
        throw BadRequestException("The description must have at most ${PollRules.MAX_DESCRIPTION_LENGTH} characters")
    }
    val options = input.options.map { PollRules.normalizeText(it) }
    if (options.size < PollRules.MIN_OPTIONS) {
        throw BadRequestException("A poll needs at least ${PollRules.MIN_OPTIONS} options")
    }
    options.forEach {
        if (it.isEmpty() || it.length > PollRules.MAX_OPTION_LENGTH || it.any { c -> c.isISOControl() }) {
            throw BadRequestException("Each option must have 1..${PollRules.MAX_OPTION_LENGTH} characters")
        }
    }
    if (options.map { PollRules.optionKey(it) }.toSet().size != options.size) {
        throw BadRequestException("Options must be distinct")
    }
    val explanations = validateExplanations(input = input)
    val closesAt = input.closesAt?.truncatedToDbPrecision()
    if (closesAt != null) {
        // closesAt is a wall-clock in the organization zone, so the window is measured on that wall-clock
        // too (the lead/max spans are added as instants in that zone, so a DST change inside the span is honoured).
        val zone = OrganizationTimeZone.current()
        val earliest = wallNow.toInstant(zone).plus(PollRules.MIN_DEADLINE_LEAD_MINUTES.minutes).toLocalDateTime(zone)
        val latest = wallNow.toInstant(zone).plus(PollRules.MAX_DEADLINE_DAYS.days).toLocalDateTime(zone)
        if (closesAt < earliest || closesAt > latest) {
            throw BadRequestException(
                "The deadline must be between ${PollRules.MIN_DEADLINE_LEAD_MINUTES} minutes and " +
                    "${PollRules.MAX_DEADLINE_DAYS} days from now",
            )
        }
    }
    return ValidatedCreate(
        question = question,
        description = rawDescription.ifEmpty { null },
        options = options,
        closesAt = closesAt,
        kind = input.kind,
        explanations = explanations,
    )
}

/** Normalises the optional per-option explanations; for the classic kind any non-blank text is rejected. */
private fun validateExplanations(input: PollCreateInput): List<String?> {
    if (input.optionExplanations.isEmpty()) return List(input.options.size) { null }
    val normalized =
        input.optionExplanations.map { raw ->
            when (val result = PollRules.normalizeExplanation(raw)) {
                PublicTextNormalization.Empty -> null
                is PublicTextNormalization.Ok -> result.text
                else -> throw BadRequestException("Invalid explanation")
            }
        }
    if (!input.kind.isConsensus && normalized.any { it != null }) {
        throw BadRequestException("Explanations are only allowed for consensus polls")
    }
    return normalized
}
