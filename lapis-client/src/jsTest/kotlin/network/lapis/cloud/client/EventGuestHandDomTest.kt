package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.CompletableDeferred
import network.lapis.cloud.client.livekit.conferenceHandsPacket
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SELF = "evg-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val ANNA = "evg-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val BEN = "11111111-1111-4111-8111-111111111111"
private const val EPOCH = "0123456789abcdef"

private fun packet(
    seq: Long,
    vararg ids: String,
): ByteArray = """{"v":1,"e":"$EPOCH","seq":$seq,"ids":[${ids.joinToString(",") { "\"$it\"" }}]}""".encodeToByteArray()

/** Answers the hand calls of the guest from memory; [gate] holds an answer back, [calls] records what was sent (token, raise). */
private class HandApi : EventGuestApi {
    var answer: (Boolean) -> EventGuestHandHttpResult = { EventGuestHandHttpResult.Failed }
    var gate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun state(slug: String): EventOnlineStateDto? = null

    override suspend fun join(
        slug: String,
        request: EventGuestJoinRequestDto,
    ): EventGuestJoinHttpResult = EventGuestJoinHttpResult.Failed

    override suspend fun hand(
        slug: String,
        handToken: String,
        raise: Boolean,
    ): EventGuestHandHttpResult {
        calls += handToken to raise
        gate?.await()
        return answer(raise)
    }
}

/**
 * V1.9.281 -- the raised hand in the call view of a person WITHOUT an account, in a real DOM with the real classes: badge and ring on the right tiles
 * (the own tile is keyed "local" but stands for the own identity), the visual order through CSS `order` (the DOM is not reordered), the button that
 * changes only after the server's snapshot, the quiet error line, the disabled state with its description before the connection. The LiveKit
 * connection itself is not runnable in Karma (the join answer has no server URL), the call phase is driven directly.
 */
class EventGuestHandDomTest {
    private fun response(handToken: String? = "tok-1") =
        EventGuestJoinResponseDto(
            outcome = EventOnlineOutcome.JOINED,
            identity = SELF,
            displayName = "Selma (Gast)",
            handToken = handToken,
        )

    private suspend fun withView(
        api: HandApi,
        handToken: String? = "tok-1",
        block: suspend (EventGuestCallView, HTMLElement) -> Unit,
    ) {
        mountedForm("event-guest-hand") { root: Root, element ->
            val view = EventGuestCallView(host = root, response = response(handToken), onLeft = {}, slug = "offene-runde", api = api)
            view.show()
            try {
                // the unconfigured join answer ends in UNAVAILABLE once the grid is in the document: from then on the grid exists
                awaitUntil(
                    "the grid is in the document",
                ) { element().textContent.orEmpty().contains("Die Teilnahme ist gerade nicht möglich") }
                block(view, element())
            } finally {
                view.endHands()
            }
        }
    }

    private fun HTMLElement.handButton(): HTMLButtonElement =
        assertNotNull(
            allOf("button").firstOrNull {
                it.textContent?.trim().let { t ->
                    t == "Hand heben" || t == "Hand senken"
                }
            },
        ) as HTMLButtonElement

    private fun HTMLElement.badges(): List<HTMLElement> = allOf("[data-role=hand-badge]")

    private fun HTMLElement.shown(): List<Boolean> = badges().map { it.style.display == "inline-flex" }

    @Test
    fun beforeTheConnection_theButtonIsOff_andTellsWhy(): Promise<Unit> =
        formTest {
            withView(HandApi()) { view, page ->
                view.setPhase(GuestCallPhase.CONNECTING)
                awaitUntil("the button is off") { page.handButton().disabled }
                val button = page.handButton()
                assertTrue(assertNotNull(button.getAttribute("title")).contains("erst nach dem Verbinden"))
                val hint = assertNotNull(page.querySelector("#${button.getAttribute("aria-describedby")}"))
                assertTrue(hint.textContent.orEmpty().contains("erst nach dem Verbinden"))
                assertTrue(hint.classList.contains("visually-hidden"))
                view.setPhase(GuestCallPhase.CONNECTED)
                awaitUntil("the button is on") { !page.handButton().disabled }
                assertNull(page.handButton().getAttribute("title"))
                assertNull(page.handButton().getAttribute("aria-describedby"))
            }
        }

    @Test
    fun withoutAToken_theButtonStaysOff_evenWhenConnected(): Promise<Unit> =
        formTest {
            withView(HandApi(), handToken = null) { view, page ->
                view.setPhase(GuestCallPhase.CONNECTED)
                awaitUntil("render") { page.allOf("button").isNotEmpty() }
                assertTrue(page.handButton().disabled)
            }
        }

