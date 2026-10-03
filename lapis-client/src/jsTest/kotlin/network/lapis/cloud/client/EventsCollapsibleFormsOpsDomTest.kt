package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CateringOrderDto
import network.lapis.cloud.shared.domain.CateringOrderInput
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftInput
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICateringService
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IEventVolunteerService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.47 -- rule R36B for the events group, part 2: [renderCateringScreen] and [renderEventVolunteerShiftsScreen]. These two forms belong to
 * the event chosen in the picker above the list, so the picker is locked while a form is open, the form names its event on a line of its own
 * (an organizer-editable title stays text) and saves against the event it was opened for. Without an event there is no button at all.
 */
class EventsCollapsibleFormsOpsDomTest {
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

    private suspend fun openForm(
        screen: HTMLElement,
        formId: String,
    ): HTMLElement {
        createFormButton(screen, formId).click()
        awaitUntil("the form '$formId' is built") { screen.formOpen(formId) && screen.querySelector("[id='$formId'] label") != null }
        return screen.querySelector("[id='$formId']") as HTMLElement
    }

    private fun HTMLElement.eventPicker(): HTMLSelectElement = controlOf("Veranstaltung") as HTMLSelectElement

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

    private fun page(rows: List<EventDto>) = jsonOf(EventPageDto.serializer(), EventPageDto(rows, rows.size, 200, 0))

    private fun order(
        id: String,
        description: String,
    ) = CateringOrderDto(
        id = id,
        eventId = "e1",
        description = description,
        quantity = 3,
        allergenNotes = null,
        status = CateringOrderStatus.PLANNED,
        createdAt = LocalDateTime(2099, 1, 1, 0, 0),
        createdBy = "member-1",
    )

    private fun shift(
        id: String,
        description: String,
    ) = EventVolunteerShiftDto(
        id = id,
        eventId = "e1",
        description = description,
        startsAt = LocalDateTime(2099, 11, 1, 17, 0),
        endsAt = LocalDateTime(2099, 11, 1, 19, 0),
        neededCount = 2,
        status = EventVolunteerShiftStatus.ACTIVE,
        confirmedCount = 0,
        full = false,
    )

    // ---- Catering -------------------------------------------------------------------------------------------------------

