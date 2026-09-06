package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.5.2 "DATEV-Format-Export" -- covers the pure, DOM-independent URL builder in
 * [DatevHttp], same scope posture as [SepaHttpTest]/[MailmergeHttpTest].
 */
class DatevHttpTest {
    @Test
    fun buchungsstapelUrl_buildsTheExactHttpRoutePath() {
        assertEquals(
            "/api/accounting/datev/buchungsstapel.csv?from=2026-01-01&to=2026-01-31",
            DatevHttp.buchungsstapelUrl(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31)),
        )
    }
}
