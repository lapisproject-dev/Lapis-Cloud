package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.TurnCredentialMinter
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceParticipantDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Default per-member window for [ConferenceService.joinRoomRateLimiter]/[ConferenceService.leaveRoomRateLimiter]/[ConferenceService.listRateLimiter] -- see class KDoc "Request-rate throttling beyond createRoom". */
private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes
private const val DEFAULT_JOIN_RATE_MAX = 30
private const val DEFAULT_LEAVE_RATE_MAX = 30
private const val DEFAULT_LIST_RATE_MAX = 60

/** Wave-1 "Kleinsitzung" default, mirrors `deploy/local/livekit.yaml`'s own `room.empty_timeout: 300`. Also the grace window [reconcileRoomIfDue] waits before closing a room LiveKit no longer knows about -- see [IConferenceService.listActiveRooms] KDoc "Lazy reconciliation". */
private const val ROOM_EMPTY_TIMEOUT_SECONDS = 300

private const val MAX_TITLE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 1000

/** DoS guard for [ConferenceService.listActiveRooms] -- same class of cap `AuctionService.listAuctions`'s own limit enforces. */
private const val MAX_LIST_RESULTS = 200

/** Result of the pre-mint DB read [ConferenceService.joinRoom] needs before it can call [LiveKitAccessToken.mintParticipantToken] outside any `transaction {}` -- see that method's own KDoc "Transaction boundaries" reasoning (shared with [network.lapis.cloud.server.rpc.PostalMailService]). */
private data class JoinPrep(
    val livekitRoomName: String,
    val role: ConferenceRole,
    val displayName: String,
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
 * method's own body) and are the DoS vector this throttling closes.
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
        requireWithinRate(listRateLimiter, current.memberId)
        transaction { requireActiveMembership(current.memberId) }
        val liveRooms = fetchLiveRooms()
        val now = nowLocalDateTime()
        return transaction {
            reconcileActiveRooms(liveRooms, now)
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.endedAt.isNull() }
                .orderBy(ConferenceRoomTable.createdAt, SortOrder.DESC)
                .limit(MAX_LIST_RESULTS)
                .map { row -> rowToDto(row, current.memberId, liveRooms) }
        }
    }

    override suspend fun getRoom(roomId: String): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(listRateLimiter, current.memberId)
        val id = roomId.toConferenceUuid()
        transaction { requireActiveMembership(current.memberId) }
        val liveRooms = fetchLiveRooms()
        val now = nowLocalDateTime()
        return transaction {
            val row =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Conference room $id not found")
            val fresh = reconcileRoomIfDue(row, liveRooms, now)
            rowToDto(fresh, current.memberId, liveRooms)
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

        transaction { requireActiveMembership(current.memberId) }
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
            }
            val row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()
            rowToDto(row, current.memberId, mapOf(newRoomName to liveKitRoom.numParticipants))
        }
    }

    override suspend fun joinRoom(roomId: String): ConferenceJoinTokenDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(joinRoomRateLimiter, current.memberId)
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
                requireActiveMembership(current.memberId)
                val row =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                if (row[ConferenceRoomTable.endedAt] != null) {
                    throw ConflictException("Conference room $roomUuid has already ended")
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
                )
            }
        val now = nowLocalDateTime()
        val minted =
            LiveKitAccessToken.mintParticipantToken(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                roomName = prep.livekitRoomName,
                identity = current.memberId.toString(),
                displayName = prep.displayName,
                ttl = config.tokenTtlMinutes.minutes,
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
                        ttl = config.tokenTtlMinutes.minutes,
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
        requireWithinRate(leaveRoomRateLimiter, current.memberId)
        val id = roomId.toConferenceUuid()
        val now = nowLocalDateTime()
        transaction {
            closeOpenParticipationsFor(id, current.memberId, now)
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
                requireModeratorOrPrivileged(existing, current)
                existing
            }
        val now = nowLocalDateTime()
        if (row[ConferenceRoomTable.endedAt] == null) {
            liveKitCall { liveKitAdminClient.deleteRoom(row[ConferenceRoomTable.livekitRoomName]) }
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[endedAt] = now }
                closeAllOpenParticipations(id, now)
                // V1.0 Wave 2 "Aufzeichnung" -- server-internal bridge, NOT a new IConferenceService
                // method. Must be the LAST statement in this transaction -- see
                // ConferenceRecordingCoordinator KDoc "deadlock-avoidance contract".
                ConferenceRecordingCoordinator.stopActiveRecordingsForRoom(id, current.memberId, current.role)
            }
        }
        return transaction {
            val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.single()
            rowToDto(fresh, current.memberId, emptyMap())
        }
    }

    override suspend fun listParticipants(roomId: String): List<ConferenceParticipantDto> {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(listRateLimiter, current.memberId)
        val id = roomId.toConferenceUuid()
        transaction { requireActiveMembership(current.memberId) }
        val room =
            transaction {
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
            } ?: throw NotFoundException("Conference room $id not found")
        val liveIdentities =
            liveKitCall { liveKitAdminClient.listParticipants(room[ConferenceRoomTable.livekitRoomName]) }
                .map { it.identity }
                .toSet()
        return transaction {
            ConferenceParticipationTable
                .selectAll()
                .where { ConferenceParticipationTable.roomId eq id }
                .orderBy(ConferenceParticipationTable.joinedAt, SortOrder.ASC)
                .map { row ->
                    val memberIdValue = row[ConferenceParticipationTable.memberId]
                    ConferenceParticipantDto(
                        memberId = memberIdValue.toString(),
                        displayName = memberDisplayName(memberIdValue),
                        role = row[ConferenceParticipationTable.role],
                        joinedAt = row[ConferenceParticipationTable.joinedAt],
                        leftAt = row[ConferenceParticipationTable.leftAt],
                        live = memberIdValue.toString() in liveIdentities,
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
                requireModeratorOrPrivileged(existing, current)
                if (existing[ConferenceRoomTable.createdByMemberId] == targetId) {
                    throw ConflictException("Cannot remove the room's own moderator")
                }
                existing
            }
        liveKitCall { liveKitAdminClient.removeParticipant(room[ConferenceRoomTable.livekitRoomName], targetId.toString()) }
        val now = nowLocalDateTime()
        transaction {
            closeOpenParticipationsFor(id, targetId, now)
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
            requireModeratorOrPrivileged(existing, current)
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
            rowToDto(fresh, current.memberId, emptyMap())
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
            .forEach { row -> reconcileRoomIfDue(row, liveRooms, now) }
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
        if (!graceElapsed(row[ConferenceRoomTable.createdAt], now)) return row
        val id = row[ConferenceRoomTable.id]
        ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[endedAt] = now }
        closeAllOpenParticipations(id, now)
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
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toConferenceUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
