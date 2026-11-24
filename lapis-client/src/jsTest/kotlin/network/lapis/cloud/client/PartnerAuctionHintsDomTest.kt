package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartnerAuctionHintDto
import network.lapis.cloud.shared.domain.PartnerAuctionHintPageDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** V1.9.322 c3 -- the partner auction cards in a real mounted root: hostile values stay text, nothing is bid on, the gates hold. */
class PartnerAuctionHintsDomTest {
    private val id = "b".repeat(64)

    private fun hint(
        host: String = "o.beispiel.de",
        title: String = "Lastenrad",
        endsAt: Instant = Clock.System.now() + 5.hours,
        offers: Boolean = false,
        buyNow: Long? = null,
        teaserId: String = id,
    ) = PartnerAuctionHintDto(
        originHost = host,
        teaserId = teaserId,
        title = title,
        currency = "EUR",
        priceKind = AuctionTeaserPriceKind.CURRENT,
        priceMinor = 2500,
        priceAsOf = Clock.System.now(),
        buyNowMinor = buyNow,
        offersPossible = offers,
        endsAt = endsAt,
    )

    private fun page(
        vararg items: PartnerAuctionHintDto,
        more: Boolean = false,
    ) = PartnerAuctionHintPageDto(items.toList(), more)

    private fun HTMLElement.all(selector: String) =
        querySelectorAll(selector).let {
            (0 until it.length).map { i -> it.item(i) as HTMLElement }
        }

    @Test
    fun theCardIsAPlainCard_withOneLink_andNothingToBidOn(): Promise<Unit> =
        formTest {
            mountedForm("partner-auction-card") { root, element ->
                PartnerAuctionHintsSection(root, loadPage = { page(hint(offers = true, buyNow = 9900)) }).start()
                awaitUntil("the card appears") { element().textContent.orEmpty().contains("Lastenrad") }
                val el = element()
                val card = el.all(".lapis-card-federated").single()
                assertTrue(card.textContent.orEmpty().contains("o.beispiel.de"))
                assertTrue(card.textContent.orEmpty().contains("Aktueller Preis"))
                assertTrue(card.textContent.orEmpty().contains("Sofortkauf"))
                assertTrue(card.textContent.orEmpty().contains("Preisvorschlag möglich"))
                assertEquals(0, card.all("button").size, "a member has no button on the card: nothing to bid or buy")
                assertNull(card.getAttribute("onclick"))
                assertNull(card.getAttribute("role"))
                val link = card.all("a").single()
                assertEquals("https://o.beispiel.de/app#/auction/hint/$id", link.getAttribute("href"))
                assertEquals("_blank", link.getAttribute("target"))
                assertEquals("noopener noreferrer", link.getAttribute("rel"))
                assertEquals("no-referrer", link.getAttribute("referrerpolicy"))
                assertEquals("Auf o.beispiel.de ansehen (öffnet neues Fenster)", link.getAttribute("aria-label"))
                assertTrue(link.textContent.orEmpty().contains("Auf o.beispiel.de ansehen"))
            }
        }

    @Test
    fun theTitleNeverReachesAnAttribute(): Promise<Unit> =
        formTest {
            mountedForm("partner-auction-title") { root, element ->
                PartnerAuctionHintsSection(root, loadPage = { page(hint(title = "Geheimtitel-4711")) }).start()
                awaitUntil("the card appears") { element().textContent.orEmpty().contains("Geheimtitel-4711") }
                val html = element().innerHTML
                // the only place of the title is the text of its own element
                val attributes = element().all("*").flatMap { e -> (0 until e.attributes.length).map { e.attributes.item(it)!!.value } }
                assertFalse(attributes.any { "Geheimtitel-4711" in it }, "no attribute carries the title: $html")
            }
        }

    @Test
    fun hostileValuesStayText(): Promise<Unit> =
        formTest {
            mountedForm("partner-auction-hostile") { root, element ->
                val hostile = listOf("<img src=x onerror=alert(1)>", "<script>alert(1)</script>", "x".repeat(3000), "a\u0000b", "a‮b")
                val items = hostile.mapIndexed { n, t -> hint(host = t, title = t, teaserId = n.toString().padStart(64, '0')) }
                PartnerAuctionHintsSection(root, loadPage = { page(*items.toTypedArray()) }).start()
                awaitUntil("the cards appear") { element().all(".lapis-card-federated").size == hostile.size }
                val el = element()
                assertNull(el.querySelector("script, img"), "no element made of a foreign value")
                assertEquals(0, el.all("a").size, "no link for a host that is not canonical")
            }
        }

