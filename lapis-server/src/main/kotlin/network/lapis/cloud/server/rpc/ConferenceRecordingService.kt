package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingAccess
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingPageDto
import network.lapis.cloud.shared.domain.ConferenceRecordingSnapshot
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Statuses that count as "already active" for the one-active-recording-per-room invariant -- see [ConferenceRecordingService.startRecording] KDoc. */
private val ACTIVE_RECORDING_STATUSES = listOf(ConferenceRecordingStatus.RECORDING, ConferenceRecordingStatus.STOPPING)

/**
 * The only two statuses [ConferenceRecordingService.deleteRecording] accepts -- every other status
 * means `RecordingPoller` is still actively driving that row (holding LiveKit egress handles, or an
 * ffmpeg subprocess writing into the very raw directory the deletion would remove). Deliberately NOT
 * expressed as `!in ACTIVE_RECORDING_STATUSES`: that set omits `PROCESSING`, which is exactly the
 * state where deleting under the poller's feet would be most destructive.
 */
private val TERMINAL_RECORDING_STATUSES = listOf(ConferenceRecordingStatus.READY, ConferenceRecordingStatus.FAILED)

private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes
private const val DEFAULT_STOP_RATE_MAX = 30

/**
 * Deliberately tighter than [DEFAULT_STOP_RATE_MAX]: stopping is a routine, sometimes-retried action
 * a moderator performs once per meeting, while deleting is destructive and irreversible for the
 * `conference_recording` row -- a legitimate caller has no reason to issue more than a handful per
 * minute, and a narrower budget bounds the blast radius of a hijacked session or a runaway client
 * loop.
 */
private const val DEFAULT_DELETE_RATE_MAX = 10
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
 * [stopRecordingRateLimiter]/[deleteRecordingRateLimiter]/[readRateLimiter] are plain per-member
 * REQUEST-rate limiters reusing [FederationInboxRateLimiter] -- same "many legitimate calls must not
 * each look like a failure" reasoning [ConferenceService]'s own
 * `joinRoomRateLimiter`/`leaveRoomRateLimiter`/`listRateLimiter` KDoc gives; [stopRecording] and
 * [deleteRecording] each get their OWN independent budget (a moderator who has just exhausted the
 * stop budget must still be able to delete, and vice versa -- one shared budget would let a routine
 * action lock out a destructive-but-legitimate one and hide which of the two is actually being
 * abused), [getActiveRecording]/[listRecordings] share [readRateLimiter] since neither is
 * individually more sensitive than the other (both are plain DB reads, unlike [ConferenceService]'s
 * own read methods which each fan out into an outbound LiveKit admin call).
 */
