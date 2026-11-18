package network.lapis.cloud.server.partg

import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.PartgReviewerRules
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- the secrets of the auditor's access, pure functions. Only HASHES are ever stored: the link token (256 bit random, hashed by
 * [SessionTokens.hash]) and the 6-digit code (hashed together with the invitation id, so a leaked hash of one invitation cannot be replayed on
 * another). A 6-digit code is NOT strong by itself: the strength is the limit of 5 tries per code, 3 burned codes per invitation (then locked), 3
 * codes per hour and 10 codes in total, all counted under a row lock (`PartgReviewerAccess`).
 */
internal object PartgReviewerTokens {
    private val random = SecureRandom()

    /** The raw link token: 256 bit, Base64url without padding. Never logged, never stored. */
    fun newLinkToken(): String = SessionTokens.newRawToken()

    fun hash(raw: String): String = SessionTokens.hash(raw)

    /** A fresh 6-digit code, uniformly drawn (`nextInt(1_000_000)`), left-padded with zeros. */
    fun newCode(): String = random.nextInt(1_000_000).toString().padStart(PartgReviewerRules.CODE_DIGITS, '0')

    /** SHA-256 (hex) over `"<invitation id>:<code>"`; a NEW [MessageDigest] per call (thread safety). */
    fun codeHash(
        invitationId: Uuid,
        code: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$invitationId:$code".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Constant-time comparison of two hex hashes. A missing stored hash is compared against a dummy of the same length first, so the time does not
     * tell "no code is pending" from "wrong code".
     */
    fun matches(
        storedHex: String?,
        candidateHex: String,
    ): Boolean {
        val candidate = candidateHex.toByteArray(Charsets.UTF_8)
        if (storedHex == null) {
            MessageDigest.isEqual(DUMMY_HASH.toByteArray(Charsets.UTF_8), candidate)
            return false
        }
        return MessageDigest.isEqual(storedHex.toByteArray(Charsets.UTF_8), candidate)
    }

    /** The alphabet and length of a link token: 32 random bytes as Base64url without padding are 43 characters. */
    fun plausibleLinkToken(raw: String?): Boolean = raw != null && raw.length in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS && raw.all(::inBase64Url)

    private fun inBase64Url(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    private const val MIN_TOKEN_CHARS = 16
    private const val MAX_TOKEN_CHARS = 64
    private val DUMMY_HASH = "0".repeat(64)
}
