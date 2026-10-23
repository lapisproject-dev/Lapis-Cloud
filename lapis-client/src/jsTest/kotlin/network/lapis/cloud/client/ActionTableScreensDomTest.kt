package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CateringOrderDto
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICateringService
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IEventVolunteerService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.194 -- Räume, Catering and Helfer-Schichten as TABLES with icon row actions (the pattern of Gremien, Sitzungen and Anträge): the
 * columns, the icons with the row's name as tooltip and accessible name, no text button left in a row, the confirmation dialog of the
 * destructive actions, the create form collapsed by default, and the edit form / roster opening above the table.
 */
class ActionTableScreensDomTest {
    private fun boardSession() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    /** One element per list row: a table row on a wide viewport, a card below the breakpoint -- the test does not depend on the window size. */
    private fun HTMLElement.tableRows(): List<HTMLElement> = allOf("tbody tr").ifEmpty { allOf(".lapis-data-card") }

    /** The column headers (table mode only; a card has no header row). */
    private fun HTMLElement.headers(): List<String>? = allOf("thead th").map { it.textContent?.trim().orEmpty() }.ifEmpty { null }

    private fun openModal(): HTMLElement? = document.querySelector(".modal.show") as? HTMLElement

    private fun assertIconOnly(
        buttons: List<HTMLElement>,
        labels: List<String>,
    ) {
        assertEquals(labels, buttons.map { it.getAttribute("aria-label") }, "accessible names carry the row's name")
        assertEquals(labels, buttons.map { it.getAttribute("title") }, "tooltips equal the accessible names")
        assertTrue(buttons.all { it.textContent?.trim().isNullOrEmpty() }, "icon only, no text button left in the row")
    }