    @Test
    fun anotherGuestsHand_getsBadgeRingAndFirstPlace_theOwnTileIsMapped(): Promise<Unit> =
        formTest {
            withView(HandApi()) { view, page ->
                view.setPhase(GuestCallPhase.CONNECTED)
                view.ensureTile("local", "Selma (Gast)")
                val anna = view.ensureTile(ANNA, "Anna (Gast)")
                val ben = view.ensureTile(BEN, "Ben")
                assertEquals(3, page.badges().size, "one badge per tile, built once")
                assertEquals(listOf(false, false, false), page.shown())
                assertTrue(page.badges().all { it.querySelector(".lapis-conference-hand-text")?.textContent == "Hand gehoben" })
                // the gallery order in the DOM before
                val domOrder = page.allOf("[data-role=hand-badge]").map { it.parentElement }
                view.onHandBroadcast(conferenceHandsPacket(false, packet(1, BEN))!!)
                assertTrue(ben.classList.contains(CONFERENCE_TILE_HAND_CLASS))
                assertFalse(anna.classList.contains(CONFERENCE_TILE_HAND_CLASS))
                assertEquals(listOf(false, false, true), page.shown())
                // the own tile never moves (index 0); the raised hand of Ben goes before Anna
                assertEquals("1", ben.style.getPropertyValue("order"))
                assertEquals("2", anna.style.getPropertyValue("order"))
                assertTrue(ben.style.position == "relative" || ben.style.cssText.contains("position: relative"))
                // the DOM was NOT reordered (a video is never re-attached for a hand)
                assertEquals(domOrder, page.allOf("[data-role=hand-badge]").map { it.parentElement })
                // the own hand: the tile "local" stands for SELF
                view.onHandBroadcast(conferenceHandsPacket(false, packet(2, BEN, SELF))!!)
                assertEquals(listOf(true, false, true), page.shown())
                // everybody lowered: badges off, the order is reset
                view.onHandBroadcast(conferenceHandsPacket(false, packet(3))!!)
                assertEquals(listOf(false, false, false), page.shown())
                assertEquals("", ben.style.getPropertyValue("order"))
                assertFalse(ben.classList.contains(CONFERENCE_TILE_HAND_CLASS))
            }
        }

    @Test
    fun theButtonChangesOnlyAfterTheSnapshot_andLowersAgain(): Promise<Unit> =
        formTest {
            val api = HandApi()
            api.answer = { raise -> EventGuestHandHttpResult.Queue(if (raise) packet(1, SELF) else packet(2)) }
            val gate = CompletableDeferred<Unit>()
            api.gate = gate
            withView(api) { view, page ->
                view.setPhase(GuestCallPhase.CONNECTED)
                view.ensureTile("local", "Selma (Gast)")
                awaitUntil("on") { !page.handButton().disabled }
                page.handButton().click()
                awaitUntil("the request is out") { api.calls.size == 1 }
                assertEquals("tok-1" to true, api.calls.single())
                assertEquals("Hand heben", page.handButton().textContent?.trim(), "still the old words while the answer is outstanding")
                assertTrue(page.handButton().disabled, "the button waits")
                gate.complete(Unit)
                awaitUntil("the new words") { page.handButton().textContent?.trim() == "Hand senken" }
                assertEquals(listOf(true), page.shown())
                api.gate = null
                page.handButton().click()
                awaitUntil("lowered") { page.handButton().textContent?.trim() == "Hand heben" }
                assertEquals(listOf(false), page.shown())
                assertEquals(listOf("tok-1" to true, "tok-1" to false), api.calls)
            }
        }

    @Test
    fun aLoweredAnswer_withoutAQueue_switchesTheButtonBack_andIsNeverToldAsLoweredByOthers(): Promise<Unit> =
        formTest {
            val api = HandApi()
            api.answer = { raise -> if (raise) EventGuestHandHttpResult.Queue(packet(1, SELF)) else EventGuestHandHttpResult.Lowered }
            withView(api) { view, page ->
                view.setPhase(GuestCallPhase.CONNECTED)
                view.ensureTile("local", "Selma (Gast)")
                awaitUntil("on") { !page.handButton().disabled }
                page.handButton().click()
                awaitUntil("raised") { page.handButton().textContent?.trim() == "Hand senken" }
                page.handButton().click()
                awaitUntil("lowered by the 204") { page.handButton().textContent?.trim() == "Hand heben" }
                // the next packet (somebody else's change) must not read as "somebody lowered my hand"
                view.onHandBroadcast(conferenceHandsPacket(false, packet(3, BEN))!!)
                assertEquals("Hand heben", page.handButton().textContent?.trim())
                assertFalse(page.textContent.orEmpty().contains("Ihre Hand wurde gesenkt"))
            }
        }

    @Test
    fun aRefusal_showsTheKnownErrorQuietly_andChangesNothing(): Promise<Unit> =
        formTest {
            val api = HandApi()
            withView(api) { view, page ->
                view.setPhase(GuestCallPhase.CONNECTED)
                view.ensureTile("local", "Selma (Gast)")
                awaitUntil("on") { !page.handButton().disabled }
                page.handButton().click()
                awaitUntil("the error line") {
                    page.textContent.orEmpty().contains("Die Hand konnte nicht gehoben werden. Bitte versuchen Sie es erneut.")
                }
                assertEquals("Hand heben", page.handButton().textContent?.trim())
                assertEquals(listOf(false), page.shown())
                awaitUntil("usable again") { !page.handButton().disabled }
            }
        }

    @Test
    fun aPacketWithASender_isNeverAQueue() {
        assertNull(conferenceHandsPacket(fromParticipant = true, payload = packet(1, BEN)))
    }

    @Test
    fun theGateway_servesOnlyTheOwnHand(): Promise<Unit> =
        formTest {
            val api = HandApi()
            val gateway = GuestHandGateway(api = api, slug = "x", handToken = "t")
            assertNull(gateway.queue())
            assertNull(gateway.lowerOf(BEN))
            assertNull(gateway.lowerAll())
            assertTrue(api.calls.isEmpty(), "no request for the calls a guest cannot make")
            api.answer = { EventGuestHandHttpResult.Queue(packet(4, ANNA)) }
            assertEquals(listOf(ANNA), gateway.raise()?.identities)
            api.answer = { EventGuestHandHttpResult.Lowered }
            assertNull(gateway.lower())
            assertEquals(OwnLowerResult.Lowered, gateway.lowerOwn(), "a 204 is 'my hand is down', not a failure")
            api.answer = { EventGuestHandHttpResult.Queue("not json".encodeToByteArray()) }
            assertNull(gateway.raise(), "an answer that is no valid packet is no queue")
            api.answer = { EventGuestHandHttpResult.Failed }
            assertNull(gateway.raise())
        }
}
