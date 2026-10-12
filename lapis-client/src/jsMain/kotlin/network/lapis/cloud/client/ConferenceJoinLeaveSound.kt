package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.localStorage
import kotlinx.browser.window
import network.lapis.cloud.client.encounter.EncounterAudioContextFactory
import network.lapis.cloud.client.encounter.browserAudioContextFactory
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * V1.9.120 -- the join and leave tones of a video conference: one short, quiet two-tone sine blip when a person joins (rising) or leaves
 * (the same two tones, mirrored), synthesised with WebAudio (no files, nothing loaded).
 *
 * The rules are decided by ONE pure function ([shouldPlayPresenceSound]) so that a test needs neither a DOM nor a clock; the stateful part
 * (the dedup timestamps) lives in [PresenceSoundGate]; the browser side in [BrowserJoinLeaveSoundPlayer], which never throws.
 *
 * Deliberately different from the bell of the encounter room: the tone also sounds in a hidden or muted tab (it is the only sign that
 * somebody arrived while the window is in the background), and a tone that cannot be played at once is DROPPED -- never queued, a late
 * "someone joined" would be wrong.
 */
internal const val JOIN_LEAVE_SOUND_MAX_PARTICIPANTS = 10

/** A person who triggered a tone is silent for this long (join and leave share the window, so a reconnect sounds once). */
internal const val JOIN_LEAVE_SOUND_DEDUP_MS = 5_000.0

/** Minimum gap between two tones of any person; a tone inside it is discarded, not delayed. */
internal const val JOIN_LEAVE_SOUND_MIN_GAP_MS = 1_000.0

/** After the SDK reports `Reconnected` it can still replay participant events for a moment; nothing sounds in this window. */
internal const val JOIN_LEAVE_SOUND_RECONNECT_GRACE_MS = 2_000.0

/** Peak level (sum of all simultaneous oscillators) of the join tone. */
internal const val JOIN_SOUND_PEAK_GAIN = 0.10

/** Peak level of the leave tone (half of the join tone). */
internal const val LEAVE_SOUND_PEAK_GAIN = 0.05

/** The two tones in Hz: the join rises from [JOIN_LEAVE_LOW_HZ] to [JOIN_LEAVE_HIGH_HZ], the leave falls the other way round. */
internal const val JOIN_LEAVE_LOW_HZ = 659.25

/** The higher of the two tones, see [JOIN_LEAVE_LOW_HZ]. */
internal const val JOIN_LEAVE_HIGH_HZ = 880.0

/** Seconds the first tone lasts. */
internal const val JOIN_LEAVE_FIRST_TONE_S = 0.11

/** Seconds the second tone lasts; it starts right after the first. */
internal const val JOIN_LEAVE_SECOND_TONE_S = 0.16

/** Seconds from "now" until the first tone starts (the nodes are connected by then). */
internal const val JOIN_LEAVE_START_DELAY_S = 0.02

/** Seconds of the linear attack of every tone. */
internal const val JOIN_LEAVE_ATTACK_S = 0.01

/** `localStorage` key of the personal switch. Only `"off"` is ever written; "on" removes the entry. */
internal const val JOIN_LEAVE_SOUND_STORAGE_KEY = "lapis.conference.joinLeaveSounds"

internal enum class PresenceSoundKind { JOIN, LEAVE }

/**
 * Whether a tone sounds. All of these must hold:
 * - the room option and the personal switch are on, the session is armed (the first roster is in) and the own connection is not
 *   reconnecting;
 * - the person is not the local one;
 * - the room is small enough on BOTH sides of the event: `max(countBefore, countAfter) <= 10` (symmetric, so a join into a room of ten and
 *   the leave out of eleven behave the same);
 * - this person did not trigger a tone within [JOIN_LEAVE_SOUND_DEDUP_MS] (join and leave share it) and no tone of anybody sounded within
 *   [JOIN_LEAVE_SOUND_MIN_GAP_MS].
 * The counts are the visible participants INCLUDING the local one, without a recorder or any other filtered identity.
 */
