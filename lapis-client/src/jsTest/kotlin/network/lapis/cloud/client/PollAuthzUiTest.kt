package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.PollStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** V1.9.31: the pure gates, the percent method, the draft validation and the own-state of a poll row. */
class PollAuthzUiTest {
    private val now = LocalDateTime(2026, 10, 1, 12, 0)

    private fun LocalDateTime.plus(duration: kotlin.time.Duration) = (toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

    private fun draft(
        question: String = "Frage?",
        description: String = "",
        options: List<String> = listOf("Ja", "Nein"),
        deadline: LocalDateTime? = null,
        hasDeadline: Boolean = false,
        explanations: List<String> = emptyList(),
        kind: network.lapis.cloud.shared.domain.PollKind = network.lapis.cloud.shared.domain.PollKind.SINGLE_CHOICE,
    ) = validatePollDraft(question, description, options, deadline, hasDeadline, now, explanations, kind)

    // ── gates ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun respondGate_needsAnOpenPoll_andTheServersCanRespond() {
        PollStatus.entries.forEach { status ->
            val poll = pollDto(status = status)
            assertEquals(status == PollStatus.OPEN, canRespondToPoll(poll, pollParticipation(canRespond = true)), "status $status")
            assertFalse(canRespondToPoll(poll, pollParticipation(canRespond = false)), "status $status without canRespond")
        }
    }

    @Test
    fun closeAndAbort_needManage_andAnOpenPoll() {
        PollStatus.entries.forEach { status ->
            assertEquals(status == PollStatus.OPEN, canClosePoll(pollDto(status = status, canManage = true)), "close $status")
            assertEquals(status == PollStatus.OPEN, canAbortPoll(pollDto(status = status, canManage = true)), "abort $status")
            assertFalse(canClosePoll(pollDto(status = status, canManage = false)))
            assertFalse(canAbortPoll(pollDto(status = status, canManage = false)))
        }
    }

    @Test
    fun resultAndCount_areShownForAClosedPollOnly() {
        assertTrue(showsPollResult(pollDto(status = PollStatus.CLOSED)))
        assertFalse(showsPollResult(pollDto(status = PollStatus.OPEN)))
        assertFalse(showsPollResult(pollDto(status = PollStatus.ABORTED)))
        assertTrue(showsResponseCount(pollDto(status = PollStatus.CLOSED, responseCount = 6)))
        assertFalse(showsResponseCount(pollDto(status = PollStatus.CLOSED, responseCount = null)))
        assertFalse(showsResponseCount(pollDto(status = PollStatus.OPEN, responseCount = 6)))
        assertFalse(showsResponseCount(pollDto(status = PollStatus.ABORTED, responseCount = 6)))
    }

    @Test
    fun ownStatus_distinguishesOpenForYou_answered_andNothing() {
        val open = pollDto(status = PollStatus.OPEN)
        assertEquals(PollOwnStatus.OpenForYou, pollOwnStatus(open, pollParticipation(canRespond = true)))
        assertEquals(PollOwnStatus.Answered, pollOwnStatus(open, pollParticipation(hasResponded = true)))
        assertEquals(PollOwnStatus.None, pollOwnStatus(open, pollParticipation(eligible = false)))
        assertEquals(PollOwnStatus.None, pollOwnStatus(open, null))
        assertEquals(PollOwnStatus.Answered, pollOwnStatus(pollDto(status = PollStatus.CLOSED), pollParticipation(hasResponded = true)))
        assertEquals(
            PollOwnStatus.None,
            pollOwnStatus(pollDto(status = PollStatus.CLOSED), pollParticipation(canRespond = false, hasResponded = false)),
        )
    }

