package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuctionService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.320 -- the settlement panel of an awarded Fiat (EUR) auction: who sees which button, that the payment details are fetched only on a
 * click, and that a payment link is opened only when it is an https link, in a new tab, without referrer.
 */
class AuctionFiatSettlementPanelDomTest {
    private val member =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Mira Mitglied",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun auction(
        status: AuctionStatus,
        seller: Boolean,
        winner: Boolean,
        paymentAt: LocalDateTime? = null,
        deliveryAt: LocalDateTime? = null,
    ) = AuctionDto(
        id = "a1",
        title = "Fahrrad",
        description = "Beschreibung",
        sellerMemberId = if (seller) "member-1" else "member-2",
        sellerDisplayName = "Sven Seller",
        startingBidLtr = null,
        buyNowPriceLtr = null,
        status = status,
        effectiveStatus = status,
        currentPriceLtr = null,
        currentLeaderDisplayName = "Mira Mitglied",
        leaderIsMe = winner,
        bidCount = 2,
        winnerMemberId = if (winner) "member-1" else "member-3",
        winnerDisplayName = "Mira Mitglied",
        finalPriceLtr = null,
        listingFeeLtr = 0.0.toDecimal(),
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        endsAt = LocalDateTime(2026, 1, 2, 10, 0),
        settledAt = LocalDateTime(2026, 1, 2, 10, 0),
        currency = "EUR",
        startingPriceMinor = 1000,
        currentPriceMinor = 2100,
        finalPriceMinor = 2100,
        paymentConfirmedAt = paymentAt,
        deliveryConfirmedAt = deliveryAt,
        callerIsSeller = seller,
        callerIsWinner = winner,
        hasPaymentLink = true,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hasButton(text: String): Boolean = allOf("button").any { it.textContent?.trim() == text }

    @Test
    fun aStranger_seesTheEuroPriceOnAnAwardedEuroAuctionShownAsSettled(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("fiat-card-stranger") { root, element ->
                renderAuctionCard(root, auction(AuctionStatus.SETTLED, seller = false, winner = false), "member-1") {}
                val card = element()
                assertTrue(card.shows("Verkauft an Mira Mitglied für"), "the sold line is shown")
                assertFalse(card.shows("für --"), "the euro price must not be a dash")
                assertTrue(card.shows("21"), "the euro amount is shown")
            }
        }

    @Test
    fun theWinner_seesTheDetailsButtonAndTheGoodsConfirmation_neverThePaymentConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("fiat-panel-winner") { root, element ->
                renderFiatSettlementPanel(root, auction(AuctionStatus.AWAITING_PAYMENT, seller = false, winner = true)) {}
                val panel = element()
                assertTrue(panel.hasButton("Zahlungsangaben anzeigen"))
                assertTrue(panel.hasButton("Wareneingang bestätigen"))
                assertFalse(panel.hasButton("Zahlungseingang bestätigen"))
                assertTrue(panel.shows("Zahlungseingang noch nicht bestätigt"), "the state is said in words")
                assertTrue(panel.shows("Lapis nimmt kein Geld an"), "the dispute notice is always there")
                assertFalse(panel.shows("DE89"), "the payment details are not on screen before the click")
            }
        }

    @Test
    fun theSeller_seesThePaymentConfirmation_notTheDetailsNorTheGoodsConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("fiat-panel-seller") { root, element ->
                renderFiatSettlementPanel(root, auction(AuctionStatus.AWAITING_PAYMENT, seller = true, winner = false)) {}
                val panel = element()
                assertTrue(panel.hasButton("Zahlungseingang bestätigen"))
                assertFalse(panel.hasButton("Zahlungsangaben anzeigen"))
                assertFalse(panel.hasButton("Wareneingang bestätigen"))
            }
        }

    @Test
    fun aConfirmationAlreadyGiven_hasNoButtonAnyMore_andACompletedAuctionSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val at = LocalDateTime(2026, 1, 5, 12, 0)
            mountedForm("fiat-panel-done") { root, element ->
                renderFiatSettlementPanel(
                    root,
                    auction(AuctionStatus.COMPLETED, seller = true, winner = false, paymentAt = at, deliveryAt = at),
                ) {}
                val panel = element()
                assertFalse(panel.hasButton("Zahlungseingang bestätigen"))
                assertTrue(panel.shows("vollständig abgewickelt"))
                assertFalse(panel.shows("noch nicht bestätigt"))
            }
        }

    @Test
    fun theDetails_areFetchedOnTheClick_theHttpsLinkOpensInANewTabWithoutReferrer_aPlainHttpLinkIsNeverRendered(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val route = routeOf { rpcService<IAuctionService>().getAuctionPaymentDetails("a1") }
            var link: String? = "https://pay.example.org/abc"
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == route) {
                        request.answerWith(
                            jsonOf(
                                AuctionPaymentDetailsDto.serializer(),
                                AuctionPaymentDetailsDto(
                                    accountHolder = "Sabine Verkäuferin",
                                    ibanFormatted = "DE89 3704 0044 0532 0130 00",
                                    paymentLink = link,
                                    paymentLinkHost = "pay.example.org",
                                    amountMinor = 2100,
                                    currency = "EUR",
                                    reference = "Lapis-Auktion a1b2c3d4",
                                ),
                            ),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("fiat-panel-details") { root, element ->
                    renderFiatSettlementPanel(root, auction(AuctionStatus.AWAITING_PAYMENT, seller = false, winner = true)) {}
                    val panel = element()
                    assertEquals(0, calls.toRoute(route).size, "nothing is requested before the click")
                    panel.buttonNamed("Zahlungsangaben anzeigen").click()
                    awaitUntil("the details") { panel.shows("DE89 3704 0044 0532 0130 00") }
                    assertEquals(1, calls.toRoute(route).size)
                    assertTrue(panel.shows("Sabine Verkäuferin"))
                    assertTrue(panel.shows("Lapis-Auktion a1b2c3d4"))
                    assertTrue(panel.shows("Führt zu: pay.example.org"), "the host of the link is named next to the button")
                    val anchor =
                        assertNotNull(
                            panel.querySelector("a[href='https://pay.example.org/abc']"),
                            "the https link",
                        ) as HTMLElement
                    assertEquals("_blank", anchor.getAttribute("target"))
                    val rel = anchor.getAttribute("rel").orEmpty()
                    assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), "rel was '$rel'")

                    // defence in depth: a link that is not https (could only come from a bug or a hostile server) is not rendered at all
                    link = "http://pay.example.org/plain"
                }
                mountedForm("fiat-panel-details-http") { root, element ->
                    renderFiatSettlementPanel(root, auction(AuctionStatus.AWAITING_PAYMENT, seller = false, winner = true)) {}
                    val panel = element()
                    panel.buttonNamed("Zahlungsangaben anzeigen").click()
                    awaitUntil("the details again") { panel.shows("Sabine Verkäuferin") }
                    assertEquals(0, panel.allOf("a").count { it.getAttribute("href").orEmpty().startsWith("http://") })
                    assertFalse(panel.shows("Link öffnen"))
                }
            }
        }
}
