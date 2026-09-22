package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.i18n.I18n
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit fix B2 (V1.4.31): switching the language while a video conference is live must not disconnect it silently.
 * `I18n.language` restarts the whole root and thereby runs the conference screen's destroy hook (the real teardown:
 * `disconnect()`), so the switcher asks first -- [requestLanguageChange]. Guarded with a REAL mounted
 * `conferenceScreenRoot` whose teardown is observed.
 */
class LanguageChangeDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                ConferenceCallPresence.set(live = false)
                closeOpenModals()
            }
        }

    private fun modalButton(text: String): HTMLElement {
        val buttons = document.querySelectorAll(".modal.show .modal-footer button")
        return (0 until buttons.length)
            .map { buttons.item(it) as HTMLElement }
            .first { it.textContent?.trim() == text }
    }

    @Test
    fun noLiveCall_switchesImmediately_withoutADialog(): Promise<Unit> =
        test {
            var applied = 0
            requestLanguageChange(code = "en", currentLanguage = "de", callLive = false) { applied++ }
            assertEquals(1, applied)
            assertEquals(0, document.querySelectorAll(".modal").length)
        }

    @Test
    fun choosingTheActiveLanguage_isANoOp_evenDuringACall(): Promise<Unit> =
        test {
            var applied = 0
            requestLanguageChange(code = "de", currentLanguage = "de", callLive = true) { applied++ }
            assertEquals(0, applied)
            assertEquals(0, document.querySelectorAll(".modal").length)
        }

    @Test
    fun liveCall_cancelKeepsTheCallAndTheLanguage_confirmSwitches_theMountedScreenTeardownDoesNotRunBefore(): Promise<Unit> =
        test {
            withMountedRoot("language-change-conference") { root, _ ->
                var teardowns = 0
                root.conferenceScreenRoot { teardowns++ }
                ConferenceCallPresence.set(live = true)
                var applied = 0

                requestLanguageChange(code = "en", currentLanguage = "de") { applied++ }
                awaitUntil("the confirm modal is shown") { document.querySelectorAll(".modal.show").length > 0 }
                assertEquals(0, applied, "the switch must wait for the confirmation while a call is live")
                assertEquals(0, teardowns, "the conference teardown (disconnect) must not run while the dialog is open")
                assertTrue(
                    document.querySelector(".modal.show")!!.textContent!!.contains("beendet die laufende Besprechung"),
                    "the dialog names the consequence",
                )

                modalButton("Abbrechen").click()
                assertEquals(0, applied, "cancel keeps the language")
                assertEquals(0, teardowns, "cancel keeps the call")
                assertTrue(ConferenceCallPresence.live)

                closeOpenModals()
                requestLanguageChange(code = "en", currentLanguage = "de") { applied++ }
                awaitUntil("the confirm modal is shown again") { document.querySelectorAll(".modal.show").length > 0 }
                assertNotNull(modalButton("Sprache wechseln und Besprechung beenden")).click()
                assertEquals(1, applied, "confirming performs the switch exactly once")
            }
        }

    @Test
    fun aVisibleAmount_followsTheRealLanguageSwitch_withoutAScreenRebuild(): Promise<Unit> =
        test {
            withMountedRoot("language-change-money") { root, element ->
                root.moneySpan(1234.5.toDecimal())
                assertEquals("1.234,50$NBSP€", element().textContent)
                try {
                    // the real switch (the shell's `applyLanguage`): the setter restarts the root, the existing widgets re-render
                    requestLanguageChange(code = "en", currentLanguage = "de", callLive = false) { I18n.language = "en" }
                    awaitUntil("the amount is shown in English") { element().textContent == "€1,234.50" }
                } finally {
                    I18n.language = "de"
                }
                awaitUntil("the amount is back in German") { element().textContent == "1.234,50$NBSP€" }
            }
        }
}
