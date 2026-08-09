package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** `compose_attempts` ceiling -- see class KDoc "PROCESSING". */
private const val MAX_COMPOSE_ATTEMPTS = 2

private val TERMINAL_TRACK_STATUSES =
    setOf(ConferenceRecordingTrackStatus.COMPLETE, ConferenceRecordingTrackStatus.FAILED, ConferenceRecordingTrackStatus.ABORTED)
private val VIDEO_TRACK_SOURCES = setOf(ConferenceRecordingTrackSource.CAMERA, ConferenceRecordingTrackSource.SCREEN_SHARE)
private val AUDIO_TRACK_SOURCES = setOf(ConferenceRecordingTrackSource.MICROPHONE, ConferenceRecordingTrackSource.SCREEN_SHARE_AUDIO)

/** Fixed, sanitized German vocabulary for [network.lapis.cloud.shared.domain.ConferenceRecordingDto.failureReason] -- see that field's own KDoc "a security boundary, not just a UX field". Raw ffmpeg/Twirp detail NEVER reaches these strings, only `kotlin-logging`. */
private const val FAILURE_COMPOSE_FAILED = "Die Aufzeichnung konnte nicht zusammengesetzt werden."
private const val FAILURE_COMPOSE_TIMEOUT = "Zeitüberschreitung beim Abschluss der Aufzeichnung."
private const val FAILURE_NO_TRACKS = "Es wurde keine Audio- oder Videospur aufgezeichnet."

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the single application-scoped
 * poller that drives `RECORDING -> STOPPING -> PROCESSING -> READY`/`FAILED`, see
 * [network.lapis.cloud.shared.rpc.IConferenceRecordingService] KDoc "startRecording inserts a row
 * only" and the wave plan's own "Completion handling: polling, not webhooks" for the full
 * poll-vs-webhook argument this class implements. Constructed once in `Application.module`
 * (alongside `priceOracleOrchestrator`), started via [start] only when
 * [ConferenceRecordingConfig.enabled] holds.
 *
 * ## Mechanism -- ONE coroutine, not one per recording
 *
 * [start] launches exactly ONE coroutine on this instance's own [CoroutineScope]
 * (`SupervisorJob() + Dispatchers.IO`) running `while (isActive) { tick(); delay(interval) }`.
 * Spawning a coroutine per recording is the exact "pile up indefinitely" failure this design
 * avoids. [tick] is `public` and exception-safe at TWO levels -- the whole tick, and each
 * recording individually -- so a single broken recording (a DB row in an unexpected state, a
 * transient LiveKit error) never stops every other recording's own progress that same tick, and
 * tests can call it directly with zero timing dependency.
 *
 * ## Restart reconciliation
 *
 * Rows left non-terminal (`RECORDING`/`STOPPING`/`PROCESSING`) by a crashed process are picked up
 * by the very first [tick] after boot -- [tick] always re-queries every non-terminal row fresh, it
 * never holds in-memory state about "which recordings I already know about". A `PROCESSING` row
 * whose `compose_attempts` is already at [MAX_COMPOSE_ATTEMPTS] (composition crashed mid-attempt,
 * after incrementing the counter but before finishing) is defensively marked `FAILED` immediately
 * rather than attempted a third time.
 *
 * ## RECORDING
 *
 * Auto-stops (transitions to `STOPPING`) if the room's `ended_at` is now non-null (belt-and-braces
 * on top of [ConferenceRecordingCoordinator]'s synchronous `endRoom` bridge -- Wave 1's OWN lazy
 * room reconciliation can close a room without `endRoom` ever running) or
 * [ConferenceRecordingConfig.maxDurationMinutes] has elapsed since `started_at`. Otherwise: calls
 * `ListParticipants`, and for every published, UNMUTED track with no `conference_recording_track`
 * row yet, calls `StartTrackEgress` and inserts one. Respects
 * [ConferenceRecordingConfig.maxTracks] as a hard cap on egresses per recording. A muted track (or
 * a newly-published one from a late joiner) is simply picked up on a LATER tick once the discovery
 * pass observes it -- the up-to-[ConferenceRecordingConfig.pollIntervalSeconds] latency this
 * implies is a known, deliberate characteristic (see
 * [network.lapis.cloud.shared.rpc.IConferenceRecordingService] KDoc and the wave plan's own "What
 * we give up by not using webhooks").
 *
 * ## STOPPING
 *
 * Zero [network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable] rows for this
 * recording -> `FAILED` immediately ([FAILURE_NO_TRACKS]), no LiveKit calls made at all -- `RECORDING`
 * is the only state that ever calls `StartTrackEgress`, so by the time a row reaches `STOPPING` its
 * final track set can never grow, and waiting out [ConferenceRecordingConfig.egressTimeoutMinutes]
 * for a composition that can never happen would just be an unexplained, indefinite-feeling hang for
 * the moderator (found live during this wave's own merge verification, 2026-08-09).
 *
 * Otherwise: requests `StopEgress` for every track row not yet in a terminal
 * [ConferenceRecordingTrackStatus] (idempotent to call repeatedly -- LiveKit simply reports the
 * egress's current status for an already-stopping/-stopped egress), then `ListEgress` to refresh
 * every track row's status/`file_name`/duration/size from the authoritative `EgressInfo`. All
 * track rows terminal -> `PROCESSING`. Otherwise, once `stopped_at + egressTimeoutMinutes` has
 * elapsed: composes from whatever tracks ARE [ConferenceRecordingTrackStatus.COMPLETE] if at least
 * one VIDEO track (`CAMERA`/`SCREEN_SHARE`) among them is complete, else `FAILED`
 * ([FAILURE_COMPOSE_TIMEOUT]).
 *
 * ## PROCESSING
 *
 * A single [composeSemaphore] (capacity 1) ensures at most one `ffmpeg` composition runs
 * system-wide at any moment -- a non-blocking [Semaphore.tryAcquire]; if another composition is
 * already running, this tick simply skips PROCESSING-status recordings and retries them next tick,
 * so a long composition never starves `RECORDING`/`STOPPING` handling for OTHER rooms.
 * `compose_attempts` is incremented BEFORE each attempt (so a crash mid-attempt still counts) and
 * capped at [MAX_COMPOSE_ATTEMPTS]. On success: [archiveGeneratedFile] creates the `document`/
 * `document_version` row under `documentStorageRoot`'s `"Aufzeichnungen"` folder, the recording row
 * is stamped `READY`, and the raw per-track directory is deleted UNLESS
 * [ConferenceRecordingConfig.keepRaw]. **On failure, raw files are ALWAYS retained regardless of
 * `keepRaw`** -- `deleteRecursively()` on the raw directory is only ever reached from the SUCCESS
 * branch below, never from the failure/`FAILED` branch -- because a `FAILED` recording's raw
 * footage may be the only remaining record of a legally significant meeting; a silent auto-delete
 * on failure would be an unrecoverable, unannounced data loss (see the Wave 2 UI/UX design review's
 * own D13 "Raw-file fate on FAILED must be explicit and safe").
 */
class RecordingPoller(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val liveKitEgressClient: LiveKitEgressClient,
    private val recordingConfig: ConferenceRecordingConfig,
    private val documentStorageRoot: File,
    private val composer: RecordingComposer,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    private val composeSemaphore = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. See class KDoc "Mechanism". */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(recordingConfig.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. Any in-flight [tick] finishes; nothing is force-killed. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass over every non-terminal recording. Exception-safe at two levels -- see class
     * KDoc "Mechanism" -- so a caller (production loop or a test) never needs its own try/catch.
     */
    suspend fun tick() {
        try {
            val rows =
                transaction {
                    ConferenceRecordingTable
                        .selectAll()
                        .where { ConferenceRecordingTable.status inList NON_TERMINAL_STATUSES }
                        .map { it.toRecordingRow() }
                }
            for (row in rows) {
                try {
                    when (row.status) {
                        ConferenceRecordingStatus.RECORDING -> handleRecording(row)
                        ConferenceRecordingStatus.STOPPING -> handleStopping(row)
                        ConferenceRecordingStatus.PROCESSING -> handleProcessing(row)
                        else -> Unit
                    }
                } catch (e: Throwable) {
                    logger.warn(e) { "RecordingPoller: tick failed for recording ${row.id} (status ${row.status})" }
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "RecordingPoller: tick failed" }
        }
    }

    // ── RECORDING ────────────────────────────────────────────────────────

    private suspend fun handleRecording(row: RecordingRow) {
        val now = clock()
        val roomRow = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row.roomId }.singleOrNull() }
        if (roomRow == null) {
            logger.warn { "RecordingPoller: room ${row.roomId} for recording ${row.id} no longer exists -- auto-stopping" }
            transitionToStopping(row.id, now)
            return
        }
        val roomEnded = roomRow[ConferenceRoomTable.endedAt] != null
        val maxDurationElapsed = elapsed(row.startedAt, now) >= recordingConfig.maxDurationMinutes.minutes
        if (roomEnded || maxDurationElapsed) {
            transitionToStopping(row.id, now)
            return
        }

        val livekitRoomName = roomRow[ConferenceRoomTable.livekitRoomName]
        val participants =
            try {
                liveKitAdminClient.listParticipants(livekitRoomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "RecordingPoller: ListParticipants failed for recording ${row.id}: ${e.message}" }
                return
            }

        val existingTrackIds =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId eq row.id }
                    .map { it[ConferenceRecordingTrackTable.livekitTrackId] }
                    .toSet()
            }
        var slotsRemaining = recordingConfig.maxTracks - existingTrackIds.size
        if (slotsRemaining <= 0) return

        for (participant in participants) {
            for (track in participant.tracks) {
                if (slotsRemaining <= 0) return
                if (track.sid in existingTrackIds || track.muted) continue
                val outputPath =
                    "${recordingConfig.outputContainerDir}/${row.rawDir}/${participant.identity}__${track.source}__${track.sid}"
                val egressInfo =
                    try {
                        liveKitEgressClient.startTrackEgress(livekitRoomName, track.sid, outputPath)
                    } catch (e: LiveKitAdminException) {
                        logger.warn {
                            "RecordingPoller: StartTrackEgress failed for track ${track.sid} (recording ${row.id}): ${e.message}"
                        }
                        continue
                    }
                slotsRemaining--
                transaction {
                    ConferenceRecordingTrackTable.insert {
                        it[id] = Uuid.random()
                        it[recordingId] = row.id
                        it[egressId] = egressInfo.egressId
                        it[livekitTrackId] = track.sid
                        it[participantIdentity] = participant.identity
                        it[trackSource] = mapTrackSource(track.source)
                        it[status] = mapEgressStatus(egressInfo.status)
                        it[startedAtEpochNanos] = egressInfo.startedAtEpochNanos.toLongOrNull()?.takeIf { it != 0L }
                        it[endedAtEpochNanos] = null
                        it[fileName] = null
                        it[durationMs] = null
                        it[sizeBytes] = null
                    }
                }
            }
        }
    }

    // ── STOPPING ─────────────────────────────────────────────────────────

    private suspend fun handleStopping(row: RecordingRow) {
        val now = clock()
        val roomRow = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row.roomId }.singleOrNull() }
        if (roomRow == null) {
            logger.warn { "RecordingPoller: room ${row.roomId} for recording ${row.id} no longer exists while STOPPING -- marking FAILED" }
            markFailed(row.id, FAILURE_COMPOSE_FAILED)
            return
        }
        val livekitRoomName = roomRow[ConferenceRoomTable.livekitRoomName]

        val trackRows =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId eq row.id }
                    .map { it.toTrackRow() }
            }

        // Fast-fail found live during this wave's own merge verification (2026-08-09): a recording
        // stopped before any participant ever published an unmuted audio/video track (handleRecording
        // -- the ONLY startTrackEgress call site -- only runs while status == RECORDING, so once here
        // in STOPPING the final track set is already fixed and can never grow) used to fall through to
        // applyEgressTimeout below and sit for the FULL egressTimeoutMinutes (default 30 minutes)
        // before finally reaching the same FAILED outcome applyEgressTimeout would produce anyway for
        // zero completed video tracks -- reproduced live: a moderator who starts and immediately stops
        // a recording with no camera/mic ever granted saw "Aufzeichnung wird beendet ..." hang with no
        // ETA and no server-side log line for the entire wait. Skips the pointless StopEgress/ListEgress
        // round trips too, since there is nothing to stop or list.
        if (trackRows.isEmpty()) {
            logger.warn { "RecordingPoller: recording ${row.id} stopped with zero published tracks -- FAILED immediately" }
            markFailed(row.id, FAILURE_NO_TRACKS)
            return
        }

        for (t in trackRows) {
            if (t.status == ConferenceRecordingTrackStatus.STARTING || t.status == ConferenceRecordingTrackStatus.ACTIVE) {
                try {
                    liveKitEgressClient.stopEgress(livekitRoomName, t.egressId)
                } catch (e: LiveKitAdminException) {
                    logger.warn { "RecordingPoller: StopEgress failed for ${t.egressId} (recording ${row.id}): ${e.message}" }
                }
            }
        }

        val egressInfos =
            try {
                liveKitEgressClient.listEgress(livekitRoomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "RecordingPoller: ListEgress failed for recording ${row.id}: ${e.message}" }
                // The egress-timeout-to-FAILED safety net (see class KDoc "STOPPING") must still
                // apply here -- a SUSTAINED ListEgress outage must not defeat the one deadline that
                // prevents an indefinite STOPPING hang. `trackRows` (the DB-known statuses from
                // before this failed call) is the best information available since the fresh
                // `EgressInfo` refresh never happened this tick.
                applyEgressTimeout(row, now, trackRows)
                return
            }
        val byEgressId = egressInfos.associateBy { it.egressId }

        transaction {
            for (t in trackRows) {
                val info = byEgressId[t.egressId] ?: continue
                val fileResult = info.firstFileResult
                ConferenceRecordingTrackTable.update({ ConferenceRecordingTrackTable.id eq t.id }) {
                    it[status] = mapEgressStatus(info.status)
                    if (fileResult != null) {
                        it[fileName] = fileResult.filename
                        it[durationMs] = fileResult.duration.toLongOrNull()?.let { nanos -> nanos / 1_000_000 }
                        it[sizeBytes] = fileResult.size.toLongOrNull()
                    }
                    info.startedAtEpochNanos
                        .toLongOrNull()
                        ?.takeIf { it != 0L }
                        ?.let { nanos -> it[startedAtEpochNanos] = nanos }
                    info.endedAtEpochNanos
                        .toLongOrNull()
                        ?.takeIf { it != 0L }
                        ?.let { nanos -> it[endedAtEpochNanos] = nanos }
                }
            }
        }

        val refreshedTracks =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId eq row.id }
                    .map { it.toTrackRow() }
            }
        val allTerminal = refreshedTracks.isNotEmpty() && refreshedTracks.all { it.status in TERMINAL_TRACK_STATUSES }
        if (allTerminal) {
            transitionToProcessing(row.id)
            return
        }

        applyEgressTimeout(row, now, refreshedTracks)
    }

    /**
     * The egress-timeout-to-FAILED safety net -- see class KDoc "STOPPING". Deliberately factored
     * out of [handleStopping]'s main body so it runs on BOTH paths out of that function: the normal
     * "ListEgress succeeded but tracks are still non-terminal" path (using the just-refreshed
     * [tracks]), and the "ListEgress itself is failing" path (using the last DB-known [tracks] from
     * before the failed call) -- a sustained LiveKit Egress outage must not defeat this deadline,
     * see [handleStopping]'s own `catch (e: LiveKitAdminException)` comment.
     */
    private fun applyEgressTimeout(
        row: RecordingRow,
        now: LocalDateTime,
        tracks: List<TrackRow>,
    ) {
        val stoppedAt = row.stoppedAt ?: return
        if (elapsed(stoppedAt, now) < recordingConfig.egressTimeoutMinutes.minutes) return
        val completedVideoCount =
            tracks.count { it.status == ConferenceRecordingTrackStatus.COMPLETE && it.trackSource in VIDEO_TRACK_SOURCES }
        if (completedVideoCount >= 1) {
            transitionToProcessing(row.id)
        } else {
            markFailed(row.id, FAILURE_COMPOSE_TIMEOUT)
        }
    }

    // ── PROCESSING ───────────────────────────────────────────────────────

    private suspend fun handleProcessing(row: RecordingRow) {
        if (row.composeAttempts >= MAX_COMPOSE_ATTEMPTS) {
            // Restart reconciliation: composition crashed mid-attempt after the counter was
            // already incremented -- never attempt a third time, see class KDoc.
            markFailed(row.id, FAILURE_COMPOSE_FAILED)
            return
        }
        if (!composeSemaphore.tryAcquire()) return
        try {
            composeOne(row)
        } finally {
            composeSemaphore.release()
        }
    }

    private suspend fun composeOne(row: RecordingRow) {
        val attemptNumber = row.composeAttempts + 1
        transaction {
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq row.id }) { it[composeAttempts] = attemptNumber }
        }

        val hostRawRoot = File(recordingConfig.outputHostDir)
        val completedTracks =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where {
                        (ConferenceRecordingTrackTable.recordingId eq row.id) and
                            (ConferenceRecordingTrackTable.status eq ConferenceRecordingTrackStatus.COMPLETE)
                    }.map { it.toTrackRow() }
            }

        data class Resolved(
            val track: TrackRow,
            val file: File,
        )
        val resolved =
            completedTracks.mapNotNull { t ->
                val fn = t.fileName
                if (fn == null) {
                    logger.warn { "RecordingPoller: COMPLETE track ${t.id} (recording ${row.id}) has no file_name -- skipping" }
                    return@mapNotNull null
                }
                val file = RecordingRawFiles.resolveWithin(hostRawRoot, row.rawDir, fn)
                if (file == null) {
                    logger.warn { "RecordingPoller: could not resolve raw file '$fn' for recording ${row.id} -- skipping this track" }
                    return@mapNotNull null
                }
                Resolved(t, file)
            }

        val t0 = resolved.mapNotNull { it.track.startedAtEpochNanos }.minOrNull() ?: 0L
        val videoResolved = resolved.filter { it.track.trackSource in VIDEO_TRACK_SOURCES }
        if (videoResolved.isEmpty()) {
            logger.warn { "RecordingPoller: no resolvable video track for recording ${row.id} -- FAILED" }
            markFailed(row.id, FAILURE_COMPOSE_FAILED)
            return
        }
        val audioResolved = resolved.filter { it.track.trackSource in AUDIO_TRACK_SOURCES }

        val outputDurationSeconds =
            resolved
                .maxOf { r -> offsetSeconds(r.track.startedAtEpochNanos, t0) + (r.track.durationMs?.let { it / 1000.0 } ?: 0.0) }
                .coerceAtLeast(1.0)

        val spec =
            RecordingComposeSpec(
                videoInputs =
                    videoResolved.map { r ->
                        RecordingComposeVideoInput(
                            file = r.file,
                            offsetSeconds = offsetSeconds(r.track.startedAtEpochNanos, t0),
                            isScreenShare = r.track.trackSource == ConferenceRecordingTrackSource.SCREEN_SHARE,
                        )
                    },
                audioInputs =
                    audioResolved.map { r ->
                        RecordingComposeAudioInput(file = r.file, offsetSeconds = offsetSeconds(r.track.startedAtEpochNanos, t0))
                    },
                outputDurationSeconds = outputDurationSeconds,
            )

        val outputFile = File(hostRawRoot, "${row.rawDir}/composed-$attemptNumber.mp4")
        outputFile.parentFile.mkdirs()
        try {
            composer.compose(spec, outputFile)

            val documentId =
                archiveGeneratedFile(
                    storageRoot = documentStorageRoot,
                    folderName = "Aufzeichnungen",
                    fileName = "aufzeichnung-${row.id}.mp4",
                    title = "Aufzeichnung ${row.id}",
                    sourceFile = outputFile,
                    mimeType = "video/mp4",
                    uploadedBy = row.startedByMemberId,
                    accessLevel = row.accessLevel,
                )
            val fileSizeBytes = outputFile.length()
            val now = clock()

            transaction {
                ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq row.id }) {
                    it[status] = ConferenceRecordingStatus.READY
                    it[readyAt] = now
                    it[ConferenceRecordingTable.documentId] = documentId
                    it[durationSeconds] = outputDurationSeconds.toLong()
                    it[ConferenceRecordingTable.fileSizeBytes] = fileSizeBytes
                }
            }

            // Raw deletion is ONLY ever reached from this success branch -- see class KDoc
            // "PROCESSING" for why a FAILED recording's raw files must never be touched here.
            if (!recordingConfig.keepRaw) {
                File(hostRawRoot, row.rawDir).deleteRecursively()
            }
        } catch (e: Exception) {
            logger.warn(e) { "RecordingPoller: composition/archiving failed for recording ${row.id} (attempt $attemptNumber)" }
            if (attemptNumber >= MAX_COMPOSE_ATTEMPTS) {
                markFailed(row.id, FAILURE_COMPOSE_FAILED)
            }
            // else: stays PROCESSING, retried on a later tick.
        } finally {
            outputFile.delete()
        }
    }

    // ── State-transition helpers ────────────────────────────────────────

    private fun transitionToStopping(
        recordingId: Uuid,
        now: LocalDateTime,
    ) {
        transaction {
            val current =
                ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingId }.singleOrNull()
                    ?: return@transaction
            if (current[ConferenceRecordingTable.status] != ConferenceRecordingStatus.RECORDING) return@transaction
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                it[stoppedAt] = now
                it[status] = ConferenceRecordingStatus.STOPPING
            }
            // System-initiated (auto-stop), no acting member -- AuditLogRecorder.record accepts a
            // null actor for exactly this case. Must be the LAST lock-taking operation -- see that
            // object's KDoc "deadlock-avoidance contract".
            AuditLogRecorder.record(
                actorMemberId = null,
                actorRole = null,
                entityType = AuditEntityType.CONFERENCE_RECORDING,
                entityId = recordingId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
        }
        logger.info { "RecordingPoller: auto-stopped recording $recordingId" }
    }

    private fun transitionToProcessing(recordingId: Uuid) {
        transaction {
            val current =
                ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingId }.singleOrNull()
                    ?: return@transaction
            if (current[ConferenceRecordingTable.status] != ConferenceRecordingStatus.STOPPING) return@transaction
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                it[status] = ConferenceRecordingStatus.PROCESSING
            }
        }
    }

    private fun markFailed(
        recordingId: Uuid,
        reason: String,
    ) {
        transaction {
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                it[status] = ConferenceRecordingStatus.FAILED
                it[failureReason] = reason
            }
        }
        logger.warn { "RecordingPoller: recording $recordingId marked FAILED: $reason" }
    }

    private fun elapsed(
        from: LocalDateTime,
        to: LocalDateTime,
    ) = to.toInstant(TZ) - from.toInstant(TZ)

    private fun offsetSeconds(
        startedAtEpochNanos: Long?,
        t0: Long,
    ): Double = if (startedAtEpochNanos == null) 0.0 else (startedAtEpochNanos - t0) / 1_000_000_000.0
}

