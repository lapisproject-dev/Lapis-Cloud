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
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * V1.9.133 -- the gong at the end of a conference timer: ONE soft, bell-like tone, synthesised with WebAudio (no file, nothing loaded). It
 * is played by the tab's single audio player ([BrowserJoinLeaveSoundPlayer.playTimerGong], so it shares the audio context, the unlock
 * inside the join click and the speaker the person chose) and only when the end was experienced live (see `shouldPlayGong`). A browser
 * that does not let the audio context run just drops it -- never queued, a late gong would be wrong.
 *
 * The sound: a fundamental of [TIMER_GONG_BASE_HZ] with two inharmonic partials (x2.01, x2.76) at weights 1 : 0.4 : 0.2, a 5 ms attack
 * and an exponential decay over about 2.2 s; the sum of all partials never exceeds [TIMER_GONG_PEAK_GAIN].
 */
internal const val TIMER_GONG_STORAGE_KEY = "lapis.conference.timerGong"

/** The fundamental of the gong in Hz (G4). */
internal const val TIMER_GONG_BASE_HZ = 392.0

/** Peak of the SUM of all partials; quieter than a voice, never a jump scare. */
internal const val TIMER_GONG_PEAK_GAIN = 0.12

/** Seconds of the linear attack. */
internal const val TIMER_GONG_ATTACK_S = 0.005

/** Seconds of the exponential decay (to silence). */
internal const val TIMER_GONG_DECAY_S = 2.2

/** The partials: frequency ratio to the fundamental and the weight in the mix. */
internal val TIMER_GONG_PARTIALS: List<Pair<Double, Double>> = listOf(1.0 to 1.0, 2.01 to 0.4, 2.76 to 0.2)

/** Frequency and peak level of every partial; the levels sum to [TIMER_GONG_PEAK_GAIN]. */
internal fun timerGongPartials(): List<Pair<Double, Double>> {
    val total = TIMER_GONG_PARTIALS.sumOf { it.second }
    return TIMER_GONG_PARTIALS.map { (ratio, weight) -> TIMER_GONG_BASE_HZ * ratio to TIMER_GONG_PEAK_GAIN * weight / total }
}

/** The person's own switch of the gong (default: on). */
internal interface TimerGongPreference {
    fun isOn(): Boolean

    fun setOn(on: Boolean)
}

/**
 * `localStorage`; only `"off"` is written (on = no entry). A blocked or throwing storage means "on" and the choice lives in memory for the
 * rest of the page (no message).
 */
internal class LocalStorageTimerGongPreference : TimerGongPreference {
    private var inMemory: Boolean? = null

    override fun isOn(): Boolean {
        inMemory?.let { return it }
        return try {
            localStorage[TIMER_GONG_STORAGE_KEY] != "off"
        } catch (e: Throwable) {
            true
        }
    }

    override fun setOn(on: Boolean) {
        inMemory = on
        try {
            if (on) localStorage.removeItem(TIMER_GONG_STORAGE_KEY) else localStorage[TIMER_GONG_STORAGE_KEY] = "off"
        } catch (e: Throwable) {
            // blocked storage: the choice is kept for this page only
        }
    }
}

/** The one personal gong switch of the tab, shared by every call screen. */
internal object ConferenceTimerGong {
    val preference: TimerGongPreference by lazy { LocalStorageTimerGongPreference() }
}

private var timerGongSwitchSerial = 0

/** The sentence under the switch. */
internal fun timerGongHint(): String = gettext("Ein leiser Gong, wenn ein Timer abläuft. Er erklingt nur, wenn Ihr Browser Ton zulässt.")

/**
 * The personal switch in the "Mehr" panel: label left, `role="switch"` button right, a hint below that is always visible. Nothing is
 * announced: the state is the knob's side. Switching on plays the gong once (inside the click, which also unlocks the audio context).
 */
internal class TimerGongSwitchRow(
    parent: Container,
    private val preference: TimerGongPreference,
    private val player: JoinLeaveSoundPlayer,
) {
    private val serial = ++timerGongSwitchSerial
    private val labelId = "lapis-timer-gong-label-$serial"
    private val hintId = "lapis-timer-gong-hint-$serial"

    val root: Div = parent.div(className = "lapis-timer-gong-row")
    val switchButton: Button

    init {
        val line = root.div(className = "lapis-noise-suppression-line")
        val label = line.span(tr("Gong am Ende"))
        label.setAttribute("id", labelId)
        switchButton = line.button("", className = "lapis-noise-switch ms-auto")
        switchButton.setAttribute("type", "button")
        switchButton.setAttribute("role", "switch")
        switchButton.setAttribute("aria-labelledby", labelId)
        switchButton.setAttribute("aria-describedby", hintId)
        val hint = root.div(timerGongHint(), className = "text-muted small")
        hint.setAttribute("id", hintId)
        switchButton.onClick {
            val on = !preference.isOn()
            // a storage that refuses must never break the switch: the real preference keeps the choice in memory, this is the second net
            runCatching { preference.setOn(on) }
            if (on) {
                // synchronous, inside the gesture: the audio context may only start here
                player.unlock()
                player.playTimerGong(confirmation = true)
            }
            // explicit receiver: inside this lambda a bare `refresh()` is the Button's own `Widget.refresh()`
            this@TimerGongSwitchRow.refresh()
        }
        refresh()
    }

    /** Re-reads the personal choice. */
    fun refresh() {
        switchButton.setAttrIfChanged("aria-checked", preference.isOn().toString())
    }
}
