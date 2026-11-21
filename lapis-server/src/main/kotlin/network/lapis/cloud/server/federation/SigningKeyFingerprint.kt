package network.lapis.cloud.server.federation

import java.security.MessageDigest
import java.util.Base64

/**
 * V1.9.319 (wave 7): the fingerprint an administrator compares with the partner organisation over a second channel before accepting a changed
 * signing key: SHA-256 over the DER (SubjectPublicKeyInfo) of the public key, 64 lower-case hex characters. A PEM that is no base64 (only seen in
 * tests, the poll parser validates real keys) falls back to the SHA-256 of its trimmed text, so the function is total.
 */
internal object SigningKeyFingerprint {
    fun of(publicKeyPem: String): String {
        val body =
            publicKeyPem
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
        val der = runCatching { Base64.getDecoder().decode(body) }.getOrNull()?.takeIf { it.isNotEmpty() }
        val bytes = der ?: publicKeyPem.trim().toByteArray(Charsets.UTF_8)
        return MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
