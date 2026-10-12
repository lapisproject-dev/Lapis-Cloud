package network.lapis.cloud.shared.conference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.115 -- the pure layout of the four conference views. */
class ConferenceViewLayoutTest {
    private fun people(
        n: Int,
        videoFor: (Int) -> Boolean = { true },
    ): List<ConferenceViewParticipant> =
        listOf(ConferenceViewParticipant(identity = "me", joinOrder = 0, hasVideo = true)) +
            (1..n).map { ConferenceViewParticipant(identity = "p$it", joinOrder = it.toLong(), hasVideo = videoFor(it)) }

    private fun input(
        view: ConferenceView,
        participants: List<ConferenceViewParticipant>,
        mainSpeakerId: String? = null,
        speaking: Set<String> = emptySet(),
        pinned: String? = null,
        shares: List<ConferenceViewScreenShare> = emptyList(),
        fallback: ConferenceView = ConferenceView.GALLERY,
        page: Int = 0,
        pageSize: Int = 9,
    ) = ConferenceViewInput(
        view = view,
        participants = participants,
        selfId = "me",
        mainSpeakerId = mainSpeakerId,
        speakingIds = speaking,
        pinnedId = pinned,
        screenShares = shares,
        fallbackView = fallback,
        page = page,
        pageSize = pageSize,
    )

    @Test
    fun `every view with a single person has one main tile and no strip`() {
        ConferenceView.entries.forEach { view ->
            val layout = computeConferenceViewLayout(input(view = view, participants = people(n = 0), shares = emptyList()))
            assertEquals("me", layout.main?.identity, "view $view")
            assertTrue(layout.strip.isEmpty())
            assertEquals(1, layout.pageCount)
        }
    }

    @Test
    fun `gallery lists self first and the others by join order and pages`() {
        val ps = people(n = 30).shuffled(kotlin.random.Random(1))
        val layout = computeConferenceViewLayout(input(view = ConferenceView.GALLERY, participants = ps, pageSize = 25))
        assertEquals(2, layout.pageCount)
        assertEquals(listOf("me") + (1..24).map { "p$it" }, layout.galleryPage.map { it.identity })
        val second = computeConferenceViewLayout(input(view = ConferenceView.GALLERY, participants = ps, page = 1, pageSize = 25))
        assertEquals((25..30).map { "p$it" }, second.galleryPage.map { it.identity })
    }

    @Test
    fun `two people in the gallery`() {
        val layout = computeConferenceViewLayout(input(view = ConferenceView.GALLERY, participants = people(n = 1)))
        assertEquals(listOf("me", "p1"), layout.galleryPage.map { it.identity })
        assertEquals(1, layout.pageCount)
    }

    @Test
    fun `the page is clamped when the group shrinks and never jumps by itself`() {
        val layout = computeConferenceViewLayout(input(view = ConferenceView.GALLERY, participants = people(n = 3), page = 5))
        assertEquals(0, layout.page)
        val stay =
            computeConferenceViewLayout(
                input(view = ConferenceView.GALLERY, participants = people(n = 30), page = 2, speaking = setOf("p1")),
            )
        // somebody speaks on another page: the page does NOT change, only the hint is set
        assertEquals(2, stay.page)
        assertEquals(0, stay.speakerPage)
    }

    @Test
    fun `speaker page points toward the next page with a speaker forward and backward`() {
        val ps = people(n = 30)
        val forward =
            computeConferenceViewLayout(
                input(view = ConferenceView.GALLERY, participants = ps, page = 0, speaking = setOf("p20"), pageSize = 9),
            )
        assertEquals(2, forward.speakerPage)
        val backward =
            computeConferenceViewLayout(
                input(view = ConferenceView.GALLERY, participants = ps, page = 3, speaking = setOf("p2"), pageSize = 9),
            )
        assertEquals(0, backward.speakerPage)
        val here =
            computeConferenceViewLayout(
                input(view = ConferenceView.GALLERY, participants = ps, page = 0, speaking = setOf("p2", "p20"), pageSize = 9),
            )
        assertNull(here.speakerPage)
        val selfOnly =
            computeConferenceViewLayout(
                input(view = ConferenceView.GALLERY, participants = ps, page = 1, speaking = setOf("me"), pageSize = 9),
            )
        assertNull(selfOnly.speakerPage)
    }

    @Test
    fun `speaker view shows the smoothed speaker large and self first in the strip`() {
        val layout = computeConferenceViewLayout(input(view = ConferenceView.SPEAKER, participants = people(n = 4), mainSpeakerId = "p3"))
        assertEquals("p3", layout.main?.identity)
        assertEquals(listOf("me", "p1", "p2", "p4"), layout.strip.map { it.identity })
    }

    @Test
    fun `speaker view without a speaker takes the first other with video, else the first other`() {
        val withVideo = computeConferenceViewLayout(input(view = ConferenceView.SPEAKER, participants = people(n = 3) { it != 1 }))
        assertEquals("p2", withVideo.main?.identity)
        val noVideo = computeConferenceViewLayout(input(view = ConferenceView.SPEAKER, participants = people(n = 3) { false }))
        assertEquals("p1", noVideo.main?.identity)
        val gone = computeConferenceViewLayout(input(view = ConferenceView.SPEAKER, participants = people(n = 2), mainSpeakerId = "left"))
        assertEquals("p1", gone.main?.identity)
    }

