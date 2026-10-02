package network.lapis.cloud.client

import io.kvision.html.Link
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Welle V1.9.34 -- the sidebar's unread counter: label grammar, coalescing, no timer, silent failures. */
class UnreadMessagesCounterTest {
    private val marker = "###KvI18nS###"

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

    private fun link() = Link(label = "start", url = "#/communication")

    @Test
    fun label_noBadgeForNullAndZero_exactCountBelow100_ninetyNinePlusFrom100() {
        assertEquals("${marker}Kommunikation", communicationSidebarLabel(null))
        assertEquals("${marker}Kommunikation", communicationSidebarLabel(0))
        assertEquals("Kommunikation (1)", communicationSidebarLabel(1))
        assertEquals("Kommunikation (99)", communicationSidebarLabel(99))
        assertEquals("Kommunikation (99+)", communicationSidebarLabel(100))
        assertEquals("Kommunikation (99+)", communicationSidebarLabel(250))
    }

    @Test
    fun withoutAttach_refreshMakesNoCall(): Promise<Unit> =
        formTest {
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
    fun attach_loadsOnce_andWritesTheLabel(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    3
                }
            val link = link()
            UnreadMessages.attach(link)
            awaitUntil("label written") { link.label == "Kommunikation (3)" }
            delay(120)
            assertEquals(1, calls, "no timer: nothing follows the first call")
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
            val link = link()
            UnreadMessages.attach(link)
            UnreadMessages.refresh()
            UnreadMessages.refresh()
            gate.complete(Unit)
            awaitUntil("follow-up call done") { calls == 2 && link.label == "Kommunikation (2)" }
            delay(120)
            assertEquals(2, calls)
        }

    @Test
    fun afterDetach_nothingIsWritten(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val gate = CompletableDeferred<Unit>()
            UnreadMessages.rpc =
                UnreadCountRpc {
                    gate.await()
                    5
                }
            val link = link()
            UnreadMessages.attach(link)
            UnreadMessages.detach()
            gate.complete(Unit)
            delay(120)
            assertTrue(link.label == "start", "a late answer must not write into a detached link, was ${link.label}")
        }

    @Test
    fun aFailingCall_keepsThePlainLabel_andShowsNoToast(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    throw ForbiddenException("inactive")
                }
            val link = link()
            UnreadMessages.attach(link)
            awaitUntil("call made") { calls == 1 }
            awaitUntil("plain label") { link.label == "${marker}Kommunikation" }
            delay(80)
            assertEquals(1, calls, "a failure is not retried either")
        }

    @Test
    fun aRouteChange_triggersExactlyOneCall_andUpdatesTheLabel(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var count = 0
            var calls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    calls++
                    count
                }
            val link = link()
            UnreadMessages.attach(link)
            awaitUntil("initial call") { calls == 1 && link.label == "${marker}Kommunikation" }
            count = 1
            UnreadMessages.onRouteShown()
            awaitUntil("label after route change") { link.label == "Kommunikation (1)" }
            delay(120)
            assertEquals(2, calls, "one call per route change, no timer")
        }
}
