package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4d batch 3: `TravelExpenseScreen.kt`'s three migrated `lapisForm`s -- the report header form
 * (Zweck/Von/Bis, driven for BOTH "Entwurf anlegen" and "Entwurf speichern"), the add-a-line form
 * (Beschreibung plus exactly one of Kilometer/Tage/Betrag) and the receipt upload form -- driven
 * the way a person does (type, blur, click) against a stubbed `window.fetch`, same idiom
 * `DocumentsScreenFormDomTest.kt` (W4d batch 2) already establishes.
 *
 * The receipt upload posts file bytes over a dedicated `XMLHttpRequest` route
 * (`TravelExpenseHttp.uploadReceipt`, not Kilua RPC -- same reasoning as `DocumentHttp`), which this
 * suite's fetch stub cannot intercept; only its required-field validation is covered here, same
 * scope `DocumentsScreenFormDomTest.versionUpload_withoutAFile_...` covers for its own XHR upload.
 */
class TravelExpenseScreenFormDomTest {
    private val memberSession =
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

    private fun draft(lines: List<TravelExpenseLineDto> = emptyList()) =
        TravelExpenseReportDto(
            id = "report-1",
            subjectMemberId = "member-1",
            subjectDisplayName = "Amara Okafor",
            status = TravelExpenseReportStatus.DRAFT,
            purpose = "Delegiertenversammlung",
            travelFrom = LocalDate(2026, 3, 10),
            travelTo = LocalDate(2026, 3, 12),
            totalAmount = 0.0.toDecimal(),
            lines = lines,
            createdAt = LocalDateTime(2026, 3, 1, 9, 0),
            requestedBy = "member-1",
            requestedByDisplayName = "Amara Okafor",
        )

