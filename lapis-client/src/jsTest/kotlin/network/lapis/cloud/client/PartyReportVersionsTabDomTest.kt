package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeadlineLevel
import network.lapis.cloud.shared.domain.PartgDiffCellDto
import network.lapis.cloud.shared.domain.PartgDiffMarker
import network.lapis.cloud.shared.domain.PartgDiffRowDto
import network.lapis.cloud.shared.domain.PartgDiffValueDto
import network.lapis.cloud.shared.domain.PartgDiffValueState
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgVersionDeletionCandidateDto
import network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgVersionStatus
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.291 (R6) -- the tab "Versionen und Freigabe" and the status line: the tab is the last one and invisible for a chapter treasurer, the status
 * line warns in a polite live region, a kept version says what it is (symbol AND word, footer text), the assurance needs the tick, the final mark needs
 * the typed number, the comparison says "Keine Unterschiede in den Zahlen." and filters, the deadline is loud with a word, and the deletion block is
 * for the ADMIN only.
 */
class PartyReportVersionsTabDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private val hash = "ab".repeat(32)
    private val at = LocalDateTime(2026, 11, 8, 10, 30)

    private fun version(
        id: String = "v1",
        number: Int = 1,
        status: PartgVersionStatus = PartgVersionStatus.FROZEN,
        scope: String = "ALL",
        comment: String? = null,
        thirdParty: Boolean = true,
    ) = PartgVersionDto(
        id = id,
        year = 2025,
        scopeKey = scope,
        versionNumber = number,
        status = status,
        frozenByName = "Dana Keller",
        frozenAt = at,
        comment = comment,
        contentHash = hash,
        byteSize = 1234,
        hasThirdPartyData = thirdParty,
        declaredByName = if (status >= PartgVersionStatus.DECLARED) "Dana Keller" else null,
        declaredAt = if (status >= PartgVersionStatus.DECLARED) at else null,
        declarationTemplateCode = if (status >= PartgVersionStatus.DECLARED) "ENTWURF-V1" else null,
        finalizedByName = if (status == PartgVersionStatus.FINAL) "Dana Keller" else null,
        finalizedAt = if (status == PartgVersionStatus.FINAL) at else null,
    )

    private fun deadline(
        level: PartgDeadlineLevel = PartgDeadlineLevel.NONE,
        days: Int = 200,
        confirmed: Boolean = false,
    ) = PartgDeadlineDto(
        year = 2025,
        deadline = LocalDate(2026, 9, 30),
        confirmed = confirmed,
        today = LocalDate(2026, 3, 15),
        level = level,
        daysUntil = days,
    )

    private fun overview(
        versions: List<PartgVersionDto> = emptyList(),
        status: PartgVersionStatus = PartgVersionStatus.DRAFT,
        deadline: PartgDeadlineDto = deadline(),
        deviation: PartgDraftDeviation = PartgDraftDeviation.SAME,
    ) = PartgVersionOverviewDto(year = 2025, versions = versions, yearStatus = status, deadline = deadline, draftDeviation = deviation)

    private suspend fun openTab(
        role: AccountRole = AccountRole.BOARD,
        block: suspend (HTMLElement) -> Unit,
    ) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "FREIGABE") }
        AppState.setSession(PartyReportDomSupport.session(role = role))
        val routes = PartyReportDomSupport.routes()
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
            mountedForm("party-report-versions") { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the versions block") { element().allOf("[data-partg-versions]").isNotEmpty() }
                block(element())
            }
        }
    }

    @Test
    fun theTab_isTheLastOne_andAChapterTreasurerNeverSeesIt() {
        assertEquals(PartyReportTab.FREIGABE, PARTG_TAB_DISPLAY_ORDER.last())
        assertTrue(PartyReportTab.FREIGABE in partgVisibleTabs(PartyReportDomSupport.session()))
        val treasurer =
            PartyReportDomSupport.session(
                role = AccountRole.MEMBER,
                chapterTreasurerScopes = listOf(RegionalChapterRefDto("0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90", "Nord")),
            )
        assertFalse(PartyReportTab.FREIGABE in partgVisibleTabs(treasurer))
        assertEquals(PartyReportTab.FREIGABE, partgTabOfQuery("?tab=FREIGABE"))
    }

    @Test
    fun theStatusLine_isOnEveryTab_andWarnsInAPoliteLiveRegionThatIsMountedEmpty(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview =
                overview(
                    versions = listOf(version()),
                    status = PartgVersionStatus.FROZEN,
                    deviation = PartgDraftDeviation.DIFFERS,
                    deadline = deadline(level = PartgDeadlineLevel.INFO, days = 80),
                )
            runCatching { window.localStorage.setItem("lapis.partyReport.tab", "OVERVIEW") }
            AppState.setSession(PartyReportDomSupport.session())
            val routes = PartyReportDomSupport.routes()
            withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
                mountedForm("party-report-status-line") { root, element ->
                    renderPartyReportScreen(root)
                    awaitUntil("the status line") { element().allOf("[data-partg-yearstatus]").single().shows("Festgehalten") }
                    val line = element().allOf("[data-partg-yearstatus]").single()
                    assertTrue(line.shows("Rechnungsjahr 2025"))
                    assertTrue(line.allOf(".fa-bookmark").isNotEmpty(), "a symbol next to the word")
                    assertTrue(line.shows("Frist in Sicht"), "the deadline level is a word too")
                    val warning = element().allOf("[data-partg-deviation]").single()
                    assertEquals("status", warning.getAttribute("role"))
                    assertEquals("polite", warning.getAttribute("aria-live"))
                    assertTrue(warning.shows("weicht von der letzten festgehaltenen Version ab"))
                }
            }
        }

    @Test
    fun aKeptVersion_isACardWithSymbolWordFooterAndTheThirdPartyNote(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview =
                overview(
                    versions =
                        listOf(
                            version(comment = "Stand Vorstand"),
                            version(id = "v0", number = 2, status = PartgVersionStatus.FINAL),
                        ),
                    status = PartgVersionStatus.FINAL,
                )
            openTab { page ->
                val cards = page.allOf("[data-partg-version]")
                assertEquals(2, cards.size)
                val first = cards.first()
                assertTrue(first.shows("Version 1"))
                assertTrue(first.shows("Festgehalten"))
                assertTrue(first.allOf(".fa-bookmark").isNotEmpty())
                assertTrue(first.shows("Stand Vorstand"))
                assertTrue(first.shows("Enthält Namen und Anschriften aus Ausweis B"))
                assertTrue(first.shows("Prüfsumme abababababab"), "the first 12 characters of the checksum")
                assertTrue(first.shows("Entwurf als Arbeitshilfe, nicht eingereicht"))
                val final = cards[1]
                assertTrue(final.allOf(".fa-lock").isNotEmpty())
                assertTrue(final.shows("Intern final, nicht eingereicht"))
                assertFalse(page.shows("eingereicht und"), "never a claim that anything was filed")
            }
        }

    @Test
    fun keepingAVersion_announcesItInAPoliteLiveRegion_andSendsScopeAndComment(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview()
            PartyReportDomSupport.versionAnswer = version(number = 3)
            openTab { page ->
                page.buttonNamed("Version festhalten").click()
                awaitUntil("the dialog") { lastOpenModal().shows("Der aktuelle Arbeitsstand wird als nummerierte Version gespeichert") }
                val modal = lastOpenModal()
                (modal.controlOf("Kommentar") as HTMLInputElement).also {
                    it.value = "Mein Stand"
                    it.dispatchEvent(
                        org.w3c.dom.events
                            .Event("input"),
                    )
                }
                modal.buttonNamed("Festhalten").click()
                awaitUntil("the call") { PartyReportDomSupport.versionWrites.isNotEmpty() }
                val call = PartyReportDomSupport.versionWrites.single().second
                assertEquals(2025, call.rpcParam(0).unsafeCast<Int>())
                assertEquals("ALL", call.rpcParam(1).unsafeCast<String>())
                assertEquals("Mein Stand", call.rpcParam(2).unsafeCast<String>())
                awaitUntil("the announcement") { page.allOf("[aria-live=polite]").any { it.shows("Version 3 wurde festgehalten.") } }
            }
        }

    @Test
    fun theAssurance_needsTheTick_showsTheFixedText_andSendsTheChecksumOnce(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(versions = listOf(version()), status = PartgVersionStatus.FROZEN)
            PartyReportDomSupport.versionAnswer = version(status = PartgVersionStatus.DECLARED)
            openTab { page ->
                page.buttonNamed("Versichern").click()
                awaitUntil("the dialog") { lastOpenModal().shows("Version 1 versichern") }
                val modal = lastOpenModal()
                assertTrue(modal.allOf("[data-partg-declaration-text]").single().shows("keine qualifizierte"))
                assertTrue(modal.shows("Rechtlich nicht geprüft"))
                val confirm = modal.buttonNamed("Versichern")
                assertEquals("true", confirm.getAttribute("aria-disabled"), "inactive without the tick")
                assertTrue(confirm.hasAttribute("disabled"))
                (modal.allOf("input[type=checkbox]").single() as HTMLInputElement).click()
                awaitUntil("the button is active") { !modal.buttonNamed("Versichern").hasAttribute("disabled") }
                modal.buttonNamed("Versichern").click()
                awaitUntil("the call") { PartyReportDomSupport.versionWrites.isNotEmpty() }
                val call = PartyReportDomSupport.versionWrites.single().second
                assertEquals("v1", call.rpcParam(0).unsafeCast<String>())
                assertEquals(hash, call.rpcParam(1).unsafeCast<String>())
                assertEquals(true, call.rpcParam(2).unsafeCast<Boolean>())
            }
        }

    @Test
    fun theFinalMark_needsTheTypedNumber_andSaysItIsInternal(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview =
                overview(versions = listOf(version(number = 7, status = PartgVersionStatus.DECLARED)), status = PartgVersionStatus.DECLARED)
            PartyReportDomSupport.versionAnswer = version(number = 7, status = PartgVersionStatus.FINAL)
            openTab { page ->
                page.buttonNamed("Final setzen").click()
                awaitUntil("the dialog") { lastOpenModal().shows("Version 7 intern final setzen") }
                val modal = lastOpenModal()
                assertTrue(modal.shows("nicht rückgängig"))
                assertTrue(modal.shows("Der Bericht ist damit nicht eingereicht"))
                val input = modal.allOf("input").first { it.getAttribute("type") != "checkbox" } as HTMLInputElement
                val confirm = modal.allOf("button").first { it.id == "partg-finalize-confirm" }
                assertTrue(confirm.hasAttribute("disabled"))
                input.value = "6"
                input.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                awaitUntil("still inactive for a wrong number") { confirm.hasAttribute("disabled") }
                input.value = "7"
                input.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                awaitUntil("active for the right number") { !confirm.hasAttribute("disabled") }
                confirm.click()
                awaitUntil("the call") { PartyReportDomSupport.versionWrites.isNotEmpty() }
                val call = PartyReportDomSupport.versionWrites.single().second
                assertEquals(7, call.rpcParam(1).unsafeCast<Int>())
                assertEquals(hash, call.rpcParam(2).unsafeCast<String>())
            }
        }

    @Test
    fun anOverdueDeadline_isAWordAndASymbol_andTheTemplateIsOnlyATextToCopy(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(deadline = deadline(level = PartgDeadlineLevel.DANGER, days = -12))
            openTab { page ->
                val block = page.allOf("[data-partg-year-block]").single().parentElement as HTMLElement
                assertTrue(block.shows("seit 12 Tagen überschritten"))
                assertTrue(block.shows("Vorschlag, nicht bestätigt"))
                assertTrue(block.shows("Frist überschritten"))
                assertTrue(block.allOf(".fa-hourglass-end").isNotEmpty())
                val template = page.allOf("[data-partg-extension]").single()
                assertTrue(template.shows("Antrag auf Verlängerung der Frist"))
                // no recipient, nothing is stored or sent: no input besides the read-only text
                assertEquals(0, template.allOf("input").size)
                assertEquals("readonly", template.allOf("textarea").single().getAttribute("readonly"))
            }
        }

    @Test
    fun theComparison_showsOnlyDifferences_saysSoWhenThereAreNone_andNbIsNeverZero(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview =
                overview(
                    versions = listOf(version(id = "v2", number = 2), version(id = "v1", number = 1)),
                    status = PartgVersionStatus.FROZEN,
                )
            PartyReportDomSupport.versionDiff =
                PartgVersionDiffDto(
                    versionIdA = "v1",
                    versionIdB = "v2",
                    versionNumberA = 1,
                    versionNumberB = 2,
                    year = 2025,
                    comparable = true,
                    truncated = false,
                    rows =
                        listOf(
                            PartgDiffRowDto(
                                rowKind = "UNIT",
                                rowKey = "FEDERAL",
                                label = "Bundesverband",
                                marker = PartgDiffMarker.CHANGED,
                                cells =
                                    listOf(
                                        PartgDiffCellDto(
                                            code = "E01",
                                            before = PartgDiffValueDto(PartgDiffValueState.VALUE, 10_000),
                                            after = PartgDiffValueDto(PartgDiffValueState.NOT_ENTERED),
                                            deltaCents = null,
                                            marker = PartgDiffMarker.CHANGED,
                                        ),
                                    ),
                            ),
                            PartgDiffRowDto("UNIT", "STATE:x", "Landesverband Nord", PartgDiffMarker.SAME, emptyList()),
                        ),
                )
            openTab { page ->
                page.buttonNamed("Vergleichen").click()
                awaitUntil("the table") { page.allOf("[data-partg-compare]").isNotEmpty() && page.allOf("tbody tr").isNotEmpty() }
                val rows = page.allOf("tbody tr")
                assertEquals(1, rows.size, "only the row with a difference")
                assertTrue(rows.single().shows("100,00"))
                assertTrue(rows.single().shows("n. e."))
                assertTrue(rows.single().shows("n. b."), "a missing side has no difference, not 0")
                (page.allOf("#partg-compare-all input").firstOrNull() ?: page.allOf("input[type=checkbox]").last()).click()
                awaitUntil("all rows") { page.allOf("tbody tr").size == 2 }
                assertTrue(page.shows("unverändert"))
            }
        }

    @Test
    fun theComparisonOfTwoEqualVersions_saysThereAreNoDifferences(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview =
                overview(versions = listOf(version(id = "v2", number = 2), version(id = "v1", number = 1)))
            PartyReportDomSupport.versionDiff =
                PartgVersionDiffDto(
                    "v1",
                    "v2",
                    1,
                    2,
                    2025,
                    listOf(PartgDiffRowDto("UNIT", "FEDERAL", "Bundesverband", PartgDiffMarker.SAME, emptyList())),
                    truncated = false,
                    comparable = true,
                )
            openTab { page ->
                page.buttonNamed("Vergleichen").click()
                awaitUntil("the notice") { page.allOf("[data-partg-no-diff]").isNotEmpty() }
                assertTrue(page.allOf("[data-partg-no-diff]").single().shows("Keine Unterschiede in den Zahlen."))
                assertEquals(0, page.allOf("tbody tr").size)
            }
        }

    @Test
    fun theDeletionBlock_isForTheAdminOnly_andNamesWhatWouldBeDeleted(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview()
            PartyReportDomSupport.deletionPreview =
                PartgVersionDeletionPreviewDto(
                    olderThanYears = 10,
                    candidates = listOf(PartgVersionDeletionCandidateDto("x1", 2005, "ALL", 2, at, finalized = false)),
                    truncated = false,
                )
            openTab(role = AccountRole.BOARD) { page ->
                assertEquals(0, page.allOf("[data-partg-deletion]").size, "the board does not see it")
            }
            openTab(role = AccountRole.ADMIN) { page ->
                assertEquals(1, page.allOf("[data-partg-deletion]").size)
                page.buttonNamed("Vorschau").click()
                awaitUntil("the preview") { page.shows("1 Versionen würden gelöscht") }
                assertTrue(page.shows("Jahr 2005, ALL, Version 2"))
                assertTrue(page.shows("rechtlich nicht geprüft"), "the retention period is an assumption and says so")
            }
        }

    @Test
    fun theLogic_isPureAndHasFixedTexts() {
        assertEquals("30.09.2026", partgGermanDate(LocalDate(2026, 9, 30)))
        assertEquals(
            "Frist: 30.09.2026 (Vorschlag, nicht bestätigt), noch 200 Tage",
            resolvedAttributeText(partgDeadlineText(deadline())),
        )
        assertTrue(resolvedAttributeText(partgDeadlineText(deadline(days = -3, confirmed = true))).contains("seit 3 Tagen überschritten"))
        assertEquals(null, partgDeadlineLook(PartgDeadlineLevel.NONE))
        assertEquals("Frist bald", resolvedAttributeText(partgDeadlineLook(PartgDeadlineLevel.WARNING)!!.word))
        assertEquals("fas fa-hourglass-end", partgDeadlineLook(PartgDeadlineLevel.DANGER)!!.icon)
        assertEquals("fas fa-lock", partgVersionStatusLook(PartgVersionStatus.FINAL).icon)
        assertEquals("fas fa-user-shield", partgVersionStatusLook(PartgVersionStatus.DECLARED).icon)
        assertEquals(null, partgDeviationText(PartgDraftDeviation.SAME))
        assertEquals(null, partgDeviationText(PartgDraftDeviation.NOT_CHECKED))
        assertTrue(partgDeviationText(PartgDraftDeviation.UNKNOWN_FORMAT)!!.isNotEmpty())
        val v = version(number = 4)
        assertTrue(partgTypedNumberMatches(" 4 ", v))
        assertFalse(partgTypedNumberMatches("5", v))
        assertFalse(partgTypedNumberMatches("vier", v))
        assertFalse(partgTypedNumberMatches("", v))
        assertEquals("–", partgDiffValueText(PartgDiffValueDto(PartgDiffValueState.ABSENT)))
    }
}
