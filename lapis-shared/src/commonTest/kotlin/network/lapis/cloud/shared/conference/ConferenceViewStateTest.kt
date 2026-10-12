package network.lapis.cloud.shared.conference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.115 -- the view state machine: share auto-switch and restore, pinning, storage. */
class ConferenceViewStateTest {
    private fun reduce(
        state: ConferenceViewState,
        event: ConferenceViewEvent,
        last: String? = null,
        first: String? = null,
    ) = reduceConferenceView(state = state, event = event, selfId = "me", lastExternalSpeaker = last, firstOther = first)

    @Test
    fun `a share switches to the share view and the end of the last share restores the earlier view`() {
        var s = ConferenceViewState(chosen = ConferenceView.SPEAKER)
        s = reduce(state = s, event = ConferenceViewEvent.ShareStarted)
        assertEquals(ConferenceView.SCREEN_SHARE, s.chosen)
        assertEquals(ConferenceView.SPEAKER to null, s.beforeShare)
        s = reduce(state = s, event = ConferenceViewEvent.AllSharesEnded)
        assertEquals(ConferenceView.SPEAKER, s.chosen)
        assertNull(s.beforeShare)
    }

    @Test
    fun `a second share does not overwrite the remembered view`() {
        var s = ConferenceViewState(chosen = ConferenceView.GALLERY)
        s = reduce(state = s, event = ConferenceViewEvent.ShareStarted)
        s = reduce(state = s, event = ConferenceViewEvent.ShareStarted)
        assertEquals(ConferenceView.GALLERY to null, s.beforeShare)
    }

    @Test
    fun `a manual choice during a share is flagged and still restored when the share ends`() {
        var s = reduce(state = ConferenceViewState(chosen = ConferenceView.GALLERY), event = ConferenceViewEvent.ShareStarted)
        s = reduce(state = s, event = ConferenceViewEvent.UserChose(ConferenceView.SPEAKER))
        assertEquals(true, s.manualDuringShare)
        assertEquals(ConferenceView.SPEAKER, s.chosen)
        s = reduce(state = s, event = ConferenceViewEvent.AllSharesEnded)
        assertEquals(ConferenceView.GALLERY, s.chosen)
        assertEquals(false, s.manualDuringShare)
    }

    @Test
    fun `the pinned person is remembered across a share`() {
        var s = reduce(state = ConferenceViewState(), event = ConferenceViewEvent.Pin("p2"))
        s = reduce(state = s, event = ConferenceViewEvent.ShareStarted)
        s = reduce(state = s, event = ConferenceViewEvent.AllSharesEnded)
        assertEquals(ConferenceView.PINNED, s.chosen)
        assertEquals("p2", s.pinnedId)
    }

    @Test
    fun `choosing pinned without a pinned person takes the last external speaker, else the first other`() {
        assertEquals(
            "p3",
            reduce(
                state = ConferenceViewState(),
                event = ConferenceViewEvent.UserChose(ConferenceView.PINNED),
                last = "p3",
                first = "p1",
            ).pinnedId,
        )
        assertEquals(
            "p1",
            reduce(state = ConferenceViewState(), event = ConferenceViewEvent.UserChose(ConferenceView.PINNED), first = "p1").pinnedId,
        )
        assertEquals(
            "p1",
            reduce(
                state = ConferenceViewState(),
                event = ConferenceViewEvent.UserChose(ConferenceView.PINNED),
                last = "me",
                first = "p1",
            ).pinnedId,
        )
        val existing =
            reduce(state = ConferenceViewState(pinnedId = "p9"), event = ConferenceViewEvent.UserChose(ConferenceView.PINNED), last = "p3")
        assertEquals("p9", existing.pinnedId)
    }

    @Test
    fun `pinning oneself changes nothing and unpin or pinned-left fall back to speaker`() {
        val base = ConferenceViewState(chosen = ConferenceView.GALLERY)
        assertEquals(base, reduce(state = base, event = ConferenceViewEvent.Pin("me")))
        val pinned = reduce(state = base, event = ConferenceViewEvent.StripTileActivated("p1"))
        assertEquals(ConferenceView.PINNED, pinned.chosen)
        assertEquals(ConferenceView.SPEAKER, reduce(state = pinned, event = ConferenceViewEvent.Unpin).chosen)
        val left = reduce(state = pinned, event = ConferenceViewEvent.PinnedLeft)
        assertEquals(ConferenceView.SPEAKER, left.chosen)
        assertNull(left.pinnedId)
    }

    @Test
    fun `storage values never contain a person and a share view is never stored`() {
        assertEquals("GALLERY", conferenceViewStorageValue(ConferenceView.GALLERY))
        assertEquals("SPEAKER", conferenceViewStorageValue(ConferenceView.SPEAKER))
        assertEquals("SPEAKER", conferenceViewStorageValue(ConferenceView.PINNED))
        assertNull(conferenceViewStorageValue(ConferenceView.SCREEN_SHARE))
        assertEquals(ConferenceView.SPEAKER, conferenceViewFromStorage("SPEAKER"))
        assertEquals(ConferenceView.GALLERY, conferenceViewFromStorage("PINNED:abc"))
        assertEquals(ConferenceView.GALLERY, conferenceViewFromStorage(null))
        assertEquals(ConferenceView.GALLERY, conferenceViewFromStorage("<script>"))
    }

    @Test
    fun `joining while a share runs starts in the share view and remembers the stored view`() {
        val s = initialConferenceView(stored = ConferenceView.SPEAKER, shareActive = true)
        assertEquals(ConferenceView.SCREEN_SHARE, s.chosen)
        assertEquals(ConferenceView.SPEAKER to null, s.beforeShare)
        assertEquals(ConferenceView.GALLERY, initialConferenceView(stored = ConferenceView.PINNED, shareActive = false).chosen)
    }
}
