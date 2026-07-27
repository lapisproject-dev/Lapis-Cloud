package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.FederationActorDto
import network.lapis.cloud.shared.domain.FederationRelationshipDto
import network.lapis.cloud.shared.domain.FederationRelationshipEventDto
import network.lapis.cloud.shared.domain.FederationRelationshipStatus

/**
 * V0.8.1 Federation-Grundgerüst -- relationship-management surface. Deliberately ADMIN-only:
 * federating with an external, self-declared server is an org-wide trust decision, same tier as
 * [IOrganizationSettingsService.updateOrganizationSettings].
 *
 * **Protocol**: a hybrid, deliberate design -- an ActivityPub-compatible core (Actor documents,
 * `Follow`/`Accept`/`Reject`/`Undo` handshake, HTTP-Signature-authenticated delivery) plus a
 * namespaced `lapis:` JSON-LD extension vocabulary for this project's own differentiator
 * (Meritokratie-specific data: LTR amounts, vote weights, pseudonym-reputation-anchors -- see
 * `network.lapis.cloud.server.federation.ActivityPubTypes.LapisExtensionDto`). A pure
 * ActivityPub approach has no native vocabulary for that data; a pure custom protocol would forgo
 * real Fediverse tooling/interoperability, itself a strategic goal ("Ideologie-Übernahme durch
 * Reichweite" -- broader reach for other organizations adopting the underlying libertarian
 * structural mechanics by federating or forking). Mirrors the sibling identity decision (OIDC
 * core + custom Trust-Anchor governance) already used elsewhere in this project.
 *
 * **Actor = Organization, not Member**: this codebase is single-tenant (exactly one
 * `organization_settings` row per deployment) -- federation happens between whole server
 * instances, so the federated ActivityPub Actor is the organization itself, not an individual
 * `Member`. See `24-federation.kuml.kts` file header.
 *
 * **HTTP Signatures**: the draft-cavage `Signature:` header scheme (RSA-2048, `rsa-sha256`), NOT
 * the newer RFC 9421 -- essentially all deployed Fediverse software (Mastodon, Pleroma/Akkoma,
 * Misskey/Firefish) still speaks draft-cavage as of this wave, and real interoperability with that
 * software is this wave's explicit strategic goal. RFC 9421 can be added additively later if
 * real-world adoption shifts -- see `network.lapis.cloud.server.federation.HttpSignatures` KDoc.
 *
 * **Scope boundary (deliberate, read before extending)**: this wave builds the federation
 * PROTOCOL layer only. No existing content type (`ICrowdfundingService`/`IPoliticianService`/
 * `IGovernanceService`) is wired into outbound federation yet -- deciding WHICH content type
 * federates first, and how, is an explicit, separate product-scope decision for a later wave.
 * This interface therefore has no `federateX(...)`-shaped method anywhere; it only manages Actor
 * identity and inter-server `Follow` relationships, the substrate a later wave will build content
 * delivery on top of. Also explicitly out of scope: OIDC guest access (V0.8.2 -- a DIFFERENT
 * identity mechanism authenticating individual members, not server-to-server delivery),
 * automatic inter-server Trust-Anchor governance (V0.8.3 -- this wave's `Follow` handshake
 * requires explicit ADMIN approval for every inbound relationship, deliberately no auto-accept),
 * and the guest timeline badge/UI (V0.8.4).
 */
@RpcService
interface IFederationService {
    /** Role: ADMIN. This server's own Actor (never the private key). */
    suspend fun getLocalActor(): FederationActorDto

    /** Role: ADMIN. */
    suspend fun listFederationRelationships(status: FederationRelationshipStatus? = null): List<FederationRelationshipDto>

    /** Role: ADMIN. Throws [NotFoundException] if [id] does not resolve to an existing relationship. */
    suspend fun getFederationRelationship(id: String): FederationRelationshipDto

    /** Role: ADMIN. Newest first. Throws [NotFoundException] if [relationshipId] does not resolve. */
    suspend fun listFederationEvents(relationshipId: String): List<FederationRelationshipEventDto>

    /**
     * Role: ADMIN. Fetches [remoteActorUri] (SSRF-guarded, HTTPS-only, private/loopback/
     * link-local addresses rejected), sends a signed `Follow`, and persists a `PENDING`/
     * `OUTBOUND` relationship. Throws [BadRequestException] for a malformed/non-HTTPS/
     * private-range URI or an actor document that cannot be fetched/parsed, [ConflictException]
     * if a non-terminal (`PENDING`/`ACTIVE`) relationship to this remote actor already exists.
     * Delivery failure (remote unreachable) does not prevent the relationship row from being
     * persisted -- a later manual retry is out of scope this wave.
     */
    suspend fun initiateFollow(remoteActorUri: String): FederationRelationshipDto

    /**
     * Role: ADMIN. Only valid for a `PENDING`/`INBOUND` relationship -- throws [ConflictException]
     * otherwise. Sends a signed `Accept` (best-effort delivery) and flips the relationship to
     * `ACTIVE` regardless of delivery outcome.
     */
    suspend fun acceptInboundFollow(relationshipId: String): FederationRelationshipDto

    /** Role: ADMIN. Only valid for a `PENDING`/`INBOUND` relationship -- throws [ConflictException] otherwise. Sends a signed `Reject`. */
    suspend fun rejectInboundFollow(relationshipId: String): FederationRelationshipDto

    /**
     * Role: ADMIN. Only valid for an `ACTIVE` relationship (either direction) -- throws
     * [ConflictException] otherwise. Sends a signed `Undo` -- best-effort delivery, the local
     * status flips to `UNDONE` regardless of remote reachability ("we no longer trust them" must
     * not depend on their reachability).
     */
    suspend fun undoRelationship(relationshipId: String): FederationRelationshipDto
}
