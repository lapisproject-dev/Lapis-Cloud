package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.browser.window
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
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.122 -- the day grid, the sticky totals bar, the settlement choice and the contact block of
 * `TravelExpenseScreen.kt`, driven the way a person does against a stubbed `window.fetch`.
 */
class TravelExpenseDayGridDomTest {
    private companion object {
        /** Real Bootstrap + theme: the row layout is CSS, a layout assertion without the stylesheet would test nothing. */
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('./theme.css')")
                true
            }
    }

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

    private fun describedText(control: Element): String =
        control
            .getAttribute("aria-describedby")
            .orEmpty()
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" | ") { document.getElementById(it)?.textContent.orEmpty() }

    private fun singleDayDraft(): TravelExpenseReportDto =
        draft(lines = listOf(dayLine(LocalDate(2026, 3, 10)))).copy(travelFrom = LocalDate(2026, 3, 10), travelTo = LocalDate(2026, 3, 10))

    private fun HTMLElement.directChildClasses(): List<String> =
        (0 until children.length).map {
            children.item(it)!!.className.substringBefore(' ')
        }

    // ---- V1.9.181: one row per day ----------------------------------------------------------------------------------------------------

    @Test
    fun rows_areOneColumnWithTheSameLeftEdgeAndWidth(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(memberSession)
            withDraft("te-row-aligned", draft()) { _, element, _, _ ->
                element().style.width = "1280px"
                delay(60)
                val rows = element().allOf("fieldset.lapis-te-day").map { it as HTMLElement }
                assertEquals(1, rows.map { it.offsetLeft }.toSet().size, "same left edge")
                assertEquals(1, rows.map { it.offsetWidth }.toSet().size, "same width")
            }
        }

    @Test
    fun row_childrenAreNamedWrappersInOrder(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-order", draft()) { _, element, _, _ ->
                element().allOf("fieldset.lapis-te-day").forEach { row ->
                    assertEquals(
                        listOf(
                            "h6",
                            "lapis-te-day-dest",
                            "lapis-te-day-times",
                            "lapis-te-day-meals",
                            "lapis-te-day-notes",
                            "lapis-te-day-result",
                        ),
                        row.directChildClasses(),
                    )
                }
            }
        }

    @Test
    fun mealCheckboxes_shareOneLine_onADesktopAndOnAPhone(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(memberSession)
            withDraft("te-row-meals-line", draft()) { _, element, _, _ ->
                for (width in listOf("1280px", "375px")) {
                    element().style.width = width
                    delay(80)
                    element().allOf("fieldset.lapis-te-day").forEach { row ->
                        val tops = row.allOf("input[type=checkbox]").map { (it as HTMLElement).offsetTop }.toSet()
                        assertEquals(1, tops.size, "breakfast, lunch and dinner sit on one line at $width: $tops")
                    }
                }
                element().style.width = ""
            }
        }

    @Test
    fun ids_areUniqueAcrossTheGrid(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-ids", draft(lines = days + hotel)) { _, element, _, _ ->
                val ids = element().allOf("[id]").map { it.id }
                val duplicates =
                    ids
                        .groupingBy { it }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                assertTrue(duplicates.isEmpty(), "duplicate ids: $duplicates")
            }
        }

    private fun fire(
        input: HTMLInputElement,
        value: String,
    ) {
        input.value = value
        input.dispatchEvent(
            org.w3c.dom.events
                .Event("input"),
        )
    }

    private val endHint = "Rückkehr oder Ankunft; bei einer einfachen Fahrt die Ankunft am Ziel"

    private fun labelsOf(row: HTMLElement): List<String> =
        row.allOf("label").map {
            it.textContent
                .orEmpty()
                .replace("*", "")
                .trim()
        }

    @Test
    fun singleDay_asksForBeginAndEndOfTheTrip_bothMarkedRequired_andTheHintHangsOnTheEndField(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-single", singleDayDraft()) { _, element, _, _ ->
                val row = element().allOf("fieldset.lapis-te-day").single()
                val labels = labelsOf(row)
                assertTrue("Beginn der Reise" in labels && "Ende der Reise" in labels, "labels: $labels")
                assertFalse(labels.any { it.contains("Abfahrt") || it.contains("Rückkehr") }, "old wording gone: $labels")
                val times = row.allOf("input[type=time]")
                assertEquals(2, times.size)
                times.forEach { assertEquals("true", it.getAttribute("aria-required"), "required for assistive technology") }
                assertEquals(2, row.allOf(".lapis-required-mark").size, "required marker on both fields")
                assertFalse(describedText(times[0]).contains(endHint), "no hint on the begin field")
                assertTrue(describedText(times[1]).contains(endHint), "hint on the end field: ${describedText(times[1])}")
                assertFalse(row.textContent.orEmpty().contains("Ohne Abfahrt und Rückkehr"), "old hint removed")
            }
        }

    @Test
    fun multiDay_firstDayAsksForBegin_lastDayForEnd_withTheHintOnTheEndFieldOnly(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-multi", draft()) { _, element, _, _ ->
                val rows = element().allOf("fieldset.lapis-te-day")
                assertEquals(listOf("Beginn der Reise"), labelsOf(rows[0]).filter { it.endsWith("der Reise") })
                assertTrue(labelsOf(rows[1]).none { it.endsWith("der Reise") }, "a middle day asks for no time")
                assertEquals(listOf("Ende der Reise"), labelsOf(rows[2]).filter { it.endsWith("der Reise") })
                assertFalse(rows[0].textContent.orEmpty().contains(endHint), "no end hint on the first day")
                val end = rows[2].allOf("input[type=time]").single()
                assertEquals("true", end.getAttribute("aria-required"))
                assertTrue(describedText(end).contains(endHint), "hint on the end field: ${describedText(end)}")
                val begin = rows[0].allOf("input[type=time]").single()
                assertEquals("true", begin.getAttribute("aria-required"))
            }
        }

    @Test
    fun theAmountCell_hasTwoLinesWithAFixedSize_neverWrapsOrClips(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(memberSession)
            withDraft("te-row-amount", singleDayDraft()) { _, element, _, _ ->
                val row = element().allOf("fieldset.lapis-te-day").single()
                val cell = row.querySelector(".lapis-te-day-amount") as HTMLElement
                // No times yet -> no result: only the first line, 0,00.
                assertEquals(1, cell.allOf(".lapis-te-day-amount-line").size, "second line absent without a result")
                assertTrue(cell.textContent.orEmpty().contains("Tagesbetrag: 0,00"))
                fire(row.allOf("input[type=time]")[0] as HTMLInputElement, "08:00")
                fire(row.allOf("input[type=time]")[1] as HTMLInputElement, "20:00")
                delay(80)
                val lines = cell.allOf(".lapis-te-day-amount-line").map { it as HTMLElement }
                assertEquals(2, lines.size, "amount line plus deductions line")
                assertTrue(lines[0].textContent.orEmpty().startsWith("Tagesbetrag: "))
                assertTrue(lines[1].textContent.orEmpty().startsWith("(Abzüge: "))
                val first = window.getComputedStyle(lines[0])
                val second = window.getComputedStyle(lines[1])
                assertEquals("nowrap", first.whiteSpace)
                assertTrue(first.fontVariantNumeric.contains("tabular-nums"), first.fontVariantNumeric)
                assertEquals(first.fontSize, second.fontSize, "same size on both lines")
                assertTrue(first.color != second.color, "the second line is a little lighter")
                assertTrue(lines.all { it.scrollWidth <= it.clientWidth }, "nothing is clipped")
                // A narrow container: still nothing cut.
                element().style.width = "360px"
                delay(60)
                assertTrue(lines.all { it.scrollWidth <= it.clientWidth }, "nothing is clipped at 360 px")
                assertTrue(
                    cell.getBoundingClientRect().right <= row.getBoundingClientRect().right + 1,
                    "the cell stays inside the row at 360 px",
                )
            }
        }

    @Test
    fun slots_showTheFixedTextOfTheDayPosition(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-slots", draft()) { _, element, _, _ ->
                val rows = element().allOf("fieldset.lapis-te-day")
                val firstSlots = rows[0].allOf(".lapis-te-day-slot")
                assertEquals(2, firstSlots.size)
                assertTrue(firstSlots[1].textContent.orEmpty().contains("bis 24:00"))
                val lastSlots = rows[2].allOf(".lapis-te-day-slot")
                // V1.9.189: the field first, the fixed text to its right -- the same order as the first day.
                assertTrue(lastSlots[0].textContent.orEmpty().contains("Ende der Reise"))
                assertTrue(lastSlots[1].textContent.orEmpty().contains("ab 00:00"))
                assertTrue(lastSlots[0].querySelector("input") != null, "the time field is the first slot")
                assertEquals(
                    lastSlots[0].getBoundingClientRect().top,
                    lastSlots[1].getBoundingClientRect().top,
                    "both slots sit in the same row",
                )
                val middle = rows[1].allOf(".lapis-te-day-slot")
                assertEquals(1, middle.size)
                assertTrue(middle.single().classList.contains("lapis-te-day-slot--span"))
                assertTrue(
                    middle
                        .single()
                        .textContent
                        .orEmpty()
                        .contains("ganztägig (24 Std.)"),
                )
            }
        }

    @Test
    fun lastDay_labelSitsTopLeft_andTheFixedTextIsLevelWithTheInput_likeTheFirstDay(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-last-geometry", draft()) { _, element, _, _ ->
                val rows = element().allOf("fieldset.lapis-te-day")
                for (index in listOf(0, 2)) {
                    val slots = rows[index].allOf(".lapis-te-day-slot")
                    val label = assertNotNull(slots[0].querySelector("label") as? HTMLElement, "label of row $index")
                    val input = assertNotNull(slots[0].querySelector("input") as? HTMLElement, "input of row $index")
                    val text = assertNotNull(slots[1].querySelector(".lapis-te-day-slot-text") as? HTMLElement, "fixed text of row $index")
                    assertEquals("flex-start", window.getComputedStyle(slots[0]).justifyContent, "slots start at the top (row $index)")
                    assertTrue(label.getBoundingClientRect().top < input.getBoundingClientRect().top, "label above the input (row $index)")
                    assertTrue(
                        kotlin.math.abs(label.getBoundingClientRect().left - slots[0].getBoundingClientRect().left) < 2,
                        "label left-aligned in its slot (row $index)",
                    )
                    val inputCenter = input.getBoundingClientRect().let { (it.top + it.bottom) / 2 }
                    val textCenter = text.getBoundingClientRect().let { (it.top + it.bottom) / 2 }
                    assertTrue(
                        kotlin.math.abs(inputCenter - textCenter) < 6,
                        "fixed text level with the input (row $index): $inputCenter vs $textCenter",
                    )
                }
                // Same classes and the same slot order on the first and the last day.
                assertEquals(
                    rows[0].allOf(".lapis-te-day-slot").map { it.className },
                    rows[2].allOf(".lapis-te-day-slot").map { it.className },
                )
            }
        }

    @Test
    fun mealLabels_areShort_andTheAmountIsTheDescription(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-meal-names", draft()) { _, element, _, _ ->
                val row = element().allOf("fieldset.lapis-te-day")[0]
                val boxes = row.allOf("input[type=checkbox]")
                assertEquals(
                    listOf("Frühstück", "Mittag", "Abend"),
                    boxes.map { box ->
                        row
                            .allOf("label")
                            .first {
                                it.getAttribute("for") ==
                                    box.id
                            }.textContent
                            .orEmpty()
                            .trim()
                    },
                )
                assertTrue(describedText(boxes[0]).contains("20 % · −5,60"), describedText(boxes[0]))
                assertTrue(describedText(boxes[1]).contains("40 % · −11,20"), describedText(boxes[1]))
            }
        }

    @Test
    fun labels_stayOnOneLine_atDesktopWidth(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(memberSession)
            withDraft("te-row-one-line", draft()) { _, element, _, _ ->
                element().style.width = "1280px"
                delay(80)
                element().allOf("fieldset.lapis-te-day label").forEach { label ->
                    val style = window.getComputedStyle(label)
                    val lineHeight =
                        style.lineHeight.removeSuffix("px").toDoubleOrNull() ?: (style.fontSize.removeSuffix("px").toDouble() * 1.2)
                    assertTrue(label.getBoundingClientRect().height < 1.5 * lineHeight, "'${label.textContent}' wraps")
                }
                element().style.width = ""
            }
        }

    @Test
    fun theWideRow_putsTheResultNextToTheFields(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(memberSession)
            withDraft("te-row-wide", draft()) { _, element, _, _ ->
                element().style.width = "1280px"
                delay(80)
                val row = element().allOf("fieldset.lapis-te-day")[0]
                val dest = row.querySelector(".lapis-te-day-dest") as HTMLElement
                val result = row.querySelector(".lapis-te-day-result") as HTMLElement
                assertTrue(result.offsetLeft > dest.offsetLeft + dest.offsetWidth - 1, "result is right of the fields")
                element().style.width = "375px"
                delay(80)
                assertTrue(result.offsetTop > dest.offsetTop, "result is below the fields on a phone")
                element().style.width = ""
            }
        }

    @Test
    fun staleRegion_existsFromTheStart_andIsEmpty(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withDraft("te-row-stale-region", draft()) { _, element, _, _ ->
                val region = element().querySelector(".lapis-te-day-stale")
                assertTrue(region != null, "present from the start")
                assertEquals("status", region.getAttribute("role"))
                assertEquals("", region.textContent.orEmpty().trim())
                assertEquals(null, element().querySelector(".lapis-te-day-grid")!!.getAttribute("data-stale"))
            }
        }

    @Test
    fun grid_hasOneFieldsetWithALegendPerDay_andNoTable(): Promise<Unit> =
        formTest {
            useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
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
                assertEquals(1, fieldsets[0].allOf("input[inputmode=numeric]").size)
                assertEquals(0, fieldsets[1].allOf("input[inputmode=numeric]").size)
                assertEquals(1, fieldsets[2].allOf("input[inputmode=numeric]").size)
                assertTrue(fieldsets[1].textContent.orEmpty().contains("ganztägig (24 Std.)"))
            }
        }

    @Test
    fun typingTheDeparture_showsTheLiveDuration_theDayAmount_andThePreviewMarker(): Promise<Unit> =
        formTest {
            useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
            AppState.setSession(memberSession)
            withDraft("te-grid-live", draft()) { _, element, _, _ ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                val bar = element().querySelector(".lapis-te-totals")
                assertTrue(bar != null, "the totals bar exists")
                // Saved state: no preview marker.
                assertTrue(bar.querySelector(".badge")?.let { (it as org.w3c.dom.HTMLElement).style.display == "none" } != false)
                val input = first.querySelector("input[inputmode=numeric]") as HTMLInputElement
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
                assertTrue(
                    describedText(breakfast).contains("gesetzt durch Übernachtung mit Frühstück"),
                    "the reason is attached via aria-describedby: ${describedText(breakfast)}",
                )
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
            useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
            AppState.setSession(memberSession)
            withDraft("te-grid-save", draft()) { _, element, calls, route ->
                val first = element().allOf("fieldset.lapis-te-day")[0]
                val time = first.querySelector("input[inputmode=numeric]") as HTMLInputElement
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
            PageIntroStorage.expandAll()
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
                // V1.9.187: the ONE help link of the screen is the page intro's (the contact block no longer carries a second one).
                val helpLinks = element().querySelectorAll("a[href*='/help/travel-expenses']")
                assertEquals(1, helpLinks.length, "exactly one help link: the intro's")
                assertTrue((helpLinks.item(0) as HTMLElement).getAttribute("rel").orEmpty().contains("noopener"))
                assertNotNull(
                    element().querySelector(".page-intro a[href*='/help/travel-expenses']"),
                    "the help link sits in the page intro",
                )
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
