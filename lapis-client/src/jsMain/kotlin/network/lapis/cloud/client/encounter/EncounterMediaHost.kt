package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import network.lapis.cloud.client.addWithLifecycle
import org.w3c.dom.HTMLElement

/**
 * V1.9.62 Begegnungsraum (B2) -- a raw-DOM home for the `<video>`/`<audio>` elements of one tile.
 *
 * Why not KVision children: a media element must keep ITS element identity and its stream while the page re-renders (a language
 * switch restarts the KVision root; a patch that replaces the host element detaches the children and the browser pauses a video that
 * stays outside the document for longer than a task). The elements are therefore owned here, outside the virtual DOM, and adopted
 * into the host element again in the same task whenever the host is (re-)inserted -- the same rule `ConferenceScreen.kt` applies to its
 * video grid (`conferenceAdoptChildren`, see the "late hooks" audit, [addWithLifecycle]).
 */
internal class EncounterMediaHost(
    className: String,
    /** V1.9.91: the room's speaker choice; every element added here gets the chosen output device (only the two audio hosts pass it). */
    private val audioOutput: EncounterAudioOutput? = null,
) {
    val widget: Div = Div(className = className)
    private val live = mutableListOf<HTMLElement>()

    /** Adds [host] to [container] with the adopt-on-insert hook registered BEFORE it is added (stable snabbdom key). */
    fun attachTo(container: Container) {
        container.addWithLifecycle(widget, onInsert = { vnode -> adopt(vnode.elm as? HTMLElement) })
    }

    fun add(element: HTMLElement) {
        if (element in live) return
        live += element
        widget.getElement()?.appendChild(element)
        audioOutput?.apply(element)
    }

    fun remove(element: HTMLElement) {
        live -= element
        element.parentNode?.removeChild(element)
    }

    /** Detaches every element (the tile leaves the room). */
    fun clear() {
        live.toList().forEach { remove(it) }
    }

    val elements: List<HTMLElement> get() = live.toList()

    private fun adopt(host: HTMLElement?) {
        if (host == null) return
        live.forEach { if (it.parentNode !== host) host.appendChild(it) }
    }
}
