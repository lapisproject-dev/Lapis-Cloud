package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageCursorDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePartnerRpc(
    var partners: List<DirectMessagePartnerDto> = emptyList(),
) : DirectMessagePartnerListRpc {
    var calls = 0
    var fail = false

    override suspend fun listConversationPartners(limit: Int): List<DirectMessagePartnerDto> {
        calls++
        if (fail) throw ForbiddenException("secret-server-text")
        return partners
    }
}

private class FakeThreadRpc(
    private val unreadMessages: Boolean = true,
    private val onMark: () -> Unit = {},
) : DirectMessageConversationRpc {
    val pageCalls = mutableListOf<String>()
    val markCalls = mutableListOf<String>()
    private val read = mutableSetOf<String>()

    override suspend fun listConversationPage(
        otherMemberId: String,
        beforeSentAt: LocalDateTime?,
        beforeId: String?,
        limit: Int,
    ): DirectMessagePageDto {
        pageCalls += otherMemberId
        val m =
            DirectMessageDto(
                id = "msg-$otherMemberId",
                senderId = otherMemberId,
                senderDisplayName = "Partner",
                recipientId = "member-1",
                recipientDisplayName = "Dana Keller",
                body = "Hallo von $otherMemberId",
                sentAt = LocalDateTime(2026, 10, 2, 9, 0),
                readAt = if (!unreadMessages || otherMemberId in read) LocalDateTime(2026, 10, 2, 23, 0) else null,
            )
        return DirectMessagePageDto(listOf(m), false, DirectMessageCursorDto(m.sentAt, m.id))
    }

    override suspend fun markConversationRead(otherMemberId: String): Int {
        markCalls += otherMemberId
        read += otherMemberId
        onMark()
        return 1
    }
}

private fun partner(
    id: String,
    name: String,
    unread: Int = 0,
    minute: Int = 0,
) = DirectMessagePartnerDto(id, name, LocalDateTime(2026, 10, 2, 9, minute), unread)

/** Welle V1.9.36 -- "Gespräche": list grammar, first-load hook, opening a conversation, mark-read once, one open at a time. */
class DirectMessagePartnerListDomTest {
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

    private inline fun withList(
        id: String,
        rpc: FakePartnerRpc,
        thread: DirectMessageConversationRpc = FakeThreadRpc(),
        crossinline onSettled: () -> Unit = {},
        noinline replySend: suspend (String, String) -> DirectMessageDto = { _, _ -> error("no reply expected") },
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            mountedForm(id) { root, element ->
                root.vPanel {
                    renderDirectMessagePartnerList(
                        this,
                        rpc,
                        thread,
                        onFirstLoadSettled = { onSettled() },
                        replySend = replySend,
                    )
                }
                block(element)
            }
        }

    @Test
    fun noPartners_showsTheEmptyText(): Promise<Unit> =
        withList("pl-empty", FakePartnerRpc()) { el ->
            awaitUntil("empty") { el().textContent.orEmpty().contains("Noch keine Gespräche.") }
            assertEquals("Gespräche", el().allOf("h2").first().textContent)
        }

