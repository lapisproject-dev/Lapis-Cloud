package network.lapis.cloud.server.mail.outbox

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 of a normalised e-mail address under a purpose-separated sub-key -- the lookup handle of `mail_outbox` (V80). It lets the
 * data-subject export / erasure (`MailOutboxPersonalData`) find OPEN rows by address without ever decrypting a payload. The sub-key
 * is `HMAC-SHA256(masterKey, "lapis-mail-outbox-lookup-v1")`, so the secret-box key itself is never used as an HMAC key
 * (domain separation). A fresh [Mac] per call: [Mac] is not thread-safe.
 */
class MailRecipientHasher(
    masterKey: ByteArray,
) {
    private val subKey: ByteArray = hmac(key = masterKey, data = DOMAIN.toByteArray(Charsets.UTF_8))

    /** Lower-cased, trimmed -- the same normalisation on write and on lookup. */
    fun hash(address: String): String = HexFormat.of().formatHex(hmac(key = subKey, data = normalise(address).toByteArray(Charsets.UTF_8)))

    override fun toString(): String = "MailRecipientHasher(<redacted>)"

    companion object {
        const val DOMAIN = "lapis-mail-outbox-lookup-v1"

        fun normalise(address: String): String = address.trim().lowercase()

        private fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}
