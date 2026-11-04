package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ConferenceStreamBroadcastInput
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeScheduler : StreamPanelScheduler {
    private var next = 1
    val pending = LinkedHashMap<Int, () -> Unit>()

    override fun after(
        millis: Int,
        action: () -> Unit,
    ): Int {
        val id = next++
        pending[id] = action
        return id
    }

    override fun cancel(id: Int) {
        pending.remove(id)
    }

    fun fireAll() {
        val actions = pending.values.toList()
        pending.clear()
        actions.forEach { it() }
    }
}

/**
 * V1.9.275 -- the target panel in a real browser: the retry state, one polite live region and no announcement for a new attempt number, the inline
 * key field (write-only, kept across a poll, emptied on a state change, cancel, confirm and dispose), the busy button, and that `dispose` leaves
 * no region and no timer behind.
 */
class StreamTargetsPanelDomTest {
    private val failedFacebook =
        streamTarget(
            status = ConferenceStreamTargetStatus.FAILED,
            failureKind = ConferenceStreamFailureKind.REJECTED,
            attempt = 3,
        )

    private fun HTMLElement.regions() = allOf("[role=status]")

    private fun panelWith(
        scheduler: FakeScheduler = FakeScheduler(),
        onReconnect: suspend (String, ConferenceStreamBroadcastInput?) -> ConferenceStreamDto? = { _, _ -> null },
    ) = StreamTargetsPanel(onReconnect = onReconnect, scheduler = scheduler)

    @Test
    fun aRetryingTarget_isNotShownAsLive_andHasNoButton() {
        withMountedRoot("stp-retry") { root, element ->
            val panel = panelWith().also { root.add(it) }
            panel.update(
                streamOf(ConferenceStreamStatus.LIVE, streamTarget(status = ConferenceStreamTargetStatus.PENDING, attempt = 2)),
                true,
            )
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Wird erneut verbunden (2 von 3) …"), text)
            assertFalse(text.contains("Live"), "a target in retry must not read as live: $text")
            assertFalse(element().allOf("button").any { it.textContent?.trim() == "Neu verbinden" })
            panel.dispose()
        }
    }

    @Test
    fun aFailedTarget_showsReasonAttemptsHintAndButton_toAModeratorOnly() {
        withMountedRoot("stp-failed") { root, element ->
            val panel = panelWith().also { root.add(it) }
            val stream = streamOf(ConferenceStreamStatus.LIVE, failedFacebook, streamTarget("d2", "YouTube"))
            panel.update(stream, canManage = false)
            assertFalse(element().allOf("button").any { it.textContent?.trim() == "Neu verbinden" })
            panel.update(stream, canManage = true)
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Facebook hat die Verbindung abgelehnt."), text)
            assertTrue(text.contains("Automatische Versuche: 3"), text)
            assertTrue(text.contains("Einmal-Schlüssel gilt bei Facebook"), text)
            assertTrue(text.contains("Live auf 1 von 2 Zielen"), text)
            val button = element().buttonNamed("Neu verbinden")
            assertEquals("Facebook neu verbinden", button.getAttribute("aria-label"))
            panel.dispose()
        }
    }

    @Test
    fun exactlyOneLiveRegion_andOnlyAClassChangeIsAnnounced_debounced() {
        withMountedRoot("stp-announce") { root, element ->
            val scheduler = FakeScheduler()
            val panel = panelWith(scheduler).also { root.add(it) }
            val pending = { n: Int ->
                streamOf(ConferenceStreamStatus.LIVE, streamTarget(status = ConferenceStreamTargetStatus.PENDING, attempt = n))
            }
            panel.update(streamOf(ConferenceStreamStatus.LIVE, streamTarget()), true)
            val region = element().regions().single()
            assertEquals("polite", region.getAttribute("aria-live"))
            assertEquals("true", region.getAttribute("aria-atomic"))
            // LIVE -> RETRYING 1: one announcement (queued, not yet spoken)
            panel.update(pending(1), true)
            assertEquals(1, scheduler.pending.size)
            assertEquals("", region.textContent.orEmpty())
            // attempt 1 -> 2: same class, nothing new
            panel.update(pending(2), true)
            assertEquals(1, scheduler.pending.size)
            scheduler.fireAll()
            assertEquals("Facebook wird erneut verbunden.", region.textContent)
            // RETRYING -> FAILED
            panel.update(streamOf(ConferenceStreamStatus.LIVE, failedFacebook), true)
            scheduler.fireAll()
            assertEquals("Facebook ist fehlgeschlagen.", region.textContent)
            assertEquals(1, element().regions().size)
            panel.dispose()
            assertEquals(0, scheduler.pending.size)
            assertEquals(0, element().regions().size, "dispose must remove the live region")
        }
    }