private val TZ = TimeZone.currentSystemDefault()

private val NON_TERMINAL_STATUSES =
    listOf(ConferenceRecordingStatus.RECORDING, ConferenceRecordingStatus.STOPPING, ConferenceRecordingStatus.PROCESSING)

/** Plain data snapshot of a `conference_recording` row -- never held across a suspension point as a live [org.jetbrains.exposed.v1.core.ResultRow]. */
private data class RecordingRow(
    val id: Uuid,
    val roomId: Uuid,
    val startedByMemberId: Uuid,
    val startedAt: LocalDateTime,
    val stoppedAt: LocalDateTime?,
    val status: ConferenceRecordingStatus,
    val accessLevel: DocumentAccessLevel,
    val rawDir: String,
    val composeAttempts: Int,
)

/** Plain data snapshot of a `conference_recording_track` row -- same "never a live ResultRow across suspension" reasoning as [RecordingRow]. */
private data class TrackRow(
    val id: Uuid,
    val egressId: String,
    val status: ConferenceRecordingTrackStatus,
    val trackSource: ConferenceRecordingTrackSource,
    val fileName: String?,
    val startedAtEpochNanos: Long?,
    val durationMs: Long?,
)

private fun ResultRow.toRecordingRow() =
    RecordingRow(
        id = this[ConferenceRecordingTable.id],
        roomId = this[ConferenceRecordingTable.roomId],
        startedByMemberId = this[ConferenceRecordingTable.startedByMemberId],
        startedAt = this[ConferenceRecordingTable.startedAt],
        stoppedAt = this[ConferenceRecordingTable.stoppedAt],
        status = this[ConferenceRecordingTable.status],
        accessLevel = this[ConferenceRecordingTable.accessLevel],
        rawDir = this[ConferenceRecordingTable.rawDir],
        composeAttempts = this[ConferenceRecordingTable.composeAttempts],
    )

