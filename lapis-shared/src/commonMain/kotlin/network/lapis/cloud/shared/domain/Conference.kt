package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- see `27-conference.kuml.kts` file header for the
 * full fachlich model and [network.lapis.cloud.shared.rpc.IConferenceService] KDoc for the
 * authorization matrix. Two tiers only: [MODERATOR] is always the room creator
 * (`conference_room.created_by_member_id`), never a persisted, independently-grantable role --
 * see [network.lapis.cloud.shared.rpc.IConferenceService] KDoc "Two-tier role model". A global
 * BOARD/ADMIN [AccountRole] additionally authorizes [network.lapis.cloud.shared.rpc.IConferenceService.endRoom]/
 * [network.lapis.cloud.shared.rpc.IConferenceService.removeParticipant] without ever changing what
 * [MODERATOR]/[PARTICIPANT] mean here -- see that interface's own KDoc.
 */
@Serializable
enum class ConferenceRole { MODERATOR, PARTICIPANT }

/**
 * Role: MEMBER+, caller must be [MemberStatus.AKTIV]. Both fields are validated server-side
 * ([title] non-blank, at most 200 characters; [description] at most 1000 characters) -- see
 * [network.lapis.cloud.shared.rpc.IConferenceService.createRoom] KDoc. Wave 1's UI never surfaces
 * [description] as a user-facing field (D1 of the Wave 1 design review: "one button, no form") --
 * it defaults to `""` and exists on this input purely for forward compatibility with a later wave's
 * richer creation flow.
 */
@Serializable
data class ConferenceRoomInput(
    val title: String,
    val description: String = "",
    /**
     * Wave 5 "Föderations-Gastbeitritt": per-room federation-guest opt-in. Defaults to `false` --
     * a room is guest-CLOSED unless its creator says otherwise. See
     * [network.lapis.cloud.shared.rpc.IConferenceService] KDoc "Federated guest join". **The
     * client never sets this at creation time** -- design review D4: Wave 4's D1 deliberately
     * deleted the lobby creation form ("one button, no form"), and guest access is always enabled
     * from INSIDE a running room (via [network.lapis.cloud.shared.rpc.IConferenceService
     * .setRoomGuestAccess]), by someone who is already there, never at creation. This field exists
     * on the input purely for API completeness/tests -- do not "helpfully" re-add a creation-time
     * toggle in the client.
     */
    val allowFederationGuests: Boolean = false,
)

/**
 * One conference room. [livekitRoomName] is the server-generated `lc-<uuid4>` join key to the
 * LiveKit SFU -- never derived from [title], never guessable. [active] is `endedAt == null`;
 * [liveParticipantCount] reflects LiveKit's OWN live `ListRooms`/`num_participants` figure (never
 * derived from `conference_participation` row counts, which only track this server's OWN historical
 * join/leave log -- see `27-conference.kuml.kts` file header "LiveKit itself owns no persistent
 * state"). [myRole] is [ConferenceRole.MODERATOR] iff the CALLER is [createdByMemberId], computed
 * fresh on every read, never cached.
 */
@Serializable
data class ConferenceRoomDto(
    val id: String,
    val title: String,
    val description: String,
    val livekitRoomName: String,
    val createdByMemberId: String,
    val createdByDisplayName: String,
    val createdAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val active: Boolean,
    val maxParticipants: Int,
    val liveParticipantCount: Int,
    val myRole: ConferenceRole,
    /** Wave 5 "Föderations-Gastbeitritt" -- see [ConferenceRoomInput.allowFederationGuests] KDoc. */
    val allowFederationGuests: Boolean = false,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IConferenceService.joinRoom] -- the ONLY LiveKit token
 * shape ever sent to a browser (room-pinned, `roomJoin`/`canPublish`/`canSubscribe`/`canPublishData`
 * only -- see [network.lapis.cloud.server.conference.LiveKitAccessToken.mintParticipantToken] KDoc).
 * [serverUrl] is the `ws://`/`wss://` LiveKit endpoint the CLIENT connects to -- never hardcoded
 * client-side, always sourced from here so a deployment can point at any LiveKit instance purely via
 * server-side `LAPIS_LIVEKIT_URL` configuration. [identity] is always the caller's own member UUID
 * string (never a display name/email) -- a receiving peer's client can trust
 * `RemoteParticipant.identity` as a server-verified member id precisely because this is the only
 * value ever placed in the JWT `sub` claim (see [network.lapis.cloud.server.conference.LiveKitAccessToken]
 * KDoc).
 *
 * [turnServers] (audit-round-1 fix) is a fresh, per-join, [expiresAt]-scoped TURN relay credential
 * set -- see [network.lapis.cloud.server.conference.TurnCredentialMinter] KDoc. Empty iff
 * `LAPIS_TURN_URLS`/`LAPIS_TURN_SHARED_SECRET` are unconfigured
 * ([network.lapis.cloud.server.conference.ConferenceConfig.turnEnabled] `false`) -- the client simply
 * connects with no extra ICE servers in that case, same as before this fix existed.
 */
