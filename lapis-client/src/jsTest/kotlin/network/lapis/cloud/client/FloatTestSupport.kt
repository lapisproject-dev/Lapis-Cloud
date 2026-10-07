package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/** A `<video>` with a live `srcObject` (a canvas stream), like the element `track.attach()` makes -- no camera needed. */
internal fun liveTestVideo(): HTMLVideoElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = 16
    canvas.height = 9
    val video = document.createElement("video") as HTMLVideoElement
    video.muted = true
    video.autoplay = true
    video.asDynamic().srcObject = canvas.asDynamic().captureStream()
    return video
}

/** A slot element in the document (the "home" of a picture, like a tile's media slot). */
internal fun testSlot(host: HTMLElement): HTMLElement {
    val slot = document.createElement("div") as HTMLElement
    host.appendChild(slot)
    return slot
}

internal fun hasLiveSource(video: HTMLVideoElement): Boolean = video.asDynamic().srcObject != null

internal fun videoCount(): Int = document.querySelectorAll("video").length

internal class FakeMediaQuery(
    override var matches: Boolean,
) : MediaQueryLike {
    private val listeners = mutableListOf<() -> Unit>()

    override fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun set(value: Boolean) {
        matches = value
        listeners.toList().forEach { it() }
    }
}

internal class MemoryStorage : StorageLike {
    val values = mutableMapOf<String, String>()

    override fun getItem(key: String): String? = values[key]

    override fun setItem(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun removeItem(key: String) {
        values.remove(key)
    }
}
