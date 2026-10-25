package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OnlineCountsDto
import network.lapis.cloud.shared.domain.OnlineGroupCountsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.215 -- the "Gerade angemeldet" tile of the admin dashboard, driven with a fake RPC. */
class OnlineCountsCardDomTest {
    private fun counts(
        activeTotal: Int,
        signedInTotal: Int,
        other: Int = 0,
        at: LocalDateTime = LocalDateTime(2026, 6, 1, 8, 5),
    ) = OnlineCountsDto(
        active = OnlineGroupCountsDto(total = activeTotal, members = activeTotal, friends = 0, guests = 0, other = 0),
        signedIn =
            OnlineGroupCountsDto(
                total = signedInTotal + other,
                members = signedInTotal - 2,
                friends = 1,
                guests = 1,
                other = other,
            ),
        computedAt = at,
    )

    private class FakeRpc(
        val answers: MutableList<() -> OnlineCountsDto>,
    ) : OnlineCountsRpc {
        var calls = 0

        override suspend fun onlineCounts(): OnlineCountsDto {
            calls++
            return answers[minOf(calls - 1, answers.size - 1)]()
        }
    }

    private fun HTMLElement.refreshButton(): HTMLElement =
        assertNotNull(
            (0 until querySelectorAll("button").length)
                .map { querySelectorAll("button").item(it) as HTMLElement }
                .firstOrNull { it.textContent?.trim() == "Aktualisieren" },
            "no Aktualisieren button",
        )

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Test",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @Test
    fun showsTheFigures_theSplit_andTheExplanation(): Promise<Unit> =
        formTest {
            mountedForm("online-counts-figures") { root, element ->
                val rpc = FakeRpc(mutableListOf({ counts(activeTotal = 5, signedInTotal = 9) }))
                renderOnlineCountsCard(root, rpc = rpc, intervalMs = 3_600_000)
                awaitUntil("the figures appear") {
                    element().textContent?.contains("Gerade aktiv") == true &&
                        element().textContent?.contains("5") == true
                }
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Gerade angemeldet"), text)
                assertTrue(text.contains("Angemeldet insgesamt"), text)
                assertTrue(text.contains("Mitglieder 5 · Freunde 0 · Gäste 0"), text)
                assertTrue(text.contains("Mitglieder 7 · Freunde 1 · Gäste 1"), text)
                assertTrue(text.contains("15 Minuten") && text.contains("8 Stunden"), text)
                assertFalse(text.contains("Sonstige"), text)
                assertTrue(text.contains("Stand: "), text)
                assertEquals(1, rpc.calls)
            }
        }

    @Test
    fun otherIsShownOnlyAboveZero(): Promise<Unit> =
        formTest {
            mountedForm("online-counts-other") { root, element ->
                val rpc = FakeRpc(mutableListOf({ counts(activeTotal = 1, signedInTotal = 4, other = 2) }))
                renderOnlineCountsCard(root, rpc = rpc, intervalMs = 3_600_000)
                awaitUntil("other appears") { element().textContent?.contains("· Sonstige 2") == true }
            }
        }

    @Test
    fun refreshButton_loadsOnce_disablesDuringTheCall_andShowsNewFigures(): Promise<Unit> =
        formTest {
            mountedForm("online-counts-refresh") { root, element ->
                val gate = CompletableDeferred<Unit>()
                var n = 0
                val rpc =
                    object : OnlineCountsRpc {
                        var calls = 0

                        override suspend fun onlineCounts(): OnlineCountsDto {
                            calls++
                            n++
                            if (n == 2) gate.await()
                            return if (n == 1) counts(2, 4) else counts(7, 10, at = LocalDateTime(2026, 6, 1, 8, 6))
                        }
                    }
                renderOnlineCountsCard(root, rpc = rpc, intervalMs = 3_600_000)
                awaitUntil("first figures") { element().textContent?.contains("Mitglieder 2 ·") == true }
                val button = element().refreshButton()
                button.click()
                awaitUntil("button disabled during the call") { element().refreshButton().hasAttribute("disabled") }
                button.click() // a second click while a call runs is a no-op
                gate.complete(Unit)
                awaitUntil("new figures") { element().textContent?.contains("Mitglieder 7 ·") == true }
                assertEquals(2, rpc.calls)
                awaitUntil("button enabled again") { !element().refreshButton().hasAttribute("disabled") }
            }
        }

    @Test
    fun aFailedReload_keepsTheOldFigures_andSaysSo(): Promise<Unit> =
        formTest {
            mountedForm("online-counts-error") { root, element ->
                val rpc = FakeRpc(mutableListOf({ counts(3, 5) }, { throw IllegalStateException("down") }))
                renderOnlineCountsCard(root, rpc = rpc, intervalMs = 3_600_000)
                awaitUntil("first figures") { element().textContent?.contains("Mitglieder 3 ·") == true }
                element().refreshButton().click()
                awaitUntil("the error text") { element().textContent?.contains("Die Zahlen konnten gerade nicht geladen werden.") == true }
                assertTrue(element().textContent.orEmpty().contains("Mitglieder 3 ·"), "old figures stay")
                awaitUntil("button enabled again") { !element().refreshButton().hasAttribute("disabled") }
            }
        }

