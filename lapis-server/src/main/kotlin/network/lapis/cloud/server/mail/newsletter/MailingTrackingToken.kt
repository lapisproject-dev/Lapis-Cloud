package network.lapis.cloud.server.mail.newsletter

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Welle V1.9.15 -- issues and verifies the opaque tokens embedded in click links and open pixels.
 *
 * Token shapes (all ASCII, at most [MAX_LENGTH] characters):
 *  - click: `1.<nonce>.<linkIndex>.<mac>`
 *  - open:  `1.<nonce>.o.<mac>`
 *
 * `nonce` is 16 `SecureRandom` bytes (base64url, 22 chars) and exists ONLY in memory and inside
 * the mail -- the database stores just `hex(SHA-256(nonce))` ([hashNonce]), so a database leak
 * yields no usable link. `mac` is the first 16 bytes of `HMAC-SHA256(key, "c|nonce|idx")` /
 * `"o|nonce"`, compared in constant time; the `c`/`o` domain prefix means a click token can never
 * be replayed as an open token and vice versa, and the index is covered by the MAC so it cannot be
 * re-pointed. A fresh [Mac]/[MessageDigest] instance is created per call (thread-safe).
 *
 * [parse] returns `null` for EVERY malformed/forged input (never throws), after a length check
 * that runs BEFORE any splitting (DoS guard). Nothing in this class logs.
 */
class MailingTrackingToken(
    private val key: ByteArray,
) {
    data class Issued(
        val nonce: String,
        val hashHex: String,
    )

    sealed interface Parsed {
        data class Click(
            val nonce: String,
            val linkIndex: Int,
        ) : Parsed

        data class Open(
            val nonce: String,
        ) : Parsed
    }

    private val random = SecureRandom()

    fun issue(): Issued {
        val bytes = ByteArray(NONCE_BYTES)
        random.nextBytes(bytes)
        val nonce = B64URL.encodeToString(bytes)
        return Issued(nonce = nonce, hashHex = hashNonce(nonce))
    }

    fun clickToken(
        nonce: String,
        linkIndex: Int,
    ): String {
        require(linkIndex in 0..MAX_LINK_INDEX) { "linkIndex out of range" }
        return "$VERSION.$nonce.$linkIndex.${mac("c|$nonce|$linkIndex")}"
    }

    fun openToken(nonce: String): String = "$VERSION.$nonce.$OPEN_MARKER.${mac("o|$nonce")}"

    fun parse(token: String): Parsed? {
        if (token.length > MAX_LENGTH) return null
        val parts = token.split('.')
        if (parts.size != PART_COUNT || parts[0] != VERSION) return null
        val nonce = parts[1]
        val indexPart = parts[2]
        val mac = parts[3]
        if (!B64_22.matches(nonce) || !B64_22.matches(mac)) return null
        return if (indexPart == OPEN_MARKER) {
            if (!macMatches(input = "o|$nonce", presented = mac)) return null
            Parsed.Open(nonce)
        } else {
            if (!INDEX.matches(indexPart)) return null
            val index = indexPart.toInt()
            if (index > MAX_LINK_INDEX) return null
            if (!macMatches(input = "c|$nonce|$index", presented = mac)) return null
            Parsed.Click(nonce = nonce, linkIndex = index)
        }
    }

    private fun mac(input: String): String = B64URL.encodeToString(rawMac(input))

    private fun rawMac(input: String): ByteArray {
        val hmac = Mac.getInstance(HMAC_ALGORITHM)
        hmac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return hmac.doFinal(input.toByteArray(Charsets.US_ASCII)).copyOf(MAC_BYTES)
    }

    private fun macMatches(
        input: String,
        presented: String,
    ): Boolean = MessageDigest.isEqual(mac(input).toByteArray(Charsets.US_ASCII), presented.toByteArray(Charsets.US_ASCII))

    companion object {
        const val MAX_LENGTH = 64
        private const val VERSION = "1"
        private const val OPEN_MARKER = "o"
        private const val NONCE_BYTES = 16
        private const val MAC_BYTES = 16
        private const val PART_COUNT = 4
        private const val MAX_LINK_INDEX = 199
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val B64URL = Base64.getUrlEncoder().withoutPadding()
        private val B64_22 = Regex("^[A-Za-z0-9_-]{22}$")
        private val INDEX = Regex("^(0|[1-9][0-9]{0,2})$")

        fun hashNonce(nonce: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
