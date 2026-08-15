package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.SecretBallotStreamLock
import network.lapis.cloud.server.rpc.restartEgressForStream
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
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

/** Polling interval for the StopEgress confirmation loop -- see [DefaultSecretBallotStreamGuard] KDoc "Quiescing algorithm". */
private const val QUIESCE_VERIFY_POLL_MS = 500L

/**
 * Security-audit MAJOR-4 defaults for [DefaultSecretBallotStreamGuard.resumeRateLimiter] -- same
 * shape as `network.lapis.cloud.server.federation.FederationInboxRateLimiter`'s own doc-comment
 * example. PAUSE ([DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting]) is deliberately NEVER
 * rate-limited -- see that method's own KDoc "Never rate-limited".
 */
private const val DEFAULT_RESUME_RATE_MAX = 5
private val DEFAULT_RESUME_RATE_WINDOW = 5.minutes

/**
 * Mirrors [network.lapis.cloud.server.conference.StreamPoller]'s own (file-private)
 * `TERMINAL_EGRESS_STATUSES` set -- `livekit.EgressStatus`'s four terminal values. Duplicated rather
 * than shared because that constant is `private` to `StreamPoller.kt`, which is out of scope for
 * this pass to touch (its own `PAUSING` dispatch branch is a later wave step).
 */
private val TERMINAL_EGRESS_STATUSES = setOf("EGRESS_COMPLETE", "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED")

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- the boundary between
 * Governance (`ElectionService`/`SystemicConsensusService`, a later wave step) and the actual LiveKit
 * `StopEgress`/restart calls. Two implementations: [DefaultSecretBallotStreamGuard] (production,
 * real LiveKit calls) and [NoOpSecretBallotStreamGuard] (tests only).
 */
interface SecretBallotStreamGuard {
    /**
     * Aufzurufen von `ElectionService.openVoting`/`SystemicConsensusService.freezeOptions`/
     * `.reopenRating` (a later wave step), NACHDEM deren eigene Transaktion committet ist (that
     * transaction has already locked the affected rooms via [SecretBallotStreamLock.lockRooms] and
     * flipped every `STARTING`/`LIVE` stream of theirs to [ConferenceStreamStatus.PAUSING] via
     * `ConferenceStreamPauseCoordinator.markPausingForSecretBallot`). Performs the actual
     * `StopEgress` plus a confirming `ListEgress` poll, and only THEN writes
     * [ConferenceStreamStatus.PAUSED]. **Never throws** -- any failure (LiveKit unreachable, timeout)
     * leaves the row in [ConferenceStreamStatus.PAUSING], which keeps
     * [SecretBallotStreamLock.requireStreamQuiescedForBallot] fail-closed (ballot casting stays
     * blocked) and lets `StreamPoller`'s own `PAUSING` handling (a later wave step) retry on its next
     * tick.
     *
     * **Never rate-limited** (security-audit MAJOR-4) -- unlike [resumeStreamsForMeeting], this
     * direction protects privacy, not availability: PAUSE must always fire regardless of how often it
     * is triggered. Only [resumeStreamsForMeeting] carries a budget.
     */
    suspend fun quiesceStreamsForMeeting(meetingId: Uuid)

    /**
     * Aufzurufen von `closeVoting`/`closeRating`/`abortElection`/`abortSystemicConsensus` (a later
     * wave step), NACHDEM deren Transaktion committet ist. For every room bound to [meetingId] whose
     * stream is [ConferenceStreamStatus.PAUSED] with `pauseReason ==`
     * [ConferenceStreamPauseReason.SECRET_BALLOT], not yet ended, and no LONGER covered by
     * [SecretBallotStreamLock.hasOpenSecretBallot] (another concurrent secret ballot on the same
     * meeting may still be open -- see the wave's own race scenarios 12/13), starts a fresh egress
     * via [restartEgressForStream]. Idempotent (a stream not meeting all four conditions is simply
     * skipped); **never throws**.
     *
     * Security-audit MAJOR-4 -- rate-limited per [meetingId] (NOT per stream/member): a
     * pause/resume/pause/resume/... cycle driven by rapid `reopenRating`/`closeRating` calls (or a
     * compromised board account) must not be able to hammer LiveKit indefinitely. Exceeding the
     * budget does NOT throw and does NOT block `closeVoting`/`closeRating`/`abortElection`/
     * `abortSystemicConsensus` themselves (governance stays fully functional) -- it just declines to
     * auto-restart the egress THIS time, leaving the stream `PAUSED` for a moderator to resume by
     * hand via [network.lapis.cloud.server.rpc.ConferenceStreamingService.resumeStream]. See
     * [DefaultSecretBallotStreamGuard] KDoc "Resume algorithm" for the exact budget.
     */
    suspend fun resumeStreamsForMeeting(meetingId: Uuid)
}

