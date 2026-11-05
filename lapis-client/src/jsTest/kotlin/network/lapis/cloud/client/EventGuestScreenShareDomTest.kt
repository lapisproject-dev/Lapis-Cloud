package network.lapis.cloud.client

import io.kvision.panel.Root
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
import kotlin.test.assertTrue

private class NoApi : EventGuestApi {
    override suspend fun state(slug: String): EventOnlineStateDto? = null

    override suspend fun join(
        slug: String,
        request: EventGuestJoinRequestDto,
    ): EventGuestJoinHttpResult = EventGuestJoinHttpResult.Failed
}

/**
 * V1.9.282 -- the screen share button in the call view of a person WITHOUT an account, in a real DOM with the real classes: hidden without a grant,
 * shown (after the camera, before the hand) with a grant and `getDisplayMedia`, a calm live region for the sentences, a share that ends at once
 * when the grant is withdrawn. The LiveKit connection itself is not runnable in Karma, so the permission callback and the share start are driven
 * directly through the same internal functions the session callbacks call.
 */
class EventGuestScreenShareDomTest {
    private fun response() =
        EventGuestJoinResponseDto(
            outcome = EventOnlineOutcome.JOINED,
            identity = "evg-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            displayName = "Selma (Gast)",
        )

    private suspend fun withView(
        supported: Boolean = true,
        block: suspend (EventGuestCallView, HTMLElement) -> Unit,
    ) {
        mountedForm("event-guest-share") { root: Root, element ->
            val view =
                EventGuestCallView(host = root, response = response(), onLeft = {
                }, slug = "offene-runde", api = NoApi(), screenShareSupported = { supported })
            view.show()
            try {
                awaitUntil(
                    "the grid is in the document",
                ) { element().textContent.orEmpty().contains("Die Teilnahme ist gerade nicht möglich") }
                block(view, element())
            } finally {
                view.endHands()
                view.disposeShare()
            }
        }
    }

    private fun HTMLElement.labels(): List<String> = allOf("button").map { it.textContent.orEmpty().trim() }

    private fun HTMLElement.shareButtons(): List<HTMLButtonElement> =
        allOf("button")
            .filter {
                it.textContent.orEmpty().trim().let { t ->
                    t == "Bildschirm teilen" || t == "Teilen beenden"
                }
            }.map { it as HTMLButtonElement }

    private fun HTMLElement.liveText(): String =
        allOf("[role=status]")
            .filter {
                it.classList.contains("small")
            }.map { it.textContent.orEmpty() }
            .filter { it.isNotEmpty() }
            .joinToString("|")

    @Test
    fun withoutAGrant_thereIsNoButton(): Promise<Unit> =
        formTest {
            withView { view, page ->
                view.setPhase(GuestCallPhase.CONNECTED)
                awaitUntil("render") { page.labels().contains("Kamera einschalten") }
                assertTrue(page.shareButtons().isEmpty())
                // the connect-time reading of a token without the source changes nothing and says nothing
                view.onScreenSharePermission(false)
                assertTrue(page.shareButtons().isEmpty())
                assertFalse(page.liveText().contains("Bildschirm"))
            }
        }

    @Test
    fun withAGrantAndGetDisplayMedia_theButtonAppears_afterTheCamera_beforeTheHand(): Promise<Unit> =
        formTest {
            withView { view, page ->
                view.onScreenSharePermission(true)
                awaitUntil("the button") { page.shareButtons().size == 1 }
                val labels = page.labels()
                val camera = labels.indexOf("Kamera einschalten")
                val share = labels.indexOf("Bildschirm teilen")
                val hand = labels.indexOf("Hand heben")
                assertTrue(camera in 0 until share && share < hand, "order: $labels")
                assertTrue(page.liveText().contains("Sie dürfen jetzt Ihren Bildschirm teilen."))
            }
        }

    @Test
    fun withoutGetDisplayMedia_thereIsNoButton_andOneCalmSentence(): Promise<Unit> =
        formTest {
            withView(supported = false) { view, page ->
                view.onScreenSharePermission(true)
                awaitUntil("the sentence") { page.liveText().contains("Bildschirm teilen ist auf diesem Gerät nicht möglich.") }
                assertTrue(page.shareButtons().isEmpty())
                // a second reading of the same permission does not repeat the sentence
                view.onScreenSharePermission(true)
                assertEquals(1, Regex("nicht möglich").findAll(page.liveText()).count())
            }
        }

