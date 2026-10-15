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
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.132 "Reisekosten-Feinschliff", driven the way a person does against a stubbed `window.fetch` (UI language `de`, so the date order is
 * day-month-year with dots and never depends on the browser the tests run in): the locale date fields, the per-meal amounts, the day notes and the
 * live breakdown.
 */
class TravelExpensePolishDomTest {
    private val memberSession =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Amara Okafor",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val boardSession = memberSession.copy(memberId = "board-1", role = AccountRole.BOARD)

    private val rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30)

    private val ratesDto =
        TravelExpenseRatesDto(
            mileageRatePerKm = 0.3.toDecimal(),
            expenseAccountConfigured = true,
            bankAccountConfigured = true,
            rateSet = rateSet,
        )

    private fun dayLine(
        date: LocalDate,
        from: LocalTime? = null,
        to: LocalTime? = null,
        lunch: Boolean = false,
        dinner: Boolean = false,
    ) = TravelExpenseLineDto(
        id = "day-$date",
        reportId = "report-1",
        kind = TravelExpenseLineKind.DAY_ALLOWANCE,
        description = "",
        amount = 0.0.toDecimal(),
        dayDate = date,
        timeFrom = from,
        timeTo = to,
        lunchProvided = lunch,
        dinnerProvided = dinner,
        computedMinutes = 0,
        baseCents = 0,
    )

    private val days =
        listOf(
            dayLine(LocalDate(2026, 3, 10)),
            dayLine(LocalDate(2026, 3, 11)),
            dayLine(LocalDate(2026, 3, 12)),
        )

    private fun draft(
        lines: List<TravelExpenseLineDto> = days,
        status: TravelExpenseReportStatus = TravelExpenseReportStatus.DRAFT,
    ) = TravelExpenseReportDto(
        id = "report-1",
        subjectMemberId = "member-1",
        subjectDisplayName = "Amara Okafor",
        status = status,
        purpose = "Delegiertenversammlung",
        travelFrom = LocalDate(2026, 3, 10),
        travelTo = LocalDate(2026, 3, 12),
        totalAmount = 0.0.toDecimal(),
        lines = lines,
        createdAt = LocalDateTime(2026, 3, 1, 9, 0),
        requestedBy = "member-1",
        requestedByDisplayName = "Amara Okafor",
        membershipNumber = "M-4711",
    )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private class Routes(
        val updateDraft: String,
        val addLine: String,
    )

    private suspend fun withEditor(
        id: String,
        report: TravelExpenseReportDto = draft(),
        block: suspend (element: () -> HTMLElement, calls: List<RecordedRequest>, routes: Routes) -> Unit,
    ) {
        val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
        val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
        val updateDraft =
            routeOf {
                rpcService<ITravelExpenseService>().updateDraft(
                    "x",
                    TravelExpenseReportInput("x", LocalDate(2026, 1, 1), LocalDate(2026, 1, 1)),
                )
            }
        val addLine =
            routeOf {
                rpcService<ITravelExpenseService>().addLine(
                    "x",
                    TravelExpenseLineInput(kind = TravelExpenseLineKind.OTHER, description = "x"),
                )
            }
        withFetchStub(
            respond =
                answering(
                    ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), ratesDto),
                    listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(report)),
                    updateDraft to jsonOf(TravelExpenseReportDto.serializer(), report),
                    addLine to jsonOf(TravelExpenseReportDto.serializer(), report),
                ),
        ) { calls ->
            mountedForm(id) { root, element ->
                renderTravelExpenseScreen(root, null)
                awaitUntil("editor rendered", timeoutMs = 1000) { element().querySelectorAll("fieldset.lapis-te-day").length > 0 }
                delay(80)
                block(element, calls, Routes(updateDraft, addLine))
            }
        }
    }

    private fun HTMLElement.input(label: String): HTMLInputElement = controlOf(label) as HTMLInputElement

    private fun fire(
        input: HTMLInputElement,
        value: String,
    ) {
        input.value = value
        input.dispatchEvent(Event("input"))
    }

    // ---- locale date fields ----------------------------------------------------------------------------------------------------------

    @Test
    fun headerForm_showsTheDatesInTheLocaleOrder_withThePatternAsPlaceholderAndHint(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-header-prefill") { element, _, _ ->
                val from = element().input("Von")
                val to = element().input("Bis")
                assertEquals("10.03.2026", from.value)
                assertEquals("12.03.2026", to.value)
                assertEquals("TT.MM.JJJJ", from.placeholder)
                assertTrue(from.getAttribute("aria-describedby").orEmpty().contains("-hint"), "the format hint is attached to the field")
                assertTrue(element().textContent.orEmpty().contains("Format: TT.MM.JJJJ"))
                assertFalse(element().textContent.orEmpty().contains("JJJJ-MM-TT"), "no ISO pattern in a label any more")
                assertFalse(element().textContent.orEmpty().contains("Beispiel: 2026-03-14."))
            }
        }

    @Test
    fun headerForm_normalisesAValidEntryWhenTheFieldIsLeft_andLeavesAnInvalidOneAsTyped(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-header-normalise") { element, _, _ ->
                element().typeInto("Von", "5.3.2026")
                assertEquals("05.03.2026", element().input("Von").value, "a valid entry is shown in the canonical form")
                element().typeInto("Von", "5.3.26")
                assertEquals("5.3.26", element().input("Von").value, "an invalid entry stays exactly as typed")
                assertTrue(element().shownErrors().isNotEmpty())
                element().typeInto("Von", "05.03.2026")
                assertTrue(element().shownErrors().isEmpty(), "correcting the entry clears the error")
            }
        }

    @Test
    fun headerForm_namesThePattern_orTheProblem_inTheErrorOfEachKind(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-header-errors") { element, _, _ ->
                element().typeInto("Von", "abc")
                assertEquals(listOf("Bitte als TT.MM.JJJJ eingeben."), element().shownErrors())
                element().typeInto("Von", "5.3.26")
                assertEquals(listOf("Bitte das Jahr vierstellig eingeben (TT.MM.JJJJ)."), element().shownErrors())
                element().typeInto("Von", "31.04.2026")
                assertEquals(listOf("Dieses Datum gibt es nicht."), element().shownErrors())
                element().typeInto("Von", "05.03.1899")
                assertEquals(listOf("Bitte ein Jahr zwischen 1900 und 2999 eingeben."), element().shownErrors())
            }
        }

    @Test
    fun headerForm_save_sendsIsoDates_andTheCrossFieldRuleStillHolds(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-header-save") { element, calls, routes ->
                element().typeInto("Bis", "08.03.2026")
                assertTrue(
                    element().shownErrors().contains("Das Bis-Datum darf nicht vor dem Von-Datum liegen."),
                    "Bis before Von is still refused: ${element().shownErrors()}",
                )
                element().typeInto("Bis", "13.03.2026")
                element().typeInto("Von", "9.3.2026")
                element().buttonNamed("Entwurf speichern").click()
                awaitUntil("updateDraft", timeoutMs = 1000) { calls.toRoute(routes.updateDraft).size == 1 }
                val input = calls.singleCall(routes.updateDraft).rpcParam(1)
                assertEquals("2026-03-09", input.travelFrom as String)
                assertEquals("2026-03-13", input.travelTo as String)
            }
        }

    @Test
    fun firstNight_isALocaleDateField_withTheRangeRuleAndSendsAnIsoDate(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-first-night") { element, calls, routes ->
                element().buttonNamed("Übernachtung hinzufügen").click()
                delay(80)
                assertEquals("10.03.2026", element().input("Erste Nacht").value, "prefilled with the first travel day, in the locale order")
                element().typeInto("Erste Nacht", "13.03.2026")
                assertTrue(
                    element().shownErrors().contains("Die Übernachtungen müssen innerhalb der Reise liegen."),
                    "outside the trip: ${element().shownErrors()}",
                )
                element().typeInto("Erste Nacht", "11.3.2026")
                assertEquals("11.03.2026", element().input("Erste Nacht").value)
                element().typeInto("Unterkunft", "Hotel Alpha")
                element().typeInto("Betrag", "80,00")
                element().buttonNamed("Zeile hinzufügen").click()
                awaitUntil("addLine", timeoutMs = 1000) { calls.toRoute(routes.addLine).size == 1 }
                val input = calls.singleCall(routes.addLine).rpcParam(1)
                assertEquals("2026-03-11", input.dayDate as String)
            }
        }

    // ---- meals ------------------------------------------------------------------------------------------------------------------------

    private fun HTMLElement.labelTexts(): List<String> = allOf("label").map { it.textContent.orEmpty().trim() }

    @Test
    fun everyMeal_showsItsPercentageAndTheAmountItTakesOff(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-meal-labels") { element, _, _ ->
                val labels = element().allOf("fieldset.lapis-te-day")[0].labelTexts()
                assertTrue(labels.any { it.startsWith("Frühstück gestellt (20 % = −5,60") }, "breakfast: $labels")
                assertTrue(labels.any { it.startsWith("Mittagessen gestellt (40 % = −11,20") }, "lunch: $labels")
                assertTrue(labels.any { it.startsWith("Abendessen gestellt (40 % = −11,20") }, "dinner: $labels")
            }
        }

    @Test
    fun aDayWithoutAllowance_saysWhyACheckedMealTakesNothingOff(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-note-no-allowance") { element, _, _ ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                fire(first.querySelector("input[type=time]") as HTMLInputElement, "23:00")
                delay(60)
                assertFalse(first.textContent.orEmpty().contains("Kein Tagegeld an diesem Tag"), "no meal checked: nothing to explain")
                check(first, 1)
                delay(60)
                assertTrue(
                    first.textContent.orEmpty().contains("Kein Tagegeld an diesem Tag, daher kein Abzug."),
                    first.textContent.orEmpty(),
                )
            }
        }

    @Test
    fun aDayWhoseMealsExceedTheAllowance_saysTheDeductionIsCapped(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-note-capped") { element, _, _ ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                // 08:00 to 24:00 = 16 h -> 12,00 EUR; lunch + dinner would take 22,40 off.
                fire(first.querySelector("input[type=time]") as HTMLInputElement, "08:00")
                check(first, 1)
                check(first, 2)
                delay(60)
                val text = first.textContent.orEmpty()
                assertTrue(text.contains("Abzug auf den Tagessatz von 12,00"), text)
                assertTrue(text.contains("Tagesbetrag: 0,00"), "the day amount is 0, not negative: $text")
                val note = first.querySelector("[id^=lapis-te-day-note-]")
                assertNotNull(note)
                assertTrue(first.allOf("input[type=checkbox]").all { it.getAttribute("aria-describedby").orEmpty().contains(note.id) })
            }
        }

    private fun check(
        fieldset: HTMLElement,
        index: Int,
    ) {
        val box = fieldset.allOf("input[type=checkbox]")[index] as HTMLInputElement
        box.checked = true
        box.dispatchEvent(Event("change"))
    }

    // ---- live breakdown -----------------------------------------------------------------------------------------------------------------

    @Test
    fun theBreakdown_isARealTableWithCaptionAndRowHeaders_andAddsUpToTheTotalsBar(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-breakdown-table") { element, _, _ ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                fire(first.querySelector("input[type=time]") as HTMLInputElement, "08:00")
                check(first, 1)
                delay(80)
                val breakdown = assertNotNull(element().querySelector(".lapis-te-breakdown") as? HTMLElement)
                val table = assertNotNull(breakdown.querySelector("table"))
                assertEquals("So setzt sich der Betrag zusammen", table.querySelector("caption")?.textContent?.trim())
                assertTrue(table.querySelectorAll("th[scope=col]").length >= 2, "column headers")
                assertTrue(table.querySelectorAll("tbody th[scope=row]").length >= 3, "every row names itself")
                val text = table.textContent.orEmpty()
                assertTrue(text.contains("Tagegeld gesamt (brutto)"))
                assertTrue(text.contains("Abzüge für gestellte Mahlzeiten"))
                // Day 1: 12,00 gross, lunch 11,20 off; the middle day is 24 h = 28,00; the last day is not filled in (0) -> sum 28,80, the bar says the same.
                assertTrue(text.contains("−11,20"), "the deduction is a minus figure: $text")
                val bar = element().querySelector(".lapis-te-totals")!!.textContent.orEmpty()
                assertTrue(bar.contains("28,80"), "the totals bar: $bar")
                assertTrue(
                    table
                        .querySelector("tr.lapis-total-row")!!
                        .textContent
                        .orEmpty()
                        .contains("28,80"),
                    "the sum row: $text",
                )
                assertEquals(0, element().allOf("[aria-live]").size - 1, "the breakdown is not a second live region")
            }
        }

    @Test
    fun theDayDetails_areCollapsedByDefault_andAnOpenDayStaysOpenWhileTyping(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-polish-breakdown-details") { element, _, _ ->
                val details = element().allOf(".lapis-te-breakdown details")
                assertEquals(3, details.size, "one per travel day")
                assertTrue(details.none { it.asDynamic().open == true }, "closed by default")
                assertTrue(
                    details[0]
                        .querySelector("summary")!!
                        .textContent
                        .orEmpty()
                        .startsWith("10.03.2026"),
                    "dated in the locale order",
                )
                details[0].asDynamic().open = true
                val first = element().allOf("fieldset.lapis-te-day")[0]
                fire(first.querySelector("input[type=time]") as HTMLInputElement, "09:00")
                delay(80)
                val after = element().allOf(".lapis-te-breakdown details")
                assertTrue(after[0] === details[0], "the very same element is kept (focus and open state survive)")
                assertTrue(after[0].asDynamic().open == true, "still open")
                assertTrue(after[0].textContent.orEmpty().contains("Tagegeld bei 15:00 Std. Abwesenheit"), after[0].textContent.orEmpty())
            }
        }

    @Test
    fun aSubmittedReport_isExplainedFromItsFrozenRates_notTheCurrentOnes(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val frozen =
                network.lapis.cloud.shared.domain
                    .TravelExpenseRateSet(tierCents = listOf(500L, 1000L, 2000L))
            val lines =
                listOf(
                    dayLine(LocalDate(2026, 3, 10), from = LocalTime(8, 0)),
                    dayLine(LocalDate(2026, 3, 11)),
                    dayLine(LocalDate(2026, 3, 12), to = LocalTime(12, 0)),
                )
            val report =
                draft(lines, TravelExpenseReportStatus.REQUESTED)
                    .copy(
                        subjectMemberId = "member-2",
                        requestedBy = "member-2",
                        ratesSnapshot = frozen,
                        submittedAt = LocalDateTime(2026, 3, 1, 9, 5),
                    )
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val listRoute =
                routeOf { rpcService<ITravelExpenseService>().listReports(status = null, afterSubmittedAt = null, afterId = null) }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), ratesDto),
                        listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(report)),
                    ),
            ) { _ ->
                mountedForm("te-polish-approvals-snapshot") { root, element ->
                    renderTravelExpenseApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 1000) { element().querySelector(".lapis-te-breakdown table") != null }
                    val breakdown = element().querySelector(".lapis-te-breakdown") as HTMLElement
                    assertTrue(
                        breakdown
                            .querySelector("details > summary")!!
                            .textContent
                            .orEmpty()
                            .contains("So setzt sich der Betrag zusammen"),
                    )
                    // 10,00 + 20,00 + 5,00 on the frozen tiers (the current ones would give 12,00 + 28,00 + 6,00).
                    val text = breakdown.textContent.orEmpty()
                    assertTrue(text.contains("35,00"), text)
                    assertFalse(text.contains("46,00"), "no current rate leaks in: $text")
                }
            }
        }

    @Test
    fun aReportWithoutFrozenRates_saysSoInsteadOfGuessing(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            withMountedRoot("te-polish-no-snapshot") { root, element ->
                TravelExpenseBreakdownView(root, collapsible = true).update(BreakdownModel.NoSnapshot)
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Für diese Abrechnung sind keine gespeicherten Sätze vorhanden"), text)
                assertEquals(0, element().querySelectorAll("table").length, "no number is shown")
            }
        }
}
