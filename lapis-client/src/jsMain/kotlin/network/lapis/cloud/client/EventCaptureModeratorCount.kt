package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.EventCaptureConsentCountDto

/** The words of the moderator's count: "Zustimmung zur Aufzeichnung: N von M". */
internal fun eventCaptureCountText(count: EventCaptureConsentCountDto): String =
    gettext("Zustimmung zur Aufzeichnung: %1 von %2", count.acknowledged, count.total)

/** `true` iff everybody in the room (at least one person) has agreed. */
internal fun eventCaptureAllAgreed(count: EventCaptureConsentCountDto): Boolean = count.total > 0 && count.acknowledged >= count.total

/** The reason the start buttons are not available while not everybody agreed, or `null` (nothing known, or everybody agreed). */
internal fun eventCaptureStartBlockedReason(count: EventCaptureConsentCountDto?): String? =
    if (count != null && count.acknowledged < count.total) {
        gettext("Noch nicht alle im Raum haben zugestimmt (%1 von %2).", count.acknowledged, count.total)
    } else {
        null
    }

/**
 * V1.9.280 -- the line for the MODERATORS of an event room that shows "Zustimmung zur Aufzeichnung: N von M" (numbers only, never a name, never "declined" versus
 * "pending"). The visible line updates at once; the SCREEN-READER announcement is a separate polite live region that is fed at most once per
 * [announceEveryMs] (a hall of people agreeing within a second is one announcement, not fifty). The pending announcement is a timer: [dispose] clears it and
 * removes both regions, so nothing outlives the call.
 *
 * The clock and the timers are injectable so a test needs no real waiting.
 */
internal class EventCaptureModeratorCount(
    private val host: Container,
    private val nowMs: () -> Double = { window.performance.now() },
    private val setTimer: (delayMs: Int, action: () -> Unit) -> Int = { delay, action -> window.setTimeout(action, delay) },
    private val clearTimer: (Int) -> Unit = { handle -> window.clearTimeout(handle) },
    private val announceEveryMs: Int = ANNOUNCE_EVERY_MS,
) {
    val line: Div = host.div(className = "text-muted small lapis-conference-consent-count")
    private val icon: Span = line.span("", className = "lapis-conference-consent-count-icon me-1")
    private val text: Span = line.span("")

    /** Visually hidden, `role="status"`, `aria-live="polite"`: only this region is read out. */
    val live: Div =
        host.div(className = "visually-hidden") {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            setAttribute("aria-atomic", "true")
        }

    /** The text the live region was last fed with (the model of what a screen reader gets; the DOM follows on the next render). */
    var announcedText: String = ""
        private set

    private var lastAnnouncedAt = Double.NEGATIVE_INFINITY
    private var pendingTimer: Int? = null
    private var pendingText: String? = null
    private var disposed = false

    /** The numbers shown right now (test aid). */
    var current: EventCaptureConsentCountDto? = null
        private set

    init {
        icon.setAttribute("aria-hidden", "true")
        line.hide()
    }

    /** The words on the visible line right now; empty while hidden (test aid). */
    val shownText: String get() = if (line.visible) (text.content ?: "") else ""

    /** `true` while the check mark is shown (everybody agreed). */
    val allAgreed: Boolean get() = current?.let(::eventCaptureAllAgreed) == true

    fun render(count: EventCaptureConsentCountDto?) {
        if (disposed || count == null || count == current) return
        current = count
        val agreed = eventCaptureAllAgreed(count)
        icon.setStyle("display", if (agreed) "inline" else "none")
        icon.removeCssClass("fas")
        if (agreed) {
            icon.addCssClasses(ActionIcon.APPROVE.css)
        } else {
            ActionIcon.APPROVE.css
                .split(' ')
                .forEach { icon.removeCssClass(it) }
        }
        val words =
            if (agreed) {
                eventCaptureCountText(count) + " – " + gettext("Alle im Raum haben zugestimmt.")
            } else {
                eventCaptureCountText(count)
            }
        text.content = words
        line.show()
        announce(words)
    }

    private fun announce(words: String) {
        val now = nowMs()
        if (pendingTimer == null && now - lastAnnouncedAt >= announceEveryMs) {
            lastAnnouncedAt = now
            announcedText = words
            live.content = words
            return
        }
        // Within the quiet period: remember only the LATEST text and announce it when the period is over.
        pendingText = words
        if (pendingTimer != null) return
        val wait = (announceEveryMs - (now - lastAnnouncedAt)).toInt().coerceAtLeast(1)
        pendingTimer =
            setTimer(wait) {
                pendingTimer = null
                val latest = pendingText
                pendingText = null
                if (!disposed && latest != null) {
                    lastAnnouncedAt = nowMs()
                    announcedText = latest
                    live.content = latest
                }
            }
    }

    /** Clears the pending announcement and removes both regions. Idempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        pendingTimer?.let(clearTimer)
        pendingTimer = null
        pendingText = null
        host.remove(line)
        host.remove(live)
        line.dispose()
        live.dispose()
    }

    companion object {
        /** At most one announcement per this many milliseconds. */
        const val ANNOUNCE_EVERY_MS = 1500
    }
}
