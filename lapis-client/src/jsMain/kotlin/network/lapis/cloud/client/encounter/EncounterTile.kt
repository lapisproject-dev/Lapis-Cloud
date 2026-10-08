package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.i18n.gettext
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.untrustedContent
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLMediaElement

/**
 * One office holder's tile (the pulpit or a steward): the media host for the video and the (hidden) audio element, and the name label.
 * The name is untrusted text (set through [untrustedContent]). A tile only exists for a person the server listed with an office
 * ([EncounterRoom] creates it after `listPresent`, or at once for the viewer's own office).
 */
internal class EncounterTile(
    val identity: String,
    displayName: String,
    large: Boolean,
) {
    val root: Div = Div(className = if (large) "lapis-encounter-tile lapis-encounter-tile--large" else "lapis-encounter-tile")
    private val media = EncounterMediaHost("lapis-encounter-tile-media")
    private val label = Div(className = "lapis-encounter-tile-label")
    private val speakingMark = Span(content = gettext("spricht"), className = "lapis-encounter-tile-speaking")
    private var currentName: String = displayName
    private var speaking = false
    private var video: HTMLElement? = null
    private var audio: HTMLElement? = null

    init {
        media.attachTo(root)
        root.add(label)
        // The visible "spricht" is text, not only a colour (V1.9.79); it is also part of the tile's accessible name while it shows.
        speakingMark.hide()
        root.add(speakingMark)
        setName(displayName)
    }

    fun setName(displayName: String) {
        currentName = displayName
        untrustedContent(label, displayName)
        refreshAccessibleName()
    }

    /**
     * V1.9.79: LiveKit reports this office holder as speaking right now. Volatile: only a class and a word, never stored or logged. The
     * word "spricht" is visible and also part of the aria-label, so the signal does not depend on the colour of the outline.
     */
    fun setSpeaking(on: Boolean) {
        if (on == speaking) return
        speaking = on
        if (on) {
            root.addCssClass("lapis-encounter-tile--speaking")
            speakingMark.show()
        } else {
            root.removeCssClass("lapis-encounter-tile--speaking")
            speakingMark.hide()
        }
        refreshAccessibleName()
    }

    val isSpeaking: Boolean get() = speaking

    private fun refreshAccessibleName() {
        if (speaking) {
            root.setAttribute("role", "group")
            root.setAttribute("aria-label", gettext("%1, spricht", sanitizeUntrustedI18nText(currentName)))
        } else {
            root.removeAttribute("aria-label")
            root.removeAttribute("role")
        }
    }

    fun attachTo(container: Container) {
        container.add(root)
    }

    /** Replaces the tile's video element ([element] `null` clears it). The element comes from `Track.attach()`. */
    fun setVideo(element: HTMLMediaElement?) {
        video?.let { media.remove(it) }
        video = element
        if (element != null) {
            element.setAttribute("playsinline", "true")
            media.add(element)
        }
    }

    /** Replaces the tile's audio element: hidden, never a control (a listener has no volume UI of its own; the system volume applies). */
    fun setAudio(element: HTMLMediaElement?) {
        audio?.let { media.remove(it) }
        audio = element
        if (element != null) {
            element.style.display = "none"
            media.add(element)
        }
    }

    val hasVideo: Boolean get() = video != null

    fun dispose() {
        media.clear()
        video = null
        audio = null
    }
}
