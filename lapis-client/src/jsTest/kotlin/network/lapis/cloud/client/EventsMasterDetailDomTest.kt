package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.204 -- the events list as a table: caption and headings, the title link to the detail page, occupancy, status, icon-only row actions. */
class EventsMasterDetailDomTest {
    private fun boardSession() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun event(
        id: String,
        title: String,
        status: EventStatus = EventStatus.PUBLISHED,
        capacity: Int? = null,
        occupied: Int = 0,
        waitlist: Int = 0,
        location: String? = "Halle",
        seriesId: String? = null,
    ) = EventDto(
        id = id,
        slug = "slug-$id",
        title = title,
        description = "Beschreibung",
        locationText = location,
        onlineUrl = null,
        startsAt = LocalDateTime(2099, 11, 1, 18, 0),
        endsAt = LocalDateTime(2099, 11, 1, 20, 0),
        capacity = capacity,
        feeAmount = 0.0.toDecimal(),
        feeCurrency = "EUR",
        status = status,
        visibility = EventVisibility.MEMBERS_ONLY,
        registrationClosesAt = null,
        occupiedSeats = occupied,
        waitlistCount = waitlist,
        full = false,
        feeEditable = true,
        ownRegistrationStatus = null,
        publicUrl = null,
        seriesId = seriesId,
    )

    private fun page(rows: List<EventDto>) = jsonOf(EventPageDto.serializer(), EventPageDto(rows, rows.size, 200, 0))

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.rows(): List<HTMLElement> = allOf("tbody tr")

    private fun HTMLElement.labels(): List<String> = allOf("button").mapNotNull { it.getAttribute("aria-label") }

    private fun withList(
        id: String,
        rows: List<EventDto>,
        body: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(rows)) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm(id) { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the table is shown") { element().rows().size == rows.size }
                    body(element)
                }
            }
        }

    @Test
    fun theTable_hasACaptionAndTheSixHeadings_andTheTitleLinksToTheDetailPage(): Promise<Unit> =
        withList("ev-md-table", listOf(event("a/1", "Sommerfest", capacity = 40, occupied = 23, waitlist = 2))) { el ->
            val table = el().querySelector("table") as HTMLElement
            assertEquals("Veranstaltungen", table.querySelector("caption")?.textContent?.trim())
            assertEquals(
                listOf("Titel", "Datum", "Ort", "Belegung", "Status", "Aktionen"),
                table.allOf("thead th").map { it.text().trim() },
            )
            val link = table.querySelector("tbody a") as HTMLElement
            assertEquals("Sommerfest", link.text().trim())
            assertEquals("#/events/a%2F1", link.getAttribute("href"), "the id is URL-encoded")
            assertTrue(table.text().contains("23/40"))
            assertTrue(table.text().contains("Veröffentlicht"))
            assertTrue(table.text().contains("Halle"))
        }

    @Test
    fun withoutACapacity_theOccupancyIsADash(): Promise<Unit> =
        withList("ev-md-nocap", listOf(event("1", "Offen", occupied = 5))) { el ->
            val cells = el().rows().single().allOf("td")
            assertEquals("–", cells[2].text().trim())
        }

    @Test
    fun theRowActions_areIconOnly_nameTheEvent_andFollowTheStatus(): Promise<Unit> =
        withList(
            "ev-md-actions",
            listOf(
                event("d", "Entwurf", status = EventStatus.DRAFT),
                event("p", "Laufend", waitlist = 2),
                event("c", "Abgesagt", status = EventStatus.CANCELLED),
            ),
        ) { el ->
            val rows = el().rows()
            val draft = rows[0].labels()
            val published = rows[1].labels()
            val cancelled = rows[2].labels()
            assertEquals(listOf("Veröffentlichen: Entwurf", "Absagen: Entwurf"), draft)
            assertEquals(listOf("Absagen: Laufend", "Warteliste nachrücken: Laufend"), published)
            assertEquals(emptyList(), cancelled)
            assertTrue(
                rows[0].allOf("button").all {
                    it.textContent
                        .orEmpty()
                        .trim()
                        .isEmpty()
                },
                "icon-only: no visible text",
            )
            assertFalse(el().text().contains("Bearbeiten"), "editing is on the detail page")
        }

    @Test
    fun aTitleWithMarkup_isShownAsText_andACancelledSeriesMemberKeepsItsMarker(): Promise<Unit> =
        withList("ev-md-evil", listOf(event("1", "<img src=x onerror=alert(1)>", seriesId = "s1"))) { el ->
            assertTrue(el().querySelector("tbody img") == null, "no element was created from the title")
            assertTrue(
                el()
                    .rows()
                    .single()
                    .text()
                    .contains("<img src=x onerror=alert(1)>"),
            )
            assertTrue(
                el()
                    .rows()
                    .single()
                    .text()
                    .contains("↻"),
            )
        }

    @Test
    fun theCreateForm_staysCollapsed_andAnEmptyListSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<IEventService>().listEvents() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith(page(emptyList())) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("ev-md-empty") { root, element ->
                    renderEventsScreen(root)
                    awaitUntil("the empty text") { element().text().contains("Noch keine Veranstaltungen.") }
                    assertEquals(0, element().querySelector("[id='lapis-create-event']")?.childElementCount ?: 0)
                    assertTrue(element().querySelector("table") == null)
                }
            }
        }

    @Test
    fun togglingThePastFilterTwiceInARow_neverDuplicatesRows(): Promise<Unit> =
        withList("ev-md-twice", listOf(event("1", "Eins"), event("2", "Zwei"))) { el ->
            val check = el().controlOf("Vergangene anzeigen") as HTMLInputElement
            check.click()
            check.click()
            awaitAppScopeIdle("both reloads are done")
            assertEquals(2, el().rows().size)
        }
}
