package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus

/*
 * V1.9.26 "Abstimmen im Konferenzraum", Welle 3 -- the client's mirror of the server's secret-ballot stream rule.
 *
 * The server refuses a secret ballot while a stream of the Sitzung is still publishing (`SecretBallotStreamLock.requireStreamQuiescedForBallot`);
 * this file only SHOWS that rule early, so a member is not invited to click a button the server will refuse. It is a pure mirror: no DOM, no
 * RPC, no ballot data (a tripwire pins that). The server stays the authority: a client that is wrong about "free" is corrected by the
 * `ConflictException` of the cast, which the booth treats as "still locked", not as an error.
 */

/** FREE: cast allowed. LOCKED: the stream is still being paused. LOCKED_SLOW: that takes longer than usual. HUNG: it does not seem to stop. */
internal enum class BallotStreamLock { FREE, LOCKED, LOCKED_SLOW, HUNG }

/** After this long in PAUSING the lock text gains a second sentence. */
internal const val PAUSE_SLOW_HINT_MS = 20_000L

/** After this long in PAUSING the stream counts as hung: operators get the emergency card. */
internal const val PAUSE_HUNG_THRESHOLD_MS = 60_000L

/** A booth that got a conflict with "still OPEN, not voted" re-enables its cast button after this long (never an automatic re-submit). */
internal const val BALLOT_LOCK_RECHECK_MS = 5_000

/** The stream poll interval while a secret ballot is open and the stream is not quiesced yet. */
internal const val SECRET_BALLOT_STREAM_POLL_MS = 5_000L

/**
 * Parity with the server's private `SecretBallotStreamLock.isQuiescedForBallot`: an exhaustive `when` WITHOUT `else`, so a new status is a
 * compile error here until somebody decides. `null` (no stream known) is free. `SecretBallotLockParityTripwireTest` compares the two blocks.
 */
internal fun ConferenceStreamStatus?.quiescedForSecretBallot(): Boolean =
    when (this) {
        null,
        ConferenceStreamStatus.PAUSED,
        ConferenceStreamStatus.ENDED,
        ConferenceStreamStatus.FAILED,
        -> true
        ConferenceStreamStatus.STARTING,
        ConferenceStreamStatus.LIVE,
        ConferenceStreamStatus.PAUSING,
        ConferenceStreamStatus.STOPPING,
        -> false
    }

/** What the panel knows about the stream: its status, why it is paused, and since when it has been PAUSING (as observed by this client). */
internal data class StreamMirrorState(
    val status: ConferenceStreamStatus? = null,
    val pauseReason: ConferenceStreamPauseReason? = null,
    val pausingSinceMs: Long? = null,
)

/** Sets [StreamMirrorState.pausingSinceMs] on the first PAUSING observation, keeps it while PAUSING, clears it on any other status. */
internal fun streamMirrorReduce(
    prev: StreamMirrorState,
    status: ConferenceStreamStatus?,
    reason: ConferenceStreamPauseReason?,
    nowMs: Long,
): StreamMirrorState {
    val since =
        when {
            status != ConferenceStreamStatus.PAUSING -> null
            prev.status == ConferenceStreamStatus.PAUSING && prev.pausingSinceMs != null -> prev.pausingSinceMs
            else -> nowMs
        }
    return StreamMirrorState(status = status, pauseReason = reason, pausingSinceMs = since)
}

internal fun ballotStreamLock(
    secret: Boolean,
    status: ConferenceStreamStatus?,
    pausingSinceMs: Long?,
    nowMs: Long,
): BallotStreamLock {
    if (!secret || status.quiescedForSecretBallot()) return BallotStreamLock.FREE
    if (status != ConferenceStreamStatus.PAUSING || pausingSinceMs == null) return BallotStreamLock.LOCKED
    val elapsed = nowMs - pausingSinceMs
    return when {
        elapsed >= PAUSE_HUNG_THRESHOLD_MS -> BallotStreamLock.HUNG
        elapsed >= PAUSE_SLOW_HINT_MS -> BallotStreamLock.LOCKED_SLOW
        else -> BallotStreamLock.LOCKED
    }
}

/** For members HUNG reads as LOCKED_SLOW: only operators and the moderation get the emergency card. */
internal fun BallotStreamLock.forMember(): BallotStreamLock = if (this == BallotStreamLock.HUNG) BallotStreamLock.LOCKED_SLOW else this

/** `null` = free; otherwise the lock text. Two sentences are two msgids joined with a space, never a sentence built from fragments. */
internal fun ballotLockReason(lock: BallotStreamLock): String? =
    when (lock) {
        BallotStreamLock.FREE -> null
        BallotStreamLock.LOCKED -> gettext("Der Live-Stream wird noch angehalten. Erst danach ist die Stimmabgabe möglich.")
        BallotStreamLock.LOCKED_SLOW, BallotStreamLock.HUNG ->
            gettext("Der Live-Stream wird noch angehalten. Erst danach ist die Stimmabgabe möglich.") +
                " " +
                gettext("Das dauert länger als üblich. Bitte haben Sie noch einen Moment Geduld.")
    }

