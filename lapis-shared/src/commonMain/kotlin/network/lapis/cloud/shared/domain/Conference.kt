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
 * V1.9.115 -- how a recording (and a stream, as a pre-selection of the start dialog) shows the people of a room: [RASTER] is the tile
 * grid, [SPEAKER] one large tile for the current speaker with a filmstrip of up to five more. A screen share is always shown large.
 * The order of the constants is load-bearing (stored by name, but a tripwire pins the set).
 */
@Serializable
enum class ConferenceBroadcastView { RASTER, SPEAKER }

/**
 * Role: MEMBER+, caller must be [MemberStatus.ACTIVE]. Both fields are validated server-side
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
    /**
     * V1.9.108 -- non-moderators join with the microphone off. Part of the API for completeness (like [allowFederationGuests]); the client
     * never sets it at creation time (design decision D4 "one button, no form") but from the "Moderation" disclosure inside a running room.
     */
    val muteOnJoin: Boolean = false,
    /** V1.9.108 -- `false` locks the room's microphones: only moderators (and individually released people) may switch theirs on. */
    val participantsMayUnmute: Boolean = true,
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
    /**
     * V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- `null` unless this room is bound to a
     * Sitzung via `IConferenceService.setRoomMeeting` (a later wave step). This is the field that
     * makes the automatic secret-ballot stream pause possible at all -- see
     * `network.lapis.cloud.server.rpc.SecretBallotStreamLock` KDoc.
     */
    val meetingId: String? = null,
    /**
     * V1.0 Wave 9 -- denormalized `meeting.title` for [meetingId], so the UI can show "Sitzung:
     * <Titel>" without a second round-trip. `null` whenever [meetingId] is `null`.
     */
    val meetingTitle: String? = null,
    /** V1.9.108 -- see [ConferenceRoomInput.muteOnJoin]. Appended last with a default so an older client/server pairing stays wire compatible. */
    val muteOnJoin: Boolean = false,
    /** V1.9.108 -- see [ConferenceRoomInput.participantsMayUnmute]. */
    val participantsMayUnmute: Boolean = true,
    /** V1.9.115 -- see [ConferenceBroadcastView]. Appended last with a default so an older client/server pairing stays wire compatible. */
    val broadcastView: ConferenceBroadcastView = ConferenceBroadcastView.RASTER,
    /** V1.9.120 -- join/leave tones enabled in this room (moderator option). Appended last with a default: wire compatible. */
    val joinLeaveSounds: Boolean = true,
    /** V1.9.127 -- reactions enabled in this room (moderator option; client-side only, see [CONFERENCE_REACTION_TOPIC]). Appended last with a default: wire compatible. */
    val reactionsEnabled: Boolean = true,
    /** V1.9.158 -- see [ConferenceEventCaptureState]. Appended last with a default: wire compatible (an older server sends [ConferenceEventCaptureState.NONE]). */
    val eventCapture: ConferenceEventCaptureState = ConferenceEventCaptureState.NONE,
)

/**
 * V1.9.158 -- whether a conference room is an EVENT room and, only for its moderators, whether recording / streaming is currently
 * available there. The concrete reason ([EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN], [EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER]) is sent ONLY to
 * a caller who may moderate the room; everybody else gets [EVENT_ROOM] (or [NONE] for an ordinary room), so the state of the release is
 * never revealed to a stranger.
 */
@Serializable
enum class ConferenceEventCaptureState {
    /** An ordinary room. */
    NONE,

    /** An event room; the caller is not a moderator (or the reason is not disclosed). */
    EVENT_ROOM,

    /** An event room, the caller moderates it and recording / streaming can be started. */
    EVENT_ROOM_CAPTURE_AVAILABLE,

    /** An event room, the caller moderates it; the instance switch is off (or the consent text / operator notice is missing or outdated). */
    EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN,

