package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.TrustAnchorEventDto
import network.lapis.cloud.shared.domain.TrustAnchorPoolMemberDto
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyDto
import network.lapis.cloud.shared.domain.TrustChainResolutionDto
import network.lapis.cloud.shared.domain.TrustedExternalAnchorDto

/**
 * V0.8.3 Trust-Anchor-Governance -- ADMIN-only administration surface for this server's OPTIONAL
 * Trust Anchor role (publishing a pool of vouched-for home servers) and its own configured set of
 * externally-trusted anchors (used only for the informational one-hop resolution signal). See
 * `network.lapis.cloud.shared.domain.TrustAnchorEventType` KDoc "CRITICAL FRAMING" -- nothing this
 * interface exposes gates federation, guest login, or Dynamic Client Registration; every method is
 * either publishing configuration or reading a purely informational signal. Same ADMIN-only tier
 * as [IFederationService] (an org-wide trust decision).
 *
 * **Publishing side** (this server acting as its own Trust Anchor -- opt-in, see
 * `network.lapis.cloud.server.routes.registerTrustAnchorRoutes` KDoc "opt-in via non-empty pool"):
 * [listSigningKeys]/[rotateSigningKey]/[revokeSigningKey] manage the signing keypair; [listPoolMembers]/
 * [addPoolMember]/[removePoolMember] manage the vouched-for home-server pool. The actual spec-shaped
 * publishing endpoints (`GET /.well-known/openid-federation`, `GET /federation/trust-anchor/fetch`)
 * are dedicated pre-auth Ktor routes, not part of this RPC surface -- same "spec-mandated path,
 * external payload shape" split [IFederationService]/OIDC already establish.
 *
 * **Consuming side** (this server choosing to trust OTHER anchors): [listTrustedAnchors]/
 * [addTrustedAnchor]/[removeTrustedAnchor] configure the set; [resolveTrustChain] is the one-hop
 * resolution itself.
 */
@RpcService
interface ITrustAnchorService {
    /** Role: ADMIN. Every signing key this server has ever provisioned (ACTIVE/RETIRED/REVOKED), newest first. Never carries a private key. */
    suspend fun listSigningKeys(): List<TrustAnchorSigningKeyDto>

    /**
     * Role: ADMIN. Rotates this server's Trust-Anchor signing key: the current `ACTIVE` key moves
     * to `RETIRED` (still published in this server's own JWKS -- grace period for already-issued,
     * still-unexpired statements), and a freshly generated key becomes `ACTIVE`. Returns the new
     * `ACTIVE` key. Throws [ConflictException] if, unexpectedly, no `ACTIVE` key currently exists
     * (should be unreachable outside a corrupted/pre-provisioning-boot state).
     */
    suspend fun rotateSigningKey(): TrustAnchorSigningKeyDto

    /**
     * Role: ADMIN. Compromise-response: immediately marks the key identified by [kid] `REVOKED` --
     * it is excluded from this server's own published JWKS from that moment on, so even a
     * still-unexpired statement signed by it stops verifying the next time any verifier re-fetches
     * this server's Entity Configuration. If [kid] was the current `ACTIVE` key, a fresh replacement
     * key is minted and activated automatically in the SAME operation (this server must always have
     * exactly one `ACTIVE` signing key to keep functioning as a Trust Anchor) -- the replacement is
     * recorded as its own [network.lapis.cloud.shared.domain.TrustAnchorEventType.KEY_ROTATED] event.
     * Throws [NotFoundException] if [kid] does not resolve to any known key, [ConflictException] if
     * it is already `REVOKED` (idempotency guard against a double revoke double-logging).
     */
    suspend fun revokeSigningKey(kid: String): TrustAnchorSigningKeyDto

    /** Role: ADMIN. The current pool of home-server URIs this server vouches for, oldest first. */
    suspend fun listPoolMembers(): List<TrustAnchorPoolMemberDto>

    /**
     * Role: ADMIN. Adds [homeServerUri] to this server's own Trust-Anchor pool. Throws
     * [BadRequestException] for a malformed/non-HTTPS/private-range URI, [ConflictException] if
     * already present.
     */
    suspend fun addPoolMember(homeServerUri: String): TrustAnchorPoolMemberDto

    /** Role: ADMIN. Removes [homeServerUri] from the pool -- the next fetch of its Subordinate Statement 404s, and any already-issued statement simply expires without renewal. Throws [NotFoundException] if not present. */
    suspend fun removePoolMember(homeServerUri: String)

    /** Role: ADMIN. The set of external Trust Anchor entity URIs this server has chosen to trust, oldest first. */
    suspend fun listTrustedAnchors(): List<TrustedExternalAnchorDto>

    /**
     * Role: ADMIN. Adds [anchorEntityUri] to the set of externally-trusted anchors. Throws
     * [BadRequestException] for a malformed/non-HTTPS/private-range URI, [ConflictException] if
     * already present.
     */
    suspend fun addTrustedAnchor(anchorEntityUri: String): TrustedExternalAnchorDto

    /** Role: ADMIN. Throws [NotFoundException] if not present. */
    suspend fun removeTrustedAnchor(anchorEntityUri: String)

    /** Role: ADMIN. Newest first, capped (mirrors [IFederationService.listFederationEvents]'s own shape). */
    suspend fun listEvents(): List<TrustAnchorEventDto>

    /**
     * Role: ADMIN. The one-hop trust-chain resolution itself (see
     * [network.lapis.cloud.server.federation.TrustAnchorResolver] KDoc): for every currently
     * configured trusted anchor, fetches and verifies whether [homeServerUri] is included, right
     * now, with a validly-signed, non-expired Subordinate Statement, in that anchor's pool. Returns
     * the first anchor that vouches for it, or a not-trusted result naming the last failure reason
     * if none do (or none are configured). Purely informational -- never throws for "not trusted",
     * only for a malformed [homeServerUri].
     */
    suspend fun resolveTrustChain(homeServerUri: String): TrustChainResolutionDto
}
