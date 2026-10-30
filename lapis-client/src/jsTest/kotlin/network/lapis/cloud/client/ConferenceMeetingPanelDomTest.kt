package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.EstablishedQuorumDto
import network.lapis.cloud.shared.domain.MeetingSessionNextStep
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SessionAgendaItemDto
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.domain.SessionRosterEntryDto
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** V1.9.236 -- the Sitzung panel mounted for real (Karma/Chrome): what each role sees, and what a click sends. */
class ConferenceMeetingPanelDomTest {
    private val agenda =
        listOf(
            SessionAgendaItemDto("a1", 1, "Begrüßung"),
            SessionAgendaItemDto("a2", 2, "Haushalt"),
            SessionAgendaItemDto("a3", 3, "Verschiedenes"),
        )

    private fun roster(vararg status: AttendanceStatus?) =
        status.mapIndexed { i, s ->
            SessionRosterEntryDto(memberId = "p$i", displayName = "Person ${'A' + i}", status = s, connected = i == 0)
        }

    private fun state(
        phase: MeetingSessionPhase = MeetingSessionPhase.OPEN,
        role: MeetingSessionRole = MeetingSessionRole.LEAD,
        present: Int = 2,
        eligible: Int = 3,
        required: Int = 2,
        established: EstablishedQuorumDto? = null,
        current: String? = "a1",
        rule: AttendanceRuleKind = AttendanceRuleKind.NAME_LIST,
        entries: List<SessionRosterEntryDto> = roster(AttendanceStatus.PRESENT, AttendanceStatus.PRESENT, null),
        hint: MeetingSessionNextStep? = null,
        adoptable: Int = 0,
    ) = ConferenceMeetingStateDto(
        meetingId = "m1",
        meetingTitle = "Vorstandssitzung",
        committeeId = "c1",
        phase = phase,
        openedAt = if (phase == MeetingSessionPhase.NOT_OPENED) null else LocalDateTime(2030, 5, 4, 19, 5),
        callerRole = role,
        ruleKind = rule,
        liveQuorum =
            if (role == MeetingSessionRole.PARTICIPANT) {
                null
            } else {
                QuorumResultDto("m1", eligible, present, required, 50, met = present >= required)
            },
        establishedQuorum = established,
        agenda = agenda,
        currentAgendaItemId = current,
        nextStepHint = if (role == MeetingSessionRole.LEAD) hint else null,
        roster = if (role == MeetingSessionRole.PARTICIPANT) emptyList() else entries,
        adoptableCount = adoptable,
    )

    private class Calls {
        val opened = mutableListOf<String>()
        val establish = mutableListOf<Boolean>()
        val moves = mutableListOf<Pair<String?, String>>()
        val attendance = mutableListOf<SessionAttendanceInput>()
        var adopts = 0
        var next: ConferenceMeetingStateDto? = null
        var moveConflict = false
        var moveOtherConflict = false
        var attendanceConflict = false
        var stale = 0
        val fresh = mutableListOf<ConferenceMeetingStateDto>()
    }

