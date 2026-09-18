package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.4.19 -- video-resume watchdog decision ([conferenceVideoNeedsResume]) and the container
 * adoption helper ([conferenceAdoptChildren]) that keeps raw-DOM `<video>` elements identical when a
 * re-render replaces their container. DOM-free except the adoption test, which only needs a plain
 * `document.createElement` (Karma runs in ChromeHeadless) -- no KVision rendering harness exists.
 */
class ConferenceVideoResumeTest {
    private fun needsResume(
        isConnected: Boolean = true,
        paused: Boolean = true,
        ended: Boolean = false,
        hasSrcObject: Boolean = true,
        streamActive: Boolean = true,
    ): Boolean =
        conferenceVideoNeedsResume(
            isConnected = isConnected,
            paused = paused,
            ended = ended,
            hasSrcObject = hasSrcObject,
            streamActive = streamActive,
        )

    @Test
    fun needsResume_connectedPausedActiveStream_true() {
        assertTrue(needsResume())
    }

    @Test
    fun needsResume_notConnected_false() {
        assertFalse(needsResume(isConnected = false))
    }

    @Test
    fun needsResume_notPaused_false() {
        assertFalse(needsResume(paused = false))
    }

    @Test
    fun needsResume_ended_false() {
        assertFalse(needsResume(ended = true))
    }

    @Test
    fun needsResume_noSrcObject_false() {
        assertFalse(needsResume(hasSrcObject = false, streamActive = false))
        assertFalse(needsResume(hasSrcObject = false))
    }

    @Test
    fun needsResume_inactiveStream_false() {
        assertFalse(needsResume(streamActive = false))
    }

    @Test
    fun needsResume_multipleConditionsFalse_false() {
        assertFalse(needsResume(isConnected = false, paused = false, ended = true, hasSrcObject = false, streamActive = false))
        assertFalse(needsResume(isConnected = false, streamActive = false))
        assertFalse(needsResume(paused = false, ended = true))
    }

    @Test
    fun adoptChildren_movesAllChildrenIdenticallyAndInOrder() {
        val oldRoot = document.createElement("div") as HTMLElement
        val newRoot = document.createElement("div") as HTMLElement
        val first = document.createElement("div") as HTMLElement
        val video = document.createElement("video") as HTMLElement
        val last = document.createElement("span") as HTMLElement
        oldRoot.appendChild(first)
        first.appendChild(video)
        oldRoot.appendChild(last)

        conferenceAdoptChildren(oldRoot = oldRoot, newRoot = newRoot)

        assertEquals(0, oldRoot.childNodes.length)
        assertEquals(2, newRoot.childNodes.length)
        assertSame(first, newRoot.firstChild)
        assertSame(last, newRoot.lastChild)
        assertSame(video, first.firstChild)
    }

    @Test
    fun adoptChildren_emptyOldRoot_isNoOp() {
        val oldRoot = document.createElement("div") as HTMLElement
        val newRoot = document.createElement("div") as HTMLElement
        val existing = document.createElement("div") as HTMLElement
        newRoot.appendChild(existing)

        conferenceAdoptChildren(oldRoot = oldRoot, newRoot = newRoot)

        assertEquals(1, newRoot.childNodes.length)
        assertSame(existing, newRoot.firstChild)
    }

    @Test
    fun adoptChildren_sameRoot_keepsChildren() {
        val root = document.createElement("div") as HTMLElement
        val child = document.createElement("div") as HTMLElement
        root.appendChild(child)

        conferenceAdoptChildren(oldRoot = root, newRoot = root)

        assertEquals(1, root.childNodes.length)
        assertSame(child, root.firstChild)
    }
}
