package network.lapis.cloud.server.federation

import java.security.MessageDigest
import java.util.Base64

/**
 * PKCE (RFC 7636) `S256` code-challenge computation -- this codebase never implements the `plain`
 * method (see `network.lapis.cloud.server.routes.OidcRoutes` KDoc). `code_verifier` generation
 * itself reuses [network.lapis.cloud.server.security.SessionTokens.newRawToken] (256-bit random,
 * Base64URL-without-padding -- a subset of RFC 7636's allowed `[A-Za-z0-9-._~]` charset, well
 * within the 43-128 char length bound), not a bespoke generator.
 */
object OidcPkce {
    /** `BASE64URL(SHA256(codeVerifier))`, no padding -- RFC 7636 `S256` transform. */
    fun codeChallengeS256(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private val S256_CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")

    /** An S256 challenge is the unpadded Base64URL of a 32-byte digest: exactly 43 URL-safe chars. */
    fun isWellFormedS256Challenge(codeChallenge: String): Boolean = S256_CHALLENGE.matches(codeChallenge)
}