    private suspend fun <T> withPanel(
        calls: Calls,
        block: suspend (HTMLElement, ConferenceMeetingPanelHandle) -> T,
    ): T {
        val rpc =
            ConferenceMeetingRpc(
                getState = { calls.next },
                open = { _, m ->
                    calls.opened += m
                    calls.next!!
                },
                setAttendance = { _, _, input ->
                    calls.attendance += input
                    if (calls.attendanceConflict) throw ConflictException("this person is not on the roster")
                    calls.next!!
                },
                adopt = { _, _ ->
                    calls.adopts++
                    1
                },
                establish = { _, _, ack ->
                    calls.establish += ack
                    calls.next!!
                },
                move = { _, _, from, to ->
                    if (calls.moveConflict) throw AgendaMovedMeanwhileException()
                    if (calls.moveOtherConflict) throw ConflictException("meeting is closed")
                    calls.moves += from to to
                    calls.next!!
                },
            )
        return mountedForm("meeting-panel") { root: Root, element ->
            val handle =
                renderConferenceMeetingPanel(
                    parent = root,
                    roomId = "r1",
                    rpc = rpc,
                    guestSearch = { { _, _ -> null } },
                    onFreshState = { calls.fresh += it },
                    onStaleState = { calls.stale++ },
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

    private fun HTMLElement.hasButtonText(text: String): Boolean = allOf("button").any { it.text() == text }

    private fun HTMLElement.buttonText(text: String): HTMLButtonElement =
        assertNotNull(allOf("button").firstOrNull { it.text() == text }, "no button '$text'") as HTMLButtonElement

    @Test
    fun theLead_seesFourSections_theCurrentItemIsMarked_andTheFiguresAreWritten(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(hint = MeetingSessionNextStep.ESTABLISH_QUORUM))
                awaitUntil("the sections") { el.allOf("h3").size == 3 }
                assertEquals(listOf("Beschlussfähigkeit", "Tagesordnung", "Anwesenheit"), el.allOf("h3").map { it.text() })
                val items = el.allOf("ol li")
                assertEquals(3, items.size)
                assertEquals("step", items[0].getAttribute("aria-current"))
                assertNull(items[1].getAttribute("aria-current"))
                assertTrue(el.text().contains("2 von 3 anwesend · beschlussfähig ab 2"))
                assertTrue(el.text().contains("Beschlussfähig"), "a word next to the symbol")
                assertTrue(el.text().contains("Noch nicht festgestellt"))
                assertTrue(el.text().contains("Nächster Schritt: Beschlussfähigkeit feststellen"))
                assertTrue(el.text().contains("Eröffnet um"))
                assertTrue(el.allOf(".conference-meeting-figures").isNotEmpty(), "tabular figures")
            }
        }

    @Test
    fun notOpened_theLeadCanOpen_theButtonSendsOpen_andTheAnswerIsRendered(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(phase = MeetingSessionPhase.NOT_OPENED, hint = MeetingSessionNextStep.OPEN_MEETING, current = null))
                awaitUntil("open button") { el.hasButtonText("Sitzung eröffnen") }
                assertTrue(el.text().contains("Nicht eröffnet"))
                calls.next = state()
                el.buttonText("Sitzung eröffnen").click()
                awaitUntil("opened") { calls.opened == listOf("m1") && el.text().contains("Eröffnet um") }
                assertFalse(el.hasButtonText("Sitzung eröffnen"))
                assertEquals(1, calls.fresh.size)
            }
        }

    @Test
    fun establishing_whenMet_sendsAtOnce_whenNotMet_asksFirstAndSendsTheAcknowledgement(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(present = 2, required = 2))
                awaitUntil("establish") { el.hasButtonText("Beschlussfähigkeit feststellen") }
                calls.next = state(established = EstablishedQuorumDto(LocalDateTime(2030, 5, 4, 19, 10), 2, 2, 3, false))
                el.buttonText("Beschlussfähigkeit feststellen").click()
                awaitUntil("sent") { calls.establish == listOf(false) }
                awaitUntil("established line") { el.text().contains("Festgestellt um") && el.text().contains("(2 von 3)") }

                // not met: a question first
                handle.apply(state(present = 1, required = 2))
                awaitUntil("not met") { el.text().contains("Es fehlen noch 1.") }
                calls.next =
                    state(present = 1, required = 2, established = EstablishedQuorumDto(LocalDateTime(2030, 5, 4, 19, 11), 1, 2, 3, true))
                el.buttonText("Beschlussfähigkeit feststellen").click()
                awaitUntil("the dialog") { lastOpenModal().text().contains("Laut Berechnung nicht beschlussfähig (1 von 3, ab 2)") }
                assertEquals(listOf(false), calls.establish, "nothing is sent before the answer")
                // V1.9.242 -- checked, not changed: the question is the only gate (no hard block), and the safe answer has the focus
                awaitUntil("cancel focused") {
                    kotlinx.browser.document.activeElement
                        ?.textContent
                        .orEmpty()
                        .trim() == "Abbrechen"
                }
                lastOpenModal().buttonText("Trotzdem feststellen").click()
                awaitUntil("acknowledged") { calls.establish == listOf(false, true) }
            }
        }

    @Test
    fun theAgendaButtons_stayInsideTheList_andSendTheStaleGuard(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(current = "a1"))
                awaitUntil("buttons") { el.hasButtonText("Nächster Punkt") }
                assertTrue(el.buttonText("Vorheriger Punkt").disabled, "no previous item before the first")
                calls.next = state(current = "a2")
                el.buttonText("Nächster Punkt").click()
                awaitUntil("moved") { calls.moves == listOf<Pair<String?, String>>("a1" to "a2") }
                awaitUntil("marker follows") { el.allOf("ol li")[1].getAttribute("aria-current") == "step" }
                handle.apply(state(current = "a3"))
                awaitUntil("end") { el.buttonText("Nächster Punkt").disabled }
            }
        }

    @Test
    fun aStaleMove_showsTheHint_andAsksForAFreshReading(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.moveConflict = true }
            withPanel(calls) { el, handle ->
                handle.apply(state(current = "a1"))
                awaitUntil("button") { el.hasButtonText("Nächster Punkt") }
                el.buttonText("Nächster Punkt").click()
                awaitUntil("hint") { el.text().contains("Die Tagesordnung wurde inzwischen weitergeschaltet.") }
                assertEquals(1, calls.stale)
            }
        }

    @Test
    fun theMinuteTaker_keepsAttendance_butCannotOpenEstablishOrMove_andSeesTheAgenda(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(role = MeetingSessionRole.MINUTES))
                awaitUntil("attendance") { el.allOf("h3").any { it.text() == "Anwesenheit" } }
                assertFalse(el.hasButtonText("Sitzung eröffnen"))
                assertFalse(el.hasButtonText("Beschlussfähigkeit feststellen"))
                assertFalse(el.hasButtonText("Nächster Punkt"))
                assertEquals(3, el.allOf("ol li").size, "the agenda is visible")
                assertTrue(el.allOf(".conference-meeting-row").isNotEmpty())
            }
        }

    @Test
    fun aParticipant_getsNoNamesNoRosterNoAttendance_onlyAgendaAndTheEstablishedLine(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(role = MeetingSessionRole.PARTICIPANT))
                awaitUntil("agenda") { el.allOf("ol li").size == 3 }
                assertFalse(el.text().contains("Person A"))
                assertTrue(el.allOf(".conference-meeting-row").isEmpty())
                assertFalse(el.allOf("h3").any { it.text() == "Anwesenheit" })
                assertTrue(el.text().contains("Noch nicht festgestellt"))
                assertFalse(el.text().contains("anwesend · beschlussfähig ab"), "no live figures")
                assertFalse(el.hasButtonText("Beschlussfähigkeit feststellen"))
            }
        }

    @Test
    fun theGeneralAssembly_getsTheHint_noRosterAndNoAdoptButton(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(rule = AttendanceRuleKind.UNSUPPORTED, entries = emptyList()))
                awaitUntil("hint") { el.text().contains("Die Anwesenheit der Mitgliederversammlung wird hier nicht geführt") }
                assertTrue(el.allOf(".conference-meeting-row").isEmpty())
                assertFalse(el.allOf("button").any { it.text().startsWith("Alle Verbundenen") })
            }
        }

    @Test
    fun adopt_isDisabledAtZero_andSendsWhenSomebodyIsConnected(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(adoptable = 0))
                awaitUntil("adopt button") { el.allOf("button").any { it.text() == "Alle Verbundenen übernehmen (0)" } }
                assertTrue(el.buttonText("Alle Verbundenen übernehmen (0)").disabled)
                calls.next = state(adoptable = 1)
                handle.apply(state(adoptable = 1))
                awaitUntil("enabled") { !el.buttonText("Alle Verbundenen übernehmen (1)").disabled }
                el.buttonText("Alle Verbundenen übernehmen (1)").click()
                awaitUntil("sent") { calls.adopts == 1 }
            }
        }

    @Test
    fun anIdenticalState_movesNothing_theListIsTheSameElement(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state())
                awaitUntil("list") { el.allOf("ol li").size == 3 }
                val list = assertNotNull(el.querySelector("ol"))
                handle.apply(state())
                handle.apply(state())
                assertSame(list, el.querySelector("ol"), "a poll that brings nothing new does not rebuild")
                handle.apply(state(current = "a2"))
                awaitUntil("rebuilt") { el.allOf("ol li")[1].getAttribute("aria-current") == "step" }
                assertNotSame(list, el.querySelector("ol"))
            }
        }

    @Test
    fun aNullState_hidesTheBody_andAnEmptyAgendaSaysSo(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state().copy(agenda = emptyList(), currentAgendaItemId = null))
                awaitUntil("empty agenda") { el.text().contains("Keine Tagesordnung hinterlegt.") }
                handle.apply(null)
                awaitUntil("hidden") { el.querySelector(".conference-meeting-body") == null }
            }
        }

    @Test
    fun theToggle_isHiddenUntilItIsToldThereIsASitzung_andCarriesTheAccessibleName(): Promise<Unit> =
        formTest {
            mountedForm("meeting-toggle") { root, element ->
                val toggle = root.conferenceMeetingToggle()
                // hidden until told otherwise: a hidden KVision widget has no element at all
                assertNull(element().querySelector("button"))
                toggle.update(visible = true, pressed = false)
                awaitUntil("shown") { element().querySelector("button") != null }
                val button = element().querySelector("button") as HTMLElement
                assertEquals("Sitzungsmodus", button.getAttribute("aria-label"))
                assertEquals("false", button.getAttribute("aria-pressed"))
                toggle.update(visible = true, pressed = true)
                awaitUntil("pressed") { button.getAttribute("aria-pressed") == "true" }
                assertTrue(button.classList.contains("active"))
                toggle.update(visible = false, pressed = false)
                awaitUntil("hidden again") { element().querySelector("button") == null }
            }
        }

    @Test
    fun aRosterRow_hasAnAccessibleStatusControl_andAConnectedMarkerThatIsAlsoText(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val first = el.allOf(".conference-meeting-row")[0]
                assertTrue(first.text().contains("Im Raum verbunden"), "connected is a word, not only a colour")
                assertFalse(el.allOf(".conference-meeting-row")[1].text().contains("Im Raum verbunden"))
                assertNotNull(el.querySelector("[aria-label='Anwesenheit: Person A']"))
                kotlinx.coroutines.delay(300)
                assertTrue(calls.attendance.isEmpty(), "building the rows sends nothing")
            }
        }

    @Test
    fun changingAStatus_sendsExactlyThatChange(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                calls.next = state()
                val select =
                    assertNotNull(
                        el.querySelector("select[aria-label='Anwesenheit: Person C']") as? org.w3c.dom.HTMLSelectElement,
                        "no native select",
                    )
                select.value = AttendanceStatus.EXCUSED.name
                select.dispatchEvent(
                    org.w3c.dom.events
                        .Event("change"),
                )
                awaitUntil("sent") { calls.attendance == listOf(SessionAttendanceInput("p2", AttendanceStatus.EXCUSED, null)) }
            }
        }

    @Test
    fun aConflictThatIsNotTheStaleView_isNotReportedAsTheAgendaMovingOn(): Promise<Unit> =
        formTest {
            val calls =
                Calls().also {
                    it.moveOtherConflict = true
                }
            withPanel(calls) { el, handle ->
                handle.apply(state(current = "a1"))
                awaitUntil("button") { el.hasButtonText("Nächster Punkt") }
                el.buttonText("Nächster Punkt").click()
                awaitUntil("fresh reading") { calls.stale == 1 }
                assertFalse(el.text().contains("Die Tagesordnung wurde inzwischen weitergeschaltet."))
            }
        }

    @Test
    fun aRefusedStatusChange_putsTheSelectBackToTheServerValue(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.attendanceConflict = true }
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select =
                    assertNotNull(
                        el.querySelector("select[aria-label='Anwesenheit: Person C']") as? org.w3c.dom.HTMLSelectElement,
                        "no native select",
                    )
                select.value = AttendanceStatus.PRESENT.name
                select.dispatchEvent(
                    org.w3c.dom.events
                        .Event("change"),
                )
                awaitUntil("refused") { calls.stale == 1 }
                // the fresh reading equals the old state: the row must still be drawn again
                handle.apply(state())
                awaitUntil("select reset") {
                    (el.querySelector("select[aria-label='Anwesenheit: Person C']") as? org.w3c.dom.HTMLSelectElement)?.value == ""
                }
            }
        }

    // ---- V1.9.242: a section is not rebuilt under a focused control or a pressed pointer ----

    private fun rosterOf(
        names: List<String> = listOf("Person A", "Person B", "Person C"),
        connected: Set<Int> = setOf(0),
        statuses: List<AttendanceStatus?> = listOf(AttendanceStatus.PRESENT, AttendanceStatus.PRESENT, null),
    ) = names.mapIndexed { i, n -> SessionRosterEntryDto("p$i", n, statuses[i], connected = i in connected) }

    private fun selectOf(
        el: HTMLElement,
        name: String,
    ) = assertNotNull(
        el.querySelector("select[aria-label='Anwesenheit: $name']") as? org.w3c.dom.HTMLSelectElement,
        "no select for $name",
    )

    /** A real focus move away plus the event a browser would send; the guard flushes on the next tick. */
    private fun leave(select: org.w3c.dom.HTMLSelectElement) {
        select.blur()
        select.dispatchEvent(
            org.w3c.dom.events
                .FocusEvent(
                    "focusout",
                    org.w3c.dom.events
                        .FocusEventInit(bubbles = true),
                ),
        )
    }

    @Test
    fun anUnchangedPayload_keepsEverySectionsNodes(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state())
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val sections = listOf("quorum", "agenda", "attendance", "head")
                val before = sections.map { el.querySelector(".conference-meeting-$it")?.firstElementChild }
                val select = selectOf(el, "Person C")
                handle.apply(state())
                handle.apply(state())
                val after = sections.map { el.querySelector(".conference-meeting-$it")?.firstElementChild }
                sections.indices.forEach { assertSame(before[it], after[it], "section ${sections[it]} was rebuilt") }
                assertSame(select, selectOf(el, "Person C"))
            }
        }

    @Test
    fun aFocusedSelect_survivesAPollThatOnlyChangesWhoIsInTheRoom(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(entries = rosterOf()))
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select = selectOf(el, "Person C")
                select.focus()
                assertSame(select, kotlinx.browser.document.activeElement)
                handle.apply(state(entries = rosterOf(connected = setOf(1))))
                assertSame(select, selectOf(el, "Person C"), "the select node is the same")
                assertSame(select, kotlinx.browser.document.activeElement, "and it keeps the focus")
                assertFalse(el.allOf(".conference-meeting-row")[0].text().contains("Im Raum verbunden"), "A left the room")
                assertTrue(el.allOf(".conference-meeting-row")[1].text().contains("Im Raum verbunden"), "B joined")
            }
        }

    @Test
    fun theConnectedMarker_isPatchedInPlace_withoutTouchingTheSelect(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(entries = rosterOf()))
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select = selectOf(el, "Person A")
                val row = el.allOf(".conference-meeting-row")[0]
                assertTrue(row.querySelector(".fa-wifi") != null)
                handle.apply(state(entries = rosterOf(connected = emptySet())))
                assertNull(row.querySelector(".fa-wifi"), "icon gone")
                assertFalse(row.text().contains("Im Raum verbunden"), "and its text")
                handle.apply(state(entries = rosterOf(connected = setOf(0, 2))))
                assertTrue(row.querySelector(".fa-wifi") != null, "icon back")
                assertTrue(el.allOf(".conference-meeting-row")[2].text().contains("Im Raum verbunden"))
                assertSame(select, selectOf(el, "Person A"))
                assertSame(row, el.allOf(".conference-meeting-row")[0], "the row itself was not rebuilt")
            }
        }

    @Test
    fun aChangeUnderAFocusedControl_waitsForTheFocusToLeave(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(entries = rosterOf()))
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select = selectOf(el, "Person C")
                select.focus()
                handle.apply(state(entries = rosterOf(names = listOf("Person A", "Neuer Name", "Person C"))))
                assertSame(select, selectOf(el, "Person C"))
                assertFalse(el.text().contains("Neuer Name"), "not drawn while the select has the focus")
                leave(select)
                awaitUntil("drawn after the focus left") { el.text().contains("Neuer Name") }
            }
        }

    @Test
    fun twoDeferredStates_onlyTheNewestIsDrawn(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(entries = rosterOf()))
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select = selectOf(el, "Person C")
                select.focus()
                handle.apply(state(entries = rosterOf(names = listOf("Person A", "Zwischenstand", "Person C"))))
                handle.apply(state(entries = rosterOf(names = listOf("Person A", "Endstand", "Person C"))))
                leave(select)
                awaitUntil("newest drawn") { el.text().contains("Endstand") }
                assertFalse(el.text().contains("Zwischenstand"))
            }
        }

    @Test
    fun aPressedPointer_keepsTheNode_andTheClickStillLandsOnIt(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(current = "a1"))
                awaitUntil("buttons") { el.hasButtonText("Nächster Punkt") }
                val button = el.buttonText("Nächster Punkt")
                calls.next = state(current = "a2")
                button.dispatchEvent(
                    org.w3c.dom.events
                        .Event("pointerdown"),
                )
                // a poll arrives while the pointer is down: the agenda would be rebuilt
                handle.apply(state(current = "a2"))
                assertTrue(button.isConnected, "the pressed button is still in the document")
                button.dispatchEvent(
                    org.w3c.dom.events
                        .Event("pointerup"),
                )
                button.click() // the click that follows pointerup, before the guard's next tick
                awaitUntil("click handled on the original node") { calls.moves == listOf<Pair<String?, String>>("a1" to "a2") }
                awaitUntil("drawn after the click") { el.allOf("ol li")[1].getAttribute("aria-current") == "step" }
            }
        }

    @Test
    fun aLostPointerUp_neverBlocksDrawingForGood(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(current = "a1"))
                awaitUntil("buttons") { el.hasButtonText("Nächster Punkt") }
                el.buttonText("Nächster Punkt").dispatchEvent(
                    org.w3c.dom.events
                        .Event("pointerdown"),
                )
                handle.apply(state(current = "a2"))
                assertEquals("step", el.allOf("ol li")[0].getAttribute("aria-current"), "deferred while the pointer is down")
                // no pointerup ever comes: the safety timer releases the guard
                awaitUntil("released by the safety timer") { el.allOf("ol li")[1].getAttribute("aria-current") == "step" }
            }
        }

    @Test
    fun aRefusedChange_isDrawnAgainEvenWithTheFocusInsideTheSection(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.attendanceConflict = true }
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select = selectOf(el, "Person C")
                select.focus()
                select.value = AttendanceStatus.PRESENT.name
                select.dispatchEvent(
                    org.w3c.dom.events
                        .Event("change"),
                )
                awaitUntil("refused") { calls.stale == 1 }
                handle.apply(state())
                awaitUntil("select reset under the focus") { selectOf(el, "Person C").value == "" }
            }
        }

    @Test
    fun afterDispose_aWaitingDrawingNeverHappens(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(entries = rosterOf()))
                awaitUntil("rows") { el.allOf(".conference-meeting-row").size == 3 }
                val select = selectOf(el, "Person C")
                select.focus()
                handle.apply(state(entries = rosterOf(names = listOf("Person A", "Nach Dispose", "Person C"))))
                handle.dispose()
                leave(select)
                kotlinx.coroutines.delay(100)
                assertFalse(el.text().contains("Nach Dispose"))
            }
        }
}