    /** An event room, the caller moderates it; the organizers did not release recording and streaming for this event. */
    EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER,
}

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
 * `LAPIS_TURN_URLS`/`LAPIS_TURNS_URLS`/`LAPIS_TURN_SHARED_SECRET` are unconfigured
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
    /** V1.9.113 -- non-null iff this room belongs to an event: a re-join must then run through `IEventService.joinEventOnline`, never `joinRoom`. */
    val eventId: String? = null,
    /**
     * V1.9.124 -- the organisation's noise suppression default (an ADMIN's setting). It applies in the browser ONLY while the person never
     * chose here; a remembered choice (on or off) always wins. `false` for every answer that does not know the setting -- in particular the
     * encounter room's join answers (the pulpit has no instance default, singing is its normal use) -- so old payloads stay readable.
     */
    val noiseSuppressionDefault: Boolean = false,
    /**
     * V1.9.129 -- whether the video overlay panel is offered in this join. `false` for every answer that does not know the setting and
     * always for event guests without an account (the overlay library needs a member session).
     */
    val videoOverlaysOffered: Boolean = false,
)

/**
 * One TURN relay ICE server, minted fresh per [network.lapis.cloud.shared.rpc.IConferenceService.joinRoom]
 * call -- see [ConferenceJoinTokenDto.turnServers] and
 * [network.lapis.cloud.server.conference.TurnCredentialMinter] KDoc. [username]/[credential] are
 * short-lived (same TTL as the surrounding [ConferenceJoinTokenDto.token]) and MUST NOT be cached or
 * reused beyond this one join -- unlike the OLD static TURN credential this replaces, a stale
 * [ConferenceTurnServer] simply stops authenticating against coturn once its embedded expiry passes.
 *
 * [urls] may contain BOTH plain `turn:` entries and (optionally, if `LAPIS_TURNS_URLS` is
 * configured) `turns:` (TURN over TLS) entries in the same list, sharing this ONE
 * [username]/[credential] pair -- coturn's `use-auth-secret` REST scheme is instance-wide, not
 * per-listener, so a single credential authenticates against every listener the instance exposes.
 * See [network.lapis.cloud.server.conference.ConferenceConfig.turnsUrls] KDoc.
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
     * [MemberStatus.GUEST] AND who has an `oidc_guest_profile` row -- `null` for every ordinary
     * member. Drives `GuestBadge.kt`'s `guestBadge(homeserverUrl)` in the in-call roster, exactly
     * like `SessionInfoDto.homeserverUrl` already drives it in the navbar. Never derived
     * client-side -- a stale `oidc_guest_profile` row left behind on a member later promoted to
     * ACTIVE must NOT surface a guest badge for them, which is why the server-side query behind
     * this field re-checks `member.status == GUEST` on every read, not just presence of a profile
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
    /** V1.9.113 -- the real ceiling of an event conference room (`LAPIS_EVENT_CONFERENCE_MAX_PARTICIPANTS`). */
    val eventMaxParticipants: Int = 0,
    /**
     * V1.9.124 -- see [ConferenceJoinTokenDto.noiseSuppressionDefault]. Sent here too, because the lobby asks for the availability before
     * every join click and guests have no room list; the join token stays the authority.
     */
    val noiseSuppressionDefault: Boolean = false,
    /** V1.9.129 -- see [ConferenceJoinTokenDto.videoOverlaysOffered]; the join token stays the authority. */
    val videoOverlaysOffered: Boolean = false,
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
 * [createdByMemberId]/[createdByDisplayName] (design review D14) let a GUEST (or, since V0.11.0, a
 * FRIEND) see WHO the room's moderator is -- the client's synthesized [ConferenceRoomDto] for an
 * unjoined guest carries these through unchanged; this does NOT grant the guest any moderator
 * affordance (`canModerate` still compares the CALLER's own id against `createdByMemberId`, which
 * a GUEST or FRIEND caller can never equal, since [ConferenceRoomInput] is only ever submitted by
 * an ACTIVE caller via `createRoom`).
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
    /** `true` iff the CALLER is [MemberStatus.GUEST] specifically -- lets the client pick the federated-guest vs. moderator-preview rendering. Does NOT cover [MemberStatus.FRIEND]; see [callerIsNonMember] for that. */
    val callerIsGuest: Boolean,
    /**
     * V0.11.0 -- `true` iff the caller's status is in [network.lapis.cloud.shared.domain
     * .MemberStatusSets.NON_MEMBER] (GUEST or FRIEND). Deliberately a SEPARATE field from
     * [callerIsGuest] rather than overloading it, so the client can render the right copy for a
     * FRIEND (no home server, unlike a federated GUEST) without conflating the two. Defaults to
     * `false` so every pre-existing construction site of this DTO stays source-compatible.
     */
    val callerIsNonMember: Boolean = false,
    val disclaimer: ConferenceGuestConsentDisclaimerDto,
)

