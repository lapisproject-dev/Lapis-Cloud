package network.lapis.cloud.server.agreementtexts

import java.security.MessageDigest

/**
 * V1.9.134 -- the hash of an agreement text: SHA-256 over `"$versionLabel\n$body"` in UTF-8, lower-case hex. This is exactly the formula of
 * [network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer.SHA256] and [network.lapis.cloud.server.rpc.FriendTermsDisclaimer.SHA256],
 * so a database version and the built-in placeholder are proven by the same acknowledgment columns. A fresh [MessageDigest] per call
 * (thread-safe).
 */
internal object AgreementTextHash {
    fun of(
        versionLabel: String,
        body: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$versionLabel\n$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Constant-time comparison of two hex hashes; a malformed [providedSha256] is a non-match, never an exception. */
    fun matches(
        expectedSha256: String,
        providedSha256: String,
    ): Boolean {
        val expected = hexToBytes(expectedSha256) ?: return false
        val provided = hexToBytes(providedSha256) ?: return false
        return MessageDigest.isEqual(expected, provided)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            ((high shl 4) + low).toByte()
        }
    }
}
