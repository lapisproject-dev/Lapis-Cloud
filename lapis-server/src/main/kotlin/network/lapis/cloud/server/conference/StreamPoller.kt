package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val NON_TERMINAL_STREAM_STATUSES =
    listOf(
        ConferenceStreamStatus.STARTING,
        ConferenceStreamStatus.LIVE,
        ConferenceStreamStatus.PAUSED,
        ConferenceStreamStatus.STOPPING,
    )

private val NON_TERMINAL_TARGET_STATUSES = listOf(ConferenceStreamTargetStatus.PENDING, ConferenceStreamTargetStatus.ACTIVE)

private val TERMINAL_EGRESS_STATUSES = setOf("EGRESS_COMPLETE", "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED")

/** Fixed, sanitized German vocabulary -- see class KDoc "Per-target failure mapping". Raw LiveKit `stream_results[].error`/`EgressInfo.error` text NEVER reaches a DTO, only these four fixed strings, mirroring [network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto.failureReason]'s own KDoc requirement. */
private const val FAILURE_CONNECTION = "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
private const val FAILURE_REJECTED = "Das Streaming-Ziel hat die Verbindung abgelehnt (Stream-Schlüssel prüfen)."
private const val FAILURE_TIMEOUT = "Der Stream wurde vom Server beendet (Zeitüberschreitung)."
private const val FAILURE_GENERIC = "Der Stream konnte nicht gestartet werden."

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- the single application-scoped
 * poller that drives `STARTING -> LIVE -> FAILED`/auto-`ENDED`, mirroring
 * [RecordingPoller]'s own "ONE coroutine, exception-safe `tick()`, no in-memory state" shape
 * EXACTLY (see that class's own KDoc "Mechanism"/"Restart reconciliation" for the full reasoning,
 * not repeated here). Constructed once in `Application.module`, started via [start] only when
 * [ConferenceStreamingConfig.enabled] holds. A SEPARATE class from [RecordingPoller] -- independent
 * enable gate, independent interval, a genuinely different state machine (six states vs. four, no
 * `ffmpeg`/composition phase at all), and no reason to widen a live-verified Wave 2 class -- see
 * [network.lapis.cloud.shared.rpc.IConferenceStreamingService] KDoc "A third independent
 * availability gate"/"Different collaborators".
 *
 * ## Per-tick per-stream branching
 *
 * [tick] re-queries every [ConferenceStreamTable] row in a [NON_TERMINAL_STREAM_STATUSES] status
 * fresh every time (no cached state, same crash-recovery property [RecordingPoller.tick]
 * establishes), exception-safe at TWO levels (the whole tick, and each stream individually). For
 * each row: if the room no longer exists, or the room has ENDED (and this row is not already
 * [ConferenceStreamStatus.STOPPING]), the stream is auto-stopped/finalized regardless of its own
 * status -- belt-and-braces on top of [ConferenceStreamingService.stopStream]'s own explicit call,
 * same "Wave 1's OWN lazy room reconciliation can close a room without a synchronous stop ever
 * running" reasoning [ConferenceRecordingCoordinator]'s KDoc gives. Otherwise:
 *
 * - [ConferenceStreamStatus.STARTING]: **orphan reconciliation.** A crash between
 *   [ConferenceStreamingService.startStream]'s two transactions leaves a row `STARTING` with NO
 *   `livekit_egress_id` -- once [ConferenceStreamingConfig.startupTimeoutSeconds] has elapsed since
 *   `started_at`, this cross-checks `ListEgress` for the room, matching each returned
 *   `EgressInfo.stream_results[].url` against this stream's OWN, already-persisted
 *   `conference_stream_target.url_fingerprint` values (computed and stored in transaction 1, so they
 *   survive the crash -- see [StreamUrlFingerprint] KDoc) -- an egress with ANY matching URL is
 *   ADOPTED (the row becomes `LIVE` with that `egress_id`) rather than leaked; no match after the
 *   timeout -- or a row with NO target rows at all, which can never be matched --
 *   [FAILURE_TIMEOUT]/`FAILED` immediately (the latter is the "fail fast when nothing can change the
 *   outcome" discipline [RecordingPoller.handleStopping]'s own KDoc documents for its own zero-tracks
 *   case, applied here).
 * - [ConferenceStreamStatus.LIVE]: [ConferenceStreamingConfig.maxDurationMinutes] elapsed since
 *   `started_at` -> auto-stop (best-effort `StopEgress`, then `ENDED`, same as a moderator-initiated
 *   [ConferenceStreamingService.stopStream]). Otherwise `ListEgress`, matched by `egress_id`; if the
 *   egress has vanished from the list entirely, `FAILED` immediately. Otherwise: every
 *   `conference_stream_target` row is refreshed from `stream_results` (matched via
 *   `url_fingerprint`, NEVER exact-URL or array position -- see [StreamUrlFingerprint] KDoc) --
 *   status/`retries`/timestamps, and a sanitized [FAILURE_CONNECTION]/[FAILURE_REJECTED]/
 *   [FAILURE_GENERIC] `failureReason` on a `FAILED` target. If the OVERALL `EgressInfo.status` is one
 *   of [TERMINAL_EGRESS_STATUSES] (the egress ended -- on its own account, not via THIS system's own
 *   `StopEgress` call, since a moderator-initiated stop already transitioned the row away from
 *   `LIVE` before reaching here), this is treated as an uncommanded failure: the STREAM row itself
 *   goes `FAILED` too (mirrors [ConferenceStreamStatus] KDoc "StreamPoller drives LIVE -> FAILED").
 * - [ConferenceStreamStatus.PAUSED]: no active egress to poll (LiveKit has no pause primitive,
 *   verified live -- see [ConferenceStreamingService] KDoc) -- only [maxDurationMinutes] (counted
 *   from the ORIGINAL `started_at`, so a paused stream cannot indefinitely hold the room's
 *   one-active-stream-per-room slot) is enforced here, auto-finalizing straight to `ENDED` with no
 *   `StopEgress` call needed (nothing is running).
 * - [ConferenceStreamStatus.STOPPING]: **completes an interrupted [ConferenceStreamingService.stopStream]**
 *   (a crash between that method's two transactions) -- best-effort `StopEgress` if an `egress_id` is
 *   still on the row, then `ENDED`. No timeout needed (unlike [RecordingPoller]'s own `STOPPING`,
 *   which waits for track files to finish composing) -- there is nothing further this row is ever
 *   waiting FOR, so completing it immediately on the very next tick is correct, not merely
 *   convenient.
 *
 * ## Per-target failure mapping -- a security boundary, not a UX nicety
 *
 * [sanitizeLiveKitError] is the ONE place raw LiveKit `error` text (from either
 * `EgressInfo.error` or a `StreamInfo.error`) is ever read in this class -- a REAL captured sample
 * (`network.lapis.cloud.server.conference.HttpLiveKitEgressClient` KDoc) echoes the destination
 * HOSTNAME back verbatim (`"Failed to connect: Error resolving “nonexistent-bad-host-xyz”:
 * Name or service not known"`). The four fixed German strings above are the ONLY values that ever
 * reach [network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto.failureReason]/
 * [network.lapis.cloud.shared.domain.ConferenceStreamDto.failureReason] from this class -- the raw
 * text itself is used ONLY for substring matching, never stored, never logged verbatim (log lines
 * below name only the stream/egress id, never the LiveKit error string).
 *
 * ## No webhooks
 *
 * Consistent with Wave 1/2's stated "no webhooks, lazy reconciliation via polling" posture -- no
 * scheduler/cron infrastructure exists in this codebase (see CLAUDE.md), same reasoning
 * [RecordingPoller]/[LoginRateLimiter]'s own KDoc give for their own in-process designs.
 */
class StreamPoller(
    private val liveKitEgressClient: LiveKitEgressClient,
    private val streamingConfig: ConferenceStreamingConfig,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. See class KDoc. */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(streamingConfig.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. Any in-flight [tick] finishes; nothing is force-killed. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass over every non-terminal stream. Exception-safe at two levels -- see class KDoc
     * -- so a caller (production loop or a test) never needs its own try/catch.
     */
    suspend fun tick() {
        try {
            val rows =
                transaction {
                    ConferenceStreamTable
                        .selectAll()
                        .where { ConferenceStreamTable.status inList NON_TERMINAL_STREAM_STATUSES }
                        .map { it.toStreamRow() }
                }
            for (row in rows) {
                try {
                    handleStream(row)
                } catch (e: Throwable) {
                    logger.warn(e) { "StreamPoller: tick failed for stream ${row.id} (status ${row.status})" }
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "StreamPoller: tick failed" }
        }
    }

    private suspend fun handleStream(row: StreamRow) {
        val now = clock()
        val roomRow =
            transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row.roomId }.singleOrNull() }
        if (roomRow == null) {
            logger.warn { "StreamPoller: room ${row.roomId} for stream ${row.id} no longer exists -- finalizing" }
            finalizeEnded(row.id, now)
            return
        }
        val roomName = roomRow[ConferenceRoomTable.livekitRoomName]

        // Belt-and-braces auto-stop on top of ConferenceStreamingService.stopStream's own explicit
        // call -- see class KDoc. STOPPING is excluded here because handleStopping below already
        // completes that row regardless of room state.
        if (roomRow[ConferenceRoomTable.endedAt] != null && row.status != ConferenceStreamStatus.STOPPING) {
            if (row.status == ConferenceStreamStatus.LIVE) {
                autoStop(row, roomName, now)
            } else {
                finalizeEnded(row.id, now)
            }
            return
        }

        when (row.status) {
            ConferenceStreamStatus.STARTING -> handleStarting(row, roomName, now)
            ConferenceStreamStatus.LIVE -> handleLive(row, roomName, now)
            ConferenceStreamStatus.PAUSED -> handlePaused(row, now)
            ConferenceStreamStatus.STOPPING -> handleStopping(row, roomName, now)
            else -> Unit
        }
    }

    // ── STARTING -- orphan reconciliation ───────────────────────────────

    private suspend fun handleStarting(
        row: StreamRow,
        roomName: String,
        now: LocalDateTime,
    ) {
        if (row.livekitEgressId != null) return
        if (elapsed(row.startedAt, now) < streamingConfig.startupTimeoutSeconds.seconds) return

        val fingerprints =
            transaction {
                ConferenceStreamTargetTable
                    .selectAll()
                    .where { ConferenceStreamTargetTable.streamId eq row.id }
                    .map { it[ConferenceStreamTargetTable.urlFingerprint] }
                    .toSet()
            }
        if (fingerprints.isEmpty()) {
            // Fast-fail -- see class KDoc "STARTING": nothing to ever match against, waiting out
            // the rest of a hypothetical grace period would just be an unexplained hang.
            markFailed(row.id, FAILURE_TIMEOUT)
            return
        }

        val egresses =
            try {
                liveKitEgressClient.listEgress(roomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: ListEgress failed while reconciling orphan STARTING stream ${row.id}: ${e.message}" }
                return
            }
        val adopted = egresses.firstOrNull { info -> info.streamResults.any { it.url in fingerprints } }
        if (adopted != null) {
            logger.info { "StreamPoller: adopted orphan egress ${adopted.egressId} for stream ${row.id}" }
            transaction {
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq row.id }) {
                    it[livekitEgressId] = adopted.egressId
                    it[status] = ConferenceStreamStatus.LIVE
                }
            }
        } else {
            markFailed(row.id, FAILURE_TIMEOUT)
        }
    }

    // ── LIVE ─────────────────────────────────────────────────────────────

    private suspend fun handleLive(
        row: StreamRow,
        roomName: String,
        now: LocalDateTime,
    ) {
        if (elapsed(row.startedAt, now) >= streamingConfig.maxDurationMinutes.minutes) {
            autoStop(row, roomName, now)
            return
        }
        val egressId = row.livekitEgressId ?: return

        val egresses =
            try {
                liveKitEgressClient.listEgress(roomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: ListEgress failed for stream ${row.id}: ${e.message}" }
                return
            }
        val info = egresses.firstOrNull { it.egressId == egressId }
        if (info == null) {
            logger.warn { "StreamPoller: egress $egressId for stream ${row.id} no longer reported by ListEgress -- FAILED" }
            markFailed(row.id, FAILURE_GENERIC)
            return
        }

        val targetRows =
            transaction {
                ConferenceStreamTargetTable
                    .selectAll()
                    .where { ConferenceStreamTargetTable.streamId eq row.id }
                    .map { it.toTargetRow() }
            }
        val byFingerprint = info.streamResults.associateBy { it.url }
        transaction {
            for (t in targetRows) {
                val si = byFingerprint[t.urlFingerprint] ?: continue
                val mappedStatus = mapStreamInfoStatus(si.status)
                ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq t.id }) {
                    it[status] = mappedStatus
                    it[retries] = si.retries
                    it[failureReason] = if (mappedStatus == ConferenceStreamTargetStatus.FAILED) sanitizeLiveKitError(si.error) else null
                    si.startedAtEpochNanos
                        .toLongOrNull()
                        ?.takeIf { it != 0L }
                        ?.let { nanos -> it[startedAtEpochNanos] = nanos }
                    si.endedAtEpochNanos
                        .toLongOrNull()
                        ?.takeIf { it != 0L }
                        ?.let { nanos -> it[endedAtEpochNanos] = nanos }
                }
            }
        }

        if (info.status in TERMINAL_EGRESS_STATUSES) {
            // The egress ended on its OWN account -- a moderator-initiated stop already moved this
            // row away from LIVE before it could ever reach here (see ConferenceStreamStatus KDoc
            // "StreamPoller drives LIVE -> FAILED").
            markFailed(row.id, sanitizeLiveKitError(info.error))
        }
    }

    // ── PAUSED ───────────────────────────────────────────────────────────

    private fun handlePaused(
        row: StreamRow,
        now: LocalDateTime,
    ) {
        if (elapsed(row.startedAt, now) >= streamingConfig.maxDurationMinutes.minutes) {
            // No active egress while PAUSED (LiveKit has no pause primitive -- StopEgress already
            // ran when this stream was paused) -- nothing to stop, finalize directly.
            finalizeEnded(row.id, now)
            logger.info { "StreamPoller: auto-stopped PAUSED stream ${row.id} (max duration elapsed)" }
        }
    }

    // ── STOPPING -- completes an interrupted ConferenceStreamingService.stopStream ─────────────

    private suspend fun handleStopping(
        row: StreamRow,
        roomName: String,
        now: LocalDateTime,
    ) {
        if (row.livekitEgressId != null) {
            try {
                liveKitEgressClient.stopEgress(roomName, row.livekitEgressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: StopEgress failed while completing STOPPING stream ${row.id}: ${e.message}" }
            }
        }
        finalizeEnded(row.id, now)
    }

    // ── Shared transitions ───────────────────────────────────────────────

    private suspend fun autoStop(
        row: StreamRow,
        roomName: String,
        now: LocalDateTime,
    ) {
        val egressId = row.livekitEgressId
        if (egressId != null) {
            try {
                liveKitEgressClient.stopEgress(roomName, egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: StopEgress failed while auto-stopping stream ${row.id}: ${e.message}" }
            }
        }
        finalizeEnded(row.id, now)
        logger.info { "StreamPoller: auto-stopped stream ${row.id}" }
    }

    private fun finalizeEnded(
        streamId: Uuid,
        now: LocalDateTime,
    ) {
        transaction {
            val current =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull() ?: return@transaction
            if (current[ConferenceStreamTable.status] !in NON_TERMINAL_STREAM_STATUSES) return@transaction
            ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                it[status] = ConferenceStreamStatus.ENDED
                it[endedAt] = now
            }
            ConferenceStreamTargetTable.update({
                (ConferenceStreamTargetTable.streamId eq streamId) and
                    (ConferenceStreamTargetTable.status inList NON_TERMINAL_TARGET_STATUSES)
            }) {
                it[status] = ConferenceStreamTargetStatus.FINISHED
            }
            // System-initiated (auto-stop), no acting member -- AuditLogRecorder.record accepts a
            // null actor for exactly this case, same as RecordingPoller.transitionToStopping. Must
            // be the LAST lock-taking operation -- see that object's KDoc "deadlock-avoidance
            // contract".
            AuditLogRecorder.record(
                actorMemberId = null,
                actorRole = null,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
        }
    }

    private fun markFailed(
        streamId: Uuid,
        reason: String,
    ) {
        transaction {
            val current =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull() ?: return@transaction
            if (current[ConferenceStreamTable.status] !in NON_TERMINAL_STREAM_STATUSES) return@transaction
            ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                it[status] = ConferenceStreamStatus.FAILED
                it[failureReason] = reason
            }
            ConferenceStreamTargetTable.update({
                (ConferenceStreamTargetTable.streamId eq streamId) and
                    (ConferenceStreamTargetTable.status inList NON_TERMINAL_TARGET_STATUSES)
            }) {
                it[status] = ConferenceStreamTargetStatus.FAILED
                it[failureReason] = reason
            }
            AuditLogRecorder.record(
                actorMemberId = null,
                actorRole = null,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamId,
                action = AuditAction.UPDATE,
                occurredAt = DbClock.nowLocalDateTime(),
            )
        }
        logger.warn { "StreamPoller: stream $streamId marked FAILED: $reason" }
    }

    private fun elapsed(
        from: LocalDateTime,
        to: LocalDateTime,
    ) = to.toInstant(TZ) - from.toInstant(TZ)
}

