package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import org.jetbrains.exposed.v1.jdbc.insert
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlin.uuid.Uuid

private const val RSA_KEY_SIZE_BITS = 2048

/**
 * RSA-2048 keypair generation + insertion for [TrustAnchorSigningKeyTable] -- shared by
 * [TrustAnchorSigningKeyProvisioner] (the first, boot-time row) and [TrustAnchorSigningKeyStore]
 * (every later ADMIN-triggered rotation/compromise-replacement row). Same plain
 * `java.security.KeyPairGenerator` + hand-rolled PEM approach as [OidcSigningKeyProvisioner] --
 * deliberately NOT [FederationKeyPairGenerator] (a different key, different cryptographic purpose,
 * see `26-trust-anchor.kuml.kts` file header).
 *
 * **Must always be called from inside the caller's already-open `transaction {}`** -- [insertNewKey]
 * deliberately does not open its own, same contract [AuditLogRecorder.record] establishes.
 */
internal object TrustAnchorKeyMaterial {
    /** Generates a fresh RSA-2048 keypair + `kid` and inserts one [TrustAnchorSigningKeyTable] row with [status]. Returns the new row's `kid`. */
    fun insertNewKey(
        id: Uuid = Uuid.random(),
        status: TrustAnchorSigningKeyStatus,
        now: LocalDateTime = trustAnchorNowLocalDateTime(),
    ): String {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_SIZE_BITS, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val kid = Uuid.random().toString()
        TrustAnchorSigningKeyTable.insert {
            it[TrustAnchorSigningKeyTable.id] = id
            it[TrustAnchorSigningKeyTable.kid] = kid
            it[publicKeyPem] = pemEncode(keyPair.public.encoded, "PUBLIC KEY")
            it[privateKeyPem] = pemEncode(keyPair.private.encoded, "PRIVATE KEY")
            it[TrustAnchorSigningKeyTable.status] = status
            it[createdAt] = now
            it[retiredAt] = null
            it[revokedAt] = null
        }
        return kid
    }

    private fun pemEncode(
        derBytes: ByteArray,
        label: String,
    ): String {
        val base64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(derBytes)
        val wrapped = base64.chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$wrapped\n-----END $label-----\n"
    }
}

/**
 * Distinctly-prefixed (not the bare `nowLocalDateTime` name several RPC service files use as
 * a private MEMBER function) -- this one is a top-level `internal` declaration shared across every
 * `TrustAnchor*.kt` file in this package, and Kotlin's same-package top-level name resolution would
 * make a bare `nowLocalDateTime` ambiguous against any other top-level declaration of that exact
 * name in this package (same reasoning [network.lapis.cloud.server.federation.OidcJwt]'s own
 * `JWT_`-prefixed private constants KDoc documents).
 */
internal fun trustAnchorNowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()
