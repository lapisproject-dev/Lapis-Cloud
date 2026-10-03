package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.form.text.text
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomInput
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventSeriesCreateResultDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V1.9.47 -- rule R36B for the events group, part 1: [renderEventsScreen] and [renderEventRoomsScreen]. After the load the create form is
 * collapsed and its button sits in the title row, Escape asks only for a changed form (also when only a series control was touched), a save
 * folds the form back and reloads the list, a failed save keeps the form and its input, and rooms that arrive while the form is open fill
 * the room select without touching what was typed.
 */
class EventsCollapsibleFormsDomTest {
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"

    private fun boardSession() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.actionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.formOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun awaitDiscardDialog(): HTMLElement {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        return lastOpenModal()
    }

    /** The create form host holds children (a built form), unlike the empty collapsed host. */
    private suspend fun openForm(
        screen: HTMLElement,
        formId: String,
    ): HTMLElement {
        createFormButton(screen, formId).click()
        awaitUntil("the form '$formId' is built") { screen.formOpen(formId) && screen.querySelector("[id='$formId'] label") != null }
        return screen.querySelector("[id='$formId']") as HTMLElement
    }

    private fun event(
        id: String,
        title: String,
    ) = EventDto(
        id = id,
        slug = "slug-$id",
        title = title,
        description = "Beschreibung",
        locationText = "Halle",
        onlineUrl = null,
        startsAt = LocalDateTime(2099, 11, 1, 18, 0),
        endsAt = LocalDateTime(2099, 11, 1, 20, 0),
        capacity = null,
        feeAmount = 0.0.toDecimal(),
        feeCurrency = "EUR",
        status = EventStatus.PUBLISHED,
        visibility = EventVisibility.MEMBERS_ONLY,
        registrationClosesAt = null,
        occupiedSeats = 0,
        waitlistCount = 0,
        full = false,
        feeEditable = true,
        ownRegistrationStatus = null,
        publicUrl = null,
    )

    private val seedInput =
        EventInput(
            "t",
            "d",
            null,
            null,
            LocalDateTime(2099, 1, 1, 0, 0),
            LocalDateTime(2099, 1, 1, 1, 0),
            null,
            0.0.toDecimal(),
            "EUR",
            EventVisibility.MEMBERS_ONLY,
            null,
            null,
        )

    private fun page(rows: List<EventDto>) = jsonOf(EventPageDto.serializer(), EventPageDto(rows, rows.size, 200, 0))

    private fun room(
        id: String,
        name: String,
    ) = EventRoomDto(id = id, name = name, capacity = 20, equipmentTags = emptyList(), status = EventRoomStatus.ACTIVE)

    private fun HTMLElement.fillRequiredEventFields(title: String) {
        typeInto("Titel", title)
        typeInto("Beschreibung", "Ein Abend für alle")
        typeInto("Ort", "Vereinsheim")
        typeInto("Beginn", "2099-11-01T18:00")
        typeInto("Ende", "2099-11-01T20:00")
    }

    // ---- Events ---------------------------------------------------------------------------------------------------------