/** How long until the lock of a PAUSING stream changes level by itself (20 s, 60 s); `null` when no time-based change is pending. */
internal fun lockRecheckDelayMs(
    pausingSinceMs: Long?,
    nowMs: Long,
): Int? {
    if (pausingSinceMs == null) return null
    val elapsed = nowMs - pausingSinceMs
    return when {
        elapsed < PAUSE_SLOW_HINT_MS -> (PAUSE_SLOW_HINT_MS - elapsed).toInt()
        elapsed < PAUSE_HUNG_THRESHOLD_MS -> (PAUSE_HUNG_THRESHOLD_MS - elapsed).toInt()
        else -> null
    }
}

/**
 * The banner for everybody in the room while a secret ballot runs. [secretOpenInRoom] is `null` until the room state is known (a guest never
 * gets it); the banner then shows only for a stream that is being paused for a ballot. [recordingActive]: `null` = unknown, which keeps the
 * recording sentence (the cautious default -- the member is told the recording goes on rather than assuming it does not).
 */
internal fun secretBallotBannerText(
    secretOpenInRoom: Boolean?,
    stream: StreamMirrorState,
    recordingActive: Boolean?,
): String? {
    val pausedForBallot =
        (stream.status == ConferenceStreamStatus.PAUSING || stream.status == ConferenceStreamStatus.PAUSED) &&
            stream.pauseReason == ConferenceStreamPauseReason.SECRET_BALLOT
    if (secretOpenInRoom != true && !pausedForBallot) return null
    val head =
        when (stream.status) {
            ConferenceStreamStatus.PAUSED -> gettext("Geheime Wahl läuft: Der Livestream ist angehalten.")
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.STOPPING,
            -> gettext("Geheime Wahl läuft: Der Livestream wird angehalten.")
            ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED, null -> gettext("Geheime Wahl läuft.")
        }
    return if (recordingActive == false) head else head + " " + gettext("Die Aufzeichnung der Sitzung läuft weiter.")
}

/** The lines of the dialog before a secret election is opened; the last line is always the note about the recording. */
internal fun secretOpenPreflightLines(status: ConferenceStreamStatus?): List<String> {
    val streamLine =
        when (status) {
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.STOPPING,
            -> gettext("Der Live-Stream in diesem Raum wird für die Dauer der Abstimmung angehalten.")
            ConferenceStreamStatus.PAUSED ->
                gettext("Der Live-Stream im Raum ist angehalten und bleibt es während der Abstimmung.")
            ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED, null -> gettext("In diesem Raum läuft kein Live-Stream.")
        }
    return listOf(
        streamLine,
        gettext("Das gilt auch für Live-Streams in anderen Räumen dieser Sitzung."),
        gettext("Die Aufzeichnung der Sitzung wird nicht angehalten."),
    )
}

/** [conferenceStreamNeedsPoll], plus: a LIVE stream is polled while a secret ballot is open (LIVE is otherwise pushed, not polled). */
internal fun conferenceStreamNeedsPollWithBallot(
    stream: ConferenceStreamDto?,
    secretBallotOpen: Boolean,
): Boolean {
    if (conferenceStreamNeedsPoll(stream)) return true
    return secretBallotOpen && stream?.status == ConferenceStreamStatus.LIVE
}

/** 5 s while a secret ballot is open and the stream is not quiesced, else the usual 15 s (at most 12 of the 60 allowed reads a minute). */
internal fun conferenceStreamPollIntervalMs(
    stream: ConferenceStreamDto?,
    secretBallotOpen: Boolean,
): Long =
    if (secretBallotOpen && stream != null && !stream.status.quiescedForSecretBallot()) {
        SECRET_BALLOT_STREAM_POLL_MS
    } else {
        CONFERENCE_STREAM_POLL_INTERVAL_MS
    }

/**
 * The lock of the panel over time. [update] feeds the stream status in; [lock] reads the level now; listeners hear about every change
 * of the stream AND about the two time-based level changes (20 s, 60 s of PAUSING), which no status push announces.
 */
internal class BallotLockClock(
    private val scheduler: ConferenceVoteScheduler,
) {
    var mirror: StreamMirrorState = StreamMirrorState()
        private set

    private var timer: Int? = null
    private var disposed = false
    private val listeners = mutableListOf<() -> Unit>()

    private fun nowMs(): Long = scheduler.now().toLong()

    fun lock(secret: Boolean): BallotStreamLock = ballotStreamLock(secret, mirror.status, mirror.pausingSinceMs, nowMs())

    fun update(
        status: ConferenceStreamStatus?,
        reason: ConferenceStreamPauseReason?,
    ) {
        if (disposed) return
        mirror = streamMirrorReduce(mirror, status, reason, nowMs())
        reschedule()
        notifyListeners()
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun dispose() {
        disposed = true
        timer?.let { scheduler.cancel(it) }
        timer = null
        listeners.clear()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private fun reschedule() {
        timer?.let { scheduler.cancel(it) }
        timer = null
        val delayMs = lockRecheckDelayMs(mirror.pausingSinceMs, nowMs()) ?: return
        timer =
            scheduler.schedule(delayMs) {
                timer = null
                if (!disposed) {
                    reschedule()
                    notifyListeners()
                }
            }
    }
}
