package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Welle V1.9.34/V1.9.36 -- the unread state: pill grammar, registry of displays, coalescing, tab-return throttle, no timer, silent failures. */
class UnreadMessagesCounterTest {
    @AfterTest
    fun reset() = UnreadMessages.resetForTest()

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    @Test
    fun pillText_noPillForNullAndZero_exactBelow100_ninetyNinePlusFrom100() {
        assertNull(unreadPillText(null))
        assertNull(unreadPillText(0))
        assertEquals("1", unreadPillText(1))
        assertEquals("99", unreadPillText(99))
        assertEquals("99+", unreadPillText(100))
        assertEquals("99+", unreadPillText(250))
    }

    @Test
    fun accessibleName_plainWithoutCount_withCountOtherwise() {
        assertEquals("Nachrichten", navbarUnreadAccessibleName(null))
        assertEquals("Nachrichten", navbarUnreadAccessibleName(0))
        assertEquals("Nachrichten, 3 ungelesen", navbarUnreadAccessibleName(3))
        assertEquals("Nachrichten, 99+ ungelesen", navbarUnreadAccessibleName(100))
    }

    @Test
    fun withoutADisplay_refreshMakesNoCall(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    1
                }
            UnreadMessages.refresh()
            delay(80)
            assertEquals(0, calls)
        }

    @Test
    fun withoutASession_registerMakesNoCall(): Promise<Unit> =
        formTest {
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    1
                }
            UnreadMessages.register("k") {}
            delay(80)
            assertEquals(0, calls)
        }

    @Test
    fun register_loadsOnce_andRendersTheCount(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    3
                }
            var shown: Int? = null
            UnreadMessages.register("k") { shown = it }
            awaitUntil("count rendered") { shown == 3 }
            delay(120)
            assertEquals(1, calls, "no timer: nothing follows the first call")
        }

    @Test
    fun registeringTheSameKeyTwice_replacesTheDisplay(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 4 }
            var first: Int? = -1
            var second: Int? = -1
            UnreadMessages.register("k") { first = it }
            UnreadMessages.register("k") { second = it }
            awaitUntil("second display rendered") { second == 4 }
            delay(60)
            assertEquals(null, first, "the replaced display only saw the initial render")
        }

    @Test
    fun parallelRefreshes_areCoalesced_toAtMostTwoCalls(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    gate.await()
                    2
                }
            var shown: Int? = null
            UnreadMessages.register("k") { shown = it }
            UnreadMessages.refresh()
            UnreadMessages.refresh()
            gate.complete(Unit)
            awaitUntil("follow-up call done") { calls == 2 && shown == 2 }
            delay(120)
            assertEquals(2, calls)
        }

    @Test
    fun afterClear_aLateAnswerIsNotWritten(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val gate = CompletableDeferred<Unit>()
            UnreadMessages.rpc =
                UnreadCountRpc {
                    gate.await()
                    5
                }
            var shown: Int? = null
            UnreadMessages.register("k") { shown = it }
            UnreadMessages.clear()
            gate.complete(Unit)
            delay(120)
            assertNull(shown)
            assertNull(UnreadMessages.count, "the late answer of the previous session must not become the state either")
        }

    @Test
    fun aFailingCall_rendersNoPill_notRetried(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    throw ForbiddenException("inactive")
                }
            var shown: Int? = 7
            UnreadMessages.register("k") { shown = it }
            awaitUntil("call made and failure rendered") { calls == 1 && shown == null }
            delay(80)
            assertEquals(1, calls, "a failure is not retried either")
        }

    @Test
    fun aRouteChange_isUnthrottled_twoQuickChangesTwoCalls(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var count = 0
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    count
                }
            var shown: Int? = -1
            UnreadMessages.register("k") { shown = it }
            awaitUntil("initial call") { calls == 1 }
            count = 1
            UnreadMessages.onRouteShown()
            awaitUntil("after first route change") { shown == 1 && calls == 2 }
            count = 2
            UnreadMessages.onRouteShown()
            awaitUntil("after second route change") { shown == 2 && calls == 3 }
            delay(120)
            assertEquals(3, calls, "one call per route change, no timer")
        }

    @Test
    fun tabReturn_isThrottledByATimestamp(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    0
                }
            var clock = 1_000_000.0
            UnreadMessages.now = { clock }
            UnreadMessages.isTabVisible = { true }
            UnreadMessages.register("k") {}
            awaitUntil("initial call") { calls == 1 }
            UnreadMessages.onTabReturn()
            awaitUntil("first tab return") { calls == 2 }
            clock += 10_000.0
            UnreadMessages.onTabReturn()
            delay(80)
            assertEquals(2, calls, "inside the 30 s throttle")
            clock += TAB_RETURN_REFRESH_THROTTLE_MS
            UnreadMessages.onTabReturn()
            awaitUntil("after the throttle") { calls == 3 }
        }

    @Test
    fun aHiddenTab_doesNotRefresh(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    0
                }
            UnreadMessages.isTabVisible = { false }
            UnreadMessages.register("k") {}
            awaitUntil("initial call") { calls == 1 }
            UnreadMessages.onTabReturn()
            delay(80)
            assertEquals(1, calls)
        }

    @Test
    fun visibilityChangeAndFocusRightAfterEachOther_makeOneCall(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    0
                }
            UnreadMessages.isTabVisible = { true }
            UnreadMessages.register("k") {}
            awaitUntil("initial call") { calls == 1 }
            document.dispatchEvent(Event("visibilitychange"))
            window.dispatchEvent(Event("focus"))
            awaitUntil("one tab-return call") { calls == 2 }
            delay(120)
            assertEquals(2, calls, "the second event is throttled away")
        }
}
