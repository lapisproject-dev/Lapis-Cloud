package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.OidcSigningKeyTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The single seeded [OidcSigningKeyTable] row's fixed id -- next unused `...-0000-0000000000fN`
 * slot after [FEDERATION_ACTOR_KEY_ID]'s own `...-f6`. Same "provisioned at boot, not
 * Flyway-seeded" reasoning as that key (no environment-specific value is needed here either --
 * unlike `federation_actor_key.actor_uri`, an OIDC signing key has nothing config-derived to wait
 * for; it is provisioned at boot purely to mirror the established singleton-row idiom and keep the
 * private key generation logic in one obvious place, exercised by the same test/dev bootstrap path
 * as every other singleton).
 */
val OIDC_SIGNING_KEY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f7")

private const val RSA_KEY_SIZE_BITS = 2048

/**
 * Provisions this server's own OIDC JWS signing keypair exactly once, idempotently, on every
 * `Application.module()` boot -- mirrors [FederationActorKeyProvisioner] exactly, but for a
 * SEPARATE key with a SEPARATE cryptographic purpose (JWS signing of ID/Logout Tokens, RS256) from
 * [FederationActorKeyProvisioner]'s HTTP-Signature RSA key. See `25-oidc-guest-federation.kuml.kts`
 * file header for why these two keys must never be the same key (different rotation/compromise
 * scopes, mixing them would let a token forged for one purpose potentially be replayed as valid
 * material for the other).
 *
 * **Registered in `OrganizationRestoreService.SEEDED_SINGLETON_ROWS`** -- otherwise a fresh
 * restore-target pre-flight check would always find this row non-empty, permanently blocking
 * restore on every server without `allowNonEmptyTarget=true`.
 *
 * `kid` (JWK Key ID, RFC 7517) is a fresh random UUID string, generated once at provisioning time
 * and never rotated this wave (see [network.lapis.cloud.server.routes.OidcRoutes] KDoc "Open
 * design questions" -- single active key only, JWKS already returns an array so adding a second
 * key later is additive).
 */
object OidcSigningKeyProvisioner {
    fun ensureProvisioned() {
        transaction {
            val exists =
                OidcSigningKeyTable
                    .selectAll()
                    .where { OidcSigningKeyTable.id eq OIDC_SIGNING_KEY_ID }
                    .count() > 0
            if (exists) return@transaction

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSA_KEY_SIZE_BITS, SecureRandom())
            val keyPair = generator.generateKeyPair()
            val now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            OidcSigningKeyTable.insert {
                it[id] = OIDC_SIGNING_KEY_ID
                it[kid] = Uuid.random().toString()
                it[publicKeyPem] = pemEncode(keyPair.public.encoded, "PUBLIC KEY")
                it[privateKeyPem] = pemEncode(keyPair.private.encoded, "PRIVATE KEY")
                it[createdAt] = now
            }
        }
    }

    /** Same hand-rolled PEM encoding as [FederationKeyPairGenerator] -- no external PEM library, see that object's KDoc. */
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