@Suppress("LongParameterList", "UNUSED_PARAMETER")
internal fun shouldPlayPresenceSound(
    kind: PresenceSoundKind,
    identity: String,
    localIdentity: String,
    countBefore: Int,
    countAfter: Int,
    armed: Boolean,
    reconnectingLocally: Boolean,
    personalOn: Boolean,
    roomOn: Boolean,
    nowMs: Double,
    lastForIdentityMs: Double?,
    lastAnyMs: Double?,
): Boolean {
    // join and leave follow the same rules; `kind` is part of the signature so a call site reads completely
    if (!roomOn || !personalOn || !armed || reconnectingLocally) return false
    if (identity == localIdentity) return false
    if (maxOf(countBefore, countAfter) > JOIN_LEAVE_SOUND_MAX_PARTICIPANTS) return false
    if (lastForIdentityMs != null && nowMs - lastForIdentityMs < JOIN_LEAVE_SOUND_DEDUP_MS) return false
    if (lastAnyMs != null && nowMs - lastAnyMs < JOIN_LEAVE_SOUND_MIN_GAP_MS) return false
    return true
}

/**
 * `false` for what is not a person in the room: a recorder / egress participant (kind `EGRESS`, or an identity of the egress naming scheme).
 * [kind] is whatever the SDK reports (a number or a name); `null` = unknown = a person. The ONE filter for the tone and for its counts.
 */
internal fun isPresenceCountable(
    kind: String?,
    identity: String,
): Boolean {
    val normalized = kind?.trim()?.lowercase().orEmpty()
    if (normalized == "2" || normalized == "egress") return false
    if (identity.startsWith("EG_")) return false
    return true
}

/** The dedup state of the tones of one call. Pure apart from the injected clock; a timestamp is only written for a tone that really sounds. */
internal class PresenceSoundGate(
    private val clock: () -> Double,
) {
    private val lastByIdentity = LinkedHashMap<String, Double>()
    private var lastAny: Double? = null

    /** How many identities are remembered (always bounded: only a tone that sounded is remembered, and entries expire). */
    internal val rememberedIdentities: Int get() = lastByIdentity.size

    @Suppress("LongParameterList")
    fun tryPlay(
        kind: PresenceSoundKind,
        identity: String,
        localIdentity: String,
        countBefore: Int,
        countAfter: Int,
        armed: Boolean,
        reconnectingLocally: Boolean,
        personalOn: Boolean,
        roomOn: Boolean,
    ): Boolean {
        val now = clock()
        prune(now)
        val play =
            shouldPlayPresenceSound(
                kind = kind,
                identity = identity,
                localIdentity = localIdentity,
                countBefore = countBefore,
                countAfter = countAfter,
                armed = armed,
                reconnectingLocally = reconnectingLocally,
                personalOn = personalOn,
                roomOn = roomOn,
                nowMs = now,
                lastForIdentityMs = lastByIdentity[identity],
                lastAnyMs = lastAny,
            )
        if (!play) return false
        lastByIdentity.remove(identity)
        lastByIdentity[identity] = now
        lastAny = now
        while (lastByIdentity.size > MAX_REMEMBERED) lastByIdentity.remove(lastByIdentity.keys.first())
        return true
    }

    private fun prune(now: Double) {
        val iterator = lastByIdentity.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value >= JOIN_LEAVE_SOUND_DEDUP_MS) iterator.remove()
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 32
    }
}

/**
 * Whether the own session may let a tone sound: armed once the first roster is in, off again on a new connect attempt and on a disconnect,
 * and silent between `Reconnecting` and `Reconnected` plus a short grace window (the SDK may replay participant events after it).
 */
internal class PresenceTracker(
    private val clock: () -> Double,
) {
    var armed: Boolean = false
        private set
    private var reconnecting = false
    private var graceUntil = Double.NEGATIVE_INFINITY

    fun arm() {
        armed = true
    }

    fun disarm() {
        armed = false
        reconnecting = false
        graceUntil = Double.NEGATIVE_INFINITY
    }

    fun onReconnecting() {
        reconnecting = true
    }

    fun onReconnected() {
        reconnecting = false
        graceUntil = clock() + JOIN_LEAVE_SOUND_RECONNECT_GRACE_MS
    }

    fun reconnectingLocally(): Boolean = reconnecting || clock() < graceUntil
}