    // ── percentages ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun headSharePercents_useTheLargestRemainder_tiesToTheLowerPosition_andAddUpTo100() {
        assertEquals(listOf(34, 33, 33), headSharePercents(listOf(1, 1, 1)))
        assertEquals(listOf(0, 0), headSharePercents(listOf(0, 0)))
        assertEquals(listOf(100, 0), headSharePercents(listOf(5, 0)))
        assertEquals(listOf(50, 50), headSharePercents(listOf(3, 3)))
        assertEquals(listOf(57, 29, 14), headSharePercents(listOf(4, 2, 1)))
        listOf(listOf(1, 2, 3, 4), listOf(7, 7, 7, 7, 7, 7, 7), listOf(1, 0, 0, 1, 1), listOf(13, 29, 1)).forEach { counts ->
            assertEquals(100, headSharePercents(counts).sum(), "sum for $counts")
        }
    }

    // ── draft validation ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aValidDraft_hasNoError() {
        assertNull(draft())
        assertNull(draft(options = (1..10).map { "Option $it" }))
    }

    @Test
    fun theQuestion_isRequired_andLimitedTo500AfterNormalizing() {
        assertEquals(PollDraftError.QuestionMissing, draft(question = "   "))
        assertNull(draft(question = "x".repeat(500)))
        assertEquals(PollDraftError.QuestionTooLong, draft(question = "x".repeat(501)))
        // spaces around and inside are normalized before the length counts, like the server does
        assertNull(draft(question = "  " + "x".repeat(500) + "  "))
    }

    @Test
    fun theDescription_isLimitedTo2000() {
        assertNull(draft(description = "y".repeat(2000)))
        assertEquals(PollDraftError.DescriptionTooLong, draft(description = "y".repeat(2001)))
    }

    @Test
    fun theOptions_needTwoToTen_eachNotEmptyAndAtMost200() {
        assertEquals(PollDraftError.TooFewOptions, draft(options = listOf("Ja")))
        assertNull(draft(options = listOf("Ja", "Nein")))
        assertEquals(PollDraftError.TooManyOptions, draft(options = (1..11).map { "Option $it" }))
        assertEquals(PollDraftError.OptionEmpty(1), draft(options = listOf("Ja", "  ")))
        assertNull(draft(options = listOf("a".repeat(200), "b")))
        assertEquals(PollDraftError.OptionTooLong(0), draft(options = listOf("a".repeat(201), "b")))
    }

    @Test
    fun aDuplicateOption_isFoundDespiteCaseAndSpaces_andNamedAtTheSecondOccurrence() {
        assertEquals(PollDraftError.DuplicateOption(1), draft(options = listOf("Ja", " JA ")))
        assertEquals(PollDraftError.DuplicateOption(2), draft(options = listOf("Ja", "Nein", "ja")))
        assertEquals(PollDraftError.DuplicateOption(1), draft(options = listOf("Eins  zwei", "eins zwei")))
        assertNull(draft(options = listOf("Ja", "Jein")))
    }

    @Test
    fun theDeadline_mustBeBetween15MinutesAnd365Days() {
        assertEquals(PollDraftError.DeadlineTooSoon, draft(deadline = now.plus(14.minutes), hasDeadline = true))
        assertNull(draft(deadline = now.plus(15.minutes), hasDeadline = true))
        assertNull(draft(deadline = now.plus(365.days), hasDeadline = true))
        assertEquals(PollDraftError.DeadlineTooFar, draft(deadline = now.plus(366.days), hasDeadline = true))
        assertEquals(PollDraftError.DeadlineInvalid, draft(deadline = null, hasDeadline = true))
        // no deadline chosen: the date is not looked at at all
        assertNull(draft(deadline = now.plus((-5).days), hasDeadline = false))
    }

    @Test
    fun theDeadlineChoice_standsForPresetCustomOrNone() {
        assertEquals(now.plus(24.hours), pollDeadlineFor(PollDeadlineChoice.Preset(24), null, now))
        assertEquals(now.plus(7.days), pollDeadlineFor(PollDeadlineChoice.Preset(168), null, now))
        val custom = LocalDateTime(2026, 11, 1, 9, 30)
        assertEquals(custom, pollDeadlineFor(PollDeadlineChoice.Custom, custom, now))
        assertNull(pollDeadlineFor(PollDeadlineChoice.Custom, null, now))
        assertNull(pollDeadlineFor(PollDeadlineChoice.None, custom, now))
    }

    // ── V1.9.41: explanations of the consensus kinds ─────────────────────────────────────────────────────────────

    @Test
    fun explanations_areCheckedOnlyForTheConsensusKinds() {
        val rank = network.lapis.cloud.shared.domain.PollKind.SK_PRIORITY
        val decision = network.lapis.cloud.shared.domain.PollKind.SK_DECISION
        assertNull(draft(kind = rank, explanations = listOf("x".repeat(1000), "")))
        assertEquals(PollDraftError.ExplanationTooLong(0), draft(kind = rank, explanations = listOf("x".repeat(1001), "")))
        assertEquals(PollDraftError.ExplanationTooLong(1), draft(kind = decision, explanations = listOf("", "y".repeat(1001))))
        assertEquals(PollDraftError.ExplanationInvalid(0), draft(kind = rank, explanations = listOf("a\u0007b", "")))
        assertEquals(PollDraftError.ExplanationInvalid(1), draft(kind = rank, explanations = listOf("", "a\n".repeat(25) + "b")))
        // a single choice never sends an explanation, so an over-long leftover in the form must not block it
        assertNull(draft(explanations = listOf("x".repeat(5000), "")))
        assertTrue(pollDraftErrorText(PollDraftError.ExplanationTooLong(1)).contains("Option 2"))
        assertTrue(pollDraftErrorText(PollDraftError.ExplanationInvalid(0)).contains("Option 1"))
    }
}
