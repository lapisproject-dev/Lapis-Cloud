package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V0.8.3 Trust-Anchor-Governance -- a deliberately-scoped, single-level CORE subset of
 * [OpenID Federation 1.0 (RFC 9678)](https://openid.net/specs/openid-federation-1_0.html), layered
 * on top of V0.8.2's OIDC guest-identity federation (`25-oidc-guest-federation.kuml.kts`). See
 * `26-trust-anchor.kuml.kts` file header for the full fachlich model and the scope-boundary
 * reasoning (no nested intermediate authorities, no Trust Marks, no Metadata Policy Language).
 *
 * **CRITICAL FRAMING, mirrored from the concept**: a Trust Anchor is UX comfort, NOT a security
 * mechanism. It never gates federation itself, guest login, or Dynamic Client Registration (all of
 * which remain exactly as open as V0.8.2 left them) -- its only effect is a positive,
 * purely-informational signal (see [network.lapis.cloud.server.federation.TrustAnchorResolver]).
 *
 * **Three separate governance layers** (only the third is this wave's concern): (1) operating a
 * server at all -- open, unregulated; (2) becoming a home-member on a specific server -- that
 * server operator's own house rules; (3) Trust-Anchor-pool membership -- decided solely by
 * whichever Trust Anchor is asked, a political/organizational decision the software supports
 * mechanically but never prescribes policy for.
 */
@Serializable
enum class TrustAnchorSigningKeyStatus {
    /** The one key currently used to sign every newly-issued Entity Configuration / Subordinate Statement. Exactly one row has this status at any time. */
    ACTIVE,

    /** A previously-ACTIVE key, rotated out -- still published in this server's own JWKS (grace period) so already-issued, still-unexpired statements signed by it keep verifying, but never signs anything new. */
    RETIRED,

    /** Explicitly revoked (compromise-response) -- immediately excluded from this server's own published JWKS, so even an unexpired statement signed by it stops verifying the moment a verifier re-fetches. See [network.lapis.cloud.server.federation.TrustAnchorSigningKeyStore] KDoc "Why revocation needs more than expiry". */
    REVOKED,
}

@Serializable
enum class TrustAnchorEventType {
    /** The initial signing key, provisioned idempotently at first boot. */
    KEY_PROVISIONED,

    /** An ADMIN-triggered rotation -- the previous ACTIVE key moved to RETIRED, a fresh key became ACTIVE. Also recorded (with the same event type) for the automatic replacement key minted as a side effect of revoking the then-ACTIVE key (see [network.lapis.cloud.shared.rpc.ITrustAnchorService.revokeSigningKey]). */
    KEY_ROTATED,

    /** An ADMIN-triggered compromise-response revocation. */
    KEY_REVOKED,
    POOL_MEMBER_ADDED,
    POOL_MEMBER_REMOVED,
    TRUSTED_ANCHOR_ADDED,
    TRUSTED_ANCHOR_REMOVED,
}

/** This server's own Trust-Anchor signing key -- NEVER carries the private key, same posture as [FederationActorDto]/[network.lapis.cloud.server.federation.OidcSigningKeyProvisioner]'s own key row. */
@Serializable
data class TrustAnchorSigningKeyDto(
    val kid: String,
    val publicKeyPem: String,
    val status: TrustAnchorSigningKeyStatus,
    val createdAt: LocalDateTime,
    val retiredAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
)

/** One home-server URI this server (acting as a Trust Anchor) currently vouches for. */
@Serializable
data class TrustAnchorPoolMemberDto(
    val id: String,
    val homeServerUri: String,
    val addedAt: LocalDateTime,
)

/** One external Trust Anchor entity URI this server has chosen to trust for the one-hop resolution signal. */
@Serializable
data class TrustedExternalAnchorDto(
    val id: String,
    val anchorEntityUri: String,
    val addedAt: LocalDateTime,
)

/** One append-only, non-hash-chained forensic log row -- mirrors `federation_relationship_event`'s own shape/reasoning (organization-level governance action, not `audit_log_entry`'s GoBD-bounded scope), see `26-trust-anchor.kuml.kts` file header. */
@Serializable
data class TrustAnchorEventDto(
    val id: String,
    val occurredAt: LocalDateTime,
    val eventType: TrustAnchorEventType,
    /** Event-type-dependent: a key's `kid` for KEY_*, a home-server URI for POOL_MEMBER_*, an anchor entity URI for TRUSTED_ANCHOR_*. */
    val subject: String,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.ITrustAnchorService.resolveTrustChain] -- purely
 * informational (see class KDoc "CRITICAL FRAMING"), never gates anything by itself.
 */
@Serializable
data class TrustChainResolutionDto(
    val homeServerUri: String,
    val trusted: Boolean,
    /** The trusted anchor that vouched for [homeServerUri], if [trusted] is `true`. */
    val anchorEntityUri: String? = null,
    /** Human-readable reason the resolution failed, if [trusted] is `false`. */
    val reason: String? = null,
)