    @Test
    fun partners_showNameTimeAndUnreadHint_neverAText(): Promise<Unit> {
        val rpc =
            FakePartnerRpc(
                listOf(partner("p1", "Ole Voss", unread = 3, minute = 5), partner("p2", "Eva Lind", unread = 120), partner("p3", "Zoe", 0)),
            )
        return withList("pl-rows", rpc) { el ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Ole Voss") }
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("3 neu") && text.contains("99+ neu"))
            assertEquals(2, el().allOf(".badge").size, "no badge for a partner without unread")
            assertTrue(el().buttonNamed("Ole Voss").className.contains("fw-bold"))
            assertFalse(el().buttonNamed("Zoe").className.contains("fw-bold"))
            assertTrue(text.contains("09:05") || text.contains("9:05"), "last activity time is shown")
        }
    }

    @Test
    fun atTheCap_theHintIsShown(): Promise<Unit> {
        val rpc = FakePartnerRpc((1..DIRECT_MESSAGE_PARTNER_CAP).map { partner("p$it", "Person $it") })
        return withList("pl-cap", rpc) { el ->
            awaitUntil("hint") { el().textContent.orEmpty().contains("Es werden die 100 zuletzt aktiven Gespräche angezeigt.") }
        }
    }

    @Test
    fun onFirstLoadSettled_runsOnceAfterSuccess(): Promise<Unit> {
        var settled = 0
        return withList("pl-settled-ok", FakePartnerRpc(listOf(partner("p1", "Ole"))), onSettled = { settled++ }) { el ->
            awaitUntil("settled") { settled == 1 }
            delay(80)
            assertEquals(1, settled)
        }
    }

    @Test
    fun onFirstLoadSettled_runsOnceAlsoAfterAFailure_withoutTheExceptionText(): Promise<Unit> {
        var settled = 0
        val rpc = FakePartnerRpc().apply { fail = true }
        return withList("pl-settled-fail", rpc, onSettled = { settled++ }) { el ->
            awaitUntil("settled") { settled == 1 }
            awaitUntil("error state") { el().textContent.orEmpty().contains("Die Daten konnten nicht geladen werden.") }
            assertFalse(el().textContent.orEmpty().contains("secret-server-text"))
            delay(80)
            assertEquals(1, settled)
        }
    }

    @Test
    fun opening_loadsTheConversation_marksItReadOnce_refreshesTheCounterOnce_andReloadsTheList(): Promise<Unit> {
        val rpc = FakePartnerRpc(listOf(partner("p1", "Ole Voss", unread = 2)))
        val thread = FakeThreadRpc(onMark = { rpc.partners = listOf(partner("p1", "Ole Voss", unread = 0)) })
        var counterCalls = 0
        return withList("pl-open", rpc, thread) { el ->
            UnreadMessages.rpc =
                UnreadCountRpc {
                    counterCalls++
                    0
                }
            UnreadMessages.register("t") {}
            awaitUntil("initial counter call") { counterCalls == 1 }
            counterCalls = 0
            awaitUntil("rows") { el().textContent.orEmpty().contains("Ole Voss") }
            el().buttonNamed("Ole Voss").click()
            awaitUntil("conversation shown") { el().textContent.orEmpty().contains("Hallo von p1") }
            awaitUntil("marked") { thread.markCalls == listOf("p1") }
            awaitUntil("list reloaded") { rpc.calls == 2 }
            awaitUntil("counter refreshed") { counterCalls >= 1 }
            delay(150)
            assertEquals(1, thread.markCalls.size, "one mark-read call")
            assertEquals(1, counterCalls, "one counter refresh")
            assertEquals(2, rpc.calls, "one reload of the list")
            assertTrue(el().textContent.orEmpty().contains("Hallo von p1"), "the conversation stays open after the reload")
            assertEquals(0, el().allOf(".badge").size, "the unread hint is gone after the reload")
            assertTrue(el().textContent.orEmpty().contains("Antworten"), "the reply form is below the conversation")
        }
    }

    @Test
    fun aConversationWithoutUnread_isNotMarked_andNotReloaded(): Promise<Unit> {
        val rpc = FakePartnerRpc(listOf(partner("p1", "Ole Voss")))
        val thread = FakeThreadRpc(unreadMessages = false)
        return withList("pl-nomark", rpc, thread) { el ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Ole Voss") }
            el().buttonNamed("Ole Voss").click()
            awaitUntil("conversation shown") { el().textContent.orEmpty().contains("Hallo von p1") }
            delay(120)
            assertTrue(thread.markCalls.isEmpty())
            assertEquals(1, rpc.calls, "no reload")
            assertEquals("true", el().buttonNamed("Ole Voss").getAttribute("aria-expanded"))
        }
    }

    @Test
    fun onlyOneConversationIsOpenAtATime_andASecondClickCloses(): Promise<Unit> {
        val rpc = FakePartnerRpc(listOf(partner("p1", "Ole Voss"), partner("p2", "Eva Lind")))
        val thread = FakeThreadRpc(unreadMessages = false)
        return withList("pl-one", rpc, thread) { el ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Eva Lind") }
            el().buttonNamed("Ole Voss").click()
            awaitUntil("first open") { el().textContent.orEmpty().contains("Hallo von p1") }
            el().buttonNamed("Eva Lind").click()
            awaitUntil("second open") { el().textContent.orEmpty().contains("Hallo von p2") }
            assertFalse(el().textContent.orEmpty().contains("Hallo von p1"), "the first one closed")
            assertEquals("false", el().buttonNamed("Ole Voss").getAttribute("aria-expanded"))
            assertEquals("true", el().buttonNamed("Eva Lind").getAttribute("aria-expanded"))
            el().buttonNamed("Eva Lind").click()
            assertFalse(el().textContent.orEmpty().contains("Hallo von p2"), "a second click closes")
            assertEquals("false", el().buttonNamed("Eva Lind").getAttribute("aria-expanded"))
        }
    }

    @Test
    fun aPartnerNameWithI18nMarkers_isNeutralized(): Promise<Unit> {
        val rpc = FakePartnerRpc(listOf(partner("p1", "###KvI18nS###Quorum heißt")))
        return withList("pl-spoof", rpc) { el ->
            awaitUntil("row") { el().textContent.orEmpty().contains("Quorum heißt") }
            assertFalse(el().textContent.orEmpty().contains("###KvI18nS###"))
        }
    }

    private fun sentMessage(body: String) =
        DirectMessageDto(
            id = "sent-1",
            senderId = "member-1",
            senderDisplayName = "Dana Keller",
            recipientId = "p1",
            recipientDisplayName = "Ole Voss",
            body = body,
            sentAt = LocalDateTime(2026, 10, 2, 10, 0),
            readAt = null,
        )

    @Test
    fun aSuccessfulReply_reloadsTheList_andKeepsTheConversationOpen(): Promise<Unit> {
        val rpc = FakePartnerRpc(listOf(partner("p1", "Ole Voss")))
        val thread = FakeThreadRpc(unreadMessages = false)
        val sent = mutableListOf<Pair<String, String>>()
        return withList(
            "pl-reply-ok",
            rpc,
            thread,
            replySend = { to, body ->
                sent += to to body
                sentMessage(body)
            },
        ) { el ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Ole Voss") }
            el().buttonNamed("Ole Voss").click()
            awaitUntil("conversation shown") { el().textContent.orEmpty().contains("Hallo von p1") }
            el().typeInto("Antwort", "  Hallo zurück  ")
            el().buttonNamed("Antworten").click()
            awaitUntil("sent") { sent.size == 1 }
            awaitUntil("list reloaded") { rpc.calls == 2 }
            delay(100)
            assertEquals("p1" to "Hallo zurück", sent.single())
            assertTrue(el().textContent.orEmpty().contains("Hallo von p1"), "the conversation stays open after the reload")
            assertEquals("true", el().buttonNamed("Ole Voss").getAttribute("aria-expanded"))
        }
    }

    @Test
    fun aFailedReply_keepsTheTypedText_andDoesNotReloadTheList(): Promise<Unit> {
        val rpc = FakePartnerRpc(listOf(partner("p1", "Ole Voss")))
        val thread = FakeThreadRpc(unreadMessages = false)
        var attempts = 0
        return withList(
            "pl-reply-fail",
            rpc,
            thread,
            replySend = { _, _ ->
                attempts++
                throw ForbiddenException("secret-server-text")
            },
        ) { el ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Ole Voss") }
            el().buttonNamed("Ole Voss").click()
            awaitUntil("conversation shown") { el().textContent.orEmpty().contains("Hallo von p1") }
            el().typeInto("Antwort", "Eine lange Antwort")
            el().buttonNamed("Antworten").click()
            awaitUntil("attempted") { attempts == 1 }
            delay(150)
            assertEquals(1, rpc.calls, "a failed send does not reload the list")
            assertEquals("Eine lange Antwort", (el().controlOf("Antwort") as HTMLTextAreaElement).value, "the text is kept")
            assertFalse(el().textContent.orEmpty().contains("secret-server-text"))
        }
    }
}