@Serializable
data class ConferenceJoinTokenDto(
    val roomId: String,
    val livekitRoomName: String,
    val serverUrl: String,
    val token: String,
    val identity: String,
    val displayName: String,
    val role: ConferenceRole,
    val expiresAt: LocalDateTime,
    val turnServers: List<ConferenceTurnServer> = emptyList(),
)

/**
 * One TURN relay ICE server, minted fresh per [network.lapis.cloud.shared.rpc.IConferenceService.joinRoom]
 * call -- see [ConferenceJoinTokenDto.turnServers] and
 * [network.lapis.cloud.server.conference.TurnCredentialMinter] KDoc. [username]/[credential] are
 * short-lived (same TTL as the surrounding [ConferenceJoinTokenDto.token]) and MUST NOT be cached or
 * reused beyond this one join -- unlike the OLD static TURN credential this replaces, a stale
 * [ConferenceTurnServer] simply stops authenticating against coturn once its embedded expiry passes.
 */
@Serializable
data class ConferenceTurnServer(
    val urls: List<String>,
    val username: String,
    val credential: String,
)

/**
 * One row of [network.lapis.cloud.shared.rpc.IConferenceService.listParticipants]. [role] is the
 * PER-JOIN snapshot from `conference_participation.role` (see `27-conference.kuml.kts` file header
 * "Two-tier role model" -- NOT re-derived from the room's current `created_by_member_id` on read,
 * so it stays accurate even if a future wave ever allows a room's creator attribution to change).
 * [live] reflects LiveKit's own live `ListParticipants` roster (a member can have `leftAt == null`
 * in this server's own history yet not currently be connected, e.g. after a hard browser-tab
 * crash that never reached [network.lapis.cloud.shared.rpc.IConferenceService.leaveRoom] -- see
 * that method's KDoc).
 */
@Serializable
data class ConferenceParticipantDto(
    val memberId: String,
    val displayName: String,
    val role: ConferenceRole,
    val joinedAt: LocalDateTime,
    val leftAt: LocalDateTime?,
    val live: Boolean,
    /**
     * Wave 5 "Föderations-Gastbeitritt": non-null ONLY for a participant whose member row is
     * [MemberStatus.GAST] AND who has an `oidc_guest_profile` row -- `null` for every ordinary
     * member. Drives `GuestBadge.kt`'s `guestBadge(homeserverUrl)` in the in-call roster, exactly
     * like `SessionInfoDto.homeserverUrl` already drives it in the navbar. Never derived
     * client-side -- a stale `oidc_guest_profile` row left behind on a member later promoted to
     * AKTIV must NOT surface a guest badge for them, which is why the server-side query behind
     * this field re-checks `member.status == GAST` on every read, not just presence of a profile
     * row (see `ConferenceService.listParticipants` KDoc).
     */
    val homeserverUrl: String? = null,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IConferenceService.getAvailability] -- the UI's ONE
 * signal for whether to show the Videokonferenz nav entry/button at all. [enabled] is `false` (with
 * [serverUrl] `null`) whenever `LAPIS_LIVEKIT_URL`/`_API_KEY`/`_API_SECRET` are unconfigured -- see
 * [network.lapis.cloud.server.conference.ConferenceConfig] KDoc "Startup behaviour". Never throws
 * for an unconfigured deployment -- unlike every other [network.lapis.cloud.shared.rpc.IConferenceService]
 * method, which rejects with [network.lapis.cloud.shared.rpc.ConflictException] when the feature is
 * off, so the UI has exactly one place to check before offering the feature at all.
 */
@Serializable
data class ConferenceAvailabilityDto(
    val enabled: Boolean,
    val serverUrl: String?,
    val maxParticipants: Int,
)

