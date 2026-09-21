package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.26 (W2), Review-Fix: the STATE machine of the migrated screens in a REAL mounted [Root].
 *
 * Under Karma there is no server, so every RPC of a screen fails with a 404 and `guarded {}` turns that into
 * `null` -- exactly the "first load failed" situation. The contract these tests pin: once the error box with
 * `Erneut versuchen` stands, typing into the search field must NOT wipe it and replace it with an empty text
 * ("Noch keine ... erfasst." would be a false statement about the data, and the retry path would be gone).
 * The pure helpers in `HotTableFiltersTest` cannot see this; only a mounted screen can.
 */
class HotTableFailedStateDomTest {
    // NOT `AppScope.promise`: AppScope has no SupervisorJob, one failing test would cancel it for the whole run.
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun errorBoxCount(element: HTMLElement): Int = element.querySelectorAll(".alert-danger").length

    private suspend fun awaitErrorBox(element: () -> HTMLElement) {
        repeat(200) {
            if (errorBoxCount(element()) > 0) return
            delay(50)
        }
        assertTrue(false, "the failed first load never produced the error box")
    }

    private suspend fun assertErrorSurvivesTyping(
        id: String,
        render: (Root) -> Unit,
    ) {
        withMountedRoot(id) { root, element ->
            render(root)
            awaitErrorBox(element)
            val input = assertNotNull(element().querySelector("input[type=text]") as? HTMLInputElement, "no search field")
            input.value = "xyz"
            input.dispatchEvent(Event("input"))
            input.dispatchEvent(Event("change"))
            // Longer than the 300 ms debounce of the search fields.
            delay(700)
            assertTrue(errorBoxCount(element()) > 0, "$id: typing into the search wiped the error box")
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Erneut versuchen"), "$id: the retry button is gone")
            assertTrue(!text.contains("Noch keine"), "$id: a false 'no data yet' statement replaced the error")
            assertTrue(!text.contains("Keine Ehrungen erfasst"), "$id: a false 'no data yet' statement replaced the error")
            assertTrue(!text.contains("Kein aktiver externer Spender"), "$id: a false 'no data yet' statement replaced the error")
        }
    }

    @Test
    fun sepaMandates_errorBoxSurvivesTypingIntoTheSearch(): Promise<Unit> =
        test { assertErrorSurvivesTyping("hot-failed-sepa-mandates") { renderSepaMandatesScreen(it) } }

    @Test
    fun paymentTransactions_errorBoxSurvivesTypingIntoTheSearch(): Promise<Unit> =
        test { assertErrorSurvivesTyping("hot-failed-payments") { renderPaymentTransactionsScreen(it) } }

    @Test
    fun dunningCases_errorBoxSurvivesTypingIntoTheSearch(): Promise<Unit> =
        test { assertErrorSurvivesTyping("hot-failed-dunning") { renderDunningCasesScreen(it) } }

    @Test
    fun memberHonors_errorBoxSurvivesTypingIntoTheSearch(): Promise<Unit> =
        test { assertErrorSurvivesTyping("hot-failed-honors") { renderMemberHonorsScreen(it, null) } }

    @Test
    fun donors_errorBoxSurvivesTypingIntoTheSearch(): Promise<Unit> =
        test { assertErrorSurvivesTyping("hot-failed-donors") { renderDonorsScreen(it) } }
}
