package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.navbar.Nav
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NavCountsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.224 -- the live "(aktiv/gesamt)" of the sidebar: the text, the accessible name, 0/0, hidden tab, visibility, sign-out, rebuilds, failures
 * and the menu search, driven with a fake RPC and a fake scheduler (no real timer, no real clock).
 */
class NavCountsPollerDomTest {
    private fun session(status: MemberStatus = MemberStatus.ACTIVE) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Person",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = status,
        )

    private class FakeRpc(
        private val answer: () -> NavCountsDto,
    ) : NavCountsRpc {
        var calls = 0

        override suspend fun navCounts(): NavCountsDto {
            calls++
            return answer()
        }
    }

    private class FakeScheduler : NavCountsScheduler {
        var visible = true
        var now = 1_000_000.0
        val timers = mutableListOf<() -> Unit>()
        val visibilityListeners = mutableListOf<(Boolean) -> Unit>()

        override fun every(
            ms: Int,
            block: () -> Unit,
        ): () -> Unit {
            timers += block
            return { timers -= block }
        }

        override fun onVisibilityChange(block: (visible: Boolean) -> Unit): () -> Unit {
            visibilityListeners += block
            return { visibilityListeners -= block }
        }

        override fun isVisible() = visible

        override fun nowMs() = now

        fun tick() = timers.toList().forEach { it() }

        fun showTab() = visibilityListeners.toList().forEach { it(true) }
    }

    @BeforeTest
    fun reset() {
        NavCountsPoller.stop()
        MenuIndex.reset()
        NavHighlight.reset()
    }

    @AfterTest
    fun tearDown() {
        NavCountsPoller.stop()
        MenuIndex.reset()
        NavHighlight.reset()
    }

    private fun counts(
        conferenceActive: Int = 2,
        conferenceTotal: Int = 5,
        encounterActive: Int = 0,
        encounterTotal: Int = 4,
    ) = NavCountsDto(conferenceActive, conferenceTotal, encounterActive, encounterTotal)

    /** The sidebar of [session] with the poller started on [rpc]/[scheduler]; the links by route. */
    private fun sidebar(
        session: SessionInfoDto,
        rpc: NavCountsRpc,
        scheduler: NavCountsScheduler,
    ): Pair<SimplePanel, Map<String, Link>> {
        val body = SimplePanel()
        buildSidebar(body, session, activeRoute = null) {}
        // buildSidebar starts the poller with the live RPC (it ends in a quiet failure here); restart it on the fake
        NavCountsPoller.start(ownerId = "${session.memberId}-test", rpc = rpc, scheduler = scheduler)
        val nav = body.getChildren().first() as Nav
        return body to nav.getChildren().filterIsInstance<Link>().associateBy { it.url.orEmpty().removePrefix("#") }
    }

    private fun Link.suffixText(): String? = (getElement() as? HTMLElement)?.querySelector(".nav-live-suffix")?.textContent

    @Test
    fun theSuffix_andTheAccessibleName_showTheFigures(): Promise<Unit> =
        formTest {
            val rpc = FakeRpc { counts() }
            val (_, links) = sidebar(session(), rpc, FakeScheduler())
            val conference = links.getValue(Routes.CONFERENCE)
            awaitUntil("the figures arrive") { conference.getAttribute("aria-label") != null }
            assertEquals("Videokonferenz, 2 von 5 aktiv", conference.getAttribute("aria-label"))
            assertEquals("2 von 5 aktiv", conference.getAttribute("title"))
            val encounter = links.getValue(Routes.ENCOUNTER)
            assertEquals("Begegnungsräume, 0 von 4 aktiv", encounter.getAttribute("aria-label"), "0/4 is shown")
            assertEquals(1, rpc.calls)
            assertEquals(" (2/5)", NavCountsPoller.lastForTest()?.let { " (${it.conferenceActive}/${it.conferenceTotal})" })
        }

    @Test
    fun noRoomsAtAll_showsNoSuffix_andKeepsThePlainAccessibleName(): Promise<Unit> =
        formTest {
            val (_, links) = sidebar(session(), FakeRpc { counts(0, 0, 0, 0) }, FakeScheduler())
            awaitUntil("the figures arrive") { NavCountsPoller.lastForTest() != null }
            val conference = links.getValue(Routes.CONFERENCE)
            assertNull(conference.getAttribute("aria-label"))
            assertNull(conference.getAttribute("title"))
            assertFalse(links.getValue(Routes.ENCOUNTER).getAttribute("aria-label") != null)
        }

    @Test
    fun theSuffixIsDecoration_notPartOfTheLabel_andNotInTheMenuSearch(): Promise<Unit> =
        formTest {
            val (_, links) = sidebar(session(), FakeRpc { counts() }, FakeScheduler())
            awaitUntil("the figures arrive") { NavCountsPoller.lastForTest() != null }
            val conference = links.getValue(Routes.CONFERENCE)
            assertEquals("Videokonferenz", plainMenuText(conference.label), "the label is untouched")
            val entries = MenuIndex.entries()
            assertEquals("Videokonferenz", entries.first { it.route == Routes.CONFERENCE }.title)
            assertEquals(1, MenuSearchMatcher.search("Videokonferenz", entries, emptyMap()).total)
            assertEquals(0, MenuSearchMatcher.search("2/5", entries, emptyMap()).total, "the figures are not searchable")
        }

    @Test
    fun aHiddenTab_isNotAsked_aTabBecomingVisibleAsksAfterAMinute(): Promise<Unit> =
        formTest {
            val rpc = FakeRpc { counts() }
            val scheduler = FakeScheduler()
            sidebar(session(), rpc, scheduler)
            awaitUntil("the first request") { rpc.calls == 1 }
            scheduler.visible = false
            scheduler.tick()
            delay(30)
            assertEquals(1, rpc.calls, "hidden: the timer asks nothing")
            scheduler.now += 30_000
            scheduler.visible = true
            scheduler.showTab()
            delay(30)
            assertEquals(1, rpc.calls, "visible again after 30 s: not yet")
            scheduler.now += 40_000
            scheduler.showTab()
            awaitUntil("the second request") { rpc.calls == 2 }
            scheduler.tick()
            awaitUntil("the timer asks while the tab is visible") { rpc.calls == 3 }
        }

    @Test
    fun afterSignOut_noTimerNoListenerNoFigures(): Promise<Unit> =
        formTest {
            val scheduler = FakeScheduler()
            val (body, _) = sidebar(session(), FakeRpc { counts() }, scheduler)
            awaitUntil("running") { scheduler.timers.size == 1 && scheduler.visibilityListeners.size == 1 }
            clearSidebar(body)
            assertEquals(0, scheduler.timers.size)
            assertEquals(0, scheduler.visibilityListeners.size)
            assertNull(NavCountsPoller.lastForTest())
            assertEquals(0, NavCountsPoller.boundForTest())
        }

    @Test
    fun aRebuildOfTheSidebar_rebindsWithoutMoreTimers_andTheOldLinksStopChanging(): Promise<Unit> =
        formTest {
            val rpc = FakeRpc { counts() }
            val scheduler = FakeScheduler()
            val s = session()
            val (_, firstLinks) = sidebar(s, rpc, scheduler)
            awaitUntil("first figures") { NavCountsPoller.lastForTest() != null }
            val oldLink = firstLinks.getValue(Routes.CONFERENCE)
            // the sidebar is built again (language change, phone off-canvas): same person, so the poller keeps running
            val body2 = SimplePanel()
            buildSidebar(body2, s, activeRoute = null) {}
            NavCountsPoller.start(ownerId = "${s.memberId}-test", rpc = rpc, scheduler = scheduler)
            assertEquals(1, scheduler.timers.size, "still one timer")
            assertEquals(1, scheduler.visibilityListeners.size, "still one listener")
            assertEquals(2, NavCountsPoller.boundForTest(), "one binding per suffix, the old ones replaced")
            val newLink =
                (body2.getChildren().first() as Nav).getChildren().filterIsInstance<Link>().first {
                    it.url ==
                        "#${Routes.CONFERENCE}"
                }
            assertNotNull(newLink.getAttribute("aria-label"), "the new link shows the last figures at once")
            assertTrue(oldLink !== newLink)
        }

    @Test
    fun aFailure_keepsTheLastFigures_andRaisesNoToast(): Promise<Unit> =
        formTest {
            var failing = false
            val rpc =
                FakeRpc {
                    if (failing) error("boom") else counts()
                }
            val scheduler = FakeScheduler()
            val (_, links) = sidebar(session(), rpc, scheduler)
            awaitUntil("first figures") { NavCountsPoller.lastForTest() != null }
            failing = true
            scheduler.tick()
            awaitUntil("the failing request ran") { rpc.calls == 2 }
            delay(30)
            assertEquals("Videokonferenz, 2 von 5 aktiv", links.getValue(Routes.CONFERENCE).getAttribute("aria-label"))
            assertEquals(
                0,
                kotlinx.browser.document
                    .querySelectorAll(".toast")
                    .length,
            )
        }

    @Test
    fun withoutFigures_thereIsNoSuffixAtAll(): Promise<Unit> =
        formTest {
            val (_, links) = sidebar(session(), FakeRpc { error("down") }, FakeScheduler())
            delay(60)
            assertNull(links.getValue(Routes.CONFERENCE).getAttribute("aria-label"))
            assertNull(NavCountsPoller.lastForTest())
        }

    @Test
    fun theSuffixSpan_isHiddenFromAssistiveTechnology(): Promise<Unit> =
        formTest {
            val (_, links) = sidebar(session(), FakeRpc { counts() }, FakeScheduler())
            awaitUntil("the figures arrive") { NavCountsPoller.lastForTest() != null }
            // KVision renders a widget only when it is in the document: mount the link to look at the DOM
            mountedForm("nav-suffix-a11y") { root, element ->
                val link = links.getValue(Routes.CONFERENCE)
                root.add(link)
                awaitUntil("rendered") { element().querySelector(".nav-live-suffix") != null }
                val span = element().querySelector(".nav-live-suffix") as HTMLElement
                assertEquals("true", span.getAttribute("aria-hidden"))
                assertEquals(" (2/5)", span.textContent)
            }
        }
}