    @Test
    fun `pinned view puts the pinned person large and orders the strip self first then join order`() {
        val layout = computeConferenceViewLayout(input(view = ConferenceView.PINNED, participants = people(n = 4), pinned = "p3"))
        assertEquals("p3", layout.main?.identity)
        assertEquals(ConferenceView.PINNED, layout.effectiveView)
        assertEquals(listOf("me", "p1", "p2", "p4"), layout.strip.map { it.identity })
        assertFalse(layout.pinnedLeft)
    }

    @Test
    fun `when the pinned person leaves the view is speaker and pinnedLeft is set`() {
        val layout =
            computeConferenceViewLayout(
                input(view = ConferenceView.PINNED, participants = people(n = 2), pinned = "gone", mainSpeakerId = "p2"),
            )
        assertEquals(ConferenceView.SPEAKER, layout.effectiveView)
        assertTrue(layout.pinnedLeft)
        assertEquals("p2", layout.main?.identity)
    }

    @Test
    fun `pinning oneself is rejected`() {
        val layout = computeConferenceViewLayout(input(view = ConferenceView.PINNED, participants = people(n = 2), pinned = "me"))
        assertEquals(ConferenceView.SPEAKER, layout.effectiveView)
        assertFalse(layout.pinnedLeft)
        assertTrue(layout.main?.identity != "me")
    }

    @Test
    fun `screen share view puts the newest share large and further shares first in the strip`() {
        val shares =
            listOf(ConferenceViewScreenShare(identity = "p1", startOrder = 1), ConferenceViewScreenShare(identity = "p2", startOrder = 5))
        val layout = computeConferenceViewLayout(input(view = ConferenceView.SCREEN_SHARE, participants = people(n = 3), shares = shares))
        assertEquals("p2#screen", layout.main?.key)
        assertEquals(listOf("p1#screen", "me", "p1", "p2", "p3"), layout.strip.map { it.key })
    }

    @Test
    fun `a sharer without a camera is a tile without video in the strip`() {
        val shares = listOf(ConferenceViewScreenShare(identity = "p1", startOrder = 1))
        val layout =
            computeConferenceViewLayout(
                input(view = ConferenceView.SCREEN_SHARE, participants = people(n = 2) { it != 1 }, shares = shares),
            )
        assertEquals(false, layout.strip.first { it.key == "p1" }.hasVideo)
    }

    @Test
    fun `a share view without a share falls back to the remembered view or the gallery and never to a share`() {
        val toGallery = computeConferenceViewLayout(input(view = ConferenceView.SCREEN_SHARE, participants = people(n = 2)))
        assertEquals(ConferenceView.GALLERY, toGallery.effectiveView)
        val toSpeaker =
            computeConferenceViewLayout(
                input(view = ConferenceView.SCREEN_SHARE, participants = people(n = 2), fallback = ConferenceView.SPEAKER),
            )
        assertEquals(ConferenceView.SPEAKER, toSpeaker.effectiveView)
        val loop =
            computeConferenceViewLayout(
                input(view = ConferenceView.SCREEN_SHARE, participants = people(n = 2), fallback = ConferenceView.SCREEN_SHARE),
            )
        assertEquals(ConferenceView.GALLERY, loop.effectiveView)
    }

    @Test
    fun `a share by a person who left is ignored`() {
        val layout =
            computeConferenceViewLayout(
                input(
                    view = ConferenceView.SCREEN_SHARE,
                    participants = people(n = 2),
                    shares = listOf(ConferenceViewScreenShare(identity = "ghost", startOrder = 1)),
                ),
            )
        assertEquals(ConferenceView.GALLERY, layout.effectiveView)
    }

    @Test
    fun `the gallery still shows a running share next to its owner`() {
        val layout =
            computeConferenceViewLayout(
                input(
                    view = ConferenceView.GALLERY,
                    participants = people(n = 2),
                    shares = listOf(ConferenceViewScreenShare(identity = "p1", startOrder = 1)),
                ),
            )
        assertEquals(listOf("me", "p1", "p1#screen", "p2"), layout.galleryPage.map { it.key })
    }

    @Test
    fun `page sizes at the breakpoints`() {
        assertEquals(4, conferenceGalleryPageSize(599.0))
        assertEquals(9, conferenceGalleryPageSize(600.0))
        assertEquals(9, conferenceGalleryPageSize(1023.0))
        assertEquals(25, conferenceGalleryPageSize(1024.0))
        assertEquals(4, conferenceGalleryPageSize(Double.NaN))
        assertEquals(4, conferenceGalleryPageSize(0.0))
        assertEquals(4, conferenceGalleryPageSize(-5.0))
        assertEquals(112, conferenceFilmstripTileHeight(600.0))
        assertEquals(54, conferenceFilmstripTileHeight(599.0))
        assertEquals(54, conferenceFilmstripTileHeight(Double.NaN))
    }

    @Test
    fun `a zero or negative page size is treated as one and an empty room does not crash`() {
        val one = computeConferenceViewLayout(input(view = ConferenceView.GALLERY, participants = people(n = 2), pageSize = 0))
        assertEquals(3, one.pageCount)
        val empty = computeConferenceViewLayout(input(view = ConferenceView.GALLERY, participants = emptyList()))
        assertNull(empty.main)
    }
}
