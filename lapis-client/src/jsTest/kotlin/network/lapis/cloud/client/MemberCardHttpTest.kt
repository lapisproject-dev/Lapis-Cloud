package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- deckt die eine DOM-freie Funktion von
 * [MemberCardHttp] ab, gleiche Testtiefe wie [BackupHttpTest]/[BankStatementHttpTest] (fuer das
 * Formular-Submit selbst existiert in diesem Modul keine DOM-Harness).
 *
 * Die zweite Zusicherung ist die eigentlich wichtige: die URL muss relativ und gleich-origin sein.
 * Eine absolute URL wuerde das `SameSite=Strict`-Cookie umgehen, auf dem die CSRF-Sicherheit dieses
 * zustandsaendernden Downloads beruht (siehe [MemberCardHttp] KDoc).
 */
class MemberCardHttpTest {
    @Test
    fun cardPdfUrl_usesTheMemberScopedRoute() {
        assertEquals(
            "/api/members/1b69b54a-25c9-43bf-8cac-063f9f28be01/card.pdf",
            MemberCardHttp.cardPdfUrl("1b69b54a-25c9-43bf-8cac-063f9f28be01"),
        )
    }

    @Test
    fun cardPdfUrl_isSameOriginRelative() {
        val url = MemberCardHttp.cardPdfUrl("abc")
        assertTrue(url.startsWith("/api/"), "expected a same-origin, root-relative path but was: $url")
        assertTrue(!url.contains("://"), "expected no scheme/host in: $url")
    }
}
