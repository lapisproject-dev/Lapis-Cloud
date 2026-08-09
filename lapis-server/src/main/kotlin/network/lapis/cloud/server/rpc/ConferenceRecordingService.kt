package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingAccess
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Statuses that count as "already active" for the one-active-recording-per-room invariant -- see [ConferenceRecordingService.startRecording] KDoc. */
private val ACTIVE_RECORDING_STATUSES = listOf(ConferenceRecordingStatus.RECORDING, ConferenceRecordingStatus.STOPPING)

/** DoS guard for [ConferenceRecordingService.listRecordings] -- same class of cap [ConferenceService.listActiveRooms]'s own limit enforces. */
private const val MAX_LIST_RESULTS = 200

private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes
private const val DEFAULT_STOP_RATE_MAX = 30
private const val DEFAULT_READ_RATE_MAX = 60

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- see [IConferenceRecordingService]
 * KDoc for the full authorization matrix and the "separate service, startRecording inserts a row
 * only" design decisions this class implements. [config]/[recordingConfig] both default to a fresh
 * `.load()` per construction, same "cheap, pure env-var read, safe to repeat" reasoning
 * [ConferenceService]'s own KDoc documents (this codebase constructs one service instance per RPC
 * call). [ffmpegAvailable] is NOT re-probed here -- it is the ONE-TIME startup probe result
 * ([ConferenceRecordingConfig.probeFfmpegAvailable], run once in `Application.module`), passed in
 * so this class never spawns a `ProcessBuilder` per RPC call.
 *
 * ## Authorization re-derivation, never a cached role
 *
 * [requireModeratorOrPrivileged] recomputes "is this caller the room's creator, or BOARD/ADMIN"
 * from [ConferenceRoomTable.createdByMemberId] plus [CurrentMember.isPrivileged] on EVERY call to
 * [startRecording]/[stopRecording] -- same discipline [ConferenceService]'s own KDoc documents at
 * length for its own `endRoom`/`removeParticipant`. Deliberately duplicated here (not extracted
 * into a shared helper) rather than widening [ConferenceService]'s already-live-verified
 * constructor for a two-line check -- see [IConferenceRecordingService] KDoc "A separate service"
 * reason 3 ("different server-side collaborators... folding them in would widen a live-verified
 * Wave 1 constructor for no benefit"), which applies equally to this small authorization helper.
 *
 * ## `startRecording` is transaction-only -- no LiveKit call
 *
 * See [IConferenceRecordingService] KDoc "startRecording inserts a row only" -- this class never
 * imports [network.lapis.cloud.server.conference.LiveKitEgressClient] at all. A later wave's
 * `RecordingPoller` is the sole caller of every LiveKit Egress Twirp method.
 *
 * ## Request-rate throttling
 *
 * [startRecordingRateLimiter] is a [LoginRateLimiter] reused as a generic per-member throttle
 * (every attempt counts, successful or not) -- same reuse [ConferenceService.createRoomRateLimiter]
 * already establishes for the analogous "rare, moderator-gated, room-creating action" shape.
 * [stopRecordingRateLimiter]/[readRateLimiter] are plain per-member REQUEST-rate limiters reusing
 * [FederationInboxRateLimiter] -- same "many legitimate calls must not each look like a failure"
 * reasoning [ConferenceService]'s own `joinRoomRateLimiter`/`leaveRoomRateLimiter`/`listRateLimiter`
 * KDoc gives; [stopRecording] gets its own independent budget, [getActiveRecording]/[listRecordings]
 * share [readRateLimiter] since neither is individually more sensitive than the other (both are
 * plain DB reads, unlike [ConferenceService]'s own read methods which each fan out into an outbound
 * LiveKit admin call).
 */
class ConferenceRecordingService(
    private val call: ApplicationCall,
    private val ffmpegAvailable: Boolean,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val recordingConfig: ConferenceRecordingConfig = ConferenceRecordingConfig.load(),
    private val startRecordingRateLimiter: LoginRateLimiter = LoginRateLimiter(),
    private val stopRecordingRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_STOP_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val readRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_READ_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
) : IConferenceRecordingService {
    override suspend fun getRecordingAvailability(): ConferenceRecordingAvailabilityDto {
        resolveCurrentMember(call)
        return ConferenceRecordingAvailabilityDto(
            enabled = config.enabled && recordingConfig.enabled && ffmpegAvailable,
            ffmpegAvailable = ffmpegAvailable,
            maxDurationMinutes = recordingConfig.maxDurationMinutes.toInt(),
        )
    }

    override suspend fun startRecording(
        roomId: String,
        accessLevel: DocumentAccessLevel,
    ): ConferenceRecordingDto {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        val throttleKey = "member:${current.memberId}"
        if (!startRecordingRateLimiter.checkAllowed(throttleKey)) {
            throw ConflictException("Too many recording-start attempts -- try again later")
        }
        // Every attempt (successful or not) counts against the throttle -- same reuse
        // ConferenceService.createRoom's own throttle already establishes.
        startRecordingRateLimiter.recordFailure(throttleKey)

        // Named "roomUuid", NOT "id" -- deliberately avoids colliding with
        // ConferenceRecordingTable.id's own column property name below (verified empirically in
        // this exact wave: a same-named outer local resolves inside `it[id] = ...`, NOT the
        // Table's own id column -- same shadowing footgun ConferenceService.joinRoom's own
        // "roomUuid" rename already documents, see PeerTransferService.executeTransfer KDoc for
        // the canonical explanation).
        val roomUuid = roomId.toRecordingUuid()
        return transaction {
            // `.forUpdate()` locks the room row for the rest of this transaction -- closes the
            // check-then-act race on the "one active recording per room" invariant below: two
            // concurrent startRecording calls for the same room now serialize on this lock instead
            // of both reading "no active recording" and both inserting a RECORDING row. Same
            // discipline LtrBalanceProvider/PasswordResetTokenStore/AuditLogRecorder/
            // FederationRelationshipStore already establish for exactly this bug class (see class
            // KDoc "Authorization re-derivation" for the analogous per-call re-derivation
            // discipline this mirrors). The room row is the natural lock target here -- there is no
            // pre-existing row to lock on the recording side before the first recording exists.
            val room =
                ConferenceRoomTable
                    .selectAll()
                    .where { ConferenceRoomTable.id eq roomUuid }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Conference room $roomUuid not found")
            if (room[ConferenceRoomTable.endedAt] != null) {
                throw ConflictException("Conference room $roomUuid has already ended -- cannot start a recording")
            }
            requireModeratorOrPrivileged(room, current)

            val alreadyActive =
                ConferenceRecordingTable
                    .selectAll()
                    .where {
                        (ConferenceRecordingTable.roomId eq roomUuid) and
                            (ConferenceRecordingTable.status inList ACTIVE_RECORDING_STATUSES)
                    }.limit(1)
                    .any()
            if (alreadyActive) {
                throw ConflictException("A recording is already active for this room")
            }

            val now = nowLocalDateTime()
            val recordingId = Uuid.random()
            ConferenceRecordingTable.insert {
                it[ConferenceRecordingTable.id] = recordingId
                it[ConferenceRecordingTable.roomId] = roomUuid
                it[startedByMemberId] = current.memberId
                it[startedAt] = now
                it[stoppedAt] = null
                it[readyAt] = null
                it[status] = ConferenceRecordingStatus.RECORDING
                it[ConferenceRecordingTable.accessLevel] = accessLevel
                it[documentId] = null
                it[rawDir] = recordingId.toString()
                it[durationSeconds] = null
                it[fileSizeBytes] = null
                it[failureReason] = null
                it[composeAttempts] = 0
            }
            // AuditLogRecorder.record must be the LAST lock-taking operation in this transaction --
            // see that object's KDoc "deadlock-avoidance contract".
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_RECORDING,
                entityId = recordingId,
                action = AuditAction.CREATE,
                occurredAt = now,
            )
            val row = ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingId }.single()
            rowToDto(row, room[ConferenceRoomTable.title], current)
        }
    }

    override suspend fun stopRecording(recordingId: String): ConferenceRecordingDto {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(stopRecordingRateLimiter, current.memberId)
        // See startRecording's own "roomUuid, NOT id" comment -- same shadowing avoidance.
        val recordingUuid = recordingId.toRecordingUuid()
        return transaction {
            val row =
                ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingUuid }.singleOrNull()
                    ?: throw NotFoundException("Conference recording $recordingUuid not found")
            val room =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceRecordingTable.roomId] }.singleOrNull()
                    ?: throw NotFoundException("Conference room for recording $recordingUuid not found")
            requireModeratorOrPrivileged(room, current)

            if (row[ConferenceRecordingTable.status] == ConferenceRecordingStatus.RECORDING) {
                val now = nowLocalDateTime()
                ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingUuid }) {
                    it[stoppedAt] = now
                    it[status] = ConferenceRecordingStatus.STOPPING
                }
                // See startRecording's own comment -- must be the LAST lock-taking operation.
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.CONFERENCE_RECORDING,
                    entityId = recordingUuid,
                    action = AuditAction.UPDATE,
                    occurredAt = now,
                )
            }
            // Idempotent once already stopped -- see IConferenceRecordingService.stopRecording KDoc.
            val fresh = ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingUuid }.single()
            rowToDto(fresh, room[ConferenceRoomTable.title], current)
        }
    }

    override suspend fun getActiveRecording(roomId: String): List<ConferenceRecordingDto> {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(readRateLimiter, current.memberId)
        val roomUuid = roomId.toRecordingUuid()
        return transaction {
            requireActiveMembership(current.memberId)
            val room =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                    ?: throw NotFoundException("Conference room $roomUuid not found")
            val row =
                ConferenceRecordingTable
                    .selectAll()
                    .where {
                        (ConferenceRecordingTable.roomId eq roomUuid) and
                            (ConferenceRecordingTable.status inList ACTIVE_RECORDING_STATUSES)
                    }.orderBy(ConferenceRecordingTable.startedAt, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?: return@transaction emptyList()
            // Never gated on canAccessDocumentAtLevel -- see IConferenceRecordingService
            // .getActiveRecording KDoc "everyone in the room has a legal right to know".
            listOf(rowToDto(row, room[ConferenceRoomTable.title], current))
        }
    }

    override suspend fun listRecordings(roomId: String?): List<ConferenceRecordingDto> {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(readRateLimiter, current.memberId)
        val parsedRoomId = roomId?.toRecordingUuid()
        return transaction {
            requireActiveMembership(current.memberId)
            val query =
                if (parsedRoomId != null) {
                    ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq parsedRoomId }
                } else {
                    ConferenceRecordingTable.selectAll()
                }
            val roomTitleById = mutableMapOf<Uuid, String>()
            query
                .orderBy(ConferenceRecordingTable.startedAt, SortOrder.DESC)
                .limit(MAX_LIST_RESULTS)
                .filter { row ->
                    ConferenceRecordingAccess.mayAccess(
                        current,
                        row[ConferenceRecordingTable.accessLevel],
                        row[ConferenceRecordingTable.startedByMemberId],
                    )
                }.map { row ->
                    val roomIdValue = row[ConferenceRecordingTable.roomId]
                    val title =
                        roomTitleById.getOrPut(roomIdValue) {
                            ConferenceRoomTable
                                .select(ConferenceRoomTable.title)
                                .where { ConferenceRoomTable.id eq roomIdValue }
                                .singleOrNull()
                                ?.get(ConferenceRoomTable.title)
                                ?: ""
                        }
                    rowToDto(row, title, current)
                }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** See [IConferenceRecordingService] KDoc "A second, independent availability gate". */
    private fun requireRecordingEnabled() {
        if (!config.enabled) {
            throw ConflictException(
                "Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET " +
                    "unset) -- see ConferenceConfig KDoc",
            )
        }
        if (!recordingConfig.enabled) {
            throw ConflictException(
                "Aufzeichnung is not enabled on this server (LAPIS_RECORDING_ENABLED unset) -- see ConferenceRecordingConfig KDoc",
            )
        }
        if (!ffmpegAvailable) {
            throw ConflictException(
                "Aufzeichnung is unavailable on this server (ffmpeg not found) -- see ConferenceRecordingConfig.probeFfmpegAvailable KDoc",
            )
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Too many requests -- try again later")
        }
    }

    /** See class KDoc "Authorization re-derivation, never a cached role". */
    private fun requireModeratorOrPrivileged(
        room: ResultRow,
        current: CurrentMember,
    ) {
        val isCreator = room[ConferenceRoomTable.createdByMemberId] == current.memberId
        if (!isCreator && !current.isPrivileged) throw ForbiddenException()
    }

    private fun rowToDto(
        row: ResultRow,
        roomTitle: String,
        current: CurrentMember,
    ): ConferenceRecordingDto {
        val recordingId = row[ConferenceRecordingTable.id]
        val startedByMemberId = row[ConferenceRecordingTable.startedByMemberId]
        val status = row[ConferenceRecordingTable.status]
        val documentId = row[ConferenceRecordingTable.documentId]
        val trackCount =
            ConferenceRecordingTrackTable
                .selectAll()
                .where { ConferenceRecordingTrackTable.recordingId eq recordingId }
                .count()
                .toInt()
        // mediaUrl: only non-null once READY AND this specific caller may access it -- see
        // ConferenceRecordingDto KDoc and ConferenceRecordingAccess.mayAccess KDoc "the ONE access
        // predicate", same rule the media route itself re-checks server-side (never trust this
        // client-visible URL alone).
        val mediaUrl: String? =
            if (status == ConferenceRecordingStatus.READY &&
                ConferenceRecordingAccess.mayAccess(current, row[ConferenceRecordingTable.accessLevel], startedByMemberId)
            ) {
                "/api/conference/recordings/$recordingId/media"
            } else {
                null
            }
        return ConferenceRecordingDto(
            id = recordingId.toString(),
            roomId = row[ConferenceRecordingTable.roomId].toString(),
            roomTitle = roomTitle,
            status = status,
            startedByMemberId = startedByMemberId.toString(),
            startedByDisplayName = memberDisplayName(startedByMemberId),
            startedAt = row[ConferenceRecordingTable.startedAt],
            stoppedAt = row[ConferenceRecordingTable.stoppedAt],
            readyAt = row[ConferenceRecordingTable.readyAt],
            durationSeconds = row[ConferenceRecordingTable.durationSeconds],
            accessLevel = row[ConferenceRecordingTable.accessLevel],
            documentId = documentId?.toString(),
            mediaUrl = mediaUrl,
            fileSizeBytes = row[ConferenceRecordingTable.fileSizeBytes],
            trackCount = trackCount,
            failureReason = row[ConferenceRecordingTable.failureReason],
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toRecordingUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}

/**
 * V1.0 Wave 2 "Aufzeichnung" -- the ONE non-RPC, server-internal bridge from Wave 1's
 * [ConferenceService.endRoom] into this wave's recording lifecycle. A plain object (not an RPC
 * service, not a new [IConferenceRecordingService] method) because ending a room and stopping its
 * own active recording is a single fachlich action from the moderator's point of view, not two
 * separate calls a client should have to sequence itself. [IConferenceService] itself is otherwise
 * completely untouched by this wave -- see [IConferenceRecordingService] KDoc "A separate service".
 *
 * **Belt-and-braces, not the only safety net.** A later wave's `RecordingPoller` independently ALSO
 * auto-stops a recording whose room's `ended_at` has become non-null -- because Wave 1's OWN lazy
 * reconciliation (`ConferenceService.listActiveRooms`'s per-room grace-period close) can end a room
 * WITHOUT `endRoom` ever running at all. This coordinator therefore closes the common, synchronous
 * case fast; its own absence or failure is never a correctness gap for the recording state machine,
 * only a latency one (the poller catches it on its next tick either way).
 *
 * **Must be called from inside the caller's already-open `transaction {}`**, and as the LAST
 * lock-taking operation of that transaction -- mirrors
 * [network.lapis.cloud.server.audit.AuditLogRecorder]'s own "transaction-free by contract,
 * deadlock-avoidance" idiom exactly (this function's own [AuditLogRecorder.record] calls are
 * themselves subject to that same contract, one per stopped recording).
 */
object ConferenceRecordingCoordinator {
    /**
     * Transitions every currently-[ConferenceRecordingStatus.RECORDING] row for [roomId] to
     * [ConferenceRecordingStatus.STOPPING] -- the SAME transition [ConferenceRecordingService.stopRecording]
     * performs -- and records one [AuditAction.UPDATE] audit entry per stopped recording, attributed
     * to [actorMemberId]/[actorRole] (the member who called `endRoom`), exactly as if they had
     * called `stopRecording` themselves for each one. No-op (zero queries beyond the initial lookup)
     * if [roomId] has no `RECORDING`-status row.
     */
    fun stopActiveRecordingsForRoom(
        roomId: Uuid,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ) {
        val now = DbClock.nowLocalDateTime()
        val activeIds =
            ConferenceRecordingTable
                .selectAll()
                .where {
                    (ConferenceRecordingTable.roomId eq roomId) and
                        (ConferenceRecordingTable.status eq ConferenceRecordingStatus.RECORDING)
                }.map { it[ConferenceRecordingTable.id] }
        if (activeIds.isEmpty()) return
        ConferenceRecordingTable.update({ ConferenceRecordingTable.id inList activeIds }) {
            it[stoppedAt] = now
            it[status] = ConferenceRecordingStatus.STOPPING
        }
        activeIds.forEach { recordingId ->
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.CONFERENCE_RECORDING,
                entityId = recordingId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
        }
    }
}