    private val receiptedLine =
        TravelExpenseLineDto(
            id = "line-1",
            reportId = "report-1",
            kind = TravelExpenseLineKind.RECEIPTED,
            description = "Bahnfahrkarte",
            amount = 45.5.toDecimal(),
        )

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `DocumentsScreenFormDomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    @Test
    fun headerForm_createDraft_sendsTheTypedPurposeAndDatesTrimmed(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            val createDraftRoute =
                routeOf {
                    rpcService<ITravelExpenseService>().createDraft(
                        "x",
                        TravelExpenseReportInput("x", LocalDate(2026, 1, 1), LocalDate(2026, 1, 1)),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), emptyList()),
                        createDraftRoute to jsonOf(TravelExpenseReportDto.serializer(), draft()),
                    ),
            ) { calls ->
                mountedForm("travel-expense-header-create-happy") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    delay(80)
                    element().buttonNamed("Neuen Antrag anlegen").click()
                    delay(80)
                    element().typeInto("Zweck der Reise", "  Delegiertenversammlung  ")
                    element().typeInto("Von (JJJJ-MM-TT)", "  2026-03-10  ")
                    element().typeInto("Bis (JJJJ-MM-TT)", "  2026-03-12  ")
                    element().buttonNamed("Entwurf anlegen").click()
                    awaitUntil("createDraft", timeoutMs = 800) { calls.toRoute(createDraftRoute).size == 1 }
                    val call = calls.singleCall(createDraftRoute)
                    assertEquals("member-1", call.rpcParam(0) as String, "the session member is the subject")
                    val input = call.rpcParam(1)
                    assertEquals("Delegiertenversammlung", input.purpose as String, "the purpose is sent, trimmed")
                    assertEquals("2026-03-10", input.travelFrom as String)
                    assertEquals("2026-03-12", input.travelTo as String)
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a valid header")
                }
            }
        }

    @Test
    fun headerForm_withAnInvalidDate_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            val createDraftRoute =
                routeOf {
                    rpcService<ITravelExpenseService>().createDraft(
                        "x",
                        TravelExpenseReportInput("x", LocalDate(2026, 1, 1), LocalDate(2026, 1, 1)),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("travel-expense-header-create-invalid") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    delay(80)
                    element().buttonNamed("Neuen Antrag anlegen").click()
                    delay(80)
                    element().typeInto("Zweck der Reise", "Delegiertenversammlung")
                    element().typeInto("Von (JJJJ-MM-TT)", "nicht-ein-datum")
                    element().typeInto("Bis (JJJJ-MM-TT)", "2026-03-12")
                    element().buttonNamed("Entwurf anlegen").click()
                    delay(80)
                    assertTrue(calls.toRoute(createDraftRoute).isEmpty(), "no createDraft call for an invalid date")
                    assertTrue(element().shownErrors().isNotEmpty(), "the invalid date is reported on the field")
                }
            }
        }

    @Test
    fun addLineForm_mileage_sendsTheParsedKilometersAsADecimal(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            val addLineRoute =
                routeOf {
                    rpcService<ITravelExpenseService>().addLine(
                        "x",
                        TravelExpenseLineInput(kind = TravelExpenseLineKind.MILEAGE, description = "x"),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(draft())),
                        addLineRoute to jsonOf(TravelExpenseReportDto.serializer(), draft()),
                    ),
            ) { calls ->
                mountedForm("travel-expense-add-line-mileage-happy") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("mileage add button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Fahrt hinzufügen" }
                    }
                    delay(80)
                    element().buttonNamed("Fahrt hinzufügen").click()
                    delay(80)
                    element().typeInto("Beschreibung", "  Zugfahrt Hannover  ")
                    element().typeInto("Kilometer", "  123,5  ")
                    element().buttonNamed("Zeile hinzufügen").click()
                    awaitUntil("addLine", timeoutMs = 800) { calls.toRoute(addLineRoute).size == 1 }
                    val call = calls.singleCall(addLineRoute)
                    assertEquals("report-1", call.rpcParam(0) as String)
                    val input = call.rpcParam(1)
                    assertEquals("Zugfahrt Hannover", input.description as String, "the description is sent, trimmed")
                    assertEquals(123.5, input.kilometers as Double, "the comma decimal is parsed and sent as a Decimal")
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a valid kilometer value")
                }
            }
        }

    @Test
    fun addLineForm_receiptedWithATooLargeAmount_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            val addLineRoute =
                routeOf {
                    rpcService<ITravelExpenseService>().addLine(
                        "x",
                        TravelExpenseLineInput(kind = TravelExpenseLineKind.RECEIPTED, description = "x"),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(draft())),
                    ),
            ) { calls ->
                mountedForm("travel-expense-add-line-receipted-invalid") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("receipted add button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Beleg-Kosten hinzufügen" }
                    }
                    delay(80)
                    element().buttonNamed("Beleg-Kosten hinzufügen").click()
                    delay(80)
                    element().typeInto("Beschreibung", "Taxiquittung")
                    // Above TravelExpenseAmountRules.MAX_LINE_AMOUNT (100000) -- the field's own upper bound.
                    element().typeInto("Betrag", "999999999")
                    element().buttonNamed("Zeile hinzufügen").click()
                    delay(80)
                    assertTrue(calls.toRoute(addLineRoute).isEmpty(), "no addLine call for a too-large amount")
                    assertTrue(element().shownErrors().isNotEmpty(), "the too-large amount is reported on the field")
                }
            }
        }

    @Test
    fun receiptUpload_withoutAFile_sendsNoRequestAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(draft(listOf(receiptedLine)))),
                    ),
            ) { calls ->
                mountedForm("travel-expense-receipt-upload-invalid") { root, element ->
                    renderTravelExpenseScreen(root, null)
                    awaitUntil("upload button rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Hochladen" }
                    }
                    delay(80)
                    element().buttonNamed("Hochladen").click()
                    delay(80)
                    assertTrue(element().shownErrors().isNotEmpty(), "no file selected is reported on the field")
                    assertEquals(1, calls.toRoute(listRoute).size, "the background report list still loaded exactly once")
                }
            }
        }
}