/** What a session reports about one arrival or departure. The counts include the local participant and exclude filtered identities. */
class PresenceEvent(
    val identity: String,
    val countBefore: Int,
    val countAfter: Int,
    val armed: Boolean,
    val reconnectingLocally: Boolean,
)

/** The speaker of the tones. Every method is safe at any time and never throws. */
internal interface JoinLeaveSoundPlayer {
    /** Creates / resumes the audio context; call it synchronously inside a user's click. */
    fun unlock()

    fun playJoin()

    fun playLeave()

    /** The switch has just been turned on: the join tone once, as a confirmation. Must be called inside the click. */
    fun playProbe()

    /** The speaker the person chose (`null` or empty = the default). Best effort. */
    fun setSink(deviceId: String?)

    fun dispose()
}

private fun present(value: dynamic): Boolean = value != null && value != undefined

private val ignoreRejection: (dynamic) -> Unit = { }

private fun swallow(promise: dynamic) {
    try {
        if (present(promise) && present(promise["catch"])) promise["catch"](ignoreRejection)
    } catch (e: Throwable) {
        // nothing to do
    }
}

/** WebAudio synthesis: two sine oscillators per tone (the note and a faint octave), a short attack and an exponential decay. */
internal class BrowserJoinLeaveSoundPlayer(
    private val factory: EncounterAudioContextFactory = browserAudioContextFactory,
) : JoinLeaveSoundPlayer {
    private var context: dynamic = null
    private var sinkId: String = ""
    private var disposed = false

    private fun contextOrCreate(): dynamic {
        if (disposed) return null
        if (present(context)) return context
        val created =
            try {
                factory.create()
            } catch (e: Throwable) {
                null
            }
        if (!present(created)) return null
        context = created
        applySink(created)
        return created
    }

    private fun applySink(ctx: dynamic) {
        try {
            if (sinkId.isNotEmpty() && present(ctx.setSinkId)) swallow(ctx.setSinkId(sinkId))
        } catch (e: Throwable) {
            // the system default speaker is used
        }
    }

    private fun resume(ctx: dynamic): dynamic =
        try {
            if (ctx.state == "suspended") ctx.resume() else null
        } catch (e: Throwable) {
            null
        }

    override fun unlock() {
        try {
            val ctx = contextOrCreate()
            if (present(ctx)) swallow(resume(ctx))
        } catch (e: Throwable) {
            // silent
        }
    }

    override fun playJoin() = play(PresenceSoundKind.JOIN)

    override fun playLeave() = play(PresenceSoundKind.LEAVE)

    override fun playProbe() {
        try {
            val ctx = contextOrCreate()
            if (!present(ctx)) return
            if (ctx.state == "running") {
                play(PresenceSoundKind.JOIN)
                return
            }
            // inside the click the context is still resuming: play once it runs (this is the person's own confirmation, not a stale event)
            val pending = resume(ctx)
            if (present(pending) && present(pending["then"])) {
                val onResumed: (dynamic) -> Unit = { play(PresenceSoundKind.JOIN) }
                swallow(pending["then"](onResumed, ignoreRejection))
            }
        } catch (e: Throwable) {
            // silent
        }
    }

    override fun setSink(deviceId: String?) {
        try {
            sinkId = deviceId.orEmpty()
            val ctx = context
            if (present(ctx)) applySink(ctx)
        } catch (e: Throwable) {
            // best effort
        }
    }

    private fun play(kind: PresenceSoundKind) {
        try {
            val ctx = context
            if (disposed || !present(ctx)) return
            if (ctx.state != "running") {
                // never queue: a context that is not running would play this tone late, when the browser finally lets it start
                swallow(resume(ctx))
                return
            }
            val peak = if (kind == PresenceSoundKind.JOIN) JOIN_SOUND_PEAK_GAIN else LEAVE_SOUND_PEAK_GAIN
            val first = if (kind == PresenceSoundKind.JOIN) JOIN_LEAVE_LOW_HZ else JOIN_LEAVE_HIGH_HZ
            val second = if (kind == PresenceSoundKind.JOIN) JOIN_LEAVE_HIGH_HZ else JOIN_LEAVE_LOW_HZ
            val start = (ctx.currentTime as Double) + JOIN_LEAVE_START_DELAY_S
            tone(ctx, first, start, JOIN_LEAVE_FIRST_TONE_S, peak)
            tone(ctx, second, start + JOIN_LEAVE_FIRST_TONE_S, JOIN_LEAVE_SECOND_TONE_S, peak)
        } catch (e: Throwable) {
            // a failing tone never takes anything else down
        }
    }

    /** One note: the fundamental (80 % of the peak) plus a faint octave (20 %), so the simultaneous sum never exceeds [peak]. */
    private fun tone(
        ctx: dynamic,
        hz: Double,
        start: Double,
        duration: Double,
        peak: Double,
    ) {
        partial(ctx, hz, start, duration, peak * FUNDAMENTAL_SHARE)
        partial(ctx, hz * 2, start, duration, peak * (1.0 - FUNDAMENTAL_SHARE))
    }

    private fun partial(
        ctx: dynamic,
        hz: Double,
        start: Double,
        duration: Double,
        level: Double,
    ) {
        val oscillator = ctx.createOscillator()
        oscillator.type = "sine"
        oscillator.frequency.value = hz
        val gain = ctx.createGain()
        gain.gain.setValueAtTime(0.0, start)
        gain.gain.linearRampToValueAtTime(level, start + JOIN_LEAVE_ATTACK_S)
        gain.gain.exponentialRampToValueAtTime(SILENCE, start + duration)
        oscillator.connect(gain)
        gain.connect(ctx.destination)
        oscillator.start(start)
        oscillator.stop(start + duration + STOP_MARGIN_S)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        val ctx = context
        context = null
        try {
            if (present(ctx)) swallow(ctx.close())
        } catch (e: Throwable) {
            // closing is best effort
        }
    }

    private companion object {
        const val FUNDAMENTAL_SHARE = 0.8
        const val SILENCE = 0.0001
        const val STOP_MARGIN_S = 0.02
    }
}

