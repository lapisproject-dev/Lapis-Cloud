package network.lapis.cloud.client.livekit

import io.kvision.i18n.gettext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import network.lapis.cloud.client.ngettext
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_EXTENSIONS_SECONDS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_FINAL_PHASE_MS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_SECONDS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MIN_SECONDS
import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import kotlin.math.ceil

/*
 * V1.9.133 -- the pure logic of the display-only conference timer: no DOM, no RPC, no clock of its own (every function gets its times
 * as parameters), so a plain unit test drives all of it. The stateful controller is `ConferenceTimerController`, the widgets are
 * `ConferenceTimerPill` / `ConferenceTimerDialog`.
 *
 * Time bases: the SERVER epoch (`serverNowEpochMs`, a wall clock, used ONLY to estimate an offset) and the LOCAL monotonic clock
 * (`performance.now()`, immune to a changed system clock). The countdown is always `endsAtLocal - nowLocal`.
 */

private val TIMER_EPOCH = Regex("^[0-9a-f]{16}$")
private const val TIMER_MAX_DURATION_MS = 86_400_000L
private const val TIMER_MAX_REMAINING_MS = CONFERENCE_TIMER_MAX_SECONDS * 1000L
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/**
 * The ONE place a data packet of the timer topic becomes a [ConferenceTimerDto], strict by design:
 *
 *  - **never from a participant**: participants hold `can_publish_data`, so anything on this topic that carries a sending participant is a
 *    forgery ("the time is up") and is dropped. Server packets (`SendData`) carry no sender;
 *  - 1..[CONFERENCE_TIMER_MAX_PAYLOAD_BYTES] bytes;
 *  - exactly `{"v":1,"e":"<16 hex>","seq":N,"s":"R|P|X|O","d":<ms>,"rem":<ms>,"t":<epochMs>}`: `v` is 1, `seq` a non-negative integer, `d` in
 *    0..24 h, `rem` in 0..60 min, `t` positive, every number really a JSON number (not a string), no extra key. Anything else -- or invalid
 *    JSON -- yields `null`. Never throws.
 */