    @Test
    fun aHiddenTab_doesNotPoll_andNothingPollsAfterTheTileIsGone(): Promise<Unit> =
        formTest {
            mountedForm("online-counts-timer") { root, element ->
                val hidden = FakeRpc(mutableListOf({ counts(1, 3) }))
                var visible = false
                renderOnlineCountsCard(root, rpc = hidden, intervalMs = 40, isTabVisible = { visible })
                awaitUntil("the first load") { hidden.calls == 1 }
                delay(250)
                assertEquals(1, hidden.calls, "no tick loads while the tab is hidden")
                visible = true
                awaitUntil("a tick loads once the tab is visible") { hidden.calls >= 2 }
                // the tile leaves the document: no further calls
                root.removeAll()
                delay(100)
                val after = hidden.calls
                delay(300)
                assertEquals(after, hidden.calls, "no call after the tile is gone")
                assertNotNull(element())
            }
        }

    @Test
    fun noHorizontalOverflowAt360px(): Promise<Unit> =
        formTest {
            mountedForm("online-counts-360") { root, element ->
                element().style.width = "360px"
                element().style.boxSizing = "border-box"
                val rpc = FakeRpc(mutableListOf({ counts(12345, 23456, other = 99) }))
                renderOnlineCountsCard(root, rpc = rpc, intervalMs = 3_600_000)
                awaitUntil("figures") { element().textContent?.contains("Sonstige") == true }
                assertTrue(element().scrollWidth <= element().clientWidth, "overflow ${element().scrollWidth} > ${element().clientWidth}")
            }
        }

    private class CountingScheduler : OnlineCountsScheduler {
        var intervals = 0
        var listeners = 0
        private var nextId = 1

        override fun setInterval(
            callback: () -> Unit,
            ms: Int,
        ): Int {
            intervals++
            return nextId++
        }

        override fun clearInterval(id: Int) {
            intervals--
        }

        override fun addVisibilityListener(listener: (Event) -> Unit) {
            listeners++
        }

        override fun removeVisibilityListener(listener: (Event) -> Unit) {
            listeners--
        }
    }

    /** The cards of the page that hold the heading "Gerade angemeldet" as a leaf element of their own. */
    private fun HTMLElement.onlineTiles(): List<HTMLElement> =
        allOf(".card-body").filter { body ->
            body.allOf("*").any { it.children.length == 0 && it.textContent?.trim() == "Gerade angemeldet" }
        }

    @Test
    fun onlyAdminSeesTheTile_onTheDashboard(): Promise<Unit> =
        formTest {
            AccountRole.entries.forEach { role ->
                AppState.setSession(session(role))
                assertEquals(role == AccountRole.ADMIN, shouldShowOnlineCounts(), "$role")
            }
            withFetchStub {
                listOf(AccountRole.MEMBER, AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                    mountedForm("online-counts-dashboard-$role") { root, element ->
                        AppState.setSession(session(role))
                        renderDashboardScreen(root, FakeRpc(mutableListOf({ counts(1, 3) })), CountingScheduler())
                        assertEquals(if (role == AccountRole.ADMIN) 1 else 0, element().onlineTiles().size, "tiles for $role")
                    }
                }
            }
        }

    @Test
    fun dashboardAsAdmin_rendersExactlyOneOnlineTile_andOneTimer(): Promise<Unit> =
        formTest {
            withFetchStub {
                mountedForm("online-counts-dashboard-once") { root, element ->
                    AppState.setSession(session(AccountRole.ADMIN))
                    val rpc = FakeRpc(mutableListOf({ counts(activeTotal = 5, signedInTotal = 9) }))
                    val scheduler = CountingScheduler()
                    renderDashboardScreen(root, rpc, scheduler)

                    fun assertOneTile(stage: String) {
                        val tiles = element().onlineTiles()
                        assertEquals(1, tiles.size, "$stage: tiles with the heading")
                        val refreshButtons = tiles.single().allOf("button").filter { it.textContent?.trim() == "Aktualisieren" }
                        assertEquals(1, refreshButtons.size, "$stage: refresh buttons")
                        assertEquals(1, scheduler.intervals, "$stage: running intervals")
                        assertEquals(1, scheduler.listeners, "$stage: visibility listeners")
                    }
                    assertOneTile("right after the render")
                    awaitUntil("the figures appear") { element().textContent?.contains("Mitglieder 5 · Freunde 0 · Gäste 0") == true }
                    assertFalse(element().textContent.orEmpty().contains("Wird geladen"), "nothing is left loading")
                    assertEquals(1, rpc.calls, "one load")
                    assertOneTile("after the first load")
                    // A late re-render of the page (the member card loads in AppScope) would show up as a second tile.
                    delay(300)
                    assertOneTile("300 ms later")
                    assertEquals(1, rpc.calls, "still one load")

                    root.removeAll()
                    awaitUntil("the timer and the listener are released") { scheduler.intervals == 0 && scheduler.listeners == 0 }
                }
            }
        }
}
