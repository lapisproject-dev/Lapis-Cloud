package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceConfigDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the two draft screens (travel expenses, volunteer allowance). Both had a self-built "button hides itself" pattern;
 * now they use the component. The create button lives in a slot of the page header that is shown only after a successful load WITHOUT an
 * open draft; the form host is never touched by a reload, so typed text survives a reload triggered from a report card.
 */
class RestGroupFormsDraftScreensDomTest {
    private val travelFormId = "travel-expense-create"
    private val volunteerFormId = "volunteer-allowance-create"

    private val member =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Amara Okafor",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val rates =
        TravelExpenseRatesDto(
            mileageRatePerKm = 0.3.toDecimal(),
            perDiemRate = 28.0.toDecimal(),
            expenseAccountConfigured = true,
            bankAccountConfigured = true,
        )

    private fun report(
        id: String,
        status: TravelExpenseReportStatus,
    ) = TravelExpenseReportDto(
        id = id,
        subjectMemberId = "member-1",
        subjectDisplayName = "Amara Okafor",
        status = status,
        purpose = "Delegiertenversammlung",
        travelFrom = LocalDate(2026, 3, 10),
        travelTo = LocalDate(2026, 3, 12),
        totalAmount = 0.0.toDecimal(),
        lines = emptyList(),
        createdAt = LocalDateTime(2026, 3, 1, 9, 0),
        requestedBy = "member-1",
        requestedByDisplayName = "Amara Okafor",
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private suspend fun travelStub(
        reports: () -> List<TravelExpenseReportDto>,
        onWithdraw: () -> Unit = {},
    ): (RecordedRequest) -> StubResponse {
        val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
        val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
        val withdrawRoute = routeOf { rpcService<ITravelExpenseService>().withdrawReport("x") }
        val createRoute =
            routeOf {
                rpcService<ITravelExpenseService>().createDraft(
                    "x",
                    TravelExpenseReportInput("x", LocalDate(2026, 1, 1), LocalDate(2026, 1, 1)),
                )
            }
        return { request ->
            when {
                !request.isRpc -> StubResponse()
                request.rpcRoute == ratesRoute -> request.answerWith(jsonOf(TravelExpenseRatesDto.serializer(), rates))
                request.rpcRoute == listRoute ->
                    request.answerWith(jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), reports()))
                request.rpcRoute == withdrawRoute -> {
                    onWithdraw()
                    request.answerWith(jsonOf(TravelExpenseReportDto.serializer(), report("r1", TravelExpenseReportStatus.WITHDRAWN)))
                }
                request.rpcRoute == createRoute ->
                    request.answerWith(jsonOf(TravelExpenseReportDto.serializer(), report("new", TravelExpenseReportStatus.DRAFT)))
                else -> request.answerWith("[]")
            }
        }
    }

    @Test
    fun travel_noDraft_oneButtonAfterTheLoad_collapsed_escapeClosesAtOnce(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val respond = travelStub(reports = { emptyList() })
            withFetchStub(respond = respond) {
                mountedForm("r49-travel-nodraft") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("the button is shown after the load") { element().createButtonShown(travelFormId) }
                    assertEquals(listOf("Neuer Reisekostenantrag"), element().createButtonLabels())
                    assertFalse(element().hostOpen(travelFormId), "collapsed")
                    val host = openCreateForm(element(), travelFormId)
                    assertTrue(host.shows("Zweck der Reise"))
                    pressEscape(host)
                    awaitUntil("closed without a question") { !element().hostOpen(travelFormId) }
                }
            }
        }

    @Test
    fun travel_withAnOpenDraft_theButtonIsNotShown_andTheEditorIs(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val respond = travelStub(reports = { listOf(report("d1", TravelExpenseReportStatus.DRAFT)) })
            withFetchStub(respond = respond) {
                mountedForm("r49-travel-draft") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("the draft editor") { element().shows("Zur Freigabe einreichen") }
                    assertFalse(element().createButtonShown(travelFormId), "one draft at a time: no create button")
                    assertFalse(element().hostOpen(travelFormId))
                }
            }
        }

    @Test
    fun travel_aReloadFromAReportCard_keepsTheTypedForm_andNeverDoublesTheButton(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            val respond = travelStub(reports = { listOf(report("r1", TravelExpenseReportStatus.REQUESTED)) })
            withFetchStub(respond = respond) { calls ->
                mountedForm("r49-travel-reload") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("the report card with its withdraw button") {
                        element().allOf("button").any { it.textContent?.trim() == "Zurückziehen" }
                    }
                    awaitUntil("the create button") { element().createButtonShown(travelFormId) }
                    val host = openCreateForm(element(), travelFormId)
                    host.typeInto("Zweck der Reise", "Tagung in Kassel")
                    val before = calls.toRoute(listRoute).size
                    element().buttonNamed("Zurückziehen").click()
                    awaitUntil("the list was reloaded") { calls.toRoute(listRoute).size > before }
                    delay(150)
                    assertTrue(element().hostOpen(travelFormId), "the form survives the reload")
                    assertEquals("Tagung in Kassel", (host.controlOf("Zweck der Reise") as HTMLInputElement).value)
                    assertEquals(1, element().createButtonLabels().size, "never a second button")
                }
            }
        }

    @Test
    fun travel_aDraftThatAppearsAfterAReload_closesAnUnchangedForm_andAsksForAChangedOne(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            var withdrawn = false
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            val respond =
                travelStub(
                    reports = {
                        if (withdrawn) {
                            listOf(report("d1", TravelExpenseReportStatus.DRAFT))
                        } else {
                            listOf(report("r1", TravelExpenseReportStatus.REQUESTED))
                        }
                    },
                    onWithdraw = { withdrawn = true },
                )
            withFetchStub(respond = respond) { calls ->
                mountedForm("r49-travel-appears") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("the create button") { element().createButtonShown(travelFormId) }
                    val host = openCreateForm(element(), travelFormId)
                    host.typeInto("Zweck der Reise", "Tagung")
                    element().buttonNamed("Zurückziehen").click()
                    awaitUntil("the dialog about the changed form") { kotlinx.browser.document.querySelector(".modal.show") != null }
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(element().hostOpen(travelFormId), "'Weiter bearbeiten' keeps the typed text")
                    assertFalse(element().createButtonShown(travelFormId), "the draft exists now: the slot is hidden")
                    assertTrue(calls.toRoute(listRoute).size >= 2)
                    assertTrue(element().shows("Zur Freigabe einreichen"), "the draft editor is shown")
                }
            }
        }

    @Test
    fun travel_aBlankSubmit_keepsTheFormOpen(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val respond = travelStub(reports = { emptyList() })
            withFetchStub(respond = respond) {
                mountedForm("r49-travel-blank") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("the button is shown after the load") { element().createButtonShown(travelFormId) }
                    val host = openCreateForm(element(), travelFormId)
                    host.buttonNamed("Entwurf anlegen").click()
                    awaitUntil("the fields report the missing values") { host.shownErrors().isNotEmpty() }
                    assertTrue(element().hostOpen(travelFormId))
                }
            }
        }

    @Test
    fun volunteer_noDraft_oneButtonAfterTheLoad_collapsed_typedFormAsks_blankSubmitStays(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val list = routeOf { rpcService<IVolunteerAllowanceService>().listMyPayments() }
            val config = routeOf { rpcService<IVolunteerAllowanceService>().getVolunteerAllowanceConfig() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), emptyList()))
                        request.rpcRoute == config ->
                            request.answerWith(
                                jsonOf(
                                    VolunteerAllowanceConfigDto.serializer(),
                                    VolunteerAllowanceConfigDto(instructorCap = 3000.0.toDecimal(), honoraryCap = 840.0.toDecimal()),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-volunteer") { root, element ->
                    renderVolunteerAllowanceScreen(root, null)
                    awaitUntil("the empty list") { element().shows("Noch keine eingereichten Zahlungen.") }
                    awaitUntil("the button is shown after the load") { element().createButtonShown(volunteerFormId) }
                    assertEquals(listOf("Neue Zahlung beantragen"), element().createButtonLabels())
                    assertFalse(element().hostOpen(volunteerFormId))
                    val host = openCreateForm(element(), volunteerFormId)
                    host.buttonNamed("Entwurf anlegen").click()
                    awaitUntil("the fields report the missing values") { host.shownErrors().isNotEmpty() }
                    assertTrue(element().hostOpen(volunteerFormId))
                    host.typeInto("Tätigkeitsbeschreibung", "Training der Jugendgruppe")
                    pressEscape(host)
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(element().hostOpen(volunteerFormId))
                    host.buttonNamed("Abbrechen").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("closed after discarding") { !element().hostOpen(volunteerFormId) }
                }
            }
        }
}
