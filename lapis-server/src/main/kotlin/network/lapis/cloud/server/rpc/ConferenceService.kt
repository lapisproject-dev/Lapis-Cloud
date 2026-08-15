package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.TurnCredentialMinter
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.isActiveCommitteeMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceGuestConsentDisclaimerDto
import network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceParticipantDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** V1.0 Wave 6 "Breakout-Räume" -- WARN-logged, best-effort breakout-LiveKit-room cleanup in [ConferenceService.endRoom], see that method's own Wave 6 addition. Did not exist before Wave 6 -- see [network.lapis.cloud.server.rpc.ConferenceBreakoutCoordinator] KDoc. */
private val logger = KotlinLogging.logger {}

/** Default per-member window for [ConferenceService.joinRoomRateLimiter]/[ConferenceService.leaveRoomRateLimiter]/[ConferenceService.listRateLimiter] -- see class KDoc "Request-rate throttling beyond createRoom". */
private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes
private const val DEFAULT_JOIN_RATE_MAX = 30
private const val DEFAULT_LEAVE_RATE_MAX = 30
private const val DEFAULT_LIST_RATE_MAX = 60

/** Wave 5 "Föderations-Gastbeitritt" -- see class KDoc "Request-rate throttling beyond createRoom". */
private const val DEFAULT_GUEST_INFO_RATE_MAX = 30

/**
 * Wave 5 security-audit fix -- [ConferenceService.setRoomGuestAccess] had NO rate limiter at all
 * despite fanning out to up to [network.lapis.cloud.server.conference.ConferenceConfig.maxParticipants]
 * outbound LiveKit `RemoveParticipant` admin calls plus a hash-chained [AuditLogRecorder] write on
 * every single invocation -- a much smaller budget than the plain-request limiters above, matching
 * this call's real per-invocation cost (it is a moderator-only room-level toggle, never called at the
 * per-participant frequency [joinRoomRateLimiter]/[leaveRoomRateLimiter] are sized for).
 */
private const val DEFAULT_GUEST_ACCESS_RATE_MAX = 10

/** Wave-1 "Kleinsitzung" default, mirrors `deploy/local/livekit.yaml`'s own `room.empty_timeout: 300`. Also the grace window [reconcileRoomIfDue] waits before closing a room LiveKit no longer knows about -- see [IConferenceService.listActiveRooms] KDoc "Lazy reconciliation". */
private const val ROOM_EMPTY_TIMEOUT_SECONDS = 300

private const val MAX_TITLE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 1000

/** DoS guard for [ConferenceService.listActiveRooms] -- same class of cap `AuctionService.listAuctions`'s own limit enforces. */
private const val MAX_LIST_RESULTS = 200

/** Security-audit MINOR-10 fix -- [ConferenceService.setRoomMeeting]'s own cap on how many rooms may be bound to the SAME Sitzung at once. */
private const val MAX_ROOMS_PER_MEETING = 10

/** Result of the pre-mint DB read [ConferenceService.joinRoom] needs before it can call [LiveKitAccessToken.mintParticipantToken] outside any `transaction {}` -- see that method's own KDoc "Transaction boundaries" reasoning (shared with [network.lapis.cloud.server.rpc.PostalMailService]). */
private data class JoinPrep(
    val livekitRoomName: String,
    val role: ConferenceRole,
    val displayName: String,
    /**
     * Wave 5 "Föderations-Gastbeitritt" -- non-null iff the caller is [MemberStatus.GAST] (read
     * from [OidcGuestProfileTable] inside the same authorization transaction that verified the
     * consent). `null` for every AKTIV caller -- drives whether [ConferenceService.joinRoom]'s
     * second `transaction {}` writes a [ConferenceGuestConsentAcknowledgmentTable] row at all.
     */
    val guestHomeserverUrl: String? = null,
    /** Wave 5 -- snapshotted alongside [guestHomeserverUrl], see [ConferenceGuestConsentAcknowledgmentTable] KDoc. */
    val guestOrganizationName: String? = null,
)

/**
 * Result of [ConferenceService.setRoomGuestAccess]'s Phase-1 `transaction {}`, consumed by its
 * Phase-2 (outside any transaction) LiveKit disconnect loop -- same "collect inside the
 * transaction, act on the network outside it" shape [JoinPrep] establishes.
 */
