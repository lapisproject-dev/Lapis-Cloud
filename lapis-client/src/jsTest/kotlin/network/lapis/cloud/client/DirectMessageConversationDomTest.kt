package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageCursorDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
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

/** A fake server: [all] is newest first; pages are cut by the keyset cursor exactly like the real service. */
private class FakeConversationRpc(
    var all: List<DirectMessageDto> = emptyList(),
) : DirectMessageConversationRpc {
    val pageCalls = mutableListOf<Triple<String, String?, Int>>()
    val markCalls = mutableListOf<String>()
    var failList = false
    var failOlder = false
    var markReturns = 0

    override suspend fun listConversationPage(
        otherMemberId: String,
        beforeSentAt: LocalDateTime?,
        beforeId: String?,
        limit: Int,
    ): DirectMessagePageDto {
        pageCalls += Triple(otherMemberId, beforeId, limit)
        if (failList) throw ForbiddenException("secret-server-text")
        if (beforeId != null && failOlder) throw ForbiddenException("secret-server-text")
        val start = if (beforeId == null) 0 else all.indexOfFirst { it.id == beforeId } + 1
        val slice = all.drop(start).take(limit)
        val last = slice.lastOrNull()
        return DirectMessagePageDto(
            messages = slice,
            hasMore = start + slice.size < all.size,
            nextCursor = last?.let { DirectMessageCursorDto(it.sentAt, it.id) },
        )
    }

    override suspend fun markConversationRead(otherMemberId: String): Int {
        markCalls += otherMemberId
        return markReturns
    }
}

private fun message(
    id: String,
    from: String,
    body: String,
    minute: Int,
    read: Boolean = true,
    senderName: String = if (from == SELF) "Dana Keller" else "Ole Voss",
    hour: Int = 9,
) = DirectMessageDto(
    id = id,
    senderId = from,
    senderDisplayName = senderName,
    recipientId = if (from == SELF) OTHER else SELF,
    recipientDisplayName = if (from == SELF) "Ole Voss" else "Dana Keller",
    body = body,
    sentAt = LocalDateTime(2026, 10, 2, hour, minute),
    readAt = if (read) LocalDateTime(2026, 10, 2, 23, 0) else null,
)

