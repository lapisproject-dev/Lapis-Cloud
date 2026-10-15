package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_FINAL_SECONDS
import network.lapis.cloud.shared.domain.EncounterFloorDto
import network.lapis.cloud.shared.domain.EncounterFloorSignal
import kotlin.math.ceil

/** The monotonic clock of the floor in milliseconds (`performance.now()`): immune to a changed system clock, the only clock the countdown uses. */
internal fun browserMonotonicNow(): Double = window.performance.now()

/** What the room shows of the floor at this moment. */
internal data class EncounterFloorView(
    val active: Boolean,
    /** The viewer has the floor. */
    val self: Boolean,
    val remainingSeconds: Int,
    /** The last [ENCOUNTER_FLOOR_FINAL_SECONDS] seconds: the countdown is emphasised (no animation). */
    val final: Boolean,
    /** The viewer's own microphone is switched on. */
    val microphoneOn: Boolean,
)

/** What the model asks of the room. */
internal interface EncounterFloorHost {
    /** The view changed (or one second passed): update the banner and the list. */
    fun floorChanged()

    /** One of the four fixed sentences, for the polite live region. Never a name. */
    fun announceFloor(sentence: String)

    /** The viewer was given the floor: their raised hand is no longer a request. */
    fun lowerOwnHand()

    /** The viewer's floor is over: the microphone must be ended for good. */
    fun endFloorMicrophone()
}

/**
 * V1.9.138 -- the state machine of the temporary floor on the client, with no DOM and no wall clock. It mirrors what the SERVER says
 * (a packet without a participant, or the answer of `floorState`); it never decides anything: whether anybody may speak is the LiveKit
 * permission the server raised or took back.
 *
 * - The countdown is [monotonicNow] only: a packet says "t seconds left", the model remembers the monotonic instant it ends at. Nothing
 *   reads `Date.now()`; changing the clock of the machine changes nothing.
 * - At most three sentences are announced over a floor's life: given (to the viewer: "you", to somebody else: "given"), ten seconds left,
 *   ended -- never once per second, never a name.
 * - The floor microphone ends when the server says "off", when the countdown reaches 0, or when the own microphone permission is gone.
 */
internal class EncounterFloorModel(
    private val monotonicNow: () -> Double,
    private val terms: EncounterTerms,
    private val host: EncounterFloorHost,
) {
    private var active = false
    private var self = false
    private var grant: FloorGrant? = null
    private var endsAt = 0.0
    private var tenSecondsAnnounced = false
    private var microphoneOn = false

    val view: EncounterFloorView
        get() {
            val remaining = if (active) remainingSeconds() else 0
            return EncounterFloorView(
                active = active,
                self = self,
                remainingSeconds = remaining,
                final = active && remaining in 1..ENCOUNTER_FLOOR_FINAL_SECONDS,
                microphoneOn = microphoneOn,
            )
        }

    /** The proof the server gave the viewer the floor, `null` while they do not have it. */
    val currentGrant: FloorGrant? get() = grant

    /** A server packet. */
    fun onSignal(signal: EncounterFloorSignal) {
        when (signal) {
            EncounterFloorSignal.Off -> finish(announce = active)
            is EncounterFloorSignal.On -> onOn(signal)
            is EncounterFloorSignal.You -> onYou(signal)
        }
    }

    /** The answer of `floorState`, mapped onto the same signals (it carries the server's truth for a client that missed a packet). */
    fun onSnapshot(dto: EncounterFloorDto) {
        when {
            !dto.active || dto.remainingSeconds <= 0 -> onSignal(EncounterFloorSignal.Off)
            dto.self -> onSignal(EncounterFloorSignal.You(dto.remainingSeconds))
            else -> onSignal(EncounterFloorSignal.On(dto.remainingSeconds))
        }
    }

    /** Called every second by the room. */
    fun onTick() {
        if (!active) return
        val remaining = remainingSeconds()
        if (remaining <= ENCOUNTER_FLOOR_FINAL_SECONDS && !tenSecondsAnnounced) {
            tenSecondsAnnounced = true
            host.announceFloor(terms.floorTenSecondsAnnouncement())
        }
        if (remaining <= 0) {
            finish(announce = true)
        } else {
            host.floorChanged()
        }
    }

    /** The own microphone permission changed; losing it ends the floor of the viewer at once. */
    fun onMicrophonePermission(allowed: Boolean) {
        if (!allowed && active && self) finish(announce = true)
    }

    /** The viewer switched the floor microphone on or off (the room tells the model, so the banner can show it). */
    fun onMicrophoneSwitched(on: Boolean) {
        if (!active || !self) return
        microphoneOn = on
        host.floorChanged()
    }

    /** The room leaves: nothing is announced and nothing is left running. */
    fun reset() {
        if (active && self) host.endFloorMicrophone()
        active = false
        self = false
        grant = null
        microphoneOn = false
        tenSecondsAnnounced = false
    }

    private fun onOn(signal: EncounterFloorSignal.On) {
        if (!active) {
            start(self = false, remainingSeconds = signal.remainingSeconds)
            host.announceFloor(terms.floorGivenAnnouncement())
        } else {
            // A broadcast "on" also reaches the holder: they keep "I have the floor"; only the countdown is adjusted.
            adjust(signal.remainingSeconds)
        }
        host.floorChanged()
    }

    private fun onYou(signal: EncounterFloorSignal.You) {
        grant = FloorGrant.issue(signal)
        if (!active || !self) {
            start(self = true, remainingSeconds = signal.remainingSeconds)
            host.lowerOwnHand()
            // the viewer hears "you have the floor" (also when they first thought somebody else had it), never also "given"
            host.announceFloor(terms.floorSelfAnnouncement())
        } else {
            adjust(signal.remainingSeconds)
        }
        host.floorChanged()
    }

    private fun start(
        self: Boolean,
        remainingSeconds: Int,
    ) {
        active = true
        this.self = self
        microphoneOn = false
        tenSecondsAnnounced = false
        adjust(remainingSeconds)
    }

    private fun adjust(remainingSeconds: Int) {
        endsAt = monotonicNow() + remainingSeconds * MS_PER_SECOND
        if (remainingSeconds > ENCOUNTER_FLOOR_FINAL_SECONDS) tenSecondsAnnounced = false
    }

    private fun finish(announce: Boolean) {
        if (!active) return
        val wasSelf = self
        active = false
        self = false
        grant = null
        microphoneOn = false
        tenSecondsAnnounced = false
        if (wasSelf) host.endFloorMicrophone()
        if (announce) host.announceFloor(terms.floorEndedAnnouncement())
        host.floorChanged()
    }

    private fun remainingSeconds(): Int = ceil((endsAt - monotonicNow()) / MS_PER_SECOND).toInt().coerceAtLeast(0)

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
