package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.9.113 -- the pure, DOM-free decisions behind the online participation UI. */
class EventOnlineUiTest {
    private val opens = LocalDateTime(2026, 11, 1, 17, 45)
    private val closes = LocalDateTime(2026, 11, 1, 23, 0)

    private fun conference(kind: EventOnlineKind = EventOnlineKind.LAPIS_CONFERENCE) =
        testEvent(onlineKind = kind, onlineWindowOpensAt = opens, onlineWindowClosesAt = closes)

    @Test
    fun anEventWithoutALapisOnlineKind_hasNoButton() {
        assertNull(eventOnlineButtonState(testEvent(), LocalDateTime(2026, 11, 1, 18, 0)))
        assertNull(eventOnlineButtonState(testEvent(onlineKind = EventOnlineKind.EXTERNAL_LINK, onlineUrl = "https://x.example"), opens))
        // a Lapis kind without a window (an old server) is no button either, rather than a wrong one
        assertNull(eventOnlineButtonState(testEvent(onlineKind = EventOnlineKind.LAPIS_CONFERENCE), opens))
    }

    @Test
    fun theThreeStates_withBothBoundsInclusive() {
        val e = conference()
        assertEquals(EventOnlineButtonState.NotYet(opens), eventOnlineButtonState(e, LocalDateTime(2026, 11, 1, 17, 44)))
        assertEquals(EventOnlineButtonState.Open, eventOnlineButtonState(e, opens))
        assertEquals(EventOnlineButtonState.Open, eventOnlineButtonState(e, closes))
        assertEquals(EventOnlineButtonState.Ended, eventOnlineButtonState(e, LocalDateTime(2026, 11, 1, 23, 1)))
    }

    @Test
    fun theLabels_nameTheStateBeforeTheWindow_andTheKindWhenOpen() {
        val notYet = EventOnlineButtonState.NotYet(opens)
        assertTrue(eventOnlineButtonLabel(notYet, EventOnlineKind.LAPIS_CONFERENCE).startsWith("Teilnehmen ab"))
        assertEquals("Teilnehmen", eventOnlineButtonLabel(EventOnlineButtonState.Open, EventOnlineKind.LAPIS_CONFERENCE))
        assertEquals("Raum betreten", eventOnlineButtonLabel(EventOnlineButtonState.Open, EventOnlineKind.LAPIS_ENCOUNTER))
        assertEquals("Beendet", eventOnlineButtonLabel(EventOnlineButtonState.Ended, EventOnlineKind.LAPIS_CONFERENCE))
    }

    @Test
    fun theReason_existsExactlyWhenTheButtonIsNotOpen() {
        assertNotNull(eventOnlineButtonReason(EventOnlineButtonState.NotYet(opens)))
        assertNotNull(eventOnlineButtonReason(EventOnlineButtonState.Ended))
        assertNull(eventOnlineButtonReason(EventOnlineButtonState.Open))
    }

    @Test
    fun everyOutcome_hasWordsExceptTheSuccess() {
        assertNull(eventOnlineOutcomeMessage(EventOnlineOutcome.JOINED))
        EventOnlineOutcome.entries.filter { it != EventOnlineOutcome.JOINED }.forEach {
            assertTrue(eventOnlineOutcomeMessage(it)?.isNotBlank() == true, "no message for $it")
        }
        assertTrue(eventOnlineOutcomeMessage(EventOnlineOutcome.NOT_YET, opens)?.isNotBlank() == true)
    }

    @Test
    fun exactlyOneSentencePerKind_andTheyDiffer() {
        val sentences = EventOnlineKind.entries.map { eventOnlineKindSentence(it) }
        assertEquals(4, sentences.toSet().size)
        sentences.forEach { assertTrue(it.isNotBlank()) }
    }

    private fun warnings(
        kind: EventOnlineKind,
        visibility: EventVisibility = EventVisibility.PUBLIC,
        deadline: Boolean = false,
        capacity: Int? = null,
        max: Int = 100,
        membersOnly: Boolean = false,
    ) = eventOnlineWarnings(kind, visibility, deadline, capacity, max, membersOnly)

    @Test
    fun theWarnings_followTheKindTheVisibilityTheDeadlineAndTheSeats() {
        assertTrue(warnings(EventOnlineKind.LAPIS_CONFERENCE).any { it.contains("ohne Konto") })
        assertFalse(warnings(EventOnlineKind.LAPIS_CONFERENCE, deadline = true).any { it.contains("ohne Konto") })
        assertFalse(warnings(EventOnlineKind.LAPIS_CONFERENCE, visibility = EventVisibility.MEMBERS_ONLY).any { it.contains("ohne Konto") })
        assertTrue(warnings(EventOnlineKind.LAPIS_CONFERENCE, capacity = 500).any { it.contains("begrenzt") })
        assertFalse(warnings(EventOnlineKind.LAPIS_CONFERENCE, capacity = 40).any { it.contains("begrenzt") })
        assertFalse(warnings(EventOnlineKind.LAPIS_CONFERENCE, capacity = 500, max = 0).any { it.contains("begrenzt") })
        assertTrue(warnings(EventOnlineKind.LAPIS_ENCOUNTER, membersOnly = true).any { it.contains("nur Mitglieder") })
        assertFalse(
            warnings(EventOnlineKind.LAPIS_ENCOUNTER, visibility = EventVisibility.MEMBERS_ONLY, membersOnly = true)
                .any { it.contains("nur Mitglieder") },
        )
        assertTrue(warnings(EventOnlineKind.LAPIS_ENCOUNTER, deadline = true).any { it.contains("Anmeldeschluss") })
        assertTrue(warnings(EventOnlineKind.NONE).isEmpty())
        assertTrue(warnings(EventOnlineKind.EXTERNAL_LINK).isEmpty())
    }

    @Test
    fun isLapisHosted_namesTheTwoHostedKinds() {
        assertEquals(
            setOf(EventOnlineKind.LAPIS_CONFERENCE, EventOnlineKind.LAPIS_ENCOUNTER),
            EventOnlineKind.entries.filter { it.isLapisHosted() }.toSet(),
        )
    }
}
