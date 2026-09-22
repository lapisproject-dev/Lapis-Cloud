package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CateringOrderDto
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICateringService
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IEventVolunteerService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.4.31 audit fixes M1 (empty state that claims a fact), M2 (a failed settings load reported as "disabled") and M3 (the cleaned
 * amount on screen vs. the raw one behind it), each driven through the real screen / dialog in a mounted root against a stubbed
 * `window.fetch`.
 */
class AuditFixesM1M2M3DomTest {
    @AfterTest
    fun reset() {
        AppState.setSession(null)
    }

    private fun session(role: AccountRole = AccountRole.ADMIN) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Testperson",
            role = role,
            expiresAt = LocalDateTime(2026, 9, 8, 12, 0),
        )

    private fun HTMLElement.hasErrorBox() = querySelector(".alert-danger[role=alert]") != null

    private fun HTMLElement.retry(): HTMLElement =
        assertNotNull(allOf("button").firstOrNull { it.textContent?.trim() == "Erneut versuchen" }, "no retry button")

    /** Answers by route: [answers] maps a route to its JSON (or `null` for a dropped connection); every other RPC gets `null`. */
    private fun answering(vararg answers: Pair<String, () -> String?>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request ->
            when {
                !request.isRpc -> StubResponse()
                request.rpcRoute in byRoute ->
                    byRoute.getValue(request.rpcRoute)()?.let { request.answerWith(it) } ?: StubResponse(networkError = true)
                else -> rpcResult(request.json.id as Int, "null")
            }
        }
    }

    private fun event(id: String = "e1") =
        EventDto(
            id = id,
            slug = "sommerfest",
            title = "Sommerfest",
            description = "",
            locationText = null,
            onlineUrl = null,
            startsAt = LocalDateTime(2026, 9, 30, 10, 0),
            endsAt = LocalDateTime(2026, 9, 30, 18, 0),
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

    private fun eventPage(vararg events: EventDto) =
        jsonOf(EventPageDto.serializer(), EventPageDto(rows = events.toList(), totalCount = events.size, limit = 200, offset = 0))

    private fun shift() =
        EventVolunteerShiftDto(
            id = "s1",
            eventId = "e1",
            description = "Aufbau Bühne",
            startsAt = LocalDateTime(2026, 9, 30, 8, 0),
            endsAt = LocalDateTime(2026, 9, 30, 10, 0),
            neededCount = 3,
            status = EventVolunteerShiftStatus.ACTIVE,
            confirmedCount = 1,
            full = false,
        )

    private fun order() =
        CateringOrderDto(
            id = "c1",
            eventId = "e1",
            description = "Vegetarisches Buffet",
            quantity = 40,
            allergenNotes = null,
            status = CateringOrderStatus.PLANNED,
            createdAt = LocalDateTime(2026, 9, 1, 10, 0),
            createdBy = "member-1",
        )

    private val quietSentences =
        listOf("keine Schichten offen", "Noch keine Bestellpositionen")

    // ---- M1 ------------------------------------------------------------------------------------------------------------------

    private class ScreenUnderTest(
        val id: String,
        val render: (io.kvision.panel.Root) -> Unit,
        val listRoute: suspend () -> String,
        val contentJson: () -> String,
        val contentText: String,
        val emptyText: String,
    )

    private fun screens(): List<ScreenUnderTest> =
        listOf(
            ScreenUnderTest(
                id = "m1-shifts",
                render = { renderMyVolunteerShiftsScreen(it) },
                listRoute = { routeOf { rpcService<IEventVolunteerService>().listShifts("x") } },
                contentJson = { jsonOf(ListSerializer(EventVolunteerShiftDto.serializer()), listOf(shift())) },
                contentText = "Aufbau Bühne",
                emptyText = "Für diese Veranstaltung sind aktuell keine Schichten offen.",
            ),
            ScreenUnderTest(
                id = "m1-catering",
                render = { renderCateringScreen(it) },
                listRoute = { routeOf { rpcService<ICateringService>().listCateringOrders("x") } },
                contentJson = { jsonOf(ListSerializer(CateringOrderDto.serializer()), listOf(order())) },
                contentText = "Vegetarisches Buffet",
                emptyText = "Noch keine Bestellpositionen für diese Veranstaltung.",
            ),
        )

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    @Test
    fun noEvents_saysSo_andNeverClaimsThatNoShiftsOrOrdersExist_andAsksNoListRpc(): Promise<Unit> =
        test {
            AppState.setSession(session())
            val eventsRoute = routeOf { rpcService<IEventService>().listEvents(EventQuery(limit = 1)) }
            screens().forEach { screen ->
                val listRoute = screen.listRoute()
                withFetchStub(answering(eventsRoute to { eventPage() })) { calls ->
                    mountedForm(screen.id) { root, element ->
                        screen.render(root)
                        awaitUntil("${screen.id}: the no-events sentence") {
                            element().textContent.orEmpty().contains("Es sind derzeit keine Veranstaltungen geplant.")
                        }
                        delay(100)
                        val text = element().textContent.orEmpty()
                        quietSentences.forEach { assertTrue(!text.contains(it), "${screen.id}: must not claim '$it' without an event") }
                        assertTrue(!element().hasErrorBox(), "${screen.id}: an empty event list is not an error")
                        assertEquals(0, calls.toRoute(listRoute).size, "${screen.id}: no event chosen -> no list request")
                    }
                }
            }
        }

    @Test
    fun aFailedEventList_isTheErrorBox_notTheQuietSentence_andItsRetryReloads(): Promise<Unit> =
        test {
            AppState.setSession(session())
            val eventsRoute = routeOf { rpcService<IEventService>().listEvents(EventQuery(limit = 1)) }
            screens().forEach { screen ->
                val listRoute = screen.listRoute()
                var failing = true
                withFetchStub(answering(eventsRoute to { if (failing) null else eventPage(event()) }, listRoute to { "[]" })) { calls ->
                    mountedForm(screen.id) { root, element ->
                        screen.render(root)
                        awaitUntil("${screen.id}: error state") { element().hasErrorBox() }
                        val text = element().textContent.orEmpty()
                        quietSentences.forEach { assertTrue(!text.contains(it), "${screen.id}: an error is not '$it'") }
                        assertTrue(!text.contains("keine Veranstaltungen geplant"), "${screen.id}: a failure is not 'no events'")
                        assertEquals(0, calls.toRoute(listRoute).size)
                        failing = false
                        element().retry().click()
                        awaitUntil("${screen.id}: the retry loads the list of the event") { calls.toRoute(listRoute).isNotEmpty() }
                    }
                }
            }
        }

    @Test
    fun withAnEvent_theContentAndTheRealEmptyStateStillWork(): Promise<Unit> =
        test {
            AppState.setSession(session())
            val eventsRoute = routeOf { rpcService<IEventService>().listEvents(EventQuery(limit = 1)) }
            screens().forEach { screen ->
                val listRoute = screen.listRoute()
                withFetchStub(answering(eventsRoute to { eventPage(event()) }, listRoute to { screen.contentJson() })) { calls ->
                    mountedForm(screen.id + "-content") { root, element ->
                        screen.render(root)
                        awaitUntil("${screen.id}: the content row") { element().textContent.orEmpty().contains(screen.contentText) }
                        assertTrue(calls.toRoute(listRoute).isNotEmpty(), "the list of the chosen event was requested")
                        assertTrue(!element().textContent.orEmpty().contains(screen.emptyText))
                    }
                }
                withFetchStub(answering(eventsRoute to { eventPage(event()) }, listRoute to { "[]" })) { _ ->
                    mountedForm(screen.id + "-empty") { root, element ->
                        screen.render(root)
                        awaitUntil("${screen.id}: the real empty state of a real event") {
                            element().textContent.orEmpty().contains(screen.emptyText)
                        }
                    }
                }
            }
        }

    // ---- M2 ------------------------------------------------------------------------------------------------------------------

    private fun settings(postalMailEnabled: Boolean) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto(
                id = "o1",
                name = "Verein",
                street = null,
                postalCode = null,
                city = null,
                country = null,
                bankIban = null,
                bankBic = null,
                taxExemptionAuthority = null,
                taxExemptionDate = null,
                postalMailEnabled = postalMailEnabled,
            ),
        )

    @Test
    fun postalMailGate_threeStates_unknownIsAnErrorBoxWithRetry_neverDisabled(): Promise<Unit> =
        test {
            val route = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            var state: String? = null // null = the load fails
            withFetchStub(answering(route to { state })) { calls ->
                mountedForm("m2-gate") { root, element ->
                    var enabledRendered = 0
                    root.renderPostalMailGate { host ->
                        enabledRendered++
                        host.add(io.kvision.html.Button("Per Post versenden"))
                    }
                    awaitUntil("unknown -> error box") { element().hasErrorBox() }
                    val text = element().textContent.orEmpty()
                    assertTrue(!text.contains("deaktiviert"), "a failed load must not read 'deaktiviert': $text")
                    assertEquals(0, element().allOf("button").count { it.textContent?.trim() == "Per Post versenden" })
                    state = settings(postalMailEnabled = false)
                    element().retry().click()
                    awaitUntil(
                        "false -> the disabled notice",
                    ) { element().textContent.orEmpty().contains("Postversand ist derzeit deaktiviert") }
                    assertTrue(!element().hasErrorBox())
                    assertEquals(0, enabledRendered)
                    assertEquals(2, calls.toRoute(route).size, "the retry asked exactly once more")
                }
                state = settings(postalMailEnabled = true)
                mountedForm("m2-gate-on") { root, element ->
                    root.renderPostalMailGate { host -> host.add(io.kvision.html.Button("Per Post versenden")) }
                    awaitUntil("true -> the dispatch button") {
                        element().allOf("button").any { it.textContent?.trim() == "Per Post versenden" }
                    }
                    assertTrue(!element().textContent.orEmpty().contains("deaktiviert"))
                }
            }
        }

    @Test
    fun postalMailScreenBanner_failedSettingsIsNotTheYellowDisabledBand(): Promise<Unit> =
        test {
            val route = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            var state: String? = null
            withFetchStub(answering(route to { state })) { _ ->
                mountedForm("m2-banner") { root, element ->
                    renderPostalMailScreen(root)
                    awaitUntil("error box") { element().hasErrorBox() }
                    assertTrue(!element().textContent.orEmpty().contains("Postversand ist derzeit deaktiviert"))
                    assertEquals(0, element().allOf(".alert-warning").size, "no yellow 'disabled' band on a failure")
                    state = settings(postalMailEnabled = false)
                    element().retry().click()
                    awaitUntil("the band after the retry") { element().querySelector(".alert-warning") != null }
                }
            }
        }

    @Test
    fun orgWideContributions_unknownPostalStateIsTheErrorBox_notAListWithoutDispatchButtons(): Promise<Unit> =
        test {
            AppState.setSession(session())
            val settingsRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val listRoute = routeOf { rpcService<IContributionService>().listContributions(status = ContributionStatus.OPEN) }
            val contribution =
                ContributionDto(
                    id = "c1",
                    memberId = "m1",
                    memberDisplayName = "Erika Muster",
                    membershipTierId = "t1",
                    membershipTierName = "Standard",
                    periodStart = LocalDate(2026, 1, 1),
                    periodEnd = LocalDate(2026, 12, 31),
                    amountDue = 60.0.toDecimal(),
                    status = ContributionStatus.OPEN,
                    paidAt = null,
                    paidAmount = null,
                    note = null,
                    createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                    dueDate = LocalDate(2026, 1, 31),
                )
            var settingsState: String? = null
            withFetchStub(
                answering(
                    settingsRoute to { settingsState },
                    listRoute to { jsonOf(ListSerializer(ContributionDto.serializer()), listOf(contribution)) },
                ),
            ) { calls ->
                mountedForm("m2-contributions") { root, element ->
                    renderContributionsScreen(root)
                    // the other sections of the screen (stubbed with `null`) show their own error boxes too: wait for the settings call
                    awaitUntil("the org-wide list failed on the unknown postal state") { calls.toRoute(settingsRoute).isNotEmpty() }
                    delay(200)
                    assertTrue(!element().textContent.orEmpty().contains("Erika Muster"), "no half-known list")
                    assertTrue(!element().textContent.orEmpty().contains("Postversand ist derzeit deaktiviert"))
                    assertEquals(0, calls.toRoute(listRoute).size, "no contributions request while the postal state is unknown")
                    settingsState = settings(postalMailEnabled = true)
                    element().allOf("button").filter { it.textContent?.trim() == "Erneut versuchen" }.forEach { it.click() }
                    awaitUntil("the list with its dispatch button after the retry") {
                        element().textContent.orEmpty().contains("Erika Muster") &&
                            element().allOf("button").any { it.textContent?.trim() == "Per Post versenden" }
                    }
                }
            }
        }

    // ---- M3 ------------------------------------------------------------------------------------------------------------------

    private val accounts =
        listOf(
            LedgerAccountDto(
                id = "a1",
                accountNumber = "1200",
                name = "Bank",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                active = true,
            ),
            LedgerAccountDto(
                id = "a2",
                accountNumber = "4200",
                name = "Spenden",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                active = true,
            ),
        )

    @Test
    fun settlementDialog_aFloatingPointOpenAmount_isShownPrefilledAndCheckedAsTheSameCleanAmount(): Promise<Unit> =
        test {
            withFetchStub { _ ->
                mountedForm("m3-settle") { _, _ ->
                    val item =
                        OpenItemDto(
                            id = "oi1",
                            direction = OpenItemDirection.RECEIVABLE,
                            counterpartyName = "Muster GmbH",
                            counterpartyKey = "muster",
                            itemDate = LocalDate(2026, 1, 1),
                            dueDate = LocalDate(2026, 2, 1),
                            amount = 100.0.toDecimal(),
                            openAmount = 99.99999999999999.toDecimal(),
                            contraAccountId = "a2",
                            contraAccountNumber = "4200",
                            contraAccountName = "Spenden",
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            status = OpenItemStatus.OPEN,
                            daysOverdue = 0,
                            asOf = LocalDate(2026, 1, 1),
                            createdByMemberId = "m1",
                            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                        )
                    openItemSettlementDialog(
                        item = item,
                        accounts = { accounts },
                        paymentMapping = { PaymentAccountMapping(defaultBankAccountId = "a1") },
                        knownSettlementIds = null,
                        onDone = {},
                    )
                    val modal = lastOpenModal()
                    assertTrue(modal.textContent.orEmpty().contains("Offen: 100,00$NBSP€"), "the headline shows the cleaned amount")
                    assertEquals(
                        "100",
                        (modal.controlOf("Betrag in EUR") as HTMLInputElement).value,
                        "the prefill is the SAME cleaned amount",
                    )
                    modal.typeInto("Betrag in EUR", "100,00")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    assertTrue(
                        modal.shownErrors().none { it.contains("offenen Betrag") },
                        "entering the amount the screen shows must be accepted: ${modal.shownErrors()}",
                    )
                    modal.typeInto("Betrag in EUR", "100,01")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    assertTrue(
                        modal.shownErrors().any { it.contains("offenen Betrag (100,00$NBSP€)") },
                        "a cent more is still rejected: ${modal.shownErrors()}",
                    )
                }
            }
        }
}