private val TZ = TimeZone.currentSystemDefault()

/** Plain data snapshot of a `conference_stream` row -- never held across a suspension point as a live [ResultRow], same discipline [RecordingRow] establishes in `RecordingPoller`. */
private data class StreamRow(
    val id: Uuid,
    val roomId: Uuid,
    val status: ConferenceStreamStatus,
    val layout: ConferenceStreamLayout,
    val startedAt: LocalDateTime,
    val livekitEgressId: String?,
)

/** Plain data snapshot of a `conference_stream_target` row -- same "never a live ResultRow across suspension" reasoning. */
private data class TargetRow(
    val id: Uuid,
    val urlFingerprint: String,
)

private fun ResultRow.toStreamRow() =
    StreamRow(
        id = this[ConferenceStreamTable.id],
        roomId = this[ConferenceStreamTable.roomId],
        status = this[ConferenceStreamTable.status],
        layout = this[ConferenceStreamTable.layout],
        startedAt = this[ConferenceStreamTable.startedAt],
        livekitEgressId = this[ConferenceStreamTable.livekitEgressId],
    )

private fun ResultRow.toTargetRow() =
    TargetRow(
        id = this[ConferenceStreamTargetTable.id],
        urlFingerprint = this[ConferenceStreamTargetTable.urlFingerprint],
    )

/** Maps `livekit.StreamInfo.Status` (`ACTIVE`/`FINISHED`/`FAILED`) onto this wave's own [ConferenceStreamTargetStatus]. An unrecognized value stays [ConferenceStreamTargetStatus.PENDING] (non-terminal, polling continues). */
private fun mapStreamInfoStatus(status: String): ConferenceStreamTargetStatus =
    when (status) {
        "ACTIVE" -> ConferenceStreamTargetStatus.ACTIVE
        "FINISHED" -> ConferenceStreamTargetStatus.FINISHED
        "FAILED" -> ConferenceStreamTargetStatus.FAILED
        else -> ConferenceStreamTargetStatus.PENDING
    }

/** See class KDoc "Per-target failure mapping". */
private fun sanitizeLiveKitError(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("resolv") || lower.contains("connect") -> FAILURE_CONNECTION
        lower.contains("refused") || lower.contains("unauthoriz") || lower.contains("403") || lower.contains("401") -> FAILURE_REJECTED
        lower.contains("timeout") || lower.contains("timed out") -> FAILURE_TIMEOUT
        else -> FAILURE_GENERIC
    }
}
