package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

// V1.9.282 -- an account-less event guest may share the screen while a moderator allows it. The grant is ephemeral (the LiveKit permission plus
// an in-memory registry bound to the participant sid); nothing is stored in the database. Kilua transmits only an exception TYPE, so the
// distinguishable outcomes below come back as a result DTO; an unauthorised call is the one uniform ForbiddenException (no oracle).

/** What `setGuestScreenShare` did. */
@Serializable
enum class GuestScreenShareOutcome {
    GRANTED,
    ALREADY_GRANTED,
    REVOKED,
    NOT_GRANTED,

    /** Another guest already holds the (single) share grant of the room. */
    TAKEN,

    /** The guest is not in the room (any more), or reconnected while the grant was being made. */
    NOT_IN_ROOM,

    /** The grant was withdrawn, but the guest's share could not be stopped by a mute, so the guest was removed from the room. */
    REVOKED_REMOVED,
}

@Serializable
data class GuestScreenShareResultDto(
    val outcome: GuestScreenShareOutcome,
)

/** [grantedIdentity] is the LiveKit identity (`evg-...`) of the one guest allowed to share, [sharing] whether that guest is sharing right now. */
@Serializable
data class GuestScreenShareStateDto(
    val grantedIdentity: String? = null,
    val sharing: Boolean = false,
)
