package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MailingSendEstimateDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMailingService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.81 -- "Senden" first asks the server how many recipients the send reaches and how long it takes under the hourly budget,
 * and the confirmation states exactly those figures (the count, the hourly bulk budget, the duration, and -- when it does not fit into
 * one budget hour -- that the send is spread over several hours). Nothing is sent before the person confirms.
 */
class MailingSendConfirmDomTest {
    private fun session() =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Vorstand",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2031, 12, 1, 12, 0),
        )

    private fun draft() =
        MailingMessageDto(
            id = "msg-1",
            mailingListId = "list-1",
            subject = "Einladung zum Sommerfest",
            bodyText = "Text",
            bodyHtml = null,
            sentBy = "board-1",
            sentAt = null,
            status = MailingMessageStatus.DRAFT,
        )

    private fun scenario(
        id: String,
        estimate: MailingSendEstimateDto,
        check: suspend (modalText: String, sendCalls: () -> Int, estimateCalls: () -> Int) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val estimateRoute = routeOf { rpcService<IMailingService>().mailingSendEstimate("x") }
            val sendRoute = routeOf { rpcService<IMailingService>().sendMailingMessage("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == estimateRoute ->
                            request.answerWith(jsonOf(MailingSendEstimateDto.serializer(), estimate))
                        request.rpcRoute == sendRoute ->
                            request.answerWith(jsonOf(MailingMessageDto.serializer(), draft().copy(status = MailingMessageStatus.QUEUED)))
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm(id) { root, element ->
                    renderMailingMessageRow(root.vPanel(), draft(), "Mitgliederliste", onChanged = {})
                    element().buttonNamed("Senden").click()
                    awaitUntil("the estimate was requested") { calls.count { it.isRpc && it.rpcRoute == estimateRoute } == 1 }
                    awaitUntil("the confirmation is open") { document_modalText().contains("Nachricht senden") }
                    check(
                        document_modalText(),
                        { calls.count { it.isRpc && it.rpcRoute == sendRoute } },
                        { calls.count { it.isRpc && it.rpcRoute == estimateRoute } },
                    )
                }
            }
        }

    private fun document_modalText(): String = runCatching { lastOpenModal().textContent.orEmpty() }.getOrDefault("")

    @Test
    fun withABudget_theDialogNamesCountHourlyBudgetAndDuration_andNothingIsSentYet(): Promise<Unit> =
        scenario(
            "msc-budget",
            MailingSendEstimateDto(recipientCount = 120, bulkBudgetPerHour = 200, estimatedSeconds = 2160, spansMultipleHours = false),
        ) { text, sendCalls, estimateCalls ->
            assertTrue(text.contains("120 Empfänger · Stundenbudget für Rundschreiben: 200 pro Stunde · Dauer ca. 40 Min."), text)
            assertFalse(text.contains("mehrere Stunden"), text)
            assertTrue(text.contains("Einladung zum Sommerfest"), "the subject stays in the confirmation")
            assertEquals(1, estimateCalls())
            assertEquals(0, sendCalls(), "nothing is sent before the person confirms")
            assertFalse(text.contains("###"), "no tr() marker on screen")
        }

    @Test
    fun whenItDoesNotFitIntoOneHour_theDialogSaysTheSendIsSpread(): Promise<Unit> =
        scenario(
            "msc-spread",
            MailingSendEstimateDto(recipientCount = 450, bulkBudgetPerHour = 200, estimatedSeconds = 8100, spansMultipleHours = true),
        ) { text, _, _ ->
            assertTrue(text.contains("450 Empfänger · Stundenbudget für Rundschreiben: 200 pro Stunde · Dauer ca. 2 Std. 15 Min."), text)
            assertTrue(text.contains("Der Versand wird auf mehrere Stunden verteilt."), text)
        }

    @Test
    fun withoutABudget_theDialogShowsOnlyTheNumber(): Promise<Unit> =
        scenario(
            "msc-nobudget",
            MailingSendEstimateDto(recipientCount = 42, bulkBudgetPerHour = null, estimatedSeconds = 11, spansMultipleHours = false),
        ) { text, _, _ ->
            assertTrue(text.contains("42 Empfänger"), text)
            assertFalse(text.contains("Stundenbudget"), text)
            assertFalse(text.contains("Dauer"), text)
        }

    @Test
    fun confirming_sendsExactlyOnce(): Promise<Unit> =
        scenario("msc-confirm", MailingSendEstimateDto(3, null, 1, false)) { _, sendCalls, _ ->
            lastOpenModal().buttonNamed("Senden").click()
            awaitUntil("the send RPC was sent") { sendCalls() == 1 }
            kotlinx.coroutines.delay(100)
            assertEquals(1, sendCalls())
        }
}
