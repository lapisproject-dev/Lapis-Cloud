package network.lapis.cloud.client

import io.kvision.panel.Root
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import network.lapis.cloud.shared.domain.RoomPollCloseReason
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollGroupResultDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollResultDto
import network.lapis.cloud.shared.domain.RoomPollSplitWithheldReason
import network.lapis.cloud.shared.domain.RoomPollStartInput
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.304 -- the room-poll panel mounted for real (Karma/Chrome): the leadership form, the answer card, the interim result, the three-column result. */
class ConferenceRoomPollPanelDomTest {
    private fun poll(
        status: RoomPollStatus = RoomPollStatus.OPEN,
        question: String = "Sollen wir verlängern?",
        mode: RoomPollCountingMode = RoomPollCountingMode.BOTH,
        reason: RoomPollCloseReason? = null,
    ) = RoomPollDto(
        id = "p1",
        question = question,
        options = listOf(RoomPollOptionDto("o1", 0, "Ja"), RoomPollOptionDto("o2", 1, "Nein"), RoomPollOptionDto("o3", 2, "Enthaltung")),
        countingMode = mode,
        status = status,
        closeReason = reason,
    )

    private fun state(
        poll: RoomPollDto? = poll(),
        canAnswer: Boolean = true,
        voted: Boolean = false,
        leadership: Boolean = false,
        answerCount: Int? = null,
        interim: RoomPollResultDto? = null,
        interimCount: Int? = null,
        result: RoomPollResultDto? = null,
        version: String = "v1",
    ) = RoomPollStateDto(
        roomId = "r1",
        poll = poll,
        voted = voted,
        canAnswer = canAnswer,
        isLeadership = leadership,
        answerCount = answerCount,
        interim = interim,
        interimAnswerCount = interimCount,
        result = result,
        version = version,
    )

    private val splitResult =
        RoomPollResultDto(
            totalAnswerCount = 10,
            resultAvailable = true,
            splitAvailable = true,
            members = RoomPollGroupResultDto(RoomPollVoterGroup.MEMBERS, 5, listOf(60, 20, 20), true, listOf(50, 30, 20)),
            guestsAndFriends = RoomPollGroupResultDto(RoomPollVoterGroup.GUESTS_AND_FRIENDS, 5, listOf(40, 40, 20)),
            all = RoomPollGroupResultDto(null, 10, listOf(50, 30, 20)),
            weightedShownIn = RoomPollVoterGroup.MEMBERS,
        )

    private val mergedResult =
        RoomPollResultDto(
            totalAnswerCount = 10,
            resultAvailable = true,
            splitAvailable = false,
            splitWithheldReason = RoomPollSplitWithheldReason.GROUP_BELOW_MINIMUM,
            all = RoomPollGroupResultDto(null, 10, listOf(50, 30, 20), true, listOf(57, 43, 0)),
        )

    private class Calls {
        val started = mutableListOf<RoomPollStartInput>()
        val answered = mutableListOf<Triple<String, String, String>>()
        val closed = mutableListOf<String>()
        val fresh = mutableListOf<RoomPollStateDto>()
        var refresh = 0
        var conflictOnAnswer = false
        var conflictOnStart = false
        var next: RoomPollStateDto = RoomPollStateDto(roomId = "r1", version = "n")
    }

    private suspend fun <T> withPanel(
        calls: Calls,
        block: suspend (HTMLElement, ConferenceRoomPollPanelHandle) -> T,
    ): T {
        val rpc =
            ConferenceRoomPollRpc(
                start = {
                    if (calls.conflictOnStart) throw ConflictException("running")
                    calls.started += it
                    calls.next
                },
                close = { _, poll ->
                    calls.closed += poll
                    calls.next
                },
                answer = { room, poll, option ->
                    if (calls.conflictOnAnswer) throw ConflictException("answered")
                    calls.answered += Triple(room, poll, option)
                    calls.next
                },
            )
        return mountedForm("room-poll-panel") { root: Root, element ->
            val handle =
                renderConferenceRoomPollPanel(
                    parent = root,
                    roomId = "r1",
                    rpc = rpc,
                    onFreshState = { calls.fresh += it },
                    onRefreshRequested = { calls.refresh++ },
                )
            handle.setOpen(true)
            try {
                block(element(), handle)
            } finally {
                handle.dispose()
            }
        }
    }

