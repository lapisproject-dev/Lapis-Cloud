package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4d batch 4: `TravelExpenseApprovalsScreen.kt`'s three migrated `lapisForm`s -- the admin rates form
 * (Kilometersatz/Tagespauschale, ADMIN only) and the two decision panels (Entscheidungsnotiz, Pflicht;
 * "Genehmigen und buchen"/"Buchung wiederholen" vs. "Ablehnen"), 1:1 nach
 * `ContributionReliefQueueScreen.renderReliefRequestedDecidePanel`/`renderReliefApprovedRetryPanel`s
 * Vorbild (`FormAuditFixesDomTest.reliefDecide_...`/`reliefRetry_...`). Driven the way a person does
 * (type, blur, click) against a stubbed `window.fetch`, same idiom `TravelExpenseScreenFormDomTest.kt`
 * (W4d batch 3) already establishes.
 */
class TravelExpenseApprovalsScreenFormDomTest {
    private val boardSession =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Board Member",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val adminSession =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin Member",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val rates =
        TravelExpenseRatesDto(
            mileageRatePerKm = 0.3.toDecimal(),
            perDiemRate = 28.0.toDecimal(),
            expenseAccountConfigured = true,
            bankAccountConfigured = true,
            rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30),
        )

    private fun requestedReport() =
        TravelExpenseReportDto(
            id = "report-1",
            subjectMemberId = "member-2",
            subjectDisplayName = "Amara Okafor",
            status = TravelExpenseReportStatus.REQUESTED,
            purpose = "Delegiertenversammlung",
            travelFrom = LocalDate(2026, 3, 10),
            travelTo = LocalDate(2026, 3, 12),
            totalAmount = 36.0.toDecimal(),
            createdAt = LocalDateTime(2026, 3, 1, 9, 0),
            submittedAt = LocalDateTime(2026, 3, 1, 9, 5),
            requestedBy = "member-2",
            requestedByDisplayName = "Amara Okafor",
        )

    private fun approvedReport() =
        requestedReport().copy(status = TravelExpenseReportStatus.APPROVED, executionError = "BANK_ACCOUNT_MISSING")

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `TravelExpenseScreenFormDomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    @Test
    fun requestedDecision_theNoteIsMarkedAsRequired_bothDecisionsCarryTheTrimmedNoteAndTheirOwnFlag(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute =
                routeOf { rpcService<ITravelExpenseService>().listReports(status = null, afterSubmittedAt = null, afterId = null) }
            val decideRoute = routeOf { rpcService<ITravelExpenseService>().decideReport("i", true, "n") }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(requestedReport())),
                        decideRoute to jsonOf(TravelExpenseReportDto.serializer(), requestedReport()),
                    ),
            ) { calls ->
                mountedForm("travel-expense-approvals-decide") { root, element ->
                    renderTravelExpenseApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Genehmigen und buchen" }
                    }
                    delay(80)
                    // M-7: a single required field is recognisable as required (star + legend), not silently "case (c)".
                    assertEquals(1, element().querySelectorAll(".lapis-required-mark").length, "a star on the note")
                    assertTrue(element().textContent.orEmpty().contains("* Pflichtfeld"), "with the legend")

                    // empty note: nothing goes out, at all
                    element().buttonNamed("Genehmigen und buchen").click()
                    delay(60)
                    assertEquals(0, calls.toRoute(decideRoute).size, "no decideReport for an empty note")
                    assertEquals("Bitte eine Entscheidungsnotiz eingeben.", element().shownErrors().single())

                    element().typeInto("Entscheidungsnotiz", "  Ordnungsgemäß geprüft  ")
                    element().buttonNamed("Genehmigen und buchen").click()
                    awaitUntil("approve", timeoutMs = 800) { calls.toRoute(decideRoute).size == 1 }
                    val approve = calls.singleCall(decideRoute)
                    assertEquals("report-1", approve.rpcParam(0) as String)
                    assertEquals(true, approve.rpcParam(1) as Boolean, "Genehmigen sends approve = true")
                    assertEquals("Ordnungsgemäß geprüft", approve.rpcParam(2) as String, "the note arrives trimmed")
                }
            }
        }

    @Test
    fun requestedDecision_reject_withABlankNote_blocksAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute =
                routeOf { rpcService<ITravelExpenseService>().listReports(status = null, afterSubmittedAt = null, afterId = null) }
            val decideRoute = routeOf { rpcService<ITravelExpenseService>().decideReport("i", false, "n") }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(requestedReport())),
                    ),
            ) { calls ->
                mountedForm("travel-expense-approvals-decide-blank") { root, element ->
                    renderTravelExpenseApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Ablehnen" }
                    }
                    delay(80)
                    element().typeInto("Entscheidungsnotiz", "   ")
                    element().buttonNamed("Ablehnen").click()
                    delay(60)
                    assertEquals(0, calls.toRoute(decideRoute).size, "a blank note blocks Ablehnen as well")
                    assertTrue(element().shownErrors().isNotEmpty())
                }
            }
        }

    @Test
    fun approvedRetry_retryNeedsNoNote_onlyRejectingDoes_andTheHintSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute =
                routeOf { rpcService<ITravelExpenseService>().listReports(status = null, afterSubmittedAt = null, afterId = null) }
            val retryRoute = routeOf { rpcService<ITravelExpenseService>().retryPosting("i") }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(approvedReport())),
                        retryRoute to jsonOf(TravelExpenseReportDto.serializer(), approvedReport()),
                    ),
            ) { calls ->
                mountedForm("travel-expense-approvals-retry") { root, element ->
                    renderTravelExpenseApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Buchung wiederholen" }
                    }
                    delay(80)
                    assertTrue(
                        element().textContent.orEmpty().contains("Nur für \"Ablehnen\" erforderlich."),
                        "the hint tells that the star is for Ablehnen only",
                    )
                    element().buttonNamed("Buchung wiederholen").click()
                    awaitUntil("retry", timeoutMs = 800) { calls.toRoute(retryRoute).size == 1 }
                    assertEquals("report-1", calls.singleCall(retryRoute).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun ratesSection_admin_getsTheLinkToTheSettingsPage_boardDoesNot(): Promise<Unit> =
        formTest {
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute =
                routeOf { rpcService<ITravelExpenseService>().listReports(status = null, afterSubmittedAt = null, afterId = null) }
            listOf(adminSession to true, boardSession to false).forEach { (session, expectsLink) ->
                AppState.setSession(session)
                withFetchStub(
                    respond =
                        answering(
                            ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                            listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), emptyList()),
                        ),
                ) { _ ->
                    mountedForm("travel-expense-approvals-rates-link-${session.role}") { root, element ->
                        renderTravelExpenseApprovalsScreen(root)
                        awaitUntil("rates summary rendered", timeoutMs = 800) {
                            element().textContent.orEmpty().contains("Tagegeld: ab 8 Std.")
                        }
                        val hasLink = element().allOf("button").any { it.textContent?.trim() == "Reisekosten-Einstellungen öffnen" }
                        assertEquals(expectsLink, hasLink, "the settings link is for the ADMIN only")
                        // The old inline rates form is gone.
                        assertTrue(element().allOf("button").none { it.textContent?.trim() == "Sätze speichern" })
                    }
                }
            }
        }

    @Test
    fun requestedCard_showsTheDayTableWithCaptionAndColumnScopes_theOtherCostsAndTheSettlement(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute =
                routeOf { rpcService<ITravelExpenseService>().listReports(status = null, afterSubmittedAt = null, afterId = null) }
            val report =
                requestedReport().copy(
                    advanceCents = 1000,
                    deductionCents = 560,
                    payoutCents = 1500,
                    payoutChoice = TravelExpensePayoutChoice.DONATION_WAIVER,
                    membershipNumber = "M-4711",
                    lines =
                        listOf(
                            TravelExpenseLineDto(
                                id = "day-1",
                                reportId = "report-1",
                                kind = TravelExpenseLineKind.DAY_ALLOWANCE,
                                description = "",
                                amount = 6.4.toDecimal(),
                                dayDate = LocalDate(2026, 3, 10),
                                timeFrom = LocalTime(8, 0),
                                timeTo = LocalTime(17, 0),
                                computedMinutes = 540,
                                baseCents = 600,
                                deductionCents = 560,
                                amountCents = 40,
                            ),
                            TravelExpenseLineDto(
                                id = "line-2",
                                reportId = "report-1",
                                kind = TravelExpenseLineKind.OTHER,
                                description = "Taxi",
                                amount = 12.0.toDecimal(),
                                amountCents = 1200,
                            ),
                        ),
                )
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(report)),
                    ),
            ) { _ ->
                mountedForm("travel-expense-approvals-day-table") { root, element ->
                    renderTravelExpenseApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) { element().querySelector("table") != null }
                    val table = element().allOf("table").single()
                    assertEquals("Reisetage", table.querySelector("caption")?.textContent?.trim(), "the caption is the table's name")
                    val headerScopes = table.allOf("th[scope=col]").map { it.textContent.orEmpty().trim() }
                    assertEquals(listOf("Datum", "Zeit", "Stunden", "Basis", "Abzüge", "Betrag"), headerScopes)
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("08:00 – 17:00"), "the stored times")
                    assertTrue(text.contains("9:00"), "the stored minutes as hours")
                    assertTrue(text.contains("Taxi"), "the other costs")
                    assertTrue(text.contains("Spendenquittung gewünscht"), "the waiver flag")
                    assertTrue(text.contains("Vorschuss"), "the advance")
                    assertTrue(text.contains("Mitgliedsnummer: M-4711"))
                }
            }
        }
}
