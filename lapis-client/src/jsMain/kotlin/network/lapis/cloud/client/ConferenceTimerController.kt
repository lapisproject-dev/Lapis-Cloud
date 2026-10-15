package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.ConferenceTimerView
import network.lapis.cloud.client.livekit.ServerClockOffset
import network.lapis.cloud.client.livekit.TimerAccepted
import network.lapis.cloud.client.livekit.TimerAnnouncementPlanner
import network.lapis.cloud.client.livekit.TimerDisplay
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.client.livekit.endsAtLocal
import network.lapis.cloud.client.livekit.shouldPlayGong
import network.lapis.cloud.client.livekit.timerPhase
import network.lapis.cloud.client.livekit.timerRemainingMs
import network.lapis.cloud.client.livekit.timerWholeSeconds
import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException

/*
 * V1.9.133 -- the stateful part of the display-only conference timer, free of DOM and RPC types so a test can drive it with a fake gateway
 * and a fake clock: which snapshot is newer, where the countdown ends on the LOCAL monotonic clock, the 4 Hz ticker (only while running),
 * the screen-reader notes and the one gong. The screen owns the widgets; it only reads [ConferenceTimerController.display] after `onChanged`.
 * It contains NO `rpcService` read (the ledger of `ClientDataStateTripwireTest` must not grow): the gateway is built in `ConferenceScreen.kt`.
 */

/** The six calls of `IConferenceTimerService` as the controller needs them. `null` = the call failed (the gateway already told the person). */
internal interface ConferenceTimerGateway {
    suspend fun start(seconds: Int): ConferenceTimerDto?

    suspend fun pause(): ConferenceTimerDto?

    suspend fun resume(): ConferenceTimerDto?

    suspend fun stop(): ConferenceTimerDto?

    suspend fun extend(seconds: Int): ConferenceTimerDto?

    suspend fun state(): ConferenceTimerDto?
}

/**
 * A control call of the timer: a refusal of the state ([ConflictException]) or of the range ([BadRequestException]) gets a toast in the
 * timer's own words (Kilua transmits only the exception TYPE, never its message); every other failure takes the shared [guarded] path
 * (forbidden, session expired, unknown error). `null` = the call failed and the person has been told.
 */