/**
 * Wave 5 -- proof the guest was shown the CURRENT disclaimer text. Same shape
 * [AuctionComplianceAcknowledgmentInput] already establishes. Read by
 * [network.lapis.cloud.shared.rpc.IConferenceService.joinRoom] ONLY for a
 * [MemberStatus.GUEST] caller -- ignored (no side effect, no acknowledgment row written) for every
 * other caller, see that method's own KDoc.
 */
@Serializable
data class ConferenceGuestConsentAcknowledgmentInput(
    val consentVersion: String,
    val consentSha256: String,
)

// ── Wave 6 "Breakout-Räume" ─────────────────────────────────────────────────────────────────────

/**
 * Wave 6 -- one explicit pin of a specific, currently-live member to a specific breakout room slot
 * within a [ConferenceBreakoutPlanInput]/`assignParticipants` call. [breakoutIndex] is 0-based and
 * meaningful only relative to the OTHER indices in the same call -- see
 * [network.lapis.cloud.shared.rpc.IConferenceBreakoutService.createBreakoutRooms]/
 * [network.lapis.cloud.shared.rpc.IConferenceBreakoutService.assignParticipants] KDoc for how it
 * maps onto the batch's actual rooms (ordered by `createdAt`).
 */
@Serializable
data class ConferenceBreakoutAssignmentInput(
    val memberId: String,
    val breakoutIndex: Int,
)

/**
 * Wave 6 -- the moderator's breakout-room creation plan. [roomLabels], if non-empty, MUST have
 * exactly [roomCount] entries (validated server-side) -- otherwise every room gets an
 * auto-generated German default label "Breakout-Raum N" (1-based). [manualAssignments] lists
 * members explicitly pinned to a specific room by index; every OTHER currently-live participant of
 * the parent room (excluding its moderator -- see
 * [network.lapis.cloud.shared.rpc.IConferenceBreakoutService] KDoc "The moderator is never
 * auto-assigned") is distributed evenly (round-robin, sorted by display name for a moderator-legible
 * result) across the remaining slots.
 */
@Serializable
data class ConferenceBreakoutPlanInput(
    val roomCount: Int,
    val roomLabels: List<String> = emptyList(),
    val manualAssignments: List<ConferenceBreakoutAssignmentInput> = emptyList(),
)

/**
 * Wave 6 -- one breakout room of the parent room's currently open (or just-closed, for a final
 * status read) batch. [assignedMemberIds]/[assignedDisplayNames] are parallel lists (index `i` of
 * one corresponds to index `i` of the other) of every member currently holding an OPEN
 * `conference_breakout_assignment` row for this breakout room -- read-only status display for the
 * moderator's own overview, see [network.lapis.cloud.shared.rpc.IConferenceBreakoutService] KDoc.
 */
@Serializable
data class ConferenceBreakoutRoomDto(
    val id: String,
    val parentRoomId: String,
    val label: String,
    val createdAt: LocalDateTime,
    val closedAt: LocalDateTime?,
    val assignedMemberIds: List<String>,
    val assignedDisplayNames: List<String>,
)