internal fun conferenceTimerPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): ConferenceTimerDto? {
    if (fromParticipant) return null
    if (payload.isEmpty() || payload.size > CONFERENCE_TIMER_MAX_PAYLOAD_BYTES) return null
    val obj = runCatching { Json.parseToJsonElement(payload.decodeToString()) }.getOrNull() as? JsonObject ?: return null
    if (obj.keys != setOf("v", "e", "seq", "s", "d", "rem", "t")) return null

    fun number(key: String): Long? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    if ((obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != 1) return null
    val epoch = (obj["e"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    if (!TIMER_EPOCH.matches(epoch)) return null
    val seq = number("seq") ?: return null
    if (seq < 0) return null
    val status =
        when ((obj["s"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) {
            "R" -> ConferenceTimerStatus.RUNNING
            "P" -> ConferenceTimerStatus.PAUSED
            "X" -> ConferenceTimerStatus.EXPIRED
            "O" -> ConferenceTimerStatus.OFF
            else -> return null
        }
    val duration = number("d") ?: return null
    if (duration !in 0..TIMER_MAX_DURATION_MS) return null
    val remaining = number("rem") ?: return null
    if (remaining !in 0..TIMER_MAX_REMAINING_MS) return null
    val serverNow = number("t") ?: return null
    if (serverNow <= 0) return null
    return ConferenceTimerDto(
        epoch = epoch,
        seq = seq,
        status = status,
        initialDurationMs = duration,
        remainingMs = remaining,
        serverNowEpochMs = serverNow,
    )
}

/**
 * Estimates `serverEpochMs - localMonotonicMs` the way NTP does: from a request / response pair the offset is `t - (send + receive) / 2`
 * with an uncertainty of half the round trip, and the sample with the SMALLEST round trip inside a sliding window ([windowMs], 5 min) wins.
 * A packet (no request, so no round trip) is a sample with an infinite round trip: it is used only while no RPC sample exists (an event
 * guest has no RPC), where its error is about one network latency.
 */
internal class ServerClockOffset(
    private val windowMs: Double = 300_000.0,
) {
    private class Sample(
        val offset: Double,
        val rtt: Double,
        val at: Double,
    )

    private val samples = ArrayList<Sample>()

    fun addRpcSample(
        serverNowEpochMs: Long,
        sendLocal: Double,
        recvLocal: Double,
    ) {
        val rtt = recvLocal - sendLocal
        if (rtt < 0.0) return
        add(Sample(offset = serverNowEpochMs - (sendLocal + recvLocal) / 2.0, rtt = rtt, at = recvLocal))
    }

    fun addPacketSample(
        serverNowEpochMs: Long,
        recvLocal: Double,
    ) {
        add(Sample(offset = serverNowEpochMs - recvLocal, rtt = Double.POSITIVE_INFINITY, at = recvLocal))
    }

    /** The best offset known at [nowLocal], or `null` if there is no sample inside the window. */
    fun offset(nowLocal: Double): Double? {
        samples.removeAll { nowLocal - it.at > windowMs }
        // the smallest round trip wins; among packet samples (all infinite) the most recent one
        return samples.minWithOrNull(compareBy<Sample> { it.rtt }.thenByDescending { it.at })?.offset
    }

    private fun add(sample: Sample) {
        samples += sample
        if (samples.size > MAX_SAMPLES) samples.removeAt(0)
    }

    private companion object {
        const val MAX_SAMPLES = 32
    }
}

/** When (local monotonic ms) the timer of [dto] ends: `t + rem - offset`; without any offset the packet counts as "just received". */
internal fun endsAtLocal(
    dto: ConferenceTimerDto,
    offset: Double?,
    recvLocal: Double,
): Double = if (offset == null) recvLocal + dto.remainingMs else dto.serverNowEpochMs + dto.remainingMs - offset

/** The time left at [nowLocal]; never negative. */
internal fun timerRemainingMs(
    endsAtLocal: Double,
    nowLocal: Double,
): Long = (endsAtLocal - nowLocal).coerceAtLeast(0.0).toLong()

/** Whole seconds shown for [ms], rounded UP (so the display reads 00:00 only when the time is really up). */
internal fun timerWholeSeconds(ms: Long): Long = ceil(ms.coerceAtLeast(0L) / MS_PER_SECOND.toDouble()).toLong()

/** `MM:SS`, rounded up: 0 -> "00:00", 999 -> "00:01", 10 000 -> "00:10", 3 600 000 -> "60:00". */
internal fun formatMmSs(ms: Long): String {
    val total = timerWholeSeconds(ms)
    return (total / SECONDS_PER_MINUTE).toString().padStart(2, '0') + ":" + (total % SECONDS_PER_MINUTE).toString().padStart(2, '0')
}

internal enum class TimerPhase { NORMAL, FINAL, EXPIRED }

/** FINAL is `0 < remaining <= 10 s` of a running timer; EXPIRED a timer that ran out; everything else (also paused) is NORMAL. */
internal fun timerPhase(
    remainingMs: Long,
    status: ConferenceTimerStatus,
): TimerPhase =
    when {
        status == ConferenceTimerStatus.EXPIRED -> TimerPhase.EXPIRED
        status == ConferenceTimerStatus.RUNNING && remainingMs <= 0 -> TimerPhase.EXPIRED
        status == ConferenceTimerStatus.RUNNING && remainingMs <= CONFERENCE_TIMER_FINAL_PHASE_MS -> TimerPhase.FINAL
        else -> TimerPhase.NORMAL
    }

/** "2 Minuten 15 Sekunden" -- the duration in words (plural forms through the catalog), rounded up to whole seconds. */
internal fun timerSpokenDuration(ms: Long): String {
    val total = timerWholeSeconds(ms)
    val minutes = (total / SECONDS_PER_MINUTE).toInt()
    val seconds = (total % SECONDS_PER_MINUTE).toInt()
    return buildList {
        if (minutes > 0) add(ngettext("%1 Minute", "%1 Minuten", minutes, minutes))
        if (seconds > 0 || minutes == 0) add(ngettext("%1 Sekunde", "%1 Sekunden", seconds, seconds))
    }.joinToString(" ")
}

/** The accessible name of the display: "Timer: noch 2 Minuten 15 Sekunden". */
internal fun timerAccessibleName(remainingMs: Long): String = gettext("Timer: noch %1", timerSpokenDuration(remainingMs))

/** What the pill / dock / floating window show; the controller computes it, the widgets only paint it. */
internal data class TimerDisplay(
    val status: ConferenceTimerStatus,
    val remainingMs: Long,
    val initialMs: Long,
    val phase: TimerPhase,
    /** "Timer wurde zurückgesetzt" is shown (the server restarted while a timer was set; 8 s). */
    val resetNotice: Boolean = false,
) {
    val visible: Boolean get() = status != ConferenceTimerStatus.OFF

    /** The share of the progress ring that is still full (0..1). */
    val fraction: Double
        get() = if (initialMs <= 0L) 0.0 else (remainingMs.toDouble() / initialMs.toDouble()).coerceIn(0.0, 1.0)

    companion object {
        val OFF = TimerDisplay(ConferenceTimerStatus.OFF, 0L, 0L, TimerPhase.NORMAL)
    }
}

/** The outcome of [ConferenceTimerView.accept]. */
internal enum class TimerAccepted { NEWER, EPOCH_CHANGED, STALE }

/**
 * The timer as this client knows it. Another epoch (the server restarted) always wins; within an epoch only a larger `seq` does, so an
 * out-of-order or duplicated packet can never bring an old state back. The same `seq` is [TimerAccepted.STALE] (nothing changed) -- the
 * caller may still use such a snapshot to refresh the clock offset.
 */
internal class ConferenceTimerView {
    var current: ConferenceTimerDto? = null
        private set

    fun accept(dto: ConferenceTimerDto): TimerAccepted {
        val known = current
        val result =
            when {
                known == null -> TimerAccepted.NEWER
                known.epoch != dto.epoch -> TimerAccepted.EPOCH_CHANGED
                dto.seq > known.seq -> TimerAccepted.NEWER
                else -> TimerAccepted.STALE
            }
        if (result != TimerAccepted.STALE) current = dto
        return result
    }
}

/**
 * The words the timer says to a screen reader -- throttled so a burst never talks over the room: at most one note per [gapMs] (5 s), except
 * "Zeit abgelaufen", which is always said. A late joiner (the first snapshot of a call) hears nothing about the state they just found.
 */
internal class TimerAnnouncementPlanner(
    private val now: () -> Double,
    private val gapMs: Double = 5000.0,
) {
    private var lastAt = Double.NEGATIVE_INFINITY

    /** The announcement for the step [prev] -> [next] (a state change of the server), or `null`. */
    fun onTransition(
        prev: ConferenceTimerDto?,
        next: ConferenceTimerDto,
        lateJoin: Boolean,
    ): String? {
        if (lateJoin) return null
        val before = prev?.status ?: ConferenceTimerStatus.OFF
        val after = next.status
        val startedFresh = before == ConferenceTimerStatus.OFF || before == ConferenceTimerStatus.EXPIRED
        val text =
            when {
                after == ConferenceTimerStatus.RUNNING && startedFresh ->
                    gettext("Timer gestartet: %1", timerSpokenDuration(next.remainingMs))
                after == ConferenceTimerStatus.PAUSED && before == ConferenceTimerStatus.RUNNING -> gettext("Timer pausiert")
                after == ConferenceTimerStatus.RUNNING && before == ConferenceTimerStatus.PAUSED -> gettext("Timer fortgesetzt")
                after == ConferenceTimerStatus.OFF && before != ConferenceTimerStatus.OFF -> gettext("Timer gestoppt")
                else -> null
            } ?: return null
        return throttled(text)
    }

    /**
     * The announcement for the countdown step [prevRemaining] -> [remaining]: "Noch 10 Sekunden" when 10 s are crossed, "Noch eine Minute" when
     * a minute is crossed (only for a timer of at least two minutes), "Zeit abgelaufen" at zero (never throttled). One jump may cross several
     * marks (a tab that was asleep): only the most urgent is said.
     */
    fun onTick(
        prevRemaining: Long,
        remaining: Long,
        initialMs: Long,
    ): String? {
        if (remaining <= 0L && prevRemaining > 0L) {
            lastAt = now()
            return gettext("Zeit abgelaufen")
        }
        if (remaining <= 0L) return null
        if (prevRemaining > CONFERENCE_TIMER_FINAL_PHASE_MS && remaining <= CONFERENCE_TIMER_FINAL_PHASE_MS) {
            return throttled(gettext("Noch 10 Sekunden"))
        }
        if (initialMs >= TWO_MINUTES_MS && prevRemaining > MINUTE_MS && remaining <= MINUTE_MS) {
            return throttled(gettext("Noch eine Minute"))
        }
        return null
    }

    /** The reset notice after a server restart; counted into the throttle like every other note. */
    fun onReset(): String? = throttled(gettext("Timer wurde zurückgesetzt"))

    private fun throttled(text: String): String? {
        val t = now()
        if (t - lastAt < gapMs) return null
        lastAt = t
        return text
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val TWO_MINUTES_MS = 120_000L
    }
}

/** The gong sounds only for an end that was LIVE: the countdown crossed zero just now (not older than 2 s), and it is not the first snapshot. */
internal fun shouldPlayGong(
    prevRemaining: Long,
    remaining: Long,
    expiredAgoMs: Double,
    initialSnapshot: Boolean,
): Boolean = !initialSnapshot && prevRemaining > 0L && remaining <= 0L && expiredAgoMs <= GONG_MAX_LATENESS_MS

private const val GONG_MAX_LATENESS_MS = 2000.0

/** The seconds of the dialog's own-time fields ("Eigene Zeit"): minutes 0..60, seconds 0..59, total 10..3600 -- else `null`. */
internal fun customDurationSeconds(
    minutes: Int?,
    seconds: Int?,
): Int? {
    if (minutes == null && seconds == null) return null
    val m = minutes ?: 0
    val s = seconds ?: 0
    if (m !in 0..CONFERENCE_TIMER_MAX_SECONDS / SECONDS_PER_MINUTE.toInt() || s !in 0..(SECONDS_PER_MINUTE - 1).toInt()) return null
    val total = m * SECONDS_PER_MINUTE.toInt() + s
    return total.takeIf { it in CONFERENCE_TIMER_MIN_SECONDS..CONFERENCE_TIMER_MAX_SECONDS }
}

/** Whether the dialog may offer "+[extensionSeconds]" for a timer with [remainingMs] left (the server refuses more than 60 min remaining). */
internal fun timerExtensionAllowed(
    remainingMs: Long,
    extensionSeconds: Int,
): Boolean =
    extensionSeconds in CONFERENCE_TIMER_EXTENSIONS_SECONDS && remainingMs + extensionSeconds * MS_PER_SECOND <= TIMER_MAX_REMAINING_MS
