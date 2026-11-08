package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgConsolidatedDonorDto
import network.lapis.cloud.shared.domain.PartgConsolidatedRowDto
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDonorCandidateDto
import network.lapis.cloud.shared.domain.PartgExcludedUnitDto
import network.lapis.cloud.shared.domain.PartgExclusionReason
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import network.lapis.cloud.shared.domain.PartgIntraTransferDto
import network.lapis.cloud.shared.domain.PartgMatchState
import network.lapis.cloud.shared.domain.PartgNotComputableReason
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnmatchedDto
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.290 (R5) -- the tab "Konsolidierung": five blocks in a fixed order, "n. b." with the number of units without a value (never 0), a symbol AND a
 * word for the state of a pair, the confirmation that names the units, the fixed Q1 notice, the live region, the combination call, and that a chapter
 * treasurer neither sees the tab nor calls the service.
 */
class PartyReportConsolidationTabDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private val federalKey = PartyReportDomSupport.federal.unitKey
    private val stateKey = PartyReportDomSupport.state.unitKey

    private fun donor(
        id: String,
        name: String,
        unit: String,
        cents: Long,
    ) = PartgConsolidatedDonorDto(
        combinationId = null,
        displayDonorId = id,
        personKind = PartgPersonKind.NATURAL,
        name = name,
        street = null,
        postalCode = "38100",
        city = "Ort",
        country = "DE",
        contributionKind = PartgContributionKind.DONATION,
        totalCents = cents,
        unitKeys = listOf(unit),
        donorIds = listOf(id),
    )

    private fun dto(
        transfers: List<PartgIntraTransferDto> = emptyList(),
        donors: List<PartgConsolidatedDonorDto> = emptyList(),
        candidates: List<PartgDonorCandidateDto> = emptyList(),
        findings: List<PartgFindingDto> = emptyList(),
        revision: Long = 3,
    ) = PartgConsolidationDto(
        year = 2025,
        revision = revision,
        annexRevisions = mapOf(federalKey to 2L, stateKey to 5L),
        included = listOf(federalKey, stateKey),
        excluded = listOf(PartgExcludedUnitDto("STATE:ffffffff-ffff-4fff-8fff-ffffffffffff", PartgExclusionReason.BOOKINGS_NOT_TAKEN_OVER)),
        priorExcluded = emptyList(),
        rows =
            PartgPositions.ALL.map { position ->
                val missing = position.code == "E01" || position.code == "E11"
                val cell: PartgConsolidatedCell =
                    if (missing) {
                        PartgConsolidatedCell.NotComputable(listOf(stateKey), PartgNotComputableReason.MISSING_IN_UNITS)
                    } else {
                        PartgConsolidatedCell.Value(123_456L)
                    }
                PartgConsolidatedRowDto(position.code, cell, if (position.code == "A03") 9_000L else 0L, cell, cell)
            },
        priorTransfersUnmatched = true,
        transfers = transfers,
        unmatched = listOf(PartgUnmatchedDto(federalKey, "A03", 1_000L)),
        donors = donors,
        candidates = candidates,
        candidateTotal = candidates.size,
        findings = findings,
    )

    private fun pair(match: PartgMatchState = PartgMatchState.DIFFERS) =
        PartgIntraTransferDto(
            "t1",
            PartgTransferKind.SUBSIDY,
            federalKey,
            stateKey,
            10_000L,
            if (match ==
                PartgMatchState.MATCH
            ) {
                10_000L
            } else {
                9_000L
            },
            null,
            match,
        )

    private suspend fun openTab(block: suspend (HTMLElement) -> Unit) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "KONSOLIDIERUNG") }
        AppState.setSession(PartyReportDomSupport.session())
        val routes = PartyReportDomSupport.routes()
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
            mountedForm("party-report-consolidation") { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the consolidation blocks") { element().allOf("[data-partg-positions]").isNotEmpty() }
                block(element())
            }
        }
    }

    @Test
    fun theFiveBlocks_areInAFixedOrder_theDraftBannerIsThere_andNoClaimOfAnOfficialReport(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation =
                dto(
                    transfers = listOf(pair()),
                    donors = listOf(donor("d1", "Fixture Person Eins", federalKey, 1_200_000L)),
                    findings =
                        listOf(
                            PartgFindingDto(
                                PartgFindingCode.P17,
                                PartgFindingLevel.CHECK,
                                federalKey,
                                positionCode = "A03",
                                target = PartgFindingTarget.CONSOLIDATION,
                            ),
                        ),
                )
            openTab { page ->
                val blocks = listOf("completeness", "positions", "reconciliation", "donors", "findings")
                val seen =
                    page
                        .allOf(blocks.joinToString(",") { "[data-partg-$it]" })
                        .map { el -> blocks.first { el.hasAttribute("data-partg-$it") } }
                assertEquals(blocks, seen)
                assertTrue(page.allOf(".alert-warning").single().shows("Entwurf, nicht geprüft"))
                val all =
                    page.textContent
                        .orEmpty()
                        .lowercase()
                        .replace("nicht geprüft", "")
                        .replace("kein amtlicher bericht", "")
                        .replace("kein geprüfter ausweis", "")
                        .replace("ersetzt nicht den geprüften", "")
                        .replace("nicht amtlich", "")
                listOf("amtlich", "fertig", "geprüft", "einreichen").forEach {
                    assertFalse(
                        all.contains(it),
                        "no claim of '$it' near: " + all.substringAfter(it).take(80) + " / before: " + all.substringBefore(it).takeLast(80),
                    )
                }
            }
        }

    @Test
    fun aValueThatCannotBeComputed_isNb_withTheNumberOfUnitsWithoutAValue_andNeverZero(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation = dto()
            openTab { page ->
                val rows = page.allOf("tbody tr").filter { it.shows("Mitgliedsbeiträge") }
                assertTrue(rows.isNotEmpty())
                val cells = rows.first().allOf("td").map { it.textContent.orEmpty() }
                assertTrue(cells.count { it.contains("n. b.") } >= 3)
                assertTrue(cells.any { it.contains("1 Einheiten ohne Wert") })
                // the columns sum of the units, consolidated and last year: a missing value is never shown as 0 (the eliminated column is a real amount)
                assertFalse(listOf(cells[2], cells[4], cells[5]).any { it.contains("0,00") }, "a missing value is never shown as 0")
                // a value is a number
                assertTrue(page.shows("1.234,56"))
                // the excluded unit is listed with its own reason and does not count as 0
                assertTrue(page.shows("Buchungen vorhanden, nicht übernommen"))
                // the last year has no pairs although it reports transfers
                assertTrue(page.shows("Die Spalte Vorjahr ist deshalb nicht bereinigt"))
            }
        }

    @Test
    fun theStateOfAPair_isASymbolAndAWord_neverColourAlone(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation = dto(transfers = listOf(pair(PartgMatchState.DIFFERS)))
            openTab { page ->
                val cell =
                    page
                        .allOf("tbody tr")
                        .first { it.shows("weicht ab") }
                        .allOf("td")
                        .first { it.shows("weicht ab") }
                assertTrue(cell.allOf(".fa-triangle-exclamation").isNotEmpty(), "a symbol")
                assertEquals("true", cell.allOf(".fa-triangle-exclamation").first().getAttribute("aria-hidden"))
                assertFalse(cell.allOf(".badge").any { it.className.contains("danger") }, "no red")
                // the difference that stays visible
                assertTrue(page.shows("nicht abgeglichen: 10,00"))
            }
        }

    @Test
    fun theFixedQ1Notice_andThePoliteLiveRegion_areThere(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation = dto(donors = listOf(donor("d1", "Fixture Person Eins", federalKey, 1_200_000L)))
            openTab { page ->
                assertTrue(page.allOf("[data-partg-q1]").single().shows("rechtlich noch nicht geklärt"))
                val live = page.allOf("[aria-live=polite]")
                assertTrue(live.any { it.shows("2 Gliederungen einbezogen, 1 nicht einbezogen") })
            }
        }

    @Test
    fun combiningNeedsTwoRows_namesTheUnitsInTheConfirmation_andSendsTheIdsOnce(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation =
                dto(
                    donors =
                        listOf(
                            donor("d1", "Fixture Person Eins", federalKey, 1_200_000L),
                            donor("d2", "Fixture Person Eins", stateKey, 1_100_000L),
                        ),
                    candidates = listOf(PartgDonorCandidateDto(listOf("d1", "d2"), listOf(federalKey, stateKey))),
                )
            openTab { page ->
                val combine = page.buttonNamed("Zusammenführen")
                assertEquals("true", combine.getAttribute("aria-disabled"), "disabled until two rows are ticked")
                // the suggestion carries ids only; the screen resolves the name from its donor list
                assertTrue(page.shows("Fixture Person Eins in Bundesverband, Landesverband Nord"))
                page.buttonNamed("Vorschlag auswählen").click()
                awaitUntil("the button is enabled") { page.buttonNamed("Zusammenführen").getAttribute("aria-disabled") == "false" }
                page.buttonNamed("Zusammenführen").click()
                awaitUntil("the confirmation") { lastOpenModal().shows("Zeilen zusammenführen?") }
                val modal = lastOpenModal()
                assertTrue(modal.shows("Bundesverband; Landesverband Nord"), "the units are named")
                assertTrue(modal.shows("rechtlich noch nicht geklärt"), "the Q1 notice is in the dialog too")
                modal.buttonNamed("Zusammenführen").click()
                awaitUntil("the call") { PartyReportDomSupport.consolidationWrites.isNotEmpty() }
                val call = PartyReportDomSupport.consolidationWrites.single().second
                assertEquals(2025, call.rpcParam(0).unsafeCast<Int>())
                assertEquals(3, call.rpcParam(1).unsafeCast<Int>())
                assertEquals(2, call.rpcParam(3).length.unsafeCast<Int>())
            }
        }

    @Test
    fun aChapterTreasurer_neverSeesTheTab_andNeverCallsTheService(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation = dto()
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "KONSOLIDIERUNG") }
            AppState.setSession(
                PartyReportDomSupport.session(
                    role = AccountRole.MEMBER,
                    chapterTreasurerScopes = listOf(RegionalChapterRefDto("0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90", "Nord")),
                ),
            )
            val routes = PartyReportDomSupport.routes()
            val calls = mutableListOf<String>()
            withFetchStub(
                respond = { request ->
                    if (request.isRpc) calls += request.rpcRoute
                    PartyReportDomSupport.answer(routes, request, 2025)
                },
            ) {
                mountedForm("party-report-consolidation-treasurer") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").isNotEmpty() }
                    // the remembered tab is not visible for this person: the overview is shown, the tab is not offered
                    assertFalse(element().allOf(".btn-group button").any { it.shows("Konsolidierung") })
                    assertFalse(element().shows("Gesamtpartei (konsolidiert"))
                    assertFalse(routes.getConsolidation in calls, "the service is never called")
                }
            }
        }

    @Test
    fun theOverview_hasTheConsolidatedRowFirst_onlyForTheFederalView(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.consolidation = dto()
            runCatching { window.localStorage.removeItem("lapis.partyReport.tab") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-consolidation-overview") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the overview rows") { element().allOf("tbody tr").size == 3 }
                    val first = element().allOf("tbody tr").first()
                    assertTrue(first.shows("Gesamtpartei (konsolidiert, Entwurf)"))
                    assertTrue(first.shows("n. b."), "income is not computable in the fixture")
                    assertEquals("Zur Konsolidierung", first.allOf("button").single().getAttribute("aria-label"))
                }
            }
        }

    @Test
    fun theLogic_isPureAndHasFixedTexts() {
        assertTrue(
            resolvedAttributeText(
                partgConsolidatedCellText(PartgConsolidatedCell.NotComputable(emptyList(), PartgNotComputableReason.MISSING_IN_UNITS)),
            ) == "n. b.",
        )
        assertNull(partgConsolidatedCellReason(PartgConsolidatedCell.Value(1)))
        assertEquals(
            "3 Einheiten ohne Wert",
            resolvedAttributeText(
                partgConsolidatedCellReason(
                    PartgConsolidatedCell.NotComputable(listOf("a", "b", "c"), PartgNotComputableReason.MISSING_IN_UNITS),
                ).orEmpty(),
            ),
        )
        assertTrue(partgConsolidationVisible(PartyReportDomSupport.session()))
        assertFalse(
            partgConsolidationVisible(
                PartyReportDomSupport.session(
                    role = AccountRole.MEMBER,
                    chapterTreasurerScopes = listOf(RegionalChapterRefDto("c", "Nord")),
                ),
            ),
        )
        assertTrue(resolvedAttributeText(partgConsolidationCodeText("PAIR_EXISTS")).contains("bearbeiten Sie den vorhandenen Eintrag"))
        // an unknown code still gives a fixed text, never the code itself
        assertFalse(resolvedAttributeText(partgConsolidationCodeText("SOMETHING")).contains("SOMETHING"))
    }
}
