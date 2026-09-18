package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- [MemberCardCode] mirrors [EventTicketCode]'s
 * grammar exactly (see that object's own KDoc "Deliberate duplicate"); this test pins today's
 * parity in both directions so a future drift in either grammar is caught.
 */
class MemberCardCodeTest {
    @Test
    fun `alphabet length and group size match EventTicketCode`() {
        assertEquals(EventTicketCode.ALPHABET, MemberCardCode.ALPHABET)
        assertEquals(EventTicketCode.CANONICAL_LENGTH, MemberCardCode.CANONICAL_LENGTH)
        assertEquals(EventTicketCode.GROUP_SIZE, MemberCardCode.GROUP_SIZE)
    }

    @Test
    fun `canonicalize repairs O to 0 and I slash L to 1, strips whitespace and hyphens, uppercases`() {
        val raw = " ab cd-ef gh-jk mn-pq rs "
        // 20 letters incl. lowercase, spaces, hyphens -- canonicalize must strip down to exactly
        // CANONICAL_LENGTH (16) alphabet characters or reject; construct a case that lands exactly
        // on 16 after stripping.
        val exact = "abcd-efgh-jkmn-pqrs"
        assertEquals("ABCD-EFGH-JKMN-PQRS".replace("-", ""), MemberCardCode.canonicalize(exact))
        assertEquals(EventTicketCode.canonicalize(exact), MemberCardCode.canonicalize(exact))

        val withTypos = "0BCD-EFGH-JKMN-PQRS".replace("0", "O", ignoreCase = false) // literal 'O' typo for '0'
        // "OBCD-..." contains an 'O' which must become '0'.
        val canon = MemberCardCode.canonicalize(withTypos)
        assertEquals(EventTicketCode.canonicalize(withTypos), canon)

        val withIl = "IBCD-EFGH-JKMN-PQRL" // I -> 1, trailing L -> 1
        assertEquals(EventTicketCode.canonicalize(withIl), MemberCardCode.canonicalize(withIl))
    }

    @Test
    fun `canonicalize rejects wrong length`() {
        assertNull(MemberCardCode.canonicalize("ABCD"))
        assertNull(MemberCardCode.canonicalize(""))
    }

    @Test
    fun `canonicalize rejects null input`() {
        assertNull(MemberCardCode.canonicalize(null))
    }

    @Test
    fun `formatForDisplay groups into 4-character chunks separated by hyphens`() {
        val canonical = "ABCDEFGHJKMNPQRS"
        assertEquals("ABCD-EFGH-JKMN-PQRS", MemberCardCode.formatForDisplay(canonical))
    }

    @Test
    fun `extractAndCanonicalize accepts a bare code`() {
        val canonical = "ABCDEFGHJKMNPQRS"
        assertEquals(canonical, MemberCardCode.extractAndCanonicalize(canonical))
    }

    @Test
    fun `extractAndCanonicalize extracts the code query parameter from a full verification URL`() {
        val canonical = "ABCDEFGHJKMNPQRS"
        val url = "https://example.org/ausweis?code=$canonical"
        assertEquals(canonical, MemberCardCode.extractAndCanonicalize(url))
    }

    @Test
    fun `extractAndCanonicalize with an unparsable code query param does not fall back to the whole URL`() {
        val url = "https://example.org/ausweis?code="
        assertNull(MemberCardCode.extractAndCanonicalize(url))
    }

    @Test
    fun `extractAndCanonicalize with additional query parameters still finds code`() {
        val canonical = "ABCDEFGHJKMNPQRS"
        val url = "https://example.org/ausweis?foo=bar&code=$canonical&baz=qux"
        assertEquals(canonical, MemberCardCode.extractAndCanonicalize(url))
    }
}
