package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeadlineLevel
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgReviewerEventDto
import network.lapis.cloud.shared.domain.PartgReviewerEventKind
import network.lapis.cloud.shared.domain.PartgReviewerInvitationCreatedDto
import network.lapis.cloud.shared.domain.PartgReviewerInvitationDto
import network.lapis.cloud.shared.domain.PartgReviewerInvitationStatus
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgVersionStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.310 (R6b) -- the board's section "Lesezugang für die Prüfung": the invitation dialog (all versions ticked, no version = inactive button and a
 * message, an invalid address is refused, the link is shown ONCE with the warning above the close button and is gone from the page after closing),
 * the list (status as a word, versions, the usage list, a deleted address, never a token) and the confirmation of "Widerrufen".
 */
class PartyReportReviewerDomTest {
    private val hash = "ab".repeat(32)
    private val at = LocalDateTime(2026, 11, 8, 10, 30)
    private val link = "https://example.org/pruefung#t=TOKEN-SHOWN-ONCE-0123456789abcdefghij"

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun version(
        id: String,
        number: Int,
        scope: String = "ALL",
    ) = PartgVersionDto(
        id = id,
        year = 2025,
        scopeKey = scope,
        versionNumber = number,
        status = PartgVersionStatus.FROZEN,
        frozenByName = "Dana Keller",
        frozenAt = at,
        comment = null,
        contentHash = hash,
        byteSize = 1234,
        hasThirdPartyData = false,
    )

    private fun overview(versions: List<PartgVersionDto>) =
        PartgVersionOverviewDto(
            year = 2025,
            versions = versions,
            yearStatus = PartgVersionStatus.FROZEN,
            deadline =
                PartgDeadlineDto(
                    year = 2025,
                    deadline = LocalDate(2026, 9, 30),
                    confirmed = false,
                    today = LocalDate(2026, 3, 15),
                    level = PartgDeadlineLevel.NONE,
                    daysUntil = 200,
                ),
            draftDeviation = PartgDraftDeviation.SAME,
        )

    private fun invitation(
        id: String = "inv-1",
        email: String? = "pruefer@example.org",
        status: PartgReviewerInvitationStatus = PartgReviewerInvitationStatus.ACTIVE,
        events: List<PartgReviewerEventDto> = emptyList(),
    ) = PartgReviewerInvitationDto(
        id = id,
        year = 2025,
        email = email,
        versionNumbers = listOf(1, 2),
        versionIds = listOf("v1", "v2"),
        createdAt = at,
        expiresAt = LocalDateTime(2026, 12, 1, 10, 0),
        status = status,
        lastUsedAt = null,
        events = events,
    )

    private suspend fun openSection(block: suspend (HTMLElement) -> Unit) {
        runCatching { window.localStorage.setItem("lapis.partyReport.tab", "FREIGABE") }
        AppState.setSession(PartyReportDomSupport.session())
        val routes = PartyReportDomSupport.routes()
        withFetchStub(respond = { request -> PartyReportDomSupport.answer(routes, request, 2025) }) {
            mountedForm("party-report-reviewer") { root, element ->
                renderPartyReportScreen(root)
                awaitUntil("the reviewer section") { element().allOf("[data-partg-reviewer]").isNotEmpty() }
                block(element())
            }
        }
    }

    private suspend fun openInviteDialog(page: HTMLElement): HTMLElement {
        awaitUntil("the invite button enabled") { !(page.buttonNamed("Prüfer einladen") as org.w3c.dom.HTMLButtonElement).disabled }
        page.buttonNamed("Prüfer einladen").click()
        awaitUntil("the dialog") { lastOpenModal().shows("Der Prüfer bekommt Lesezugang") }
        return lastOpenModal()
    }

    @Test
    fun theInvitationDialog_ticksEveryVersion_andNeedsOne(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview =
                overview(listOf(version("v1", 1), version("v2", 2), version("u1", 3, scope = "FEDERAL")))
            openSection { page ->
                val modal = openInviteDialog(page)
                val boxes = modal.allOf("input[type=checkbox]").map { it as HTMLInputElement }
                assertEquals(2, boxes.size, "only the versions of the whole party")
                assertTrue(boxes.all { it.checked })
                assertTrue(modal.shows("Berichtsjahr: 2025"))
                assertTrue(modal.shows("14 Tage"))
                assertFalse((modal.buttonNamed("Einladung erstellen") as org.w3c.dom.HTMLButtonElement).disabled)
                boxes.forEach { it.click() }
                awaitUntil("the message and the inactive button") {
                    modal.shows("Bitte wählen Sie mindestens eine Version aus.") &&
                        (modal.buttonNamed("Einladung erstellen") as org.w3c.dom.HTMLButtonElement).disabled
                }
                boxes.first().click()
                awaitUntil(
                    "the button active again",
                ) { !(modal.buttonNamed("Einladung erstellen") as org.w3c.dom.HTMLButtonElement).disabled }
            }
        }

    @Test
    fun anInvalidAddress_isRefused_andNothingIsSent(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(listOf(version("v1", 1)))
            openSection { page ->
                val modal = openInviteDialog(page)
                modal.typeInto("E-Mail-Adresse des Prüfers", "kein-at")
                modal.buttonNamed("Einladung erstellen").click()
                awaitUntil("the message") { modal.shows("Bitte geben Sie eine gültige E-Mail-Adresse ein.") }
                assertTrue(PartyReportDomSupport.reviewerWrites.isEmpty())
            }
        }

