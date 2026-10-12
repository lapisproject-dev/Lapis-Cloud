package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.conference.ConferenceView
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.115 -- the remembered view: only GALLERY / SPEAKER, never a person, and a broken storage never breaks the call. */
class ConferenceViewStoreTest {
    private fun clear() {
        try {
            window.localStorage.removeItem(CONFERENCE_VIEW_STORAGE_KEY)
        } catch (_: Throwable) {
        }
    }

    @Test
    fun aWrittenViewIsReadBack_andGarbageIsTheGallery() {
        clear()
        assertEquals(ConferenceView.GALLERY, ConferenceViewStore.read())
        ConferenceViewStore.write("SPEAKER")
        assertEquals(ConferenceView.SPEAKER, ConferenceViewStore.read())
        window.localStorage.setItem(CONFERENCE_VIEW_STORAGE_KEY, "PINNED:abc-def")
        assertEquals(ConferenceView.GALLERY, ConferenceViewStore.read())
        window.localStorage.setItem(CONFERENCE_VIEW_STORAGE_KEY, "<script>alert(1)</script>")
        assertEquals(ConferenceView.GALLERY, ConferenceViewStore.read())
        clear()
    }

    @Test
    fun aNullValueIsNeverWritten() {
        clear()
        ConferenceViewStore.write(null)
        assertEquals(null, window.localStorage.getItem(CONFERENCE_VIEW_STORAGE_KEY))
    }

    @Test
    fun aStorageThatThrowsOnReadAndOnWrite_changesNothing() {
        val storage = window.localStorage.asDynamic()
        val proto = js("Object.getPrototypeOf(window.localStorage)")
        val originalGet = proto.getItem
        val originalSet = proto.setItem
        try {
            proto.getItem = { _: dynamic -> throw js("new Error('blocked')") }
            proto.setItem = { _: dynamic, _: dynamic -> throw js("new Error('blocked')") }
            assertEquals(ConferenceView.GALLERY, ConferenceViewStore.read())
            ConferenceViewStore.write("SPEAKER") // must not throw
        } finally {
            proto.getItem = originalGet
            proto.setItem = originalSet
        }
        assertEquals(storage, window.localStorage.asDynamic())
    }
}
