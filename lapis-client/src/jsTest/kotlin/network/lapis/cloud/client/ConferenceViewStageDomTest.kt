package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.conference.ConferenceTileRef
import network.lapis.cloud.shared.conference.ConferenceView
import network.lapis.cloud.shared.conference.ConferenceViewInput
import network.lapis.cloud.shared.conference.ConferenceViewParticipant
import network.lapis.cloud.shared.conference.ConferenceViewScreenShare
import network.lapis.cloud.shared.conference.computeConferenceViewLayout
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** V1.9.115 -- the DOM side of the views: tiles are MOVED (never re-created), the pager, the strip, the parking container. */
class ConferenceViewStageDomTest {
    private class Fixture(
        count: Int,
    ) {
        val root = document.createElement("div") as HTMLElement
        val pages = mutableListOf<Int>()
        val stripPages = mutableListOf<Int>()
        val stripTaps = mutableListOf<String>()
        val containers =
            ConferenceViewStage.create(
                root,
                ConferenceViewStageHandlers(
                    onPage = { pages += it },
                    onStripPage = { stripPages += it },
                    onStripTile = { stripTaps += it },
                ),
            )
        val tiles = LinkedHashMap<String, HTMLElement>()
        val videos = LinkedHashMap<String, HTMLVideoElement>()

        init {
            document.body!!.appendChild(root)
            tiles["me"] = tile("me")
            for (i in 1 until count) tiles["p$i"] = tile("p$i")
        }

        fun tile(identity: String): HTMLElement {
            val element = document.createElement("div") as HTMLElement
            element.className = "lapis-conference-tile"
            val video = document.createElement("video") as HTMLVideoElement
            element.appendChild(video)
            videos[identity] = video
            return element
        }

        fun layout(
            view: ConferenceView,
            page: Int = 0,
            pageSize: Int = 9,
            pinned: String? = null,
            main: String? = null,
            speaking: Set<String> = emptySet(),
            shares: List<ConferenceViewScreenShare> = emptyList(),
        ) = computeConferenceViewLayout(
            ConferenceViewInput(
                view = view,
                participants = tiles.keys.mapIndexed { i, id -> ConferenceViewParticipant(id, i.toLong(), true) },
                selfId = "me",
                mainSpeakerId = main,
                speakingIds = speaking,
                pinnedId = pinned,
                screenShares = shares,
                page = page,
                pageSize = pageSize,
            ),
        )

        fun apply(
            layout: network.lapis.cloud.shared.conference.ConferenceViewLayout,
            stripVisible: List<ConferenceTileRef> = layout.strip,
        ): Boolean =
            ConferenceViewStage.apply(
                c = containers,
                layout = layout,
                strip = ConferenceStripState(stripVisible, 0, 1),
                elementOf = { tile -> tiles[tile.identity] },
                allTiles = tiles.values,
                stripNames = { it.identity },
            )

        fun dispose() {
            root.parentNode?.removeChild(root)
        }
    }

    private fun HTMLElement.ids(tiles: Map<String, HTMLElement>): List<String> =
        (0 until children.length).map { index -> tiles.entries.first { it.value === children.item(index) }.key }

