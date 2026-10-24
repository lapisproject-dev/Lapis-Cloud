package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationOverviewDto
import network.lapis.cloud.shared.domain.EventRegistrationPaymentState
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.204 -- the registrations section: counters, the filter (client side only), the table, the folded message as TEXT, the CSV link, the role gate. */
class EventRegistrationsSectionDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun reg(
        id: String,
        name: String,
        status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
        member: Boolean = true,
        email: String? = null,
        message: String? = null,
        wait: Int? = null,
        fee: Double = 0.0,
        pay: EventRegistrationPaymentState = EventRegistrationPaymentState.FREE,
    ) = EventRegistrationDto(
        id = id,
        eventId = "e1",
        memberId = if (member) "m-$id" else null,
        memberDisplayName = if (member) name else null,
        guestName = if (member) null else name,
        guestEmail = email,
        status = status,
        feeAmount = fee.toDecimal(),
        waitlistPosition = wait,
        registeredAt = LocalDateTime(2030, 1, 15, 10, 30),
        paymentTransactionId = null,
        journalEntryId = null,
        message = message,
        paymentState = pay,
    )

    private fun overview(
        rows: List<EventRegistrationDto>,
        capacity: Int? = 10,
        truncated: Boolean = false,
    ) = jsonOf(
        EventRegistrationOverviewDto.serializer(),
        EventRegistrationOverviewDto(
            eventId = "e1",
            capacity = capacity,
            activeCount = 2,
            waitlistedCount = 1,
            inactiveCount = 1,
            freeSeats = capacity?.let { it - 2 },
            withMessageCount = rows.count { it.message != null },
            rows = rows,
            truncated = truncated,
        ),
    )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.radios(): List<HTMLElement> = allOf("[role=radio]")

    private fun HTMLElement.radioNamed(text: String): HTMLElement =
        radios().firstOrNull { it.text().trim() == text } ?: error("no segment '$text' in ${radios().map { it.text().trim() }}")

    private fun withSection(
        id: String,
        role: AccountRole = AccountRole.BOARD,
        json: String,
        body: suspend (() -> HTMLElement, List<RecordedRequest>, String) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            val route = routeOf { rpcService<IEventService>().getRegistrationOverview("e1") }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc &&
                        request.rpcRoute == route
                    ) {
                        request.answerWith(json)
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm(id) { root, element ->
                    renderEventRegistrationsSection(root, "e1")
                    body(element, calls, route)
                }
            }
        }

    private val rows =
        listOf(
            reg("1", "Anna Muster", message = "<img src=x onerror=alert(1)>"),
            reg("2", "Gast Gerd", member = false, email = "gerd@example.org", status = EventRegistrationStatus.WAITLISTED, wait = 1),
            reg("3", "Bea Bezahlt", fee = 12.5, pay = EventRegistrationPaymentState.PAID),
            reg("4", "Carl Cancel", status = EventRegistrationStatus.CANCELLED),
        )

    @Test
    fun boardSeesTheCounters_theFilterWithFiveOptions_theTable_andTheGuestEmail(): Promise<Unit> =
        withSection("regs-board", json = overview(rows)) { el, _, _ ->
            awaitUntil("the table") { el().querySelector("table") != null }
            assertTrue(el().text().contains("Angemeldet 2 · Warteliste 1 · Abgesagt/abgelaufen 1 · Freie Plätze 8"))
            assertEquals(
                listOf("Alle", "Angemeldet", "Warteliste", "Abgesagt/abgelaufen", "Mit Nachricht"),
                el().radios().map { it.text().trim() },
            )
            assertTrue(el().text().contains("gerd@example.org"))
            assertTrue(el().text().contains("Gast Gerd"))
            assertEquals(4, el().allOf("tbody tr").count { it.getAttribute("id") == null })
        }

    @Test
    fun withoutACapacity_noFreeSeatsFigure(): Promise<Unit> =
        withSection("regs-nocap", json = overview(rows, capacity = null)) { el, _, _ ->
            awaitUntil("the table") { el().querySelector("table") != null }
            assertFalse(el().text().contains("Freie Plätze"))
        }

    @Test
    fun theFilter_hidesRowsOnTheClient_andSendsNoSecondRequest(): Promise<Unit> =
        withSection("regs-filter", json = overview(rows)) { el, calls, route ->
            awaitUntil("the table") { el().querySelector("table") != null }
            el().radioNamed("Warteliste").click()
            awaitUntil("only the waiting list") { el().text().contains("Gast Gerd") && !el().text().contains("Anna Muster") }
            el().radioNamed("Mit Nachricht").click()
            awaitUntil("only the row with a message") { el().text().contains("Anna Muster") && !el().text().contains("Gast Gerd") }
            el().radioNamed("Abgesagt/abgelaufen").click()
            awaitUntil("only the cancelled one") { el().text().contains("Carl Cancel") && !el().text().contains("Anna Muster") }
            assertEquals(1, calls.toRoute(route).size, "filtering never asks the server again")
        }

    @Test
    fun theMessage_isFoldedOut_byAnAccessibleButton_andShownAsText(): Promise<Unit> =
        withSection("regs-message", json = overview(rows)) { el, _, _ ->
            awaitUntil("the table") { el().querySelector("table") != null }
            val button = el().allOf("button").first { it.getAttribute("aria-label") == "Nachricht anzeigen: Anna Muster" }
            assertEquals("false", button.getAttribute("aria-expanded"))
            val target = button.getAttribute("aria-controls").orEmpty()
            assertTrue(target.isNotEmpty())
            val detail = el().querySelector("[id='$target']") as HTMLElement
            assertTrue(detail.classList.contains("d-none"))
            button.click()
            awaitUntil("expanded") { button.getAttribute("aria-expanded") == "true" }
            assertFalse(detail.classList.contains("d-none"))
            assertTrue(detail.text().contains("<img src=x onerror=alert(1)>"), "the markup is text")
            assertTrue(el().querySelector("table img") == null, "no element was created from the message")
            assertEquals(1, el().allOf("button").count { (it.getAttribute("aria-label") ?: "").startsWith("Nachricht anzeigen") })
            button.click()
            awaitUntil("folded again") { button.getAttribute("aria-expanded") == "false" }
            assertTrue(detail.classList.contains("d-none"))
        }

    @Test
    fun theCsvLink_pointsAtTheExportOfTheWholeList(): Promise<Unit> =
        withSection("regs-csv", json = overview(rows)) { el, _, _ ->
            awaitUntil("the table") { el().querySelector("table") != null }
            val link = el().allOf("a").first { it.text().trim() == "Alle Anmeldungen als CSV" }
            assertEquals("/api/events/e1/registrations.csv", link.getAttribute("href"))
            assertEquals("_blank", link.getAttribute("target"))
        }

    @Test
    fun anEmptyList_saysSo(): Promise<Unit> =
        withSection("regs-empty", json = overview(emptyList())) { el, _, _ ->
            awaitUntil("the empty text") { el().text().contains("Noch keine Anmeldungen.") }
        }

    @Test
    fun aTruncatedList_saysSo(): Promise<Unit> =
        withSection("regs-truncated", json = overview(rows, truncated = true)) { el, _, _ ->
            awaitUntil("the hint") { el().text().contains("Es werden nur die ersten 2000 Anmeldungen angezeigt. Die CSV enthält alle.") }
        }

    @Test
    fun aPlainMember_getsNoSection_andNeverAnOverviewRequest(): Promise<Unit> =
        withSection("regs-member", role = AccountRole.MEMBER, json = overview(rows)) { el, calls, route ->
            awaitAppScopeIdle("nothing is loading")
            assertFalse(el().text().contains("Anmeldungen"))
            assertEquals(0, calls.toRoute(route).size)
        }
}
