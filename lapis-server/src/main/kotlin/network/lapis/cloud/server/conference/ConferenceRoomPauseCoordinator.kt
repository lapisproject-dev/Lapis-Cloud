package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.pausefeed.PauseFeedControl
import network.lapis.cloud.server.conference.pausefeed.PauseFeeds
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRecordingPauseTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.events.EventCaptureLiveBroadcaster
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.MODERATION_DENIED
import network.lapis.cloud.server.rpc.SecretBallotStreamLock
import network.lapis.cloud.server.rpc.quiesceStreamEgress
import network.lapis.cloud.server.rpc.restartEgressForStream
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_TOPIC
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRoomPauseDto
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.rpc.ConferenceRoomPauseLimitException
import network.lapis.cloud.shared.rpc.ConferenceRoomPauseNothingToPauseException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a member id, identity, display name or room id.
private val logger = KotlinLogging.logger {}

/** At most this many pauses per recording (a constant, not an environment variable): bounds the cut-outs and the remux effort. */
internal const val MAX_PAUSES_PER_RECORDING = 20

/** Audit `capturePause` discriminators. */
private const val AUDIT_PAUSE_ON = "on"
private const val AUDIT_PAUSE_OFF = "off"
private const val AUDIT_PAUSE_ENDED = "ended_nothing_left"

/**
 * V1.9.254 -- the database side of the room pause: derived scope, lock, and the cleanup every stop path needs. Together with
 * [ConferenceRoomPauseCoordinator] this file is the ONLY writer of `conference_room.capture_paused_at` and of the pause rows / `paused_at` /
 * `stage` of a recording on the pause path (plus `RecordingPoller.transitionToStopping`, which calls into here) -- pinned by
 * `ConferenceRoomPauseTripwireTest`. Every function must be called inside the caller's `transaction {}`.
 */
internal object RoomPauseDb {
    /** Locks the room row (the lock anchor of pause/resume). Always the FIRST lock of a transaction that touches recording or stream too. */
    fun lockRoom(roomId: Uuid) {
        ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.id eq roomId }
            .forUpdate()
            .singleOrNull()
    }

    fun isRoomPaused(roomId: Uuid): Boolean =
        ConferenceRoomTable
            .selectAll()
            .where { (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.capturePausedAt.isNotNull() }
            .limit(1)
            .any()

    fun recordingPaused(roomId: Uuid): Boolean =
        ConferenceRecordingTable
            .selectAll()
            .where {
                (ConferenceRecordingTable.roomId eq roomId) and
                    (ConferenceRecordingTable.status eq ConferenceRecordingStatus.RECORDING) and
                    ConferenceRecordingTable.pausedAt.isNotNull()
            }.limit(1)
            .any()

    fun streamPaused(roomId: Uuid): Boolean =
        ConferenceStreamTable
            .selectAll()
            .where {
                (ConferenceStreamTable.roomId eq roomId) and
                    (ConferenceStreamTable.status inList listOf(ConferenceStreamStatus.PAUSING, ConferenceStreamStatus.PAUSED)) and
                    (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.ROOM_PAUSE)
            }.limit(1)
            .any()

    /** The pause state of the room, derived (never stored beyond `capture_paused_at`). */
    fun stateOf(roomId: Uuid): ConferenceRoomPauseDto {
        val recording = recordingPaused(roomId)
        val stream = streamPaused(roomId)
        return ConferenceRoomPauseDto(roomPaused = isRoomPaused(roomId), recordingPaused = recording, streamPaused = stream)
    }

    /** Closes the open pause row of [recordingId] (if any) at [at]. */
    fun closeOpenPause(
        recordingId: Uuid,
        at: LocalDateTime,
    ) {
        ConferenceRecordingPauseTable.update({
            (ConferenceRecordingPauseTable.recordingId eq recordingId) and ConferenceRecordingPauseTable.resumedAt.isNull()
        }) {
            it[resumedAt] = at
        }
    }

    /**
     * Clears `capture_paused_at` when neither the recording nor the stream of the room is paused any more (a stop path just ended the last
     * paused thing). Returns `true` iff it cleared. With an [actor] it writes the audit entry "pause ended, nothing left" -- place the
     * call BEFORE the caller's own audit record (the audit stays the last lock-taking operation).
     */
    fun clearCaptureIfNothingPaused(
        roomId: Uuid,
        actor: CurrentMember? = null,
    ): Boolean {
        if (!isRoomPaused(roomId)) return false
        if (recordingPaused(roomId) || streamPaused(roomId)) return false
        ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[capturePausedAt] = null }
        if (actor != null) {
            AuditLogRecorder.record(
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                entityType = AuditEntityType.CONFERENCE_ROOM,
                entityId = roomId,
                action = AuditAction.UPDATE,
                after = """{"capturePause":"$AUDIT_PAUSE_ENDED"}""",
            )
        }
        return true
    }

    /** Z7: a stream paused for a secret ballot while the room is paused becomes a plain room-pause stream (conditional on its state). */
    fun convertBallotPauseToRoomPause(streamId: Uuid) {
        ConferenceStreamTable.update({
            (ConferenceStreamTable.id eq streamId) and
                (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSED) and
                (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.SECRET_BALLOT)
        }) {
            it[pauseReason] = ConferenceStreamPauseReason.ROOM_PAUSE
        }
    }
}

