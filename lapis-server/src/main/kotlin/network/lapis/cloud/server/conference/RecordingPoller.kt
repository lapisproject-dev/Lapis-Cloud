package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.server.routes.archiveNewVersionOfFile
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRecordingEgressMode
import network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * `compose_attempts` ceiling -- see class KDoc "PROCESSING".
 *
 * Bug fix (live user report, 2026-08-27): a raw-file directory freshly created by `egress` can be
 * unwritable by the composing `ffmpeg` process for up to ~30s after creation, on any deployment
 * using the POSIX-ACL self-heal timer documented in `deploy/example/README.adoc` ("Shared
 * lapis-egress-output volume") -- that timer only re-applies the ACL every 30s, host-side, and the
 * in-container composer has no privilege to fix its own permission (it is deliberately non-root,
 * cannot chgrp/setfacl a directory it does not own). At the previous ceiling of 2, two attempts
 * spaced [ConferenceRecordingConfig.pollIntervalSeconds] (10s default) apart give up after only
 * ~10-20s -- comfortably inside that worst-case 30s window, so a recording composed immediately
 * after its raw directory was created could fail permanently even though the ACL fix was only
 * seconds away. Raised so the total retry window (attempts - 1) * pollIntervalSeconds comfortably
 * exceeds the timer's 30s worst case regardless of the configured poll interval.
 */
internal const val MAX_COMPOSE_ATTEMPTS = 5

/** V1.9.109: a gap of at least this many seconds between two packets of a video track means "not visible there" (camera switched off, see [RecordingMediaProber.probePacketRanges]). */
private const val VIDEO_GAP_SECONDS = 1.0

/** V1.9.109: audio gaps of at least this many seconds are silence rather than a lost packet. */
private const val AUDIO_GAP_SECONDS = 0.5

/** V1.9.110: black pictures shorter than this many seconds in a per-person file are a flicker, not a switched-off camera. */
private const val BLANK_MIN_SECONDS = 1.5

/** Bound of [RecordingPoller]'s "already warned" set -- a hostile stream of identities must not grow it without limit. */
private const val MAX_WARNED_SIDS = 1000

/** LiveKit `TrackSource` strings that belong to the person file of a per-person (Participant Egress) recording. */
private val PERSON_SOURCES = setOf("CAMERA", "MICROPHONE")

/** LiveKit `TrackSource` strings recorded by Track Egress even in the per-person mode (the egress's `screen_share` flag is `false`). */
private val SCREEN_SHARE_SOURCES = setOf("SCREEN_SHARE", "SCREEN_SHARE_AUDIO")

private val TERMINAL_TRACK_STATUSES =
    setOf(ConferenceRecordingTrackStatus.COMPLETE, ConferenceRecordingTrackStatus.FAILED, ConferenceRecordingTrackStatus.ABORTED)
private val VIDEO_TRACK_SOURCES = setOf(ConferenceRecordingTrackSource.CAMERA, ConferenceRecordingTrackSource.SCREEN_SHARE)
private val AUDIO_TRACK_SOURCES = setOf(ConferenceRecordingTrackSource.MICROPHONE, ConferenceRecordingTrackSource.SCREEN_SHARE_AUDIO)

/** Fixed, sanitized German vocabulary for [network.lapis.cloud.shared.domain.ConferenceRecordingDto.failureReason] -- see that field's own KDoc "a security boundary, not just a UX field". Raw ffmpeg/Twirp detail NEVER reaches these strings, only `kotlin-logging`. */
private const val FAILURE_COMPOSE_FAILED = "Die Aufzeichnung konnte nicht zusammengesetzt werden."
private const val FAILURE_COMPOSE_TIMEOUT = "Zeitüberschreitung beim Abschluss der Aufzeichnung."
private const val FAILURE_NO_TRACKS = "Es wurde keine Audio- oder Videospur aufgezeichnet."

/** ELB-Test-Fix 2026-09-27 (Befund 3): every track egress ran but NONE reached `COMPLETE` -- on ELB
 * because the egress service could not write into the shared output volume (`mkdir /out/<id>:
 * permission denied`, an ACL/ownership gap on that instance, see deploy/example/README.adoc "Shared
 * lapis-egress-output volume"). Distinct from [FAILURE_COMPOSE_FAILED], which previously covered this
 * case too and misdirected the diagnosis towards ffmpeg. Still sanitized: the raw egress error only
 * ever reaches the server log (see `handleStopping`), never this string. */
private const val FAILURE_NO_TRACK_STORED = "Der Aufzeichnungsdienst konnte keine Spur speichern (Server-Konfiguration prüfen)."

/** Code -> fixed German text. The two are always written together by [RecordingPoller.markFailed]; V87 backfills the code from exactly these strings. */
private val FAILURE_TEXT_BY_CODE =
    mapOf(
        ConferenceRecordingFailureCode.COMPOSE_FAILED to FAILURE_COMPOSE_FAILED,
        ConferenceRecordingFailureCode.EGRESS_TIMEOUT to FAILURE_COMPOSE_TIMEOUT,
        ConferenceRecordingFailureCode.NO_TRACKS to FAILURE_NO_TRACKS,
        ConferenceRecordingFailureCode.NO_TRACK_STORED to FAILURE_NO_TRACK_STORED,
    )

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
 * **[requestTick] -- the loop can be woken early.** ELB test recording 2026-09-27: the moderator
 * pressed "Aufzeichnung starten", the UI immediately showed "Aufzeichnung läuft", but the first
 * seconds of the meeting were missing from the finished video. Structural cause, not a composition
 * bug: `startRecording` only inserts a `RECORDING` row (see `ConferenceRecordingService` KDoc), and
 * the FIRST `StartTrackEgress` for that row happened on the next scheduled tick -- up to
 * [ConferenceRecordingConfig.pollIntervalSeconds] (10 s by default) later, plus the egress's own
 * join/subscribe latency, during which the participants were already talking "on the record".
 * `startRecording` now calls [requestTick] right after committing its row, so the discovery pass
 * runs within milliseconds; the loop's own `delay(interval)` becomes "sleep until the interval
 * elapses OR someone requests a tick" ([wake], a conflated channel -- N requests during one sleep
 * coalesce into ONE extra tick, so a burst of starts can never queue up a pile of redundant ticks).
 * The egress's own startup latency (LiveKit joining the room, subscribing, first keyframe) is
 * inherent to Track Egress and remains; the poll-interval part of the gap is gone.
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
 * ## Participant egress mode (V1.9.110, opt-in)
 *
 * A recording started with `LAPIS_RECORDING_EGRESS_MODE=participant` (stored per recording in `conference_recording.egress_mode`, so a
 * restart with another value never mixes modes) records ONE file per person with a Participant Egress -- audio and video muxed by LiveKit on
 * one clock -- instead of one file per track, because Track Egress has no RTCP sender-report synchronisation between its separate files (a
 * measured 5.3 s audio/video offset on the ELB recording `d08d4205`). See [handleRecordingParticipantMode]. Screen shares stay on Track
 * Egress. Composition: [ParticipantFileInputs] turns each person file into one video and one audio input with PER-STREAM offsets, no
 * `atempo`; a switched-off camera shows as a packet gap or as black pictures ([RecordingBlankVideoDetector]). A refused start is retried
 * and never falls back to Track Egress. Recompute needs no special case: [buildSpec] decides per ROW (`egress_kind`), not by configuration.
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
 * ## Manual retry (V1.9.107)
 *
 * A moderator can restart a FAILED/`COMPOSE_FAILED` recording (`ConferenceRecordingService.retryRecordingComposition`). That
 * action only flips the row back to PROCESSING with `compose_attempts = MAX_COMPOSE_ATTEMPTS - 1` and wakes this poller
 * ([requestTick]); the poller itself is unchanged: it sees PROCESSING with 4 attempts, makes exactly ONE attempt (number 5) and
 * ends in READY or again FAILED/`COMPOSE_FAILED` (raw files are kept on failure).
 *
 * ## Worker (V1.9.109)
 *
 * A composition (or recompute) of a long meeting takes many minutes. Until V1.9.108 it ran INSIDE [tick], and while it did no
 * `StartTrackEgress` was issued for any meeting that was recording at the same time -- somebody who joined then was never recorded.
 * Since V1.9.109 the work runs on its own coroutine ([launchWorker]); [tick] only starts it, and only when [composeSemaphore] (capacity 1,
 * non-blocking `tryAcquire`) is free, so at most one `ffmpeg` composition runs system-wide at any moment and `RECORDING`/`STOPPING`
 * handling of every room continues meanwhile. The worker re-reads its row when it starts (the tick's snapshot may be older than the
 * previous worker's last write). Tests wait with [awaitCompositionIdleForTest].
 *
 * ## Recompute (V1.9.109)
 *
 * `ConferenceRecordingService.recomputeRecording` marks a READY recording with `recompute_started_at`; the tick finds those rows
 * ([acceptRecompute]) and the worker composes the raw tracks anew into `recompute-<uuid>.mp4.part`, then
 * `archiveNewVersionOfFile` adds the result as a NEW version of the recording's document and switches `currentVersionId` in ONE
 * transaction -- the atomic replacement. The marker doubles as the fencing value: every write of the recompute is conditional on
 * `status = READY AND recompute_started_at = <marker>`, so a deletion, a newer request or a stale worker can never write over a newer
 * state (nothing is committed then, and the new blob is removed). A failure clears the marker and records `COMPOSE_FAILED`; the
 * previous file is untouched in every case, raw files are never deleted, the previous version stays in the document history. A marker
 * older than twice the composition timeout without a worker is declared failed (a crash must not leave a recording "being recomputed"
 * for ever); a marker found after a restart simply starts the recompute again.
 *
 * ## PROCESSING
 *
 * See "Worker": at most one composition runs at a time; if the worker is busy this tick simply skips PROCESSING-status recordings and
 * retries them next tick.
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
 *
 * **Per-track alignment at composition time** -- each COMPLETE track's `offsetSeconds` is
 * [RecordingTrackAlignment.offsetSeconds]: its file-level `started_at` (receive time of the track's
 * first RTP packet) relative to the recording's earliest track, and nothing else. The [prober]'s
 * `ffprobe` numbers (media duration, the file's own `start_time`, and the reported-minus-probed
 * duration difference) are DIAGNOSTICS logged at INFO next to the applied offset -- round 1 of the
 * 2026-09-27 fix applied that difference as a "head loss" and thereby introduced a spurious
 * 0.285 s audio-vs-video shift on the first real ELB recording; see [RecordingTrackAlignment]'s KDoc
 * for the LiveKit-source-level reason (`FileInfo.Duration` is floored by wall-clock pipeline
 * running time at EOS, so the difference is tail latency, not a lost head).
 */
class RecordingPoller(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val liveKitEgressClient: LiveKitEgressClient,
    private val recordingConfig: ConferenceRecordingConfig,
    private val documentStorageRoot: File,
    private val composer: RecordingComposer,
    private val prober: RecordingMediaProber = RecordingMediaProber.None,
    /** V1.9.110: finds the black stretches (camera switched off) of a per-person file; a test seam, [RecordingBlankVideoDetector.None] measures nothing. */
    private val blankDetector: RecordingBlankVideoDetector = RecordingBlankVideoDetector.None,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
    /** V1.9.109: free-space probe of the recompute's pre-flight check; a test seam. */
    private val diskSpace: (File) -> Long = { it.usableSpace },
    /** V1.9.115: measures the loudness of the microphones for the speaker timeline; a test seam, [SpeechActivityAnalyzer.None] measures nothing (the recording then shows the grid). */
    private val speechAnalyzer: SpeechActivityAnalyzer = SpeechActivityAnalyzer.None,
) {
    private val composeSemaphore = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** V1.9.109: the ONE running composition/recompute (see class KDoc "Worker"); `null` while idle. */
    @Volatile
    private var workerJob: Job? = null

    /** The recording the worker is busy with, so the stale-recompute rule never fails a live job. */
    @Volatile
    private var workerRecordingId: Uuid? = null

    /** Recordings the worker has been handed but has not finished yet (the current one included); the tick must never fail those. */
    @Volatile
    private var workerPendingIds: Set<Uuid> = emptySet()

    /**
     * V1.9.110: Participant-Egress rows for which a `StopEgress` was already sent because the person left the room -- keeps the
     * leave handling from repeating the call every tick. In memory only: after a restart at most one repetition happens, and StopEgress is idempotent.
     */
    private val stopRequestedEgressIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** V1.9.110: participant SIDs already reported as having an unusable identity (logged once, not every tick). */
    private val warnedUnusableParticipantSids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** See class KDoc "requestTick". Conflated: at most one pending wake-up, never a queue. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Idempotent -- a second call while already running is a no-op. See class KDoc "Mechanism". */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    // Sleep until the interval elapses OR requestTick() wakes us -- see class KDoc.
                    withTimeoutOrNull(recordingConfig.pollIntervalSeconds.seconds) { wake.receive() }
                }
            }
    }

    /**
     * Asks the running loop to run its next [tick] now instead of after the remaining poll delay --
     * see class KDoc "requestTick". Non-suspending and never blocks: `trySend` on a conflated
     * channel always succeeds immediately. A no-op if the loop is not running (a deployment with
     * recording disabled never started it; nothing to wake).
     */
    fun requestTick() {
        if (loopJob == null) return
        wake.trySend(Unit)
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. Any in-flight [tick] finishes; nothing is force-killed. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        workerJob?.cancel()
    }

    /**
     * Suspends until the composition/recompute worker has finished (returns at once while idle) -- a TEST SEAM: since V1.9.109 a
     * composition no longer runs inside [tick] (see class KDoc "Worker"), so a test that wants the outcome of one waits here.
     */
    suspend fun awaitCompositionIdleForTest() {
        workerJob?.join()
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
                        .where {
                            (ConferenceRecordingTable.status inList NON_TERMINAL_STATUSES) or
                                (
                                    (ConferenceRecordingTable.status eq ConferenceRecordingStatus.READY) and
                                        ConferenceRecordingTable.recomputeStartedAt.isNotNull()
                                )
                        }.map { it.toRecordingRow() }
                }
            val workQueue = ArrayList<WorkItem>()
            for (row in rows) {
                try {
                    when (row.status) {
                        ConferenceRecordingStatus.RECORDING -> handleRecording(row)
                        ConferenceRecordingStatus.STOPPING -> handleStopping(row)
                        ConferenceRecordingStatus.PROCESSING ->
                            if (acceptProcessing(row)) {
                                workQueue +=
                                    WorkItem(recordingId = row.id, recompute = false)
                            }
                        ConferenceRecordingStatus.READY ->
                            if (acceptRecompute(row)) {
                                workQueue +=
                                    WorkItem(recordingId = row.id, recompute = true)
                            }
                        else -> Unit
                    }
                } catch (e: Throwable) {
                    logger.warn(e) { "RecordingPoller: tick failed for recording ${row.id} (status ${row.status})" }
                }
            }
            if (workQueue.isNotEmpty()) launchWorker(workQueue)
        } catch (e: Throwable) {
            logger.warn(e) { "RecordingPoller: tick failed" }
        }
    }

    /** One recording the worker has to compose (PROCESSING) or recompute (READY with a marker). */
    private data class WorkItem(
        val recordingId: Uuid,
        val recompute: Boolean,
    )

    // ── RECORDING ────────────────────────────────────────────────────────

    private suspend fun handleRecording(row: RecordingRow) {
        val now = clock()
        val roomRow = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row.roomId }.singleOrNull() }
        if (roomRow == null) {
            logger.warn { "RecordingPoller: room ${row.roomId} for recording ${row.id} no longer exists -- auto-stopping" }
            transitionToStopping(recordingId = row.id, now = now)
            return
        }
        val roomEnded = roomRow[ConferenceRoomTable.endedAt] != null
        val maxDurationElapsed = elapsed(from = row.startedAt, to = now) >= recordingConfig.maxDurationMinutes.minutes
        if (roomEnded || maxDurationElapsed) {
            transitionToStopping(recordingId = row.id, now = now)
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

        val existingRows =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId eq row.id }
                    .map { it.toTrackRow() }
            }
        if (row.egressMode == ConferenceRecordingEgressMode.PARTICIPANT) {
            handleRecordingParticipantMode(
                row = row,
                livekitRoomName = livekitRoomName,
                participants = participants,
                existingRows = existingRows,
            )
            return
        }
        val existingTrackIds = existingRows.mapTo(HashSet()) { it.livekitTrackId }
        var slotsRemaining = recordingConfig.maxTracks - existingTrackIds.size
        if (slotsRemaining <= 0) return

        for (participant in participants) {
            if (!usableIdentity(participant)) continue
            for (track in participant.tracks) {
                if (slotsRemaining <= 0) return
                if (track.sid in existingTrackIds || track.muted) continue
                val outputPath =
                    "${recordingConfig.outputContainerDir}/${row.rawDir}/${participant.identity}__${track.source}__${track.sid}"
                val egressInfo =
                    try {
                        liveKitEgressClient.startTrackEgress(
                            roomName = livekitRoomName,
                            trackId = track.sid,
                            outputFilepathWithoutExtension = outputPath,
                        )
                    } catch (e: LiveKitAdminException) {
                        logger.warn {
                            "RecordingPoller: StartTrackEgress failed for track ${track.sid} (recording ${row.id}): ${e.message}"
                        }
                        continue
                    }
                slotsRemaining--
                insertTrackRow(
                    recordingId = row.id,
                    rowId = Uuid.random(),
                    egressInfo = egressInfo,
                    livekitId = track.sid,
                    identity = participant.identity,
                    source = mapTrackSource(track.source),
                    kind = ConferenceRecordingEgressMode.TRACK,
                )
            }
        }
    }

    /**
     * V1.9.110 -- the discovery pass of a recording in [ConferenceRecordingEgressMode.PARTICIPANT] mode: ONE Participant Egress per
     * person (audio and video muxed by LiveKit on one clock, written to `p-<rowUuid>.mp4`), Track Egress only for screen shares.
     *
     *  1. A person who has published a camera or a microphone track (muted is fine -- unlike Track Egress this egress follows the
     *     person, not the track) and whose participant SID is not yet known gets a `StartParticipantEgress` and a row
     *     (`egress_kind = PARTICIPANT`, `track_source = CAMERA`, `livekit_track_id` = the participant SID).
     *  2. LiveKit hands out a new SID on every join: a person who leaves and comes back gets a new row, a new file and, in the
     *     composition, a new picture.
     *  3. Screen shares (`SCREEN_SHARE`, `SCREEN_SHARE_AUDIO`) run exactly as in the track mode.
     *  4. A person who is gone from the room gets ONE `StopEgress` (deterministic, whether or not LiveKit ends the egress on its own),
     *     followed by a refresh of the rows.
     *  5. A refused start (e.g. LiveKit has no egress CPU left) is logged and tried again on the next tick -- there is deliberately NO
     *     fallback to Track Egress, which would silently bring the audio/video offset back.
     * Every row counts against [ConferenceRecordingConfig.maxTracks].
     */
    private suspend fun handleRecordingParticipantMode(
        row: RecordingRow,
        livekitRoomName: String,
        participants: List<LiveKitParticipantInfo>,
        existingRows: List<TrackRow>,
    ) {
        val knownIds = existingRows.mapTo(HashSet()) { it.livekitTrackId }
        stopEgressOfDepartedParticipants(row = row, livekitRoomName = livekitRoomName, participants = participants, rows = existingRows)

        var slotsRemaining = recordingConfig.maxTracks - existingRows.size
        for (participant in participants) {
            for (track in participant.tracks) {
                if (slotsRemaining <= 0) return
                if (track.source !in SCREEN_SHARE_SOURCES || track.sid in knownIds || track.muted) continue
                if (!usableIdentity(participant)) continue
                val outputPath =
                    "${recordingConfig.outputContainerDir}/${row.rawDir}/${participant.identity}__${track.source}__${track.sid}"
                val egressInfo =
                    try {
                        liveKitEgressClient.startTrackEgress(
                            roomName = livekitRoomName,
                            trackId = track.sid,
                            outputFilepathWithoutExtension = outputPath,
                        )
                    } catch (e: LiveKitAdminException) {
                        logger.warn {
                            "RecordingPoller: StartTrackEgress failed for track ${track.sid} (recording ${row.id}): ${e.message}"
                        }
                        continue
                    }
                slotsRemaining--
                insertTrackRow(
                    recordingId = row.id,
                    rowId = Uuid.random(),
                    egressInfo = egressInfo,
                    livekitId = track.sid,
                    identity = participant.identity,
                    source = mapTrackSource(track.source),
                    kind = ConferenceRecordingEgressMode.TRACK,
                )
            }
            if (slotsRemaining <= 0) return
            val hasPersonTrack = participant.tracks.any { it.source in PERSON_SOURCES }
            if (!hasPersonTrack || participant.sid.isBlank() || participant.sid in knownIds) continue
            if (!usableIdentity(participant)) continue
            val rowId = Uuid.random()
            val outputPath =
                RecordingEgressPaths.participantFile(
                    containerDir = recordingConfig.outputContainerDir,
                    rawDir = row.rawDir,
                    rowId = rowId,
                )
            val egressInfo =
                try {
                    liveKitEgressClient.startParticipantFileEgress(
                        roomName = livekitRoomName,
                        identity = participant.identity,
                        outputFilepath = outputPath,
                    )
                } catch (e: LiveKitAdminException) {
                    // No identity in the message (data protection); the row id would only exist after success -- the recording id is enough.
                    logger.warn {
                        "RecordingPoller: StartParticipantEgress failed for a participant of recording ${row.id} (retried next tick): ${e.message}"
                    }
                    continue
                }
            slotsRemaining--
            insertTrackRow(
                recordingId = row.id,
                rowId = rowId,
                egressInfo = egressInfo,
                livekitId = participant.sid,
                identity = participant.identity,
                source = ConferenceRecordingTrackSource.CAMERA,
                kind = ConferenceRecordingEgressMode.PARTICIPANT,
            )
        }
    }

    /**
     * Sends ONE `StopEgress` for every still-running Participant-Egress row whose person is no longer in [participants] (see
     * [handleRecordingParticipantMode] item 4), then refreshes those rows from `ListEgress`.
     */
    private suspend fun stopEgressOfDepartedParticipants(
        row: RecordingRow,
        livekitRoomName: String,
        participants: List<LiveKitParticipantInfo>,
        rows: List<TrackRow>,
    ) {
        val presentSids = participants.mapTo(HashSet()) { it.sid }
        val departed =
            rows.filter {
                it.egressKind == ConferenceRecordingEgressMode.PARTICIPANT &&
                    (it.status == ConferenceRecordingTrackStatus.STARTING || it.status == ConferenceRecordingTrackStatus.ACTIVE) &&
                    it.livekitTrackId !in presentSids &&
                    it.egressId !in stopRequestedEgressIds
            }
        if (departed.isEmpty()) return
        var stopped = false
        for (t in departed) {
            try {
                liveKitEgressClient.stopEgress(roomName = livekitRoomName, egressId = t.egressId)
                stopRequestedEgressIds += t.egressId
                stopped = true
            } catch (e: LiveKitAdminException) {
                // Not remembered: the next tick tries again. If LiveKit already ended the egress, the refresh below makes the row terminal.
                logger.warn { "RecordingPoller: StopEgress failed for ${t.egressId} (recording ${row.id}): ${e.message}" }
            }
        }
        if (!stopped) return
        val egressInfos =
            try {
                liveKitEgressClient.listEgress(livekitRoomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "RecordingPoller: ListEgress failed for recording ${row.id}: ${e.message}" }
                return
            }
        refreshTrackRows(row = row, egressInfos = egressInfos, trackRows = departed)
    }

    /** `false` (and ONE warning per participant) for an identity that does not fit the column / is not a harmless file-name part, see [RecordingEgressPaths.isAcceptableIdentity]. */
    private fun usableIdentity(participant: LiveKitParticipantInfo): Boolean {
        if (RecordingEgressPaths.isAcceptableIdentity(participant.identity)) return true
        if (warnedUnusableParticipantSids.size < MAX_WARNED_SIDS && warnedUnusableParticipantSids.add(participant.sid)) {
            logger.warn { "RecordingPoller: a participant with an unusable identity (length or characters) is not recorded" }
        }
        return false
    }

    private fun insertTrackRow(
        recordingId: Uuid,
        rowId: Uuid,
        egressInfo: LiveKitEgressInfo,
        livekitId: String,
        identity: String,
        source: ConferenceRecordingTrackSource,
        kind: ConferenceRecordingEgressMode,
    ) {
        transaction {
            ConferenceRecordingTrackTable.insert {
                it[id] = rowId
                it[ConferenceRecordingTrackTable.recordingId] = recordingId
                it[egressId] = egressInfo.egressId
                it[livekitTrackId] = livekitId
                it[participantIdentity] = identity
                it[trackSource] = source
                it[egressKind] = kind
                it[status] = mapEgressStatus(egressInfo.status)
                it[startedAtEpochNanos] = egressInfo.startedAtEpochNanos.toLongOrNull()?.takeIf { it != 0L }
                it[endedAtEpochNanos] = null
                it[fileName] = null
                it[durationMs] = null
                it[sizeBytes] = null
            }
        }
    }

    // ── STOPPING ─────────────────────────────────────────────────────────

    private suspend fun handleStopping(row: RecordingRow) {
        val now = clock()
        val roomRow = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row.roomId }.singleOrNull() }
        if (roomRow == null) {
            logger.warn { "RecordingPoller: room ${row.roomId} for recording ${row.id} no longer exists while STOPPING -- marking FAILED" }
            markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.COMPOSE_FAILED)
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
            markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.NO_TRACKS)
            return
        }

        for (t in trackRows) {
            if (t.status == ConferenceRecordingTrackStatus.STARTING || t.status == ConferenceRecordingTrackStatus.ACTIVE) {
                try {
                    liveKitEgressClient.stopEgress(roomName = livekitRoomName, egressId = t.egressId)
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
                applyEgressTimeout(row = row, now = now, tracks = trackRows)
                return
            }
        refreshTrackRows(row = row, egressInfos = egressInfos, trackRows = trackRows)

        val refreshedTracks =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId eq row.id }
                    .map { it.toTrackRow() }
            }
        val allTerminal = refreshedTracks.isNotEmpty() && refreshedTracks.all { it.status in TERMINAL_TRACK_STATUSES }
        if (allTerminal) {
            stopRequestedEgressIds.removeAll(refreshedTracks.mapTo(HashSet()) { it.egressId })
            transitionToProcessing(row.id)
            return
        }

        applyEgressTimeout(row = row, now = now, tracks = refreshedTracks)
    }

    /**
     * Updates [trackRows] (status, file name, duration, size, start/end) from the authoritative `EgressInfo`s of `ListEgress`. Shared by
     * [handleStopping] (all rows) and the leave handling of the per-person mode (the departed person's rows only).
     */
    private fun refreshTrackRows(
        row: RecordingRow,
        egressInfos: List<LiveKitEgressInfo>,
        trackRows: List<TrackRow>,
    ) {
        val byEgressId = egressInfos.associateBy { it.egressId }

        transaction {
            for (t in trackRows) {
                val info = byEgressId[t.egressId] ?: continue
                val fileResult = info.firstFileResult
                val mappedStatus = mapEgressStatus(info.status)
                // ELB-Test-Fix 2026-09-27 (Befund 3): LiveKit's own `EgressInfo.error` is the ONE
                // place the real cause of a failed track egress is visible (on ELB: "Local upload
                // failed: mkdir /out/<id>/: permission denied"). It was never logged before, so the
                // server log showed only the misleading HTTP 412 from the subsequent StopEgress and
                // the recording ended as "konnte nicht zusammengesetzt werden". Log-only -- the
                // sanitized `failure_reason` vocabulary stays a security boundary.
                if (
                    (mappedStatus == ConferenceRecordingTrackStatus.FAILED || mappedStatus == ConferenceRecordingTrackStatus.ABORTED) &&
                    t.status != mappedStatus &&
                    info.error.isNotBlank()
                ) {
                    logger.warn {
                        "RecordingPoller: track egress ${t.egressId} (recording ${row.id}, ${t.trackSource}) ended ${info.status}: ${info.error}"
                    }
                }
                ConferenceRecordingTrackTable.update({ ConferenceRecordingTrackTable.id eq t.id }) {
                    it[status] = mappedStatus
                    if (fileResult != null) {
                        it[fileName] = fileResult.filename
                        it[durationMs] = fileResult.duration.toLongOrNull()?.let { nanos -> nanos / 1_000_000 }
                        it[sizeBytes] = fileResult.size.toLongOrNull()
                    }
                    // Bug fix (live user report -- A/V gap in composed recordings): prefer the FILE's
                    // own `started_at` (`fileResult.startedAtEpochNanos`, the first-media-byte
                    // timestamp) over the EGRESS JOB's `started_at` (`info.startedAtEpochNanos`,
                    // seeded at StartTrackEgress-response time, before a single byte is written). The
                    // gap between the two is systematic and asymmetric between audio and video: an
                    // audio track can write its first packet almost immediately, while a video track
                    // cannot write frame 0 until a keyframe arrives (PLI round-trip + IDR), routinely
                    // several hundred ms later. `offsetSeconds` below (see its own KDoc) is only
                    // correct if every track's `startedAtEpochNanos` means the same thing -- anchoring
                    // to the job-level field instead of the file-level one biased every video track's
                    // computed offset too early relative to audio by exactly that keyframe-wait delta,
                    // which is video visibly lagging audio in the composed output.
                    (
                        fileResult?.startedAtEpochNanos?.toLongOrNull()?.takeIf { it != 0L }
                            ?: info.startedAtEpochNanos.toLongOrNull()?.takeIf { it != 0L }
                    )?.let { nanos -> it[startedAtEpochNanos] = nanos }
                    info.endedAtEpochNanos
                        .toLongOrNull()
                        ?.takeIf { it != 0L }
                        ?.let { nanos -> it[endedAtEpochNanos] = nanos }
                }
            }
        }
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
        if (elapsed(from = stoppedAt, to = now) < recordingConfig.egressTimeoutMinutes.minutes) return
        val completedVideoCount =
            tracks.count { it.status == ConferenceRecordingTrackStatus.COMPLETE && it.trackSource in VIDEO_TRACK_SOURCES }
        if (completedVideoCount >= 1) {
            transitionToProcessing(row.id)
        } else {
            markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.EGRESS_TIMEOUT)
        }
    }

    // ── Worker (V1.9.109) ────────────────────────────────────────────────

    /**
     * Starts THE worker for [items], if no other composition/recompute is running ([composeSemaphore], non-blocking `tryAcquire`; a
     * tick that finds the worker busy simply tries again next time). The worker walks the items one after the other, exactly as the
     * tick used to, but on its own coroutine, NOT inside the tick: a composition of a long meeting takes many minutes, and while it ran
     * inline no `StartTrackEgress` was issued for meetings that were recording at the same time (a person who joined then was never
     * recorded). See class KDoc "Worker".
     */
    private fun launchWorker(items: List<WorkItem>) {
        if (!composeSemaphore.tryAcquire()) return
        workerPendingIds = items.mapTo(HashSet()) { it.recordingId }
        workerJob =
            scope.launch {
                try {
                    for (item in items) {
                        workerRecordingId = item.recordingId
                        try {
                            // The tick's snapshot may be older than this moment (the previous item or an earlier worker may have changed
                            // the row): decide on the current row, never on the snapshot.
                            val fresh = loadRow(item.recordingId) ?: continue
                            if (item.recompute) {
                                if (fresh.status == ConferenceRecordingStatus.READY && fresh.recomputeStartedAt != null) recomputeOne(fresh)
                            } else if (fresh.status == ConferenceRecordingStatus.PROCESSING &&
                                fresh.composeAttempts < MAX_COMPOSE_ATTEMPTS
                            ) {
                                composeOne(fresh)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.warn(e) { "RecordingPoller: worker failed for recording ${item.recordingId}" }
                        } finally {
                            workerRecordingId = null
                        }
                    }
                } finally {
                    workerPendingIds = emptySet()
                    composeSemaphore.release()
                }
            }
    }

    // ── PROCESSING ───────────────────────────────────────────────────────

    /** `true` iff [row] should be composed by the worker; a row at the attempt ceiling is failed on the spot instead. */
    private fun acceptProcessing(row: RecordingRow): Boolean {
        // The worker is composing this row right now (or has it queued): its attempt counter is already raised, but that is NOT a crash.
        if (workerRecordingId == row.id || row.id in workerPendingIds) return false
        if (row.composeAttempts >= MAX_COMPOSE_ATTEMPTS) {
            // Restart reconciliation: composition crashed mid-attempt after the counter was
            // already incremented -- never attempt a third time, see class KDoc.
            markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.COMPOSE_FAILED)
            return false
        }
        return true
    }

    private fun loadRow(recordingId: Uuid): RecordingRow? =
        transaction {
            ConferenceRecordingTable
                .selectAll()
                .where { ConferenceRecordingTable.id eq recordingId }
                .singleOrNull()
                ?.toRecordingRow()
        }

    /** What [buildSpec] found: a spec to compose, or the reason there is none. */
    private sealed interface SpecResult {
        data class Ready(
            val spec: RecordingComposeSpec,
        ) : SpecResult

        /** Not a single track egress completed (see [FAILURE_NO_TRACK_STORED]). */
        data object NoTrackStored : SpecResult

        /** Tracks exist but no video track of them could be resolved on disk. */
        data object NoVideo : SpecResult
    }

    /**
     * Resolves the COMPLETE tracks of [row] on disk, aligns them on one timeline and measures them -- everything the composer needs,
     * shared by the first composition ([composeOne]) and the recompute ([recomputeOne]).
     *
     * **Per-track alignment** -- each track's `offsetSeconds` is [RecordingTrackAlignment.offsetSeconds]: its file-level `started_at`
     * (receive time of the track's first RTP packet) relative to the recording's earliest track, and nothing else. The [prober]'s
     * `ffprobe` numbers (media duration, the file's own `start_time`, and the reported-minus-probed duration difference) are
     * DIAGNOSTICS logged at INFO next to the applied offset -- round 1 of the 2026-09-27 fix applied that difference as a "head loss"
     * and thereby introduced a spurious 0.285 s audio-vs-video shift on the first real ELB recording; see [RecordingTrackAlignment].
     *
     * **Data ranges (V1.9.109)** -- additionally the stretches in which a track really carries packets
     * ([RecordingMediaProber.probePacketRanges]; video gaps of at least [VIDEO_GAP_SECONDS], audio gaps of at least
     * [AUDIO_GAP_SECONDS]). They decide who is in the picture and where audio is silence. A failed probe yields no ranges and the
     * composer then treats the whole reported duration as one range.
     */
    private suspend fun buildSpec(
        row: RecordingRow,
        hostRawRoot: File,
    ): SpecResult {
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
                val file = RecordingRawFiles.resolveWithin(hostRawRoot = hostRawRoot, recordingId = row.rawDir, reportedFilename = fn)
                if (file == null) {
                    logger.warn { "RecordingPoller: could not resolve raw file '$fn' for recording ${row.id} -- skipping this track" }
                    return@mapNotNull null
                }
                Resolved(t, file)
            }

        val t0 = resolved.mapNotNull { it.track.startedAtEpochNanos }.minOrNull() ?: 0L
        if (completedTracks.isEmpty()) return SpecResult.NoTrackStored
        // A per-person file (V1.9.110) has a video stream when its row says CAMERA, as every video check relies on.
        val videoResolved = resolved.filter { it.track.trackSource in VIDEO_TRACK_SOURCES }
        if (videoResolved.isEmpty()) return SpecResult.NoVideo

        data class Measured(
            val offset: Double,
            val probe: RecordingMediaProbe,
            val ranges: PacketRanges?,
            /** Only for a per-person file: the per-stream measurements [ParticipantFileInputs] turns into one video and one audio input. */
            val person: ParticipantStreamMeasurements?,
        )
        val measured =
            resolved.associate { r ->
                val probe = prober.probe(r.file)
                val isPerson = r.track.egressKind == ConferenceRecordingEgressMode.PARTICIPANT
                val isVideo = r.track.trackSource in VIDEO_TRACK_SOURCES
                val offset =
                    RecordingTrackAlignment.offsetSeconds(
                        startedAtEpochNanos = r.track.startedAtEpochNanos,
                        t0EpochNanos = t0,
                    )
                val discrepancy =
                    RecordingTrackAlignment.durationDiscrepancySeconds(
                        reportedDurationMs = r.track.durationMs,
                        probedDurationSeconds = probe.durationSeconds,
                    )
                if (isPerson) {
                    val person = measurePersonFile(file = r.file, probe = probe, reportedDurationMs = r.track.durationMs)
                    logger.info {
                        "RecordingPoller: recording ${row.id} person file '${r.file.name}': " +
                            "fileOffsetApplied=${formatSeconds(offset)}s; streams: " +
                            "videoStartS=${person.videoStartSeconds?.let(::formatSeconds) ?: "n/a"}, " +
                            "audioStartS=${person.audioStartSeconds?.let(::formatSeconds) ?: "n/a"}, " +
                            "videoRanges=${person.videoPacketRanges?.ranges?.size ?: "n/a"}, " +
                            "audioRanges=${person.audioPacketRanges?.ranges?.size ?: "n/a"}, " +
                            "blankRanges=${person.blankVideoRanges?.size ?: "n/a"}; " +
                            "probedDurationS=${probe.durationSeconds?.let(::formatSeconds) ?: "n/a"}"
                    }
                    return@associate r.track.id to Measured(offset, probe, null, person)
                }
                val ranges =
                    prober.probePacketRanges(
                        file = r.file,
                        kind = if (isVideo) StreamKind.VIDEO else StreamKind.AUDIO,
                        gapThresholdSeconds = if (isVideo) VIDEO_GAP_SECONDS else AUDIO_GAP_SECONDS,
                    )
                logger.info {
                    "RecordingPoller: recording ${row.id} track ${r.track.trackSource} '${r.file.name}': " +
                        "offsetApplied=${formatSeconds(offset)}s (started_at relative to earliest track); diagnostics: " +
                        "reportedDurationMs=${r.track.durationMs}, " +
                        "probedDurationS=${probe.durationSeconds?.let(::formatSeconds) ?: "n/a"}, " +
                        "probedStartTimeS=${probe.startTimeSeconds?.let(::formatSeconds) ?: "n/a"}, " +
                        "reportedMinusProbedS=${discrepancy?.let(::formatSeconds) ?: "n/a"} (not applied); " +
                        "dataRanges=${ranges?.ranges?.size ?: "n/a"}"
                }
                r.track.id to Measured(offset, probe, ranges, null)
            }

        /** The best known media length of a track: measured packets, then container, then what the egress reported. */
        fun mediaSeconds(r: Resolved): Double? {
            val m = measured.getValue(r.track.id)
            val personDuration =
                m.person?.let {
                    maxOf(
                        it.videoPacketRanges?.mediaDurationSeconds ?: 0.0,
                        it.audioPacketRanges?.mediaDurationSeconds ?: 0.0,
                    )
                }
            return personDuration?.takeIf { it > 0.0 }
                ?: m.ranges?.mediaDurationSeconds
                ?: m.probe.durationSeconds
                ?: r.track.durationMs?.let { it / 1000.0 }
        }

        // The length of the output is governed by the start offsets and the durations the egress REPORTED (they include the 0.1-0.4 s
        // flush tail after the last packet, see RecordingTrackAlignment) -- the old, tested behaviour; the measured media length only
        // feeds the composer's per-track ranges.
        val outputDurationSeconds =
            resolved
                .maxOf { r -> measured.getValue(r.track.id).offset + (r.track.durationMs?.let { it / 1000.0 } ?: mediaSeconds(r) ?: 0.0) }
                .coerceAtLeast(1.0)

        val videoInputs = ArrayList<RecordingComposeVideoInput>()
        val audioInputs = ArrayList<RecordingComposeAudioInput>()
        // V1.9.115: which person (by the identity of the track row) a microphone input belongs to -- only for the speaker timeline.
        val speechOwners = ArrayList<Pair<String, RecordingComposeAudioInput>>()
        for (r in resolved) {
            val m = measured.getValue(r.track.id)
            val person = m.person
            if (person != null) {
                val (video, audio) =
                    ParticipantFileInputs.build(
                        file = r.file,
                        trackId = r.track.id.toString(),
                        fileOffsetSeconds = m.offset,
                        m = person,
                    )
                video?.let { videoInputs += it }
                audio?.let {
                    audioInputs += it
                    speechOwners += r.track.participantIdentity to it
                }
            } else if (r.track.trackSource in VIDEO_TRACK_SOURCES) {
                videoInputs +=
                    RecordingComposeVideoInput(
                        file = r.file,
                        offsetSeconds = m.offset,
                        isScreenShare = r.track.trackSource == ConferenceRecordingTrackSource.SCREEN_SHARE,
                        trackId = r.track.id.toString(),
                        ranges = m.ranges?.ranges.orEmpty(),
                        durationSeconds = mediaSeconds(r),
                    )
            } else if (r.track.trackSource in AUDIO_TRACK_SOURCES) {
                val wall =
                    if (r.track.startedAtEpochNanos != null && r.track.endedAtEpochNanos != null) {
                        (r.track.endedAtEpochNanos - r.track.startedAtEpochNanos) / 1_000_000_000.0
                    } else {
                        r.track.durationMs?.let { it / 1000.0 }
                    }
                val audioInput =
                    RecordingComposeAudioInput(
                        file = r.file,
                        offsetSeconds = m.offset,
                        ranges = m.ranges?.ranges.orEmpty(),
                        mediaDurationSeconds = mediaSeconds(r),
                        wallDurationSeconds = wall,
                    )
                audioInputs += audioInput
                // the sound of a screen share is not somebody speaking
                if (r.track.trackSource ==
                    ConferenceRecordingTrackSource.MICROPHONE
                ) {
                    speechOwners += r.track.participantIdentity to audioInput
                }
            }
        }
        // A per-person file whose picture is black throughout contributes no video input; with no picture left anywhere there is nothing to compose.
        if (videoInputs.isEmpty()) return SpecResult.NoVideo
        if (row.broadcastView == ConferenceBroadcastView.SPEAKER) {
            val plan =
                speakerPlan(
                    row = row,
                    tracks = resolved.map { it.track },
                    videoInputs = videoInputs,
                    speechOwners = speechOwners,
                    totalSeconds = outputDurationSeconds,
                )
            if (plan != null) {
                return SpecResult.Ready(
                    RecordingComposeSpec(
                        videoInputs = plan.videoInputs,
                        audioInputs = audioInputs,
                        outputDurationSeconds = outputDurationSeconds,
                        layout = RecordingLayout.SPEAKER,
                        speakerSpans = plan.spans,
                    ),
                )
            }
        }
        return SpecResult.Ready(
            RecordingComposeSpec(
                videoInputs = videoInputs,
                audioInputs = audioInputs,
                outputDurationSeconds = outputDurationSeconds,
            ),
        )
    }

    /** What the speaker layout needs: the video inputs tagged with their person key, and the smoothed spans. */
    private data class SpeakerPlan(
        val videoInputs: List<RecordingComposeVideoInput>,
        val spans: List<RecordingSpeakerSpan>,
    )

    /**
     * V1.9.115 -- the speaker timeline of [row] and the person keys of its pictures, or `null` when there is none and the recording is
     * composed as the plain grid (analysis unavailable or failed -- this NEVER fails the recording).
     *
     * A person is identified by an OPAQUE key: the id of their first track row (the first CAMERA row, else the first MICROPHONE row).
     * The stored timeline references that row and nothing else. Logs carry counts only -- never an identity, a name or a file name.
     * The timeline is analysed once and then read back from the store (a recompute does not analyse again).
     */
    private suspend fun speakerPlan(
        row: RecordingRow,
        tracks: List<TrackRow>,
        videoInputs: List<RecordingComposeVideoInput>,
        speechOwners: List<Pair<String, RecordingComposeAudioInput>>,
        totalSeconds: Double,
    ): SpeakerPlan? {
        try {
            val canonical = HashMap<String, Uuid>()
            val ordered =
                tracks.sortedWith(
                    compareBy<TrackRow> { it.trackSource != ConferenceRecordingTrackSource.CAMERA }
                        .thenBy { it.trackSource != ConferenceRecordingTrackSource.MICROPHONE }
                        .thenBy { it.startedAtEpochNanos ?: Long.MAX_VALUE }
                        .thenBy { it.id },
                )
            for (track in ordered) {
                if (track.trackSource == ConferenceRecordingTrackSource.CAMERA ||
                    track.trackSource == ConferenceRecordingTrackSource.MICROPHONE
                ) {
                    canonical.putIfAbsent(track.participantIdentity, track.id)
                }
            }
            val identityByTrackId = tracks.associate { it.id.toString() to it.participantIdentity }
            val tagged =
                videoInputs.map { input ->
                    val identity = identityByTrackId[input.trackId]
                    val key = if (input.isScreenShare || identity == null) null else canonical[identity]?.toString()
                    input.copy(personKey = key)
                }

            val stored = RecordingSpeakerTimelineStore.load(row.id)
            if (stored != null) {
                logger.info { "RecordingPoller: recording ${row.id} reuses its stored speaker timeline (${stored.size} span(s))" }
                return SpeakerPlan(
                    videoInputs = tagged,
                    spans =
                        stored.map {
                            RecordingSpeakerSpan(
                                personKey = it.trackId.toString(),
                                startSeconds = it.startMs / 1000.0,
                                endSeconds =
                                    it.endMs / 1000.0,
                            )
                        },
                )
            }
            val perPerson = HashMap<String, MutableList<SpeechWindow>>()
            var analyzed = 0
            for ((identity, input) in speechOwners) {
                val key = canonical[identity]?.toString() ?: continue
                val series = speechAnalyzer.levels(file = input.file) ?: continue
                analyzed++
                val tempo = AudioTempo.ratioFor(mediaSeconds = input.mediaDurationSeconds, wallSeconds = input.wallDurationSeconds)
                perPerson.getOrPut(key) { ArrayList() } +=
                    SpeechActivity.detect(series = series, offsetSeconds = input.offsetSeconds, tempo = tempo)
            }
            if (analyzed == 0) {
                logger.warn { "RecordingPoller: no speech analysis possible for recording ${row.id} -- composing the grid" }
                return null
            }
            val spans = RecordingSpeakerTimeline.build(perPerson = perPerson, totalMs = (totalSeconds * 1000.0).toLong())
            RecordingSpeakerTimelineStore.replace(
                recordingId = row.id,
                spans = spans.map { StoredSpeakerSpan(trackId = Uuid.parse(it.key), startMs = it.startMs, endMs = it.endMs) },
            )
            logger.info { "RecordingPoller: speaker timeline of recording ${row.id}: ${spans.size} span(s), ${perPerson.size} person(s)" }
            return SpeakerPlan(
                videoInputs = tagged,
                spans =
                    spans.map {
                        RecordingSpeakerSpan(
                            personKey = it.key,
                            startSeconds = it.startMs / 1000.0,
                            endSeconds =
                                it.endMs / 1000.0,
                        )
                    },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn {
                "RecordingPoller: the speaker timeline of recording ${row.id} failed (${e::class.simpleName ?: "error"}) -- composing the grid"
            }
            return null
        }
    }

    /**
     * V1.9.110 -- measures the video and the audio stream of one per-person file separately (start times, packet ranges, black
     * stretches); everything that cannot be measured stays `null` and [ParticipantFileInputs] degrades to "whole duration" for it.
     */
    private suspend fun measurePersonFile(
        file: File,
        probe: RecordingMediaProbe,
        reportedDurationMs: Long?,
    ): ParticipantStreamMeasurements {
        val times = prober.probeStreamStartTimes(file)
        val hasVideo = times?.hasVideo ?: true
        val hasAudio = times?.hasAudio ?: true
        val videoRanges =
            if (hasVideo) prober.probePacketRanges(file = file, kind = StreamKind.VIDEO, gapThresholdSeconds = VIDEO_GAP_SECONDS) else null
        val audioRanges =
            if (hasAudio) prober.probePacketRanges(file = file, kind = StreamKind.AUDIO, gapThresholdSeconds = AUDIO_GAP_SECONDS) else null
        val blank =
            if (hasVideo) {
                blankDetector.detectBlankRanges(
                    file = file,
                    videoStreamStartSeconds = times?.videoSeconds ?: 0.0,
                    minSeconds = BLANK_MIN_SECONDS,
                )
            } else {
                null
            }
        return ParticipantStreamMeasurements(
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            videoStartSeconds = times?.videoSeconds,
            audioStartSeconds = times?.audioSeconds,
            videoPacketRanges = videoRanges,
            audioPacketRanges = audioRanges,
            blankVideoRanges = blank,
            reportedDurationSeconds = reportedDurationMs?.let { it / 1000.0 } ?: probe.durationSeconds,
        )
    }

    private suspend fun composeOne(row: RecordingRow) {
        val attemptNumber = row.composeAttempts + 1
        transaction {
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq row.id }) { it[composeAttempts] = attemptNumber }
        }

        val hostRawRoot = File(recordingConfig.outputHostDir)
        val spec =
            when (val built = buildSpec(row = row, hostRawRoot = hostRawRoot)) {
                is SpecResult.Ready -> built.spec
                SpecResult.NoTrackStored -> {
                    // ELB-Test-Fix 2026-09-27 (Befund 3): not a composition problem at all -- see
                    // FAILURE_NO_TRACK_STORED. Reached via STOPPING -> PROCESSING when every track row went
                    // terminal without a single COMPLETE among them.
                    logger.warn {
                        "RecordingPoller: no track egress of recording ${row.id} completed -- FAILED (check egress output volume permissions)"
                    }
                    markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.NO_TRACK_STORED)
                    return
                }
                SpecResult.NoVideo -> {
                    logger.warn { "RecordingPoller: no resolvable video track for recording ${row.id} -- FAILED" }
                    markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.COMPOSE_FAILED)
                    return
                }
            }
        val outputDurationSeconds = spec.outputDurationSeconds

        val outputFile = File(hostRawRoot, "${row.rawDir}/composed-$attemptNumber.mp4")
        outputFile.parentFile.mkdirs()
        try {
            composer.compose(spec = spec, outputFile = outputFile)

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
            // Result checked and logged (not silently swallowed): in a Docker deployment where the
            // `egress` container writes the raw files as a different UID than this process runs as,
            // deleteRecursively() can legitimately fail with no exception -- it just returns false --
            // and a silent failure here would leave every raw per-track recording accumulating
            // forever with no visible symptom until the disk fills up.
            if (!recordingConfig.keepRaw) {
                val rawDir = File(hostRawRoot, row.rawDir)
                if (!rawDir.deleteRecursively()) {
                    logger.warn {
                        "RecordingPoller: failed to delete raw files for recording ${row.id} at $rawDir -- check container UID/permissions"
                    }
                }
                // V1.9.115: the speaker timeline lives exactly as long as the raw files it was derived from -- whether or not the
                // file deletion above succeeded (the timeline is useless without a recompute, and recompute needs the raw files).
                RecordingSpeakerTimelineStore.deleteFor(row.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "RecordingPoller: composition/archiving failed for recording ${row.id} (attempt $attemptNumber)" }
            if (attemptNumber >= MAX_COMPOSE_ATTEMPTS) {
                markFailed(recordingId = row.id, code = ConferenceRecordingFailureCode.COMPOSE_FAILED)
            }
            // else: stays PROCESSING, retried on a later tick.
        } finally {
            outputFile.delete()
        }
    }

    // ── RECOMPUTE of a READY recording (V1.9.109) ───────────────────────

    /** `true` iff the worker should recompute [row]; a stale marker (nobody works on it, far older than any composition) is failed on the spot instead. */
    private fun acceptRecompute(row: RecordingRow): Boolean {
        val fence = row.recomputeStartedAt ?: return false
        if (workerRecordingId == row.id) return false
        if (elapsed(from = fence, to = clock()) > recordingConfig.composeTimeoutMinutes.minutes * 2) {
            // Nobody is working on it and it is far older than any composition may take (a crash, or a queue that never drained).
            logger.warn { "RecordingPoller: recompute of recording ${row.id} is stale -- marking it failed" }
            failRecompute(recordingId = row.id, fence = fence)
            return false
        }
        return true
    }

    /**
     * Recomputes the composed file of the READY recording [row] from its raw per-track files and replaces it -- see class KDoc "Recompute".
     * Any failure leaves the previous file untouched and ends in [failRecompute]; the raw files are NEVER deleted here, whatever
     * [ConferenceRecordingConfig.keepRaw] says (that flag guards the first composition only).
     */
    private suspend fun recomputeOne(row: RecordingRow) {
        val fence = row.recomputeStartedAt ?: return
        val documentId = row.documentId
        val hostRawRoot = File(recordingConfig.outputHostDir)
        if (documentId == null) {
            logger.warn { "RecordingPoller: recompute of recording ${row.id} has no archived document -- failing it" }
            failRecompute(recordingId = row.id, fence = fence)
            return
        }

        // The pre-flight check again: the RPC's answer is minutes old, the disk may have filled up since.
        val fileNames =
            transaction {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where {
                        (ConferenceRecordingTrackTable.recordingId eq row.id) and
                            (ConferenceRecordingTrackTable.status eq ConferenceRecordingTrackStatus.COMPLETE)
                    }.map { it[ConferenceRecordingTrackTable.fileName] }
            }
        val rawBytes = RecordingRawFiles.totalBytes(hostRawRoot = hostRawRoot, rawDir = row.rawDir, fileNames = fileNames)
        if (rawBytes == 0L ||
            !RecordingDiskBudget.hasRoom(
                workRoot = hostRawRoot,
                documentRoot = documentStorageRoot,
                rawBytes = rawBytes,
                durationSeconds = row.durationSeconds ?: 0L,
                currentFileBytes = row.fileSizeBytes,
                diskSpace = diskSpace,
            )
        ) {
            logger.warn { "RecordingPoller: recompute of recording ${row.id} refused -- raw files missing or not enough free disk space" }
            failRecompute(recordingId = row.id, fence = fence)
            return
        }

        val rawDirFile = File(hostRawRoot, row.rawDir)
        rawDirFile.listFiles { f -> f.isFile && f.name.startsWith("recompute-") && f.name.endsWith(".part") }?.forEach { it.delete() }
        val partFile = File(rawDirFile, "recompute-${Uuid.random()}.mp4.part")
        try {
            val spec =
                when (val built = buildSpec(row = row, hostRawRoot = hostRawRoot)) {
                    is SpecResult.Ready -> built.spec
                    else -> {
                        logger.warn { "RecordingPoller: recompute of recording ${row.id} found no usable tracks -- failing it" }
                        failRecompute(recordingId = row.id, fence = fence)
                        return
                    }
                }
            composer.compose(spec = spec, outputFile = partFile)

            val now = clock()
            val newVersion =
                archiveNewVersionOfFile(
                    storageRoot = documentStorageRoot,
                    documentId = documentId,
                    sourceFile = partFile,
                    fileName = "aufzeichnung-${row.id}.mp4",
                    mimeType = "video/mp4",
                    uploadedBy = row.startedByMemberId,
                    changeNote = "Neu berechnet (V1.9.109)",
                    fence = { _, sizeBytes ->
                        // The fencing update: only the request this worker belongs to may finish. A deletion, a newer request or a
                        // failure marker in between makes it match nothing -> the whole version switch rolls back.
                        ConferenceRecordingTable.update({ fencedRecompute(recordingId = row.id, fence = fence) }) {
                            it[fileSizeBytes] = sizeBytes
                            it[durationSeconds] = spec.outputDurationSeconds.toLong()
                            it[recomputedAt] = now
                            it[recomputeStartedAt] = null
                            it[lastRecomputeFailureCode] = null
                        } > 0
                    },
                    finalize = {
                        // System-initiated, no acting member. LAST lock-taking operation -- see AuditLogRecorder KDoc.
                        AuditLogRecorder.record(
                            actorMemberId = null,
                            actorRole = null,
                            entityType = AuditEntityType.CONFERENCE_RECORDING,
                            entityId = row.id,
                            action = AuditAction.UPDATE,
                            before = "status=READY",
                            after = "recompute completed",
                            occurredAt = now,
                        )
                    },
                )
            if (newVersion == null) {
                logger.warn {
                    "RecordingPoller: recompute of recording ${row.id} was superseded (deleted, replaced or its document is gone)"
                }
                failRecompute(recordingId = row.id, fence = fence)
            } else {
                logger.info { "RecordingPoller: recompute of recording ${row.id} finished (new document version $newVersion)" }
            }
        } catch (e: CancellationException) {
            // Shutdown: the marker stays, the next boot starts the recompute again.
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "RecordingPoller: recompute of recording ${row.id} failed" }
            failRecompute(recordingId = row.id, fence = fence)
        } finally {
            withContext(NonCancellable) { partFile.delete() }
        }
    }

    /** The row filter of every fenced recompute write: READY and still carrying exactly the marker this recompute was started with. */
    private fun fencedRecompute(
        recordingId: Uuid,
        fence: LocalDateTime,
    ): Op<Boolean> =
        (ConferenceRecordingTable.id eq recordingId) and
            (ConferenceRecordingTable.status eq ConferenceRecordingStatus.READY) and
            (ConferenceRecordingTable.recomputeStartedAt eq fence)

    /**
     * Ends the recompute marked by [fence] as failed: remembers the code, clears the marker, audits. A fenced update -- if the recording
     * was deleted or a newer recompute replaced the marker meanwhile this matches nothing and writes nothing. The previous file is not
     * touched in any case.
     */
    private fun failRecompute(
        recordingId: Uuid,
        fence: LocalDateTime,
    ) {
        val now = clock()
        transaction {
            val updated =
                ConferenceRecordingTable.update({ fencedRecompute(recordingId = recordingId, fence = fence) }) {
                    it[recomputeStartedAt] = null
                    it[lastRecomputeFailureCode] = ConferenceRecordingFailureCode.COMPOSE_FAILED
                }
            if (updated > 0) {
                // System-initiated, no acting member. LAST lock-taking operation -- see AuditLogRecorder KDoc.
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.CONFERENCE_RECORDING,
                    entityId = recordingId,
                    action = AuditAction.UPDATE,
                    before = "status=READY",
                    after = "recompute failed (COMPOSE_FAILED)",
                    occurredAt = now,
                )
            }
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
        code: ConferenceRecordingFailureCode,
    ) {
        val reason = FAILURE_TEXT_BY_CODE.getValue(code)
        transaction {
            // Only a non-terminal row may fail: a stale snapshot must never overwrite a READY/FAILED row the worker just wrote.
            ConferenceRecordingTable.update({
                (ConferenceRecordingTable.id eq recordingId) and (ConferenceRecordingTable.status inList NON_TERMINAL_STATUSES)
            }) {
                it[status] = ConferenceRecordingStatus.FAILED
                it[failureReason] = reason
                it[failureCode] = code
            }
        }
        logger.warn { "RecordingPoller: recording $recordingId marked FAILED: $reason" }
    }

    private fun elapsed(
        from: LocalDateTime,
        to: LocalDateTime,
    ) = to.toInstant(TZ) - from.toInstant(TZ)

    /** Log formatting only -- [java.util.Locale.ROOT] so a German default locale never prints `1,120`. */
    private fun formatSeconds(seconds: Double): String = String.format(java.util.Locale.ROOT, "%.3f", seconds)
}

