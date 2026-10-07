package network.lapis.cloud.client

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/*
 * V1.9.71 -- one track, one `<video>`. The floating conference window never creates a video element of its own and never attaches a track
 * a second time: it LENDS the element the call view already owns ([ConferenceVideoLedger.borrow]) and hands it back
 * ([ConferenceVideoLedger.returnHome]). Only `setTileVideo` and `showScreenShareStage` create elements (through `track.attach()`).
 *
 * Moves are atomic (`appendChild` of a connected node is one step, never `remove()` followed by a later insert): a media element that
 * is outside the document for longer than one task is paused by the browser (see V1.4.19, `resumeStalledVideos`).
 */

/**
 * V1.4.19 watchdog body, top-level since V1.9.71 so that the floating window can run it on its own stage (the call view's sweep only looks
 * under its own panel and would never find a video that is on loan).
 */
internal fun resumeStalledVideosIn(scope: Element) {
    val videos = scope.querySelectorAll("video")
    for (i in 0 until videos.length) {
        val video = videos.item(i) as? HTMLVideoElement ?: continue
        val srcObject: dynamic = video.asDynamic().srcObject
        val hasSrcObject = srcObject != null
        val streamActive = hasSrcObject && ((srcObject.active as? Boolean) ?: true)
        if (!conferenceVideoNeedsResume(
                isConnected = video.isConnected,
                paused = video.paused,
                ended = video.ended,
                hasSrcObject = hasSrcObject,
                streamActive = streamActive,
            )
        ) {
            continue
        }
        try {
            // `play()` returns a promise; an autoplay `NotAllowedError` must never escape -- the next watchdog tick tries again.
            val playPromise: dynamic = video.asDynamic().play()
            if (playPromise != null) playPromise.catch { _: dynamic -> null }
        } catch (ignored: Throwable) {
            // a synchronous failure is very rare -- next tick
        }
    }
}

/** Ledger of the elements on loan: `key` (opaque) -> the video and the slot it belongs to. */
internal class ConferenceVideoLedger {
    private class Loan(
        val video: HTMLVideoElement,
        val home: HTMLElement,
        val into: HTMLElement,
    )

    private val loans = LinkedHashMap<String, Loan>()

    /** Lends [video] (living in [home]) to [into]. Idempotent; a different element for the same [key] sends the older one home first. */
    fun borrow(
        key: String,
        video: HTMLVideoElement,
        home: HTMLElement,
        into: HTMLElement,
    ) {
        val existing = loans[key]
        if (existing != null && existing.video !== video) returnHome(key)
        if (video.parentNode !== into) into.appendChild(video)
        loans[key] = Loan(video, home, into)
        resumeStalledVideosIn(into)
    }

    /** Hands the element of [key] back. If its home is gone the element is stopped and dropped (last guard: no live stream stays behind). */
    fun returnHome(key: String) {
        val loan = loans.remove(key) ?: return
        if (loan.video.parentNode !== loan.into) return // the call view already took or removed it (track.detach())
        if (loan.home.isConnected) {
            loan.home.appendChild(loan.video)
            resumeStalledVideosIn(loan.home)
        } else {
            release(loan.video)
        }
    }

    /** Every loan that belongs to [home] goes back -- called BEFORE the call view clears that slot. */
    fun reclaimSlot(home: HTMLElement) {
        loans.entries
            .filter { it.value.home === home }
            .map { it.key }
            .forEach { returnHome(it) }
    }

    fun returnAll() {
        loans.keys.toList().forEach { returnHome(it) }
    }

    /** Forgets loans whose element is no longer where it was lent (removed by `track.detach()`) or no longer connected. */
    fun prune() {
        loans.entries
            .filter { (_, loan) -> loan.video.parentNode !== loan.into || !loan.video.isConnected }
            .map { it.key }
            .forEach { loans.remove(it) }
    }

    fun lent(): Set<String> = loans.keys.toSet()

    fun isLent(key: String): Boolean = loans.containsKey(key)

    internal fun sizeForTest(): Int = loans.size

    private fun release(video: HTMLVideoElement) {
        runCatching { video.pause() }
        video.asDynamic().srcObject = null
        video.parentNode?.removeChild(video)
    }
}