/** The person's own switch. */
internal interface JoinLeaveSoundPreference {
    fun isOn(): Boolean

    fun setOn(on: Boolean)
}

/**
 * `localStorage`; only `"off"` is written (on = no entry). A blocked or throwing storage means "on" and the choice lives in memory for the
 * rest of the page (no message).
 */
internal class LocalStorageJoinLeaveSoundPreference : JoinLeaveSoundPreference {
    private var inMemory: Boolean? = null

    override fun isOn(): Boolean {
        inMemory?.let { return it }
        return try {
            localStorage[JOIN_LEAVE_SOUND_STORAGE_KEY] != "off"
        } catch (e: Throwable) {
            true
        }
    }

    override fun setOn(on: Boolean) {
        inMemory = on
        try {
            if (on) localStorage.removeItem(JOIN_LEAVE_SOUND_STORAGE_KEY) else localStorage[JOIN_LEAVE_SOUND_STORAGE_KEY] = "off"
        } catch (e: Throwable) {
            // blocked storage: the choice is kept for this page only
        }
    }
}

/**
 * The one player and the one personal switch of the tab, shared by every call screen: the audio context is created inside the click that
 * joins (see [unlockFromGesture]) and lives on, so the screen that is built afterwards finds it ready.
 */
internal object ConferenceJoinLeaveSounds {
    val preference: JoinLeaveSoundPreference by lazy { LocalStorageJoinLeaveSoundPreference() }
    val player: JoinLeaveSoundPlayer by lazy { BrowserJoinLeaveSoundPlayer() }

    /** Synchronously inside a user's click, before any `await`. A person who switched the tones off does not get an audio context. */
    fun unlockFromGesture() {
        try {
            if (preference.isOn()) player.unlock()
        } catch (e: Throwable) {
            // silent
        }
    }