/**
 * Wave 6 -- the CALLER's own currently open breakout assignment (see
 * [network.lapis.cloud.shared.rpc.IConferenceBreakoutService.getMyBreakoutAssignment]). This is the
 * SOLE mechanism the client uses to learn "was I just assigned somewhere" / "was I just recalled" --
 * see `network.lapis.cloud.client.ConferenceScreen`'s `resolvePostDisconnectDestination` KDoc for
 * why this is called from an `onDisconnected` handler rather than pushed or polled.
 */
@Serializable
data class ConferenceBreakoutAssignmentDto(
    val breakoutRoomId: String,
    val breakoutRoomLabel: String,
    val assignedAt: LocalDateTime,
)

/**
 * V1.9.108 -- LiveKit data-packet topic of the moderation signals (server -> client only). Named like the other `lapis-...` topics
 * (`lapis-chat`, `lapis-encounter-bell`). A packet is accepted by a client ONLY if it carries no sending participant (a packet the server
 * sent through `SendData`); participants hold `can_publish_data`, so anything with a sender on this topic is a forgery and is dropped.
 */
const val CONFERENCE_MODERATION_TOPIC = "lapis-conference-moderation"

/** Hard cap of a moderation payload in bytes (the largest one, `muted_peer` with a UUID, is about 70 bytes). */
const val CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES = 128

/** Moderation signal kinds (`{"v":1,"k":"<kind>"}`): the person was muted by a moderator. */
const val CONFERENCE_MODERATION_KIND_MUTED = "muted"

/** Sent to the moderators only: `{"v":1,"k":"muted_peer","id":"<member uuid>"}` -- who was muted (shown in the roster of moderators only). */
const val CONFERENCE_MODERATION_KIND_MUTED_PEER = "muted_peer"

/** A moderator asks this person to switch the microphone on. The person does it themselves; nothing is ever forced. */
const val CONFERENCE_MODERATION_KIND_UNMUTE_REQUEST = "unmute_request"

/** Broadcast: the room's microphones were locked / released. */
const val CONFERENCE_MODERATION_KIND_LOCK = "lock"
const val CONFERENCE_MODERATION_KIND_UNLOCK = "unlock"

/** V1.9.127 -- broadcast: a moderator switched the reactions of the room on / off (running clients follow at once). */
const val CONFERENCE_MODERATION_KIND_REACTIONS_ON = "reactions_on"
const val CONFERENCE_MODERATION_KIND_REACTIONS_OFF = "reactions_off"

/** The closed set of accepted `k` values -- shared by the server (what it may send) and the client (what it accepts). */
val CONFERENCE_MODERATION_KINDS: Set<String> =
    setOf(
        CONFERENCE_MODERATION_KIND_MUTED,
        CONFERENCE_MODERATION_KIND_MUTED_PEER,
        CONFERENCE_MODERATION_KIND_UNMUTE_REQUEST,
        CONFERENCE_MODERATION_KIND_LOCK,
        CONFERENCE_MODERATION_KIND_UNLOCK,
        CONFERENCE_MODERATION_KIND_REACTIONS_ON,
        CONFERENCE_MODERATION_KIND_REACTIONS_OFF,
    )

/**
 * V1.9.127 -- LiveKit data-packet topic of the reactions (participant -> everybody, peer to peer; the server never sees or counts them).
 * A reaction is a mood, not a record: no storage, no audit, and -- because the client DOM renders it -- it is NOT part of a recording or
 * a stream (see `docs/features/video-conferencing.adoc`, "Reactions and raised hands"). The sender is always the LiveKit participant
 * identity of the packet, never a field of the payload.
 */
const val CONFERENCE_REACTION_TOPIC = "lapis-conference-reaction"

/** Hard cap of a reaction payload (`{"r":"APPLAUSE"}` is 16 bytes). */
const val CONFERENCE_REACTION_MAX_PAYLOAD_BYTES = 32