private fun ResultRow.toTrackRow() =
    TrackRow(
        id = this[ConferenceRecordingTrackTable.id],
        egressId = this[ConferenceRecordingTrackTable.egressId],
        status = this[ConferenceRecordingTrackTable.status],
        trackSource = this[ConferenceRecordingTrackTable.trackSource],
        fileName = this[ConferenceRecordingTrackTable.fileName],
        startedAtEpochNanos = this[ConferenceRecordingTrackTable.startedAtEpochNanos],
        durationMs = this[ConferenceRecordingTrackTable.durationMs],
    )

/** Maps LiveKit's `livekit.proto` `TrackSource` wire string -- see [LiveKitTrackInfo] KDoc -- onto this wave's own [ConferenceRecordingTrackSource]. */
private fun mapTrackSource(source: String): ConferenceRecordingTrackSource =
    when (source) {
        "CAMERA" -> ConferenceRecordingTrackSource.CAMERA
        "MICROPHONE" -> ConferenceRecordingTrackSource.MICROPHONE
        "SCREEN_SHARE" -> ConferenceRecordingTrackSource.SCREEN_SHARE
        "SCREEN_SHARE_AUDIO" -> ConferenceRecordingTrackSource.SCREEN_SHARE_AUDIO
        else -> ConferenceRecordingTrackSource.UNKNOWN
    }

