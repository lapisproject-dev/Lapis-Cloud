package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** V1.9.71 -- the loan ledger: one track, one `<video>`; the window lends and returns, it never creates. */
class ConferenceVideoLedgerDomTest {
    private lateinit var host: HTMLElement
    private lateinit var stage: HTMLElement

    @BeforeTest
    fun setUp() {
        host = document.createElement("div") as HTMLElement
        stage = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        document.body!!.appendChild(stage)
    }

    @AfterTest
    fun tearDown() {
        host.remove()
        stage.remove()
    }

    @Test
    fun borrow_keepsTheIdentity_andCreatesNoNewVideoElement() {
        val ledger = ConferenceVideoLedger()
        val home = testSlot(host)
        val video = liveTestVideo()
        home.appendChild(video)
        val before = videoCount()
        ledger.borrow("a", video, home, stage)
        assertSame(video, stage.querySelector("video"))
        assertEquals(before, videoCount(), "no <video> was created or duplicated")
        assertTrue(ledger.isLent("a"))
        assertEquals(setOf("a"), ledger.lent())
        // idempotent
        ledger.borrow("a", video, home, stage)
        assertEquals(1, stage.querySelectorAll("video").length)
        assertEquals(1, ledger.sizeForTest())
    }

    @Test
    fun returnHome_putsTheSameElementBack() {
        val ledger = ConferenceVideoLedger()
        val home = testSlot(host)
        val video = liveTestVideo()
        home.appendChild(video)
        ledger.borrow("a", video, home, stage)
        ledger.returnHome("a")
        assertSame(video, home.querySelector("video"))
        assertNull(stage.querySelector("video"))
        assertFalse(ledger.isLent("a"))
        assertTrue(hasLiveSource(video), "a returned picture keeps its stream")
    }

    @Test
    fun reclaimSlot_bringsEveryLoanOfThatSlotBack_beforeTheCallViewClearsIt() {
        val ledger = ConferenceVideoLedger()
        val homeA = testSlot(host)
        val homeB = testSlot(host)
        val a = liveTestVideo()
        val b = liveTestVideo()
        homeA.appendChild(a)
        homeB.appendChild(b)
        ledger.borrow("a", a, homeA, stage)
        ledger.borrow("b", b, homeB, stage)
        ledger.reclaimSlot(homeA)
        assertSame(a, homeA.querySelector("video"), "the call view's clearElement now finds the picture where it expects it")
        assertSame(b, stage.querySelector("video"), "the other loan is untouched")
        assertEquals(setOf("b"), ledger.lent())
    }

    @Test
    fun returnAll_sendsEverythingHome() {
        val ledger = ConferenceVideoLedger()
        val homes = List(3) { testSlot(host) }
        val videos = List(3) { liveTestVideo() }
        videos.forEachIndexed { i, v ->
            homes[i].appendChild(v)
            ledger.borrow("k$i", v, homes[i], stage)
        }
        ledger.returnAll()
        assertEquals(0, stage.querySelectorAll("video").length)
        videos.forEachIndexed { i, v -> assertSame(v, homes[i].querySelector("video")) }
        assertEquals(0, ledger.sizeForTest())
    }

    @Test
    fun prune_forgetsALoanWhoseElementWasRemovedElsewhere() {
        val ledger = ConferenceVideoLedger()
        val home = testSlot(host)
        val video = liveTestVideo()
        home.appendChild(video)
        ledger.borrow("a", video, home, stage)
        video.remove() // `track.detach()` of the call view
        ledger.prune()
        assertEquals(0, ledger.sizeForTest())
        ledger.returnHome("a") // must not throw or resurrect it
        assertNull(home.querySelector("video"))
    }

    @Test
    fun aHomeThatIsGone_stopsAndDropsThePicture() {
        val ledger = ConferenceVideoLedger()
        val home = testSlot(host)
        val video = liveTestVideo()
        home.appendChild(video)
        ledger.borrow("a", video, home, stage)
        home.remove()
        ledger.returnHome("a")
        assertFalse(hasLiveSource(video), "no live stream stays behind")
        assertNull(video.parentNode)
    }

    @Test
    fun aDifferentElementForTheSameKey_sendsTheOlderOneHome() {
        val ledger = ConferenceVideoLedger()
        val home = testSlot(host)
        val first = liveTestVideo()
        val second = liveTestVideo()
        home.appendChild(first)
        ledger.borrow("a", first, home, stage)
        home.appendChild(second)
        ledger.borrow("a", second, home, stage)
        assertSame(second, stage.querySelector("video"))
        assertEquals(1, stage.querySelectorAll("video").length)
        assertSame(first, home.querySelector("video"))
    }

    @Test
    fun moving_betweenTwoCells_neverLeavesTheDocument() {
        val ledger = ConferenceVideoLedger()
        val home = testSlot(host)
        val cellA = testSlot(stage)
        val cellB = testSlot(stage)
        val video = liveTestVideo()
        home.appendChild(video)
        ledger.borrow("a", video, home, cellA)
        ledger.borrow("a", video, home, cellB)
        assertTrue(video.isConnected)
        assertSame(cellB, video.parentNode)
        assertEquals(1, ledger.sizeForTest())
    }
}
