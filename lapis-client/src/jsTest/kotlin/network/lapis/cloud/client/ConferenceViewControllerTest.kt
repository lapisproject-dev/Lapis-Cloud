package network.lapis.cloud.client

import network.lapis.cloud.shared.conference.ConferenceTileRef
import network.lapis.cloud.shared.conference.ConferenceView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.115 -- the DOM-free state behind the switchable views: events, smoothing, paging, notices, storage. */
class ConferenceViewControllerTest {
    private fun room(vararg others: String): Map<String, Boolean> = linkedMapOf("me" to true).also { m -> others.forEach { m[it] = true } }

    private fun controller(stored: ConferenceView = ConferenceView.GALLERY) = ConferenceViewController(selfId = "me", storedView = stored)

    @Test
    fun theGalleryIsTheDefaultAndPagesWithTheWidthOfTheArea() {
        val c = controller()
        val people = room(*(1..30).map { "p$it" }.toTypedArray())
        val wide = c.layout(people, areaWidthPx = 1280.0)
        assertEquals(ConferenceView.GALLERY, wide.effectiveView)
        assertEquals(25, wide.galleryPage.size)
        assertEquals(2, wide.pageCount)
        val narrow = c.layout(people, areaWidthPx = 360.0)
        assertEquals(4, narrow.galleryPage.size)
        assertEquals(8, narrow.pageCount)
    }

    @Test
    fun theGalleryNeverJumpsToASpeakerOnAnotherPage_onlyTheArrowHints() {
        val c = controller()
        val people = room(*(1..30).map { "p$it" }.toTypedArray())
        c.layout(people, 800.0) // 9 per page
        c.onSpeakers(1000, listOf("p20"))
        val layout = c.layout(people, 800.0, nowMs = 1100)
        assertEquals(0, layout.page)
        assertEquals(2, layout.speakerPage)
        c.turnPage(1)
        assertEquals(1, c.layout(people, 800.0).page)
    }

    @Test
    fun theSpeakerViewFollowsTheSmoothedSpeaker_notTheRawReport() {
        val c = controller(ConferenceView.SPEAKER)
        val people = room("a", "b")
        c.layout(people, 1280.0)
        c.onSpeakers(0, listOf("a"))
        // 799 ms: not yet a claim -> the first other with video (a) is shown anyway; b speaks in between and never wins a claim
        c.onSpeakers(300, listOf("b"))
        val early = c.layout(people, 1280.0, nowMs = 400)
        assertEquals("a", early.main?.identity)
        var t = 400L
        while (t < 1500) {
            t += 100
            c.onSpeakers(t, listOf("b"))
        }
        val later = c.layout(people, 1280.0, nowMs = t)
        assertEquals("b", later.main?.identity)
    }

    @Test
    fun theBeatRunsOnlyWhileSomebodyElseSpeaksInASpeakerView() {
        val c = controller(ConferenceView.SPEAKER)
        assertFalse(c.needsBeat())
        c.onSpeakers(0, listOf("me"))
        assertFalse(c.needsBeat(), "the own voice does not count")
        c.onSpeakers(10, listOf("a"))
        assertTrue(c.needsBeat())
        c.choose(ConferenceView.GALLERY)
        assertFalse(c.needsBeat())
    }

    @Test
    fun pinningTheViewAndTheStorageValueNeverContainAPerson() {
        val c = controller()
        c.layout(room("a", "b"), 1280.0)
        assertTrue(c.pin("b"))
        assertEquals(ConferenceView.PINNED, c.state.chosen)
        assertEquals("SPEAKER", c.storageValue())
        assertFalse(c.storageValue().orEmpty().contains("b"))
        assertTrue(c.unpin())
        assertEquals(ConferenceView.SPEAKER, c.state.chosen)
    }

    @Test
    fun choosingPinnedWithoutAPinnedPersonTakesTheLastExternalSpeakerElseTheFirstOther() {
        val c = controller()
        val people = room("a", "b", "c")
        c.layout(people, 1280.0)
        c.onSpeakers(0, listOf("c"))
        c.choose(ConferenceView.PINNED)
        assertEquals("c", c.state.pinnedId)
        val d = controller()
        d.layout(people, 1280.0)
        d.choose(ConferenceView.PINNED)
        assertEquals("a", d.state.pinnedId)
    }

    @Test
    fun whenThePinnedPersonLeavesTheViewFallsBackAndAnnouncesOnce() {
        val c = controller()
        c.layout(room("a", "b"), 1280.0)
        c.pin("b")
        assertTrue(c.participantLeft("b"))
        val layout = c.layout(room("a"), 1280.0)
        assertEquals(ConferenceView.SPEAKER, layout.effectiveView)
        assertEquals(ConferenceViewNotice.PinnedLeft, c.takeNotice())
        assertNull(c.takeNotice())
        assertEquals(ConferenceView.SPEAKER, c.state.chosen)
        assertNull(c.state.pinnedId)
    }

