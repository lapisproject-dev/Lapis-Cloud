package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V0.8.1 Federation-Grundgerüst -- server-to-server ActivityPub-compatible federation between
 * whole Lapis-Cloud instances (organizations), NOT individual members. See
 * `network.lapis.cloud.shared.rpc.IFederationService` KDoc "Scope boundary" for what this wave
 * deliberately does NOT build (no content type wired into outbound federation yet).
 */
@Serializable
enum class FederationRelationshipDirection { OUTBOUND, INBOUND }

@Serializable
enum class FederationRelationshipStatus { PENDING, ACTIVE, REJECTED, UNDONE }

@Serializable
enum class FederationEventType {
    FOLLOW_SENT,
    FOLLOW_RECEIVED,
    ACCEPT_SENT,
    ACCEPT_RECEIVED,
    REJECT_SENT,
    REJECT_RECEIVED,
    UNDO_SENT,
    UNDO_RECEIVED,
}

/** This server's own ActivityPub Actor -- NEVER carries the private key, see [network.lapis.cloud.server.federation.FederationActorKeyProvisioner] KDoc. */
@Serializable
data class FederationActorDto(
    val actorUri: String,
    val publicKeyPem: String,
    val createdAt: LocalDateTime,
)

@Serializable
data class FederationRelationshipDto(
    val id: String,
    val direction: FederationRelationshipDirection,
    val status: FederationRelationshipStatus,
    val remoteActorUri: String,
    val remoteInboxUri: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Serializable
data class FederationRelationshipEventDto(
    val id: String,
    val relationshipId: String,
    val eventType: FederationEventType,
    val activityId: String?,
    val occurredAt: LocalDateTime,
)
