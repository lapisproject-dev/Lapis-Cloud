package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.ProtocolAgendaItemDto
import network.lapis.cloud.shared.domain.ProtocolAttendanceDto
import network.lapis.cloud.shared.domain.ProtocolBlockSaveResultDto
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolHeaderDto
import network.lapis.cloud.shared.domain.ProtocolMemberRefDto
import network.lapis.cloud.shared.domain.ProtocolPersonDto
import network.lapis.cloud.shared.domain.ProtocolResolutionDto
import network.lapis.cloud.shared.domain.ProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SessionAgendaItemDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.239 -- the Protokoll draft section mounted for real (Karma/Chrome): what each role sees, what a click sends, what a lost race keeps. */
class ConferenceProtocolDraftSectionDomTest {
    private val resolution =
        ProtocolResolutionDto(
            resolutionId = "r1",
            number = "B-1",
            title = "Haushalt",
            text = "Der Haushalt wird beschlossen.",
            yes = 3,
            no = 1,
            abstain = 0,
            status = ResolutionStatus.ADOPTED,
            quorumMet = true,
            decidedAt = LocalDateTime(2030, 5, 4, 19, 40),
            secretBallot = false,
        )

    private fun item(
        id: String,
        position: Int,
        body: String = "",
        version: Long = 0,
        resolutions: List<ProtocolResolutionDto> = emptyList(),
        tasks: List<ProtocolTaskDto> = emptyList(),
    ) = ProtocolAgendaItemDto(
        agendaItemId = id,
        position = position,
        title = "TOP $position",
        body = body,
        version = version,
        updatedAt = if (version > 0) LocalDateTime(2030, 5, 4, 19, 30) else null,
        resolutions = resolutions,
        tasks = tasks,
    )

    private fun draft(
        editable: Boolean = true,
        role: MeetingSessionRole = MeetingSessionRole.LEAD,
        items: List<ProtocolAgendaItemDto> = listOf(item("a1", 1), item("a2", 2)),
    ) = MeetingProtocolDraftDto(
        meetingId = "m1",
        phase = ProtocolDraftPhase.DRAFT,
        editable = editable,
        role = role,
        header =
            ProtocolHeaderDto(
                committeeName = "Vorstand",
                committeeType = CommitteeType.EXECUTIVE_BOARD,
                title = "Vorstandssitzung",
                scheduledAt = LocalDateTime(2030, 5, 4, 19, 0),
                format = MeetingFormat.ONLINE,
                location = null,
                openedAt = LocalDateTime(2030, 5, 4, 19, 5),
                endedAt = null,
                chairName = "Chris Chair",
                minuteTakerName = "Mia Minutes",
            ),
        attendance = ProtocolAttendanceDto(present = listOf(ProtocolPersonDto("Anna Anwesend"))),
        quorum = null,
        items = items,
        assignableMembers =
            if (editable) {
                listOf(
                    ProtocolMemberRefDto("p1", "Anna Anwesend"),
                    ProtocolMemberRefDto("p2", "Bert Beisitz"),
                )
            } else {
                emptyList()
            },
        taskCount = items.sumOf { it.tasks.size },
        serverNow = LocalDateTime(2030, 5, 4, 19, 30),
    )

    private class Calls {
        var next: MeetingProtocolDraftDto? = null
        val saves = mutableListOf<Triple<String, Long, String>>()
        var saveAnswer: (
            String,
            Long,
            String,
        ) -> ProtocolBlockSaveResultDto = { _, v, _ -> ProtocolBlockSaveResultDto(true, v + 1, null, null) }
        val tasks = mutableListOf<ProtocolTaskInput>()
        val removed = mutableListOf<String>()
        var gets = 0
    }

    private object NoTimers : ConferenceVoteScheduler {
        override fun now(): Double = 0.0

        override fun schedule(
            delayMs: Int,
            block: () -> Unit,
        ): Int = 0

        override fun cancel(handle: Int) = Unit
    }

