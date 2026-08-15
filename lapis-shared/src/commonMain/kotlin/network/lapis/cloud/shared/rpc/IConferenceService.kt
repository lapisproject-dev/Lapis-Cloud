package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceParticipantDto
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- LiveKit-backed video conferencing for the
 * codebase's first use case: Vorstandssitzungen and other small (<=25 participant), streaming-free
 * meetings. See the concept document ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault, the
 * 2026-08-01 architecture decision) for the full fachlich model, `27-conference.kuml.kts` file
 * header for the persistence model, and [network.lapis.cloud.server.conference.ConferenceConfig]/
 * [network.lapis.cloud.server.conference.LiveKitAccessToken]/
 * [network.lapis.cloud.server.conference.LiveKitAdminClient] (already landed in an earlier step of
 * this wave) for the LiveKit-side integration this interface's implementation coordinates.
 *
 * ## Two-tier role model
 *
 * [ConferenceRoomDto.myRole]/[ConferenceParticipantDto.role] are [network.lapis.cloud.shared.domain.ConferenceRole.MODERATOR]
 * iff the member in question IS `conference_room.created_by_member_id` -- there is no separate,
 * independently-grantable moderator role, no hand-over, and no multi-moderator concept in Wave 1
 * (all deferred, see "Out of scope" below). [endRoom]/[removeParticipant] additionally accept a
 * global `BOARD`/`ADMIN` [network.lapis.cloud.shared.domain.AccountRole] as an escalation path (an
 * org is never locked out of ending a room merely because its creator went offline mid-meeting) --
 * this NEVER changes what [network.lapis.cloud.shared.domain.ConferenceRole.MODERATOR] means for
 * display purposes, it is purely an additional authorization path checked independently.
 *
 * ## The "conference enabled" gate (env-configured, not a DB/admin toggle)
 *
 * Every method below except [getAvailability] requires `ConferenceConfig.enabled` (LiveKit URL +
 * API key + API secret all configured, see that class's own KDoc) -- if unconfigured, every OTHER
 * method rejects with [ConflictException] and has zero side effects. Unlike `auctionEnabled`/
 * `postalMailEnabled` (which are `OrganizationSettings` DB flags an ADMIN can flip at runtime), this
 * gate is purely operator environment configuration -- there is no in-app "enable Videokonferenz"
 * action in Wave 1. [getAvailability] is the ONE method that never throws for this reason -- it
 * returns `enabled=false` instead, so a client has exactly one place to check before showing the
 * feature's nav entry/button at all.
 *
 * ## Room-name generation and the LiveKit join key
 *
 * `conference_room.livekit_room_name` is generated server-side as `lc-<uuid4>` in [createRoom] --
 * NEVER derived from [ConferenceRoomInput.title] or any other user-supplied text. This keeps the
 * name unguessable, keeps arbitrary user text out of every Twirp JSON request body
 * [network.lapis.cloud.server.conference.LiveKitAdminClient] sends, and makes name collisions
 * impossible.
 *
 * ## Lazy reconciliation, no webhooks (see "Out of scope" below for the full rationale)
 *
 * [listActiveRooms] is the ONE place a room can be closed WITHOUT an explicit [endRoom] call: any
 * `conference_room` row with `ended_at IS NULL` whose `livekit_room_name` no longer appears in
 * LiveKit's own `ListRooms` response, AND whose `created_at` is older than the empty-timeout grace
 * (`deploy/local/livekit.yaml`'s `room.empty_timeout`, 300 seconds), gets `ended_at` stamped in
 * place before the (now-reduced) active list is returned. [getRoom] does the SAME per-room check for
 * the single room it targets. No OTHER method (in particular [createRoom]/[joinRoom]) ever performs
 * this reconciliation as a side effect -- surprising and DoS-shaped for a bulk list, exactly the same
 * "listAuctions never triggers a lazy-close side effect" judgement call
 * [network.lapis.cloud.shared.rpc.IAuctionService] KDoc already makes for its own domain.
 *
 * ## Chat (ephemeral, LiveKit data channel only -- see [network.lapis.cloud.shared.domain.ConferenceChatMessage] KDoc)
 *
 * Wave 1 chat is never an RPC call and never touches this server's database -- it travels entirely
 * over the LiveKit data channel between connected clients and dies with the room. No method on this
 * interface sends, receives, or lists a chat message.
 *
 * ## Federated guest join (Wave 5 "Föderations-Gastbeitritt") -- opt-in per room, consent-gated,
 * never a blanket widening
 *
 * [joinRoom] admits [network.lapis.cloud.shared.domain.MemberStatus.GAST] (via
 * `network.lapis.cloud.server.rpc.requireActiveOrGuestMembership`) ONLY for a room whose
 * `allowFederationGuests` column is `true` (default `false` -- see [ConferenceRoomInput
 * .allowFederationGuests] KDoc), and ONLY against a matching, CURRENT
 * `network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer` acknowledgment (see [joinRoom]'s
 * own KDoc). Every OTHER method on this interface keeps its pre-Wave-5 `requireActiveMembership`
 * gate verbatim -- [listActiveRooms]/[getRoom]/[createRoom] stay AKTIV-only (a guest can neither
 * browse nor create rooms) -- with the single, narrowly-scoped exception of [listParticipants] (see
 * its own KDoc) and the two new Wave-5 methods [getGuestJoinInfo]/[setRoomGuestAccess].
 *
 * A federated guest gets NO moderator rights, cannot be promoted, cannot toggle
 * `allowFederationGuests`, cannot start/stop a recording or stream, cannot rename or end the room,
 * and cannot remove another participant -- every mutating method besides [joinRoom]/[leaveRoom]
 * still requires either room creatorship or a global BOARD/ADMIN role, neither of which a GAST
 * member can ever hold. A guest's LiveKit grant is otherwise IDENTICAL to an ordinary participant's
 * (`canPublish`/`canSubscribe`/`canPublishData` all `true`, same as [ConferenceRole.PARTICIPANT]) --
 * Wave 5 does NOT introduce a reduced, publish-restricted guest grant; that remains a possible
 * future item, not this wave's scope.
 *
 * Still out of scope: no `raum-id@host-domain` identifier, no WebFinger room discovery, no
 * cross-server SFU mesh (a joined guest's media still flows entirely through THIS server's own
 * LiveKit instance, same as every other participant -- see the vault concept note "Föderation":
 * sessions are always server-local). No deep-link route (`#/conference/:roomId`) -- a guest obtains
 * a room id out-of-band (the moderator's "Einladung kopieren" invite text) and pastes it into the
 * guest lobby's own entry field; this codebase has zero parameterized Navigo routes today and
 * introducing one was judged the wrong risk to add inside a trust-boundary wave.
 *
 * ## Out of scope this wave (deliberate, not gaps -- see the Wave 1 plan's own "Explicitly out of
 * scope" section for the full, authoritative list)
 *
 * - **No webhooks.** No `/api/conference/webhook` route, no webhook signature verification, no
 *   webhook-driven presence sync. Every Wave-1 UI need (participant joined/left, track published,
 *   disconnected) is already covered by the client SDK's own `RoomEvent` stream; [endRoom] is
 *   synchronous; the one residual gap (a room everyone merely left) is closed by [listActiveRooms]'s
 *   lazy reconciliation above.
 * - **No whiteboard, shared notes, live subtitles, hand-raise/reactions, or private 1:1 chat.**
 *   (Breakout rooms, originally listed here as out of scope, are now implemented -- see
 *   [network.lapis.cloud.shared.rpc.IConferenceBreakoutService], Wave 6 "Breakout-Räume".)
 * - **No lobby/Warteraum with moderator admission** for LOCAL members -- a federated guest's own
 *   "guest lobby" (Wave 5, client-only, no server-side waiting-room state) is an entry SCREEN, not
 *   an admission queue: [joinRoom] either admits immediately (opt-in + valid consent) or rejects
 *   outright, there is no pending/awaiting-approval state anywhere in this schema.
 * - **No four-tier role system** (Moderator/Präsentator/Teilnehmer/Zuhörer) or in-meeting role
 *   hand-over -- see "Two-tier role model" above. A federated guest is always
 *   [ConferenceRole.PARTICIPANT], never [ConferenceRole.MODERATOR] (`createRoom` is AKTIV-only, so
 *   `conference_room.created_by_member_id` can never equal a GAST member's id).
 * - **No E2EE opt-in mode.**
 * - **No scaling-class auto-switching** (Kleinsitzung/Mittelsitzung/Großveranstaltung) -- a single
 *   hardcoded Kleinsitzung profile (`maxParticipants` defaulting to 25) applies to every room.
 * - **No "Termin -> Konferenzraum" integration.** A [ConferenceRoomDto] is a standalone object with
 *   just a title, never linked to `network.lapis.cloud.shared.rpc.IGovernanceService`'s
 *   `MeetingDto`/`MeetingTable` at all.
 * - **No "Teilnehmerliste = Anwesenheitsliste" integration.** Nothing here writes to
 *   `AttendanceTable`; [ConferenceParticipantDto] is purely this feature's own record, not an
 *   attendance derivation (that requires webhook-accurate presence, a later wave's prerequisite).
 * - **No in-conference launch of a Demokratische Wahl/meritokratische Abstimmung/Systemisches
 *   Konsensieren** -- the room-to-Sitzung binding this interface's [setRoomMeeting] provides (Wave 9
 *   "Stream-Pause bei geheimen Abstimmungen") only lets a moderator ASSOCIATE an existing Sitzung with
 *   a room, so the server-side stream-pause interlock can find it; it does not add any voting-module
 *   UI/RPC surface to this interface itself.
 * - **No audit-log (`AuditLogEntryTable`) entries** for `createRoom`/`endRoom`/`removeParticipant`
 *   (Wave 5's own [setRoomGuestAccess] IS audited -- see that method's own KDoc -- this bullet
 *   covers only the pre-Wave-5 methods it originally named).
 */
@RpcService
interface IConferenceService {
    /** Any authenticated member. Never throws for an unconfigured deployment -- see class KDoc "The conference enabled gate". */
    suspend fun getAvailability(): ConferenceAvailabilityDto

    /**
     * Role: MEMBER+, caller must be [network.lapis.cloud.shared.domain.MemberStatus.AKTIV]. Bounded
     * to the 200 most recently created active rooms -- DoS guard, same class of cap
     * [network.lapis.cloud.shared.rpc.IAuctionService.listAuctions]'s own limit enforces. Performs
     * the lazy reconciliation described in class KDoc "Lazy reconciliation" before returning.
     * Throttled per caller (shared budget with [getRoom]/[listParticipants]) -- see
     * [network.lapis.cloud.server.rpc.ConferenceService] KDoc "Request-rate throttling beyond
     * createRoom"; rejects with [ConflictException] once exceeded, because every call fans out into
     * an outbound LiveKit `ListRooms` admin call.
     */
    suspend fun listActiveRooms(): List<ConferenceRoomDto>

    /**
     * Role: MEMBER+, caller must be AKTIV. Performs the SAME per-room lazy reconciliation as
     * [listActiveRooms], scoped to [roomId]. Throttled per caller (shared budget with
     * [listActiveRooms]/[listParticipants]) -- see [network.lapis.cloud.server.rpc.ConferenceService]
     * KDoc "Request-rate throttling beyond createRoom".
     */
    suspend fun getRoom(roomId: String): ConferenceRoomDto

    /**
     * Role: MEMBER+, caller must be AKTIV. Throttled per caller via a reused
     * [network.lapis.cloud.server.security.LoginRateLimiter] instance (generic per-member throttle,
     * same reuse [network.lapis.cloud.server.routes.registerOidcRoutes]'s own `"/register"` handler
     * already establishes for OIDC Dynamic Client Registration) -- rejects with [ConflictException]
     * once exceeded. Generates the room's `lc-<uuid4>` LiveKit name server-side -- see class KDoc
     * "Room-name generation". The caller does NOT automatically join -- a subsequent [joinRoom] call
     * (by the creator or anyone else) is required to actually obtain a LiveKit token and create the
     * first [ConferenceParticipantDto] row.
     */
    suspend fun createRoom(input: ConferenceRoomInput): ConferenceRoomDto

    /**
     * Role: MEMBER+, caller must be AKTIV **or** [network.lapis.cloud.shared.domain.MemberStatus
     * .GAST]. The room must exist and have `endedAt == null` (else [ConflictException]). Mints a
     * fresh, room-pinned participant token (never cached, see
     * [network.lapis.cloud.server.conference.LiveKitAccessToken.mintParticipantToken] KDoc) and
     * records a new, open [ConferenceParticipantDto] row -- rejoining an already-left room creates a
     * SECOND row (see `27-conference.kuml.kts` file header "append-only per join"), never upserts
     * the prior one. Throttled per caller with its own budget (independent of [leaveRoom]'s) -- see
     * [network.lapis.cloud.server.rpc.ConferenceService] KDoc "Request-rate throttling beyond
     * createRoom"; rejects with [ConflictException] once exceeded, guarding against a scripted
     * join/leave loop that would otherwise mint unbounded LiveKit tokens and
     * `conference_participation` rows.
     *
     * **Wave 5 "Föderations-Gastbeitritt":** an AKTIV caller is COMPLETELY unaffected --
     * [guestConsent] defaults to `null` and is IGNORED for a non-GAST caller (no acknowledgment row
     * is ever written for them, and passing a bogus value changes nothing). For a
     * [network.lapis.cloud.shared.domain.MemberStatus.GAST] caller the call additionally requires
     * (a) the room's own `allowFederationGuests` opt-in AND (b) a [guestConsent] whose
     * `consentVersion`/`consentSha256` match the CURRENT
     * `network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer` -- both re-verified
     * server-side (constant-time hash comparison), never a client-side-only gate. A GAST supplying
     * no/stale/tampered consent, or targeting a room that has not opted in, is rejected with
     * [ForbiddenException]/[ConflictException] and ZERO side effects: no LiveKit token, no
     * `conference_participation` row, no acknowledgment row. ANTRAG/AUSGETRETEN/ABGELEHNT remain
     * rejected exactly as before -- this wave widens the gate to AKTIV+GAST only, never further.
     * Call [getGuestJoinInfo] first to obtain the current version/sha256 to echo back unmodified.
     */
    suspend fun joinRoom(
        roomId: String,
        guestConsent: ConferenceGuestConsentAcknowledgmentInput? = null,
    ): ConferenceJoinTokenDto

    /**
     * Role: MEMBER+ (any authenticated caller with an open participation in this room). Takes NO
     * member id parameter -- it only ever closes the CALLER'S OWN open participation row(s) for
     * [roomId] (`left_at` stamped), which is also why this call carries no IDOR surface. A no-op
     * (not an error) if the caller has no currently-open participation for this room. Never touches
     * `conference_room.ended_at` -- ending the room for everyone is [endRoom]'s job alone. Throttled
     * per caller with its own budget (independent of [joinRoom]'s) -- see
     * [network.lapis.cloud.server.rpc.ConferenceService] KDoc "Request-rate throttling beyond
     * createRoom".
     */
    suspend fun leaveRoom(roomId: String)

    /**
     * Role: the room's creator, OR global BOARD/ADMIN (see class KDoc "Two-tier role model").
     * Idempotent once already ended. Closes every currently-open [ConferenceParticipantDto] row for
     * this room (`left_at` stamped) and calls LiveKit's `DeleteRoom` (disconnects every currently
     * connected participant) before stamping `conference_room.ended_at`.
     */
    suspend fun endRoom(roomId: String): ConferenceRoomDto

    /**
     * Role: MEMBER+, caller must be AKTIV **or** [network.lapis.cloud.shared.domain.MemberStatus
     * .GAST]. Live roster ordered by `joinedAt` -- see
     * [network.lapis.cloud.shared.domain.ConferenceParticipantDto] KDoc for what
     * [network.lapis.cloud.shared.domain.ConferenceParticipantDto.live] does and does not guarantee.
     * Throttled per caller (shared budget with [listActiveRooms]/[getRoom]) -- see
     * [network.lapis.cloud.server.rpc.ConferenceService] KDoc "Request-rate throttling beyond
     * createRoom"; rejects with [ConflictException] once exceeded, because every call fans out into
     * an outbound LiveKit `ListParticipants` admin call.
     *
     * **Wave 5:** a [network.lapis.cloud.shared.domain.MemberStatus.GAST] caller is admitted iff
     * the room has `allowFederationGuests = true` AND the caller has at least one
     * `conference_participation` row for this room (i.e. they actually joined it at some point via
     * [joinRoom]) -- a guest can never enumerate the roster of a room they were merely told the id
     * of. Every guest participant's [network.lapis.cloud.shared.domain.ConferenceParticipantDto
     * .homeserverUrl] is populated; every ordinary member's is `null`.
     */
    suspend fun listParticipants(roomId: String): List<ConferenceParticipantDto>

    /**
     * Role: the room's creator, OR global BOARD/ADMIN. Refuses (with [ConflictException]) to target
     * the room's own creator -- a moderator cannot remove themselves via this call (use [endRoom] or
     * simply disconnect). Closes the target's currently-open participation row(s) for this room and
     * calls LiveKit's `RemoveParticipant` (disconnects them immediately) -- does NOT end the room.
     */
    suspend fun removeParticipant(
        roomId: String,
        memberId: String,
    )

    /**
     * Role: the room's creator, OR global BOARD/ADMIN (same gate as [endRoom]/[removeParticipant]).
     * The room must still be active (`endedAt == null`), else [ConflictException]. Same title
     * validation as [createRoom] (non-blank after trim, at most 200 characters). V1.0
     * Videokonferenzen Wave 4 "Politur", D1: supports the in-call header's inline rename affordance
     * for the single-button flow's auto-generated default title -- see
     * [network.lapis.cloud.server.rpc.ConferenceService] KDoc for the wave's own context.
     */
    suspend fun renameRoom(
        roomId: String,
        title: String,
    ): ConferenceRoomDto

    /**
     * Wave 5 "Föderations-Gastbeitritt". Role: MEMBER+, caller must be AKTIV **or**
     * [network.lapis.cloud.shared.domain.MemberStatus.GAST] (reuses
     * `network.lapis.cloud.server.rpc.requireActiveOrGuestMembership`). NEVER throws merely
     * because a room does not admit guests -- it returns `allowsFederationGuests = false` instead,
     * so the client can render an honest, specific reason (see [ConferenceGuestJoinInfoDto] KDoc
     * for why this must be data, not an exception). Throws [NotFoundException] for an unknown room
     * id and [ForbiddenException] for an ANTRAG/AUSGETRETEN/ABGELEHNT caller. An AKTIV moderator
     * may call this on their own room to preview exactly what a guest will be asked to confirm
     * (`callerIsGuest = false` in that case). Throttled per caller on its OWN budget (this method
     * makes NO outbound LiveKit call, so it deliberately does not share
     * [listActiveRooms]/[getRoom]/[listParticipants]'s shared budget) -- rejects with
     * [ConflictException] once exceeded.
     */
    suspend fun getGuestJoinInfo(roomId: String): ConferenceGuestJoinInfoDto

    /**
     * Wave 5 "Föderations-Gastbeitritt". Role: the room's creator, OR global BOARD/ADMIN -- the
     * SAME `requireModeratorOrPrivileged` gate as [endRoom]/[removeParticipant]/[renameRoom]. The
     * room must still be active (`endedAt == null`), else [ConflictException]. Turning the opt-in
     * OFF additionally DISCONNECTS every currently-joined
     * [network.lapis.cloud.shared.domain.MemberStatus.GAST] from the LiveKit room and closes their
     * open `conference_participation` rows (same machinery [removeParticipant] uses) -- a room
     * that "no longer admits guests" must not silently keep guests inside it (design review D16).
     * Ordinary AKTIV participants are never touched. Writes an
     * [network.lapis.cloud.shared.domain.AuditEntityType.CONFERENCE_ROOM] audit-log row either way
     * (grant or revoke).
     */
    suspend fun setRoomGuestAccess(
        roomId: String,
        allowFederationGuests: Boolean,
    ): ConferenceRoomDto

    /**
     * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen". Bindet den Raum an eine
     * Sitzung; [meetingId] == null löst die Bindung. Diese Bindung ist die EINZIGE Kopplung zwischen
     * Conference- und Governance-Domäne (siehe
     * `network.lapis.cloud.server.rpc.SecretBallotStreamLock` KDoc) und damit die Voraussetzung
     * dafür, dass die automatische Stream-Pause bei geheimen Abstimmungen überhaupt greifen kann.
     *
     * **Role -- security-audit MAJOR-2/MAJOR-3 fix, asymmetrisch zwischen Binden und Lösen**:
     * - **Hin-binden** (Zuordnung zu einer NEUEN Sitzung): Raum-Ersteller ODER globaler BOARD/ADMIN
     *   (wie [setRoomGuestAccess]), UND zusätzlich Mitglied des Gremiums, dem die Ziel-Sitzung
     *   zugeordnet ist (ODER wieder BOARD/ADMIN). Ohne diese zweite Bedingung könnte jedes aktive
     *   Mitglied seinen eigenen Raum an eine beliebige fremde Sitzung binden.
     * - **Lösen** (Bindung entfernen ODER auf eine ANDERE Sitzung ändern): NUR globaler BOARD/ADMIN --
     *   der Raum-Ersteller allein genügt hier NICHT mehr, auch wenn er Gremiumsmitglied ist. Dieser
     *   Pfad ist absichtlich enger als das Binden, weil er den Schutz für die AKTUELL gebundene
     *   Sitzung beendet.
     *
     * Wirft [ConflictException], wenn für die AKTUELL gebundene ODER die neu zu bindende Sitzung
     * gerade eine geheime Abstimmung offen ODER unmittelbar bevorstehend ist (Vorbereitungs-Zustand
     * vor der eigentlichen Öffnung, siehe `SecretBallotStreamLock.hasPendingOrOpenSecretBallot`
     * KDoc) -- eine Umbindung mitten in einer geheimen Wahl (oder unmittelbar davor) würde die Sperre
     * entweder still aufheben (weg-binden) oder einen laufenden Stream überraschend anhalten
     * (hin-binden). Beides ist eine Manipulationsfläche, keine legitime Bedienhandlung. Außerhalb
     * dieser Zeitfenster bleibt die Bindung durch BOARD/ADMIN änderbar -- der Schutz gilt für die
     * DAUER einer geheimen Abstimmung, nicht als generelle Unveränderlichkeit der Zuordnung.
     */
    suspend fun setRoomMeeting(
        roomId: String,
        meetingId: String?,
    ): ConferenceRoomDto
}
