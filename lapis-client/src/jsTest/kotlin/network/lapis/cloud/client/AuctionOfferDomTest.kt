package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionOfferDto
import network.lapis.cloud.shared.domain.AuctionOfferStatus
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuctionService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.321 (Fiat wave b) -- the price-offer controls on the auction card: who sees which button, the dialog with its non-binding notice, the
 * seller's list with the tier-2 confirmation for the acceptance, the buyer's counter-offer, "Auktion beendet" versus "Abgelaufen", and that the
 * word "Angebot" never appears for a price offer.
 */
class AuctionOfferDomTest {
    private val member =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Mira Mitglied",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun auction(
        seller: Boolean = false,
        currency: String = "EUR",
        offersEnabled: Boolean = true,
        hasOpen: Boolean = false,
        blocked: Boolean = false,
        openForSeller: Int? = null,
    ) = AuctionDto(
        id = "a1",
        title = "Fahrrad",
        description = "Beschreibung",
        sellerMemberId = if (seller) "member-1" else "member-2",
        sellerDisplayName = "Sven Seller",
        startingBidLtr = if (currency == "LTR") 1.0.toDecimal() else null,
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
        listingFeeLtr = 0.0.toDecimal(),
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        endsAt = LocalDateTime(2099, 1, 2, 10, 0),
        settledAt = null,
        currency = currency,
        startingPriceMinor = if (currency == "LTR") null else 1000,
        buyNowPriceMinor = if (currency == "LTR") null else 9000,
        callerIsSeller = seller,
        offersEnabled = offersEnabled,
        openOfferCountForSeller = openForSeller,
        callerHasOpenOffer = hasOpen,
        callerOfferBlocked = blocked,
    )

    private fun offer(
        id: String,
        status: AuctionOfferStatus = AuctionOfferStatus.OFFERED,
        fromSeller: Boolean = false,
        amount: Long = 4000,
        endedByAuction: Boolean = false,
        below: Boolean = false,
        asSeller: Boolean = false,
    ) = AuctionOfferDto(
        id = id,
        auctionId = "a1",
        auctionTitle = "Fahrrad",
        fromSeller = fromSeller,
        parentOfferId = if (fromSeller) "p" else null,
        amountMinor = amount,
        currency = "EUR",
        status = status,
        endedByAuction = endedByAuction,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        expiresAt = LocalDateTime(2099, 1, 2, 10, 0),
        decidedAt = null,
        buyerDisplayName = if (asSeller) "Bea Käuferin" else null,
        belowCurrentPrice = below,
        callerCanAccept = status == AuctionOfferStatus.OFFERED && (asSeller != fromSeller),
        callerCanDecline = status == AuctionOfferStatus.OFFERED && (asSeller != fromSeller),
        callerCanCounter = status == AuctionOfferStatus.OFFERED && asSeller && !fromSeller,
        callerCanWithdraw = status == AuctionOfferStatus.OFFERED && (asSeller == fromSeller),
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hasButton(text: String): Boolean = allOf("button").any { it.textContent?.trim() == text }

    @Test
    fun theProposeButton_isOnlyThereForABuyerOfAnOpenEuroAuctionWithOffersAndNoOpenOfferAndNoBlock(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("offer-buyer-yes") { root, element ->
                renderAuctionCard(root, auction(), "member-1") {}
                assertTrue(element().hasButton("Preis vorschlagen"))
            }
            mountedForm("offer-seller") { root, element ->
                renderAuctionCard(root, auction(seller = true, openForSeller = 0), "member-1") {}
                assertFalse(element().hasButton("Preis vorschlagen"), "the seller never proposes a price on his own auction")
            }
            mountedForm("offer-ltr") { root, element ->
                renderAuctionCard(root, auction(currency = "LTR", offersEnabled = false), "member-1") {}
                assertFalse(element().hasButton("Preis vorschlagen"))
            }
            mountedForm("offer-off") { root, element ->
                renderAuctionCard(root, auction(offersEnabled = false), "member-1") {}
                assertFalse(element().hasButton("Preis vorschlagen"))
            }
            mountedForm("offer-blocked") { root, element ->
                renderAuctionCard(root, auction(blocked = true), "member-1") {}
                val card = element()
                assertFalse(card.hasButton("Preis vorschlagen"))
                assertTrue(card.shows("keine weiteren Preisvorschläge machen"), "the block is said in words")
            }
        }

    @Test
    fun theDialog_saysThatTheProposalBindsNobody_andShowsTheAllowedRange(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("offer-dialog") { root, element ->
                renderAuctionCard(root, auction(), "member-1") {}
                element().buttonNamed("Preis vorschlagen").click()
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                val dialog = document.querySelector(".modal.show") as HTMLElement
                assertTrue(dialog.shows("bindet Sie erst, wenn der Verkäufer ihn annimmt"))
                assertTrue(dialog.shows("höchstens bis zum Auktionsende"))
                assertTrue(dialog.shows("Erlaubt: ab"))
                assertTrue(dialog.hasButton("Vorschlag senden"))
                assertFalse(dialog.shows("Angebot"), "a price offer is never called Angebot")
            }
        }

    @Test
    fun theSeller_seesTheCountAndTheList_andTheAcceptanceAsksForATierTwoConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val route = routeOf { rpcService<IAuctionService>().listOffersForAuction("a1") }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == route) {
                        request.answerWith(
                            jsonOf(
                                ListSerializer(AuctionOfferDto.serializer()),
                                listOf(
                                    offer("o1", amount = 4000, below = true, asSeller = true),
                                    offer("o2", amount = 5000, asSeller = true),
                                ),
                            ),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("offer-seller-list") { root, element ->
                    renderAuctionCard(root, auction(seller = true, openForSeller = 2), "member-1") {}
                    val card = element()
                    assertTrue(card.shows("Offene Preisvorschläge: 2"))
                    assertEquals(0, calls.toRoute(route).size, "nothing is requested before the disclosure is opened")
                    card.buttonNamed("Preisvorschläge anzeigen").click()
                    awaitUntil("the list") { card.shows("Bea Käuferin") }
                    assertTrue(card.shows("Unter dem aktuellen Gebot"))
                    assertEquals(2, card.allOf("button").count { it.textContent?.trim() == "Annehmen" })
                    assertEquals(2, card.allOf("button").count { it.textContent?.trim() == "Gegenvorschlag" })
                    assertEquals(2, card.allOf("button").count { it.textContent?.trim() == "Ablehnen" })
                    assertFalse(card.shows("Angebot"))
                    card.allOf("button").first { it.textContent?.trim() == "Annehmen" }.click()
                    awaitUntil("the confirmation") { document.querySelector(".modal.show") != null }
                    val dialog = document.querySelector(".modal.show") as HTMLElement
                    assertTrue(dialog.shows("Mit der Annahme endet die Auktion"))
                    assertTrue(dialog.shows("Bea Käuferin"), "the name is in the confirmation")
                    assertTrue(dialog.shows("40"), "the amount is in the confirmation")
                }
            }
        }

    @Test
    fun theBuyer_canAcceptOrDeclineACounterOffer_andWithdrawAnOwnOpenProposal(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val route = routeOf { rpcService<IAuctionService>().listMyOffers() }
            var rows = listOf(offer("c1", fromSeller = true, amount = 6000))
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == route) {
                        request.answerWith(jsonOf(ListSerializer(AuctionOfferDto.serializer()), rows))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("offer-counter") { root, element ->
                    renderAuctionCard(root, auction(hasOpen = true), "member-1") {}
                    val card = element()
                    awaitUntil("the counter-offer") { card.shows("Gegenvorschlag des Verkäufers") }
                    assertTrue(card.hasButton("Annehmen"))
                    assertTrue(card.hasButton("Ablehnen"))
                    assertFalse(card.hasButton("Preis vorschlagen"), "only one negotiation at a time")
                    card.buttonNamed("Annehmen").click()
                    awaitUntil("the confirmation") { document.querySelector(".modal.show") != null }
                    val dialog = document.querySelector(".modal.show") as HTMLElement
                    assertTrue(dialog.shows("Sie bezahlen"), "the buyer text, not the seller text")
                    assertTrue(dialog.shows("keinen Käuferschutz"))
                }
                rows = listOf(offer("m1", amount = 3000))
                mountedForm("offer-own-open") { root, element ->
                    renderAuctionCard(root, auction(hasOpen = true), "member-1") {}
                    val card = element()
                    awaitUntil("the own proposal") { card.shows("Ihr Preisvorschlag") }
                    assertTrue(card.hasButton("Zurückziehen"))
                    assertFalse(card.hasButton("Annehmen"))
                }
            }
        }

    @Test
    fun anEndedAuction_isNamedAsSuch_notAsExpired(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("offer-status") { root, element ->
                renderMyOffersTable(
                    root,
                    listOf(
                        offer("e1", status = AuctionOfferStatus.EXPIRED, endedByAuction = true),
                        offer("e2", status = AuctionOfferStatus.EXPIRED),
                        offer("e3", status = AuctionOfferStatus.WITHDRAWN),
                    ),
                )
                val table = element()
                assertTrue(table.shows("Auktion beendet"))
                assertTrue(table.shows("Abgelaufen"))
                assertTrue(table.shows("Zurückgezogen"))
            }
        }
}
