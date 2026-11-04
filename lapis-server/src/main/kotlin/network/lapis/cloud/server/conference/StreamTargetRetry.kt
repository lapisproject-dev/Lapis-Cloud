package network.lapis.cloud.server.conference

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamRetryLimits
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Fixed, sanitized German vocabulary -- the ONLY failure texts that reach a DTO (see `StreamPoller` KDoc "Per-target failure mapping"). */
internal const val FAILURE_CONNECTION = "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
internal const val FAILURE_REJECTED = "Das Streaming-Ziel hat die Verbindung abgelehnt (Stream-Schlüssel prüfen)."
internal const val FAILURE_TIMEOUT = "Der Stream wurde vom Server beendet (Zeitüberschreitung)."
internal const val FAILURE_GENERIC = "Der Stream konnte nicht gestartet werden."

/** V1.9.275 -- same text as the service's FAILURE_BROADCAST_KEY_MISSING (kept equal by a test). */
internal const val FAILURE_BROADCAST_KEY_MISSING_TEXT = "Stream-Schlüssel dieser Sendung fehlt."

/** V1.9.275 -- fixed text of a manual reconnect whose `UpdateStream` call was refused by LiveKit. */
internal const val FAILURE_RECONNECT_FAILED = "Das Ziel konnte nicht neu verbunden werden."

/**
 * V1.9.275 -- the rules of the automatic retry of ONE target of a LIVE stream (pure functions, no I/O). The poller applies them; a target that fails
 * within [WINDOW] after its stream went live (resume, start) or after the last issued attempt is re-added to the running egress with `UpdateStream`
 * (see [reissueStreamTargetOutput]) up to [MAX_ATTEMPTS] times. A rejected key ([ConferenceStreamFailureKind.REJECTED]) is never retried.
 */
internal object StreamTargetRetryPolicy {
    val WINDOW: Duration = 45.seconds

    /** "Earliest" delay before attempt n+1; the real resolution is the poll interval. */
    val BACKOFF: List<Duration> = listOf(5.seconds, 15.seconds, 30.seconds)

    /** An issued attempt without any fresh result after this long counts as failed (TIMEOUT). Also converges after a crash between claim and call. */
    val ATTEMPT_RESULT_TIMEOUT: Duration = 30.seconds
    const val MAX_ATTEMPTS = ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS

    /** Instance-wide upper bound of `UpdateStream` calls per poll tick; the rest follows in the next tick. */
    const val MAX_ISSUES_PER_TICK = 20

    /** Classifies LiveKit's raw error text. REJECTED is checked first (a "connect ... 403" is a refusal, not a network problem). Raw text is never stored. */
    fun classify(raw: String): ConferenceStreamFailureKind {
        val lower = raw.lowercase()
        return when {
            lower.contains("unauthoriz") || lower.contains("forbidden") || lower.contains("403") || lower.contains("401") ->
                ConferenceStreamFailureKind.REJECTED
            lower.contains("timeout") || lower.contains("timed out") -> ConferenceStreamFailureKind.TIMEOUT
            lower.contains("resolv") || lower.contains("connect") || lower.contains("refused") -> ConferenceStreamFailureKind.CONNECTION
            else -> ConferenceStreamFailureKind.UNKNOWN
        }
    }

    fun reasonText(kind: ConferenceStreamFailureKind): String =
        when (kind) {
            ConferenceStreamFailureKind.REJECTED -> FAILURE_REJECTED
            ConferenceStreamFailureKind.TIMEOUT -> FAILURE_TIMEOUT
            ConferenceStreamFailureKind.CONNECTION -> FAILURE_CONNECTION
            ConferenceStreamFailureKind.UNKNOWN -> FAILURE_GENERIC
        }

    fun isRetryable(kind: ConferenceStreamFailureKind): Boolean = kind != ConferenceStreamFailureKind.REJECTED

