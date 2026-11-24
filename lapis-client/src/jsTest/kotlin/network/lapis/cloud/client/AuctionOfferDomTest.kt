package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionOfferDto
import network.lapis.cloud.shared.domain.AuctionOfferNoticeDto
import network.lapis.cloud.shared.domain.AuctionOfferNoticeKind
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
        blockedUntil: LocalDateTime? = null,
        maxOpen: Int? = null,
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
        callerOfferBlocked = blocked || blockedUntil != null,
        callerOfferBlockedUntil = blockedUntil,
        maxOpenOffers = maxOpen,
    )

    private fun offer(
        id: String,
        status: AuctionOfferStatus = AuctionOfferStatus.OFFERED,
        fromSeller: Boolean = false,
        amount: Long = 4000,
        endedByAuction: Boolean = false,
        below: Boolean = false,
        asSeller: Boolean = false,
        declines: Int? = null,
        buyerBlockedUntil: LocalDateTime? = null,
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
        buyerDeclinesInCycle = declines,
        buyerBlockedUntil = buyerBlockedUntil,
    )

    private fun notice(
        kind: AuctionOfferNoticeKind = AuctionOfferNoticeKind.BUYER_OFFER,
        text: String = NOTICE_TEXT,
        version: String = "2026-11-24.v1",
    ) = AuctionOfferNoticeDto(kind = kind, version = version, lang = "de", text = text, sha256 = "a".repeat(64))

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
    fun theDialog_showsTheServerNoticeUnchanged_withItsVersion_andEchoesItWithTheOffer(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val noticeRoute = routeOf { rpcService<IAuctionService>().getAuctionOfferNotice(AuctionOfferNoticeKind.BUYER_OFFER, "de") }
            val makeRoute = routeOf { rpcService<IAuctionService>().makeOffer("a1", 1L, "v", "de", "h") }
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == noticeRoute ->
                            request.answerWith(jsonOf(AuctionOfferNoticeDto.serializer(), notice()))
                        request.isRpc && request.rpcRoute == makeRoute ->
                            request.answerWith(jsonOf(AuctionOfferDto.serializer(), offer("m1", amount = 3000)))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("offer-dialog") { root, element ->
                    renderAuctionCard(root, auction(), "member-1") {}
                    element().buttonNamed("Preis vorschlagen").click()
                    awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                    val dialog = document.querySelector(".modal.show") as HTMLElement
                    awaitUntil("the notice is shown") { dialog.shows(NOTICE_TEXT) }
                    // the notice text is the server's text, byte for byte, in ONE text node (no gettext, no sanitizer)
                    assertTrue(dialog.allOf("div").any { it.textContent == NOTICE_TEXT }, "exactly the delivered string")
                    assertTrue(dialog.shows("Hinweisfassung 2026-11-24.v1"))
                    assertTrue(dialog.shows("Erlaubt: ab"))
                    assertTrue(dialog.hasButton("Vorschlag senden"))
                    assertFalse(dialog.shows("Angebot"), "a price offer is never called Angebot")
                    assertEquals(1, calls.toRoute(noticeRoute).size)
                    assertEquals(
                        "BUYER_OFFER",
                        calls
                            .toRoute(noticeRoute)
                            .single()
                            .rpcParam(0)
                            .toString()
                            .trim('"'),
                    )
                    // an amount, then send: version, language and hash go back exactly as delivered
                    dialog.typeInto("Ihr Preis (EUR)", "40")
                    dialog.buttonNamed("Vorschlag senden").click()
                    awaitUntil("the offer is sent") { calls.toRoute(makeRoute).isNotEmpty() }
                    val sent = calls.toRoute(makeRoute).single()
                    assertEquals("2026-11-24.v1", sent.rpcParam(2).toString())
                    assertEquals("de", sent.rpcParam(3).toString())
                    assertEquals("a".repeat(64), sent.rpcParam(4).toString())
                }
            }
        }

    @Test
    fun withoutTheNotice_theSendButtonStaysOff(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val noticeRoute = routeOf { rpcService<IAuctionService>().getAuctionOfferNotice(AuctionOfferNoticeKind.BUYER_OFFER, "de") }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == noticeRoute) StubResponse(networkError = true) else request.answerWith("[]")
                },
            ) { _ ->
                mountedForm("offer-no-notice") { root, element ->
                    renderAuctionCard(root, auction(), "member-1") {}
                    element().buttonNamed("Preis vorschlagen").click()
                    awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                    val dialog = document.querySelector(".modal.show") as HTMLElement
                    awaitUntil("the failure is said") { dialog.shows("Hinweistext konnte nicht geladen werden") }
                    val send = dialog.buttonNamed("Vorschlag senden") as org.w3c.dom.HTMLButtonElement
                    assertTrue(send.disabled, "nobody sends a proposal without having been shown the wording")
                }
            }
        }

    @Test
    fun aBlockedBuyer_seesALockAndTheEndDate_notACounter_andNoButton(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            mountedForm("offer-blocked-until") { root, element ->
                renderAuctionCard(root, auction(blockedUntil = LocalDateTime(2099, 1, 8, 10, 0)), "member-1") {}
                val card = element()
                assertFalse(card.hasButton("Preis vorschlagen"))
                assertTrue(card.shows("drei Ihrer Preisvorschläge abgelehnt"))
                assertTrue(card.shows("keinen neuen machen"))
                assertTrue(card.querySelector(".fa-lock") != null, "the lock symbol")
                assertFalse(card.shows("von 3"), "no counter for the buyer")
            }
        }

    @Test
    fun theSeller_seesOpenOfOfMax_andTheDeclineCounterPerBuyer_andTheLockOfABlockedBuyer(): Promise<Unit> =
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
                                    offer("o1", asSeller = true, declines = 1),
                                    offer(
                                        "o2",
                                        status = AuctionOfferStatus.DECLINED,
                                        asSeller = true,
                                        buyerBlockedUntil = LocalDateTime(2099, 1, 8, 10, 0),
                                    ),
                                ),
                            ),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("offer-seller-restrictions") { root, element ->
                    renderAuctionCard(root, auction(seller = true, openForSeller = 3, maxOpen = 10), "member-1") {}
                    val card = element()
                    assertTrue(card.shows("Offene Preisvorschläge: 3 von 10"))
                    card.buttonNamed("Preisvorschläge anzeigen").click()
                    awaitUntil("the list") { card.shows("Bea Käuferin") }
                    assertTrue(card.shows("Ablehnungen: 1 von 3"))
                    assertTrue(card.shows("Gesperrt bis"))
                    assertTrue(card.querySelector(".fa-lock") != null)
                }
            }
        }

    @Test
    fun theThirdDecline_isAnnouncedToTheSellerBeforeHeConfirms_theSecondIsNot(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val route = routeOf { rpcService<IAuctionService>().listOffersForAuction("a1") }
            var declines = 2
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == route) {
                        request.answerWith(
                            jsonOf(ListSerializer(AuctionOfferDto.serializer()), listOf(offer("o1", asSeller = true, declines = declines))),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("offer-third-decline") { root, element ->
                    renderAuctionCard(root, auction(seller = true, openForSeller = 1, maxOpen = 10), "member-1") {}
                    val card = element()
                    card.buttonNamed("Preisvorschläge anzeigen").click()
                    awaitUntil("the list") { card.shows("Bea Käuferin") }
                    card.buttonNamed("Ablehnen").click()
                    awaitUntil("the confirmation") { document.querySelector(".modal.show") != null }
                    val dialog = document.querySelector(".modal.show") as HTMLElement
                    assertTrue(dialog.shows("Das ist die dritte Ablehnung"))
                    assertTrue(dialog.shows("7 Tage"))
                }
            }
        }

    @Test
    fun aWithdrawalOfOwnProposal_saysThatANewOneIsPossible_unlessTheBuyerIsBlocked(): Promise<Unit> =
        formTest {
            AppState.setSession(member)
            val route = routeOf { rpcService<IAuctionService>().listMyOffers() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == route) {
                        request.answerWith(jsonOf(ListSerializer(AuctionOfferDto.serializer()), listOf(offer("m1", amount = 3000))))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("offer-withdraw-free") { root, element ->
                    renderAuctionCard(root, auction(hasOpen = true), "member-1") {}
                    val card = element()
                    awaitUntil("the own proposal") { card.shows("Ihr Preisvorschlag") }
                    card.buttonNamed("Zurückziehen").click()
                    awaitUntil("the confirmation") { document.querySelector(".modal.show") != null }
                    val dialog = document.querySelector(".modal.show") as HTMLElement
                    assertTrue(dialog.shows("Sie können danach einen neuen Preisvorschlag machen."))
                }
            }
        }

    @Test
    fun everyAnswerOfAnOffer_hasItsOwnText(): Promise<Unit> =
        formTest {
            val texts = AuctionOfferStatus.entries.map { auctionOfferAnsweredText(it) }
            assertEquals(texts.size, texts.toSet().size, "one distinct text per answer")
            assertTrue(auctionOfferAnsweredText(AuctionOfferStatus.WITHDRAWN).contains("zurückgezogen"))
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

    private companion object {
        const val NOTICE_TEXT =
            "Ein Preisvorschlag bindet Sie erst, wenn der Verkäufer ihn annimmt. Er gilt höchstens bis zum Auktionsende."
    }
}