    /** Fallback for a call that was entered without a click of its own: the first gesture on the page unlocks (the listeners remove themselves). */
    fun unlockOnNextGesture() {
        try {
            val target = window.asDynamic()
            var remove: () -> Unit = {}
            val listener: (dynamic) -> Unit = {
                remove()
                unlockFromGesture()
            }
            remove = {
                try {
                    target.removeEventListener("pointerdown", listener, true)
                    target.removeEventListener("keydown", listener, true)
                } catch (e: Throwable) {
                    // gone with the page
                }
            }
            target.addEventListener("pointerdown", listener, true)
            target.addEventListener("keydown", listener, true)
        } catch (e: Throwable) {
            // silent
        }
    }
}

private var joinLeaveSwitchSerial = 0

/** The sentence under the switch; the number comes from [JOIN_LEAVE_SOUND_MAX_PARTICIPANTS], never typed into a translation. */
internal fun joinLeaveSoundHint(): String =
    gettext(
        "Ein leiser Ton, wenn jemand beitritt oder den Raum verlässt. Bei mehr als %1 Personen im Raum bleibt es still.",
        JOIN_LEAVE_SOUND_MAX_PARTICIPANTS,
    )

/**
 * The personal switch in the "Mehr" panel: label left, `role="switch"` button right, a hint below that is always visible. When the
 * moderation switched the tones off for the room the switch is `aria-disabled` and says so (it is not hidden). Nothing is announced: the
 * state is the knob's side. Switching on plays the join tone once (inside the click).
 */
internal class JoinLeaveSoundSwitchRow(
    parent: Container,
    private val preference: JoinLeaveSoundPreference,
    private val player: JoinLeaveSoundPlayer,
    private val roomEnabled: () -> Boolean,
) {
    private val serial = ++joinLeaveSwitchSerial
    private val labelId = "lapis-join-leave-label-$serial"
    private val hintId = "lapis-join-leave-hint-$serial"

    val root: Div = parent.div(className = "lapis-join-leave-sound-row")
    val switchButton: Button
    private val roomOffLine: Div

    /** Whether the "the moderation switched the tones off" line is shown. */
    internal val roomOffReasonVisible: Boolean get() = roomOffLine.visible

    init {
        val line = root.div(className = "lapis-noise-suppression-line")
        val label = line.span(tr("Töne bei Beitritt und Verlassen"))
        label.setAttribute("id", labelId)
        switchButton = line.button("", className = "lapis-noise-switch ms-auto")
        switchButton.setAttribute("type", "button")
        switchButton.setAttribute("role", "switch")
        switchButton.setAttribute("aria-labelledby", labelId)
        switchButton.setAttribute("aria-describedby", hintId)
        val hint = root.div(joinLeaveSoundHint(), className = "text-muted small")
        hint.setAttribute("id", hintId)
        roomOffLine = root.div(tr("Die Moderation hat die Töne für diesen Raum ausgeschaltet."), className = "text-muted small")
        switchButton.onClick {
            if (!roomEnabled()) return@onClick
            val on = !preference.isOn()
            // a storage that refuses must never break the switch: the real preference keeps the choice in memory, this is the second net
            runCatching { preference.setOn(on) }
            if (on) {
                // synchronous, inside the gesture: the audio context may only start here
                player.unlock()
                player.playProbe()
            }
            // explicit receiver: inside this lambda a bare `refresh()` is the Button's own `Widget.refresh()`
            this@JoinLeaveSoundSwitchRow.refresh()
        }
        refresh()
    }

    /** Re-reads the personal choice and the room option (call it after the room option changed). */
    fun refresh() {
        val roomOn = roomEnabled()
        switchButton.setAttrIfChanged("aria-checked", (roomOn && preference.isOn()).toString())
        switchButton.setAttrIfChanged("aria-disabled", (!roomOn).toString())
        if (roomOn) roomOffLine.hide() else roomOffLine.show()
    }
}
