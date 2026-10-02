package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SELF = "member-1"
private const val OTHER = "member-2"
private const val SPOOF = "###KvI18nS###Quorum heißt"

private class FakeConversationRpc(
    var messages: List<DirectMessageDto> = emptyList(),
) : DirectMessageConversationRpc {
    val listCalls = mutableListOf<String>()
    val markCalls = mutableListOf<String>()
    var failList = false

    override suspend fun listConversation(otherMemberId: String): List<DirectMessageDto> {
        listCalls += otherMemberId
        if (failList) throw ForbiddenException("secret-server-text")
        return messages
    }

    override suspend fun markRead(messageId: String) {
        markCalls += messageId
    }
}

private fun message(
    id: String,
    from: String,
    body: String,
    minute: Int,
    read: Boolean = true,
    senderName: String = if (from == SELF) "Dana Keller" else "Ole Voss",
) = DirectMessageDto(
    id = id,
    senderId = from,
    senderDisplayName = senderName,
    recipientId = if (from == SELF) OTHER else SELF,
    recipientDisplayName = if (from == SELF) "Ole Voss" else "Dana Keller",
    body = body,
    sentAt = LocalDateTime(2026, 10, 2, 9, minute),
    readAt = if (read) LocalDateTime(2026, 10, 2, 10, 0) else null,
)

/** Welle V1.9.34 -- the conversation disclosure and the batched mark-read + counter refresh. */
class DirectMessageConversationDomTest {
    @AfterTest
    fun reset() = UnreadMessages.resetForTest()

    private fun session() =
        SessionInfoDto(
            memberId = SELF,
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private inline fun withDisclosure(
        id: String,
        rpc: FakeConversationRpc,
        otherName: String = "Ole Voss",
        crossinline block: suspend (() -> org.w3c.dom.HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm(id) { root, element ->
                root.vPanel { conversationDisclosure(OTHER, otherName, SELF, rpc) }
                block(element)
            }
        }

    @Test
    fun closed_makesNoCall(): Promise<Unit> {
        val rpc = FakeConversationRpc()
        return withDisclosure("conv-closed", rpc) { el ->
            delay(80)
            assertTrue(rpc.listCalls.isEmpty())
            assertEquals("false", el().buttonNamed("Verlauf anzeigen").getAttribute("aria-expanded"))
        }
    }

    @Test
    fun expanding_loadsOnce_oldestFirst_andLabelsOwnMessagesAsYou(): Promise<Unit> {
        // The server delivers newest first.
        val rpc =
            FakeConversationRpc(
                listOf(message("m3", OTHER, "Dritte", 3), message("m2", SELF, "Zweite", 2), message("m1", OTHER, "Erste", 1)),
            )
        return withDisclosure("conv-open", rpc) { el ->
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("messages rendered") { el().textContent.orEmpty().contains("Dritte") }
            assertEquals(listOf(OTHER), rpc.listCalls)
            val text = el().textContent.orEmpty()
            assertTrue(text.indexOf("Erste") < text.indexOf("Zweite") && text.indexOf("Zweite") < text.indexOf("Dritte"), "ascending")
            val sentByYou = el().allOf("div").filter { it.className.contains("fw-bold") && it.textContent == "Sie" }
            assertEquals(1, sentByYou.size, "only the viewer's own message says 'Sie'")
            assertFalse(el().allOf("div.fw-bold").any { it.textContent == "Dana Keller" }, "own name is never shown")
            assertEquals("true", el().buttonNamed("Verlauf ausblenden").getAttribute("aria-expanded"))
        }
    }

    @Test
    fun anEmptyConversation_showsTheEmptyText(): Promise<Unit> =
        withDisclosure("conv-empty", FakeConversationRpc()) { el ->
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("empty text") { el().textContent.orEmpty().contains("Noch keine Nachrichten in diesem Verlauf.") }
        }

    @Test
    fun aFailingLoad_showsTheErrorState_withoutTheExceptionText(): Promise<Unit> {
        val rpc = FakeConversationRpc().apply { failList = true }
        return withDisclosure("conv-fail", rpc) { el ->
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("error state") { el().textContent.orEmpty().contains("Die Daten konnten nicht geladen werden.") }
            assertFalse(el().textContent.orEmpty().contains("secret-server-text"))
        }
    }

    @Test
    fun foreignTextWithI18nMarkers_isSanitized(): Promise<Unit> {
        val rpc = FakeConversationRpc(listOf(message("m1", OTHER, "$SPOOF Body", 1, senderName = "$SPOOF Name")))
        return withDisclosure("conv-spoof", rpc, otherName = "$SPOOF Partner") { el ->
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("rendered") { el().textContent.orEmpty().contains("Body") }
            val text = el().textContent.orEmpty()
            assertFalse(text.contains("###KvI18nS###"))
            assertTrue(text.contains("Name") && text.contains("Partner"))
        }
    }

    @Test
    fun onlyReceivedUnreadMessages_areMarkedRead_inOrder_thenTheCounterRefreshesOnce(): Promise<Unit> {
        val rpc =
            FakeConversationRpc(
                listOf(
                    message("m4", OTHER, "vier", 4, read = false),
                    message("m3", SELF, "drei", 3, read = false), // own message with an empty readAt: never marked by this side
                    message("m2", OTHER, "zwei", 2, read = false),
                    message("m1", OTHER, "eins", 1, read = true),
                ),
            )
        var counterCalls = 0
        return withDisclosure("conv-mark", rpc) { el ->
            UnreadMessages.rpc =
                UnreadCountRpc {
                    counterCalls++
                    0
                }
            UnreadMessages.attach(Link(label = "x", url = "#/c"))
            awaitUntil("initial counter call") { counterCalls == 1 }
            counterCalls = 0
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("counter refreshed") { counterCalls == 1 }
            assertEquals(listOf("m4", "m2"), rpc.markCalls)
            delay(120)
            assertEquals(1, counterCalls, "one refresh for the whole batch")
        }
    }

    @Test
    fun collapsingAndExpandingAgain_reloads(): Promise<Unit> {
        val rpc = FakeConversationRpc(listOf(message("m1", OTHER, "eins", 1)))
        return withDisclosure("conv-reload", rpc) { el ->
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("first load") { rpc.listCalls.size == 1 && el().textContent.orEmpty().contains("eins") }
            el().buttonNamed("Verlauf ausblenden").click()
            assertFalse(el().textContent.orEmpty().contains("eins"), "collapsed: content removed")
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("second load") { rpc.listCalls.size == 2 }
        }
    }

    @Test
    fun theBatchHelper_marksAllIds_andRefreshesTheCounterOnce(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val rpc = FakeConversationRpc()
            var counterCalls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    counterCalls++
                    0
                }
            UnreadMessages.attach(Link(label = "x", url = "#/c"))
            awaitUntil("initial") { counterCalls == 1 }
            counterCalls = 0
            markReadThenRefreshCounter(listOf("a", "b", "c"), rpc)
            awaitUntil("refreshed") { counterCalls == 1 }
            assertEquals(listOf("a", "b", "c"), rpc.markCalls)
            // An empty inbox still refreshes (the counter must drop to nothing).
            counterCalls = 0
            markReadThenRefreshCounter(emptyList(), rpc)
            awaitUntil("refreshed again") { counterCalls == 1 }
            assertEquals(3, rpc.markCalls.size)
        }
}