    @Test
    fun startingAndEndingTheShare_followsTheButton(): Promise<Unit> =
        formTest {
            withView { view, page ->
                val calls = mutableListOf<Boolean>()
                view.screenShareStarter = { on -> calls += on }
                view.onScreenSharePermission(true)
                awaitUntil("the button") { page.shareButtons().size == 1 }
                page.shareButtons().single().click()
                awaitUntil("sharing") {
                    page
                        .shareButtons()
                        .singleOrNull()
                        ?.textContent
                        ?.trim() == "Teilen beenden"
                }
                assertEquals(listOf(true), calls)
                // ending is always possible, here through the same button
                page.shareButtons().single().click()
                awaitUntil("ended") {
                    page
                        .shareButtons()
                        .singleOrNull()
                        ?.textContent
                        ?.trim() == "Bildschirm teilen"
                }
                assertEquals(listOf(true, false), calls)
                // the browser's own "stop sharing" resets the button as well
                page.shareButtons().single().click()
                awaitUntil("sharing again") {
                    page
                        .shareButtons()
                        .singleOrNull()
                        ?.textContent
                        ?.trim() == "Teilen beenden"
                }
                view.onScreenShareEnded()
                awaitUntil("reset") {
                    page
                        .shareButtons()
                        .singleOrNull()
                        ?.textContent
                        ?.trim() == "Bildschirm teilen"
                }
            }
        }

    @Test
    fun aWithdrawalWhileSharing_stopsTheShareAndSaysSo(): Promise<Unit> =
        formTest {
            withView { view, page ->
                val calls = mutableListOf<Boolean>()
                view.screenShareStarter = { on -> calls += on }
                view.onScreenSharePermission(true)
                awaitUntil("the button") { page.shareButtons().size == 1 }
                page.shareButtons().single().click()
                awaitUntil("sharing") {
                    page
                        .shareButtons()
                        .singleOrNull()
                        ?.textContent
                        ?.trim() == "Teilen beenden"
                }
                view.onScreenSharePermission(false)
                awaitUntil("the share was stopped locally") { calls == listOf(true, false) }
                awaitUntil("the button is gone") { page.shareButtons().isEmpty() }
                // the first sentence ("Sie dürfen ...") was shown at once, the next one waits for the 1 s spacing and then replaces it
                awaitUntil("the moderation sentence") { page.liveText().contains("Die Moderation hat Ihre Bildschirmfreigabe beendet.") }
            }
        }

    @Test
    fun aWithdrawalWithoutSharing_usesTheOtherSentence_andSentencesAreSpaced(): Promise<Unit> =
        formTest {
            withView { view, page ->
                view.onScreenSharePermission(true)
                awaitUntil("first") { page.liveText().contains("Sie dürfen jetzt Ihren Bildschirm teilen.") }
                view.onScreenSharePermission(false)
                // within the spacing the first sentence is still there
                assertTrue(page.liveText().contains("Sie dürfen jetzt Ihren Bildschirm teilen."))
                awaitUntil("second") { page.liveText().contains("Die Freigabe zum Bildschirmteilen wurde beendet.") }
                assertFalse(page.liveText().contains("Die Moderation hat"))
                awaitUntil("the button is gone") { page.shareButtons().isEmpty() }
            }
        }

    @Test
    fun disposing_clearsTheLiveRegion_theTimer_andTheButton(): Promise<Unit> =
        formTest {
            withView { view, page ->
                view.onScreenSharePermission(true)
                awaitUntil("button") { page.shareButtons().size == 1 }
                view.onScreenSharePermission(false)
                view.disposeShare()
                awaitUntil("button gone") { page.shareButtons().isEmpty() }
                assertEquals("", page.liveText())
                kotlinx.coroutines.delay(1_300)
                assertEquals("", page.liveText(), "no timer writes after the call is over")
                assertNotNull(page)
            }
        }
}
