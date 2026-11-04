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
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.pausefeed.PauseFeedControl
import network.lapis.cloud.server.conference.pausefeed.PauseFeedWishes
import network.lapis.cloud.server.conference.pausefeed.PauseFeeds
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.rpc.ConferenceStreamPauseCoordinator
import network.lapis.cloud.server.rpc.SecretBallotStreamLock
import network.lapis.cloud.server.rpc.restartEgressForStream
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.atomic.AtomicInteger
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
    /** V1.9.255 -- the pause feed; this poller reconciles it (starts missing feeds, stops those no longer wanted) at the end of every tick. */
    private val pauseFeed: PauseFeedControl = PauseFeeds.current,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private val secretBox: SecretBox? = streamingConfig.secretEncryptionKey?.let { SecretBox(it) }

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
        // V1.9.275 -- `UpdateStream` calls claimed in THIS tick (cap: [StreamTargetRetryPolicy.MAX_ISSUES_PER_TICK]).
        // Local to the call, so overlapping ticks cannot reset each other's count.
        val issuesThisTick = AtomicInteger(0)
        // V1.9.274 safety net (also covers the first tick after a server start): no broadcast key survives on a stream that already ended.
        try {
            val cleared = transaction { BroadcastKeys.clearForTerminalStreams() }
            if (cleared > 0) logger.warn { "StreamPoller: cleared $cleared leftover broadcast key(s) of ended streams" }
        } catch (e: Throwable) {
            logger.warn { "StreamPoller: clearing leftover broadcast keys failed (${e::class.simpleName})" }
        }
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
                    handleStream(row = row, issuesThisTick = issuesThisTick)
                } catch (e: Throwable) {
                    logger.warn(e) { "StreamPoller: tick failed for stream ${row.id} (status ${row.status})" }
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "StreamPoller: tick failed" }
        }
        reconcilePauseFeeds()
    }

    /**
     * V1.9.255 -- the pause feed follows the database: every stream that is `PAUSED` for the room pause (room still paused, not ended) gets a
     * feed (idempotent; this also covers a server restart, a missed confirmation and a secret ballot that begins inside a room pause), every
     * feed of a stream that is no longer wanted (resumed, stopped, ended, failed) is stopped. Never throws: the feed is a nicety, the
     * stream state machine above must not depend on it.
     */
    private suspend fun reconcilePauseFeeds() {
        try {
            val wanted = transaction { PauseFeedWishes.wantedStreamIds() }
            pauseFeed.reconcile(wanted)
            for (id in wanted) pauseFeed.ensureRunning(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "StreamPoller: pause feed reconcile failed" }
        }
    }

    private suspend fun handleStream(
        row: StreamRow,
        issuesThisTick: AtomicInteger,
    ) {
        val now = clock()
        val roomRow =
            transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row.roomId }.singleOrNull() }
        if (roomRow == null) {
            logger.warn { "StreamPoller: room ${row.roomId} for stream ${row.id} no longer exists -- finalizing" }
            finalizeEnded(streamId = row.id, now = now)
            return
        }
        val roomName = roomRow[ConferenceRoomTable.livekitRoomName]

        // Belt-and-braces auto-stop on top of ConferenceStreamingService.stopStream's own explicit
        // call -- see class KDoc. STOPPING is excluded here because handleStopping below already
        // completes that row regardless of room state.
        if (roomRow[ConferenceRoomTable.endedAt] != null && row.status != ConferenceStreamStatus.STOPPING) {
            if (row.status == ConferenceStreamStatus.LIVE) {
                autoStop(row = row, roomName = roomName, now = now)
            } else {
                finalizeEnded(streamId = row.id, now = now)
            }
            return
        }

        when (row.status) {
            // STARTING is deliberately NOT routed through reconcileMissedSecretBallotPause --
            // handleStarting already re-checks SecretBallotStreamLock.hasOpenSecretBallot itself, in
            // the SAME forUpdate()-locked transaction that adopts (or fails to adopt) an orphaned
            // egress, so a blind early PAUSING claim here would only throw away the egress-id
            // discovery handleStarting's own adoption logic performs -- see that method's own KDoc.
            ConferenceStreamStatus.STARTING -> handleStarting(row = row, roomName = roomName, now = now)
            ConferenceStreamStatus.LIVE -> {
                if (!reconcileMissedSecretBallotPause(row)) {
                    handleLive(row = row, roomName = roomName, now = now, issuesThisTick = issuesThisTick)
                }
            }
            ConferenceStreamStatus.PAUSING -> handlePausing(row = row, roomName = roomName, now = now)
            ConferenceStreamStatus.PAUSED -> handlePaused(row = row, now = now)
            ConferenceStreamStatus.STOPPING -> handleStopping(row = row, roomName = roomName, now = now)
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
        if (elapsed(from = row.startedAt, to = now) < streamingConfig.startupTimeoutSeconds.seconds) return

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
            markFailed(streamId = row.id, reason = FAILURE_TIMEOUT, confirmedEgressId = null)
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
                if (!hasOpenSecretBallot) openRetryWindowForStream(streamId = row.id, now = now)
            }
        } else {
            // confirmedEgressId = null -- same reasoning as the fingerprints.isEmpty() branch above:
            // this row never had an egress id recorded, see markFailed KDoc.
            markFailed(streamId = row.id, reason = FAILURE_TIMEOUT, confirmedEgressId = null)
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
        if (elapsed(from = row.startedAt, to = now) >= streamingConfig.maxDurationMinutes.minutes) {
            // Same ceiling handlePaused enforces for an already-PAUSED row -- a PAUSING row must not
            // be able to hold the room's one-active-stream-per-room slot open indefinitely either.
            finalizeEnded(streamId = row.id, now = now)
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
            if (elapsed(from = row.startedAt, to = now) < streamingConfig.startupTimeoutSeconds.seconds) return
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
                markPaused(streamId = row.id, now = now, confirmedEgressId = null)
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
                markPaused(streamId = row.id, now = now, confirmedEgressId = null)
                return
            }
            try {
                liveKitEgressClient.stopEgress(roomName = roomName, egressId = adopted.egressId)
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
            liveKitEgressClient.stopEgress(roomName = roomName, egressId = egressId)
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
            markPaused(streamId = row.id, now = now, confirmedEgressId = egressId)
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
            clearRetryScheduleForStream(streamId)
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
        issuesThisTick: AtomicInteger,
    ) {
        if (elapsed(from = row.startedAt, to = now) >= streamingConfig.maxDurationMinutes.minutes) {
            autoStop(row = row, roomName = roomName, now = now)
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
            markFailed(streamId = row.id, reason = FAILURE_GENERIC, confirmedEgressId = egressId)
            return
        }

        // V1.9.275: refresh the targets from stream_results and plan/claim the automatic retries -- see [refreshTargets].
        val claims =
            refreshTargets(
                streamId = row.id,
                egressId = egressId,
                info = info,
                now = now,
                claimDue =
                    info.status !in TERMINAL_EGRESS_STATUSES,
                issuesThisTick = issuesThisTick,
            )

        if (info.status in TERMINAL_EGRESS_STATUSES) {
            // The egress ended on its OWN account -- a moderator-initiated stop already moved this
            // row away from LIVE before it could ever reach here (see ConferenceStreamStatus KDoc
            // "StreamPoller drives LIVE -> FAILED"). confirmedEgressId = egressId -- this IS the
            // specific id whose terminal status this ListEgress call just observed, see markFailed KDoc.
            markFailed(streamId = row.id, reason = sanitizeLiveKitError(info.error), confirmedEgressId = egressId)
            return
        }
        reissueClaims(claims = claims, roomName = roomName, egressId = egressId)
    }

    /**
     * V1.9.275 -- ONE transaction per LIVE stream and tick. The stream row is locked first and must still be `LIVE` on the same egress (so a
     * pause, stop, restart or quiesce that won the race makes this a no-op). Every target is refreshed from `stream_results`; LiveKit's raw
     * status/retries are overwritten each tick, therefore the own state (`auto_retry_*`, `failure_kind`) lives in separate columns and a result
     * that predates the last issued attempt is ignored ([StreamTargetRetryPolicy.pickResult]). A newly detected failure is either planned for a
     * retry (PENDING, attempt + 1, `auto_retry_next_at`) or becomes FAILED; due attempts are claimed with a conditional UPDATE (so two ticks
     * can never issue the same attempt twice) and returned for the call OUTSIDE of any transaction.
     */
    private fun refreshTargets(
        streamId: Uuid,
        egressId: String,
        info: LiveKitEgressInfo,
        now: LocalDateTime,
        claimDue: Boolean,
        issuesThisTick: AtomicInteger,
    ): List<RetryClaim> =
        transaction {
            val current =
                ConferenceStreamTable
                    .selectAll()
                    .where { ConferenceStreamTable.id eq streamId }
                    .forUpdate()
                    .singleOrNull() ?: return@transaction emptyList()
            if (current[ConferenceStreamTable.status] != ConferenceStreamStatus.LIVE ||
                current[ConferenceStreamTable.livekitEgressId] != egressId
            ) {
                return@transaction emptyList()
            }
            val nowDb = now.truncatedToDbPrecision()
            val byFingerprint = info.streamResults.groupBy { it.url }
            val targets =
                ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.streamId eq streamId }.map {
                    it
                        .toTargetRow()
                }
            for (t in targets) {
                // A planned attempt that is not due yet: LiveKit still reports the old failure, the row must stay as planned.
                if (t.status == ConferenceStreamTargetStatus.PENDING && t.autoRetryNextAt != null && t.autoRetryIssuedAt == null) continue
                // No entry for the fingerprint is NOT a reason to skip: an issued attempt without any fresh result must still time out.
                val candidates = byFingerprint[t.urlFingerprint].orEmpty()
                val picked = StreamTargetRetryPolicy.pickResult(candidates = candidates, issuedAt = t.autoRetryIssuedAt, zone = TZ)
                val issued = t.autoRetryIssuedAt
                val timedOut =
                    picked == null &&
                        issued != null &&
                        elapsed(from = issued, to = nowDb) >= StreamTargetRetryPolicy.ATTEMPT_RESULT_TIMEOUT
                if (picked == null && !timedOut) continue
                val mapped = if (picked == null) ConferenceStreamTargetStatus.FAILED else mapStreamInfoStatus(picked.status)
                if (mapped == ConferenceStreamTargetStatus.FAILED) {
                    if (issued == null && t.status == ConferenceStreamTargetStatus.FAILED) continue // decided before, nothing new
                    val kind = if (picked == null) ConferenceStreamFailureKind.TIMEOUT else StreamTargetRetryPolicy.classify(picked.error)
                    applyTargetFailure(target = t, kind = kind, detectedAt = nowDb, liveKitRetries = picked?.retries, guardIssuedAt = null)
                } else {
                    val finished = mapped == ConferenceStreamTargetStatus.ACTIVE || mapped == ConferenceStreamTargetStatus.FINISHED
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq t.id }) {
                        it[status] = mapped
                        it[retries] = picked!!.retries
                        it[failureReason] = null
                        if (finished) {
                            it[failureKind] = null
                            it[autoRetryNextAt] = null
                            it[autoRetryIssuedAt] = null
                        }
                        picked.startedAtEpochNanos
                            .toLongOrNull()
                            ?.takeIf { n -> n != 0L }
                            ?.let { n -> it[startedAtEpochNanos] = n }
                        picked.endedAtEpochNanos
                            .toLongOrNull()
                            ?.takeIf { n -> n != 0L }
                            ?.let { n -> it[endedAtEpochNanos] = n }
                    }
                }
            }
            if (!claimDue) return@transaction emptyList()
            claimDueAttempts(streamId = streamId, nowDb = nowDb, issuesThisTick = issuesThisTick)
        }

    /** Decides a newly detected failure of [target]: planned retry (PENDING) or FAILED. Runs inside the caller's transaction. */
    private fun applyTargetFailure(
        target: TargetRow,
        kind: ConferenceStreamFailureKind,
        detectedAt: LocalDateTime,
        liveKitRetries: Int?,
        guardIssuedAt: LocalDateTime?,
    ) {
        val next =
            StreamTargetRetryPolicy.nextAttemptAt(
                kind = kind,
                attemptSoFar = target.autoRetryAttempt,
                windowStartedAt = target.retryWindowStartedAt,
                lastIssuedAt = target.autoRetryIssuedAt ?: guardIssuedAt,
                detectedAt = detectedAt,
                zone = TZ,
            )
        val where: Op<Boolean> =
            (ConferenceStreamTargetTable.id eq target.id) and
                (guardIssuedAt?.let { ConferenceStreamTargetTable.autoRetryIssuedAt eq it } ?: Op.TRUE)
        ConferenceStreamTargetTable.update({ where }) {
            it[failureKind] = kind
            it[autoRetryIssuedAt] = null
            if (liveKitRetries != null) it[retries] = liveKitRetries
            if (next != null) {
                it[status] = ConferenceStreamTargetStatus.PENDING
                it[failureReason] = null
                it[autoRetryAttempt] = target.autoRetryAttempt + 1
                it[autoRetryNextAt] = next
            } else {
                it[status] = ConferenceStreamTargetStatus.FAILED
                it[failureReason] = StreamTargetRetryPolicy.reasonText(kind)
                it[autoRetryNextAt] = null
            }
        }
        if (next != null) {
            logger.info {
                "StreamPoller: target ${target.id} failed (${kind.name}) -- retry ${target.autoRetryAttempt + 1} of " +
                    "${StreamTargetRetryPolicy.MAX_ATTEMPTS} planned"
            }
        }
    }

    /** Claims (conditional UPDATE) the attempts that are due and builds their URLs. Runs inside [refreshTargets]' transaction. */
    private fun claimDueAttempts(
        streamId: Uuid,
        nowDb: LocalDateTime,
        issuesThisTick: AtomicInteger,
    ): List<RetryClaim> {
        val due =
            ConferenceStreamTargetTable
                .selectAll()
                .where {
                    (ConferenceStreamTargetTable.streamId eq streamId) and
                        (ConferenceStreamTargetTable.status eq ConferenceStreamTargetStatus.PENDING) and
                        ConferenceStreamTargetTable.autoRetryNextAt.isNotNull() and
                        (ConferenceStreamTargetTable.autoRetryNextAt lessEq nowDb) and
                        ConferenceStreamTargetTable.autoRetryIssuedAt.isNull()
                }.map { it.toTargetRow() }
        val claims = ArrayList<RetryClaim>()
        for (t in due) {
            if (issuesThisTick.get() >= StreamTargetRetryPolicy.MAX_ISSUES_PER_TICK) break
            val claimed =
                ConferenceStreamTargetTable.update({
                    (ConferenceStreamTargetTable.id eq t.id) and
                        (ConferenceStreamTargetTable.status eq ConferenceStreamTargetStatus.PENDING) and
                        (ConferenceStreamTargetTable.autoRetryNextAt eq t.autoRetryNextAt) and
                        ConferenceStreamTargetTable.autoRetryIssuedAt.isNull()
                }) {
                    it[autoRetryIssuedAt] = nowDb
                    it[autoRetryNextAt] = null
                }
            if (claimed != 1) continue
            val url =
                try {
                    val destRow =
                        (ConferenceStreamTargetTable innerJoin ConferenceStreamDestinationTable)
                            .selectAll()
                            .where { ConferenceStreamTargetTable.id eq t.id }
                            .single()
                    targetRtmpUrl(
                        destinationRow = destRow,
                        targetId = t.id,
                        broadcastKeyCiphertext = destRow[ConferenceStreamTargetTable.broadcastKeyCiphertext],
                        secretBox = secretBox ?: throw StreamKeyMissing(),
                    )
                } catch (e: Exception) {
                    logger.warn { "StreamPoller: no usable key for the retry of target ${t.id} (${e::class.simpleName})" }
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq t.id }) {
                        it[status] = ConferenceStreamTargetStatus.FAILED
                        it[failureReason] = FAILURE_BROADCAST_KEY_MISSING_TEXT
                        it[failureKind] = null
                        it[autoRetryIssuedAt] = null
                    }
                    continue
                }
            issuesThisTick.incrementAndGet()
            claims += RetryClaim(targetId = t.id, issuedAt = nowDb, url = url)
            logger.info {
                "StreamPoller: re-adding the output of target ${t.id} to egress of stream $streamId (attempt ${t.autoRetryAttempt})"
            }
        }
        return claims
    }

    /** Outside of any transaction: the calls of the claimed attempts. A refused add counts at once as a failed attempt (UNKNOWN). */
    private suspend fun reissueClaims(
        claims: List<RetryClaim>,
        roomName: String,
        egressId: String,
    ) {
        for (claim in claims) {
            val ok =
                reissueStreamTargetOutput(
                    liveKitEgressClient = liveKitEgressClient,
                    roomName = roomName,
                    egressId = egressId,
                    removeUrl = claim.url,
                    addUrl = claim.url,
                )
            if (ok) continue
            val detectedAt = clock().truncatedToDbPrecision()
            transaction {
                val t =
                    ConferenceStreamTargetTable
                        .selectAll()
                        .where {
                            (ConferenceStreamTargetTable.id eq claim.targetId) and
                                (ConferenceStreamTargetTable.autoRetryIssuedAt eq claim.issuedAt)
                        }.singleOrNull()
                        ?.toTargetRow() ?: return@transaction
                applyTargetFailure(
                    target = t,
                    kind = ConferenceStreamFailureKind.UNKNOWN,
                    detectedAt = detectedAt,
                    liveKitRetries = null,
                    guardIssuedAt = claim.issuedAt,
                )
            }
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
        if (row.pauseReason == ConferenceStreamPauseReason.ROOM_PAUSE) {
            // V1.9.254 (Z17) -- a stream paused by the room pause whose room is NOT paused any more (the resume could not restart it, e.g. the
            // stream was still PAUSING at that moment, or the process crashed): convergent rule, start it again. While the room is paused it
            // simply stays paused (the max-duration ceiling below still applies, measured on the wall clock including pauses).
            val roomStillPaused = transaction { RoomPauseDb.isRoomPaused(row.roomId) }
            if (!roomStillPaused) {
                val ballotOpen = transaction { SecretBallotStreamLock.hasOpenSecretBallot(row.roomId) }
                if (ballotOpen) {
                    transaction {
                        ConferenceStreamTable.update({
                            (ConferenceStreamTable.id eq row.id) and
                                (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSED) and
                                (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.ROOM_PAUSE)
                        }) {
                            it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                        }
                    }
                    return
                }
                logger.info { "StreamPoller: room of stream ${row.id} is no longer paused -- restarting the stream" }
                restartEgressForStream(
                    streamId = row.id,
                    liveKitEgressClient = liveKitEgressClient,
                    streamingConfig = streamingConfig,
                    actorMemberId = null,
                    actorRole = null,
                    pauseFeed = pauseFeed,
                )
                return
            }
        }
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
                restartEgressForStream(
                    streamId = row.id,
                    liveKitEgressClient = liveKitEgressClient,
                    streamingConfig = streamingConfig,
                    actorMemberId = null,
                    actorRole = null,
                    pauseFeed = pauseFeed,
                )
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
        if (elapsed(from = row.startedAt, to = now) >= streamingConfig.maxDurationMinutes.minutes) {
            // No active egress while PAUSED (LiveKit has no pause primitive -- StopEgress already
            // ran when this stream was paused) -- nothing to stop, finalize directly.
            finalizeEnded(streamId = row.id, now = now)
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
        if (elapsed(from = row.startedAt, to = now) >= streamingConfig.maxDurationMinutes.minutes) {
            logger.error {
                "StreamPoller: force-finalizing STOPPING stream ${row.id} to ENDED after exceeding max duration -- " +
                    "StopEgress confirmation never landed (egress id: ${row.livekitEgressId ?: "never recorded"})"
            }
            finalizeEnded(streamId = row.id, now = now)
            return
        }

        val egressId = row.livekitEgressId
        if (egressId == null) {
            // Nothing was ever recorded as running for this row -- see class KDoc "STOPPING". No
            // egress to confirm, finalize directly -- but still guarded (confirmedEgressId = null): see
            // finalizeEndedConfirmed KDoc.
            finalizeEndedConfirmed(streamId = row.id, now = now, confirmedEgressId = null)
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
            liveKitEgressClient.stopEgress(roomName = roomName, egressId = egressId)
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
            finalizeEndedConfirmed(streamId = row.id, now = now, confirmedEgressId = egressId)
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
                liveKitEgressClient.stopEgress(roomName = roomName, egressId = egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "StreamPoller: StopEgress failed while auto-stopping stream ${row.id}: ${e.message}" }
            }
        }
        finalizeEnded(streamId = row.id, now = now)
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
            // V1.9.254: room first (lock order room -> recording -> stream), so the pause cleanup below cannot deadlock with a pause/resume.
            RoomPauseDb.lockRoom(current[ConferenceStreamTable.roomId])
            ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                it[status] = ConferenceStreamStatus.ENDED
                it[endedAt] = now
            }
            // V1.9.275: a target that was being retried and never recovered ends FAILED, never "FINISHED" or "live".
            failUnrecoveredTargetsForStream(streamId)
            ConferenceStreamTargetTable.update({
                (ConferenceStreamTargetTable.streamId eq streamId) and
                    (ConferenceStreamTargetTable.status inList NON_TERMINAL_TARGET_STATUSES)
            }) {
                it[status] = ConferenceStreamTargetStatus.FINISHED
            }
            // V1.9.274: ENDED is final -- the broadcast keys go with it, in the same transaction.
            BroadcastKeys.clearForStream(streamId)
            clearRetryScheduleForStream(streamId)
            // System-initiated (auto-stop), no acting member -- AuditLogRecorder.record accepts a
            // null actor for exactly this case, same as RecordingPoller.transitionToStopping. Must
            // be the LAST lock-taking operation -- see that object's KDoc "deadlock-avoidance
            // contract".
            RoomPauseDb.clearCaptureIfNothingPaused(roomId = current[ConferenceStreamTable.roomId])
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
            RoomPauseDb.lockRoom(current[ConferenceStreamTable.roomId])
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
            // V1.9.275: a target that was being retried and never recovered ends FAILED, never "FINISHED" or "live".
            failUnrecoveredTargetsForStream(streamId)
            ConferenceStreamTargetTable.update({
                (ConferenceStreamTargetTable.streamId eq streamId) and
                    (ConferenceStreamTargetTable.status inList NON_TERMINAL_TARGET_STATUSES)
            }) {
                it[status] = ConferenceStreamTargetStatus.FINISHED
            }
            // V1.9.274: ENDED is final -- the broadcast keys go with it, in the same transaction.
            BroadcastKeys.clearForStream(streamId)
            clearRetryScheduleForStream(streamId)
            RoomPauseDb.clearCaptureIfNothingPaused(roomId = current[ConferenceStreamTable.roomId])
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
            // V1.9.274: FAILED is final -- the broadcast keys go with it, in the same transaction.
            BroadcastKeys.clearForStream(streamId)
            clearRetryScheduleForStream(streamId)
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

private val TZ = ServerClock.zone

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
    val status: ConferenceStreamTargetStatus,
    val autoRetryAttempt: Int,
    val autoRetryNextAt: LocalDateTime?,
    val autoRetryIssuedAt: LocalDateTime?,
    val retryWindowStartedAt: LocalDateTime?,
)

