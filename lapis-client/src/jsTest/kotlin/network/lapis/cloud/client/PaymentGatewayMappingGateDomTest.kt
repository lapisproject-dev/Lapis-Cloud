package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.FinanceSetupStep
import network.lapis.cloud.shared.domain.FinanceSetupStepState
import network.lapis.cloud.shared.domain.FinanceSetupStepStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.206: the payment page no longer hosts the mapping; it shows a status line and refuses to open the disclaimer while the mapping is incomplete. */
class PaymentGatewayMappingGateDomTest {
    private fun status(complete: Boolean) =
        FinanceSetupStatusDto(
            steps =
                FinanceSetupStep.entries.map {
                    FinanceSetupStepStatus(
                        it,
                        if (!complete && it == FinanceSetupStep.DONATION_INCOME) FinanceSetupStepState.OPEN else FinanceSetupStepState.DONE,
                    )
                },
            activeAccountCount = 3,
            mappingComplete = complete,
        )

    private val settingsJson =
        """{"paymentGatewayEnabled":false,"paymentGatewayProvider":null,"lastAcknowledgedByDisplayName":null,""" +
            """"lastAcknowledgedAt":null,"lastDisclaimerVersion":null}"""

    private val pspJson =
        """{"secretKeyConfigured":true,"webhookSecretConfigured":true,"webhookUrl":"https://x/hook","paypalClientIdConfigured":true,""" +
            """"paypalClientSecretConfigured":true,"paypalWebhookIdConfigured":true,"paypalWebhookUrl":"https://x/pp",""" +
            """"paymentBankAccountConfigured":false,"contributionIncomeAccountConfigured":false,""" +
            """"donationIncomeAccountConfigured":false,""" +
            """"paymentFeeAccountConfigured":false}"""

    private suspend fun withPage(
        id: String,
        complete: Boolean,
        block: suspend (screen: HTMLElement, calls: List<RecordedRequest>, disclaimerRoute: String) -> Unit,
    ) {
        AppState.setSession(
            SessionInfoDto(
                memberId = "member-1",
                displayName = "Dana Keller",
                role = AccountRole.ADMIN,
                status = MemberStatus.ACTIVE,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            ),
        )
        val getRoute = routeOf { rpcService<IPaymentGatewayService>().getPaymentGatewaySettings() }
        val pspRoute = routeOf { rpcService<IPaymentGatewayService>().getPspConfigStatus() }
        val disclaimerRoute = routeOf { rpcService<IPaymentGatewayService>().getPaymentGatewayComplianceDisclaimer() }
        val statusRoute = routeOf { rpcService<IAccountingService>().getFinanceSetupStatus() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> request.answerWith(settingsJson)
                    request.rpcRoute == pspRoute -> request.answerWith(pspJson)
                    request.rpcRoute == statusRoute -> request.answerWith(jsonOf(FinanceSetupStatusDto.serializer(), status(complete)))
                    request.rpcRoute == disclaimerRoute ->
                        request.answerWith("""{"version":"v","text":"Text","sha256":"h"}""")
                    else -> request.answerWith("[]")
                }
            },
        ) { calls ->
            mountedForm(id) { root, element ->
                renderPaymentGatewaySettingsScreen(root)
                awaitUntil("the enable row") { element().buttonOrNull("Online-Zahlung aktivieren …") != null }
                block(element(), calls, disclaimerRoute)
            }
        }
    }

    private fun HTMLElement.buttonOrNull(text: String) = allOf("button").firstOrNull { it.textContent?.trim() == text }

    @Test
    fun theMappingSelectsAreGone_andTheStatusLineLinksToTheSetup(): Promise<Unit> =
        formTest {
            withPage("pg-mapping-line", complete = false) { screen, _, _ ->
                awaitUntil("the status line") { screen.textContent.orEmpty().contains("Kontenzuordnung: unvollständig") }
                assertEquals(0, screen.allOf("select").size - screen.allOf(".lapis-setting-row select").size, "no mapping select")
                assertFalse(screen.textContent.orEmpty().contains("Bankkonto zugeordnet"), "the four badges are gone")
                val link = screen.allOf(".lapis-finance-status-line a").single()
                assertTrue(link.getAttribute("href").orEmpty().contains("tab=einrichtung&step=donation"))
                assertTrue(screen.textContent.orEmpty().contains("Stripe"), "the provider diagnosis stays")
            }
        }

    @Test
    fun completeMapping_showsTheGreenLine(): Promise<Unit> =
        formTest {
            withPage("pg-mapping-complete", complete = true) { screen, _, _ ->
                awaitUntil("the status line") { screen.textContent.orEmpty().contains("Kontenzuordnung: vollständig") }
            }
        }

    @Test
    fun enableWithAnIncompleteMapping_opensNoDisclaimer_butTheMessageAndALink(): Promise<Unit> =
        formTest {
            withPage("pg-mapping-gate", complete = false) { screen, calls, disclaimerRoute ->
                screen.buttonNamed("Online-Zahlung aktivieren …").click()
                awaitUntil("the message") {
                    screen.textContent.orEmpty().contains(
                        "Online-Zahlung kann erst aktiviert werden, wenn die Kontenzuordnung vollständig ist.",
                    )
                }
                assertTrue(calls.toRoute(disclaimerRoute).isEmpty(), "the disclaimer is never fetched")
                assertTrue(kotlinx.browser.document.querySelector(".modal.show") == null)
                assertEquals(
                    "payment-mapping-gate-message",
                    screen.buttonNamed("Online-Zahlung aktivieren …").getAttribute("aria-describedby"),
                )
            }
        }
}