/** Welle V1.9.34/V1.9.36 -- the conversation disclosure and view: paging, "Sie", one mark-read call, sanitizing. */
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

    private inline fun withView(
        id: String,
        rpc: FakeConversationRpc,
        knownUnread: Int = 0,
        crossinline block: suspend (() -> org.w3c.dom.HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm(id) { root, element ->
                root.vPanel { conversationView(OTHER, knownUnread, SELF, rpc) }
                block(element)
            }
        }

    @Test
    fun closed_makesNoCall(): Promise<Unit> {
        val rpc = FakeConversationRpc()
        return withDisclosure("conv-closed", rpc) { el ->
            delay(80)
            assertTrue(rpc.pageCalls.isEmpty())
            assertEquals("false", el().buttonNamed("Verlauf anzeigen").getAttribute("aria-expanded"))
        }
    }

    @Test
    fun expanding_loadsTheFirstPage_oldestFirst_andLabelsOwnMessagesAsYou(): Promise<Unit> {
        // The server delivers newest first.
        val rpc =
            FakeConversationRpc(
                listOf(message("m3", OTHER, "Dritte", 3), message("m2", SELF, "Zweite", 2), message("m1", OTHER, "Erste", 1)),
            )
        return withDisclosure("conv-open", rpc) { el ->
            el().buttonNamed("Verlauf anzeigen").click()
            awaitUntil("messages rendered") { el().textContent.orEmpty().contains("Dritte") }
            assertEquals(listOf(Triple<String, String?, Int>(OTHER, null, DIRECT_MESSAGE_PAGE_SIZE)), rpc.pageCalls)
            val text = el().textContent.orEmpty()
            assertTrue(text.indexOf("Erste") < text.indexOf("Zweite") && text.indexOf("Zweite") < text.indexOf("Dritte"), "ascending")
            val sentByYou = el().allOf("div").filter { it.className.contains("fw-bold") && it.textContent == "Sie" }
            assertEquals(1, sentByYou.size, "only the viewer's own message says 'Sie'")
            assertFalse(el().allOf("div.fw-bold").any { it.textContent == "Dana Keller" }, "own name is never shown")
            assertEquals("true", el().buttonNamed("Verlauf ausblenden").getAttribute("aria-expanded"))
            assertFalse(text.contains("Ältere Nachrichten laden"), "no older page: no button")
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
    fun unreadMessagesToMe_causeExactlyOneMarkConversationReadCall_thenOneCounterRefresh(): Promise<Unit> {
        val rpc =
            FakeConversationRpc(
                listOf(
                    message("m4", OTHER, "vier", 4, read = false),
                    message("m3", SELF, "drei", 3, read = false), // own message with an empty readAt: not "unread to me"
                    message("m2", OTHER, "zwei", 2, read = false),
                    message("m1", OTHER, "eins", 1, read = true),
                ),
            )
        var counterCalls = 0
        var marked = 0
        return formTest {
            AppState.setSession(session())
            mountedForm("conv-mark") { root, element ->
                UnreadMessages.rpc =
                    UnreadCountRpc {
                        counterCalls++
                        0
                    }
                UnreadMessages.register("t") {}
                awaitUntil("initial counter call") { counterCalls == 1 }
                counterCalls = 0
                root.vPanel { conversationView(OTHER, 0, SELF, rpc, onMarked = { marked++ }) }
                awaitUntil("counter refreshed") { counterCalls == 1 }
                assertEquals(listOf(OTHER), rpc.markCalls)
                delay(120)
                assertEquals(1, counterCalls, "one refresh for the whole conversation")
                assertEquals(1, marked)
                assertTrue(element().textContent.orEmpty().contains("vier"))
            }
        }
    }

    @Test
    fun noUnreadAnywhere_makesNoMarkCall(): Promise<Unit> {
        val rpc = FakeConversationRpc(listOf(message("m1", OTHER, "eins", 1, read = true)))
        return withView("conv-nomark", rpc) { el ->
            awaitUntil("rendered") { el().textContent.orEmpty().contains("eins") }
            delay(80)
            assertTrue(rpc.markCalls.isEmpty())
        }
    }

    @Test
    fun knownUnreadBeyondTheFirstPage_stillMarksTheConversation(): Promise<Unit> {
        val rpc = FakeConversationRpc(listOf(message("m1", OTHER, "eins", 1, read = true)))
        return withView("conv-known", rpc, knownUnread = 3) { el ->
            awaitUntil("marked") { rpc.markCalls == listOf(OTHER) }
            assertTrue(el().textContent.orEmpty().contains("eins"))
        }
    }

    private fun many(count: Int) =
        (count downTo 1).map { i -> message("m$i", if (i % 2 == 0) OTHER else SELF, "Text $i", i % 60, hour = 1 + i / 60) }

    private fun org.w3c.dom.HTMLElement.bodies(): List<String> =
        allOf("div").map { it.textContent.orEmpty() }.filter { Regex("^Text \\d+$").matches(it) }

    @Test
    fun olderMessages_arePrependedOnTop_withoutDuplicates_andTheButtonDisappearsAtTheEnd(): Promise<Unit> {
        val rpc = FakeConversationRpc(many(DIRECT_MESSAGE_PAGE_SIZE + 10))
        return withView("conv-older", rpc) { el ->
            awaitUntil("first page") { el().bodies().size == DIRECT_MESSAGE_PAGE_SIZE }
            assertEquals("Text ${DIRECT_MESSAGE_PAGE_SIZE + 10}", el().bodies().last(), "newest at the bottom")
            el().buttonNamed("Ältere Nachrichten laden").click()
            awaitUntil("older page") { el().bodies().size == DIRECT_MESSAGE_PAGE_SIZE + 10 }
            val bodies = el().bodies()
            assertEquals("Text 1", bodies.first(), "older ones are on top")
            assertEquals("Text ${DIRECT_MESSAGE_PAGE_SIZE + 10}", bodies.last())
            assertEquals(bodies.size, bodies.toSet().size, "no duplicates")
            assertTrue(el().allOf("button").none { it.textContent?.trim() == "Ältere Nachrichten laden" }, "no more pages: no button")
        }
    }

    @Test
    fun atTheClientCap_aSentenceReplacesTheButton(): Promise<Unit> {
        val rpc = FakeConversationRpc(many(DIRECT_MESSAGE_CLIENT_CAP + 60))
        return withView("conv-cap", rpc) { el ->
            awaitUntil("first page") { rpc.pageCalls.size == 1 && el().textContent.orEmpty().contains("Ältere Nachrichten laden") }
            repeat(DIRECT_MESSAGE_CLIENT_CAP / DIRECT_MESSAGE_PAGE_SIZE - 1) { round ->
                el().buttonNamed("Ältere Nachrichten laden").click()
                awaitUntil("page ${round + 2}") { rpc.pageCalls.size == round + 2 }
                awaitUntil("button back or sentence") {
                    val t = el().textContent.orEmpty()
                    t.contains("Ältere Nachrichten laden") || t.contains("werden hier nicht angezeigt")
                }
            }
            awaitUntil("cap sentence") { el().textContent.orEmpty().contains("Ältere Nachrichten werden hier nicht angezeigt.") }
            assertTrue(el().allOf("button").none { it.textContent?.trim() == "Ältere Nachrichten laden" })
        }
    }

    @Test
    fun aFailingOlderLoad_showsOneSentence_andKeepsTheLoadedMessages(): Promise<Unit> {
        val rpc = FakeConversationRpc(many(DIRECT_MESSAGE_PAGE_SIZE + 5)).apply { failOlder = true }
        return withView("conv-older-fail", rpc) { el ->
            awaitUntil("first page") { el().textContent.orEmpty().contains("Ältere Nachrichten laden") }
            el().buttonNamed("Ältere Nachrichten laden").click()
            awaitUntil("sentence") { el().textContent.orEmpty().contains("Ältere Nachrichten konnten nicht geladen werden.") }
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Text ${DIRECT_MESSAGE_PAGE_SIZE + 5}"), "loaded messages stay")
            assertFalse(text.contains("secret-server-text"))
        }
    }

    @Test
    fun theBatchHelper_marksAllIds_andRefreshesTheCounterOnce(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val marked = mutableListOf<String>()
            var counterCalls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    counterCalls++
                    0
                }
            UnreadMessages.register("t") {}
            awaitUntil("initial") { counterCalls == 1 }
            counterCalls = 0
            markReadThenRefreshCounter(listOf("a", "b", "c"), MarkReadRpc { marked += it })
            awaitUntil("refreshed") { counterCalls == 1 }
            assertEquals(listOf("a", "b", "c"), marked)
            // An empty inbox still refreshes (the counter must drop to nothing).
            counterCalls = 0
            markReadThenRefreshCounter(emptyList(), MarkReadRpc { marked += it })
            awaitUntil("refreshed again") { counterCalls == 1 }
            assertEquals(3, marked.size)
        }
}