/**
 * Sent over the LiveKit data channel ONLY (`publishData`, topic `lapis-chat`) -- never through an
 * RPC call, never persisted to any table. See `27-conference.kuml.kts` file header "Scope-cuts" and
 * [network.lapis.cloud.shared.rpc.IConferenceService] KDoc "Chat" for why persistence is explicitly
 * out of scope this wave. **[senderMemberId]/[senderDisplayName] are attacker-controllable by any
 * room participant** -- a receiving client MUST render the sender using the LiveKit SDK-supplied
 * `RemoteParticipant.identity`/`.name` (server-verified via the signed join token's `sub`/`name`
 * claims), never these self-reported fields, for anything identity-bearing (see
 * [network.lapis.cloud.server.conference.LiveKitAccessToken.mintParticipantToken] KDoc for why
 * `identity` is trustworthy and this payload is not).
 */
@Serializable
data class ConferenceChatMessage(
    val senderMemberId: String,
    val senderDisplayName: String,
    val text: String,
    val sentAtEpochMs: Long,
)

// ── Wave 5 "Föderations-Gastbeitritt" ──────────────────────────────────────────────────────────

/**
 * Wave 5 -- the current [network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer]'s
 * version/text/hash, same shape [AuctionComplianceDisclaimerDto] already establishes.
 * [headline]/[keyPoints] are the design review's D7 "layer 1" (rendered above the fold,
 * unscrollable-past); [text] is the full "layer 2" disclosure (rendered in a scroll box beneath
 * layer 1, always present, never hidden). [text] is structurally guaranteed to be composed from
 * [headline]/[keyPoints] server-side -- see that object's own KDoc "Two-layer disclosure" -- so a
 * client rendering only layer 1 can never show wording the hash does not also cover.
 */
@Serializable
data class ConferenceGuestConsentDisclaimerDto(
    val version: String,
    val headline: String,
    val keyPoints: List<String>,
    val text: String,
    val sha256: String,
)

/**
 * Wave 5 -- everything a caller needs BEFORE attempting a federated guest join, delivered as DATA
 * rather than as an exception message. This is deliberate and load-bearing: kilua-rpc transmits
 * only the exception discriminator, never its message (see `AppState.guarded` KDoc in the client
 * module), so an honest "this room does not admit guests" explanation is IMPOSSIBLE to deliver via
 * a thrown `ForbiddenException`. [allowsFederationGuests]/[roomActive] are the two fields the
 * client renders a precise German reason from (see
 * `network.lapis.cloud.client.conferenceGuestJoinBlockedReason`).
 *
 * [createdByMemberId]/[createdByDisplayName] (design review D14) let a GUEST see WHO the room's
 * moderator is -- the client's synthesized [ConferenceRoomDto] for an unjoined guest carries these
 * through unchanged; this does NOT grant the guest any moderator affordance (`canModerate` still
 * compares the CALLER's own id against `createdByMemberId`, which a GAST caller can never equal,
 * since [ConferenceRoomInput] is only ever submitted by an AKTIV caller via `createRoom`).
 * [organizationName] (`organization_settings.name`) is the DSGVO-verantwortliche Organisation
 * named by the disclaimer's layer-1 org line -- deliberately NOT part of the hashed
 * [ConferenceGuestConsentDisclaimerDto.text], see that class's own KDoc "The organization name is
 * NOT part of the hashed text".
 */
@Serializable
data class ConferenceGuestJoinInfoDto(
    val roomId: String,
    val title: String,
    val allowsFederationGuests: Boolean,
    val roomActive: Boolean,
    val organizationName: String,
    val createdByMemberId: String,
    val createdByDisplayName: String,
    /** `true` iff the CALLER is [MemberStatus.GAST] -- lets the client pick the guest vs. moderator-preview rendering. */
    val callerIsGuest: Boolean,
    val disclaimer: ConferenceGuestConsentDisclaimerDto,
)

/**
 * Wave 5 -- proof the guest was shown the CURRENT disclaimer text. Same shape
 * [AuctionComplianceAcknowledgmentInput] already establishes. Read by
 * [network.lapis.cloud.shared.rpc.IConferenceService.joinRoom] ONLY for a
 * [MemberStatus.GAST] caller -- ignored (no side effect, no acknowledgment row written) for every
 * other caller, see that method's own KDoc.
 */
@Serializable
data class ConferenceGuestConsentAcknowledgmentInput(
    val consentVersion: String,
    val consentSha256: String,
)
