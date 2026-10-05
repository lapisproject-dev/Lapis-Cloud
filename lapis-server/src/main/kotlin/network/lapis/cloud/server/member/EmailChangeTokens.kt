package network.lapis.cloud.server.member

import network.lapis.cloud.server.security.SessionTokens
import java.security.MessageDigest

/**
 * Welle V1.9.56 -- the two bearer tokens of an address change (link to the NEW address, link to the OLD address).
 * Reuses [SessionTokens]: 256-bit `SecureRandom` Base64url (43 characters, no padding), SHA-256 hex with a fresh
 * `MessageDigest` per call. Only the hash is ever stored; the raw token exists as a local variable between insert and
 * mail hand-off and never reaches a return value, a log line or an audit snapshot (`MemberEmailChangeTokenLeakTripwireTest`).
 */
internal object EmailChangeTokens {
    fun newRawToken(): String = SessionTokens.newRawToken()

    fun hash(raw: String): String = SessionTokens.hash(raw)

    /** Constant-time comparison of a stored hash against the hash of [raw]. */
    fun matches(
        storedHash: String?,
        raw: String,
    ): Boolean {
        if (storedHash == null) return false
        return MessageDigest.isEqual(storedHash.toByteArray(Charsets.UTF_8), hash(raw).toByteArray(Charsets.UTF_8))
    }
}