internal suspend fun <T> timerGuarded(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        notifyError(gettext("Der Timer lässt sich jetzt nicht ändern. Bitte versuchen Sie es erneut."))
        null
    } catch (e: BadRequestException) {
        notifyError(gettext("Die gewählte Zeit ist nicht möglich (10 Sekunden bis 60 Minuten)."))
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/** How often the countdown is repainted while a timer runs. */
internal const val CONFERENCE_TIMER_TICK_MS = 250

/** A running timer is read from the server once a minute (a lost broadcast, a drifting offset). */
internal const val CONFERENCE_TIMER_RESYNC_MS = 60_000.0

/** How long "Timer wurde zurückgesetzt" stays visible. */
internal const val CONFERENCE_TIMER_RESET_NOTICE_MS = 8_000

internal class ConferenceTimerController(
    private val gateway: ConferenceTimerGateway,
    private val scope: CoroutineScope,
    /** The monotonic clock (`performance.now()`). */
    private val perfNow: () -> Double,
    /** Everything that is drawn from the state is redrawn (pill, dock, floating window). */
    private val onChanged: () -> Unit,
    /** The timer's own polite live region. */
    private val announce: (String) -> Unit,
    /** Plays the gong (the caller checks the personal switch and never throws). */
    private val gong: () -> Unit,
    /** Starts a periodic callback; returns the function that stops it. */
    private val startTicker: (periodMs: Int, tick: () -> Unit) -> () -> Unit,
    /** Moderators may control the timer. */
    val isModerator: Boolean,
    /** An account-less event guest has no RPC: they only ever see the broadcasts. */
    private val isEventGuest: Boolean,
    private val resetNoticeMs: Int = CONFERENCE_TIMER_RESET_NOTICE_MS,
) {
    private val view = ConferenceTimerView()
    private val offset = ServerClockOffset()
    private val planner = TimerAnnouncementPlanner(now = perfNow)
    private var endsAt = 0.0
    private var lastRemaining = 0L
    private var lastShownSeconds = -1L
    private var lastPhase = TimerPhase.NORMAL
    private var firstSnapshot = true
    private var stopTicker: (() -> Unit)? = null
    private var lastResyncAt = 0.0
    private var resetJob: Job? = null
    private var resetNotice = false
    private var disposed = false

    /** `true` while a control call is in flight (the dialog's buttons wait). */
    var busy: Boolean = false
        private set

    /** What the widgets paint right now. */
    fun display(): TimerDisplay {
        val dto = view.current
        if (dto == null || dto.status == ConferenceTimerStatus.OFF) return TimerDisplay.OFF.copy(resetNotice = resetNotice)
        val remaining = remainingNow(dto)
        val status = shownStatus(dto, remaining)
        return TimerDisplay(
            status = status,
            remainingMs = remaining,
            initialMs = dto.initialDurationMs,
            phase = timerPhase(remaining, status),
            resetNotice = resetNotice,
        )
    }

    // -- inputs ---------------------------------------------------------------------------------------------------------------------

    /** A broadcast of the server (already validated by `conferenceTimerPacket`). */
    fun onPacket(dto: ConferenceTimerDto) {
        if (disposed) return
        val received = perfNow()
        offset.addPacketSample(dto.serverNowEpochMs, received)
        apply(dto, received)
    }

    /** The on-join / periodic / reconnect / visible-again read. An event guest has no RPC and measures nothing. */
    suspend fun resync() {
        if (disposed || isEventGuest) return
        val sent = perfNow()
        val dto = gateway.state() ?: return
        val received = perfNow()
        offset.addRpcSample(dto.serverNowEpochMs, sent, received)
        lastResyncAt = received
        apply(dto, received)
    }

    // -- moderator controls ---------------------------------------------------------------------------------------------------------

    suspend fun start(seconds: Int) = control { gateway.start(seconds) }

    suspend fun pause() = control { gateway.pause() }

    suspend fun resume() = control { gateway.resume() }

    suspend fun stop() = control { gateway.stop() }

    suspend fun extend(seconds: Int) = control { gateway.extend(seconds) }

    private suspend fun control(call: suspend () -> ConferenceTimerDto?) {
        if (!isModerator || busy || disposed) return
        busy = true
        onChanged()
        try {
            val sent = perfNow()
            val dto = call()
            if (dto != null) {
                val received = perfNow()
                offset.addRpcSample(dto.serverNowEpochMs, sent, received)
                apply(dto, received)
            }
        } finally {
            busy = false
            onChanged()
        }
    }

    // -- state ----------------------------------------------------------------------------------------------------------------------

    private fun apply(
        dto: ConferenceTimerDto,
        received: Double,
    ) {
        val previous = view.current
        val accepted = view.accept(dto)
        if (accepted == TimerAccepted.STALE) {
            // nothing changed, but this snapshot is a fresh clock reading: re-anchor a running countdown on the better offset
            if (dto.status == ConferenceTimerStatus.RUNNING && view.current?.status == ConferenceTimerStatus.RUNNING) {
                endsAt = endsAtLocal(dto, offset.offset(received), received)
                onChanged()
            }
            return
        }
        // A late joiner (the first snapshot of the call that is not a fresh start) hears nothing about the state they just found; somebody who
        // is in the room when a timer starts (the first snapshot is a full-length run) does hear "Timer gestartet".
        // The server builds the snapshot a little after the start (truncating to whole ms), so a fresh run never has remaining == initial.
        val freshStart =
            dto.status == ConferenceTimerStatus.RUNNING && dto.initialDurationMs - dto.remainingMs < FRESH_START_TOLERANCE_MS
        val lateJoin = firstSnapshot && !freshStart
        firstSnapshot = false
        if (dto.status == ConferenceTimerStatus.RUNNING) endsAt = endsAtLocal(dto, offset.offset(received), received)
        val resetAfterRestart =
            accepted == TimerAccepted.EPOCH_CHANGED &&
                previous != null &&
                previous.status != ConferenceTimerStatus.OFF &&
                dto.status == ConferenceTimerStatus.OFF
        if (resetAfterRestart) {
            showResetNotice()
            planner.onReset()?.let(announce)
        } else {
            if (dto.status != ConferenceTimerStatus.OFF) clearResetNotice()
            planner.onTransition(previous, dto, lateJoin)?.let(announce)
        }
        // the countdown crosses no mark because of a snapshot: the marks are measured from here on
        lastRemaining = remainingNow(dto)
        lastShownSeconds = timerWholeSeconds(lastRemaining)
        lastPhase = timerPhase(lastRemaining, shownStatus(dto, lastRemaining))
        updateTicker(dto, received)
        onChanged()
    }

    private fun remainingNow(dto: ConferenceTimerDto): Long =
        when (dto.status) {
            ConferenceTimerStatus.RUNNING -> timerRemainingMs(endsAt, perfNow())
            ConferenceTimerStatus.PAUSED -> dto.remainingMs
            ConferenceTimerStatus.EXPIRED, ConferenceTimerStatus.OFF -> 0L
        }

    private fun shownStatus(
        dto: ConferenceTimerDto,
        remaining: Long,
    ): ConferenceTimerStatus =
        if (dto.status == ConferenceTimerStatus.RUNNING && remaining <= 0L) ConferenceTimerStatus.EXPIRED else dto.status

    private fun updateTicker(
        dto: ConferenceTimerDto,
        now: Double,
    ) {
        val needed = dto.status == ConferenceTimerStatus.RUNNING && lastRemaining > 0L
        if (needed && stopTicker == null) {
            lastResyncAt = now
            stopTicker = startTicker(CONFERENCE_TIMER_TICK_MS) { tick() }
        } else if (!needed) {
            stopTicker?.invoke()
            stopTicker = null
        }
    }

    /** One step of the countdown; public so a test can drive it without a real timer. */
    fun tick() {
        if (disposed) return
        val dto = view.current ?: return
        if (dto.status != ConferenceTimerStatus.RUNNING) {
            updateTicker(dto, perfNow())
            return
        }
        val now = perfNow()
        val remaining = timerRemainingMs(endsAt, now)
        planner.onTick(lastRemaining, remaining, dto.initialDurationMs)?.let(announce)
        if (shouldPlayGong(
                prevRemaining = lastRemaining,
                remaining = remaining,
                expiredAgoMs = now - endsAt,
                initialSnapshot = false,
            )
        ) {
            gong()
        }
        val seconds = timerWholeSeconds(remaining)
        val phase = timerPhase(remaining, shownStatus(dto, remaining))
        val repaint = seconds != lastShownSeconds || phase != lastPhase
        lastRemaining = remaining
        lastShownSeconds = seconds
        lastPhase = phase
        if (remaining <= 0L) updateTicker(dto, now)
        if (!isEventGuest && remaining > 0L && now - lastResyncAt >= CONFERENCE_TIMER_RESYNC_MS) {
            lastResyncAt = now
            scope.launch { resync() }
        }
        if (repaint) onChanged()
    }

    private fun showResetNotice() {
        resetNotice = true
        resetJob?.cancel()
        resetJob =
            scope.launch {
                delay(resetNoticeMs.toLong())
                resetNotice = false
                onChanged()
            }
    }

    private fun clearResetNotice() {
        resetJob?.cancel()
        resetJob = null
        resetNotice = false
    }

    /** Called when the call ends: nothing may tick, announce or sound afterwards. */
    fun dispose() {
        disposed = true
        stopTicker?.invoke()
        stopTicker = null
        resetJob?.cancel()
    }
}

/** How much of a run may already have elapsed in the first snapshot for it to still count as a fresh start (not a late join). */
private const val FRESH_START_TOLERANCE_MS = 1_000L
