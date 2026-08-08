package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mail-merge/Postal-Dispatch UI wave (design decision D3) -- covers the pure, DOM-independent
 * URL builders in [MailmergeHttp], same scope posture as [DsgvoRightsScreenTest]'s
 * `dsgvoExportUrl` coverage (no DOM/rendering test harness exists in this module).
 */
class MailmergeHttpTest {
    @Test
    fun invoiceUrl_buildsTheExactHttpRoutePath() {
        assertEquals(
            "/api/mailmerge/contributions/contribution-1/invoice.pdf",
            MailmergeHttp.invoiceUrl("contribution-1"),
        )
    }

    @Test
    fun receiptUrl_buildsTheExactHttpRoutePath() {
        assertEquals(
            "/api/mailmerge/donations/journal-entry-1/receipt.pdf",
            MailmergeHttp.receiptUrl("journal-entry-1"),
        )
    }
}