/** A claimed automatic attempt. [url] is the plaintext RTMP URL: a local value only, never logged, redacted in [toString]. */
private class RetryClaim(
    val targetId: Uuid,
    val issuedAt: LocalDateTime,
    val url: String,
) {
    override fun toString(): String = "RetryClaim(targetId=$targetId)"
}

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
        status = this[ConferenceStreamTargetTable.status],
        autoRetryAttempt = this[ConferenceStreamTargetTable.autoRetryAttempt],
        autoRetryNextAt = this[ConferenceStreamTargetTable.autoRetryNextAt],
        autoRetryIssuedAt = this[ConferenceStreamTargetTable.autoRetryIssuedAt],
        retryWindowStartedAt = this[ConferenceStreamTargetTable.retryWindowStartedAt],
    )

/** Maps `livekit.StreamInfo.Status` (`ACTIVE`/`FINISHED`/`FAILED`) onto this wave's own [ConferenceStreamTargetStatus]. An unrecognized value stays [ConferenceStreamTargetStatus.PENDING] (non-terminal, polling continues). */
private fun mapStreamInfoStatus(status: String): ConferenceStreamTargetStatus =
    when (status) {
        "ACTIVE" -> ConferenceStreamTargetStatus.ACTIVE
        "FINISHED" -> ConferenceStreamTargetStatus.FINISHED
        "FAILED" -> ConferenceStreamTargetStatus.FAILED
        else -> ConferenceStreamTargetStatus.PENDING
    }

/** See class KDoc "Per-target failure mapping" -- V1.9.275: the class of the error is decided by [StreamTargetRetryPolicy.classify]. */
private fun sanitizeLiveKitError(raw: String): String = StreamTargetRetryPolicy.reasonText(StreamTargetRetryPolicy.classify(raw))