    @Test
    fun aScreenShareSwitchesTheViewAndTheEndRestoresIt_withAnAnnouncement() {
        val c = controller(ConferenceView.SPEAKER)
        val people = room("a")
        c.layout(people, 1280.0)
        c.shareStarted("a")
        assertEquals(ConferenceViewNotice.ViewChanged(ConferenceView.SCREEN_SHARE), c.takeNotice())
        val during = c.layout(people, 1280.0)
        assertEquals(ConferenceView.SCREEN_SHARE, during.effectiveView)
        assertEquals("a#screen", during.main?.key)
        assertEquals("share:a", c.floatPreferredKey(during))
        c.shareEnded()
        assertEquals(ConferenceViewNotice.ViewChanged(ConferenceView.SPEAKER), c.takeNotice())
        assertEquals(ConferenceView.SPEAKER, c.layout(people, 1280.0).effectiveView)
    }

    @Test
    fun theSharerLeavingEndsTheShare() {
        val c = controller()
        c.layout(room("a"), 1280.0)
        c.shareStarted("a")
        c.participantLeft("a")
        assertFalse(c.shareActive())
        assertEquals(ConferenceView.GALLERY, c.state.chosen)
    }

    @Test
    fun theFloatingWindowFollowsTheLargeTile_exceptInTheGallery() {
        val c = controller(ConferenceView.SPEAKER)
        val people = room("a", "b")
        val speaker = c.layout(people, 1280.0)
        assertEquals("tile:a", c.floatPreferredKey(speaker))
        c.choose(ConferenceView.GALLERY)
        assertNull(c.floatPreferredKey(c.layout(people, 1280.0)))
    }

    @Test
    fun joiningWhileAShareRunsIsTheShareViewFromTheStart() {
        val c = ConferenceViewController("me", ConferenceView.SPEAKER, shareActive = true)
        assertEquals(ConferenceView.SCREEN_SHARE, c.state.chosen)
    }

    @Test
    fun theStripIsSlicedToWhatFitsAndThePageIsClamped() {
        val strip = (1..7).map { ConferenceTileRef("p$it", screenShare = false, hasVideo = true) }
        val (first, page, count) = conferenceStripSlice(strip, capacity = 3, page = 0)
        assertEquals(listOf("p1", "p2", "p3"), first.map { it.identity })
        assertEquals(0, page)
        assertEquals(3, count)
        val (last, clamped, _) = conferenceStripSlice(strip, capacity = 3, page = 9)
        assertEquals(listOf("p7"), last.map { it.identity })
        assertEquals(2, clamped)
        val (zero, _, pages) = conferenceStripSlice(strip, capacity = 0, page = 0)
        assertEquals(1, zero.size)
        assertEquals(7, pages)
        assertEquals(1, conferenceStripSlice(emptyList(), 3, 0).third)
    }

    // -- V1.9.253: raised hands first -------------------------------------------------------------------------------------------

    private fun galleryIds(
        c: ConferenceViewController,
        people: Map<String, Boolean>,
        nowMs: Long? = null,
    ) = c.layout(people, 1280.0, nowMs).galleryPage.map { it.identity }

    @Test
    fun aRaisedHand_putsThePersonFirstInTheGallery_andLoweringRestoresTheOrder() {
        val c = controller()
        val people = room("a", "b", "c")
        val before = galleryIds(c, people)
        assertEquals(listOf("me", "a", "b", "c"), before)
        assertTrue(c.setRaisedHands(listOf("c", "b")))
        assertEquals(listOf("me", "c", "b", "a"), galleryIds(c, people))
        assertTrue(c.setRaisedHands(emptyList()))
        assertEquals(before, galleryIds(c, people))
    }

    @Test
    fun setRaisedHands_reportsOnlyRealChanges() {
        val c = controller()
        assertFalse(c.setRaisedHands(emptyList()))
        assertTrue(c.setRaisedHands(listOf("a")))
        assertFalse(c.setRaisedHands(listOf("a")))
        assertTrue(c.setRaisedHands(listOf("a", "b")))
    }

    @Test
    fun aSpeakerChange_doesNotReorderTheGalleryWithHands() {
        val c = controller()
        val people = room("a", "b", "c")
        c.setRaisedHands(listOf("c"))
        val first = galleryIds(c, people)
        c.onSpeakers(0, listOf("a"))
        assertEquals(first, galleryIds(c, people, nowMs = 100))
        c.onSpeakers(200, listOf("b"))
        assertEquals(first, galleryIds(c, people, nowMs = 1500))
    }

    @Test
    fun withoutHands_theLayoutIsTheOneOfToday() {
        val withController = controller()
        val untouched = controller()
        val people = room("a", "b", "c")
        withController.setRaisedHands(listOf("a"))
        withController.setRaisedHands(emptyList())
        assertEquals(untouched.layout(people, 1280.0), withController.layout(people, 1280.0))
    }

    @Test
    fun aPinnedPerson_staysPinned_whenAHandGoesUp() {
        val c = controller()
        val people = room("a", "b", "c")
        c.layout(people, 1280.0)
        c.pin("a")
        c.setRaisedHands(listOf("c"))
        val layout = c.layout(people, 1280.0)
        assertEquals("a", layout.main?.identity)
        assertEquals(ConferenceView.PINNED, layout.effectiveView)
    }
}
