package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review finding fix (Welle V1.4.5.4 "sevDesk-Live-Anbindung", MINOR Testabdeckung).
 * [AccountingExportProvider.displayName] is -- per its own KDoc -- "die EINZIGE Stelle, an der
 * ein Fremdmarken-Name in diesem Codebase steht", read by both server (blocker detail texts,
 * [network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer]) and client (every provider-labelled
 * UI element on `AccountingExportScreen`). This is the cheapest possible regression wall: if a
 * future wave adds a provider to [AccountingExportProvider] without extending the `when` here,
 * this test goes red at the exact same moment the (non-exhaustive if `else` were ever added)
 * production `when` would otherwise silently start throwing or omitting a brand name.
 */
class AccountingExportProviderDisplayNameTest {
    @Test
    fun lexoffice_displayName_isLexwareOffice() {
        assertEquals("Lexware Office", AccountingExportProvider.LEXOFFICE.displayName)
    }

    @Test
    fun sevdesk_displayName_isSevDesk() {
        assertEquals("sevDesk", AccountingExportProvider.SEVDESK.displayName)
    }

    @Test
    fun everyProvider_hasANonBlankDisplayName() {
        AccountingExportProvider.entries.forEach { provider ->
            assertTrue(provider.displayName.isNotBlank(), "displayName for $provider must not be blank")
        }
    }
}