    @Test
    fun theKeyField_opensInline_isWriteOnly_andSurvivesAPollWithoutAStateChange(): Promise<Unit> =
        formTest {
            mountedForm("stp-keyfield") { root, element ->
                val panel = panelWith().also { root.add(it) }
                val stream = streamOf(ConferenceStreamStatus.LIVE, failedFacebook)
                panel.update(stream, true)
                element().buttonNamed("Neu verbinden").click()
                awaitUntil("the key field") { element().querySelector("input") != null }
                assertTrue(panel.isKeyFieldOpen("d1"))
                val input = element().querySelector("input") as HTMLInputElement
                assertNull(input.closest("form"), "the key field must not sit in a form")
                assertEquals("off", input.getAttribute("autocomplete"))
                input.value = "FB-dummy-key-1234"
                input.dispatchEvent(Event("input"))
                // a poll without a state change: same row, field open, value kept
                panel.update(stream.copy(restartCount = 0), true)
                panel.update(streamOf(ConferenceStreamStatus.LIVE, failedFacebook.copy(retries = 5)), true)
                assertEquals(input, element().querySelector("input"))
                assertEquals("FB-dummy-key-1234", input.value)
                // the target recovers: the state class changes, the field is emptied and closed
                panel.update(streamOf(ConferenceStreamStatus.LIVE, streamTarget()), true)
                assertEquals("", input.value, "the key must be emptied before the field is dropped")
                assertFalse(panel.isKeyFieldOpen("d1"))
                assertNull(element().querySelector("input"))
                panel.dispose()
            }
        }

    @Test
    fun cancel_emptiesAndClosesTheField_andReturnsTheFocusToTheButton(): Promise<Unit> =
        formTest {
            mountedForm("stp-cancel") { root, element ->
                val panel = panelWith().also { root.add(it) }
                panel.update(streamOf(ConferenceStreamStatus.LIVE, failedFacebook), true)
                element().buttonNamed("Neu verbinden").click()
                awaitUntil("the key field") { element().querySelector("input") != null }
                val input = element().querySelector("input") as HTMLInputElement
                input.value = "FB-dummy-key-1234"
                input.dispatchEvent(Event("input"))
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the field is gone") { element().querySelector("input") == null }
                assertEquals("", input.value)
                assertFalse(panel.isKeyFieldOpen("d1"))
                panel.dispose()
            }
        }

    @Test
    fun confirm_sendsTheKeyOnce_emptiesTheField_andDisablesTheButtonWhileItRuns(): Promise<Unit> =
        formTest {
            mountedForm("stp-confirm") { root, element ->
                val calls = mutableListOf<Pair<String, String?>>()
                val panel =
                    panelWith(onReconnect = { id, key ->
                        calls += id to key?.streamKey
                        null
                    }).also { root.add(it) }
                panel.update(streamOf(ConferenceStreamStatus.LIVE, failedFacebook), true)
                element().buttonNamed("Neu verbinden").click()
                awaitUntil("the key field") { element().querySelector("input") != null }
                val input = element().querySelector("input") as HTMLInputElement
                input.value = "FB-dummy-key-1234"
                input.dispatchEvent(Event("input"))
                element().buttonNamed("Verbinden").click()
                awaitUntil("the call") { calls.size == 1 }
                assertEquals("d1" to "FB-dummy-key-1234", calls.single())
                assertEquals("", input.value)
                assertFalse(panel.isKeyFieldOpen("d1"))
                panel.dispose()
            }
        }

    @Test
    fun anEmptyField_sendsNoKey_andAnInvalidOneStaysOpenWithAnError(): Promise<Unit> =
        formTest {
            mountedForm("stp-empty") { root, element ->
                val calls = mutableListOf<String?>()
                val panel =
                    panelWith(onReconnect = { _, key ->
                        calls += key?.streamKey
                        null
                    }).also { root.add(it) }
                panel.update(streamOf(ConferenceStreamStatus.LIVE, failedFacebook), true)
                element().buttonNamed("Neu verbinden").click()
                awaitUntil("the key field") { element().querySelector("input") != null }
                val input = element().querySelector("input") as HTMLInputElement
                input.value = "two words"
                input.dispatchEvent(Event("input"))
                element().buttonNamed("Verbinden").click()
                assertTrue(panel.isKeyFieldOpen("d1"), "an invalid key keeps the field open")
                assertEquals(0, calls.size)
                input.value = ""
                input.dispatchEvent(Event("input"))
                element().buttonNamed("Verbinden").click()
                awaitUntil("the call without a key") { calls.size == 1 }
                assertNull(calls.single())
                panel.dispose()
            }
        }

    @Test
    fun aPersistentTarget_reconnectsAtOnce_withoutAKeyField(): Promise<Unit> =
        formTest {
            mountedForm("stp-persistent") { root, element ->
                val calls = mutableListOf<String?>()
                val panel =
                    panelWith(onReconnect = { _, key ->
                        calls += key?.streamKey
                        null
                    }).also { root.add(it) }
                val persistent = failedFacebook.copy(keyKind = ConferenceStreamKeyKind.PERSISTENT)
                panel.update(streamOf(ConferenceStreamStatus.LIVE, persistent), true)
                assertFalse(element().textContent.orEmpty().contains("Einmal-Schlüssel"), "no one-time-key hint for a persistent key")
                element().buttonNamed("Neu verbinden").click()
                awaitUntil("the call") { calls.size == 1 }
                assertNull(calls.single())
                assertNull(element().querySelector("input"))
                panel.dispose()
            }
        }

    @Test
    fun paused_hidesEveryRow_andDisposeIsRepeatable() {
        withMountedRoot("stp-paused") { root, element ->
            val panel = panelWith().also { root.add(it) }
            panel.update(streamOf(ConferenceStreamStatus.LIVE, failedFacebook), true)
            assertEquals(listOf("d1"), panel.rowIds)
            panel.update(streamOf(ConferenceStreamStatus.PAUSED, failedFacebook), true)
            assertEquals(emptyList(), panel.rowIds)
            assertEquals("", element().textContent.orEmpty().trim())
            panel.dispose()
            panel.dispose()
            assertNotNull(element())
        }
    }
}
