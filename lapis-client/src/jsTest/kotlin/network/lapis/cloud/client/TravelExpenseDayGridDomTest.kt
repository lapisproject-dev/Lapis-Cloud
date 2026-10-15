package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.122 -- the day grid, the sticky totals bar, the settlement choice and the contact block of
 * `TravelExpenseScreen.kt`, driven the way a person does against a stubbed `window.fetch`.
 */
class TravelExpenseDayGridDomTest {
    private val memberSession =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Amara Okafor",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val adminSession = memberSession.copy(memberId = "admin-1", role = AccountRole.ADMIN)

    private val rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30)

    private fun rates(
        contact: TravelExpenseContactDto = TravelExpenseContactDto(),
        canEdit: Boolean = false,
    ) = TravelExpenseRatesDto(
        mileageRatePerKm = 0.3.toDecimal(),
        expenseAccountConfigured = true,
        bankAccountConfigured = true,
        rateSet = rateSet,
        contact = contact,
        canEditSettings = canEdit,
    )

    private fun dayLine(
        date: LocalDate,
        from: LocalTime? = null,
        to: LocalTime? = null,
        breakfast: Boolean = false,
        forced: Boolean = false,
        destination: String? = null,
    ) = TravelExpenseLineDto(
        id = "day-$date",
        reportId = "report-1",
        kind = TravelExpenseLineKind.DAY_ALLOWANCE,
        description = destination.orEmpty(),
        amount = 0.0.toDecimal(),
        dayDate = date,
        timeFrom = from,
        timeTo = to,
        breakfastProvided = breakfast,
        breakfastForcedByAccommodation = forced,
        destination = destination,
        computedMinutes = 0,
        baseCents = 0,
    )

    private val days =
        listOf(
            dayLine(LocalDate(2026, 3, 10)),
            dayLine(LocalDate(2026, 3, 11), forced = true),
            dayLine(LocalDate(2026, 3, 12)),
        )

    private val hotel =
        TravelExpenseLineDto(
            id = "hotel-1",
            reportId = "report-1",
            kind = TravelExpenseLineKind.ACCOMMODATION,
            description = "Hotel Alpha",
            amount = 150.0.toDecimal(),
            dayDate = LocalDate(2026, 3, 10),
            nights = 2,
            breakfastIncluded = true,
            amountCents = 15000,
        )

    private fun draft(
        lines: List<TravelExpenseLineDto> = days,
        payout: TravelExpensePayoutChoice? = null,
    ) = TravelExpenseReportDto(
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
        payoutChoice = payout,
        membershipNumber = "M-4711",
    )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private suspend fun withDraft(
        id: String,
        report: TravelExpenseReportDto,
        ratesDto: TravelExpenseRatesDto = rates(),
        block: suspend (
            root: io.kvision.panel.Root,
            element: () -> org.w3c.dom.HTMLElement,
            calls: List<RecordedRequest>,
            saveDays: String,
        ) -> Unit,
    ) {
        val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
        val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
        val saveDaysRoute = routeOf { rpcService<ITravelExpenseService>().saveDays("x", emptyList()) }
        withFetchStub(
            respond =
                answering(
                    ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), ratesDto),
                    listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(report)),
                    saveDaysRoute to jsonOf(TravelExpenseReportDto.serializer(), report),
                ),
        ) { calls ->
            mountedForm(id) { root, element ->
                renderTravelExpenseScreen(root, null)
                awaitUntil("editor rendered", timeoutMs = 800) { element().querySelectorAll("fieldset.lapis-te-day").length > 0 }
                delay(80)
                block(root, element, calls, saveDaysRoute)
            }
        }
    }

    @Test
    fun grid_hasOneFieldsetWithALegendPerDay_andNoTable(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-grid-structure", draft()) { _, element, _, _ ->
                val fieldsets = element().allOf("fieldset.lapis-te-day")
                assertEquals(3, fieldsets.size)
                assertEquals("Dienstag, 10.03.2026", fieldsets[0].querySelector("legend")?.textContent?.trim())
                assertEquals("Mittwoch, 11.03.2026", fieldsets[1].querySelector("legend")?.textContent?.trim())
                assertEquals("Donnerstag, 12.03.2026", fieldsets[2].querySelector("legend")?.textContent?.trim())
                assertEquals(
                    0,
                    element().querySelector(".lapis-te-day-grid")!!.querySelectorAll("table").length,
                    "an input grid is never a table",
                )
                assertTrue(element().querySelector(".lapis-te-day-grid") != null, "the CSS grid class")
                // Position-dependent fields: first day departure only, middle day nothing, last day arrival only.
                assertEquals(1, fieldsets[0].allOf("input[type=time]").size)
                assertEquals(0, fieldsets[1].allOf("input[type=time]").size)
                assertEquals(1, fieldsets[2].allOf("input[type=time]").size)
                assertTrue(fieldsets[1].textContent.orEmpty().contains("ganztägig (24 Std.)"))
            }
        }

    @Test
    fun typingTheDeparture_showsTheLiveDuration_theDayAmount_andThePreviewMarker(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-grid-live", draft()) { _, element, _, _ ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                val bar = element().querySelector(".lapis-te-totals")
                assertTrue(bar != null, "the totals bar exists")
                // Saved state: no preview marker.
                assertTrue(bar.querySelector(".badge")?.let { (it as org.w3c.dom.HTMLElement).style.display == "none" } != false)
                val input = first.querySelector("input[type=time]") as HTMLInputElement
                input.value = "08:00"
                input.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                delay(60)
                // First day from 08:00 runs until 24:00 = 16 h = 12.00 EUR (tier 2), calculated by the SHARED calculator.
                assertTrue(first.textContent.orEmpty().contains("16:00"), "live duration h:mm, was: ${first.textContent}")
                assertTrue(first.textContent.orEmpty().contains("12,00"), "live day amount, was: ${first.textContent}")
                assertTrue(bar.textContent.orEmpty().contains("Vorschau – nicht gespeichert"))
            }
        }

    @Test
    fun theBreakfastOfAnOvernightStayIsCheckedLockedAndExplained(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-grid-forced", draft(lines = days + hotel)) { _, element, _, _ ->
                val second = element().allOf("fieldset.lapis-te-day")[1]
                val breakfast = second.allOf("input[type=checkbox]").first() as HTMLInputElement
                assertTrue(breakfast.checked, "checked")
                assertTrue(breakfast.disabled, "locked")
                assertTrue(second.textContent.orEmpty().contains("gesetzt durch Übernachtung mit Frühstück"), "says why")
                val describedBy = breakfast.getAttribute("aria-describedby").orEmpty()
                assertTrue(describedBy.contains("-hint"), "the reason is attached via aria-describedby")
                val first = element().allOf("fieldset.lapis-te-day")[0]
                assertFalse((first.allOf("input[type=checkbox]").first() as HTMLInputElement).disabled, "a normal day stays editable")
            }
        }

    @Test
    fun settlement_hasNoPreselection_andSubmitStaysBlockedUntilAChoiceIsSaved(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-grid-settlement", draft(lines = days + hotel)) { _, element, _, _ ->
                val radios = element().allOf("input[type=radio]")
                assertEquals(2, radios.size, "Auszahlen / Spendenquittung")
                assertTrue(radios.none { (it as HTMLInputElement).checked }, "no preselection")
                assertTrue(element().textContent.orEmpty().contains("Die Zuwendungsbestätigung wird außerhalb von Lapis ausgestellt."))
                val submit = element().buttonNamed("Zur Freigabe einreichen") as org.w3c.dom.HTMLButtonElement
                assertTrue(submit.disabled, "no choice -> no submit")
            }
        }

    @Test
    fun theTotalsBar_isTheOnlyLiveRegion_andShowsTheDeductionsWithoutAnErrorColour(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-grid-live-region", draft()) { _, element, _, _ ->
                val live = element().allOf("[aria-live]")
                assertEquals(1, live.size, "exactly one live region in the editor")
                assertTrue(live.single().classList.contains("lapis-te-totals"))
                val text = live.single().textContent.orEmpty()
                assertTrue(
                    text.contains("Zwischensumme") &&
                        text.contains("Abzüge") &&
                        text.contains("Vorschuss") &&
                        text.contains("Auszahlungsbetrag"),
                )
                assertEquals(
                    0,
                    live
                        .single()
                        .allOf(".text-danger")
                        .filter { it.offsetParent != null }
                        .size,
                    "deductions are never red",
                )
            }
        }

    @Test
    fun saveDays_sendsTheEnteredTimesMealsAndDestination(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-grid-save", draft()) { _, element, calls, route ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                val time = first.querySelector("input[type=time]") as HTMLInputElement
                time.value = "09:30"
                time.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                first.typeInto("Ziel / Zweck", "Berlin")
                (first.allOf("input[type=checkbox]")[1] as HTMLInputElement).also {
                    it.checked = true
                    it.dispatchEvent(
                        org.w3c.dom.events
                            .Event("change"),
                    )
                }
                element().buttonNamed("Tage speichern").click()
                awaitUntil("saveDays", timeoutMs = 800) { calls.toRoute(route).size == 1 }
                val call = calls.singleCall(route)
                assertEquals("report-1", call.rpcParam(0) as String)
                val sent = call.rpcParam(1)
                assertEquals(3, sent.length as Int)
                assertEquals("2026-03-10", sent[0].dayDate as String)
                assertEquals("09:30", (sent[0].timeFrom as String).take(5))
                assertEquals(true, sent[0].lunchProvided as Boolean)
                assertEquals("Berlin", sent[0].destination as String)
                assertEquals(null, sent[1].timeFrom)
            }
        }

    // ---- contact block ---------------------------------------------------------------------------

    @Test
    fun contact_emptyAsAMember_showsThePlaceholderWithoutALink(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-contact-empty-member", draft(), rates()) { _, element, _, _ ->
                assertTrue(element().textContent.orEmpty().contains("Ihr Ansprechpartner für Reisekosten"))
                assertTrue(element().textContent.orEmpty().contains("Kontaktdaten werden noch ergänzt."))
                assertTrue(element().allOf("button").none { it.textContent?.trim() == "In den Einstellungen eintragen" })
                assertTrue(element().allOf("a[href^='mailto:']").isEmpty())
            }
        }

    @Test
    fun contact_emptyAsAnAdmin_offersTheLinkToTheSettings(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            withDraft("te-contact-empty-admin", draft(), rates(canEdit = true)) { _, element, _, _ ->
                assertTrue(element().allOf("button").any { it.textContent?.trim() == "In den Einstellungen eintragen" })
            }
        }

    @Test
    fun contact_set_rendersMailtoAndTelCorrectlyEncoded_andTheHintAsTextNotMarkup(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val contact =
                TravelExpenseContactDto(
                    name = "Erika Mustermann",
                    email = "erika+reisen@example.org",
                    phone = "+49 (531) 123 45-6",
                    hint = "Bitte <b>vorher</b> anrufen.\nZweite Zeile",
                )
            withDraft("te-contact-set", draft(), rates(contact = contact)) { _, element, _, _ ->
                val mail = element().querySelector("a[href^='mailto:']")
                assertEquals("mailto:erika%2Breisen%40example.org", mail?.getAttribute("href"), "the address is percent-encoded")
                val tel = element().querySelector("a[href^='tel:']")
                assertEquals("tel:+495311234" + "56", tel?.getAttribute("href"), "only + and digits")
                val hint = element().querySelector(".lapis-te-contact-hint")
                assertEquals(0, hint?.querySelectorAll("b")?.length, "a tag in the hint is text, not markup")
                assertTrue(hint?.textContent.orEmpty().contains("<b>vorher</b>"))
                val help = element().querySelector("a[href*='/help/travel-expenses']")
                assertTrue(help?.getAttribute("rel").orEmpty().contains("noopener"))
            }
        }

    @Test
    fun contact_withoutAHint_showsTheDefaultAlternative(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-contact-default-hint", draft(), rates(contact = TravelExpenseContactDto(name = "Erika"))) { _, element, _, _ ->
                assertTrue(element().textContent.orEmpty().contains("Alternativ: alte Excel-Vorlage oder E-Mail an den Finanzvorstand."))
                assertTrue(element().textContent.orEmpty().contains("Reisekosten rechnen Sie vorrangig hier ab."))
            }
        }
}