/** Maps LiveKit's `EgressStatus` wire string -- see [LiveKitEgressInfo] KDoc -- onto this wave's own [ConferenceRecordingTrackStatus]. `EGRESS_ENDING` maps to [ConferenceRecordingTrackStatus.ACTIVE] (still finishing, not yet terminal); `EGRESS_LIMIT_REACHED` maps to [ConferenceRecordingTrackStatus.FAILED] (LiveKit stopped the egress on its own account, a failure from this wave's point of view). An unrecognized status stays [ConferenceRecordingTrackStatus.STARTING] (non-terminal, so polling continues -- eventually caught by the STOPPING egress-timeout deadline either way). */
private fun mapEgressStatus(status: String): ConferenceRecordingTrackStatus =
    when (status) {
        "EGRESS_STARTING" -> ConferenceRecordingTrackStatus.STARTING
        "EGRESS_ACTIVE" -> ConferenceRecordingTrackStatus.ACTIVE
        "EGRESS_ENDING" -> ConferenceRecordingTrackStatus.ACTIVE
        "EGRESS_COMPLETE" -> ConferenceRecordingTrackStatus.COMPLETE
        "EGRESS_FAILED" -> ConferenceRecordingTrackStatus.FAILED
        "EGRESS_ABORTED" -> ConferenceRecordingTrackStatus.ABORTED
        "EGRESS_LIMIT_REACHED" -> ConferenceRecordingTrackStatus.FAILED
        else -> ConferenceRecordingTrackStatus.STARTING
    }
