package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.188 (Part 2): real screens of the four sidebar groups in a mounted root, against a stub that answers every RPC with `null`
 * (the header is built before any load, so the intro is there synchronously). Pins:
 *  - the audit log carries its GoBD statement in a CLOSED "Mehr" part, once, and hiding removes the whole section;
 *  - one screen of each group (Wirtschaft, Finanzen, Verwaltung, System) shows its intro without a "Mehr" part;
 *  - a restriction notice stays in the body when the intro is hidden (the use-of-funds banner of the compliance reports);
 *  - the hand-built lines that moved into the registry are gone (Postversand, Stream-Ziele): the sentence shows once, not twice;
 *  - a detail view (the honors of ONE member) shows no intro.
 */
class PageIntroPart2DomTest {
    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    @BeforeTest
    fun setUp() {
        PageIntroStorage.expandAll()
        AppState.setSession(admin)
    }

    @AfterTest
    fun tearDown() {
        PageIntroStorage.expandAll()
        AppState.setSession(null)
    }

    private val answerNull: (RecordedRequest) -> StubResponse = { request ->
        if (request.isRpc) request.answerWith("null") else StubResponse()
    }

    private fun HTMLElement.intro(): HTMLElement? = querySelector("section.page-intro") as HTMLElement?

    private fun HTMLElement.introText(): String =
        (querySelector("section.page-intro .page-intro__text") as HTMLElement).textContent.orEmpty()

    private fun HTMLElement.occurrences(fragment: String): Int = textContent.orEmpty().split(fragment).size - 1

    private fun screen(
        id: String,
        render: (io.kvision.panel.SimplePanel) -> Unit,
        check: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            withFetchStub(respond = answerNull) {
                mountedForm(id) { root, element ->
                    render(root)
                    check(element)
                }
            }
        }

    @Test
    fun auditLog_hasAClosedMehrPart_withTheGobdStatementOnce_andCollapsingRemovesEverything(): Promise<Unit> =
        screen("p2-audit-log", { renderAuditLogScreen(it) }) { element ->
            val intro = assertNotNull(element().intro(), "the audit log has an intro")
            assertFalse(element().introText().contains("GoBD"), "the headline text carries no legal vocabulary")
            assertTrue(element().introText().contains("nur lesen"), element().introText())
            val details = assertNotNull(intro.querySelector("details.page-intro__more") as HTMLElement?)
            assertFalse(details.hasAttribute("open"), "closed on every render")
            assertEquals("Mehr", (details.querySelector("summary") as HTMLElement).textContent?.trim())
            assertTrue(details.textContent.orEmpty().contains("GoBD-revisionssicheres Protokoll"))
            assertEquals(1, element().occurrences("GoBD-revisionssicheres Protokoll"), "the statement shows once -- no hand-built twin")
            (intro.querySelector("button.page-intro__collapse") as HTMLElement).click()
            delay(120)
            assertNotNull(element().intro(), "the section stays as a toggle")
            assertNull(element().querySelector(".page-intro__text"))
            assertNull(element().querySelector("details"), "the details part goes with the section")
            assertEquals(0, element().occurrences("GoBD-revisionssicheres Protokoll"))
        }

    @Test
    fun oneScreenOfEachGroup_showsItsIntro_withoutAMehrPart(): Promise<Unit> =
        formTest {
            val cases: List<Triple<String, (io.kvision.panel.SimplePanel) -> Unit, String>> =
                listOf(
                    Triple("Wirtschaft", { renderCrowdfundingScreen(it) }, "Projekte mit einem LTR-Einsatz"),
                    Triple("Finanzen", { renderPaymentTransactionsScreen(it) }, "eingegangene Zahlungen"),
                    Triple("Verwaltung", { renderEventRoomsScreen(it) }, "Räume für Veranstaltungen"),
                    Triple("System", { renderTravelExpenseSettingsScreen(it) }, "Regeln für Reisekosten"),
                )
            cases.forEach { (group, render, fragment) ->
                withFetchStub(respond = answerNull) {
                    mountedForm("p2-group-${group.lowercase()}") { root, element ->
                        render(root)
                        assertNotNull(element().intro(), "$group: the screen has an intro")
                        assertTrue(element().introText().contains(fragment), "$group: ${element().introText()}")
                        assertNull(element().querySelector("details"), "$group: no Mehr part")
                        assertNotNull(element().querySelector("section.page-intro[aria-labelledby=$PAGE_TITLE_ID]"))
                    }
                }
            }
        }

    @Test
    fun complianceReports_keepTheUseOfFundsBanner_whenTheIntroIsHidden(): Promise<Unit> =
        formTest {
            PageIntroStorage.collapse("compliance-reports")
            withFetchStub(respond = answerNull) {
                mountedForm("p2-compliance") { root, element ->
                    renderNonprofitComplianceReportsScreen(root)
                    assertNull(element().querySelector(".page-intro__text"), "the intro is collapsed")
                    element().buttonNamed("Mittelverwendungsrechnung").click()
                    awaitUntil("the use-of-funds banner") { element().querySelector(".alert.alert-warning") != null }
                    assertNotNull(element().querySelector(".alert.alert-warning"), "a restriction notice cannot be hidden with the intro")
                }
            }
        }

    @Test
    fun postalMail_showsTheNewSentenceOnce_andTheOldHandBuiltLineIsGone(): Promise<Unit> =
        screen("p2-postal", { renderPostalMailScreen(it) }) { element ->
            assertEquals(1, element().occurrences("welche Briefe bereits verschickt wurden"))
            assertEquals(0, element().occurrences("Protokoll aller bisherigen postalischen"))
            assertEquals(0, element().occurrences("rein informativ"))
        }

    @Test
    fun streamDestinations_showTheShortSentenceOnce_withoutGenderedPairs(): Promise<Unit> =
        screen("p2-stream", { renderConferenceStreamDestinationsScreen(it) }) { element ->
            assertEquals(1, element().occurrences("externen Stream-Ziele"))
            assertEquals(0, element().occurrences("Moderatorinnen"))
            assertTrue(element().introText().contains("Stream-Schlüssel"))
        }

    @Test
    fun honorsOfOneMember_isADetailView_withoutAnIntro_theFullListHasOne(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answerNull) {
                mountedForm("p2-honors-list") { root, element ->
                    renderMemberHonorsScreen(root, null)
                    assertNotNull(element().intro(), "the full list has the intro")
                }
                mountedForm("p2-honors-one") { root, element ->
                    renderMemberHonorsScreen(root, "member-42")
                    assertNull(element().intro(), "narrowed to one member the page is a detail view")
                }
            }
        }

    @Test
    fun collapsedKeyOfAPart2View_isTheSameStorageContractAsPart1() {
        PageIntroStorage.collapse("audit-log")
        assertEquals("collapsed", localStorage.getItem("lapis.pageIntro.v2.audit-log"))
        assertTrue(PageIntroStorage.isCollapsed("audit-log"))
    }
}