    @Test
    fun events_collapsed_roomsLoadedOnce_escapeAsksOnlyAfterTyping_andASaveFoldsBackAndReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val create = routeOf { rpcService<IEventService>().createEvent(seedInput) }
            val rooms = routeOf { rpcService<IEventRoomService>().listRooms() }
            val rows = mutableListOf(event("1", "Altbestand"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(rows))
                        request.rpcRoute == rooms ->
                            request.answerWith(jsonOf(ListSerializer(EventRoomDto.serializer()), listOf(room("r1", "Aula"))))
                        request.rpcRoute == create -> {
                            val added = event("2", "Sommerfest")
                            rows += added
                            request.answerWith(jsonOf(EventDto.serializer(), added))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-events-screen") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altbestand") }
                    val screen = element()
                    assertEquals(listOf("Neue Veranstaltung"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neue Veranstaltung anlegen"), "the old always-visible section title is gone")
                    assertFalse(screen.formOpen("lapis-create-event"), "collapsed after the load")
                    assertEquals(1, calls.toRoute(rooms).size, "the rooms are loaded once, not a second time for the form")

                    val host = openForm(screen, "lapis-create-event")
                    escape(host)
                    awaitUntil("closed without a question") { !screen.formOpen("lapis-create-event") }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openForm(screen, "lapis-create-event")
                    reopened.typeInto("Titel", "Sommerfest")
                    escape(reopened)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertEquals("Sommerfest", (reopened.controlOf("Titel") as HTMLInputElement).value, "keep editing keeps the input")

                    reopened.fillRequiredEventFields("Sommerfest")
                    val listCallsBefore = calls.toRoute(list).size
                    screen.buttonNamed("Veranstaltung anlegen").click()
                    awaitUntil("createEvent was called") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.formOpen("lapis-create-event") }
                    awaitUntil("the new row is listed") { screen.shows("Sommerfest") }
                    val sent = calls.singleCall(create).rpcParam(0)
                    assertEquals("Sommerfest", sent.title as String)
                    assertEquals("Ein Abend für alle", sent.description as String)
                    assertEquals("Vereinsheim", sent.locationText as String)
                    assertEquals(listCallsBefore + 1, calls.toRoute(list).size, "the list was reloaded once after the save")
                    val button = createFormButton(screen, "lapis-create-event")
                    assertEquals("false", button.getAttribute("aria-expanded"))
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                    // the navigation row and the status filter are still there and unchanged
                    assertTrue(screen.shows("Helfer-Schichten") && screen.shows("Catering") && screen.shows("Check-in"))
                    assertTrue(screen.shows("Vergangene anzeigen"))
                }
            }
        }

    @Test
    fun events_aSeriesIsCreatedAndFoldsTheFormBack(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val series =
                routeOf {
                    rpcService<IEventService>().createEventSeries(seedInput, RecurrenceRuleInput(RecurrenceFrequency.DAILY, 1))
                }
            val plain = routeOf { rpcService<IEventService>().createEvent(seedInput) }
            val first = event("s1", "Stammtisch")
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(emptyList()))
                        request.rpcRoute == series ->
                            request.answerWith(
                                jsonOf(
                                    EventSeriesCreateResultDto.serializer(),
                                    EventSeriesCreateResultDto("series-1", listOf("s1", "s2"), first),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-events-series") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Keine Veranstaltungen gefunden.") }
                    val screen = element()
                    val host = openForm(screen, "lapis-create-event")
                    host.tick("Wiederkehrende Veranstaltung")
                    host.fillRequiredEventFields("Stammtisch")
                    screen.buttonNamed("Veranstaltung anlegen").click()
                    awaitUntil("createEventSeries was called") { calls.toRoute(series).size == 1 }
                    awaitUntil("the form folded back") { !screen.formOpen("lapis-create-event") }
                    assertEquals(0, calls.toRoute(plain).size, "no plain createEvent for a series")
                }
            }
        }

    @Test
    fun events_aTouchedSeriesControlMakesEscapeAskBeforeDiscarding(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(emptyList())) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("r36b-events-series-dirty") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Keine Veranstaltungen gefunden.") }
                    val screen = element()
                    val host = openForm(screen, "lapis-create-event")
                    host.tick("Wiederkehrende Veranstaltung")
                    escape(host)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertTrue(screen.formOpen("lapis-create-event"), "the form is still open")
                }
            }
        }

    @Test
    fun recurrenceEditor_stateFingerprint_changesWithAWeekdayChipAndReturns(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("r36b-events-fingerprint") { root, element ->
                    val starts = root.text()
                    val ends = root.text()
                    val editor = renderRecurrenceEditor(root, starts, ends)
                    awaitUntil("the editor is rendered") { element().querySelector("input[type=checkbox]") != null }
                    val initial = editor.stateFingerprint()
                    element().tick("Wiederkehrende Veranstaltung")
                    awaitUntil(
                        "the weekday chips are built",
                    ) { element().allOf("button.btn-sm").any { it.getAttribute("disabled") == null } }
                    val enabled = editor.stateFingerprint()
                    assertNotEquals(initial, enabled, "enabling the series changes the fingerprint")
                    val chip = element().allOf("button.btn-sm").first { it.getAttribute("disabled") == null }
                    chip.click()
                    awaitUntil("the chip is selected") { editor.stateFingerprint() != enabled }
                    chip.click()
                    awaitUntil("the chip is unselected again") { editor.stateFingerprint() == enabled }
                }
            }
        }

    @Test
    fun events_roomsThatArriveWhileTheFormIsOpen_fillTheSelectWithoutLosingInput(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            val rooms = routeOf { rpcService<IEventRoomService>().listRooms() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(listOf(event("1", "Altbestand"))))
                        request.rpcRoute == rooms ->
                            rpcResult(
                                request.json.id as Int,
                                jsonOf(ListSerializer(EventRoomDto.serializer()), listOf(room("r1", "Aula"))),
                            ).let { StubResponse(text = it.text, delayMs = 700) }
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-events-late-rooms") { root, element ->
                    renderEventsScreen(root)
                    val screen = element()
                    val host = openForm(screen, "lapis-create-event")
                    host.typeInto("Titel", "Tippfehler")
                    val roomSelect = host.controlOf("Raum") as HTMLSelectElement
                    assertEquals(1, roomSelect.options.length, "only 'Kein Raum' before the rooms arrive")
                    awaitUntil("the room arrived in the open form", timeoutMs = 5000) {
                        (host.controlOf("Raum") as HTMLSelectElement).options.length == 2
                    }
                    assertEquals("Tippfehler", (host.controlOf("Titel") as HTMLInputElement).value, "the typed title survives")
                    escape(host)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertTrue(screen.formOpen("lapis-create-event"), "the form is still dirty and open")
                }
            }
        }

    // ---- Event rooms ----------------------------------------------------------------------------------------------------

    @Test
    fun rooms_collapsed_saveFoldsBackAndReloads_andAConflictKeepsTheForm(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventRoomService>().listRooms() }
            val create = routeOf { rpcService<IEventRoomService>().createRoom(EventRoomInput("n", null, emptyList())) }
            val rows = mutableListOf(room("r1", "Altraum"))
            var conflictNext = true
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(EventRoomDto.serializer()), rows))
                        request.rpcRoute == create ->
                            if (conflictNext) {
                                conflictNext = false
                                serviceExceptionResult(request.json.id as Int, conflict)
                            } else {
                                val added = room("r2", "Aula")
                                rows += added
                                request.answerWith(jsonOf(EventRoomDto.serializer(), added))
                            }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-event-rooms") { root, element ->
                    renderEventRoomsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altraum") }
                    val screen = element()
                    assertEquals(listOf("Neuer Raum"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neuen Raum anlegen"), "the old always-visible section title is gone")
                    assertFalse(screen.formOpen("lapis-create-event-room"), "collapsed after the load")

                    val host = openForm(screen, "lapis-create-event-room")
                    escape(host)
                    awaitUntil("closed without a question") { !screen.formOpen("lapis-create-event-room") }

                    val reopened = openForm(screen, "lapis-create-event-room")
                    reopened.typeInto("Name", "Aula")
                    escape(reopened)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }

                    screen.buttonNamed("Raum anlegen").click()
                    awaitUntil("the first write was attempted") { calls.toRoute(create).size == 1 }
                    awaitUntil("the failed save is over") { !screen.buttonNamed("Raum anlegen").hasAttribute("disabled") }
                    assertTrue(screen.formOpen("lapis-create-event-room"), "a failed save never folds the form back")
                    assertEquals("Aula", (reopened.controlOf("Name") as HTMLInputElement).value)

                    screen.buttonNamed("Raum anlegen").click()
                    awaitUntil("the second write was made") { calls.toRoute(create).size == 2 }
                    awaitUntil("the form folded back") { !screen.formOpen("lapis-create-event-room") }
                    awaitUntil("the new room is listed") { screen.shows("Aula") }
                    assertEquals(
                        "Aula",
                        calls
                            .toRoute(create)
                            .last()
                            .rpcParam(0)
                            .name as String,
                    )
                    val button = createFormButton(screen, "lapis-create-event-room")
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                }
            }
        }

    @Test
    fun anActionButtonKeepsItsAccessibleName_afterTheEventsScreenRebuild(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc &&
                        request.rpcRoute == list
                    ) {
                        request.answerWith(page(listOf(event("1", "Altbestand"))))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-events-names") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altbestand") }
                    val names = element().allOf("button").map { it.textContent?.trim().orEmpty() }
                    assertTrue("Bearbeiten" in names && "Absagen" in names && "Neue Veranstaltung" in names, "names: $names")
                }
            }
        }
}
