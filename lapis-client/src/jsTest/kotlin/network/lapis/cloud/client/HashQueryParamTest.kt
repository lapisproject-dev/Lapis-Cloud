package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [parseHashQueryParam] -- the pure, DOM-free parsing core [hashQueryParam] extracts a
 * value from (e.g. `#/password-reset?token=abc123` -> `"abc123"`). Same DOM-free unit-test posture
 * as [NavRouteMatchTest]/[ValidationTest] (no rendering harness exists in this module, and this
 * function in particular is deliberately pure specifically so it CAN be tested that way -- see its
 * own KDoc). Exercises exactly the edge cases the hand-rolled parser in [Routing.kt] documents as
 * its own reasoning for existing instead of `URLSearchParams`: no `?` at all, no `=` in a pair, a
 * missing key, `&`-separated multi-pair hashes, and a `decodeURIComponent` throw on malformed
 * percent-encoding.
 */
class HashQueryParamTest {
    @Test
    fun noQuestionMark_returnsNull() {
        assertNull(parseHashQueryParam("#/password-reset", "token"))
    }

    @Test
    fun questionMarkButKeyAbsent_returnsNull() {
        assertNull(parseHashQueryParam("#/password-reset?other=xyz", "token"))
    }

    @Test
    fun pairWithoutEquals_isSkipped_keyStillFound() {
        // "garbage" has no "=" at all -- must be skipped, not mistaken for a key with an empty value.
        assertEquals("abc123", parseHashQueryParam("#/password-reset?garbage&token=abc123", "token"))
    }

    @Test
    fun singlePair_matchingKey_returnsValue() {
        assertEquals("abc123", parseHashQueryParam("#/password-reset?token=abc123", "token"))
    }

    @Test
    fun ampersandSeparatedPairs_findsCorrectKeyAmongSeveral() {
        assertEquals(
            "abc123",
            parseHashQueryParam("#/verify-email?foo=bar&token=abc123&baz=qux", "token"),
        )
    }

    @Test
    fun emptyValue_returnsEmptyString_notNull() {
        assertEquals("", parseHashQueryParam("#/password-reset?token=", "token"))
    }

    @Test
    fun percentEncodedValue_isDecoded() {
        assertEquals("a b+c", parseHashQueryParam("#/password-reset?token=a%20b%2Bc", "token"))
    }

    @Test
    fun malformedPercentEncoding_decodeURIComponentThrows_rawValueReturnedInstead() {
        // "%" alone is not a valid percent-escape -- the global decodeURIComponent throws a
        // URIError for it, which jsDecodeUriComponent must catch and fall back to the raw value
        // (see its own KDoc) rather than let the exception propagate out of parseHashQueryParam.
        assertEquals("100%", parseHashQueryParam("#/password-reset?token=100%", "token"))
    }

    @Test
    fun emptyHash_returnsNull() {
        assertNull(parseHashQueryParam("", "token"))
    }

    @Test
    fun questionMarkAtVeryEnd_emptyQuery_returnsNull() {
        assertNull(parseHashQueryParam("#/password-reset?", "token"))
    }
}
