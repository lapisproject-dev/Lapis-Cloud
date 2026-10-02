package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Welle V1.9.36 -- the reply form: a send refreshes the unread counter and calls onSent; a failure keeps the text and calls onFailed only. */
class DirectMessageReplyFormDomTest {
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

    private fun message(body: String) =
        DirectMessageDto(
            id = "m-1",
            senderId = "member-1",
            senderDisplayName = "Dana Keller",
            recipientId = "p1",
            recipientDisplayName = "Ole Voss",
            body = body,
            sentAt = LocalDateTime(2026, 10, 2, 10, 0),
            readAt = null,
        )

    @Test
    fun aSuccessfulSend_sendsTheTrimmedText_refreshesTheCounter_callsOnSent(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var counterCalls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    counterCalls++
                    0
                }
            UnreadMessages.register("t") {}
            awaitUntil("initial counter call") { counterCalls == 1 }
            val sent = mutableListOf<Pair<String, String>>()
            var onSent = 0
            var onFailed = 0
            mountedForm("rf-ok") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = "p1",
                        onSent = { onSent++ },
                        onFailed = { onFailed++ },
                        send = { to, body ->
                            sent += to to body
                            message(body)
                        },
                    )
                }
                element().typeInto("Antwort", "  Hallo  ")
                element().buttonNamed("Antworten").click()
                awaitUntil("sent") { onSent == 1 }
                awaitUntil("counter refreshed") { counterCalls >= 2 }
                assertEquals("p1" to "Hallo", sent.single())
                assertEquals(0, onFailed)
            }
        }

    @Test
    fun aFailedSend_callsOnFailedOnly_keepsTheText_noCounterRefresh(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var counterCalls = 0
            UnreadMessages.rpc =
                UnreadCountRpc {
                    counterCalls++
                    0
                }
            var onSent = 0
            var onFailed = 0
            mountedForm("rf-fail") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = "p1",
                        onSent = { onSent++ },
                        onFailed = { onFailed++ },
                        send = { _, _ -> throw ForbiddenException("secret-server-text") },
                    )
                }
                element().typeInto("Antwort", "Meine Antwort")
                element().buttonNamed("Antworten").click()
                awaitUntil("failed") { onFailed == 1 }
                delay(100)
                assertEquals(0, onSent)
                assertEquals(0, counterCalls)
                assertEquals("Meine Antwort", (element().controlOf("Antwort") as HTMLTextAreaElement).value)
            }
        }
}
