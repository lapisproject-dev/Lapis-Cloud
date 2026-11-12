package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.RoomPollCloseReason
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.304 -- the DOM-free part of the room-poll client: form check, polling rhythm, toolbar dot, announcements, auto-open. */
class ConferenceRoomPollModelTest {
    private fun poll(
        id: String = "p1",
        status: RoomPollStatus = RoomPollStatus.OPEN,
        question: String = "Sollen wir verlängern?",
    ) = RoomPollDto(
        id = id,
        question = question,
        options = listOf(RoomPollOptionDto("o1", 0, "Ja"), RoomPollOptionDto("o2", 1, "Nein")),
        countingMode = RoomPollCountingMode.BOTH,
        status = status,
        closeReason = if (status == RoomPollStatus.CLOSED) RoomPollCloseReason.MANUAL else null,
    )

    private fun state(
        poll: RoomPollDto? = poll(),
        canAnswer: Boolean = true,
        leadership: Boolean = false,
        voted: Boolean = false,
    ) = RoomPollStateDto(roomId = "r1", poll = poll, voted = voted, canAnswer = canAnswer, isLeadership = leadership, version = "v")

    @Test
    fun theFormCheckMirrorsTheServerRules() {
        val ok = RoomPollForm(question = "Passt das so?")
        assertNull(roomPollFormProblem(ok))
        assertEquals(RoomPollFormProblem.QUESTION_LENGTH, roomPollFormProblem(ok.copy(question = "ab")))
        assertEquals(RoomPollFormProblem.QUESTION_LENGTH, roomPollFormProblem(ok.copy(question = "x".repeat(301))))
        assertEquals(RoomPollFormProblem.FORBIDDEN_CHARACTERS, roomPollFormProblem(ok.copy(question = "a <b> c")))
        val custom = ok.copy(template = RoomPollTemplate.CUSTOM)
        assertEquals(RoomPollFormProblem.OPTIONS, roomPollFormProblem(custom.copy(custom = listOf("", ""))))
        assertEquals(RoomPollFormProblem.OPTIONS, roomPollFormProblem(custom.copy(custom = listOf("Ja", " ja "))))
        assertEquals(RoomPollFormProblem.FORBIDDEN_CHARACTERS, roomPollFormProblem(custom.copy(custom = listOf("Ja", "Ne‮in"))))
        assertNull(roomPollFormProblem(custom.copy(custom = listOf("Rot", "Grün", "Blau"))))
        assertEquals(RoomPollFormProblem.OPTIONS, roomPollFormProblem(custom.copy(custom = (1..11).map { "o$it" })))
    }

    @Test
    fun theTemplatesStandForTheirAnswers() {
        assertEquals(listOf("Ja", "Nein", "Enthaltung"), RoomPollForm().options())
        assertEquals(listOf("Ja", "Nein"), RoomPollForm(template = RoomPollTemplate.YES_NO).options())
        assertEquals(listOf("A", "B"), RoomPollForm(template = RoomPollTemplate.CUSTOM, custom = listOf("A", "B")).options())
        val input = roomPollFormInput("r1", RoomPollForm(question = "Q?!", mode = RoomPollCountingMode.WEIGHTED))
        assertEquals(RoomPollCountingMode.WEIGHTED, input.countingMode)
        assertEquals("r1", input.roomId)
    }

    @Test
    fun theRhythm_is4sForTheLeadershipWhileOpen_5sForEverybodyElse_15sWhenHidden() {
        assertEquals(4_000, roomPollPollDelayMs(state(leadership = true), hidden = false))
        assertEquals(5_000, roomPollPollDelayMs(state(leadership = false), hidden = false))
        assertEquals(5_000, roomPollPollDelayMs(state(poll = poll(status = RoomPollStatus.CLOSED), leadership = true), hidden = false))
        assertEquals(5_000, roomPollPollDelayMs(null, hidden = false))
        assertEquals(15_000, roomPollPollDelayMs(state(leadership = true), hidden = true))
    }

    @Test
    fun theDot_isOnlyForAnOpenPollThatWaitsForThisPerson() {
        assertTrue(roomPollBadgeVisible(state()))
        assertFalse(roomPollBadgeVisible(state(canAnswer = false, voted = true)))
        assertFalse(roomPollBadgeVisible(state(poll = poll(status = RoomPollStatus.CLOSED), canAnswer = false)))
        assertFalse(roomPollBadgeVisible(state(poll = null, canAnswer = false)))
        assertFalse(roomPollBadgeVisible(null))
    }

    @Test
    fun announcements_aNewPollWithItsQuestion_theEnd_nothingElse() {
        assertEquals("Neue Umfrage: Frage?", roomPollAnnouncement(null, state(poll = poll(question = "Frage?")), "Frage?"))
        assertEquals(
            "Neue Umfrage: Frage?",
            roomPollAnnouncement(state(poll = poll(id = "old", status = RoomPollStatus.CLOSED)), state(), "Frage?"),
        )
        // the same poll again, an answer, a refresh: silent
        assertNull(roomPollAnnouncement(state(), state(voted = true, canAnswer = false), "x"))
        assertNull(roomPollAnnouncement(state(), state(), "x"))
        // the end of the poll
        assertEquals(
            "Umfrage beendet. Das Ergebnis ist da.",
            roomPollAnnouncement(state(), state(poll = poll(status = RoomPollStatus.CLOSED), canAnswer = false), "x"),
        )
        // a poll the person cannot answer (leadership who already answered, ended room) is not announced as new
        assertNull(roomPollAnnouncement(null, state(canAnswer = false, voted = true), "x"))
    }

    @Test
    fun autoOpen_onlyOnAWideScreen_andOnlyForANewPollToAnswer() {
        assertTrue(roomPollShouldAutoOpen(null, state(), wideViewport = true))
        assertFalse(roomPollShouldAutoOpen(null, state(), wideViewport = false))
        assertFalse(roomPollShouldAutoOpen(state(), state(), wideViewport = true))
        assertFalse(roomPollShouldAutoOpen(null, state(canAnswer = false), wideViewport = true))
        assertEquals("(min-width: 992px)", ROOM_POLL_WIDE_QUERY)
    }
}
