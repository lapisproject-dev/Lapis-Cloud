package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
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
    private var video: HTMLElement? = null
    private var audio: HTMLElement? = null

    init {
        media.attachTo(root)
        root.add(label)
        setName(displayName)
    }

    fun setName(displayName: String) {
        untrustedContent(label, displayName)
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
