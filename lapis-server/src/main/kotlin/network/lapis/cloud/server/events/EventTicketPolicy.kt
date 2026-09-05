package network.lapis.cloud.server.events

import network.lapis.cloud.shared.domain.EventTicketCode
import java.security.SecureRandom

/**
 * Pure fachlogik for the V1.4.3.2 ticket code -- no DB access, no transaction, same posture
 * [EventPolicy] already establishes for the rest of this domain.
 *
 * **The code GRAMMAR (alphabet, length, canonicalization) lives in [EventTicketCode]
 * (`lapis-shared`)** -- the client's `EventCheckInScreen` needs the SAME canonicalization the
 * server applies (local pre-validation before a network round-trip), so it cannot live JVM-only.
 * This object owns only what genuinely IS JVM-only: [SecureRandom]-backed minting, and the two URL
 * builders used consistently across the mail/ticket-page/QR/PDF surfaces.
 */
internal object EventTicketPolicy {
    /** 10 bytes = 80 bits -> exactly [EventTicketCode.CANONICAL_LENGTH] (16) base32 characters, no padding needed. */
    const val CODE_BYTES: Int = 10

    private val RANDOM = SecureRandom()

    /** Fresh raw code in canonical form -- cryptographically random, see [EventTicketCode] KDoc "Why 16 characters" for the entropy/threat-model reasoning. */
    fun newRawCode(): String {
        val bytes = ByteArray(CODE_BYTES)
        RANDOM.nextBytes(bytes)
        return encode(bytes)
    }

    /** The public ticket page's URL for [rawCode] -- the ONE place this URL shape is built (mail, ticket page's own PDF-download link, QR content all call this). */
    fun ticketUrl(
        baseUrl: String,
        slug: String,
        rawCode: String,
    ): String = "${baseUrl.trimEnd('/')}/veranstaltung/$slug/ticket?code=$rawCode"

    /** The ticket-PDF endpoint's URL for [rawCode] -- see [ticketUrl]. */
    fun ticketPdfUrl(
        baseUrl: String,
        slug: String,
        rawCode: String,
    ): String = "${baseUrl.trimEnd('/')}/veranstaltung/$slug/ticket.pdf?code=$rawCode"

    private fun encode(bytes: ByteArray): String {
        // Bit-packing analogous to standard Base32 (RFC 4648 section 6), just with the
        // Crockford alphabet substituted in -- 10 bytes (80 bits) pack into exactly 16 groups
        // of 5 bits with zero leftover, hence no padding character is ever needed.
        val builder = StringBuilder(EventTicketCode.CANONICAL_LENGTH)
        var buffer = 0L
        var bitsInBuffer = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                val index = ((buffer shr bitsInBuffer) and 0x1F).toInt()
                builder.append(EventTicketCode.ALPHABET[index])
            }
        }
        return builder.toString()
    }
}
