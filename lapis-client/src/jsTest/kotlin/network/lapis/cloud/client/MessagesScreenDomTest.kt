package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDirectMessageService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.231 -- the "Nachrichten" screen: one list of conversations, type marker, no separate "Postfach", opening shows the thread. */
class MessagesScreenDomTest {
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

    private fun partner(
        id: String,
        name: String,
        unread: Int = 0,
        minute: Int = 0,
    ) = DirectMessagePartnerDto(id, name, LocalDateTime(2026, 10, 2, 9, minute), unread)

    private inline fun withScreen(
        id: String,
        partners: List<DirectMessagePartnerDto>,
        crossinline block: suspend (() -> HTMLElement, List<RecordedRequest>, List<String>) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            val listPartners = routeOf { rpcService<IDirectMessageService>().listConversationPartners(DIRECT_MESSAGE_PARTNER_CAP) }
            val listInbox = routeOf { rpcService<IDirectMessageService>().listInbox() }
            val mark = routeOf { rpcService<IDirectMessageService>().markRead("x") }
            val markConversation = routeOf { rpcService<IDirectMessageService>().markConversationRead("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listPartners ->
                            request.answerWith(jsonOf(ListSerializer(DirectMessagePartnerDto.serializer()), partners))
                        request.rpcRoute == listInbox -> request.answerWith("[]")
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm(id) { root, element ->
                    renderMessagesScreen(root, "#/messages")
                    block(element, calls, listOf(listInbox, mark, markConversation))
                }
            }
        }

    @Test
    fun noConversations_showsTheEmptyText_andNoPostfachHeading(): Promise<Unit> =
        withScreen("msg-empty", emptyList()) { el, _, _ ->
            awaitUntil("empty") { el().textContent.orEmpty().contains("Noch keine Nachrichten.") }
            assertFalse(el().textContent.orEmpty().contains("Postfach"))
        }

    @Test
    fun everyRow_isAConversation_withIconAndWord_unreadBadgeOnlyWhenUnread_andNoMarkOnLoad(): Promise<Unit> =
        withScreen(
            "msg-rows",
            listOf(partner("p1", "Ole Voss", unread = 2, minute = 5), partner("p2", "Eva Lind", minute = 9)),
        ) { el, calls, routes ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Ole Voss") }
            assertEquals(2, el().allOf("i.fa-comments").size)
            assertEquals(2, el().allOf("span").count { it.textContent?.trim() == "Gespräch" })
            assertEquals(1, el().allOf(".badge").size, "a badge only for the partner with unread messages")
            assertTrue(el().textContent.orEmpty().contains("2 neu"))
            delay(150)
            assertEquals(0, calls.toRoute(routes[1]).size + calls.toRoute(routes[2]).size, "loading the screen marks nothing read")
            assertEquals(0, calls.toRoute(routes[0]).size, "no separate inbox call any more")
        }

    @Test
    fun newestConversationComesFirst(): Promise<Unit> =
        withScreen("msg-order", listOf(partner("p1", "Alt", minute = 1), partner("p2", "Neu", minute = 40))) { el, _, _ ->
            awaitUntil("rows") { el().textContent.orEmpty().contains("Alt") }
            val names = el().allOf("button").map { it.textContent?.trim().orEmpty() }.filter { it == "Alt" || it == "Neu" }
            assertEquals(listOf("Neu", "Alt"), names)
        }
}
