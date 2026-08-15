package network.lapis.cloud.server.federation

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

/** An RSA-2048 keypair, PEM-encoded -- see [FederationKeyPairGenerator.generate] KDoc. */
data class FederationKeyPair(
    val publicKeyPem: String,
    val privateKeyPem: String,
)

private const val RSA_KEY_SIZE_BITS = 2048
private const val PEM_LINE_LENGTH = 64

/**
 * Generates this server's own ActivityPub Actor keypair (V0.8.1 Federation-Grundgerüst) -- RSA-2048
 * via the JDK's own `KeyPairGenerator`/`SecureRandom`, no bespoke RNG, same "trust the platform's
 * own crypto primitives" posture [network.lapis.cloud.server.security.PasswordHasher]/
 * [network.lapis.cloud.server.security.SessionTokens] already establish. RSA (not Ed25519) is
 * deliberate -- see `network.lapis.cloud.server.federation.HttpSignatures` KDoc "Algorithm choice"
 * for why `rsa-sha256` is the near-universal Fediverse choice this wave targets for real
 * interoperability.
 *
 * PEM-encoded by hand (Base64 + literal `-----BEGIN/END-----` wrapper over the key's own
 * PKCS#8/X.509 DER encoding) -- no external PEM library, same "no bespoke dependency for a small,
 * well-understood encoding" posture [PasswordHasher]'s own KDoc documents for Base64 pre-hashing.
 */
object FederationKeyPairGenerator {
    fun generate(): FederationKeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_SIZE_BITS, SecureRandom())
        val keyPair = generator.generateKeyPair()
        return FederationKeyPair(
            publicKeyPem = pemEncode(derBytes = keyPair.public.encoded, label = "PUBLIC KEY"),
            privateKeyPem = pemEncode(derBytes = keyPair.private.encoded, label = "PRIVATE KEY"),
        )
    }

    private fun pemEncode(
        derBytes: ByteArray,
        label: String,
    ): String {
        val base64 = Base64.getEncoder().encodeToString(derBytes)
        val wrapped = base64.chunked(PEM_LINE_LENGTH).joinToString("\n")
        return "-----BEGIN $label-----\n$wrapped\n-----END $label-----\n"
    }
}
