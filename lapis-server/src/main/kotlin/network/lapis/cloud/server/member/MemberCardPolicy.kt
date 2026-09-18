package network.lapis.cloud.server.member

import network.lapis.cloud.shared.domain.MemberCardCode
import java.security.SecureRandom

/**
 * Pure fachlogik for the member-card bearer code -- no DB access, no transaction, mirrors
 * [network.lapis.cloud.server.events.EventTicketPolicy]'s own split between JVM-only minting
 * (here) and the shared canonicalization grammar ([MemberCardCode], `lapis-shared`).
 */
internal object MemberCardPolicy {
    /** 10 bytes = 80 bits -> exactly [MemberCardCode.CANONICAL_LENGTH] (16) base32 characters, no padding needed. */
    const val CODE_BYTES: Int = 10

    private val RANDOM = SecureRandom()

    /** Fresh raw code in canonical form -- cryptographically random. */
    fun newRawCode(): String {
        val bytes = ByteArray(CODE_BYTES)
        RANDOM.nextBytes(bytes)
        return encode(bytes)
    }

    /** The public verification page's URL for [rawCode] -- the ONE place this URL shape is built (QR content, verification-page's own link). */
    fun verifyUrl(
        baseUrl: String,
        rawCode: String,
    ): String = "${baseUrl.trimEnd('/')}/ausweis?code=$rawCode"

    private fun encode(bytes: ByteArray): String {
        // Same bit-packing idiom as EventTicketPolicy.encode -- 10 bytes (80 bits) pack into
        // exactly 16 groups of 5 bits with zero leftover, hence no padding character is ever needed.
        val builder = StringBuilder(MemberCardCode.CANONICAL_LENGTH)
        var buffer = 0L
        var bitsInBuffer = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                val index = ((buffer shr bitsInBuffer) and 0x1F).toInt()
                builder.append(MemberCardCode.ALPHABET[index])
            }
        }
        return builder.toString()
    }
}