private val TZ = ServerClock.zone

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
    val documentId: Uuid?,
    val durationSeconds: Long?,
    val fileSizeBytes: Long?,
    val recomputeStartedAt: LocalDateTime?,
    val egressMode: ConferenceRecordingEgressMode,
    /** V1.9.115: the room's view frozen when the recording started. */
    val broadcastView: ConferenceBroadcastView,
)

/** Plain data snapshot of a `conference_recording_track` row -- same "never a live ResultRow across suspension" reasoning as [RecordingRow]. */
private data class TrackRow(
    val id: Uuid,
    val egressId: String,
    val livekitTrackId: String,
    val egressKind: ConferenceRecordingEgressMode,
    /** V1.9.115: only used to group a person's tracks; never logged, never stored anywhere but the track row itself. */
    val participantIdentity: String,
    val status: ConferenceRecordingTrackStatus,
    val trackSource: ConferenceRecordingTrackSource,
    val fileName: String?,
    val startedAtEpochNanos: Long?,
    val endedAtEpochNanos: Long?,
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
        documentId = this[ConferenceRecordingTable.documentId],
        durationSeconds = this[ConferenceRecordingTable.durationSeconds],
        fileSizeBytes = this[ConferenceRecordingTable.fileSizeBytes],
        recomputeStartedAt = this[ConferenceRecordingTable.recomputeStartedAt],
        egressMode = this[ConferenceRecordingTable.egressMode],
        broadcastView = this[ConferenceRecordingTable.broadcastView],
    )

private fun ResultRow.toTrackRow() =
    TrackRow(
        id = this[ConferenceRecordingTrackTable.id],
        egressId = this[ConferenceRecordingTrackTable.egressId],
        livekitTrackId = this[ConferenceRecordingTrackTable.livekitTrackId],
        egressKind = this[ConferenceRecordingTrackTable.egressKind],
        participantIdentity = this[ConferenceRecordingTrackTable.participantIdentity],
        status = this[ConferenceRecordingTrackTable.status],
        trackSource = this[ConferenceRecordingTrackTable.trackSource],
        fileName = this[ConferenceRecordingTrackTable.fileName],
        startedAtEpochNanos = this[ConferenceRecordingTrackTable.startedAtEpochNanos],
        endedAtEpochNanos = this[ConferenceRecordingTrackTable.endedAtEpochNanos],
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
