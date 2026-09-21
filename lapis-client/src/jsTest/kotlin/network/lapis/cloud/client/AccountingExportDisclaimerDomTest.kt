package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.AccountingExportConnectionDto
import network.lapis.cloud.shared.domain.AccountingExportProvider
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.30 (W4c), Review-Fix: the 0 % VAT notice of the accounting export loads its text over RPC. A failed load must not leave a
 * permanent "Wird geladen …" (W3 rule): it shows the error state with a retry button, and the confirm button stays locked as long as
 * there is no checksum to acknowledge.
 */
class AccountingExportDisclaimerDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private val connection =
        AccountingExportConnectionDto(
            provider = AccountingExportProvider.LEXOFFICE,
            connected = true,
            tokenLast4 = "1234",
            connectedCompanyName = null,
            lastTestedAt = null,
            zeroVatAcknowledged = false,
            zeroVatAcknowledgedAt = null,
        )

    private fun HTMLElement.confirmButton(): HTMLElement {
        val buttons = querySelectorAll("button")
        return assertNotNull(
            (0 until buttons.length).map { buttons.item(it) as HTMLElement }.firstOrNull { it.textContent?.trim() == "Bestätigen" },
            "no confirm button",
        )
    }

    private fun HTMLElement.retryButton(): HTMLElement? {
        val buttons = querySelectorAll("button")
        return (0 until buttons.length).map { buttons.item(it) as HTMLElement }.firstOrNull { it.textContent?.trim() == "Erneut versuchen" }
    }

    @Test
    fun aFailedLoad_showsTheErrorStateInsteadOfALoadingTextForever_andKeepsTheConfirmButtonLocked_thenRetryLoadsTheText(): Promise<Unit> =
        test {
            var failing = true
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    failing -> StubResponse(networkError = true)
                    else ->
                        rpcResult(
                            request.json.id as Int,
                            """{"version":"v1","text":"Hinweistext zur Umsatzsteuer","sha256":"abc123"}""",
                        )
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("accounting-export-disclaimer") { root, element ->
                    renderZeroVatSection(root, AccountingExportProvider.LEXOFFICE, connection) {}
                    awaitUntil("the failed load shows the retry button") { element().retryButton() != null }
                    val text = element().textContent.orEmpty()
                    assertFalse(text.contains("Wird geladen"), "a failed load must not leave a permanent loading text: $text")
                    assertTrue(text.contains("Die Daten konnten nicht geladen werden."), "the error state names the problem: $text")
                    assertTrue(element().confirmButton().hasAttribute("disabled"), "no checksum, no confirmation")

                    failing = false
                    element().retryButton()!!.click()
                    awaitUntil("the retry loads the notice text") {
                        element().textContent.orEmpty().contains("Hinweistext zur Umsatzsteuer")
                    }
                    assertEquals(null, element().retryButton(), "the error state is gone after a successful retry")
                    assertFalse(element().confirmButton().hasAttribute("disabled"), "with a checksum the confirm button is released")
                }
            }
        }
}
