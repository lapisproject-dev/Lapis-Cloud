package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.icon
import io.kvision.html.span
import kotlinx.browser.document
import kotlinx.browser.window

/** How long the sign stays in the picture after a bell, in milliseconds (a further packet restarts the window). */
internal const val ENCOUNTER_BELL_DISPLAY_MS = 5_000

/** Length of the fade-out in milliseconds; matches the CSS transition (the element is hidden after it). */
internal const val ENCOUNTER_BELL_FADE_MS = 250

/** How long the fixed sentence stays in the polite region before it is cleared, so that a LATER bell is announced again. */
internal const val ENCOUNTER_BELL_ANNOUNCE_CLEAR_MS = 1_000

/** How long the pulpit's own bell button stays locked after a ring, in milliseconds (the server swallows a second bell within ten seconds anyway). */
internal const val ENCOUNTER_BELL_CLIENT_LOCK_MS = 10_000

/** What a bell packet does to the sign. */
internal enum class EncounterBellOutcome {
    /** The sign was off: show it, announce the fixed sentence once and (if wanted) ring. */
    SHOW,

    /** The sign was on: only restart the window. Nothing is announced, nothing rings, and never a second sign opens. */
    EXTEND,
}

/**
 * V1.9.96 -- the pure timer logic of the sign: a new packet while the sign is on restarts the [displayMs] window and never opens a second
 * sign; only the first packet of a window is announced and sounded. Holds one number (the end of the window): no counter, no history.
 */
internal class EncounterBellTimer(
    private val now: () -> Double,
    private val displayMs: Int = ENCOUNTER_BELL_DISPLAY_MS,
) {
    private var until = Double.NEGATIVE_INFINITY

    fun onPacket(): EncounterBellOutcome {
        val t = now()
        val wasVisible = t < until
        until = t + displayMs
        return if (wasVisible) EncounterBellOutcome.EXTEND else EncounterBellOutcome.SHOW
    }

    fun isVisibleAt(t: Double): Boolean = t < until
}

/** Pure decision: the bell rings only when the person switched the sound on in this browser AND the page is visible (never in a background tab). */
internal fun encounterBellShouldSound(
    soundOn: Boolean,
    pageVisible: Boolean,
): Boolean = soundOn && pageVisible

/**
 * V1.9.96 -- the sign of the bell over the pulpit area: the bell symbol (a drawn `<i>` element, never markup from text) and the word of
 * [EncounterTerms.bellWord], for EVERYBODY in the room (the congregation and the stewards too). While this browser's sound is off, a
 * second line says so. The visual part is `aria-hidden`; a persistent polite region ([live], created by the room BEFORE the first bell --
 * a region that appears together with its text is not announced) receives the one fixed sentence when the sign opens and is cleared a
 * moment later. There is nothing to click in the sign (WCAG 2.2.1: no timing the person has to beat).
 *
 * The sign is set BEFORE the sound is tried, and the sound runs inside `runCatching`: a sound that fails can never keep the sign away.
 * Holds no name, no time of day and no counter; the only state is "is the sign on" and the timers, which [dispose] cancels.
 */
internal class EncounterBellDisplay(
    host: Container,
    private val terms: EncounterTerms,
    private val live: Div,
    private val timer: EncounterBellTimer,
    private val soundOn: () -> Boolean,
    private val sound: EncounterBellSound,
    private val scheduler: EncounterBlessingScheduler = browserBlessingScheduler,
    private val reducedMotion: () -> Boolean = { window.matchMedia("(prefers-reduced-motion: reduce)").matches },
    private val visible: () -> Boolean = { (document.asDynamic().visibilityState as? String) != "hidden" },
) {
    private val root: Div = Div(className = "lapis-encounter-bell").also { host.add(it) }
    private lateinit var muteLine: Span
    private var cancelHide: (() -> Unit)? = null
    private var cancelFade: (() -> Unit)? = null
    private var cancelClear: (() -> Unit)? = null
    private var disposed = false

    init {
        root.setAttribute("aria-hidden", "true")
        root.setAttribute("hidden", "")
        // the symbol is an icon element of the icon font (never markup from text), then the word and the note are text nodes of their own (never markup)
        root.icon("fas fa-bell").setAttribute("aria-hidden", "true")
        root.span(terms.bellWord(), className = "lapis-encounter-bell-word")
        muteLine = root.span(terms.bellSoundOffNote(), className = "lapis-encounter-bell-mute")
        muteLine.setAttribute("hidden", "")
    }

    /** `true` while the sign is in the picture (test aid). */
    val shown: Boolean get() = root.getAttribute("hidden") == null

    /** A bell packet arrived (accepted by the session): show, or extend the window. */
    fun onBell() {
        if (disposed) return
        val outcome = timer.onPacket()
        cancelHide?.invoke()
        cancelFade?.invoke()
        cancelFade = null
        if (outcome == EncounterBellOutcome.SHOW) {
            val on = readSoundOn()
            if (on) muteLine.setAttribute("hidden", "") else muteLine.removeAttribute("hidden")
            val wantSound = encounterBellShouldSound(soundOn = on, pageVisible = readVisible())
            root.removeAttribute("hidden")
            root.addCssClass(CLASS_ON)
            announce()
            if (wantSound) runCatching { sound.ring() }
        }
        cancelHide = scheduler.schedule(ENCOUNTER_BELL_DISPLAY_MS) { fadeOut() }
    }

    private fun readSoundOn(): Boolean = runCatching { soundOn() }.getOrDefault(false)

    private fun readVisible(): Boolean = runCatching { visible() }.getOrDefault(false)

    private fun announce() {
        val sentence = terms.bellAnnouncement()
        live.content = sentence
        cancelClear?.invoke()
        cancelClear = scheduler.schedule(ENCOUNTER_BELL_ANNOUNCE_CLEAR_MS) { live.content = "" }
    }

    private fun fadeOut() {
        root.removeCssClass(CLASS_ON)
        if (reducedMotion()) {
            root.setAttribute("hidden", "")
        } else {
            cancelFade = scheduler.schedule(ENCOUNTER_BELL_FADE_MS) { root.setAttribute("hidden", "") }
        }
    }

    /** Ends all timers; the room calls this from its own dispose. */
    fun dispose() {
        disposed = true
        cancelHide?.invoke()
        cancelFade?.invoke()
        cancelClear?.invoke()
        cancelHide = null
        cancelFade = null
        cancelClear = null
    }

    private companion object {
        const val CLASS_ON = "lapis-encounter-bell--visible"
    }
}