    private suspend fun <T> withSection(
        calls: Calls,
        block: suspend (HTMLElement, ProtocolDraftSectionHandle) -> T,
    ): T {
        val rpc =
            ProtocolDraftRpc(
                get = {
                    calls.gets++
                    calls.next!!
                },
                save = { _, item, version, body ->
                    calls.saves += Triple(item, version, body)
                    calls.saveAnswer(item, version, body)
                },
                addTask = { _, input ->
                    calls.tasks += input
                    ProtocolTaskDto(
                        "t${calls.tasks.size}",
                        input.agendaItemId,
                        "Anna Anwesend",
                        input.description,
                        input.dueDate,
                        LocalDateTime(2030, 5, 4, 19, 31),
                    )
                },
                removeTask = { _, id -> calls.removed += id },
            )
        return mountedForm("protocol-draft") { root: Root, element ->
            val handle =
                renderConferenceProtocolDraftSection(
                    parent = root,
                    meetingId = "m1",
                    rpc = rpc,
                    scheduler = NoTimers,
                    clock = { 1_000.0 },
                    isHidden = { false },
                )
            handle.setActive(true)
            try {
                block(element(), handle)
            } finally {
                handle.dispose()
            }
        }
    }

    private fun HTMLElement.text(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun HTMLElement.areas(): List<HTMLTextAreaElement> = allOf(".protocol-textarea textarea").map { it as HTMLTextAreaElement }

    private fun HTMLElement.button(label: String): HTMLElement =
        assertNotNull(
            allOf(
                "button",
            ).firstOrNull { it.text() == label || it.getAttribute("aria-label") == label || it.getAttribute("title") == label },
            "no button '$label'",
        )

    private fun HTMLTextAreaElement.type(text: String) {
        value = text
        dispatchEvent(Event("input"))
    }

    @Test
    fun theLead_seesTheSystemPartsMarked_aTextFieldPerItem_theVersion_andTheToolbarOfTheActiveItem(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "Hallo", 2, listOf(resolution)), item("a2", 2))) }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                assertTrue(el.text().contains("Entwurf – nur für Mitglieder des Gremiums sichtbar. Noch nicht fertiggestellt."))
                assertTrue(el.text().contains("Vorstandssitzung"))
                assertTrue(el.text().contains("Chris Chair"))
                assertTrue(el.text().contains("Anna Anwesend"))
                assertEquals("Hallo", el.areas()[0].value)
                assertTrue(el.text().contains("Fassung 2"))
                assertTrue(el.text().contains("Fassung 0"))
                assertTrue(el.text().contains("Beschluss B-1"))
                assertTrue(el.text().contains("Ja 3 · Nein 1 · Enthaltung 0"))
                assertTrue(el.allOf(".protocol-system-part").isNotEmpty())
                assertTrue(el.allOf("[aria-label='Aus der Sitzung übernommen']").isNotEmpty(), "the lock has a name")
                assertTrue(el.allOf(".protocol-toolbar").isNotEmpty())
                listOf("Speichern", "Beschluss einfügen", "Aufgabe hinzufügen", "Textbaustein ▾", "Zeitmarke").forEach { el.button(it) }
                assertTrue(calls.gets >= 1, "read at least once")
            }
        }

    @Test
    fun aReader_getsTheSameViewWithoutFieldsAndWithoutTools(): Promise<Unit> =
        formTest {
            val calls =
                Calls().also {
                    it.next = draft(editable = false, role = MeetingSessionRole.PARTICIPANT, items = listOf(item("a1", 1, "Nur lesen", 1)))
                }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.text().contains("Nur lesen") }
                assertTrue(el.areas().isEmpty(), "no field")
                assertTrue(el.allOf("textarea").isEmpty())
                assertTrue(el.allOf("button").isEmpty(), "no tool")
                assertTrue(el.text().contains("Nur lesen"))
            }
        }

    @Test
    fun insertions_goToTheCursor_theResolution_aBuildingBlock_andTheServerTime(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "Anfang Ende", 1, listOf(resolution)), item("a2", 2))) }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                val area = el.areas()[0]
                area.setSelectionRange(7, 7)
                area.dispatchEvent(Event("focus"))
                el.button("Beschluss einfügen").click()
                awaitUntil("resolution inserted") { area.value == "Anfang Beschluss B-1: Haushalt – siehe unten.Ende" }
                area.setSelectionRange(0, 0)
                el.button("Zeitmarke").click()
                awaitUntil("time inserted") { Regex("^\\[\\d{2}:\\d{2}\\] Anfang").containsMatchIn(area.value) }
                el.button("Textbaustein ▾").click()
                awaitUntil("menu") { el.allOf(".protocol-menu-entry").size == 6 }
                el.allOf(".protocol-menu-entry")[4].click()
                awaitUntil("block inserted") { area.value.contains("Der Punkt wird vertagt.") }
                assertEquals(0, calls.saves.size, "nothing is saved by an insertion")
            }
        }

    @Test
    fun savingHappensOnLeavingTheField_andNamesTheVersionTheTextWasBasedOn(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "alt", 3), item("a2", 2))) }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                val area = el.areas()[0]
                area.dispatchEvent(Event("focus"))
                area.type("neu")
                assertEquals(0, calls.saves.size, "typing saves nothing")
                area.dispatchEvent(Event("blur"))
                awaitUntil("saved") { calls.saves == listOf(Triple("a1", 3L, "neu")) }
                awaitUntil("version shown") { el.text().contains("Fassung 4") }
            }
        }

    @Test
    fun aLostRace_keepsTheOwnText_showsTheNewerOneReadOnlyAbove_andTheMessage(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "alt", 1), item("a2", 2))) }
            calls.saveAnswer =
                { _, _, _ -> ProtocolBlockSaveResultDto(false, 5, "Text der anderen Person", LocalDateTime(2030, 5, 4, 19, 33)) }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                val area = el.areas()[0]
                area.type("mein Text")
                el.button("Speichern").click()
                awaitUntil("conflict") { el.text().contains("Inzwischen geändert – bitte zusammenführen") }
                assertEquals("mein Text", area.value, "the own text is kept")
                val box = el.allOf(".protocol-conflict-body").single()
                assertEquals("Text der anderen Person", box.text())
                assertEquals(0, box.allOf("textarea").size, "read-only: not a field")
                assertTrue(el.text().contains("Fassung 5"), "the base moves to the newer version")
                // saving again is a deliberate overwrite on the newer version
                calls.saveAnswer = { _, v, _ -> ProtocolBlockSaveResultDto(true, v + 1, null, null) }
                el.button("Speichern").click()
                awaitUntil("overwritten") { calls.saves.last() == Triple("a1", 5L, "mein Text") }
                awaitUntil("conflict gone") { !el.text().contains("Inzwischen geändert – bitte zusammenführen") }
            }
        }

    @Test
    fun aReload_neverOverwritesAChangedBlock_butUpdatesAnUntouchedOne(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "eins", 1), item("a2", 2, "zwei", 1))) }
            withSection(calls) { el, handle ->
                awaitUntil("the draft") { el.areas().size == 2 }
                el.areas()[0].type("meine Änderung")
                calls.next = draft(items = listOf(item("a1", 1, "fremd 1", 2), item("a2", 2, "fremd 2", 2)))
                handle.refreshNow()
                awaitUntil("untouched block follows") { el.areas()[1].value == "fremd 2" }
                assertEquals("meine Änderung", el.areas()[0].value, "the changed block stays as typed")
            }
        }

    @Test
    fun theTaskForm_refusesWithoutAPerson_andOtherwiseSendsPersonTextAndDate(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft() }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                el.areas()[0].dispatchEvent(Event("focus"))
                el.button("Aufgabe hinzufügen").click()
                awaitUntil("the form") { el.allOf(".protocol-task-form select").isNotEmpty() }
                val form = el.allOf(".protocol-task-form").first()
                (form.allOf("textarea").first() as HTMLTextAreaElement).type("Protokoll verschicken")
                form.buttonNamed("Aufgabe hinzufügen").click()
                awaitUntil("refused") { form.text().contains("Bitte eine Person aus dem Gremium wählen.") }
                assertEquals(0, calls.tasks.size)
                val select = form.allOf("select").first() as HTMLSelectElement
                select.value = "p2"
                select.dispatchEvent(Event("change"))
                form.buttonNamed("Aufgabe hinzufügen").click()
                awaitUntil("sent") { calls.tasks.size == 1 }
                assertEquals(ProtocolTaskInput("a1", "p2", "Protokoll verschicken", null), calls.tasks.single())
            }
        }

    @Test
    fun aTaskIsListedWithItsPersonAndDate_andCanBeRemoved(): Promise<Unit> =
        formTest {
            val task =
                ProtocolTaskDto("t9", "a1", "Anna Anwesend", "Mail schreiben", LocalDate(2030, 6, 1), LocalDateTime(2030, 5, 4, 19, 31))
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, tasks = listOf(task)), item("a2", 2))) }
            withSection(calls) { el, _ ->
                awaitUntil("the task") { el.text().contains("Mail schreiben") }
                assertTrue(el.text().contains("Anna Anwesend: Mail schreiben"))
                el.button("Aufgabe entfernen").click()
                awaitUntil("removed") { calls.removed == listOf("t9") }
            }
        }

    @Test
    fun text_isShownAsText_aTagBecomesNoElement(): Promise<Unit> =
        formTest {
            val evil = "<img src=x onerror=alert(1)>"
            val calls =
                Calls().also {
                    it.next =
                        draft(
                            editable = false,
                            role = MeetingSessionRole.PARTICIPANT,
                            items = listOf(item("a1", 1, evil, 1, listOf(resolution.copy(title = evil)))),
                        )
                }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.text().contains(evil) }
                assertEquals(0, el.allOf("img").size, "no element is created from the text")
                assertTrue(el.text().contains(evil))
            }
        }

    @Test
    fun theSecretBallot_isMarkedWithAName(): Promise<Unit> =
        formTest {
            val calls =
                Calls().also {
                    it.next =
                        draft(items = listOf(item("a1", 1, resolutions = listOf(resolution.copy(secretBallot = true))), item("a2", 2)))
                }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                assertFalse(el.allOf("[aria-label='Geheime Abstimmung – nur Summen']").isEmpty())
            }
        }

    @Test
    fun aReader_followsTheTextOfAnItemAfterAReload(): Promise<Unit> =
        formTest {
            val calls =
                Calls().also {
                    it.next = draft(editable = false, role = MeetingSessionRole.PARTICIPANT, items = listOf(item("a1", 1, "", 0)))
                }
            withSection(calls) { el, handle ->
                awaitUntil("the draft") { el.text().contains("TOP 1") }
                calls.next =
                    draft(editable = false, role = MeetingSessionRole.PARTICIPANT, items = listOf(item("a1", 1, "Neuer Text zu TOP 1", 1)))
                handle.refreshNow()
                awaitUntil("the new text") { el.text().contains("Neuer Text zu TOP 1") }
                calls.next =
                    draft(editable = false, role = MeetingSessionRole.PARTICIPANT, items = listOf(item("a1", 1, "Dritte Fassung", 2)))
                handle.refreshNow()
                awaitUntil("the third text") { el.text().contains("Dritte Fassung") && !el.text().contains("Neuer Text zu TOP 1") }
            }
        }

    @Test
    fun withAnOpenConflict_leavingTheFieldNeverSaves_onlyTheSaveButtonDoes(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "alt", 1), item("a2", 2))) }
            calls.saveAnswer =
                { _, _, _ -> ProtocolBlockSaveResultDto(false, 5, "Text der anderen Person", LocalDateTime(2030, 5, 4, 19, 33)) }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                val area = el.areas()[0]
                area.dispatchEvent(Event("focus"))
                area.type("mein Text")
                el.button("Speichern").click()
                awaitUntil("conflict") { el.text().contains("Inzwischen geändert – bitte zusammenführen") }
                val savesAfterConflict = calls.saves.size
                calls.saveAnswer = { _, v, _ -> ProtocolBlockSaveResultDto(true, v + 1, null, null) }
                // clicking into the field and out again (to copy the other text) must not overwrite it
                area.dispatchEvent(Event("focus"))
                area.dispatchEvent(Event("blur"))
                area.dispatchEvent(Event("focus"))
                area.dispatchEvent(Event("blur"))
                // save() launches asynchronously, so give the dispatcher time before asserting nothing happened
                delay(100)
                assertEquals(savesAfterConflict, calls.saves.size, "no automatic save while the conflict is open")
                assertTrue(el.text().contains("Text der anderen Person"), "the other text stays")
                el.button("Speichern").click()
                awaitUntil("explicit overwrite") { calls.saves.size == savesAfterConflict + 1 }
            }
        }

    @Test
    fun activatingAnActiveSection_doesNotReadAgain(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft() }
            withSection(calls) { el, handle ->
                awaitUntil("the draft") { el.areas().size == 2 }
                val reads = calls.gets
                handle.setActive(true)
                handle.setActive(true)
                // reload() launches asynchronously, so give the dispatcher time before asserting nothing happened
                delay(100)
                assertEquals(reads, calls.gets, "the tab repaint of every poll must not start a read")
            }
        }

    @Test
    fun aSecretVoteWithWithheldFigures_showsNoNumbers(): Promise<Unit> =
        formTest {
            val withheld = resolution.copy(secretBallot = true, figuresWithheld = true, yes = 0, no = 0, abstain = 0)
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, resolutions = listOf(withheld)), item("a2", 2))) }
            withSection(calls) { el, _ ->
                awaitUntil("the draft") { el.areas().size == 2 }
                assertFalse(el.text().contains("Ja 0 · Nein 0"), "no zero figures that look like a result")
                assertTrue(el.text().contains("Beschluss B-1"))
            }
        }

    // ---- inside the panel ----

    private fun panelState(role: MeetingSessionRole) =
        ConferenceMeetingStateDto(
            meetingId = "m1",
            meetingTitle = "Vorstandssitzung",
            committeeId = "c1",
            phase = MeetingSessionPhase.OPEN,
            openedAt = LocalDateTime(2030, 5, 4, 19, 5),
            callerRole = role,
            ruleKind = network.lapis.cloud.shared.domain.AttendanceRuleKind.NAME_LIST,
            agenda = listOf(SessionAgendaItemDto("a1", 1, "TOP 1")),
        )

    @Test
    fun inThePanel_everyRoleHasTheTab_theDraftIsOnlyBuiltAndReadWhenItIsShown(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.next = draft(items = listOf(item("a1", 1, "Text", 1))) }
            val rpc =
                ProtocolDraftRpc(
                    get = {
                        calls.gets++
                        calls.next!!
                    },
                )
            mountedForm("protocol-panel") { root: Root, element ->
                val handle =
                    renderConferenceMeetingPanel(
                        parent = root,
                        roomId = "r1",
                        rpc = ConferenceMeetingRpc(getState = { null }),
                        guestSearch = { { _, _ -> null } },
                        protocolRpc = rpc,
                    )
                handle.setOpen(true)
                try {
                    val el = element()
                    handle.apply(panelState(MeetingSessionRole.PARTICIPANT))
                    awaitUntil("tabs") { el.allOf("button[role=tab]").size == 2 }
                    assertEquals(0, calls.gets, "not read before the tab is shown")
                    assertTrue(el.allOf(".protocol-draft").isEmpty(), "no markup of the draft on the Sitzung tab")
                    el.allOf("button[role=tab]")[1].click()
                    awaitUntil("draft read") { calls.gets >= 1 && el.text().contains("Text") }
                    assertEquals("true", el.allOf("button[role=tab]")[1].getAttribute("aria-selected"))
                    el.allOf("button[role=tab]")[0].click()
                    awaitUntil("back") {
                        el
                            .allOf(".conference-meeting-body")
                            .first()
                            .getAttribute("style")
                            ?.contains("none") != true
                    }
                } finally {
                    handle.dispose()
                }
            }
            assertNull(null)
        }
}