/**
 * The closed set of eight reactions (V1.9.164 appended [LAUGH], [ASTONISHED], [QUESTION]; order = menu order, append only: old clients
 * decode an unknown name to `null` and drop it). There is deliberately no `HAND`: the raised hand is the server-ordered queue, never a
 * peer packet. [QUESTION] is a mood symbol only -- no hand, no queue.
 */
@Serializable
enum class ConferenceReaction { APPLAUSE, HEART, AMEN, THUMBS_UP, FLOWER, LAUGH, ASTONISHED, QUESTION }

/**
 * V1.9.127 -- LiveKit data-packet topic of the raised-hand queue (server -> client only, like [CONFERENCE_MODERATION_TOPIC]): a packet is
 * accepted by a client ONLY if it carries no sending participant. Wire format `{"v":1,"e":"<16 hex>","seq":N,"ids":[...]}`.
 */
const val CONFERENCE_HANDS_TOPIC = "lapis-conference-hands"

/** Most raised hands of one room at the same time. */
const val CONFERENCE_HAND_QUEUE_MAX = 100

/**
 * Hard cap of a hands payload: 100 identities of the form `evg-<uuid>` are 43 bytes each with quotes and comma, i.e. 4300 bytes plus the
 * header -- 4096 would be too small.
 */
const val CONFERENCE_HANDS_MAX_PAYLOAD_BYTES = 6144

/**
 * V1.9.127 -- the server-ordered queue of raised hands of one room. [epoch] is chosen at random once per server process (16 hex chars):
 * after a restart the queue is empty and [seq] starts again, so a client compares the epoch to tell "a moderator lowered my hand" (same
 * epoch, hand missing) from "the server restarted" (new epoch). [identities] is the order, first hand first.
 */
@Serializable
data class ConferenceHandQueueDto(
    val epoch: String,
    val seq: Long,
    val identities: List<String>,
)

/**
 * V1.9.133 -- LiveKit data-packet topic of the display-only conference timer (server -> client only, like [CONFERENCE_HANDS_TOPIC]): a
 * packet is accepted by a client ONLY if it carries no sending participant. Wire format
 * `{"v":1,"e":"<16 hex>","seq":N,"s":"R|P|X|O","d":<ms>,"rem":<ms>,"t":<epochMs>}`.
 */
const val CONFERENCE_TIMER_TOPIC = "lapis-conference-timer"

/** Hard cap of a timer payload (the fixed-shape packet is well below 128 bytes). */
const val CONFERENCE_TIMER_MAX_PAYLOAD_BYTES = 256

/** Shortest and longest timer a moderator can start (and the longest remaining time after an extension). */
const val CONFERENCE_TIMER_MIN_SECONDS = 10
const val CONFERENCE_TIMER_MAX_SECONDS = 3600

/** The extension steps a moderator may add to a running or paused timer. */
val CONFERENCE_TIMER_EXTENSIONS_SECONDS: Set<Int> = setOf(30, 60, 300)

/** The final phase begins at this many milliseconds remaining or fewer. */
const val CONFERENCE_TIMER_FINAL_PHASE_MS = 10_000L

/** Status of the conference timer. [EXPIRED] is derived (a running timer whose remaining time reached 0), never stored. */
@Serializable
enum class ConferenceTimerStatus { OFF, RUNNING, PAUSED, EXPIRED }

/**
 * V1.9.133 -- one consistent view of a room's timer. [epoch] is random per server process (16 hex chars) and [seq] rises on every real
 * change, so a client takes only a newer view; [remainingMs] is the time left at [serverNowEpochMs] (used ONLY to estimate the clock
 * offset, never as a wall clock) and is never negative; [initialDurationMs] is the denominator of the progress ring (0 when OFF).
 */
@Serializable
data class ConferenceTimerDto(
    val epoch: String,
    val seq: Long,
    val status: ConferenceTimerStatus,
    val initialDurationMs: Long,
    val remainingMs: Long,
    val serverNowEpochMs: Long,
)