/**
 * Production [SecretBallotStreamGuard] -- real [LiveKitEgressClient] calls, real DB writes.
 *
 * ## Quiescing algorithm ([quiesceStreamsForMeeting])
 *
 * 1. One transaction reads every `conference_stream` row currently [ConferenceStreamStatus.PAUSING]
 *    for [meetingId]'s bound rooms (id, room name, `livekit_egress_id`).
 * 2. For each row, OUTSIDE any transaction:
 *    - `egressId == null` (a `startStream` call was still mid-flight between ITS two transactions
 *      when `ConferenceStreamPauseCoordinator` marked it `PAUSING`) -- left untouched.
 *      `StreamPoller`'s own `PAUSING` handling (a later wave step) adopts it once the egress
 *      actually appears; flipping it to `PAUSED` here would wrongly unblock ballot casting while an
 *      egress might still be about to come up.
 *    - otherwise: best-effort `stopEgress` (caught, WARN-logged, never fatal -- same
 *      "`StopEgress` failure never blocks the state transition" discipline
 *      [network.lapis.cloud.server.rpc.ConferenceStreamingService] KDoc documents for
 *      `pauseStream`/`stopStream`), then a confirmation loop polling `listEgress` every
 *      [QUIESCE_VERIFY_POLL_MS] until the egress is either gone from the list or reports a
 *      [TERMINAL_EGRESS_STATUSES] status, capped at [ConferenceStreamingConfig.pauseVerifyTimeoutSeconds].
 * 3. Confirmed stop -> a second transaction writes [ConferenceStreamStatus.PAUSED] +
 *    `pausedAt = now` (`pauseReason` stays [ConferenceStreamPauseReason.SECRET_BALLOT], already set
 *    by the coordinator) + one [AuditLogRecorder.record] entry, attributed to `null`/`null` (system) --
 *    security-audit round-4 R4-1 fix: this write is additionally guarded on the row's CURRENT
 *    `livekit_egress_id` still matching (or remaining unset) the specific id whose stop step 2 just
 *    confirmed, see [DefaultSecretBallotStreamGuard.markPaused] KDoc for the resurrection race this closes.
 * 4. Timeout -> the row stays [ConferenceStreamStatus.PAUSING], one WARN log naming only the
 *    stream/egress id -- **never** LiveKit error text (this class never even reads
 *    `LiveKitEgressInfo.error`/`LiveKitStreamInfo.error`, only `egressId`/`status`, so there is
 *    nothing to accidentally leak).
 *
 * ## Resume algorithm ([resumeStreamsForMeeting])
 *
 * Exactly the [network.lapis.cloud.server.rpc.ConferenceStreamingService.resumeStream] path, minus
 * the role check (this IS the system, not a moderator click) -- delegated entirely to
 * [restartEgressForStream] with `actorMemberId = null`/`actorRole = null` for the audit trail.
 * Security-audit MAJOR-4: gated on [resumeRateLimiter], keyed `"meeting:$meetingId"` (per meeting,
 * not per stream -- a meeting with several bound rooms/streams shares one budget, same "per governed
 * resource, not per artifact" reasoning [ConferenceService]'s own `conferenceMeetingBindRateLimiter`
 * KDoc gives). Checked ONCE per [resumeStreamsForMeeting] call, before the candidate loop -- if
 * exceeded, the ENTIRE call is a no-op (every candidate stream stays `PAUSED`, one WARN log naming
 * the meeting, no [AuditLogRecorder.record] entry since nothing changed) rather than partially
 * resuming some streams and not others.
 *
 * [DefaultSecretBallotStreamGuard] is constructed exactly ONCE, in `Application.module` (unlike
 * [network.lapis.cloud.server.rpc.ConferenceStreamingService], which the Kilua RPC `registerService`
 * factory lambda constructs fresh per call) -- so [resumeRateLimiter]'s own constructor default is
 * safe to rely on even in production (there is only ever one instance, so the default is never
 * silently re-created per request the way [network.lapis.cloud.server.rpc.ConferenceStreamingService]
 * KDoc warns against for ITS OWN rate-limiter defaults). `Application.module` still threads an
 * explicit instance through, for the same tunability/consistency reasons
 * `conferenceMeetingBindRateLimiter` is its own module-scoped `val` rather than relying on
 * [ConferenceService]'s constructor default.
 */
