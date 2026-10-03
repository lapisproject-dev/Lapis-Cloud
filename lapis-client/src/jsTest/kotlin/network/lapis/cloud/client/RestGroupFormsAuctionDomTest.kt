package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuctionService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the auction: "Neues Angebot" is one collapsed form behind a page header button. The own LTR balance and the fee
 * line (design decision D3) live INSIDE the opened form, not on the closed page. The Tier 1 confirm dialog before the fee is booked stays.
 */
class RestGroupFormsAuctionDomTest {
    private val formId = "auction-listing-create"
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"

    private val member =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Mira Mitglied",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun auction(title: String) =
        AuctionDto(
            id = "a1",
            title = title,
            description = "Beschreibung",
            sellerMemberId = "member-2",
            sellerDisplayName = "Sven Seller",
            startingBidLtr = 5.0.toDecimal(),
            buyNowPriceLtr = null,
            status = AuctionStatus.OPEN,
            effectiveStatus = AuctionStatus.OPEN,
            currentPriceLtr = null,
            currentLeaderDisplayName = null,
            leaderIsMe = false,
            bidCount = 0,
            winnerMemberId = null,
            winnerDisplayName = null,
            finalPriceLtr = null,
            listingFeeLtr = 0.01.toDecimal(),
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            endsAt = LocalDateTime(2026, 1, 2, 10, 0),
            settledAt = null,
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.fillValidListing() {
        typeInto("Titel", "Fahrrad")
        typeInto("Beschreibung", "Gut erhalten")
        typeInto("Startpreis (LTR)", "5")
        typeInto("Laufzeit in Stunden", "24")
    }

    @Test
    fun oneButtonInTheHeader_balanceAndFeeLiveOnlyInsideTheOpenedForm(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val list = routeOf { rpcService<IAuctionService>().listAuctions(null) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(AuctionDto.serializer()), listOf(auction("${KV_MARKER}Fahrrad alt"))))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-auction-closed") { root, element ->
                    renderAuctionScreen(root)
                    awaitUntil("the auction card") { element().shows("Fahrrad alt") }
                    val screen = element()
                    assertEquals(listOf("Neues Angebot"), screen.createButtonLabels())
                    assertFalse(screen.hostOpen(formId), "collapsed after the load")
                    assertFalse(screen.shows("feste Gebühr"), "the fee line is not on the closed page")
                    assertFalse(screen.shows("Ihr LTR-Guthaben"), "the balance strip is not on the closed page")
                    assertFalse(screen.shows(KV_MARKER), "a forged i18n marker in a title never reaches the DOM")

                    val host = openCreateFormHost(screen, formId)
                    assertTrue(host.shows("feste Gebühr"), "the fee line is the first thing inside the opened form")
                    awaitUntil("the focus is in the first field") { document.activeElement is HTMLInputElement }
                    pressEscape(host)
                    awaitUntil("closed without a question") { !screen.hostOpen(formId) }
                    assertFalse(screen.shows("feste Gebühr"))
                }
            }
        }

    @Test
    fun aTypedForm_asksBeforeDiscarding_andABlankSubmitStaysOpenWithItsMessage(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val list = routeOf { rpcService<IAuctionService>().listAuctions(null) }
            val create =
                routeOf { rpcService<IAuctionService>().createListing(CreateAuctionListingInput("t", "d", 1.0.toDecimal(), null, 1)) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(AuctionDto.serializer()), emptyList()))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-auction-typed") { root, element ->
                    renderAuctionScreen(root)
                    awaitUntil("the empty list") { element().shows("Noch keine Auktionen vorhanden") }
                    val screen = element()
                    val host = openCreateFormHost(screen, formId)
                    host.buttonNamed("Angebot erstellen").click()
                    awaitUntil("the message") { host.shows("Bitte Titel, Beschreibung und einen positiven Startpreis") }
                    assertTrue(calls.toRoute(create).isEmpty(), "nothing is sent for a blank form")
                    assertTrue(screen.hostOpen(formId))

                    host.typeInto("Titel", "Fahrrad")
                    pressEscape(host)
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(formId), "'Weiter bearbeiten' keeps the typed form")
                    host.buttonNamed("Abbrechen").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("closed after discarding") { !screen.hostOpen(formId) }
                }
            }
        }

    @Test
    fun aConfirmedListing_foldsTheFormBack_andReloadsTheAuctions_aConflictKeepsItOpen(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val list = routeOf { rpcService<IAuctionService>().listAuctions(null) }
            val create =
                routeOf { rpcService<IAuctionService>().createListing(CreateAuctionListingInput("t", "d", 1.0.toDecimal(), null, 1)) }
            var conflictNext = true
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(AuctionDto.serializer()), emptyList()))
                        request.rpcRoute == create ->
                            if (conflictNext) {
                                conflictNext = false
                                serviceExceptionResult(request.json.id as Int, conflict)
                            } else {
                                request.answerWith(jsonOf(AuctionDto.serializer(), auction("Fahrrad")))
                            }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-auction-save") { root, element ->
                    renderAuctionScreen(root)
                    awaitUntil("the empty list") { element().shows("Noch keine Auktionen vorhanden") }
                    val screen = element()
                    val host = openCreateFormHost(screen, formId)
                    host.fillValidListing()

                    host.buttonNamed("Angebot erstellen").click()
                    awaitUntil("the confirm dialog") { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Erstellen").click()
                    awaitUntil("the first write was attempted") { calls.toRoute(create).size == 1 }
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertTrue(screen.hostOpen(formId), "a server conflict (auction switched off) never folds the form back")

                    val listCallsBefore = calls.toRoute(list).size
                    host.buttonNamed("Angebot erstellen").click()
                    awaitUntil("the confirm dialog again") { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Erstellen").click()
                    awaitUntil("the second write was made") { calls.toRoute(create).size == 2 }
                    awaitUntil("the form folded back") { !screen.hostOpen(formId) }
                    awaitUntil("the auctions were reloaded") { calls.toRoute(list).size > listCallsBefore }
                    assertEquals(1, screen.createButtonLabels().size, "still exactly one button")
                }
            }
        }
}