    @Test
    fun anAdministratorGetsAHideButton_aMemberDoesNot(): Promise<Unit> =
        formTest {
            disableModalTransitions()
            mountedForm("partner-auction-hide") { root, element ->
                val hidden = mutableListOf<Pair<String, String>>()
                PartnerAuctionHintsSection(
                    container = root,
                    loadPage = { page(hint(host = "a.beispiel.de"), hint(host = "b.beispiel.de", teaserId = "c".repeat(64))) },
                    isAdmin = true,
                    hide = { host, tid -> hidden += host to tid },
                ).start()
                awaitUntil("both cards") { element().all(".lapis-card-federated").size == 2 }
                val first =
                    element()
                        .all(
                            ".lapis-card-federated",
                        ).first()
                        .all("button")
                        .single { it.textContent.orEmpty().contains("Hinweis ausblenden") }
                first.click()
                awaitUntil("the dialog appears") { modalText().contains("Hinweis ausblenden?") }
                assertTrue(modalText().contains("a.beispiel.de"))
                val confirm = document.querySelectorAll(".modal.show .modal-footer button")
                val ok =
                    (0 until confirm.length)
                        .map {
                            confirm.item(
                                it,
                            ) as HTMLElement
                        }.single { it.textContent.orEmpty().contains("Ausblenden") }
                ok.click()
                awaitUntil("the card is gone") { element().all(".lapis-card-federated").size == 1 }
                assertEquals(listOf("a.beispiel.de" to id), hidden)
                assertFalse(element().textContent.orEmpty().contains("a.beispiel.de"))
            }
            mountedForm("partner-auction-nohide") { root, element ->
                PartnerAuctionHintsSection(root, loadPage = { page(hint()) }, isAdmin = false).start()
                awaitUntil("the card") { element().all(".lapis-card-federated").size == 1 }
                assertEquals(0, element().all("button").size, "no node for a non-administrator")
            }
        }

    private fun modalText(): String = document.querySelector(".modal.show")?.textContent.orEmpty()

    @Test
    fun theEmptyStateKeepsTheHeading(): Promise<Unit> =
        formTest {
            mountedForm("partner-auction-empty") { root, element ->
                PartnerAuctionHintsSection(root, loadPage = { page() }).start()
                awaitUntil("the empty line") { element().textContent.orEmpty().contains("Noch keine Auktionen von Partnerservern.") }
                assertTrue(element().textContent.orEmpty().contains("Auktionen von Partnerservern"))
            }
        }

    @Test
    fun theMinuteTickRemovesACardThatHasEnded(): Promise<Unit> =
        formTest {
            mountedForm("partner-auction-tick") { root, element ->
                var now = Clock.System.now()
                val soon = hint(host = "a.beispiel.de", endsAt = now + 1.minutes)
                val later = hint(host = "b.beispiel.de", endsAt = now + 3.hours, teaserId = "c".repeat(64))
                PartnerAuctionHintsSection(root, loadPage = { page(soon, later) }, clock = { now }, tickMs = 100).start()
                awaitUntil("both cards") { element().all(".lapis-card-federated").size == 2 }
                now += 2.minutes
                awaitUntil("the ended card is gone") { element().all(".lapis-card-federated").size == 1 }
                assertTrue(element().textContent.orEmpty().contains("b.beispiel.de"))
                delay(10.milliseconds)
            }
        }

    @Test
    fun nothingIsRequested_forANonMember_aSwitchOff_orAFailingAvailability(): Promise<Unit> =
        formTest {
            mountedForm("partner-auction-gates") { root, element ->
                var pageCalls = 0
                val load: suspend (Int) -> PartnerAuctionHintPageDto = {
                    pageCalls++
                    page(hint())
                }
                val none: suspend (String, String) -> Unit = { _, _ -> }
                listOf<MemberStatus?>(MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.DONOR, MemberStatus.APPLICATION, null).forEach {
                    mountPartnerAuctionHints(root, status = it, isAdmin = false, readVisible = { true }, loadPage = load, hide = none)
                }
                mountPartnerAuctionHints(
                    root,
                    status = MemberStatus.ACTIVE,
                    isAdmin = false,
                    readVisible = { false },
                    loadPage = load,
                    hide = none,
                )
                mountPartnerAuctionHints(root, status = MemberStatus.ACTIVE, isAdmin = false, readVisible = {
                    error("forbidden")
                }, loadPage = load, hide = none)
                settleAppScope()
                assertEquals(0, pageCalls, "listPartnerAuctionHints is never called in an excluded case")
                assertFalse(element().textContent.orEmpty().contains("Auktionen von Partnerservern"))
                mountPartnerAuctionHints(
                    root,
                    status = MemberStatus.ACTIVE,
                    isAdmin = false,
                    readVisible = { true },
                    loadPage = load,
                    hide = none,
                )
                awaitUntil("the section appears") { element().textContent.orEmpty().contains("Auktionen von Partnerservern") }
                assertNotNull(element().all(".lapis-card-federated").firstOrNull() ?: element())
                assertTrue(pageCalls >= 1)
            }
        }
}