    private fun HTMLElement.text(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun HTMLElement.type(
        selector: String,
        value: String,
    ) {
        val el = assertNotNull(querySelector(selector), "no $selector")
        when (el) {
            is HTMLTextAreaElement -> el.value = value
            is HTMLInputElement -> el.value = value
            else -> error("not an input: $selector")
        }
        el.dispatchEvent(Event("input", EventInit(bubbles = true)))
    }

    private fun HTMLElement.pick(id: String) {
        val radio = assertNotNull(querySelector("#$id") as? HTMLInputElement, "no radio $id")
        radio.checked = true
        radio.dispatchEvent(Event("change", EventInit(bubbles = true)))
    }

    private fun HTMLElement.startButton() = buttonNamed("Starten") as org.w3c.dom.HTMLButtonElement

    @Test
    fun theLeadership_seesTheForm_startStaysOffUntilItIsValid_andSendsWhatWasTyped(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(poll = null, canAnswer = false, leadership = true))
                awaitUntil("the form") { el.text().contains("Neue Umfrage") && el.text().contains("Ja / Nein / Enthaltung") }
                assertTrue(el.startButton().disabled, "no question yet")
                assertTrue(el.text().contains("Zählweise"))
                assertTrue(el.text().contains("Beides nebeneinander"))
                el.type("#lapis-room-poll-question", "ab")
                assertTrue(el.startButton().disabled, "too short")
                assertTrue(el.text().contains("Bitte geben Sie eine Frage mit 3 bis 300 Zeichen ein."))
                el.type("#lapis-room-poll-question", "Sollen wir verlängern?")
                assertFalse(el.startButton().disabled)
                assertTrue(el.text().contains("22 / 300"))
                el.pick("lapis-room-poll-mode-WEIGHTED")
                el.startButton().click()
                awaitUntil("sent") { calls.started.size == 1 }
                val sent = calls.started.single()
                assertEquals("r1", sent.roomId)
                assertEquals("Sollen wir verlängern?", sent.question)
                assertEquals(listOf("Ja", "Nein", "Enthaltung"), sent.options)
                assertEquals(RoomPollCountingMode.WEIGHTED, sent.countingMode)
                awaitUntil("fresh state adopted") { calls.fresh.size == 1 }
            }
        }

    @Test
    fun customAnswers_areValidated_andForbiddenCharactersAreSaidInWords(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(poll = null, canAnswer = false, leadership = true))
                awaitUntil("the form") { el.text().contains("Eigene Antworten") }
                el.type("#lapis-room-poll-question", "Welche Farbe?")
                el.pick("lapis-room-poll-template-CUSTOM")
                awaitUntil("two empty rows") { el.allOf(".lapis-room-poll-custom").size == 2 }
                assertTrue(el.startButton().disabled, "empty rows")
                val rows = el.allOf(".lapis-room-poll-custom")
                (rows[0] as HTMLInputElement).value = "Rot"
                (rows[1] as HTMLInputElement).value = "Grün"
                rows[1].dispatchEvent(Event("input", EventInit(bubbles = true)))
                assertFalse(el.startButton().disabled)
                // a third row
                el.buttonNamed("Antwort hinzufügen").click()
                awaitUntil("three rows") { el.allOf(".lapis-room-poll-custom").size == 3 }
                assertTrue(el.startButton().disabled, "the new row is empty")
                el.type("#lapis-room-poll-question", "Frage <b>fett</b>")
                assertTrue(el.text().contains("kein < oder >"))
                assertTrue(el.startButton().disabled)
            }
        }

    @Test
    fun whileAPollRuns_theFormIsGone_theLeadershipSeesTheExactCount_noInterim_andEndingAsksFirst(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(leadership = true, canAnswer = true, answerCount = 12))
                awaitUntil("running poll") { el.text().contains("12 Antworten") }
                assertTrue(el.allOf(".lapis-room-poll-form").all { it.style.display == "none" }, "the form is hidden while a poll runs")
                assertTrue(
                    el.allOf("textarea").isEmpty() ||
                        el.allOf("textarea").all {
                            it.closest(".lapis-room-poll-form")?.let { f ->
                                (f as HTMLElement).style.display ==
                                    "none"
                            } ==
                                true
                        },
                )
                assertTrue(el.text().contains("Unverbindlich"))
                assertTrue(el.allOf(".${ROOM_POLL_RESULT_CLASS}").isEmpty(), "there is no interim result")
                assertFalse(el.text().contains("Zwischenstand"))
                // ending asks first
                el.buttonNamed("Umfrage beenden").click()
                awaitUntil("question") { el.text().contains("Umfrage beenden?") }
                assertTrue(calls.closed.isEmpty())
                calls.next =
                    state(poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.MANUAL), canAnswer = false, result = splitResult)
                el.buttonNamed("Beenden").click()
                awaitUntil("closed") { calls.closed == listOf("p1") }
            }
        }

    @Test
    fun theAnswerCard_oneTapSendsOne_andAnAlreadyGivenAnswerIsNoError(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("answer card") { el.allOf("button.lapis-room-poll-option").size == 3 }
                assertEquals(listOf("Ja", "Nein", "Enthaltung"), el.allOf("button.lapis-room-poll-option").map { it.text() })
                assertTrue(el.text().contains("Unverbindlich"))
                assertTrue(el.text().contains("Eine Antwort, nicht änderbar."))
                assertTrue(el.allOf(".${ROOM_POLL_RESULT_CLASS}").isEmpty())
                calls.next = state(canAnswer = false, voted = true)
                el.allOf("button.lapis-room-poll-option")[1].click()
                awaitUntil("sent") { calls.answered == listOf(Triple("r1", "p1", "o2")) }
                awaitUntil("counted") { calls.fresh.size == 1 }
                handle.apply(calls.next)
                awaitUntil("thanks") { el.text().contains("Ihre Stimme wurde gezählt.") }
                assertTrue(el.allOf("button.lapis-room-poll-option").isEmpty())
            }
        }

    @Test
    fun aConflictOnAnswer_isTreatedAsCounted_noErrorText_theStateIsAskedAgain(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.conflictOnAnswer = true }
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("answer card") { el.allOf("button.lapis-room-poll-option").isNotEmpty() }
                el.allOf("button.lapis-room-poll-option")[0].click()
                awaitUntil("asked again") { calls.refresh == 1 }
                assertFalse(el.text().contains("konnte nicht"), "no error")
                assertTrue(el.allOf(".lapis-room-poll-note").all { it.style.display == "none" || it.text().isEmpty() })
            }
        }

    @Test
    fun theEndedPoll_showsThreeColumnsInOrder_withNeutralBarsAndNoWinner(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(
                    state(poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.MANUAL), canAnswer = false, result = splitResult),
                )
                awaitUntil("result") { el.allOf(".lapis-room-poll-column").size == 3 }
                assertEquals(
                    listOf("Mitglieder", "Gäste und Freunde", "Alle zusammen"),
                    el.allOf(".lapis-room-poll-column h3").map { it.text() },
                )
                assertEquals(listOf("n = 5", "n = 5", "n = 10"), el.allOf(".lapis-room-poll-n").map { it.text() })
                // both counting methods side by side in the members column: head and LTR
                val members = el.allOf(".lapis-room-poll-column")[0]
                assertEquals(6, members.allOf(".lapis-room-poll-bar").size)
                assertTrue(members.text().contains("60 %"))
                assertTrue(members.text().contains("50 %"))
                // the weighted figures exist once, in the members column
                assertEquals(0, el.allOf(".lapis-room-poll-column")[2].allOf(".lapis-room-poll-bar-label").size)
                assertTrue(el.text().contains("LTR-Gewichtung zählt nur Mitglieder"))
                assertTrue(el.text().contains("Gäste haben kein LTR – nur Kopfzahl"))
                assertTrue(el.text().contains("Die Umfrage wurde beendet."))
                // the options keep the order they were created in; no winner is marked
                assertEquals(listOf("Ja", "Nein", "Enthaltung"), members.allOf(".lapis-room-poll-option-text").map { it.text() })
                assertTrue(el.allOf(".text-success, .fw-bold.lapis-room-poll-option-text, .lapis-room-poll-winner").isEmpty())
            }
        }

    @Test
    fun aWeightedPollWithASplit_showsBarsInAllThreeColumns_theGuestsAndAllTogetherByHeadCount(): Promise<Unit> =
        formTest {
            val calls = Calls()
            val weightedSplit =
                RoomPollResultDto(
                    totalAnswerCount = 10,
                    resultAvailable = true,
                    splitAvailable = true,
                    members = RoomPollGroupResultDto(RoomPollVoterGroup.MEMBERS, 5, emptyList(), true, listOf(50, 30, 20)),
                    guestsAndFriends = RoomPollGroupResultDto(RoomPollVoterGroup.GUESTS_AND_FRIENDS, 5, listOf(40, 40, 20)),
                    all = RoomPollGroupResultDto(null, 10, listOf(50, 30, 20)),
                    weightedShownIn = RoomPollVoterGroup.MEMBERS,
                )
            withPanel(calls) { el, handle ->
                handle.apply(
                    state(
                        poll = poll(RoomPollStatus.CLOSED, mode = RoomPollCountingMode.WEIGHTED, reason = RoomPollCloseReason.MANUAL),
                        canAnswer = false,
                        result = weightedSplit,
                    ),
                )
                awaitUntil("result") { el.allOf(".lapis-room-poll-column").size == 3 }
                el.allOf(".lapis-room-poll-column").forEach { column ->
                    assertEquals(3, column.allOf(".lapis-room-poll-bar").size, "every column draws one bar per option")
                }
            }
        }

    @Test
    fun aHiddenSplit_showsOnlyAllTogether_withTheReason_andNoGroupSize(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(
                    state(
                        poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.ROOM_ENDED),
                        canAnswer = false,
                        result = mergedResult,
                    ),
                )
                awaitUntil("result") { el.allOf(".lapis-room-poll-column").size == 1 }
                assertEquals(listOf("Alle zusammen"), el.allOf(".lapis-room-poll-column h3").map { it.text() })
                assertEquals(listOf("n = 10"), el.allOf(".lapis-room-poll-n").map { it.text() })
                assertTrue(el.text().contains("Aufteilung erst ab 5 Antworten je Gruppe"))
                assertTrue(el.text().contains("Die Umfrage endete mit dem Raum."))
                assertFalse(el.allOf(".lapis-room-poll-column h3").any { it.text() == "Mitglieder" })
            }
        }

    @Test
    fun belowTheMinimum_noFigureIsDrawn_onlyTheTotal(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                val few = RoomPollResultDto(totalAnswerCount = 3, resultAvailable = false, splitAvailable = false)
                handle.apply(
                    state(poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.TIMEOUT), canAnswer = false, result = few),
                )
                awaitUntil("hint") { el.text().contains("Ergebnis erst ab 5 Antworten (bisher 3)") }
                assertTrue(el.allOf(".lapis-room-poll-bar").isEmpty())
                assertTrue(el.text().contains("Die Umfrage endete nach 240 Minuten."))
            }
        }

    @Test
    fun aWithheldWeightedResult_saysWhy_inTheColumnThatShouldCarryIt(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                val withheld =
                    splitResult.copy(
                        members =
                            splitResult.members!!.copy(
                                weightedAvailable = false,
                                weightedSharesPercent = emptyList(),
                                weightedWithheldReason = PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT,
                            ),
                    )
                handle.apply(
                    state(poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.MANUAL), canAnswer = false, result = withheld),
                )
                awaitUntil("hint") { el.text().contains("Keine der Antworten hatte LTR-Gewicht") }
            }
        }

    @Test
    fun aHostileQuestionAndOption_areTextOnly_neverMarkup(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                val hostile =
                    poll(question = "<img src=x onerror=alert(1)>Frage").copy(
                        options = listOf(RoomPollOptionDto("o1", 0, "<b>Ja</b>"), RoomPollOptionDto("o2", 1, "Nein")),
                    )
                handle.apply(state(poll = hostile))
                awaitUntil("card") { el.allOf("button.lapis-room-poll-option").size == 2 }
                assertEquals(0, el.allOf("img").size)
                assertEquals(0, el.allOf("button.lapis-room-poll-option b").size)
                assertTrue(el.text().contains("<img src=x onerror=alert(1)>Frage"))
                assertEquals("<b>Ja</b>", el.allOf("button.lapis-room-poll-option")[0].text())
            }
        }

    @Test
    fun aNewPoll_isAnnouncedInALiveRegionOutsideThePanel_andTheEnd_too(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.setOpen(false)
                handle.apply(state())
                val region = assertNotNull(el.allOf("[role=status][aria-live=polite]").firstOrNull(), "no live region")
                awaitUntil("announced") { region.text() == "Neue Umfrage: Sollen wir verlängern?" }
                assertNull(region.closest(".lapis-room-poll-panel"), "outside the panel")
                handle.apply(
                    state(poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.MANUAL), canAnswer = false, result = splitResult),
                )
                awaitUntil("end announced") { region.text() == "Umfrage beendet. Das Ergebnis ist da." }
            }
        }

    @Test
    fun theResultCanBeHiddenInMemory(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(
                    state(poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.MANUAL), canAnswer = false, result = splitResult),
                )
                awaitUntil("result") { el.allOf(".lapis-room-poll-column").size == 3 }
                el.buttonNamed("Ausblenden").click()
                awaitUntil("hidden") { el.allOf(".lapis-room-poll-column").isEmpty() }
                handle.apply(
                    state(
                        poll = poll(RoomPollStatus.CLOSED, reason = RoomPollCloseReason.MANUAL),
                        canAnswer = false,
                        result = splitResult,
                        version = "v2",
                    ),
                )
                assertTrue(el.allOf(".lapis-room-poll-column").isEmpty(), "stays hidden for this poll")
            }
        }

    @Test
    fun theToolbarButton_isHiddenUntilTheServerAnswered_hasADotWhileAPollWaits_andIsPressedWhileOpen(): Promise<Unit> =
        formTest {
            mountedForm("room-poll-toggle") { root: Root, element ->
                val button = root.conferencePollButton()
                paintRoomPollToggle(button, state = null, pressed = false)
                // a hidden widget is not rendered at all
                assertTrue(element().allOf("button").isEmpty())
                paintRoomPollToggle(button, state = state(), pressed = false)
                awaitUntil("shown with a dot") { element().allOf("button.lapis-room-poll-new").size == 1 }
                val el = element().allOf("button").single()
                assertEquals("Umfrage – offen, noch nicht beantwortet", el.getAttribute("aria-label"))
                paintRoomPollToggle(button, state = state(canAnswer = false, voted = true), pressed = true)
                awaitUntil("no dot") { !el.classList.contains("lapis-room-poll-new") }
                assertEquals("true", el.getAttribute("aria-pressed"))
                assertEquals("Umfrage", el.getAttribute("aria-label"))
                assertNull(el.getAttribute("hidden"))
            }
        }
}