    private fun event(id: String) =
        EventDto(
            id = id,
            slug = "slug-$id",
            title = "Sommerfest",
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

    private fun events() = jsonOf(EventPageDto.serializer(), EventPageDto(listOf(event("e1")), 1, 200, 0))

    // ---- Räume ------------------------------------------------------------------------------------------------------------

    @Test
    fun rooms_areATable_withIconActionsNamedAfterTheRoom_aConfirmedDeactivation_andACollapsedCreateForm(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventRoomService>().listRooms(includeInactive = true) }
            val deactivate = routeOf { rpcService<IEventRoomService>().deactivateRoom("x") }
            val active = EventRoomDto("r1", "Festsaal", 120, listOf("Beamer", "Mikrofon"), EventRoomStatus.ACTIVE)
            val inactive = EventRoomDto("r2", "Keller", null, emptyList(), EventRoomStatus.INACTIVE)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(
                                jsonOf(ListSerializer(EventRoomDto.serializer()), listOf(active, inactive)),
                            )
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("rooms-table") { root, element ->
                    renderEventRoomsScreen(root)
                    awaitUntil("the table") { element().tableRows().size == 2 }
                    element().headers()?.let { assertEquals(listOf("Name", "Status", "Kapazität", "Ausstattung", "Aktionen"), it) }
                    val rows = element().tableRows()
                    assertTrue(rows[0].shows("Festsaal") && rows[0].shows("120") && rows[0].shows("Beamer, Mikrofon"))
                    assertIconOnly(rows[0].allOf("button"), listOf("Bearbeiten: Festsaal", "Deaktivieren: Festsaal"))
                    assertIconOnly(rows[1].allOf("button"), listOf("Bearbeiten: Keller", "Aktivieren: Keller"))
                    assertTrue(element().allOf("[id='lapis-create-event-room'] label").isEmpty(), "the create form is collapsed by default")

                    // Bearbeiten opens the pre-filled form above the table; the table stays.
                    rows[0].allOf("button")[0].click()
                    awaitUntil("the edit form") { element().shows("Änderungen speichern") }
                    assertEquals(2, element().tableRows().size)
                    element().buttonNamed("Abbrechen").click()
                    awaitUntil("the edit form is gone") { !element().shows("Änderungen speichern") }

                    // Deaktivieren still asks first, and calls nothing before the answer.
                    val before = calls.count { it.isRpc && it.rpcRoute == deactivate }
                    rows[0].allOf("button")[1].click()
                    awaitUntil("the confirmation") { openModal()?.shows("wirklich deaktivieren") == true }
                    assertEquals(before, calls.count { it.isRpc && it.rpcRoute == deactivate }, "nothing happens before the confirmation")
                }
            }
        }

    // ---- Catering ---------------------------------------------------------------------------------------------------------

    @Test
    fun catering_isATable_withIconActionsNamedAfterThePosition_andAConfirmedDeletion(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            val list = routeOf { rpcService<ICateringService>().listCateringOrders("e") }
            val delete = routeOf { rpcService<ICateringService>().deleteCateringOrder("x") }
            val order =
                CateringOrderDto(
                    "o1",
                    "e1",
                    "Kuchen",
                    12,
                    "Nüsse",
                    CateringOrderStatus.PLANNED,
                    LocalDateTime(2099, 1, 1, 0, 0),
                    "member-1",
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == events -> request.answerWith(events())
                        request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(CateringOrderDto.serializer()), listOf(order)))
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("catering-table") { root, element ->
                    renderCateringScreen(root)
                    awaitUntil("the table") { element().tableRows().size == 1 }
                    element().headers()?.let {
                        assertEquals(
                            listOf("Beschreibung", "Menge", "Allergene/Hinweise", "Status", "Aktionen"),
                            it,
                        )
                    }
                    val row = element().tableRows().single()
                    assertTrue(row.shows("Kuchen") && row.shows("12") && row.shows("Nüsse"))
                    assertIconOnly(row.allOf("button"), listOf("Bearbeiten: Kuchen", "Löschen: Kuchen"))
                    assertNotNull(row.querySelector("select"), "the status stays a select in its column")
                    assertEquals("Status: Kuchen", row.querySelector("select")?.getAttribute("aria-label"))
                    assertTrue(
                        element().allOf("[id='lapis-create-catering-order'] label").isEmpty(),
                        "the create form is collapsed by default",
                    )

                    row.allOf("button")[1].click()
                    awaitUntil("the confirmation") { openModal()?.shows("endgültig löschen") == true }
                    assertEquals(0, calls.count { it.isRpc && it.rpcRoute == delete }, "nothing is deleted before the confirmation")
                }
            }
        }

    // ---- Helfer-Schichten -------------------------------------------------------------------------------------------------

    @Test
    fun shifts_areATable_withIconActions_aRosterAboveTheTable_andAConfirmedCancellation(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val events = routeOf { rpcService<IEventService>().listEvents() }
            val list = routeOf { rpcService<IEventVolunteerService>().listShifts("e") }
            val cancel = routeOf { rpcService<IEventVolunteerService>().cancelShift("x") }
            val active =
                EventVolunteerShiftDto(
                    "s1",
                    "e1",
                    "Aufbau",
                    LocalDateTime(2099, 11, 1, 17, 0),
                    LocalDateTime(2099, 11, 1, 19, 0),
                    3,
                    EventVolunteerShiftStatus.ACTIVE,
                    1,
                    false,
                )
            val cancelled = active.copy(id = "s2", description = "Abbau", status = EventVolunteerShiftStatus.CANCELLED)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == events -> request.answerWith(events())
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(EventVolunteerShiftDto.serializer()), listOf(active, cancelled)))
                        else -> request.answerWith("""{"shift":null,"signups":[]}""")
                    }
                },
            ) { calls ->
                mountedForm("shifts-table") { root, element ->
                    renderEventVolunteerShiftsScreen(root)
                    awaitUntil("a row") { element().tableRows().isNotEmpty() }
                    assertEquals(2, element().tableRows().size, "rows: ${element().textContent}")
                    element().headers()?.let { assertEquals(listOf("Beschreibung", "Zeitraum", "Besetzung", "Status", "Aktionen"), it) }
                    val rows = element().tableRows()
                    assertTrue(rows[0].shows("Aufbau") && rows[0].shows("1 von 3"))
                    assertIconOnly(
                        rows[0].allOf("button"),
                        listOf("Bearbeiten: Aufbau", "Stornieren: Aufbau", "Zusagen anzeigen: Aufbau"),
                    )
                    // A cancelled shift offers neither edit nor cancel, only its roster.
                    assertIconOnly(rows[1].allOf("button"), listOf("Zusagen anzeigen: Abbau"))
                    assertTrue(
                        element().allOf("[id='lapis-create-event-shift'] label").isEmpty(),
                        "the create form is collapsed by default",
                    )

                    rows[0].allOf("button")[1].click()
                    awaitUntil("the confirmation") { openModal()?.shows("wirklich stornieren") == true }
                    assertEquals(0, calls.count { it.isRpc && it.rpcRoute == cancel }, "nothing is cancelled before the confirmation")
                    assertFalse(element().shows("Zusagen: Aufbau"))
                }
            }
        }
}
