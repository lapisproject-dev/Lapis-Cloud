package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartgDonorProposalAction
import network.lapis.cloud.shared.domain.PartgDonorProposalAvailability
import network.lapis.cloud.shared.domain.PartgDonorProposalBlock
import network.lapis.cloud.shared.domain.PartgDonorProposalDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRowDto
import network.lapis.cloud.shared.domain.PartgDonorProposalStatus
import network.lapis.cloud.shared.domain.PartgLargeDonorOrigin
import network.lapis.cloud.shared.domain.PartgPersonKind
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.293 (R5b) -- the dialog "Vorschlag für Ausweis B aus Buchungen" (status as a word, only NEW pre-ticked, nothing chosen for SIMILAR, the button
 * says what happens, "–" instead of 0, the fixed note with Q40, the stale answer), the button in mode C and the column "Herkunft".
 */
class PartyReportDonorProposalDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.buttons(): List<String> = allOf("button").map { it.textContent?.trim().orEmpty() }

    private val refNew = "M:11111111-1111-4111-8111-111111111111"
    private val refDifferent = "M:22222222-2222-4222-8222-222222222222"
    private val refSimilar = "M:33333333-3333-4333-8333-333333333333"
    private val refUnchanged = "M:44444444-4444-4444-8444-444444444444"
    private val refBlocked = "X:55555555-5555-4555-8555-555555555555"

    private fun row(
        ref: String?,
        status: PartgDonorProposalStatus,
        name: String,
        booked: Long? = 1_200_000L,
        entered: Long? = null,
        allowed: Set<PartgDonorProposalAction> = emptySet(),
        preselected: PartgDonorProposalAction? = null,
        similar: List<String> = emptyList(),
        block: PartgDonorProposalBlock? = null,
        linked: String? = null,
        below: Boolean = false,
    ) = PartgDonorProposalRowDto(
        ref = ref,
        status = status,
        personKind = PartgPersonKind.NATURAL,
        name = name,
        street = "Hauptstraße 1",
        postalCode = "38100",
        city = "Braunschweig",
        country = "DE",
        bookedCents = booked,
        enteredCents = entered,
        linkedRowId = linked,
        similarRowIds = similar,
        allowedActions = allowed,
        preselected = preselected,
        block = block,
        linkedBelowThreshold = below,
    )

    private fun proposal(anonymised: Int = 2) =
        PartgDonorProposalDto(
            year = 2025,
            annexRevision = 3,
            checksum = "a".repeat(64),
            rows =
                listOf(
                    row(
                        refNew,
                        PartgDonorProposalStatus.NEW,
                        "Neue Person",
                        allowed = setOf(PartgDonorProposalAction.CREATE),
                        preselected = PartgDonorProposalAction.CREATE,
                    ),
                    row(
                        refSimilar,
                        PartgDonorProposalStatus.SIMILAR,
                        "Ähnliche Person",
                        allowed = setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE),
                        similar = listOf("d1", "d2"),
                    ),
                    row(
                        refDifferent,
                        PartgDonorProposalStatus.DIFFERENT,
                        "Abweichende Person",
                        booked = 1_300_000L,
                        entered = 1_200_000L,
                        allowed = setOf(PartgDonorProposalAction.REPLACE_AMOUNT),
                        linked = "d3",
                    ),
                    row(
                        refBlocked,
                        PartgDonorProposalStatus.NOT_TRANSFERABLE,
                        "Gesperrte Firma",
                        block = PartgDonorProposalBlock.COUNTRY_MISSING,
                    ),
                    row(refUnchanged, PartgDonorProposalStatus.UNCHANGED, "Gleiche Person", entered = 1_200_000L, linked = "d4"),
                    row(null, PartgDonorProposalStatus.ENTERED_ONLY, "Nur erfasst", booked = null, entered = 2_000_000L, linked = "d5"),
                    row(
                        null,
                        PartgDonorProposalStatus.ENTERED_ONLY,
                        "Verknüpft darunter",
                        booked = null,
                        entered = 1_100_000L,
                        linked = "d6",
                        below = true,
                    ),
                ),
            anonymisedCount = anonymised,
            withoutDonorCount = 1,
        )

    @Test
    fun theDialog_showsWordsNotOnlyColours_preselectsOnlyNew_andTheButtonSaysWhatHappens(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            AppState.setSession(PartyReportDomSupport.session())
            mountedForm("party-report-donor-proposal") { root, _ ->
                root.div()
                openPartgDonorProposalDialog(
                    proposal = proposal(),
                    rowNumbers = mapOf("d1" to 1, "d2" to 2),
                    onApplied = {},
                    onReload = {},
                )
                val modal = lastOpenModal()
                assertTrue(modal.shows("Vorschlag für Ausweis B aus Buchungen: 2025"))
                val rows = modal.allOf(".lapis-partg-prop-row")
                assertEquals(7, rows.size)
                assertEquals(
                    listOf("NEW", "SIMILAR", "DIFFERENT", "NOT_TRANSFERABLE", "UNCHANGED", "ENTERED_ONLY", "ENTERED_ONLY"),
                    rows.map { it.getAttribute("data-partg-prop-status") },
                )
                // the status is a word in every row
                assertTrue(rows[0].shows("Neu") && rows[1].shows("Ähnliche Zeile vorhanden") && rows[2].shows("Betrag weicht ab"))
                assertTrue(rows[3].shows("Nicht übernehmbar") && rows[3].shows("Land fehlt in den Stammdaten"))
                assertTrue(rows[4].shows("Unverändert") && rows[5].shows("Nur von Hand erfasst"))
                assertTrue(rows[6].shows("Verknüpft; die Buchungen liegen derzeit nicht über 10.000 €."))
                // every cell carries its label for the card layout
                rows.forEach { r -> assertEquals(7, r.allOf(".lapis-partg-prop-cell[data-label]").size) }
                // only the NEW row is ticked; the different amount is the person's decision; nothing is chosen for the similar row
                val picks = modal.allOf("input[type=checkbox]")
                assertEquals(listOf(true, false), picks.map { it.asDynamic().checked as Boolean })
                val radios = modal.allOf("input[type=radio]")
                assertEquals(3, radios.size, "two candidates and 'Neu anlegen'")
                assertTrue(radios.none { it.asDynamic().checked as Boolean })
                assertTrue(modal.shows("Verknüpfen mit Zeile 1") && modal.shows("Verknüpfen mit Zeile 2") && modal.shows("Neu anlegen"))
                // the difference is "–" when one side is missing, never 0; a real difference is shown
                assertTrue(rows[0].allOf(".lapis-partg-prop-cell").single { it.getAttribute("data-label") == "Differenz" }.shows("–"))
                assertTrue(
                    rows[2].allOf(".lapis-partg-prop-cell").single { it.getAttribute("data-label") == "Differenz" }.shows("1.000,00"),
                )
                assertFalse(rows[5].allOf(".lapis-partg-prop-cell").single { it.getAttribute("data-label") == "Differenz" }.shows("0,00"))
                // the fixed note, the anonymised donors only as a number
                assertTrue(modal.shows("Q1, Q40") && modal.shows("Entwurf, nicht geprüft"))
                assertTrue(modal.shows("2 anonymisierte Spender sind nicht aufgeführt."))
                assertFalse(modal.shows("Anonym"))
                // the button counts what will happen
                assertTrue(modal.buttons().contains("1 anlegen"), modal.buttons().toString())
                picks[1].click()
                awaitUntil("replace counted") { modal.buttons().contains("1 anlegen · 1 Betrag ersetzen") }
                radios[0].click()
                awaitUntil("link counted") { modal.buttons().contains("1 anlegen · 1 Betrag ersetzen · 1 verknüpfen") }
                // the same row cannot be linked twice: choosing 'Neu anlegen' replaces the link
                radios[2].click()
                awaitUntil("create counted") { modal.buttons().contains("2 anlegen · 1 Betrag ersetzen") }
                // without any pick the button is switched off
                picks[0].click()
                picks[1].click()
                awaitUntil("only the similar row left") { modal.buttons().contains("1 anlegen") }
                val apply = modal.allOf("button").single { it.textContent.orEmpty().trim() == "1 anlegen" } as HTMLElement
                assertFalse(apply.asDynamic().disabled as Boolean)
            }
        }

    @Test
    fun linkingARowAlreadyTakenByAnotherRow_isRefused_andTheUiKeepsTheEarlierPick(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            AppState.setSession(PartyReportDomSupport.session())
            mountedForm("party-report-donor-proposal-conflict") { root, _ ->
                root.div()
                val refX = "M:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                val refY = "M:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                val similarAction = setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE)
                openPartgDonorProposalDialog(
                    proposal =
                        PartgDonorProposalDto(
                            year = 2025,
                            annexRevision = 3,
                            checksum = "a".repeat(64),
                            rows =
                                listOf(
                                    row(refX, PartgDonorProposalStatus.SIMILAR, "Person X", allowed = similarAction, similar = listOf("t")),
                                    row(refY, PartgDonorProposalStatus.SIMILAR, "Person Y", allowed = similarAction, similar = listOf("t")),
                                ),
                            anonymisedCount = 0,
                            withoutDonorCount = 0,
                        ),
                    rowNumbers = mapOf("t" to 1),
                    onApplied = {},
                    onReload = {},
                )
                val modal = lastOpenModal()

                fun checked(r: HTMLElement) = r.asDynamic().checked as Boolean
                val radios = modal.allOf("input[type=radio]")
                assertEquals(4, radios.size)
                radios[0].click() // X links T
                awaitUntil("X linked") { modal.buttons().contains("1 verknüpfen") }
                radios[3].click() // Y: create
                awaitUntil("Y create") { modal.buttons().contains("1 anlegen · 1 verknüpfen") }
                radios[2].click() // Y: link T is refused
                awaitUntil("Y shows its create pick again: ${radios.map { checked(it) }}") { checked(radios[3]) }
                assertFalse(checked(radios[2]))
                assertTrue(checked(radios[0]))
                assertTrue(modal.buttons().contains("1 anlegen · 1 verknüpfen"), modal.buttons().toString())
            }
        }

    @Test
    fun theDialog_withoutAnyPick_hasASwitchedOffButton_andAnEmptyProposalSaysSo(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            AppState.setSession(PartyReportDomSupport.session())
            mountedForm("party-report-donor-proposal-empty") { root, _ ->
                root.div()
                openPartgDonorProposalDialog(
                    proposal = proposal(anonymised = 0).copy(rows = emptyList(), withoutDonorCount = 0),
                    rowNumbers = emptyMap(),
                    onApplied = {},
                    onReload = {},
                )
                val modal = lastOpenModal()
                assertTrue(modal.shows("Keine Person liegt über 10.000 € und es ist keine Zeile erfasst."))
                val apply = modal.allOf("button").single { it.textContent.orEmpty().trim() == "Übernehmen" } as HTMLElement
                assertTrue(apply.asDynamic().disabled as Boolean)
                assertFalse(modal.shows("anonymisierte"))
            }
        }

    @Test
    fun takingOver_sendsTheChecksumAndThePicks_andAStaleAnswerOffersReloading(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.donorApplyStale = true
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            var reloaded = 0
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-donor-proposal-stale") { root, _ ->
                    root.div()
                    openPartgDonorProposalDialog(
                        proposal = proposal(),
                        rowNumbers = emptyMap(),
                        onApplied = {},
                        onReload = { reloaded += 1 },
                    )
                    val modal = lastOpenModal()
                    (modal.allOf("button").single { it.textContent.orEmpty().trim() == "1 anlegen" } as HTMLElement).click()
                    awaitUntil("the stale text") { modal.shows("Der Vorschlag ist veraltet. Bitte neu laden.") }
                    val sent =
                        PartyReportDomSupport.donorWrites
                            .single()
                            .json.params
                    // Kilua RPC sends every parameter as its own JSON text
                    assertEquals("a".repeat(64), JSON.parse<String>(sent[1] as String))
                    val picks = JSON.parse<dynamic>(sent[2] as String)
                    assertEquals(1, (picks.length as Int))
                    assertEquals(refNew, picks[0].ref as String)
                    assertEquals("CREATE", picks[0].action as String)
                    (modal.allOf("button").single { it.textContent.orEmpty().trim() == "Neu laden" } as HTMLElement).click()
                    awaitUntil("the reload callback") { reloaded == 1 }
                }
            }
        }

    @Test
    fun theTab_showsTheButtonInModeA_aDisabledOneWithTextInModeC_andTheOriginInWords(): Promise<Unit> =
        formTest {
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "AUSWEISE") }
            AppState.setSession(PartyReportDomSupport.session())
            PartyReportDomSupport.resetR3()
            PartyReportDomSupport.resetR4()
            val applied = LocalDateTime(2025, 3, 15, 10, 30)
            val donors =
                listOf(
                    PartyReportDomSupport.donor("d1", 1, "Hand Person"),
                    PartyReportDomSupport.donor("d2", 2, "Buchung Person", origin = PartgLargeDonorOrigin.LEDGER, appliedAt = applied),
                    PartyReportDomSupport.donor(
                        "d3",
                        3,
                        "Geaendert Person",
                        origin = PartgLargeDonorOrigin.LEDGER_EDITED,
                        appliedAt = applied,
                    ),
                )
            val routes = PartyReportDomSupport.routes()
            for (availability in listOf(PartgDonorProposalAvailability.AVAILABLE, PartgDonorProposalAvailability.MODE_C)) {
                PartyReportDomSupport.annex = PartyReportDomSupport.annexDto(donors = donors, proposal = availability)
                withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                    mountedForm("party-report-donor-origin-$availability") { root, element ->
                        renderPartyReportScreen(root)
                        awaitUntil("the jump bar") { element().allOf("#partg-block-b-toggle").isNotEmpty() }
                        element().allOf("#partg-block-b-toggle").single().let {
                            if (it.getAttribute("aria-expanded") != "true") it.click()
                        }
                        awaitUntil("the donor table") { element().allOf("tbody tr").size == donors.size }
                        val page = element()
                        val origins =
                            page.allOf("[data-partg-donor-origin]").map {
                                it.getAttribute("data-partg-donor-origin") to
                                    it.textContent.orEmpty()
                            }
                        assertEquals(
                            listOf(
                                "MANUAL" to "Von Hand",
                                "LEDGER" to "Aus Buchungen (übernommen am 15.03.2025)",
                                "LEDGER_EDITED" to "Aus Buchungen, danach geändert",
                            ),
                            origins,
                        )
                        val button = page.allOf("#partg-donor-proposal").single()
                        assertTrue(button.textContent.orEmpty().contains("Vorschlag aus Buchungen"))
                        if (availability == PartgDonorProposalAvailability.AVAILABLE) {
                            assertFalse(button.asDynamic().disabled as Boolean)
                            assertTrue(page.allOf("[data-partg-donor-proposal-note]").isEmpty())
                        } else {
                            assertTrue(button.asDynamic().disabled as Boolean)
                            assertEquals("true", button.getAttribute("aria-disabled"))
                            assertTrue(page.shows("Die Bundesgliederung bucht nicht in Lapis – kein Vorschlag möglich."))
                        }
                        assertTrue(page.allOf("input").none { (it as? HTMLInputElement)?.value == "M:" })
                    }
                }
            }
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
        }
}
