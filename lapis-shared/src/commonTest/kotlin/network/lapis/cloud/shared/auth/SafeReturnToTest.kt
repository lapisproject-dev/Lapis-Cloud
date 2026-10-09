package network.lapis.cloud.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeReturnToTest {
    private val base = "/federation/oidc/authorize?"

    private fun assertAccepted(value: String) = assertEquals(value, safeReturnTo(value), "should be accepted: $value")

    private fun assertRejected(value: String?) = assertNull(safeReturnTo(value), "should be rejected: $value")

    @Test
    fun acceptsTheRealClaudeCodeUrl() {
        assertAccepted(
            base +
                "response_type=code&client_id=abc&redirect_uri=http%3A%2F%2F127.0.0.1%3A53682%2Fcallback" +
                "&code_challenge=E9Melhoe2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256" +
                "&scope=mcp%3Amember_read&resource=https%3A%2F%2Fhost%2Fmcp&state=xyz",
        )
    }

    @Test
    fun acceptsPlusInTheQuery() = assertAccepted(base + "response_type=code&client_id=a&scope=openid+profile")

    @Test
    fun acceptsTheFederationUrlWithNonce() = assertAccepted(base + "response_type=code&client_id=a&nonce=n-0S6_WzA2Mj&state=s")

    @Test
    fun acceptsExactlyTheMaximumLength() {
        val value = base + "x=" + "a".repeat(RETURN_TO_MAX_LENGTH - base.length - 2)
        assertEquals(RETURN_TO_MAX_LENGTH, value.length)
        assertAccepted(value)
    }

    @Test
    fun rejectsOneCharacterOverTheMaximum() = assertRejected(base + "x=" + "a".repeat(RETURN_TO_MAX_LENGTH - base.length - 1))

    @Test
    fun rejectsForeignTargets() {
        listOf(
            "https://evil.example",
            "//evil.example",
            "/\\evil.example",
            "\\/evil.example",
            "/federation/oidc/authorize.evil?x",
            "/federation/oidc/authorize/../../x?",
            "/federation/oidc/authorize%2f..?",
            "%2f%2fevil.example",
            "/%2F%2Fevil",
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,x",
            "/app#/dashboard",
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsControlAndWhitespaceCharacters() {
        listOf(
            base + "x=1\r\nSet-Cookie: a=b",
            base + "x=1\nfoo",
            base + "x=1 2",
            base + "x=1\t2",
            base + "x=1\u00002",
            base + "x=1\u007F2",
            base + "x=é",
            base + "x=а",
            "/federation/oidc/authorizе?x=1",
            "/federation/oidc/аuthorize?x=1",
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsDecodedCrlfAndMalformedOrDoubleEncodedEscapes() {
        listOf(
            base + "x=%252F",
            base + "x=%252f",
            base + "x=%zz",
            base + "x=%2",
            base + "x=%",
            base + "x=%2g",
        ).forEach(::assertRejected)
        // A still-encoded %0d%0a is a well-formed single escape inside the query (never a raw CR/LF); the decoded form is rejected above.
        assertAccepted(base + "x=%0d%0a")
    }

    @Test
    fun rejectsWrongCaseAndShape() {
        listOf(
            "/Federation/oidc/authorize?x=1",
            "/FEDERATION/OIDC/AUTHORIZE?x=1",
            base + "x#frag",
            "/federation/oidc/authorize",
            "/federation/oidc/authorizeX?x=1",
            "/federation/oidc/authorize;x?x=1",
            "?" + "x=1",
            "",
        ).forEach(::assertRejected)
        assertRejected(null)
    }
}
