package network.lapis.cloud.server.federation

import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.shared.domain.TrustAnchorEventType
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * The single FIRST-provisioned [TrustAnchorSigningKeyTable] row's fixed id -- next unused
 * `...-0000-0000000000fN` slot after [network.lapis.cloud.server.federation.OIDC_SIGNING_KEY_ID]'s
 * own `...-f7`. See `26-trust-anchor.kuml.kts` file header for why this table is provisioned here
 * (at boot, one row) exactly like [FederationActorKeyProvisioner]/[OidcSigningKeyProvisioner], even
 * though -- UNLIKE those two genesis-singleton tables -- this one is rotation-capable and can
 * legitimately grow beyond this one row over a server's lifetime.
 */
val TRUST_ANCHOR_SIGNING_KEY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f8")

/**
 * Provisions this server's own FIRST Trust-Anchor signing keypair exactly once, idempotently, on
 * every `Application.module()` boot -- mirrors [FederationActorKeyProvisioner]/
 * [OidcSigningKeyProvisioner] exactly, but for a THIRD, separate key with a separate cryptographic
 * purpose (JWS-signing OpenID Federation Entity Configurations/Subordinate Statements, RS256) from
 * both of those. See `26-trust-anchor.kuml.kts` file header for why these three keys must never be
 * the same key.
 *
 * **Registered in `OrganizationRestoreService.SEEDED_SINGLETON_ROWS`** -- otherwise a fresh
 * restore-target pre-flight check would always find this row non-empty, permanently blocking
 * restore on every server without `allowNonEmptyTarget=true`.
 *
 * Provisioning this server's own Trust-Anchor identity unconditionally at boot does NOT mean the
 * Trust Anchor role is "active" -- see `network.lapis.cloud.server.routes.registerTrustAnchorRoutes`
 * KDoc "opt-in via non-empty pool". A provisioned-but-unused key is harmless, same posture as the
 * federation Actor key existing even for an operator who never federates with anyone.
 */
object TrustAnchorSigningKeyProvisioner {
    fun ensureProvisioned() {
        transaction {
            val exists =
                TrustAnchorSigningKeyTable
                    .selectAll()
                    .where { TrustAnchorSigningKeyTable.id eq TRUST_ANCHOR_SIGNING_KEY_ID }
                    .count() > 0
            if (exists) return@transaction

            val kid =
                TrustAnchorKeyMaterial.insertNewKey(
                    id = TRUST_ANCHOR_SIGNING_KEY_ID,
                    status = TrustAnchorSigningKeyStatus.ACTIVE,
                )
            TrustAnchorEventStore.record(TrustAnchorEventType.KEY_PROVISIONED, subject = kid)
        }
    }
}