private data class RevokePlan(
    val livekitRoomName: String,
    val guestMemberIds: List<Uuid>,
)

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- see [IConferenceService] KDoc and
 * `27-conference.kuml.kts` file header for the full fachlich model. [liveKitAdminClient] is the
 * pluggable Twirp-over-HTTP admin boundary (see that interface's own KDoc "the pluggable LiveKit
 * Room-Service admin boundary"); [createRoomRateLimiter] is a [LoginRateLimiter] instance REUSED as
 * a generic per-member throttle, same reuse [network.lapis.cloud.server.routes.registerOidcRoutes]'s
 * own `"/register"` handler already establishes for OIDC Dynamic Client Registration -- NOT a
 * login-failure guard here either. [config] defaults to a fresh [ConferenceConfig.load] per
 * construction (this codebase constructs one service instance per RPC call, see
 * `network.lapis.cloud.server.Application.module`'s `initRpc { registerService(...) }` block), same
 * "cheap, pure env-var read, safe to repeat" reasoning every other per-request config load in this
 * codebase already relies on.
 *
 * ## Transaction boundaries around the LiveKit network call
 *
 * Every [LiveKitAdminClient] method is `suspend` real outbound network I/O -- called ALWAYS outside
 * any Exposed `transaction {}` block, same discipline
 * [network.lapis.cloud.server.rpc.PostalMailService] KDoc documents at length for its own
 * [network.lapis.cloud.server.postal.PostalMailProvider] call. [liveKitCall] is the single choke
 * point every LiveKit-touching method here funnels through, mapping [LiveKitAdminException] to a
 * [ConflictException] with a sanitized (already-sanitized-by-[LiveKitAdminException] itself)
 * message -- LiveKit being briefly unreachable is a legitimate, user-facing "try again" outcome, not
 * an unhandled 500.
 *
 * ## Authorization re-derivation, never a cached role
 *
 * [requireModeratorOrPrivileged] recomputes "is this caller the room's creator, or BOARD/ADMIN" from
 * [ConferenceRoomTable.createdByMemberId] plus [CurrentMember.isPrivileged] on EVERY call to
 * [endRoom]/[removeParticipant] -- it never trusts `conference_participation.role` (a per-join
 * SNAPSHOT, not an authorization source, see `27-conference.kuml.kts` file header "Two-tier role
 * model") for an authorization decision.
 *
 * ## Request-rate throttling beyond createRoom
 *
 * [createRoomRateLimiter] (a [LoginRateLimiter] reused as a per-member FAILURE-counting throttle,
 * see its own KDoc reasoning above) only covers [createRoom] -- it deliberately does NOT cover
 * [joinRoom]/[leaveRoom]/[listActiveRooms]/[getRoom]/[listParticipants], because "every call counts
 * as a failure" is the wrong model for actions a legitimate member repeats often (reconnecting after
 * a dropped WebRTC session, refreshing a room list). Those five methods are instead guarded by
 * [joinRoomRateLimiter]/[leaveRoomRateLimiter]/[listRateLimiter] -- plain per-member REQUEST-rate
 * limiters, reusing [FederationInboxRateLimiter] (already a generic `checkAndRecord(key): Boolean`
 * sliding-window guard, despite its federation-flavored name -- see that class's own KDoc; its
 * `POST /federation/inbox` use is exactly this same "many legitimate calls must not each look like a
 * failure" shape). [joinRoom] and [leaveRoom] get independent budgets so a scripted join/leave loop
 * cannot escape either one; [listActiveRooms]/[getRoom]/[listParticipants] share [listRateLimiter]
 * because all three fan out into an outbound LiveKit Twirp admin call on every invocation (see each
 * method's own body) and are the DoS vector this throttling closes. [getGuestJoinInfo] (Wave 5) gets
 * its OWN budget ([guestInfoRateLimiter]) rather than joining [listRateLimiter] because -- unlike
 * listActiveRooms/getRoom/listParticipants -- it performs no outbound LiveKit call at all; it is
 * throttled purely as a cheap pre-join probe surface reachable by a federated guest.
 * [setRoomGuestAccess] (Wave 5) gets its own, much STRICTER budget ([guestAccessRateLimiter],
 * security-audit fix) rather than joining any of the above -- unlike a single LiveKit admin call,
 * one invocation can fan out to up to [ConferenceConfig.maxParticipants] outbound `RemoveParticipant`
 * calls plus a hash-chained [AuditLogRecorder] write, so its per-call cost is far higher than the
 * request-rate limiters above are sized for; see [DEFAULT_GUEST_ACCESS_RATE_MAX] KDoc.
 *
 * ## Federated guest entry (Wave 5 "Föderations-Gastbeitritt")
 *
 * [requireRoomEntryAuthorization] is the SINGLE place "may this caller enter/inspect this room at
 * all" is decided -- both [joinRoom] and [listParticipants] funnel through it so the two can never
 * drift apart. It always runs [requireActiveOrGuestMembership] FIRST, so an ANTRAG/AUSGETRETEN/
 * ABGELEHNT caller is rejected identically whether or not the room happens to be guest-opted-in --
 * the per-room `allowFederationGuests` toggle can only NARROW the pre-existing status gate, never
 * widen it.
 */
class ConferenceService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val createRoomRateLimiter: LoginRateLimiter,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val joinRoomRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_JOIN_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val leaveRoomRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_LEAVE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val listRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_LIST_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val guestInfoRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_GUEST_INFO_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    /** Security-audit fix -- see [DEFAULT_GUEST_ACCESS_RATE_MAX] KDoc. */
    private val guestAccessRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_GUEST_ACCESS_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    /**
     * V1.0 Wave 7 "Whiteboard" -- see that wave's own [ConferenceWhiteboardState] KDoc and this
     * class's own `endRoom`/`reconcileRoomIfDue` KDoc additions for why BOTH teardown paths clear
     * whiteboard state. Defaulted (unlike `Application.kt`'s own wiring, which threads a real
     * shared singleton, same "constructed here, NOT left to the service's own constructor default"
     * reasoning [guestAccessRateLimiter] KDoc already documents for rate limiters) purely so
     * pre-existing `ConferenceService(...)` test call sites that have no reason to know about
     * whiteboard state keep compiling unmodified -- a default-constructed, per-call, always-empty
     * state is harmless here (unlike a rate limiter) because nothing in THIS class ever reads
     * whiteboard state, it only ever calls [ConferenceWhiteboardState.clear], a no-op on an empty
     * map.
     */
    private val whiteboardState: ConferenceWhiteboardState = ConferenceWhiteboardState(),
    /**
     * V1.0 Wave 8 "Geteilte Notizen" -- same "constructed here, NOT left to the service's own
     * constructor default" reasoning as [whiteboardState]'s own KDoc, and same default-argument
     * escape hatch purely so pre-existing `ConferenceService(...)` test call sites that have no
     * reason to know about notes state keep compiling unmodified -- nothing in THIS class ever
     * reads notes state, it only ever calls [ConferenceNotesState.clear], a no-op on an empty map.
     */
    private val notesState: ConferenceNotesState = ConferenceNotesState(),
    /**
     * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- [setRoomMeeting]'s own
     * budget, same "constructed here, NOT left to the service's own constructor default" reasoning as
     * [guestAccessRateLimiter] KDoc: `registerService`'s factory lambda constructs a brand-new
     * `ConferenceService` on EVERY RPC call, so relying on a default here would silently give every
     * request a fresh, empty-state limiter. Security-audit MINOR-11 fix -- NO default value, exactly
     * like [ElectionService]/[SystemicConsensusService]'s own `streamGuard` constructor parameter (see
     * those classes' own KDoc "(D6)"): a default would have let the SAME class of bug the Wave-3
     * audit-round-2 rate-limiter finding already caught slip back in here. `Application.module` always
     * threads a real, module-scoped singleton through explicitly; every pre-existing test call site
     * that does not care about this limiter now passes its own throwaway instance explicitly too.
     */
    private val conferenceMeetingBindRateLimiter: FederationInboxRateLimiter,
) : IConferenceService {
    override suspend fun getAvailability(): ConferenceAvailabilityDto {
        resolveCurrentMember(call)
        return ConferenceAvailabilityDto(
            enabled = config.enabled,
            serverUrl = if (config.enabled) config.livekitUrl else null,
            maxParticipants = config.maxParticipants,
        )
    }

    override suspend fun listActiveRooms(): List<ConferenceRoomDto> {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        transaction { requireActiveMembership(memberId = current.memberId) }
        val liveRooms = fetchLiveRooms()
        val now = nowLocalDateTime()
        return transaction {
            reconcileActiveRooms(liveRooms = liveRooms, now = now)
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.endedAt.isNull() }
                .orderBy(ConferenceRoomTable.createdAt, SortOrder.DESC)
                .limit(MAX_LIST_RESULTS)
                .map { row -> rowToDto(row = row, callerId = current.memberId, liveRooms = liveRooms) }
        }
    }

    override suspend fun getRoom(roomId: String): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        val id = roomId.toConferenceUuid()
        transaction { requireActiveMembership(memberId = current.memberId) }
        val liveRooms = fetchLiveRooms()
        val now = nowLocalDateTime()
        return transaction {
            val row =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Conference room $id not found")
            val fresh = reconcileRoomIfDue(row = row, liveRooms = liveRooms, now = now)
            rowToDto(row = fresh, callerId = current.memberId, liveRooms = liveRooms)
        }
    }

    override suspend fun createRoom(input: ConferenceRoomInput): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val normalizedTitle = input.title.trim()
        if (normalizedTitle.isBlank()) throw BadRequestException("title must not be blank")
        if (normalizedTitle.length > MAX_TITLE_LENGTH) {
            throw BadRequestException("title must be at most $MAX_TITLE_LENGTH characters")
        }
        if (input.description.length > MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("description must be at most $MAX_DESCRIPTION_LENGTH characters")
        }
        val throttleKey = "member:${current.memberId}"
        if (!createRoomRateLimiter.checkAllowed(throttleKey)) {
            throw ConflictException("Too many room-creation attempts -- try again later")
        }
        // Every attempt (successful or not) counts against the throttle -- same "generic throttle,
        // not a login-failure guard" reuse registerOidcRoutes' own "/register" handler establishes.
        createRoomRateLimiter.recordFailure(throttleKey)

        transaction { requireActiveMembership(memberId = current.memberId) }
        // Named "newRoomName", NOT "livekitRoomName" -- deliberately avoids colliding with
        // ConferenceRoomTable.livekitRoomName's own column property name; see
        // PeerTransferService.executeTransfer KDoc for the shadowing footgun this sidesteps (a
        // same-named bare reference inside an insert{} body resolves against the Table receiver's
        // column property, not the outer local variable).
        val newRoomName = "lc-${Uuid.random()}"
        val liveKitRoom =
            liveKitCall {
                liveKitAdminClient.createRoom(
                    name = newRoomName,
                    maxParticipants = config.maxParticipants,
                    emptyTimeoutSeconds = ROOM_EMPTY_TIMEOUT_SECONDS,
                )
            }
        val now = nowLocalDateTime()
        return transaction {
            val roomId = Uuid.random()
            ConferenceRoomTable.insert {
                it[id] = roomId
                it[title] = normalizedTitle
                it[description] = input.description
                it[livekitRoomName] = newRoomName
                it[createdByMemberId] = current.memberId
                it[createdAt] = now
                it[endedAt] = null
                it[maxParticipants] = config.maxParticipants
                it[ConferenceRoomTable.allowFederationGuests] = input.allowFederationGuests
            }
            val row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()
            rowToDto(row = row, callerId = current.memberId, liveRooms = mapOf(newRoomName to liveKitRoom.numParticipants))
        }
    }

    override suspend fun joinRoom(
        roomId: String,
        guestConsent: ConferenceGuestConsentAcknowledgmentInput?,
    ): ConferenceJoinTokenDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = joinRoomRateLimiter, memberId = current.memberId)
        // Named "roomUuid", NOT "id" -- deliberately avoids colliding with
        // ConferenceParticipationTable.id's own column property name below (verified empirically:
        // a same-named outer local, even a `val` and not just a function parameter, is what
        // resolves inside `it[id] = ...`, NOT the Table's own id column -- the resulting type
        // mismatch, Uuid where Column<Uuid> is expected, is exactly the "compile-time type
        // mismatch" PeerTransferService.executeTransfer's own KDoc warns this class of footgun
        // produces).
        val roomUuid = roomId.toConferenceUuid()
        val prep =
            transaction {
                val row =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                if (row[ConferenceRoomTable.endedAt] != null) {
                    throw ConflictException("Conference room $roomUuid has already ended")
                }
                val status = requireRoomEntryAuthorization(roomRow = row, current = current)

                // Wave 5: consent is verified for a GAST caller ONLY. For an AKTIV caller
                // `guestConsent` is ignored entirely -- passing a bogus value is a no-op, and no
                // acknowledgment row is ever written for them. This is what keeps AKTIV callers
                // byte-for-byte unaffected by this wave.
                var guestHomeserver: String? = null
                var guestOrganizationName: String? = null
                if (status == MemberStatus.GAST) {
                    val consent =
                        guestConsent
                            ?: throw ConflictException(
                                "A federated guest must acknowledge the current ConferenceGuestConsentDisclaimer " +
                                    "before joining -- call getGuestJoinInfo and submit its version/sha256 unmodified",
                            )
                    if (!ConferenceGuestConsentDisclaimer.matches(version = consent.consentVersion, sha256 = consent.consentSha256)) {
                        throw ConflictException(
                            "consentVersion/consentSha256 do not match the current ConferenceGuestConsentDisclaimer " +
                                "-- call getGuestJoinInfo again and submit its CURRENT version/sha256 unmodified",
                        )
                    }
                    // 1:1 with a GAST member (OidcGuestMemberStore), but read defensively.
                    guestHomeserver =
                        OidcGuestProfileTable
                            .selectAll()
                            .where { OidcGuestProfileTable.memberId eq current.memberId }
                            .singleOrNull()
                            ?.get(OidcGuestProfileTable.homeserverUrl)
                            ?: throw ConflictException(
                                "Guest profile missing for this federated identity -- please sign in again",
                            )
                    guestOrganizationName = organizationDisplayName()
                }

                val role =
                    if (row[ConferenceRoomTable.createdByMemberId] == current.memberId) {
                        ConferenceRole.MODERATOR
                    } else {
                        ConferenceRole.PARTICIPANT
                    }
                JoinPrep(
                    livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
                    role = role,
                    displayName = memberDisplayName(current.memberId),
                    guestHomeserverUrl = guestHomeserver,
                    guestOrganizationName = guestOrganizationName,
                )
            }
        val now = nowLocalDateTime()
        // Security-audit fix: a GAST-issued token gets ConferenceConfig.guestTokenTtlMinutes (short,
        // default 15min) rather than the AKTIV-member config.tokenTtlMinutes (4h default) -- see that
        // property's KDoc "why a separate, shorter TTL". prep.guestHomeserverUrl is non-null iff the
        // caller was verified GAST in the first transaction above (class KDoc "Federated guest
        // entry"); AKTIV callers are entirely unaffected.
        val effectiveTtl = if (prep.guestHomeserverUrl != null) config.guestTokenTtlMinutes else config.tokenTtlMinutes
        val minted =
            LiveKitAccessToken.mintParticipantToken(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                roomName = prep.livekitRoomName,
                identity = current.memberId.toString(),
                displayName = prep.displayName,
                ttl = effectiveTtl.minutes,
            )
        // Audit-round-1 fix: mint a fresh, short-lived TURN credential alongside the JWT, same TTL
        // -- see TurnCredentialMinter KDoc. Empty iff TURN is unconfigured (config.turnEnabled ==
        // false), see ConferenceConfig KDoc "TURN is independently optional".
        val turnServers =
            if (config.turnEnabled) {
                val turnCredential =
                    TurnCredentialMinter.mint(
                        sharedSecret = config.turnSharedSecret,
                        urls = config.turnUrls,
                        label = current.memberId.toString(),
                        ttl = effectiveTtl.minutes,
                    )
                listOf(
                    ConferenceTurnServer(
                        urls = turnCredential.urls,
                        username = turnCredential.username,
                        credential = turnCredential.credential,
                    ),
                )
            } else {
                emptyList()
            }
        transaction {
            // Security-audit fix (TOCTOU): the FIRST transaction above authorized this join, but
            // LiveKitAccessToken.mintParticipantToken/TurnCredentialMinter.mint just happened OUTSIDE
            // any transaction (class KDoc "Transaction boundaries around the LiveKit network call"),
            // a real network-latency gap in which a concurrent setRoomGuestAccess(false) or a
            // member-status change (e.g. leaveMembership -> AUSGETRETEN) could have committed. Re-read
            // the room row with a FOR-UPDATE lock and re-run the exact same
            // requireRoomEntryAuthorization gate the first transaction used, so a room/room-access
            // state that changed in that gap is honored, not the stale snapshot in [prep]. The FOR
            // UPDATE lock additionally makes this re-check and the participation insert below atomic
            // against a *concurrently racing* setRoomGuestAccess(false): that call's own
            // `ConferenceRoomTable.update` blocks on this row lock until this transaction commits or
            // rolls back, so the two can never interleave -- either this join is rejected because the
            // revoke already landed, or the revoke's own guest-sweep (which reads
            // conference_participation AFTER its update) runs after this insert commits and will see
            // (and disconnect) the newly-joined guest. Thrown BEFORE the insert below, so the token
            // already minted above is simply discarded -- never returned to the caller, see class KDoc
            // "Federated guest entry" and IConferenceService.setRoomGuestAccess KDoc design review D16.
            val freshRoomRow =
                ConferenceRoomTable
                    .selectAll()
                    .where { ConferenceRoomTable.id eq roomUuid }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Conference room $roomUuid not found")
            if (freshRoomRow[ConferenceRoomTable.endedAt] != null) {
                throw ConflictException("Conference room $roomUuid has already ended")
            }
            requireRoomEntryAuthorization(roomRow = freshRoomRow, current = current)

            ConferenceParticipationTable.insert {
                it[id] = Uuid.random()
                // Explicitly qualified -- the enclosing joinRoom(roomId: String) PARAMETER would
                // otherwise shadow ConferenceParticipationTable.roomId (the Column) for this bare
                // subscript key, same class of footgun the "roomUuid" rename above avoids.
                it[ConferenceParticipationTable.roomId] = roomUuid
                it[memberId] = current.memberId
                it[role] = prep.role
                it[joinedAt] = now
                it[leftAt] = null
            }
            // Wave 5: append-only, one row PER JOIN (a re-join writes a SECOND row) -- consent is
            // per-join, not a one-time acceptance. Written in the SAME transaction as the
            // participation row above so a guest can never appear in the roster without a matching
            // proof.
            if (prep.guestHomeserverUrl != null) {
                ConferenceGuestConsentAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceGuestConsentAcknowledgmentTable.memberId] = current.memberId
                    it[ConferenceGuestConsentAcknowledgmentTable.roomId] = roomUuid
                    it[acknowledgedAt] = now
                    it[consentVersion] = guestConsent!!.consentVersion
                    it[consentSha256] = guestConsent.consentSha256
                    it[ConferenceGuestConsentAcknowledgmentTable.homeserverUrl] = prep.guestHomeserverUrl
                    it[organizationName] = prep.guestOrganizationName!!
                }
            }
        }
        return ConferenceJoinTokenDto(
            roomId = roomUuid.toString(),
            livekitRoomName = prep.livekitRoomName,
            serverUrl = config.livekitUrl,
            token = minted.jwt,
            identity = current.memberId.toString(),
            displayName = prep.displayName,
            role = prep.role,
            expiresAt = minted.expiresAt.toLocalDateTime(TimeZone.currentSystemDefault()),
            turnServers = turnServers,
        )
    }

    override suspend fun leaveRoom(roomId: String) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = leaveRoomRateLimiter, memberId = current.memberId)
        val id = roomId.toConferenceUuid()
        val now = nowLocalDateTime()
        transaction {
            closeOpenParticipationsFor(roomId = id, memberId = current.memberId, now = now)
        }
    }

    override suspend fun endRoom(roomId: String): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val id = roomId.toConferenceUuid()
        val row =
            transaction {
                val existing =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("Conference room $id not found")
                requireModeratorOrPrivileged(row = existing, current = current)
                existing
            }
        val now = nowLocalDateTime()
        if (row[ConferenceRoomTable.endedAt] == null) {
            liveKitCall { liveKitAdminClient.deleteRoom(row[ConferenceRoomTable.livekitRoomName]) }
            // V1.0 Wave 6 "Breakout-Räume" -- best-effort, OUTSIDE any transaction (class KDoc
            // "Transaction boundaries around the LiveKit network call"), delete every still-open
            // breakout LiveKit room of this parent BEFORE the closing transaction below stamps
            // their DB rows closed. Log-and-continue on failure (unlike ConferenceBreakoutService
            // .recallAll's own fail-loud posture on a genuine error) -- ending the whole meeting
            // must never be blocked by one stuck breakout LiveKit room; an orphaned breakout
            // LiveKit room self-heals via LiveKit's own empty_timeout, see
            // ConferenceBreakoutCoordinator KDoc "Belt-and-braces, not the only safety net".
            val openBreakoutLiveKitNames = transaction { ConferenceBreakoutCoordinator.openLiveKitRoomNames(id) }
            openBreakoutLiveKitNames.forEach { name ->
                runCatching { liveKitCall { liveKitAdminClient.deleteRoom(name) } }
                    .onFailure {
                        logger.warn {
                            "endRoom: failed to delete breakout LiveKit room $name for parent $id -- " +
                                "will self-clean via LiveKit's own empty_timeout"
                        }
                    }
            }
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[endedAt] = now }
                closeAllOpenParticipations(roomId = id, now = now)
                // V1.0 Wave 6 "Breakout-Räume" -- server-internal bridge, NOT a new
                // IConferenceBreakoutService method. Pure DB bookkeeping (the LiveKit deleteRoom
                // calls already happened, best-effort, above) -- see ConferenceBreakoutCoordinator
                // KDoc. Must run before ConferenceRecordingCoordinator's own call below, which must
                // stay LAST per its own contract.
                ConferenceBreakoutCoordinator.closeAllBreakoutRoomsForRoom(roomId = id, now = now)
                // V1.0 Wave 2 "Aufzeichnung" -- server-internal bridge, NOT a new IConferenceService
                // method. Must be the LAST statement in this transaction -- see
                // ConferenceRecordingCoordinator KDoc "deadlock-avoidance contract".
                ConferenceRecordingCoordinator.stopActiveRecordingsForRoom(
                    roomId = id,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            }
            // V1.0 Wave 7 "Whiteboard" -- plain, side-effect-free, thread-safe in-memory removal, no
            // DB-transaction/deadlock-ordering discipline to respect (unlike the two coordinators
            // above), so it deliberately runs OUTSIDE the transaction rather than squeezed into it --
            // see ConferenceWhiteboardState KDoc "clear".
            whiteboardState.clear(id)
            // V1.0 Wave 8 "Geteilte Notizen" -- same reasoning as whiteboardState.clear immediately
            // above, see ConferenceNotesState KDoc "clear".
            notesState.clear(id)
        }
        return transaction {
            val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.single()
            rowToDto(row = fresh, callerId = current.memberId, liveRooms = emptyMap())
        }
    }

    override suspend fun listParticipants(roomId: String): List<ConferenceParticipantDto> {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        val id = roomId.toConferenceUuid()
        val room =
            transaction {
                val row =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("Conference room $id not found")
                val status = requireRoomEntryAuthorization(roomRow = row, current = current)
                // Wave 5: extra narrowing for a guest beyond the shared gate above -- see
                // requireGuestHasJoinedRoom KDoc.
                requireGuestHasJoinedRoom(roomId = id, current = current, status = status)
                row
            }
        val liveIdentities =
            liveKitCall { liveKitAdminClient.listParticipants(room[ConferenceRoomTable.livekitRoomName]) }
                .map { it.identity }
                .toSet()
        return transaction {
            val rows =
                ConferenceParticipationTable
                    .selectAll()
                    .where { ConferenceParticipationTable.roomId eq id }
                    .orderBy(ConferenceParticipationTable.joinedAt, SortOrder.ASC)
                    .toList()
            val participantIds = rows.map { it[ConferenceParticipationTable.memberId] }.distinct()
            // Security-audit fix (privacy/consent mismatch): ConferenceGuestConsentDisclaimer's own
            // DETAIL text promises "Ihr Gaststatus und Ihr Heimserver sind fuer alle uebrigen
            // Teilnehmenden dieser Besprechung sichtbar" -- i.e. visible to FELLOW PARTICIPANTS of
            // THIS meeting, not org-wide. Without this check, any AKTIV member could call
            // listParticipants on any room id (discoverable via listActiveRooms) and see every
            // guest's homeserverUrl regardless of whether they ever joined that room, which is a
            // broader disclosure than the disclaimer describes. A caller who is either the room's
            // creator/moderator or has ANY conference_participation row here (open or closed -- they
            // WERE a participant, matching the disclaimer's own scope) counts; everyone else gets an
            // empty homeserverUrl map, same as if no room had any guests at all.
            val callerIsParticipant =
                room[ConferenceRoomTable.createdByMemberId] == current.memberId ||
                    current.memberId in participantIds
            // ONE query, no N+1. The `status eq GAST` predicate is load-bearing, not decorative: a
            // stale oidc_guest_profile row left behind on a member who was later promoted to AKTIV
            // must NOT surface a guest badge for them (see ConferenceParticipantDto.homeserverUrl
            // KDoc).
            val guestHomeservers: Map<Uuid, String> =
                if (!callerIsParticipant || participantIds.isEmpty()) {
                    emptyMap()
                } else {
                    (MemberTable innerJoin OidcGuestProfileTable)
                        .selectAll()
                        .where { (MemberTable.id inList participantIds) and (MemberTable.status eq MemberStatus.GAST) }
                        .associate { it[MemberTable.id] to it[OidcGuestProfileTable.homeserverUrl] }
                }
            rows.map { row ->
                val memberIdValue = row[ConferenceParticipationTable.memberId]
                ConferenceParticipantDto(
                    memberId = memberIdValue.toString(),
                    displayName = memberDisplayName(memberIdValue),
                    role = row[ConferenceParticipationTable.role],
                    joinedAt = row[ConferenceParticipationTable.joinedAt],
                    leftAt = row[ConferenceParticipationTable.leftAt],
                    live = memberIdValue.toString() in liveIdentities,
                    homeserverUrl = guestHomeservers[memberIdValue],
                )
            }
        }
    }

    override suspend fun removeParticipant(
        roomId: String,
        memberId: String,
    ) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val id = roomId.toConferenceUuid()
        val targetId = memberId.toConferenceUuid()
        val room =
            transaction {
                val existing =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("Conference room $id not found")
                requireModeratorOrPrivileged(row = existing, current = current)
                if (existing[ConferenceRoomTable.createdByMemberId] == targetId) {
                    throw ConflictException("Cannot remove the room's own moderator")
                }
                existing
            }
        liveKitCall {
            liveKitAdminClient.removeParticipant(
                room = room[ConferenceRoomTable.livekitRoomName],
                identity = targetId.toString(),
            )
        }
        val now = nowLocalDateTime()
        transaction {
            closeOpenParticipationsFor(roomId = id, memberId = targetId, now = now)
        }
    }

    /**
     * V1.0 Videokonferenzen Wave 4 "Politur", D1 -- same shape as [endRoom]/[removeParticipant]
     * (fetch-authorize-mutate inside one `transaction {}`, no LiveKit call involved since only the
     * `conference_room.title` column changes). Reuses [MAX_TITLE_LENGTH] rather than a duplicate
     * literal, same validation [createRoom] already applies.
     */
    override suspend fun renameRoom(
        roomId: String,
        title: String,
    ): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) throw BadRequestException("title must not be blank")
        if (normalizedTitle.length > MAX_TITLE_LENGTH) {
            throw BadRequestException("title must be at most $MAX_TITLE_LENGTH characters")
        }
        val id = roomId.toConferenceUuid()
        transaction {
            val existing =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Conference room $id not found")
            requireModeratorOrPrivileged(row = existing, current = current)
            if (existing[ConferenceRoomTable.endedAt] != null) {
                throw ConflictException("Cannot rename an ended room")
            }
            // Explicitly qualified -- the enclosing `renameRoom(title: String)` PARAMETER shadows
            // `ConferenceRoomTable.title`'s own column property for a bare `it[title]` subscript key
            // here (unlike `createRoom`, which has no same-named parameter), same class of footgun
            // the `roomUuid`/`newRoomName` renames elsewhere in this file avoid.
            ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[ConferenceRoomTable.title] = normalizedTitle }
        }
        return transaction {
            val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.single()
            rowToDto(row = fresh, callerId = current.memberId, liveRooms = emptyMap())
        }
    }

    /**
     * V1.0 Videokonferenzen Wave 5 "Föderations-Gastbeitritt" -- see [IConferenceService
     * .getGuestJoinInfo] KDoc. Deliberately returns the room's title/creator even for a
     * non-opted-in room -- room ids are unguessable UUIDv4 (`Uuid.random()` in [createRoom]), so
     * this is not an enumeration surface, and returning them is what makes the client's rejection
     * copy honest ("Die Besprechung „X" lässt derzeit keine Gäste zu.") and what lets a guest see
     * WHO the moderator is even before joining (design review D14).
     */
    override suspend fun getGuestJoinInfo(roomId: String): ConferenceGuestJoinInfoDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = guestInfoRateLimiter, memberId = current.memberId)
        val id = roomId.toConferenceUuid()
        return transaction {
            // ANTRAG/AUSGETRETEN/ABGELEHNT -> ForbiddenException, same as every other entry point.
            val status = requireActiveOrGuestMembership(current.memberId)
            val row =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Conference room $id not found")
            val creatorId = row[ConferenceRoomTable.createdByMemberId]
            ConferenceGuestJoinInfoDto(
                roomId = id.toString(),
                title = row[ConferenceRoomTable.title],
                allowsFederationGuests = row[ConferenceRoomTable.allowFederationGuests],
                roomActive = row[ConferenceRoomTable.endedAt] == null,
                organizationName = organizationDisplayName(),
                createdByMemberId = creatorId.toString(),
                createdByDisplayName = memberDisplayName(creatorId),
                callerIsGuest = status == MemberStatus.GAST,
                disclaimer =
                    ConferenceGuestConsentDisclaimerDto(
                        version = ConferenceGuestConsentDisclaimer.VERSION,
                        headline = ConferenceGuestConsentDisclaimer.HEADLINE,
                        keyPoints = ConferenceGuestConsentDisclaimer.KEY_POINTS,
                        text = ConferenceGuestConsentDisclaimer.TEXT,
                        sha256 = ConferenceGuestConsentDisclaimer.SHA256,
                    ),
            )
        }
    }

    /**
     * V1.0 Videokonferenzen Wave 5 "Föderations-Gastbeitritt" -- see [IConferenceService
     * .setRoomGuestAccess] KDoc. Shape modelled on [renameRoom] (fetch-authorize-mutate) for the
     * flag flip, plus [removeParticipant]'s transaction/LiveKit/transaction shape for the
     * revoke-disconnect leg (design review D16: revoking access must not silently leave already-
     * connected guests inside the room).
     */
    override suspend fun setRoomGuestAccess(
        roomId: String,
        allowFederationGuests: Boolean,
    ): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        // Security-audit fix -- see DEFAULT_GUEST_ACCESS_RATE_MAX KDoc.
        requireWithinRate(limiter = guestAccessRateLimiter, memberId = current.memberId)
        val id = roomId.toConferenceUuid()

        // Phase 1 (transaction): authorize, flip the column, collect the guests to disconnect (if
        // revoking), write the audit-log row.
        val plan =
            transaction {
                val existing =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("Conference room $id not found")
                requireModeratorOrPrivileged(row = existing, current = current)
                if (existing[ConferenceRoomTable.endedAt] != null) {
                    throw ConflictException("Cannot change guest access on an ended room")
                }
                // Explicitly qualified -- the enclosing setRoomGuestAccess(allowFederationGuests:
                // Boolean) PARAMETER shadows ConferenceRoomTable.allowFederationGuests' own column
                // property for a bare `it[allowFederationGuests]` subscript key here, same class of
                // footgun the `renameRoom`/`title` qualification above avoids.
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) {
                    it[ConferenceRoomTable.allowFederationGuests] = allowFederationGuests
                }
                val guestsToDrop =
                    if (allowFederationGuests) {
                        emptyList()
                    } else {
                        (ConferenceParticipationTable innerJoin MemberTable)
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq id) and
                                    ConferenceParticipationTable.leftAt.isNull() and
                                    (MemberTable.status eq MemberStatus.GAST)
                            }.map { it[ConferenceParticipationTable.memberId] }
                            .distinct()
                    }
                // AuditLogRecorder.record must be the LAST lock-taking operation in this
                // transaction -- see that object's KDoc "deadlock-avoidance contract".
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.CONFERENCE_ROOM,
                    entityId = id,
                    action = AuditAction.UPDATE,
                    after = """{"allowFederationGuests":$allowFederationGuests}""",
                )
                RevokePlan(livekitRoomName = existing[ConferenceRoomTable.livekitRoomName], guestMemberIds = guestsToDrop)
            }

        // Phase 2 (OUTSIDE any transaction -- class KDoc "Transaction boundaries around the
        // LiveKit network call"): disconnect each currently-joined guest. Bounded by
        // max_participants (25 by default), so no unbounded fan-out.
        plan.guestMemberIds.forEach { guestId ->
            liveKitCall { liveKitAdminClient.removeParticipant(room = plan.livekitRoomName, identity = guestId.toString()) }
        }

        // Phase 3: close their participation rows.
        val now = nowLocalDateTime()
        return transaction {
            plan.guestMemberIds.forEach { guestId -> closeOpenParticipationsFor(roomId = id, memberId = guestId, now = now) }
            rowToDto(
                row =
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.id eq id
                        }.single(),
                callerId = current.memberId,
                liveRooms = emptyMap(),
            )
        }
    }

    /**
     * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- see
     * [IConferenceService.setRoomMeeting] KDoc for the full (security-audit MAJOR-2/MAJOR-3-widened)
     * authorization matrix. Shape modelled on [renameRoom]/[setRoomGuestAccess] (fetch-authorize-
     * mutate, single transaction, no LiveKit call) -- unlike [setRoomGuestAccess] there is no
     * revoke-disconnect leg here, just the binding column itself.
     */
    override suspend fun setRoomMeeting(
        roomId: String,
        meetingId: String?,
    ): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = conferenceMeetingBindRateLimiter, memberId = current.memberId)
        val id = roomId.toConferenceUuid()
        val newMeetingUuid = meetingId?.toConferenceUuid()
        transaction {
            // forUpdate() -- same locking-order discipline SecretBallotStreamLock's own KDoc
            // "Locking order" prescribes for every caller that acts on hasOpenSecretBallot/
            // hasOpenSecretBallotForMeeting: the affected conference_room row must be locked first,
            // so this binding change serializes against a concurrent ElectionService.openVoting/
            // SystemicConsensusService.freezeOptions targeting the SAME room.
            val existing =
                ConferenceRoomTable
                    .selectAll()
                    .where { ConferenceRoomTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Conference room $id not found")
            // Baseline gate, unchanged -- "is this caller the room's creator, or globally privileged"
            // is still necessary (though, see below, no longer SUFFICIENT) for either direction.
            requireModeratorOrPrivileged(row = existing, current = current)
            if (existing[ConferenceRoomTable.endedAt] != null) {
                throw ConflictException("Cannot change the Sitzung binding of an ended room")
            }
            val currentMeetingId = existing[ConferenceRoomTable.meetingId]
            val newMeetingRow =
                if (newMeetingUuid != null) {
                    MeetingTable.selectAll().where { MeetingTable.id eq newMeetingUuid }.singleOrNull()
                        ?: throw NotFoundException("Meeting $newMeetingUuid not found")
                } else {
                    null
                }

            // Security-audit MAJOR-3 fix -- "Lösen" (unbind entirely, OR rebind to a DIFFERENT
            // meeting) requires BOARD/ADMIN -- the room creator alone is no longer sufficient here,
            // asymmetric to "hin-binden" below (which the creator CAN do, together with committee
            // membership). This ends the protection the currently-bound Sitzung had; only a global
            // privileged role may make that call, not whoever happens to have created the room.
            val isUnbindingOrRebinding = currentMeetingId != null && currentMeetingId != newMeetingUuid
            if (isUnbindingOrRebinding && !current.isPrivileged) throw ForbiddenException()

            // Security-audit MAJOR-2 fix -- "hin-binden" (binding to a NEW/different meeting)
            // additionally requires the caller be either BOARD/ADMIN or an active member of the
            // Gremium the target Sitzung belongs to. Security-audit-round-2 L1 fix: this used to be a
            // hand-rolled `until IS NULL` check, which wrongly rejected a member with a time-limited
            // (but still CURRENT) term -- the normal case for an elected Vorstand seat -- since a term
            // end date almost always exists even while the seat is still active. Reuses
            // [network.lapis.cloud.server.security.isActiveCommitteeMember] instead, the SAME
            // `since <= today AND (until IS NULL OR until >= today)` active-membership semantics every
            // other Committee-role gate in this codebase already establishes
            // ([network.lapis.cloud.server.security.GovernanceAuthorization]'s own `hasCommitteeRole`,
            // via `canSubmitMotion`'s non-GENERAL_ASSEMBLY branch) -- "any role", not just leadership,
            // same as that check. Without this gate entirely, ANY active member creating a room could
            // bind it to an arbitrary foreign Sitzung.
            if (newMeetingRow != null && !current.isPrivileged) {
                if (!current.isActiveCommitteeMember(newMeetingRow[MeetingTable.committeeId])) throw ForbiddenException()
            }

            // Wave 9 -- see IConferenceService.setRoomMeeting KDoc: neither the CURRENTLY-bound
            // meeting (if any, "weg-binden") nor the NEWLY-to-be-bound meeting ("hin-binden") may have
            // an open OR PENDING (Vorbereitungs-Zustand) secret ballot right now -- security-audit
            // MAJOR-3 fix widens both checks from hasOpenSecretBallotForMeeting to
            // hasPendingOrOpenSecretBallot (see that method's own KDoc for the exact pre-open states
            // it additionally covers), closing the "unbind right before a secret ballot opens, so its
            // protection never applies in the first place" gap. hasPendingOrOpenSecretBallot (not the
            // roomId-based hasOpenSecretBallot) is used for the "hin-binden" half because the room is
            // not yet bound to it at this point in the transaction -- there is no
            // conference_room.meeting_id row to derive it from.
            if (currentMeetingId != null && SecretBallotStreamLock.hasPendingOrOpenSecretBallot(currentMeetingId)) {
                throw ConflictException(
                    "Dieser Raum ist gerade an eine Sitzung mit laufender oder unmittelbar bevorstehender " +
                        "geheimer Abstimmung gebunden -- die Zuordnung kann erst nach deren Ende geändert werden.",
                )
            }
            if (newMeetingUuid != null && SecretBallotStreamLock.hasPendingOrOpenSecretBallot(newMeetingUuid)) {
                throw ConflictException(
                    "Die Ziel-Sitzung hat gerade eine laufende oder unmittelbar bevorstehende geheime " +
                        "Abstimmung -- der Raum kann erst nach deren Ende an sie gebunden werden.",
                )
            }

            // Security-audit MINOR-10 fix -- DoS/complexity cap: an unbounded number of rooms bound
            // to the same Sitzung would make SecretBallotStreamGuard.quiesceStreamsForMeeting's own
            // (now-concurrent, see that method's own MINOR-10 fix) fan-out unbounded too. Only
            // checked when actually GROWING the bound set for the target meeting (hin-binden to a
            // meeting this room is not already bound to) -- lösen only ever shrinks it.
            if (newMeetingUuid != null && newMeetingUuid != currentMeetingId) {
                val alreadyBoundCount =
                    ConferenceRoomTable
                        .selectAll()
                        .where { (ConferenceRoomTable.meetingId eq newMeetingUuid) and (ConferenceRoomTable.id neq id) }
                        .count()
                if (alreadyBoundCount >= MAX_ROOMS_PER_MEETING) {
                    throw ConflictException(
                        "Sitzung $newMeetingUuid hat bereits $MAX_ROOMS_PER_MEETING gebundene Räume (Höchstgrenze)",
                    )
                }
            }

            ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) {
                it[ConferenceRoomTable.meetingId] = newMeetingUuid
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_ROOM,
                entityId = id,
                action = AuditAction.UPDATE,
                after = """{"meetingId":${newMeetingUuid?.let { "\"$it\"" } ?: "null"}}""",
            )
        }
        return transaction {
            val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.single()
            rowToDto(row = fresh, callerId = current.memberId, liveRooms = emptyMap())
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** See [IConferenceService] KDoc "The conference enabled gate". */
    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException(
                "Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET " +
                    "unset) -- see ConferenceConfig KDoc",
            )
        }
    }

    /** See class KDoc "Request-rate throttling beyond createRoom". */
    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Too many requests -- try again later")
        }
    }

    /** See class KDoc "Transaction boundaries around the LiveKit network call". */
    private suspend fun <T> liveKitCall(action: suspend () -> T): T =
        try {
            action()
        } catch (e: LiveKitAdminException) {
            throw ConflictException("LiveKit request failed: ${e.message}")
        }

    private suspend fun fetchLiveRooms(): Map<String, Int> =
        liveKitCall { liveKitAdminClient.listRooms() }.associate { it.name to it.numParticipants }

    /** See [IConferenceService.listActiveRooms] KDoc "Lazy reconciliation". */
    private fun reconcileActiveRooms(
        liveRooms: Map<String, Int>,
        now: LocalDateTime,
    ) {
        ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.endedAt.isNull() }
            .toList()
            .forEach { row -> reconcileRoomIfDue(row = row, liveRooms = liveRooms, now = now) }
    }

    /**
     * Closes [row]'s room in place (stamping `ended_at` and every currently-open participation) iff
     * it is still open, LiveKit no longer lists its `livekit_room_name`, AND
     * [ROOM_EMPTY_TIMEOUT_SECONDS] have elapsed since `created_at` -- see class KDoc and
     * [IConferenceService.listActiveRooms] KDoc "Lazy reconciliation". Returns a freshly re-read row
     * if it closed [row], or [row] itself unchanged otherwise.
     */
    private fun reconcileRoomIfDue(
        row: ResultRow,
        liveRooms: Map<String, Int>,
        now: LocalDateTime,
    ): ResultRow {
        if (row[ConferenceRoomTable.endedAt] != null) return row
        if (row[ConferenceRoomTable.livekitRoomName] in liveRooms) return row
        if (!graceElapsed(createdAt = row[ConferenceRoomTable.createdAt], now = now)) return row
        val id = row[ConferenceRoomTable.id]
        ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[endedAt] = now }
        closeAllOpenParticipations(roomId = id, now = now)
        // V1.0 Wave 7 "Whiteboard" -- deliberate deviation from the breakout/recording precedent:
        // this LAZY reconciliation path does NOT call ConferenceBreakoutCoordinator/
        // ConferenceRecordingCoordinator (their cleanup involves DB writes + outbound LiveKit calls,
        // arguably deserving an explicit, audited path via endRoom instead -- a pre-existing, accepted
        // gap). Whiteboard's teardown is a single side-effect-free ConcurrentHashMap.remove(), cheap
        // and safe to run from BOTH paths -- see ConferenceWhiteboardState KDoc "clear" -- which
        // closes exactly the "no unbounded accumulation... as rooms come and go" risk a room that is
        // only ever closed lazily (never via an explicit endRoom call) would otherwise leave open.
        whiteboardState.clear(id)
        // V1.0 Wave 8 "Geteilte Notizen" -- same reasoning as whiteboardState.clear immediately
        // above, see ConferenceNotesState KDoc "clear".
        notesState.clear(id)
        return ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.single()
    }

    private fun graceElapsed(
        createdAt: LocalDateTime,
        now: LocalDateTime,
    ): Boolean {
        val zone = TimeZone.currentSystemDefault()
        return createdAt.toInstant(zone).plus(ROOM_EMPTY_TIMEOUT_SECONDS.seconds) <= now.toInstant(zone)
    }

    private fun closeAllOpenParticipations(
        roomId: Uuid,
        now: LocalDateTime,
    ) {
        ConferenceParticipationTable.update({
            (ConferenceParticipationTable.roomId eq roomId) and ConferenceParticipationTable.leftAt.isNull()
        }) {
            it[leftAt] = now
        }
    }

    private fun closeOpenParticipationsFor(
        roomId: Uuid,
        memberId: Uuid,
        now: LocalDateTime,
    ) {
        ConferenceParticipationTable.update({
            (ConferenceParticipationTable.roomId eq roomId) and
                (ConferenceParticipationTable.memberId eq memberId) and
                ConferenceParticipationTable.leftAt.isNull()
        }) {
            it[leftAt] = now
        }
    }

    /** See class KDoc "Authorization re-derivation, never a cached role". */
    private fun requireModeratorOrPrivileged(
        row: ResultRow,
        current: CurrentMember,
    ) {
        val isCreator = row[ConferenceRoomTable.createdByMemberId] == current.memberId
        if (!isCreator && !current.isPrivileged) throw ForbiddenException()
    }

    /** `organization_settings.name` -- the DSGVO-verantwortliche Organisation the disclaimer names. Single-row lookup. */
    private fun organizationDisplayName(): String =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .single()[OrganizationSettingsTable.name]

    private fun rowToDto(
        row: ResultRow,
        callerId: Uuid,
        liveRooms: Map<String, Int>,
    ): ConferenceRoomDto {
        val creatorId = row[ConferenceRoomTable.createdByMemberId]
        return ConferenceRoomDto(
            id = row[ConferenceRoomTable.id].toString(),
            title = row[ConferenceRoomTable.title],
            description = row[ConferenceRoomTable.description],
            livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
            createdByMemberId = creatorId.toString(),
            createdByDisplayName = memberDisplayName(creatorId),
            createdAt = row[ConferenceRoomTable.createdAt],
            endedAt = row[ConferenceRoomTable.endedAt],
            active = row[ConferenceRoomTable.endedAt] == null,
            maxParticipants = row[ConferenceRoomTable.maxParticipants],
            liveParticipantCount = liveRooms[row[ConferenceRoomTable.livekitRoomName]] ?: 0,
            myRole = if (creatorId == callerId) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT,
            allowFederationGuests = row[ConferenceRoomTable.allowFederationGuests],
            meetingId = row[ConferenceRoomTable.meetingId]?.toString(),
            meetingTitle = row[ConferenceRoomTable.meetingId]?.let { meetingTitle(it) },
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    /** V1.0 Videokonferenzen, Wave 9 -- follow-up lookup for [rowToDto]'s `meetingTitle`, same idiom as [memberDisplayName] above. */
    private fun meetingTitle(meetingId: Uuid): String? =
        MeetingTable
            .selectAll()
            .where { MeetingTable.id eq meetingId }
            .singleOrNull()
            ?.get(MeetingTable.title)

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toConferenceUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
