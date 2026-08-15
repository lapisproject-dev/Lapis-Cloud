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
import network.lapis.cloud.server.rpc.ConferenceStreamPauseCoordinator
import network.lapis.cloud.server.rpc.SecretBallotStreamLock
import network.lapis.cloud.server.rpc.restartEgressForStream
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
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
        // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen".
        ConferenceStreamStatus.PAUSING,
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
 *   (a crash between that method's two transactions, OR [ConferenceStreamingService.stopStream]'s own
 *   confirmation loop timing out -- security-audit MAJOR-1/MINOR-6 fix) -- if [maxDurationMinutes] has
 *   elapsed since the ORIGINAL `started_at` (security-audit-round-2 F4 fix -- same ceiling
 *   [handlePausing]/[handlePaused] already enforce for their own states), this row is force-finalized to
 *   `ENDED` with a loud ERROR log, since [SecretBallotStreamLock.requireStreamQuiescedForBallot] also
 *   fail-closes on `STOPPING` and must not be allowed to block ballot casting forever with no operator
 *   escape hatch. Otherwise, if an `egress_id` is on the row: best-effort `StopEgress`, then `ListEgress`
 *   to CONFIRM it actually stopped (mirrors [handlePausing]'s own shape one-for-one), retrying on the
 *   next tick if not yet confirmed; `ENDED` only once confirmed (or immediately if there was never an
 *   `egress_id` to begin with). Before the MAJOR-1/MINOR-6 fix, `STOPPING` finalized to `ENDED`
 *   unconditionally on the very first tick with no confirmation at all -- which meant
 *   [SecretBallotStreamLock.requireStreamQuiescedForBallot]'s fail-closed gate had nothing left to block
 *   a secret ballot on the instant this row said `ENDED`, even though the egress it just asked to stop
 *   might still be publishing. Security-audit-round-2 F1 fix: [ConferenceStreamingService.startStream]'s/
 *   `restartEgressForStream`'s own "abandoned" branches can likewise resurrect an already-`ENDED` row
 *   back to `STOPPING` (with a freshly-recorded `egress_id`) if their own LiveKit call lands AFTER a
 *   racing `stopStream` had already finalized the row -- this branch is what picks that resurrection back
 *   up on the very next tick.
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
            // STARTING is deliberately NOT routed through reconcileMissedSecretBallotPause --
            // handleStarting already re-checks SecretBallotStreamLock.hasOpenSecretBallot itself, in
            // the SAME forUpdate()-locked transaction that adopts (or fails to adopt) an orphaned
            // egress, so a blind early PAUSING claim here would only throw away the egress-id
            // discovery handleStarting's own adoption logic performs -- see that method's own KDoc.
            ConferenceStreamStatus.STARTING -> handleStarting(row, roomName, now)
            ConferenceStreamStatus.LIVE -> {
                if (!reconcileMissedSecretBallotPause(row)) handleLive(row, roomName, now)
            }
            ConferenceStreamStatus.PAUSING -> handlePausing(row, roomName, now)
            ConferenceStreamStatus.PAUSED -> handlePaused(row, now)
            ConferenceStreamStatus.STOPPING -> handleStopping(row, roomName, now)
            else -> Unit
        }
    }

    // ── Security-audit MINOR-5 fix ──────────────────────────────────────

    /**
     * Closes the race between `ConferenceService.setRoomMeeting`'s "hin-binden" (bind) and
     * `ElectionService.openVoting`/`SystemicConsensusService.freezeOptions`'s own room-lock sweep:
     * both transactions lock/read [network.lapis.cloud.server.db.generated.ConferenceRoomTable] rows
     * that exist at the time each runs, but `openVoting`/`freezeOptions` can only pause the streams of
     * rooms it can actually SEE bound to the meeting at that instant
     * ([SecretBallotStreamLock.roomIdsForMeeting]). If a room gets bound to that SAME meeting in a
     * transaction that commits AFTER that snapshot was taken but with a `hasOpenSecretBallotForMeeting`
     * pre-check that ran BEFORE the ballot opened, the room's `STARTING`/`LIVE` stream never gets
     * flipped to `PAUSING` by `ConferenceStreamPauseCoordinator.markPausingForSecretBallot` at all --
     * `setRoomMeeting`'s own pre-check closes the OTHER interleaving (ballot opens first, bind second)
     * but not this one, since the two transactions never take a shared lock. This per-tick sweep is
     * the belt-and-braces net that catches it: a `LIVE` stream whose room is (now) covered by
     * [SecretBallotStreamLock.hasOpenSecretBallot] gets the exact same treatment
     * `ConferenceStreamPauseCoordinator.markPausingForSecretBallot` would have given it at ballot-open
     * time, just on the next poll tick instead of synchronously -- reusing that SAME atomic
     * per-row-claim function (`actorMemberId = null`/`actorRole = null`, a system-initiated pause,
     * same convention every other automatic transition in this class already uses).
     *
     * Only ever called for [ConferenceStreamStatus.LIVE] -- NOT [ConferenceStreamStatus.STARTING].
     * [handleStarting]'s own orphan-adoption transaction DOES re-check `hasOpenSecretBallot` itself
     * (see that method's own KDoc), letting it record the discovered egress id in the SAME write
     * rather than this function's blind "just pause it, no egress id known yet" claim -- but,
     * security-audit-round-2 L2 correction, that re-check is NOT synchronized against
     * `ConferenceService.setRoomMeeting`'s own "hin-binden" transaction the way this KDoc's own
     * paragraphs above might suggest: [handleStarting] locks only `conference_stream` (`forUpdate()`
     * on the STREAM row, to serialize against a concurrent
     * `ConferenceStreamPauseCoordinator.markPausingForSecretBallot`), while `setRoomMeeting` locks
     * only `conference_room` -- the two transactions share no lock at all, exactly the same "no shared
     * lock" gap this KDoc's own opening paragraph describes for `openVoting`/`freezeOptions`. Why the
     * STARTING window is still safe without one is a DIFFERENT, two-part argument: (1)
     * [SecretBallotStreamLock.requireStreamQuiescedForBallot] fail-closes ballot casting for the
     * ENTIRE `STARTING` window regardless of whether [handleStarting]'s own re-check has "seen" the
     * ballot yet -- a vote simply cannot be cast while the row is `STARTING`, race or not; and (2) IF
     * [handleStarting]'s own re-check loses that race and wrongly adopts the orphan straight to `LIVE`,
     * THIS function -- which IS called for `LIVE`, on the very next poll tick -- catches and corrects
     * it then, for exactly the race this KDoc describes. [handleLive], unlike [handleStarting], has NO
     * ballot-awareness of its own at all -- this function is what supplies it.
     *
     * Returns `true` iff [row] was (successfully) claimed and flipped to `PAUSING` this call -- the
     * caller must then skip its own normal `LIVE` handling for this tick, since the row's status just
     * changed out from under it.
     */
    private fun reconcileMissedSecretBallotPause(row: StreamRow): Boolean {
        val hasOpenSecretBallot = transaction { SecretBallotStreamLock.hasOpenSecretBallot(row.roomId) }
        if (!hasOpenSecretBallot) return false
        val affected =
            transaction {
                ConferenceStreamPauseCoordinator.markPausingForSecretBallot(
                    roomIds = listOf(row.roomId),
                    actorMemberId = null,
                    actorRole = null,
                )
            }
        if (affected.isNotEmpty()) {
            logger.info { "StreamPoller: reconciling orphaned bind-after-open race for stream ${row.id} -- PAUSING" }
        }
        return affected.isNotEmpty()
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
            // confirmedEgressId = null -- this row never had an egress id to begin with (the guard at
            // this method's very top already returned early if it did), see markFailed KDoc.
            markFailed(row.id, FAILURE_TIMEOUT, confirmedEgressId = null)
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
                // V1.0 Videokonferenzen, Wave 9, review-round R6 fix -- forUpdate() lock +
                // SecretBallotStreamLock.hasOpenSecretBallot re-check, mirroring handlePaused's own
                // SECRET_BALLOT guard and ConferenceStreamingService.startStream's/
                // restartEgressForStream's own Tx2 D3/§6.3 re-check: the ListEgress call above ran
                // OUTSIDE any transaction, so a secret ballot may have opened
                // (ConferenceStreamPauseCoordinator.markPausingForSecretBallot) WHILE it was in
                // flight -- without this re-check, the unconditional `status = LIVE` write below
                // could stomp that PAUSING write straight back to LIVE. .forUpdate() here also locks
                // this row for the rest of the transaction, serializing against a concurrent
                // markPausingForSecretBallot's own per-row write on the SAME stream (see that
                // function's own KDoc) -- either this transaction observes the ballot BEFORE that
                // write lands (and closes the gap itself, below), or it blocks on the row lock until
                // that write commits and then observes the ballot fresh, writing the identical
                // PAUSING/SECRET_BALLOT outcome. livekit_egress_id is ALWAYS written on adoption
                // regardless -- never leak a real, running egress by failing to record its id.
                ConferenceStreamTable
                    .selectAll()
                    .where { ConferenceStreamTable.id eq row.id }
                    .forUpdate()
                    .singleOrNull()
                val hasOpenSecretBallot = SecretBallotStreamLock.hasOpenSecretBallot(row.roomId)
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq row.id }) {
                    it[livekitEgressId] = adopted.egressId
                    if (hasOpenSecretBallot) {
                        it[status] = ConferenceStreamStatus.PAUSING
                        it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                    } else {
                        it[status] = ConferenceStreamStatus.LIVE
                    }
                }
            }
        } else {
            // confirmedEgressId = null -- same reasoning as the fingerprints.isEmpty() branch above:
            // this row never had an egress id recorded, see markFailed KDoc.
            markFailed(row.id, FAILURE_TIMEOUT, confirmedEgressId = null)
        }
    }

    // ── PAUSING -- Wave 9 "Stream-Pause bei geheimen Abstimmungen": StopEgress requested, not yet
    // confirmed terminal (belt-and-braces retry on top of DefaultSecretBallotStreamGuard's own,
    // faster confirmation loop -- see that class KDoc "Quiescing algorithm" step 4: this is exactly
    // the retry-on-next-tick path that KDoc promises when the guard's own confirmation loop timed
    // out) ─────────────────────────────────────────────────────────────

    private suspend fun handlePausing(
        row: StreamRow,
        roomName: String,
        now: LocalDateTime,
    ) {
        if (elapsed(row.startedAt, now) >= streamingConfig.maxDurationMinutes.minutes) {
            // Same ceiling handlePaused enforces for an already-PAUSED row -- a PAUSING row must not
            // be able to hold the room's one-active-stream-per-room slot open indefinitely either.
            finalizeEnded(row.id, now)
            logger.info { "StreamPoller: auto-stopped PAUSING stream ${row.id} (max duration elapsed)" }
            return
        }
        val egressId = row.livekitEgressId
        if (egressId == null) {
            // ConferenceStreamPauseCoordinator.markPausingForSecretBallot can flip a STARTING row to
            // PAUSING before startStream's own second transaction has ever recorded an egress id --
            // mirror handleStarting's own orphan-adoption logic: wait out startupTimeoutSeconds, then
            // either adopt a matching egress (and immediately try to stop it, since the entire point
            // of PAUSING is "no longer publish") or, if none is found, the room genuinely never
            // started publishing -- go straight to PAUSED, nothing left to stop.
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
                // Confirmed: no target rows to ever match against, i.e. this row's own egress id is
                // (still) null -- see markPaused KDoc "confirmedEgressId".
                markPaused(row.id, now, confirmedEgressId = null)
                return
            }
            val egresses =
                try {
                    liveKitEgressClient.listEgress(roomName)
                } catch (e: LiveKitAdminException) {
                    logger.warn { "StreamPoller: ListEgress failed while reconciling orphan PAUSING stream ${row.id}: ${e.message}" }
                    return
                }
            val adopted = egresses.firstOrNull { info -> info.streamResults.any { it.url in fingerprints } }
            if (adopted == null) {
                // Confirmed via ListEgress: nothing matching this row's fingerprints is running --
                // this row's own egress id is (still) null, see markPaused KDoc "confirmedEgressId".
                markPaused(row.id, now, confirmedEgressId = null)
                return
            }
            try {
                liveKitEgressClient.stopEgress(roomName, adopted.egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn {
                    "StreamPoller: StopEgress failed for adopted orphan PAUSING egress ${adopted.egressId}, stream ${row.id}: " +
                        e.message
                }
            }
            transaction {
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq row.id }) { it[livekitEgressId] = adopted.egressId }
            }
            return
        }

        try {
            liveKitEgressClient.stopEgress(roomName, egressId)
        } catch (e: LiveKitAdminException) {
            logger.warn { "StreamPoller: StopEgress failed while confirming PAUSING stream ${row.id}: ${e.message}" }
        }
        val egresses =
            try {
                liveKitEgressClient.listEgress(roomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: ListEgress failed while confirming PAUSING stream ${row.id}: ${e.message}" }
                return
            }
        val info = egresses.firstOrNull { it.egressId == egressId }
        if (info == null || info.status in TERMINAL_EGRESS_STATUSES) {
            // Confirmed via ListEgress: THIS specific egressId is gone/terminal.
            markPaused(row.id, now, confirmedEgressId = egressId)
        }
        // else still (possibly) publishing -- stay PAUSING, the next tick retries.
    }

    /**
     * Security-audit round-3 NEU-2 fix -- [confirmedEgressId] is the egress id whose stop/absence THIS
     * call just confirmed (`null` if the caller confirmed there was never one to begin with, i.e.
     * [handlePausing]'s own two orphan-reconciliation branches; the id itself if [handlePausing]'s main
     * branch confirmed via `ListEgress` that THAT specific id is gone/terminal). The finalizing PAUSED
     * write only fires if the row's CURRENT `livekitEgressId` still matches (or is still null) --
     * mirrors [ConferenceStreamingService.stopStream]'s own Tx3 fix one-for-one (see that method's own
     * KDoc for the full race): without this guard, a concurrent `startStream`/`restartEgressForStream`
     * "abandoned" branch that resurrected this row onto a FRESH, actually-publishing egress in the
     * window between that confirmation and this write would have that fresh egress silently overwritten
     * with `PAUSED` -- a terminal-for-polling-purposes status neither this poller nor
     * `SecretBallotStreamGuard` ever revisits, stranding the fresh egress running forever. If the
     * predicate does not match, the write is skipped entirely -- the row is left exactly as the
     * resurrection wrote it, still under [NON_TERMINAL_STREAM_STATUSES]' sweep on the very next tick.
     */
    private fun markPaused(
        streamId: Uuid,
        now: LocalDateTime,
        confirmedEgressId: String?,
    ) {
        transaction {
            val current =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull() ?: return@transaction
            if (current[ConferenceStreamTable.status] != ConferenceStreamStatus.PAUSING) return@transaction
            val updated =
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamId) and
                        (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSING) and
                        (
                            ConferenceStreamTable.livekitEgressId.isNull() or
                                (ConferenceStreamTable.livekitEgressId eq confirmedEgressId)
                        )
                }) {
                    it[status] = ConferenceStreamStatus.PAUSED
                    it[pausedAt] = now
                    // pauseReason left untouched -- already SECRET_BALLOT (or, in principle, MANUAL if a
                    // moderator escalated it mid-PAUSING via ConferenceStreamingService.pauseStream),
                    // written by whoever moved this row into PAUSING in the first place.
                }
            if (updated == 0) {
                logger.warn {
                    "StreamPoller: stream $streamId was resurrected with a new egress id while its stop was " +
                        "being confirmed -- NOT marking PAUSED, leaving it for the next tick"
                }
                return@transaction
            }
            AuditLogRecorder.record(
                actorMemberId = null,
                actorRole = null,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            logger.info { "StreamPoller: confirmed stream $streamId quiesced -- PAUSED" }
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
            // confirmedEgressId = egressId -- this IS the specific id whose vanishing this ListEgress
            // call just observed, see markFailed KDoc.
            markFailed(row.id, FAILURE_GENERIC, confirmedEgressId = egressId)
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
            // "StreamPoller drives LIVE -> FAILED"). confirmedEgressId = egressId -- this IS the
            // specific id whose terminal status this ListEgress call just observed, see markFailed KDoc.
            markFailed(row.id, sanitizeLiveKitError(info.error), confirmedEgressId = egressId)
        }
    }

    // ── PAUSED ───────────────────────────────────────────────────────────

    private suspend fun handlePaused(
        row: StreamRow,
        now: LocalDateTime,
    ) {
        // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- crash-recovery
        // reconciliation, MUST run before the max-duration check below: a stream that is actually
        // ready to auto-resume must never instead be auto-ended for having been paused "too long",
        // merely because the resume it was owed got lost to a crash between
        // ElectionService.closeVoting/SystemicConsensusService.closeRating's own transaction commit
        // and DefaultSecretBallotStreamGuard.resumeStreamsForMeeting's call (the window this
        // reconciliation exists to close -- see restartEgressForStream KDoc). Runs on every tick
        // (folded into this existing per-row PAUSED dispatch rather than a second top-level query,
        // since it needs exactly the same PAUSED rows NON_TERMINAL_STREAM_STATUSES/tick() already
        // fetch).
        if (row.pauseReason == ConferenceStreamPauseReason.SECRET_BALLOT) {
            // SecretBallotStreamLock.hasOpenSecretBallot runs a plain Exposed DSL query with no
            // transaction { } of its own (same "caller locks/wraps" contract every other call site
            // -- ConferenceStreamingService.resumeStream, restartEgressForStream,
            // SecretBallotStreamGuard.resumeCandidatesForMeeting -- already follows). Calling it bare
            // here throws "No transaction in context" on every invocation, silently swallowed by
            // tick()'s own per-row catch -- so this entire SECRET_BALLOT branch never actually ran.
            val hasOpenSecretBallot = transaction { SecretBallotStreamLock.hasOpenSecretBallot(row.roomId) }
            if (!hasOpenSecretBallot) {
                logger.info { "StreamPoller: reconciling orphaned SECRET_BALLOT pause for stream ${row.id} -- auto-resuming" }
                restartEgressForStream(row.id, liveKitEgressClient, streamingConfig, actorMemberId = null, actorRole = null)
                return
            }
            // Stolperfalle §9.2, real pre-existing bug fixed in this wave: without this early return,
            // a secret ballot that legitimately outlasts maxDurationMinutes (e.g. a late
            // Vorstandswahl at the end of an eight-hour Mitgliederversammlung) would have this stream
            // hard-ENDED mid-pause, and the eventual Auto-Resume would then find nothing left to
            // resume. The pause was not caused by the moderator, nor by the meeting simply running
            // long on its own -- it must not cost the meeting its stream. The max-duration ceiling is
            // therefore suspended entirely for as long as the ballot that caused this pause stays
            // open; ConferenceService.endRoom/the room-ended safety net in handleStream above still
            // collects the stream regardless of this suspension.
            return
        }
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
        // Security-audit-round-2 F4 fix -- STOPPING previously had NO upper bound at all, unlike
        // handlePausing's own maxDurationMinutes escalation just above. If the egress never reports a
        // terminal ListEgress status (or ListEgress itself keeps throwing, or -- security-audit-round-2
        // F1 fix -- the row keeps getting resurrected from ENDED back to STOPPING by a racing
        // startStream/restartEgressForStream landing late), this row would sit STOPPING forever. Since
        // SecretBallotStreamLock.requireStreamQuiescedForBallot fail-closes on STOPPING (see that
        // method's own KDoc), that would block secret-ballot casting for the entire meeting
        // indefinitely, with no operator escape hatch. Same ceiling/semantics as handlePausing's own
        // escalation -- ERROR- not WARN-logged, since this IS the fail-closed state finally giving up
        // rather than a routine auto-stop, and an operator must be able to spot it at a glance in the
        // logs.
        if (elapsed(row.startedAt, now) >= streamingConfig.maxDurationMinutes.minutes) {
            logger.error {
                "StreamPoller: force-finalizing STOPPING stream ${row.id} to ENDED after exceeding max duration -- " +
                    "StopEgress confirmation never landed (egress id: ${row.livekitEgressId ?: "never recorded"})"
            }
            finalizeEnded(row.id, now)
            return
        }

        val egressId = row.livekitEgressId
        if (egressId == null) {
            // Nothing was ever recorded as running for this row -- see class KDoc "STOPPING". No
            // egress to confirm, finalize directly -- but still guarded (confirmedEgressId = null): see
            // finalizeEndedConfirmed KDoc.
            finalizeEndedConfirmed(row.id, now, confirmedEgressId = null)
            return
        }
        // Security-audit MAJOR-1/MINOR-6 fix -- StopEgress alone is a REQUEST, not a confirmation
        // (same discipline handlePausing already applies): this branch used to finalize to ENDED
        // immediately after requesting StopEgress, with no confirmation that the egress actually
        // stopped publishing. requireStreamQuiescedForBallot now also fail-closes on STOPPING (see
        // SecretBallotStreamLock KDoc), so this retry-until-confirmed loop is what actually makes
        // that guarantee true -- mirrors handlePausing's own StopEgress-then-ListEgress-confirm shape
        // one-for-one, just for the STOPPING state instead of PAUSING.
        try {
            liveKitEgressClient.stopEgress(roomName, egressId)
        } catch (e: LiveKitAdminException) {
            logger.warn { "StreamPoller: StopEgress failed while completing STOPPING stream ${row.id}: ${e.message}" }
        }
        val egresses =
            try {
                liveKitEgressClient.listEgress(roomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: ListEgress failed while confirming STOPPING stream ${row.id}: ${e.message}" }
                return
            }
        val info = egresses.firstOrNull { it.egressId == egressId }
        if (info == null || info.status in TERMINAL_EGRESS_STATUSES) {
            // Confirmed via ListEgress: THIS specific egressId is gone/terminal.
            finalizeEndedConfirmed(row.id, now, confirmedEgressId = egressId)
        }
        // else still (possibly) publishing -- stay STOPPING, the next tick retries.
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

    /**
     * Unconditional finalize -- no egress was ever confirmed stopped by the caller (room no longer
     * exists, room ended with no LiveKit interaction attempted, [autoStop]'s best-effort
     * single-shot `StopEgress`, or [handleStopping]'s own max-duration force-finalize escalation)
     * -- see [finalizeEndedConfirmed] KDoc for the guarded sibling used after a genuine
     * `ListEgress`-based confirmation.
     */
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

    /**
     * Security-audit round-3 NEU-2 fix -- [handleStopping]'s own guarded finalize, used ONLY after
     * this poller has actually confirmed (via `ListEgress`) that [confirmedEgressId] is gone/terminal,
     * or that this row never had an egress id to begin with (`confirmedEgressId = null`). Mirrors
     * [ConferenceStreamingService.stopStream]'s own Tx3 fix and [markPaused] one-for-one (see either
     * KDoc for the full race this closes): the finalizing `ENDED` write only fires if the row's
     * CURRENT `livekitEgressId` still matches (or is still null) -- otherwise a concurrent
     * `startStream`/`restartEgressForStream` "abandoned" branch already resurrected this row onto a
     * FRESH, actually-publishing egress in the window between the confirmation above and this write,
     * and this write is skipped entirely so that fresh egress stays under
     * [NON_TERMINAL_STREAM_STATUSES]' sweep instead of being silently stranded behind a terminal
     * `ENDED` row. [finalizeEnded] (no confirmation to guard) remains the right choice for every OTHER
     * call site -- see that function's own KDoc.
     */
    private fun finalizeEndedConfirmed(
        streamId: Uuid,
        now: LocalDateTime,
        confirmedEgressId: String?,
    ) {
        transaction {
            val current =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull() ?: return@transaction
            if (current[ConferenceStreamTable.status] !in NON_TERMINAL_STREAM_STATUSES) return@transaction
            val updated =
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamId) and
                        (ConferenceStreamTable.status inList NON_TERMINAL_STREAM_STATUSES) and
                        (
                            ConferenceStreamTable.livekitEgressId.isNull() or
                                (ConferenceStreamTable.livekitEgressId eq confirmedEgressId)
                        )
                }) {
                    it[status] = ConferenceStreamStatus.ENDED
                    it[endedAt] = now
                }
            if (updated == 0) {
                logger.warn {
                    "StreamPoller: stream $streamId was resurrected with a new egress id while its stop was " +
                        "being confirmed -- NOT finalizing to ENDED, leaving it for the next tick"
                }
                return@transaction
            }
            ConferenceStreamTargetTable.update({
                (ConferenceStreamTargetTable.streamId eq streamId) and
                    (ConferenceStreamTargetTable.status inList NON_TERMINAL_TARGET_STATUSES)
            }) {
                it[status] = ConferenceStreamTargetStatus.FINISHED
            }
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

    /**
     * Security-audit round-6 R6-1 fix -- [confirmedEgressId] is the egress id whose vanishing/terminal
     * status THIS call just observed via `ListEgress` (`null` for [handleStarting]'s two orphan
     * branches, which never had an egress id to begin with; the row's OWN [StreamRow.livekitEgressId]
     * for [handleLive]'s two branches, which always DO have one -- see call sites). Mirrors
     * [finalizeEndedConfirmed]/[markPaused] one-for-one (see either KDoc for the full race): the
     * finalizing `FAILED` write only fires if the row's CURRENT `livekitEgressId` still matches (or is
     * still null) -- [markFailed] was, before this fix, the ONE remaining unconditional sibling of
     * those two functions, still writing `FAILED` purely off `streamId` with no egress-id predicate at
     * all. Without this guard, a concurrent `startStream`/`restartEgressForStream` "abandoned" branch
     * that resurrected this row onto a FRESH, actually-publishing egress in the window between THIS
     * call's own `ListEgress` observation and this write would have that fresh egress silently
     * overwritten with `FAILED` -- a terminal status outside BOTH [NON_TERMINAL_STREAM_STATUSES] (the
     * poller never revisits it) AND [SecretBallotStreamLock.requireStreamQuiescedForBallot]'s
     * quiesced-allowlist (a secret ballot could be cast immediately), stranding the fresh egress
     * running unobserved and unprotected. If the predicate does not match, the write is skipped
     * entirely -- the row is left exactly as the resurrection wrote it, still under
     * [NON_TERMINAL_STREAM_STATUSES]' sweep on the very next tick.
     */
    private fun markFailed(
        streamId: Uuid,
        reason: String,
        confirmedEgressId: String?,
    ) {
        transaction {
            val current =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull() ?: return@transaction
            if (current[ConferenceStreamTable.status] !in NON_TERMINAL_STREAM_STATUSES) return@transaction
            val updated =
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamId) and
                        (ConferenceStreamTable.status inList NON_TERMINAL_STREAM_STATUSES) and
                        (
                            ConferenceStreamTable.livekitEgressId.isNull() or
                                (ConferenceStreamTable.livekitEgressId eq confirmedEgressId)
                        )
                }) {
                    it[status] = ConferenceStreamStatus.FAILED
                    it[failureReason] = reason
                }
            if (updated == 0) {
                logger.warn {
                    "StreamPoller: stream $streamId was resurrected with a new egress id while its failure was " +
                        "being confirmed -- NOT marking FAILED, leaving it for the next tick"
                }
                return@transaction
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
    /** V1.0 Videokonferenzen, Wave 9 -- see [handlePaused]/[handlePausing]. `null` unless [status] is [ConferenceStreamStatus.PAUSING]/[ConferenceStreamStatus.PAUSED]. */
    val pauseReason: ConferenceStreamPauseReason?,
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
        pauseReason = this[ConferenceStreamTable.pauseReason],
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
