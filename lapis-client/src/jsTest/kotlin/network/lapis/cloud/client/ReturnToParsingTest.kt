package network.lapis.cloud.client

import network.lapis.cloud.shared.auth.safeReturnTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private external fun encodeURIComponent(value: String): String

/**
 * V1.9.89: [parseHashQueryParamStrict] -- the security-relevant sibling of the lenient [parseHashQueryParam]. Pure, no DOM.
 */
class ReturnToParsingTest {
    private val target = "/federation/oidc/authorize?response_type=code&client_id=a&redirect_uri=http%3A%2F%2F127.0.0.1%3A1%2Fcb&state=s"

    // What the server's URLEncoder produces for the hash (the "%" of "%3A" becomes "%253A", "&" becomes "%26").
    private fun serverEncoded(value: String): String = encodeURIComponent(value)

    @Test
    fun normalCase() {
        assertEquals("abc", parseHashQueryParamStrict("#/login?returnTo=abc", "returnTo"))
    }

    @Test
    fun malformedEscapeYieldsNullWhileTheLenientParserKeepsItsRawFallback() {
        val hash = "#/login?returnTo=%E0%A4%A"
        assertNull(parseHashQueryParamStrict(hash, "returnTo"))
        assertEquals("%E0%A4%A", parseHashQueryParam(hash, "returnTo"))
    }

    @Test
    fun duplicatedKeyYieldsNull() {
        assertNull(parseHashQueryParamStrict("#/login?returnTo=a&returnTo=b", "returnTo"))
    }

    @Test
    fun noQueryOrNoKeyYieldsNull() {
        assertNull(parseHashQueryParamStrict("#/login", "returnTo"))
        assertNull(parseHashQueryParamStrict("#/login?other=1", "returnTo"))
    }

    @Test
    fun serverEncodedFormIsDecodedExactlyOnceAndPassesTheAllowlist() {
        val hash = "#/login?returnTo=" + serverEncoded(target)
        val decoded = parseHashQueryParamStrict(hash, "returnTo")
        assertEquals(target, decoded)
        assertNotNull(safeReturnTo(decoded))
        // The decoded value still holds its single-encoded escapes (%3A), proving no second decode took place.
        assertEquals(true, decoded!!.contains("%3A"))
    }
}