    @Test
    fun catering_pickerLockedWhileOpen_namesItsEvent_savesAgainstThatEvent_andFoldsBack(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            val list = routeOf { rpcService<ICateringService>().listCateringOrders("e") }
            val create = routeOf { rpcService<ICateringService>().createCateringOrder(CateringOrderInput("e", "d", 1)) }
            val rows = mutableListOf(order("o1", "Altbestellung"))
            val hostile = "${KV_I18N_MARKER}Boese"
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == events -> request.answerWith(page(listOf(event("e1", hostile), event("e2", "Herbstfest"))))
                        request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(CateringOrderDto.serializer()), rows))
                        request.rpcRoute == create -> {
                            val added = order("o2", "Kuchen")
                            rows += added
                            request.answerWith(jsonOf(CateringOrderDto.serializer(), added))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-catering") { root, element ->
                    renderCateringScreen(root)
                    awaitUntil("the order list is shown") { element().shows("Altbestellung") }
                    val screen = element()
                    assertEquals(listOf("Neue Bestellposition"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neue Bestellposition anlegen"), "the old always-visible section title is gone")
                    assertFalse(screen.formOpen("lapis-create-catering-order"), "collapsed after the load")
                    assertFalse(screen.eventPicker().disabled)

                    val host = openForm(screen, "lapis-create-catering-order")
                    assertTrue(screen.eventPicker().disabled, "the picker is locked while the form is open")
                    assertTrue(host.shows("Für Veranstaltung:"))
                    assertTrue(host.shows("Boese"), "the title is shown as text")
                    assertFalse(screen.shows(KV_I18N_MARKER), "an injected catalog marker never reaches the DOM")

                    escape(host)
                    awaitUntil("closed without a question") { !screen.formOpen("lapis-create-catering-order") }
                    assertFalse(screen.eventPicker().disabled, "the picker is free again after closing")

                    val reopened = openForm(screen, "lapis-create-catering-order")
                    reopened.typeInto("Beschreibung", "Kuchen")
                    escape(reopened)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertTrue(screen.eventPicker().disabled, "still locked after 'keep editing'")
                    assertEquals("Kuchen", (reopened.controlOf("Beschreibung") as HTMLInputElement).value)

                    reopened.typeInto("Menge", "12")
                    screen.buttonNamed("Position anlegen").click()
                    awaitUntil("createCateringOrder was called") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.formOpen("lapis-create-catering-order") }
                    awaitUntil("the new order is listed") { screen.shows("Kuchen") }
                    val sent = calls.singleCall(create).rpcParam(0)
                    assertEquals("e1", sent.eventId as String, "saved against the event the form was opened for")
                    assertEquals("Kuchen", sent.description as String)
                    assertEquals(12, sent.quantity as Int)
                    assertFalse(screen.eventPicker().disabled, "the picker is free again after the save")
                    val button = createFormButton(screen, "lapis-create-catering-order")
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                }
            }
        }

    @Test
    fun catering_aConflictKeepsTheFormOpenAndLocked(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            val list = routeOf { rpcService<ICateringService>().listCateringOrders("e") }
            val create = routeOf { rpcService<ICateringService>().createCateringOrder(CateringOrderInput("e", "d", 1)) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == events -> request.answerWith(page(listOf(event("e1", "Sommerfest"))))
                        request.rpcRoute == list -> request.answerWith("[]")
                        request.rpcRoute == create -> serviceExceptionResult(request.json.id as Int, conflict)
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-catering-conflict") { root, element ->
                    renderCateringScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Bestellpositionen") }
                    val screen = element()
                    val host = openForm(screen, "lapis-create-catering-order")
                    host.typeInto("Beschreibung", "Doppelt")
                    host.typeInto("Menge", "2")
                    screen.buttonNamed("Position anlegen").click()
                    awaitUntil("the write was attempted") { calls.toRoute(create).size == 1 }
                    awaitUntil("the failed save is over") { !screen.buttonNamed("Position anlegen").hasAttribute("disabled") }
                    assertTrue(screen.formOpen("lapis-create-catering-order"), "a failed save never folds the form back")
                    assertEquals("Doppelt", (host.controlOf("Beschreibung") as HTMLInputElement).value)
                    assertTrue(screen.eventPicker().disabled)
                }
            }
        }

    @Test
    fun catering_withoutEvents_hasNoButton_andAnErrorShowsRetryThenExactlyOneButton(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            val list = routeOf { rpcService<ICateringService>().listCateringOrders("e") }
            var mode = "empty"
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == events ->
                            when (mode) {
                                "empty" -> request.answerWith(page(emptyList()))
                                "error" -> serviceExceptionResult(request.json.id as Int, conflict)
                                else -> request.answerWith(page(listOf(event("e1", "Sommerfest"))))
                            }
                        request.rpcRoute == list -> request.answerWith("[]")
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-catering-no-events") { root, element ->
                    renderCateringScreen(root)
                    awaitUntil("the empty text is shown") { element().shows("Es sind derzeit keine Veranstaltungen geplant.") }
                    assertTrue(element().actionButtons().isEmpty(), "no event, no button")
                }
                mode = "error"
                mountedForm("r36b-catering-events-error") { root, element ->
                    renderCateringScreen(root)
                    awaitUntil("the error state is shown") { element().shows("Die Daten konnten nicht geladen werden.") }
                    assertTrue(element().actionButtons().isEmpty(), "a failed event load gives no button")
                    mode = "ok"
                    element().buttonNamed("Erneut versuchen").click()
                    awaitUntil("the button appeared after the retry") { element().actionButtons().size == 1 }
                    awaitUntil("the order list settled") { element().shows("Noch keine Bestellpositionen") }
                    assertEquals(1, element().actionButtons().size, "a retry never builds a second button")
                }
            }
        }

    // ---- Volunteer shifts -----------------------------------------------------------------------------------------------

    @Test
    fun shifts_pickerLockedWhileOpen_namesItsEvent_savesAgainstThatEvent_andFoldsBack(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            val list = routeOf { rpcService<IEventVolunteerService>().listShifts("e") }
            val create =
                routeOf {
                    rpcService<IEventVolunteerService>().createShift(
                        EventVolunteerShiftInput("e", "d", LocalDateTime(2099, 1, 1, 0, 0), LocalDateTime(2099, 1, 1, 1, 0), 1),
                    )
                }
            val rows = mutableListOf(shift("s1", "Altschicht"))
            val hostile = "${KV_I18N_MARKER}Boese"
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == events -> request.answerWith(page(listOf(event("e1", hostile), event("e2", "Herbstfest"))))
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(EventVolunteerShiftDto.serializer()), rows))
                        request.rpcRoute == create -> {
                            val added = shift("s2", "Aufbau")
                            rows += added
                            request.answerWith(jsonOf(EventVolunteerShiftDto.serializer(), added))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-shifts") { root, element ->
                    renderEventVolunteerShiftsScreen(root)
                    awaitUntil("the shift list is shown") { element().shows("Altschicht") }
                    val screen = element()
                    assertEquals(listOf("Neue Schicht"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neue Schicht anlegen"), "the old always-visible section title is gone")
                    assertFalse(screen.formOpen("lapis-create-event-shift"), "collapsed after the load")

                    val host = openForm(screen, "lapis-create-event-shift")
                    assertTrue(screen.eventPicker().disabled, "the picker is locked while the form is open")
                    assertTrue(host.shows("Für Veranstaltung:"))
                    assertTrue(host.shows("Boese"))
                    assertFalse(screen.shows(KV_I18N_MARKER), "an injected catalog marker never reaches the DOM")

                    host.typeInto("Beschreibung", "Aufbau")
                    escape(host)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertTrue(screen.formOpen("lapis-create-event-shift"))

                    host.typeInto("Beginn", "2099-11-01T16:00")
                    host.typeInto("Ende", "2099-11-01T18:00")
                    host.typeInto("Benötigte Anzahl", "3")
                    screen.buttonNamed("Schicht anlegen").click()
                    awaitUntil("createShift was called") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.formOpen("lapis-create-event-shift") }
                    awaitUntil("the new shift is listed") { screen.shows("Aufbau") }
                    val sent = calls.singleCall(create).rpcParam(0)
                    assertEquals("e1", sent.eventId as String, "saved against the event the form was opened for")
                    assertEquals(3, sent.neededCount as Int)
                    assertFalse(screen.eventPicker().disabled, "the picker is free again after the save")
                    val button = createFormButton(screen, "lapis-create-event-shift")
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                }
            }
        }

    @Test
    fun shifts_withoutEvents_hasNoButton(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == events) request.answerWith(page(emptyList())) else request.answerWith("[]")
                },
            ) { calls ->
                mountedForm("r36b-shifts-no-events") { root, element ->
                    renderEventVolunteerShiftsScreen(root)
                    awaitUntil("the events were loaded") { calls.toRoute(events).isNotEmpty() }
                    settleAppScope()
                    assertTrue(element().actionButtons().isEmpty(), "no event, no button")
                }
            }
        }
}