class ConferenceRecordingService(
    private val call: ApplicationCall,
    private val ffmpegAvailable: Boolean,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val recordingConfig: ConferenceRecordingConfig = ConferenceRecordingConfig.load(),
    private val startRecordingRateLimiter: LoginRateLimiter = LoginRateLimiter(),
    private val stopRecordingRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_STOP_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val deleteRecordingRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_DELETE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
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
            requireModeratorOrPrivileged(room = room, current = current)

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
            rowToDto(row = row, roomTitle = room[ConferenceRoomTable.title], current = current)
        }
    }

    override suspend fun stopRecording(recordingId: String): ConferenceRecordingDto {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(limiter = stopRecordingRateLimiter, memberId = current.memberId)
        // See startRecording's own "roomUuid, NOT id" comment -- same shadowing avoidance.
        val recordingUuid = recordingId.toRecordingUuid()
        return transaction {
            val row =
                ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingUuid }.singleOrNull()
                    ?: throw NotFoundException("Conference recording $recordingUuid not found")
            val room =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceRecordingTable.roomId] }.singleOrNull()
                    ?: throw NotFoundException("Conference room for recording $recordingUuid not found")
            requireModeratorOrPrivileged(room = room, current = current)

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
            rowToDto(row = fresh, roomTitle = room[ConferenceRoomTable.title], current = current)
        }
    }

    /**
     * See [IConferenceRecordingService.deleteRecording] for the authorization rule, the
     * terminal-status-only restriction and what is (and is not) actually removed. Four
     * implementation facts worth stating here rather than leaving to be re-derived:
     *
     * 1. **Moderator standing alone does NOT authorize the `document` soft-delete.**
     *    [requireModeratorOrPrivileged] is satisfied by the ROOM's creator, who may be a plain
     *    MEMBER -- while the linked `document` carries its own [DocumentAccessLevel], and any
     *    privileged participant may have started the recording at a level that creator can neither
     *    read nor delete ([network.lapis.cloud.shared.rpc.IDocumentService.deleteDocument] is
     *    BOARD/ADMIN-only). Deleting such a recording would otherwise let a MEMBER soft-delete an
     *    `ADMIN_ONLY` document by proxy. A recording WITH a linked document therefore additionally
     *    requires [canAccessDocumentAtLevel] for its own access level, or [CurrentMember.isPrivileged]
     *    (`deleteDocument`'s own rule, mirrored exactly rather than re-invented). A failing check
     *    rejects the WHOLE call with [ForbiddenException] -- silently skipping only the document
     *    flip would hard-delete the recording row while leaving its document readable, i.e. a
     *    half-deleted state neither the caller nor a later auditor asked for. [ForbiddenException]
     *    and not [ConflictException] because this is a permission failure, matching how this same
     *    method already distinguishes the two (Conflict = wrong STATE, see the terminal-status check
     *    below; Forbidden = wrong PERMISSION, see [requireModeratorOrPrivileged]).
     * 2. **The `document` soft-delete is inlined, not delegated to [network.lapis.cloud.shared.rpc.IDocumentService.deleteDocument].**
     *    That method opens its OWN transaction -- calling it here would split one deletion across
     *    two transactions, so a failure after the document flip would leave a soft-deleted document
     *    whose recording row still exists.
     * 3. **Child rows before parent row.** `conference_recording_track.recording_id` is a real FK
     *    with NO cascade (`V1__baseline.sql`, `fk_conference_recording_track_recording_id`), so
     *    deleting the parent first would be rejected at the DB level. Child-then-parent is mandatory,
     *    not stylistic.
     * 4. **The raw-directory removal happens OUTSIDE the transaction, after it has COMMITTED.** It
     *    is the one step no transaction can roll back, so it must not run while the surrounding
     *    transaction can still abort -- a DB-level failure at COMMIT time (Exposed's own
     *    retry-on-`SQLException` behaviour, or a genuine serialization failure) would otherwise
     *    rewind document soft-delete, track deletes, recording delete and audit entry alike while
     *    the footage was already irreversibly gone, leaving a surviving `READY`/`FAILED` row
     *    pointing at nothing. The transaction block therefore does DB work ONLY and returns the
     *    captured `raw_dir`; [deleteRawDirectory] runs after it, as best-effort cleanup that logs
     *    rather than throws (a leftover directory is a janitorial problem, a failed RPC would be a
     *    functional one). Ordering DB-first also keeps [AuditLogRecorder.record] the last
     *    lock-taking operation of the transaction, per that object's "deadlock-avoidance contract".
     */
    override suspend fun deleteRecording(recordingId: String): Boolean {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(limiter = deleteRecordingRateLimiter, memberId = current.memberId)
        // See startRecording's own "roomUuid, NOT id" comment -- same shadowing avoidance.
        val recordingUuid = recordingId.toRecordingUuid()
        val hostRawRoot = File(recordingConfig.outputHostDir)
        val rawDir =
            transaction {
                val row =
                    ConferenceRecordingTable
                        .selectAll()
                        .where { ConferenceRecordingTable.id eq recordingUuid }
                        // Same `.forUpdate()` row lock startRecording takes on the room row, for the
                        // same bug class: without it two concurrent deleteRecording calls for the
                        // same id both pass the terminal-status check and both write an audit entry,
                        // even though only one of them actually deletes anything.
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("Conference recording $recordingUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceRecordingTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for recording $recordingUuid not found")
                requireModeratorOrPrivileged(room = room, current = current)

                val status = row[ConferenceRecordingTable.status]
                if (status !in TERMINAL_RECORDING_STATUSES) {
                    throw ConflictException(
                        "Conference recording $recordingUuid is still $status -- only a finished (READY) or failed (FAILED) " +
                            "recording can be deleted; stop it first and wait until it settles",
                    )
                }

                val accessLevel = row[ConferenceRecordingTable.accessLevel]
                val documentId = row[ConferenceRecordingTable.documentId]
                // See this method's own KDoc fact 1 -- moderator standing does not by itself
                // authorize touching a document the caller may not even read.
                if (documentId != null && !current.canAccessDocumentAtLevel(accessLevel) && !current.isPrivileged) {
                    throw ForbiddenException(
                        "Not authorized to delete the archived document of recording $recordingUuid",
                    )
                }

                // Soft-delete ONLY -- the composed file's blob and every document_version row stay
                // exactly where they are, matching how a Document behaves everywhere else in this app
                // (DocumentService.deleteDocument) and the same conservative instinct RecordingPoller's
                // own "raw files are ALWAYS retained on failure" branch encodes. The media route
                // already honours is_deleted, so the recording stops being playable the moment this
                // commits.
                if (documentId != null) {
                    DocumentTable.update({ DocumentTable.id eq documentId }) {
                        it[isDeleted] = true
                    }
                }

                val snapshot = deletedRecordingSnapshot(row = row, roomTitle = room[ConferenceRoomTable.title])
                ConferenceRecordingTrackTable.deleteWhere { ConferenceRecordingTrackTable.recordingId eq recordingUuid }
                ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id eq recordingUuid }

                val now = nowLocalDateTime()
                // AuditAction has no DELETE literal (see network.lapis.cloud.shared.domain.AuditAction
                // KDoc) -- recorded as UPDATE with a full ConferenceRecordingSnapshot as the "before"
                // value, the pre-serialized-JSON shape AuditLogRecorder.record's own KDoc asks for
                // and SepaService already establishes. A short human string would have been enough
                // for an UPDATE (the row survives to be re-read); this row is HARD-deleted, so this
                // entry is the only surviving record of what existed.
                // Must be the LAST lock-taking operation of this transaction -- see AuditLogRecorder
                // KDoc "deadlock-avoidance contract".
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.CONFERENCE_RECORDING,
                    entityId = recordingUuid,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(ConferenceRecordingSnapshot.serializer(), snapshot),
                    occurredAt = now,
                )

                row[ConferenceRecordingTable.rawDir]
            }
        // Best-effort, post-COMMIT filesystem cleanup -- see this method's own KDoc fact 4 for why
        // this must NOT run inside the transaction block above.
        deleteRawDirectory(hostRawRoot = hostRawRoot, rawDir = rawDir, recordingId = recordingUuid)
        return true
    }

    override suspend fun getActiveRecording(roomId: String): List<ConferenceRecordingDto> {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toRecordingUuid()
        return transaction {
            val room =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                    ?: throw NotFoundException("Conference room $roomUuid not found")
            // Wave 5 "Föderations-Gastbeitritt", design review D13 -- widened from
            // requireActiveMembership to the shared conference-domain gate so a federated GUEST
            // who is actually in the room (allowFederationGuests + has joined) can see the
            // recording badge too -- "everyone in the room has a legal right to know" applies to a
            // guest exactly as much as to an ACTIVE member. See requireRoomEntryAuthorization KDoc.
            val status = requireRoomEntryAuthorization(roomRow = room, current = current)
            requireGuestHasJoinedRoom(roomId = roomUuid, current = current, status = status)
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
            listOf(rowToDto(row = row, roomTitle = room[ConferenceRoomTable.title], current = current))
        }
    }

    /**
     * See [IConferenceRecordingService.listRecordings] for the contract. The one thing that must not
     * be "simplified" back: [ConferenceRecordingAccess.mayAccess] runs in the SQL `WHERE` clause
     * ([accessPredicate]), NOT as a Kotlin `.filter {}` after `.limit()`. This used to be a
     * post-`limit` Kotlin filter, which was harmless only while the method returned one fixed
     * 200-row slab; with real paging it would be a correctness bug in two ways at once -- a
     * filtered-out row would consume a page slot (pages randomly shorter than `limit`, with no way
     * for the client to tell "short page" from "last page"), and `totalCount` would count rows the
     * caller can never reach.
     */
    override suspend fun listRecordings(query: ConferenceRecordingListQuery): ConferenceRecordingPageDto {
        val current = resolveCurrentMember(call)
        requireRecordingEnabled()
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        val parsedRoomId = query.roomId?.toRecordingUuid()
        // Never trust client input -- same clamping shape MemberService.listMembersForAdministration
        // already applies to its own MemberAdminQuery.
        val limit = query.limit.coerceIn(1, ConferenceRecordingListQuery.MAX_LIMIT)
        val offset = query.offset.coerceAtLeast(0)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val predicate = listPredicate(current = current, roomId = parsedRoomId)
            val roomTitleById = mutableMapOf<Uuid, String>()
            val rows =
                ConferenceRecordingTable
                    .selectAll()
                    .where { predicate }
                    // Deterministic pagination requires a stable, unique tie-breaker -- started_at
                    // alone is not unique (two rooms can start recording in the same clock tick),
                    // so a row could otherwise be skipped or duplicated across a page boundary.
                    // Same two-column discipline MemberService's own roster query documents.
                    .orderBy(ConferenceRecordingTable.startedAt to SortOrder.DESC, ConferenceRecordingTable.id to SortOrder.ASC)
                    .limit(limit)
                    .offset(offset.toLong())
                    .map { row ->
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
                        rowToDto(row = row, roomTitle = title, current = current)
                    }
            val totalCount =
                ConferenceRecordingTable
                    .selectAll()
                    .where { predicate }
                    .count()
                    .toInt()
            ConferenceRecordingPageDto(rows = rows, totalCount = totalCount, limit = limit, offset = offset)
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

    /**
     * The SQL translation of [ConferenceRecordingAccess.mayAccess], combined with
     * [listRecordings]'s optional room filter -- see that method's own KDoc for why this must be a
     * `WHERE` clause rather than a Kotlin filter.
     *
     * The translation is faithful, not an approximation, because `mayAccess`'s first half
     * ([network.lapis.cloud.server.security.canAccessDocumentAtLevel]) depends only on the CALLER
     * ([CurrentMember.role]/[CurrentMember.status]) and the row's `access_level` -- never on
     * anything else about the row. The set of levels this caller may read is therefore fully
     * computable up front, exactly the same `DocumentAccessLevel.entries.filter { ... }` idiom
     * [DocumentService.listDocuments] already uses for its own SQL filter, and the remaining half
     * ("the recording's own starter can always see it") is a plain column comparison.
     */
    private fun accessPredicate(current: CurrentMember): Op<Boolean> {
        val allowedLevels = DocumentAccessLevel.entries.filter { current.canAccessDocumentAtLevel(it) }
        val startedByMe = ConferenceRecordingTable.startedByMemberId eq current.memberId
        // A caller with no readable level at all (e.g. a GUEST) keeps ONLY the starter carve-out --
        // spelled out rather than relying on Exposed's own empty-`inList` degeneration, so the
        // generated SQL stays obvious at the one place it actually matters for access control.
        return if (allowedLevels.isEmpty()) {
            startedByMe
        } else {
            (ConferenceRecordingTable.accessLevel inList allowedLevels) or startedByMe
        }
    }

    private fun listPredicate(
        current: CurrentMember,
        roomId: Uuid?,
    ): Op<Boolean> {
        val access = accessPredicate(current)
        return if (roomId == null) access else (ConferenceRecordingTable.roomId eq roomId) and access
    }

    /**
     * Removes `{outputHostDir}/{raw_dir}` -- the path built exactly as
     * [network.lapis.cloud.server.conference.RecordingPoller.composeOne] builds it (`File(hostRawRoot, row.rawDir)`),
     * never re-derived differently, so the two can never disagree about which directory belongs to a
     * recording. `raw_dir` is by construction ALWAYS the recording's own UUID and never operator or
     * LiveKit input (see `28-conference-recording.kuml.kts`'s file header, and `startRecording`'s own
     * `it[rawDir] = recordingId.toString()`), so no path-traversal hardening beyond that invariant is
     * needed here -- unlike `conference_recording_track.file_name`, which does arrive from LiveKit
     * and is why [network.lapis.cloud.server.conference.RecordingRawFiles.resolveWithin] exists.
     *
     * Unlike the poller's own deletion this ignores [ConferenceRecordingConfig.keepRaw] entirely:
     * that flag (and the FAILED-branch retention it sits next to) protects against a SILENT,
     * automatic deletion the user never asked for -- see [network.lapis.cloud.server.conference.RecordingPoller]
     * KDoc "PROCESSING". This deletion is explicit and confirmed, so retaining the raw footage of a
     * recording the moderator just deleted would be the surprising behaviour, not the safe one.
     *
     * A failed deletion is logged, never thrown: in a Docker deployment the `egress` container may
     * write those files as a different UID, in which case `deleteRecursively()` simply returns
     * `false` -- and failing the whole RPC over leftover bytes would leave the moderator unable to
     * remove the recording at all. Same "check the result, log it, do not swallow it silently"
     * posture the poller's own raw-deletion branch already takes. That posture is now doubly
     * load-bearing: [deleteRecording] calls this AFTER its transaction has committed (see that
     * method's KDoc fact 4), so a throw here would report a failure for a deletion that already
     * succeeded.
     *
     * The blank-[rawDir] guard is defensive, not a live case: `File(root, "")` resolves to
     * [hostRawRoot] ITSELF, and `deleteRecursively()` on it would wipe EVERY recording's raw footage
     * on the host. The "`raw_dir` is always the recording's own UUID" invariant above still holds by
     * construction, but this method became reachable from an authenticated RPC in this wave (it used
     * to be poller-internal state only), so a future bug elsewhere must not be able to escalate into
     * erasing the shared volume. Logged and skipped rather than thrown, for the same reason every
     * other failure in this function is -- introducing a second, throwing posture in a function
     * whose whole contract is "never throw" would just move the damage.
     */
    private fun deleteRawDirectory(
        hostRawRoot: File,
        rawDir: String,
        recordingId: Uuid,
    ) {
        if (rawDir.isBlank()) {
            logger.error {
                "deleteRecording: refusing to delete raw files for recording $recordingId -- raw_dir is blank, which " +
                    "would resolve to the shared root $hostRawRoot itself"
            }
            return
        }
        val directory = File(hostRawRoot, rawDir)
        if (!directory.exists()) return
        if (!directory.deleteRecursively()) {
            logger.warn {
                "deleteRecording: failed to delete raw files for recording $recordingId at $directory -- check container UID/permissions"
            }
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
                ConferenceRecordingAccess.mayAccess(
                    current = current,
                    accessLevel = row[ConferenceRecordingTable.accessLevel],
                    startedByMemberId = startedByMemberId,
                )
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

    /**
     * The `before` payload of [deleteRecording]'s audit entry -- see that method's own KDoc for why
     * a HARD-deleted row warrants a full [ConferenceRecordingSnapshot] rather than the short human
     * string an `UPDATE` on a surviving row can get away with. **Must be called BEFORE the
     * `conference_recording_track` children are deleted**: [ConferenceRecordingSnapshot.trackCount]
     * counts them live, exactly as [rowToDto] does.
     */
    private fun deletedRecordingSnapshot(
        row: ResultRow,
        roomTitle: String,
    ): ConferenceRecordingSnapshot {
        val recordingId = row[ConferenceRecordingTable.id]
        return ConferenceRecordingSnapshot(
            recordingId = recordingId.toString(),
            roomId = row[ConferenceRecordingTable.roomId].toString(),
            roomTitle = roomTitle,
            status = row[ConferenceRecordingTable.status],
            startedAt = row[ConferenceRecordingTable.startedAt],
            startedByMemberId = row[ConferenceRecordingTable.startedByMemberId].toString(),
            accessLevel = row[ConferenceRecordingTable.accessLevel],
            documentId = row[ConferenceRecordingTable.documentId]?.toString(),
            durationSeconds = row[ConferenceRecordingTable.durationSeconds],
            fileSizeBytes = row[ConferenceRecordingTable.fileSizeBytes],
            failureReason = row[ConferenceRecordingTable.failureReason],
            trackCount =
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId eq recordingId }
                    .count()
                    .toInt(),
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