    /**
     * Decision when a failure is detected for the first time: `null` = no attempt (the target becomes FAILED), else the earliest time of the next
     * attempt. Qualifies: retryable kind, fewer than [MAX_ATTEMPTS] attempts so far, and the detection lies within [WINDOW] of the anchor (the last
     * issued attempt, else the start of the window; no anchor = never).
     */
    fun nextAttemptAt(
        kind: ConferenceStreamFailureKind,
        attemptSoFar: Int,
        windowStartedAt: LocalDateTime?,
        lastIssuedAt: LocalDateTime?,
        detectedAt: LocalDateTime,
        zone: TimeZone,
    ): LocalDateTime? {
        if (!isRetryable(kind) || attemptSoFar !in 0 until MAX_ATTEMPTS) return null
        val anchor = lastIssuedAt ?: windowStartedAt ?: return null
        if (detectedAt.toInstant(zone) - anchor.toInstant(zone) > WINDOW) return null
        val at = detectedAt.toInstant(zone) + BACKOFF[attemptSoFar]
        return at.toLocalDateTime(zone).truncatedToDbPrecision()
    }

    /**
     * Picks THE result of a target among several `stream_results` with the same fingerprint (re-adding the same URL can leave an old FAILED entry
     * next to the new one). An ACTIVE entry wins. Without [issuedAt]: the entry with the latest timestamp (one entry = the former behaviour). With
     * [issuedAt]: only fresh entries count (not terminal, or ended at/after the issue); `null` = only stale results, do not touch the row.
     */
    fun pickResult(
        candidates: List<LiveKitStreamInfo>,
        issuedAt: LocalDateTime?,
        zone: TimeZone,
    ): LiveKitStreamInfo? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.status == "ACTIVE" }?.let { return it }

        fun stamp(i: LiveKitStreamInfo) = maxOf(i.startedAtEpochNanos.toLongOrNull() ?: 0L, i.endedAtEpochNanos.toLongOrNull() ?: 0L)
        if (issuedAt == null) return candidates.maxByOrNull { stamp(it) }
        val issuedNanos = issuedAt.toInstant(zone).toEpochMilliseconds() * 1_000_000L

        fun fresh(i: LiveKitStreamInfo): Boolean {
            if (i.status != "FAILED" && i.status != "FINISHED") return true
            val ended = i.endedAtEpochNanos.toLongOrNull() ?: 0L
            if (ended != 0L) return ended >= issuedNanos
            return (i.lastRetryAtEpochNanos.toLongOrNull() ?: 0L) >= issuedNanos
        }
        return candidates.filter { fresh(it) }.maxByOrNull { stamp(it) }
    }
}

/**
 * Opens a fresh retry window for every target of [streamId]: attempt 0, no planned/issued attempt, window anchor = [now]. Must run in the SAME
 * transaction that commits a new egress as LIVE (start, resume/restart, orphan adoption). Never touches the failure kind of a FAILED target.
 */
internal fun openRetryWindowForStream(
    streamId: Uuid,
    now: LocalDateTime,
) {
    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq streamId }) {
        it[autoRetryAttempt] = 0
        it[autoRetryNextAt] = null
        it[autoRetryIssuedAt] = null
        it[retryWindowStartedAt] = now.truncatedToDbPrecision()
    }
}

/**
 * Clears the planned/issued attempts of every target of [streamId] (the counter stays for the display). Called wherever a stream leaves LIVE.
 * Hygiene only: the poller never acts on a stream that is not LIVE, and every LIVE transition opens a new window.
 */
internal fun clearRetryScheduleForStream(streamId: Uuid) {
    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq streamId }) {
        it[autoRetryNextAt] = null
        it[autoRetryIssuedAt] = null
    }
}

/**
 * A target that was being retried (PENDING with an attempt counter or an issued attempt) and never recovered ends FAILED when its stream ends --
 * with its stored failure kind -- instead of being mapped to "FINISHED" with the rest. Runs inside the caller's transaction, before the generic mapping.
 */
internal fun failUnrecoveredTargetsForStream(streamId: Uuid) {
    val rows =
        ConferenceStreamTargetTable
            .selectAll()
            .where {
                (ConferenceStreamTargetTable.streamId eq streamId) and
                    (ConferenceStreamTargetTable.status eq ConferenceStreamTargetStatus.PENDING) and
                    (
                        (ConferenceStreamTargetTable.autoRetryAttempt greater 0) or
                            ConferenceStreamTargetTable.autoRetryIssuedAt.isNotNull()
                    )
            }.map { it[ConferenceStreamTargetTable.id] to it[ConferenceStreamTargetTable.failureKind] }
    for ((id, kind) in rows) {
        ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq id }) {
            it[status] = ConferenceStreamTargetStatus.FAILED
            it[failureReason] = StreamTargetRetryPolicy.reasonText(kind ?: ConferenceStreamFailureKind.UNKNOWN)
        }
    }
}