    @Test
    fun theGalleryShowsOnePageAndParksTheRest_withoutAnyTileLeavingTheDocument() {
        val f = Fixture(12)
        try {
            val layout = f.layout(ConferenceView.GALLERY, pageSize = 4)
            f.apply(layout)
            assertEquals(listOf("me", "p1", "p2", "p3"), f.containers.gallery.ids(f.tiles))
            assertEquals(8, f.containers.parked.children.length)
            f.tiles.values.forEach { assertTrue(it.isConnected, "every tile stays in the document") }
            assertEquals("flex", f.containers.gallery.style.display)
            assertEquals("none", f.containers.main.style.display)
            assertEquals("flex", f.containers.pager.style.display)
            assertEquals("Seite 1 von 3", f.containers.pagerStatus.textContent)
            assertEquals("true", f.containers.pagerPrev.getAttribute("aria-disabled"))
            assertNull(f.containers.pagerNext.getAttribute("aria-disabled"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun theVideoElementsAreTheSameObjectsAfterEverySwitchOfTheView() {
        val f = Fixture(6)
        try {
            val before = f.videos.toMap()
            f.apply(f.layout(ConferenceView.GALLERY))
            f.apply(f.layout(ConferenceView.SPEAKER, main = "p3"))
            f.apply(f.layout(ConferenceView.PINNED, pinned = "p2"))
            f.apply(f.layout(ConferenceView.GALLERY, pageSize = 2, page = 1))
            f.apply(f.layout(ConferenceView.SPEAKER, main = "p1"))
            f.tiles.forEach { (id, element) ->
                assertSame(before.getValue(id), element.querySelector("video"), "the <video> of $id is never replaced")
                assertTrue(element.isConnected)
            }
        } finally {
            f.dispose()
        }
    }

    @Test
    fun theSpeakerViewPutsTheSpeakerInTheLargeContainerAndTheOthersInTheStrip() {
        val f = Fixture(5)
        try {
            f.apply(f.layout(ConferenceView.SPEAKER, main = "p2"))
            assertEquals(listOf("p2"), f.containers.main.ids(f.tiles))
            assertEquals(listOf("me", "p1", "p3", "p4"), f.containers.strip.ids(f.tiles))
            assertEquals("none", f.containers.gallery.style.display)
            assertEquals("flex", f.containers.main.style.display)
            assertEquals("flex", f.containers.stripRow.style.display)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun aSecondApplyWithTheSameLayoutMovesNothing() {
        val f = Fixture(5)
        try {
            val layout = f.layout(ConferenceView.SPEAKER, main = "p2")
            assertTrue(f.apply(layout))
            assertFalse(f.apply(layout), "a steady state never re-parents a tile")
        } finally {
            f.dispose()
        }
    }

    @Test
    fun stripTilesAreActivatable_andTheOwnTileTooButTheyLoseItWhenTheyLeaveTheStrip() {
        val f = Fixture(4)
        try {
            f.apply(f.layout(ConferenceView.SPEAKER, main = "p1"))
            val p2 = f.tiles.getValue("p2")
            assertEquals("p2", p2.getAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR))
            assertEquals("button", p2.getAttribute("role"))
            assertEquals("0", p2.getAttribute("tabindex"))
            assertEquals("p2 anheften", p2.getAttribute("aria-label"))
            p2.click()
            assertEquals(listOf("p2"), f.stripTaps)
            f.apply(f.layout(ConferenceView.GALLERY))
            assertNull(p2.getAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR))
            assertNull(p2.getAttribute("tabindex"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun enterOnAStripTilePins() {
        val f = Fixture(3)
        try {
            f.apply(f.layout(ConferenceView.SPEAKER, main = "p1"))
            val p2 = f.tiles.getValue("p2")
            p2.dispatchEvent(
                org.w3c.dom.events.KeyboardEvent(
                    "keydown",
                    org.w3c.dom.events
                        .KeyboardEventInit(key = "Enter", bubbles = true, cancelable = true),
                ),
            )
            assertEquals(listOf("p2"), f.stripTaps)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun theArrowToAPageWithASpeakerCarriesTheRingClassAndAnAccessibleName() {
        val f = Fixture(12)
        try {
            val layout = f.layout(ConferenceView.GALLERY, pageSize = 4, speaking = setOf("p9"))
            f.apply(layout)
            assertTrue(
                f.containers.pagerNext.classList
                    .contains(CONFERENCE_PAGER_SPEAKING_CLASS),
            )
            assertFalse(
                f.containers.pagerPrev.classList
                    .contains(CONFERENCE_PAGER_SPEAKING_CLASS),
            )
            assertEquals("Nächste Seite, dort spricht gerade jemand (Seite 3)", f.containers.pagerNext.getAttribute("aria-label"))
            f.apply(f.layout(ConferenceView.GALLERY, pageSize = 4, speaking = emptySet()))
            assertFalse(
                f.containers.pagerNext.classList
                    .contains(CONFERENCE_PAGER_SPEAKING_CLASS),
            )
            assertEquals("Nächste Seite", f.containers.pagerNext.getAttribute("aria-label"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun thePagerArrowsCallTheHandlers() {
        val f = Fixture(12)
        try {
            f.apply(f.layout(ConferenceView.GALLERY, pageSize = 4))
            f.containers.pagerNext.click()
            f.containers.pagerPrev.click()
            assertEquals(listOf(1, -1), f.pages)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun aSingleParticipantIsTheLargeTileInTheGalleryToo() {
        val f = Fixture(1)
        try {
            f.apply(f.layout(ConferenceView.GALLERY))
            assertEquals(listOf("me"), f.containers.main.ids(f.tiles))
            assertEquals("none", f.containers.gallery.style.display)
            assertEquals("none", f.containers.stripRow.style.display)
            assertNotNull(f.containers.main.querySelector("video"))
        } finally {
            f.dispose()
        }
    }
}