/**
 * V1.9.254 -- pause mode of the video conference. A moderator pauses what runs in the room (recording, live stream, or both); people keep
 * talking. **A pause stops the egresses -- it never records and cuts afterwards**, otherwise private talk would sit in the raw files and the
 * banner "recording paused" would be untrue.
 *
 *  * **Recording**: `paused_at` is set and a pause row opened; [RecordingPoller] stops the running egresses and starts none until the
 *    resume (which closes the pause row and begins the next stage). The composer cuts the pauses out of the video.
 *  * **Stream**: the live stream goes `PAUSING` with reason `ROOM_PAUSE`, the egress is stopped and confirmed (the shared
 *    [quiesceStreamEgress]) and the stream becomes `PAUSED`. On resume it is started again (a NEW egress: the platform sees an interruption,
 *    the switch is NOT seamless). A stream paused for a secret ballot or by hand is not part of the scope (Z6).
 *
 * Authorization is the one of [network.lapis.cloud.server.rpc.ConferenceModerationService] (same single refusal, no oracle). LiveKit is
 * never called inside a `transaction {}`; the rate limiter runs before any transaction.
 */
class ConferenceRoomPauseCoordinator(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val liveKitEgressClient: LiveKitEgressClient,
    private val streamingConfig: ConferenceStreamingConfig,
    private val rateLimiter: FederationInboxRateLimiter,
    /** Wakes the [RecordingPoller] so the egresses stop at once; a test seam (no-op in tests without a poller). */
    private val requestRecordingTick: () -> Unit,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
    /** V1.9.255 -- the pause feed (picture and music for the stream targets). It never touches LiveKit; a failure of it changes nothing here. */
    private val pauseFeed: PauseFeedControl = PauseFeeds.current,
) {
    private data class PausePlan(
        val state: ConferenceRoomPauseDto,
        val roomName: String,
        val changed: Boolean,
        val streamToQuiesce: Uuid?,
        val streamEgressId: String?,
    )

    private data class ResumePlan(
        val state: ConferenceRoomPauseDto,
        val roomName: String,
        val changed: Boolean,
        val streamsToRestart: List<Uuid>,
        /** Streams that stayed paused but now for a secret ballot: they get no pause feed. */
        val streamsToUnfeed: List<Uuid> = emptyList(),
    )

    suspend fun pause(
        roomId: String,
        current: CurrentMember,
    ): ConferenceRoomPauseDto {
        val id = roomId.asUuidOrDenied()
        requireWithinRate(current)
        val plan =
            transaction {
                val room = lockedAuthorizedRoom(id = id, current = current)
                // A leftover mark with nothing paused (e.g. a stream paused by hand took the last paused thing out of the room pause) is
                // cleared here (the room lock is held), so the click is a FRESH pause, not a silent no-op.
                if (room[ConferenceRoomTable.capturePausedAt] != null &&
                    !RoomPauseDb.recordingPaused(id) &&
                    !RoomPauseDb.streamPaused(id)
                ) {
                    ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[capturePausedAt] = null }
                } else if (room[ConferenceRoomTable.capturePausedAt] != null) {
                    // Z4: a double click -- idempotent, no second pause row, no second audit entry.
                    return@transaction PausePlan(
                        state = RoomPauseDb.stateOf(id),
                        roomName = room[ConferenceRoomTable.livekitRoomName],
                        changed = false,
                        streamToQuiesce = null,
                        streamEgressId = null,
                    )
                }
                val now = clock()
                var recordingPaused = false
                var streamPaused = false
                var quiesceId: Uuid? = null
                var quiesceEgress: String? = null

                val recording =
                    ConferenceRecordingTable
                        .selectAll()
                        .where {
                            (ConferenceRecordingTable.roomId eq id) and
                                (ConferenceRecordingTable.status eq ConferenceRecordingStatus.RECORDING)
                        }.forUpdate()
                        .singleOrNull()
                if (recording != null && recording[ConferenceRecordingTable.pausedAt] == null) {
                    val recordingId = recording[ConferenceRecordingTable.id]
                    val pauseCount =
                        ConferenceRecordingPauseTable.selectAll().where { ConferenceRecordingPauseTable.recordingId eq recordingId }.count()
                    if (pauseCount >= MAX_PAUSES_PER_RECORDING) throw ConferenceRoomPauseLimitException()
                    ConferenceRecordingPauseTable.insert {
                        it[ConferenceRecordingPauseTable.id] = Uuid.random()
                        it[ConferenceRecordingPauseTable.recordingId] = recordingId
                        it[seq] = (recording[ConferenceRecordingTable.stage] + 1).toShort()
                        it[pausedAt] = now
                        it[resumedAt] = null
                    }
                    ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) { it[pausedAt] = now }
                    recordingPaused = true
                }

                val stream =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            (ConferenceStreamTable.roomId eq id) and
                                (ConferenceStreamTable.status inList listOf(ConferenceStreamStatus.LIVE, ConferenceStreamStatus.STARTING))
                        }.forUpdate()
                        .singleOrNull()
                if (stream != null) {
                    val streamId = stream[ConferenceStreamTable.id]
                    val moved =
                        ConferenceStreamTable.update({
                            (ConferenceStreamTable.id eq streamId) and
                                (ConferenceStreamTable.status inList listOf(ConferenceStreamStatus.LIVE, ConferenceStreamStatus.STARTING))
                        }) {
                            it[status] = ConferenceStreamStatus.PAUSING
                            it[pauseReason] = ConferenceStreamPauseReason.ROOM_PAUSE
                        }
                    if (moved == 1) {
                        streamPaused = true
                        // A STARTING stream has no egress id yet: StreamPoller adopts the egress that appears (and keeps PAUSING).
                        quiesceEgress = stream[ConferenceStreamTable.livekitEgressId]
                        if (quiesceEgress != null) quiesceId = streamId
                    }
                }
                if (!recordingPaused && !streamPaused) throw ConferenceRoomPauseNothingToPauseException()

                ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[capturePausedAt] = now }
                // AuditLogRecorder.record must be the LAST lock-taking operation. Metadata only -- no names, no titles.
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.CONFERENCE_ROOM,
                    entityId = id,
                    action = AuditAction.UPDATE,
                    after = """{"capturePause":"$AUDIT_PAUSE_ON","recording":$recordingPaused,"stream":$streamPaused}""",
                    occurredAt = now,
                )
                PausePlan(
                    state = RoomPauseDb.stateOf(id),
                    roomName = room[ConferenceRoomTable.livekitRoomName],
                    changed = true,
                    streamToQuiesce = quiesceId,
                    streamEgressId = quiesceEgress,
                )
            }
        if (!plan.changed) return plan.state

        // OUTSIDE any transaction. The recording: the poller stops the egresses and starts none (one place, no duplicate StopEgress).
        requestRecordingTick()
        if (plan.streamToQuiesce != null) {
            try {
                quiesceStreamEgress(
                    streamId = plan.streamToQuiesce,
                    roomName = plan.roomName,
                    egressId = plan.streamEgressId,
                    liveKitEgressClient = liveKitEgressClient,
                    streamingConfig = streamingConfig,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row is PAUSING; StreamPoller.handlePausing finishes the confirmation. Never the exception text (may name a host).
                logger.warn { "room pause: the stream stop could not be confirmed now (${e::class.simpleName}) -- StreamPoller retries" }
            }
            // V1.9.255 -- the feed starts only once the stream is confirmed PAUSED (it re-reads that itself); a stream still PAUSING or a
            // failure here is picked up by StreamPoller's reconcile.
            try {
                pauseFeed.ensureRunning(plan.streamToQuiesce)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "room pause: the pause feed could not be started now (${e::class.simpleName}) -- StreamPoller retries" }
            }
        }
        notify(id = id, roomName = plan.roomName, paused = true)
        return transaction { RoomPauseDb.stateOf(id) }
    }

    suspend fun resume(
        roomId: String,
        current: CurrentMember,
    ): ConferenceRoomPauseDto {
        val id = roomId.asUuidOrDenied()
        requireWithinRate(current)
        val plan =
            transaction {
                val room = lockedAuthorizedRoom(id = id, current = current)
                if (room[ConferenceRoomTable.capturePausedAt] == null) {
                    // Z5: not paused -- idempotent, no audit.
                    return@transaction ResumePlan(
                        state = RoomPauseDb.stateOf(id),
                        roomName = room[ConferenceRoomTable.livekitRoomName],
                        changed = false,
                        streamsToRestart = emptyList(),
                    )
                }
                val now = clock()
                var recordingResumed = false
                var streamResumed = false
                val restart = ArrayList<Uuid>()
                val unfeed = ArrayList<Uuid>()

                val recording =
                    ConferenceRecordingTable
                        .selectAll()
                        .where {
                            (ConferenceRecordingTable.roomId eq id) and
                                (ConferenceRecordingTable.status eq ConferenceRecordingStatus.RECORDING) and
                                ConferenceRecordingTable.pausedAt.isNotNull()
                        }.forUpdate()
                        .singleOrNull()
                if (recording != null) {
                    val recordingId = recording[ConferenceRecordingTable.id]
                    RoomPauseDb.closeOpenPause(recordingId = recordingId, at = now)
                    ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                        it[pausedAt] = null
                        it[stage] = (recording[ConferenceRecordingTable.stage] + 1).toShort()
                    }
                    recordingResumed = true
                }

                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            (ConferenceStreamTable.roomId eq id) and
                                (
                                    ConferenceStreamTable.status inList
                                        listOf(
                                            ConferenceStreamStatus.PAUSING,
                                            ConferenceStreamStatus.PAUSED,
                                        )
                                ) and
                                (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.ROOM_PAUSE)
                        }.forUpdate()
                        .toList()
                for (stream in streams) {
                    val streamId = stream[ConferenceStreamTable.id]
                    streamResumed = true
                    if (SecretBallotStreamLock.hasOpenSecretBallot(id)) {
                        // Z9: a secret ballot is open -- the stream stays paused, now for the ballot (auto-resumes after it).
                        ConferenceStreamTable.update({
                            (ConferenceStreamTable.id eq streamId) and
                                (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.ROOM_PAUSE)
                        }) {
                            it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                        }
                        unfeed += streamId
                    } else if (stream[ConferenceStreamTable.status] == ConferenceStreamStatus.PAUSED) {
                        restart += streamId
                    }
                    // Z17: a stream still PAUSING keeps its reason; StreamPoller restarts it once PAUSED and the room is no longer paused.
                }

                ConferenceRoomTable.update({ ConferenceRoomTable.id eq id }) { it[capturePausedAt] = null }
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.CONFERENCE_ROOM,
                    entityId = id,
                    action = AuditAction.UPDATE,
                    after = """{"capturePause":"$AUDIT_PAUSE_OFF","recording":$recordingResumed,"stream":$streamResumed}""",
                    occurredAt = now,
                )
                ResumePlan(
                    state = RoomPauseDb.stateOf(id),
                    roomName = room[ConferenceRoomTable.livekitRoomName],
                    changed = true,
                    streamsToRestart = restart,
                    streamsToUnfeed = unfeed,
                )
            }
        if (!plan.changed) return plan.state

        // V1.9.255 (Z9) -- the stream stays paused for a secret ballot: the room pause is over, so the feed ends (outside the transaction).
        for (streamId in plan.streamsToUnfeed) pauseFeed.stop(streamId)
        for (streamId in plan.streamsToRestart) {
            try {
                restartEgressForStream(
                    streamId = streamId,
                    liveKitEgressClient = liveKitEgressClient,
                    streamingConfig = streamingConfig,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    pauseFeed = pauseFeed,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The stream stays PAUSED + ROOM_PAUSE with the room no longer paused: StreamPoller.handlePaused retries the restart.
                logger.warn { "room resume: the stream could not be restarted now (${e::class.simpleName}) -- StreamPoller retries" }
            }
        }
        requestRecordingTick()
        notify(id = id, roomName = plan.roomName, paused = false)
        return transaction { RoomPauseDb.stateOf(id) }
    }

    /** Tells the room (best effort, outside any transaction); a lost packet is healed by the next read of the DTOs. */
    private suspend fun notify(
        id: Uuid,
        roomName: String,
        paused: Boolean,
    ) {
        try {
            liveKitAdminClient.sendData(
                room = roomName,
                topic = CONFERENCE_MODERATION_TOPIC,
                payload = ConferenceModerationPackets.capturePause(paused),
            )
        } catch (e: LiveKitAdminException) {
            logger.warn { "room pause: the notice could not be sent" }
        }
        EventCaptureLiveBroadcaster.notifyRoomChanged(id)
    }

    /** The room row LOCKED, after the same checks as the other moderation calls (one uniform refusal, no existence or role oracle). */
    private fun lockedAuthorizedRoom(
        id: Uuid,
        current: CurrentMember,
    ): org.jetbrains.exposed.v1.core.ResultRow {
        val row =
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?: throw ForbiddenException(MODERATION_DENIED)
        if (EncounterRoomGuard.isEncounterRoom(row) || row[ConferenceRoomTable.endedAt] != null) throw ForbiddenException(MODERATION_DENIED)
        if (!ConferenceModeratorAuthority.isModerator(row = row, current = current)) throw ForbiddenException(MODERATION_DENIED)
        return row
    }

    private fun requireWithinRate(current: CurrentMember) {
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
    }

    private fun String.asUuidOrDenied(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw ForbiddenException(MODERATION_DENIED) }
}