    @Test
    fun theLink_isShownOnce_withTheWarningAboveClose_andIsGoneAfterClosing(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(listOf(version("v1", 1), version("v2", 2)))
            PartyReportDomSupport.reviewerCreated = PartgReviewerInvitationCreatedDto(invitation = invitation(), link = link)
            openSection { page ->
                val modal = openInviteDialog(page)
                modal.typeInto("E-Mail-Adresse des Prüfers", "pruefer@example.org")
                modal.buttonNamed("Einladung erstellen").click()
                awaitUntil("the call") { PartyReportDomSupport.reviewerWrites.isNotEmpty() }
                val call = PartyReportDomSupport.reviewerWrites.single().second
                assertEquals(2025, call.rpcParam(0).unsafeCast<Int>())
                assertEquals("""["v1","v2"]""", JSON.stringify(call.rpcParam(1)))
                assertEquals(14, call.rpcParam(2).unsafeCast<Int>())
                assertEquals("pruefer@example.org", call.rpcParam(3).unsafeCast<String>())
                awaitUntil("the link") { modal.allOf("input").any { (it as HTMLInputElement).value == link } }
                val text = modal.textContent.orEmpty()
                val warning = text.indexOf("Der Link wird nur jetzt angezeigt. Bitte geben Sie ihn dem Prüfer selbst weiter.")
                val close = text.lastIndexOf("Schließen")
                assertTrue(warning in 0 until close, "the warning stands before the close button")
                assertTrue(modal.allOf("input").first { (it as HTMLInputElement).value == link }.hasAttribute("readonly"))
                modal.buttonNamed("Schließen").click()
                awaitUntil("the link leaves the page") {
                    document.body!!.allOf("input").none { (it as HTMLInputElement).value == link } &&
                        !document.body!!
                            .textContent
                            .orEmpty()
                            .contains("TOKEN-SHOWN-ONCE")
                }
            }
        }

    @Test
    fun withoutAWholePartyVersion_theInviteButtonIsInactive(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(listOf(version("u1", 1, scope = "FEDERAL")))
            openSection { page ->
                val button = page.buttonNamed("Prüfer einladen") as org.w3c.dom.HTMLButtonElement
                assertTrue(button.disabled)
                assertTrue(page.shows("Zum Einladen wird mindestens eine festgehaltene Version der Gesamtpartei gebraucht."))
            }
        }

    @Test
    fun theList_showsStatusAsWord_versions_theUsageList_andNeverAToken(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(listOf(version("v1", 1), version("v2", 2)))
            PartyReportDomSupport.reviewerInvitations =
                listOf(
                    invitation(
                        events =
                            listOf(
                                PartgReviewerEventDto(PartgReviewerEventKind.SESSION_OPENED, at),
                                PartgReviewerEventDto(PartgReviewerEventKind.INVITATION_CREATED, at),
                            ),
                    ),
                    invitation(id = "inv-2", email = null, status = PartgReviewerInvitationStatus.REVOKED),
                )
            openSection { page ->
                awaitUntil("the cards") { page.allOf("[data-partg-reviewer-invitation]").size == 2 }
                val first = page.allOf("[data-partg-reviewer-invitation]").first()
                assertTrue(first.shows("pruefer@example.org"))
                assertTrue(first.shows("Version 1, 2"))
                assertTrue(first.shows("Gültig"))
                assertTrue(first.shows("Ereignisse (2)"))
                assertTrue(first.shows("Sitzung geöffnet"))
                assertTrue(first.shows("Einladung angelegt"))
                assertTrue(first.shows("Noch nicht genutzt"))
                val second = page.allOf("[data-partg-reviewer-invitation]")[1]
                assertTrue(second.shows("E-Mail-Adresse gelöscht"))
                assertTrue(second.shows("Widerrufen"))
                assertFalse(
                    second.allOf("button").any { it.textContent?.trim() == "Widerrufen" },
                    "an ended invitation cannot be revoked again",
                )
                assertFalse(page.shows("pruefung#t="), "no link in the list")
            }
        }

    @Test
    fun revoking_asksFirst_andSaysItEndsRunningSessions(): Promise<Unit> =
        formTest {
            PartyReportDomSupport.resetR4()
            PartyReportDomSupport.versionOverview = overview(listOf(version("v1", 1)))
            PartyReportDomSupport.reviewerInvitations = listOf(invitation())
            PartyReportDomSupport.reviewerRevoked = invitation(status = PartgReviewerInvitationStatus.REVOKED)
            openSection { page ->
                awaitUntil("the card") { page.allOf("[data-partg-reviewer-invitation]").isNotEmpty() }
                page
                    .allOf("[data-partg-reviewer-invitation]")
                    .first()
                    .buttonNamed("Widerrufen")
                    .click()
                awaitUntil("the confirmation") { lastOpenModal().shows("Der Zugang endet sofort, auch laufende Sitzungen.") }
                assertTrue(PartyReportDomSupport.reviewerWrites.isEmpty(), "nothing is sent before the confirmation")
                lastOpenModal().buttonNamed("Widerrufen").click()
                awaitUntil("the call") { PartyReportDomSupport.reviewerWrites.isNotEmpty() }
                assertEquals(
                    "inv-1",
                    PartyReportDomSupport.reviewerWrites
                        .single()
                        .second
                        .rpcParam(0)
                        .unsafeCast<String>(),
                )
            }
        }
}