class DefaultSecretBallotStreamGuard(
    private val liveKitEgressClient: LiveKitEgressClient,
    private val streamingConfig: ConferenceStreamingConfig,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
    private val resumeRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_RESUME_RATE_MAX, window = DEFAULT_RESUME_RATE_WINDOW),
) : SecretBallotStreamGuard {
    override suspend fun quiesceStreamsForMeeting(meetingId: Uuid) {
        try {
            // Security-audit MINOR-10 fix -- concurrent, not serial: a meeting can have several
            // bound rooms (see setRoomMeeting's own MINOR-10 cap below), each with its own
            // StopEgress+ListEgress confirmation loop (up to pauseVerifyTimeoutSeconds each). Serial
            // execution meant N pausing streams could take N times as long to confirm, stretching how
            // long ballot casting stays fail-closed-blocked for no reason -- each stream's quiescing
            // is fully independent (different room, different egress), so there is nothing to
            // serialize FOR. quiesceOne's own per-stream try/catch already makes one stream's failure
            // harmless to the others; coroutineScope here additionally waits for ALL of them (success
            // or failure) before returning, same "every candidate gets a chance" guarantee the serial
            // loop gave.
            coroutineScope {
                pausingStreamsForMeeting(meetingId).map { pausing -> async { quiesceOne(pausing) } }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Security-audit MINOR-8 fix -- never log the full exception/cause chain here: a
            // LiveKitAdminException wrapping a connection failure can carry the LiveKit hostname in
            // its message (see network.lapis.cloud.server.conference.HttpLiveKitEgressClient KDoc).
            // Every OTHER catch site in this class already logs only `e.message` (already-sanitized,
            // generic LiveKit-admin-client text) -- this outer catch-all is the one place that used
            // to log the raw exception object (`logger.warn(e) { ... }`), which SLF4J renders with
            // its full message AND stack trace.
            logger.warn { "quiesceStreamsForMeeting: failed for meeting $meetingId (${e::class.simpleName})" }
        }
    }

    override suspend fun resumeStreamsForMeeting(meetingId: Uuid) {
        try {
            // Security-audit MAJOR-4 -- see class KDoc "Resume algorithm". Candidates are read BEFORE
            // the rate-limit check now -- security-audit-round-2 F3 fix, see below -- purely so a
            // REJECTED call still knows which streams it would have resumed; exceeding the budget still
            // must never partially resume some streams and not others for the same meeting either way.
            val candidates = resumeCandidatesForMeeting(meetingId)
            if (!resumeRateLimiter.checkAndRecord("meeting:$meetingId")) {
                logger.warn {
                    "resumeStreamsForMeeting: rate limit reached for meeting $meetingId -- auto-resume declined this " +
                        "time, affected streams stay PAUSED until a moderator resumes them manually"
                }
                // Security-audit-round-2 F3 fix -- the log line above already claimed "until a moderator
                // resumes them manually", but `pauseReason` was left untouched (still SECRET_BALLOT).
                // StreamPoller.handlePaused's own crash-recovery reconciliation calls
                // `restartEgressForStream` DIRECTLY, bypassing THIS rate limiter entirely -- so it would
                // simply retry, successfully and unthrottled, on the very next poll tick, silently
                // defeating the budget this limiter exists to enforce. One-way escalation to MANUAL --
                // same semantics `restartEgressForStream`'s own MINOR-9 branch now applies when it
                // declines an auto-resume for a disabled destination -- takes every affected stream OUT
                // of the SECRET_BALLOT auto-resume machinery for good: a moderator must consciously call
                // `resumeStream` to bring it back, and `handlePaused`'s ordinary `maxDurationMinutes`
                // ceiling applies again in the meantime instead of never being reachable.
                escalateToManualPause(candidates)
                return
            }
            for (streamId in candidates) {
                try {
                    restartEgressForStream(
                        streamId = streamId,
                        liveKitEgressClient = liveKitEgressClient,
                        streamingConfig = streamingConfig,
                        actorMemberId = null,
                        actorRole = null,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn { "resumeStreamsForMeeting: failed to auto-resume stream $streamId (${e::class.simpleName})" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn { "resumeStreamsForMeeting: failed for meeting $meetingId (${e::class.simpleName})" }
        }
    }

    private suspend fun quiesceOne(pausing: PausingStreamRow) {
        try {
            val egressId = pausing.egressId
            if (egressId == null) {
                // See class KDoc "Quiescing algorithm" step 2 -- startStream's own two transactions
                // have not finished yet, nothing to stop, do NOT flip to PAUSED.
                return
            }
            try {
                liveKitEgressClient.stopEgress(pausing.roomName, egressId)
            } catch (e: LiveKitAdminException) {
                // Best-effort -- see class KDoc. LiveKitAdminException.message is already generic
                // (network-error class name/HTTP status), safe to log verbatim, same precedent
                // ConferenceStreamingService.pauseStream/stopStream already establish.
                logger.warn { "quiesceStreamsForMeeting: StopEgress failed for stream ${pausing.streamId}: ${e.message}" }
            }
            if (awaitEgressStopped(pausing.roomName, egressId)) {
                // Security-audit round-4 R4-1 fix -- pass the SAME id awaitEgressStopped just
                // confirmed gone/terminal, so markPaused can guard its write against a resurrection.
                markPaused(pausing.streamId, confirmedEgressId = egressId)
            } else {
                logger.warn {
                    "quiesceStreamsForMeeting: timed out confirming egress $egressId stopped for stream " +
                        "${pausing.streamId} -- left PAUSING, StreamPoller retries on its next tick"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // See resumeStreamsForMeeting's own outer catch -- security-audit MINOR-8 fix, never the
            // full exception/cause chain.
            logger.warn { "quiesceStreamsForMeeting: failed to quiesce stream ${pausing.streamId} (${e::class.simpleName})" }
        }
    }

    private suspend fun awaitEgressStopped(
        roomName: String,
        egressId: String,
    ): Boolean =
        withTimeoutOrNull(streamingConfig.pauseVerifyTimeoutSeconds.seconds) {
            while (true) {
                val egresses =
                    try {
                        liveKitEgressClient.listEgress(roomName)
                    } catch (e: LiveKitAdminException) {
                        logger.warn {
                            "quiesceStreamsForMeeting: ListEgress failed while confirming egress $egressId stopped: ${e.message}"
                        }
                        delay(QUIESCE_VERIFY_POLL_MS)
                        continue
                    }
                val info = egresses.firstOrNull { it.egressId == egressId }
                if (info == null || info.status in TERMINAL_EGRESS_STATUSES) return@withTimeoutOrNull true
                delay(QUIESCE_VERIFY_POLL_MS)
            }
        } == true

    /**
     * Security-audit-round-2 F3 fix -- see [resumeStreamsForMeeting]'s own MAJOR-4-rate-limit-decline
     * branch. A blind, unconditional `pauseReason = MANUAL` write would be wrong if [streamIds]
     * somehow drifted away from `PAUSED`/`SECRET_BALLOT` between [resumeCandidatesForMeeting]'s own
     * read and this call (e.g. a concurrent moderator resumeStream/pauseStream already moved it) -- the
     * `WHERE` clause re-checks both columns so this only ever touches a row still genuinely stuck in
     * the auto-resume-eligible state. No [AuditLogRecorder] entry -- same "plain DB write, no audit
     * trail" precedent [network.lapis.cloud.server.rpc.restartEgressForStream]'s own MINOR-9 branch
     * already sets for the analogous decline.
     */
    private fun escalateToManualPause(streamIds: List<Uuid>) {
        if (streamIds.isEmpty()) return
        transaction {
            streamIds.forEach { streamId ->
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamId) and
                        (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSED) and
                        (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.SECRET_BALLOT)
                }) {
                    it[pauseReason] = ConferenceStreamPauseReason.MANUAL
                }
            }
        }
    }

    /**
     * Security-audit round-4 R4-1 fix -- [confirmedEgressId] is the egress id whose stop [awaitEgressStopped]
     * (the ONE caller, [quiesceOne]) just confirmed gone/terminal. Before this fix, this write was gated
     * ONLY on `status == PAUSING`, never on which egress it was actually the confirmation OF -- so a
     * concurrent `startStream`/`restartEgressForStream` "abandoned" branch that resurrected this SAME row
     * onto a FRESH, actually-publishing egress in the window between [awaitEgressStopped] returning and
     * this write would have that fresh egress silently overwritten with `PAUSED`, a status this guard (and
     * [network.lapis.cloud.server.conference.StreamPoller]) never revisits -- stranding the fresh egress
     * running forever while [SecretBallotStreamLock.requireStreamQuiescedForBallot] wrongly reports the
     * room quiesced. Mirrors [network.lapis.cloud.server.conference.StreamPoller]'s own `markPaused`
     * (its NEU-2 fix) and [network.lapis.cloud.server.rpc.ConferenceStreamingService.stopStream]'s own
     * Tx3 fix (NEU-1) byte-for-byte -- this is the THIRD, primary-quiescing-routine instance of the exact
     * same finding, verified reproducible with a deterministic fake-client hook (no real thread race
     * needed, see `SecretBallotStreamPauseTest`'s own R4-1 regression test). If the predicate does not
     * match, the write is skipped entirely -- the row is left exactly as the resurrection wrote it, still
     * `PAUSING` under [network.lapis.cloud.server.conference.StreamPoller]'s own `handlePausing` sweep on
     * the very next tick.
     */
    private fun markPaused(
        streamId: Uuid,
        confirmedEgressId: String?,
    ) {
        transaction {
            val now = clock()
            val current =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull()
                    ?: return@transaction
            // Re-check under this fresh transaction -- a concurrent stopStream/pauseStream may have
            // already moved the row on since pausingStreamsForMeeting's own read.
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
                    // pauseReason is left untouched -- already SECRET_BALLOT, written by
                    // ConferenceStreamPauseCoordinator.markPausingForSecretBallot.
                }
            if (updated == 0) {
                logger.warn {
                    "quiesceStreamsForMeeting: stream $streamId was resurrected with a new egress id while " +
                        "its stop was being confirmed -- NOT marking PAUSED, leaving it for StreamPoller's own " +
                        "next tick"
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
        }
    }

    private fun pausingStreamsForMeeting(meetingId: Uuid): List<PausingStreamRow> =
        transaction {
            val roomIds = SecretBallotStreamLock.roomIdsForMeeting(meetingId)
            if (roomIds.isEmpty()) {
                emptyList()
            } else {
                (ConferenceStreamTable innerJoin ConferenceRoomTable)
                    .selectAll()
                    .where {
                        (ConferenceStreamTable.roomId inList roomIds) and (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSING)
                    }.map {
                        PausingStreamRow(
                            streamId = it[ConferenceStreamTable.id],
                            roomName = it[ConferenceRoomTable.livekitRoomName],
                            egressId = it[ConferenceStreamTable.livekitEgressId],
                        )
                    }
            }
        }

    /** See interface KDoc [SecretBallotStreamGuard.resumeStreamsForMeeting] for the four conditions this filters on. */
    private fun resumeCandidatesForMeeting(meetingId: Uuid): List<Uuid> =
        transaction {
            val roomIds = SecretBallotStreamLock.roomIdsForMeeting(meetingId)
            if (roomIds.isEmpty()) {
                emptyList()
            } else {
                (ConferenceStreamTable innerJoin ConferenceRoomTable)
                    .selectAll()
                    .where {
                        (ConferenceStreamTable.roomId inList roomIds) and
                            (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSED) and
                            (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.SECRET_BALLOT) and
                            ConferenceRoomTable.endedAt.isNull()
                    }.map { it[ConferenceStreamTable.id] to it[ConferenceStreamTable.roomId] }
                    // hasOpenSecretBallot re-checked AFTER the query -- another concurrent secret
                    // ballot on the same meeting (scenarios 12/13 in the wave's own test plan) must
                    // keep this stream paused.
                    .filter { (_, roomId) -> !SecretBallotStreamLock.hasOpenSecretBallot(roomId) }
                    .map { (streamId, _) -> streamId }
            }
        }
}

private data class PausingStreamRow(
    val streamId: Uuid,
    val roomName: String,
    val egressId: String?,
)

/**
 * NUR für Tests, in denen Streaming irrelevant ist. **NIEMALS in `Application.module` verwenden** --
 * das würde die gesamte Schutzfunktion dieser Welle still abschalten, während jedes KDoc dieser Welle
 * das Gegenteil behauptet. Genau die Falle, die der Wave-3-Audit-Round-2 bei den Rate-Limitern
 * gefunden hat (siehe [network.lapis.cloud.server.rpc.ConferenceStreamingService] KDoc "Constructor
 * defaults exist for tests only").
 */
object NoOpSecretBallotStreamGuard : SecretBallotStreamGuard {
    override suspend fun quiesceStreamsForMeeting(meetingId: Uuid) = Unit

    override suspend fun resumeStreamsForMeeting(meetingId: Uuid) = Unit
}
