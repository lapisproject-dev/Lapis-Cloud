package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.190 -- the layout of the "Zahlungs-Konfiguration" enable row: wide shell, the provider select / action button in ONE wrapping row,
 * the "not configured" warning as the NEXT SIBLING of that row (not squeezed into it), `aria-describedby` only while the warning applies.
 */
class PaymentGatewaySettingsLayoutDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val settingsJson =
        """{"paymentGatewayEnabled":false,"paymentGatewayProvider":null,"lastAcknowledgedByDisplayName":null,""" +
            """"lastAcknowledgedAt":null,"lastDisclaimerVersion":null}"""

    private fun pspJson(configured: Boolean) =
        """{"configuredProvider":null,"secretKeyConfigured":$configured,"webhookSecretConfigured":$configured,""" +
            """"webhookUrl":"https://x.example/w",""" +
            """"paypalClientIdConfigured":false,"paypalClientSecretConfigured":false,"paypalWebhookIdConfigured":false,""" +
            """"paypalWebhookUrl":"https://x.example/p","publicBaseUrl":"https://x.example",""" +
            """"paymentBankAccountConfigured":true,"contributionIncomeAccountConfigured":true,""" +
            """"donationIncomeAccountConfigured":true,"paymentFeeAccountConfigured":true}"""

    private fun layoutTest(
        id: String,
        stripeConfigured: Boolean,
        check: suspend (HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            AppState.setSession(admin)
            val getRoute = routeOf { rpcService<IPaymentGatewayService>().getPaymentGatewaySettings() }
            val statusRoute = routeOf { rpcService<IPaymentGatewayService>().getPspConfigStatus() }
            withFetchStub(
                { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == getRoute -> request.answerWith(settingsJson)
                        request.rpcRoute == statusRoute -> request.answerWith(pspJson(stripeConfigured))
                        else -> request.answerWith("[]")
                    }
                },
            ) {
                mountedForm(id) { root, element ->
                    renderPaymentGatewaySettingsScreen(root)
                    awaitUntil("the enable row") { element().querySelector(".lapis-setting-row button") != null }
                    check(element())
                }
            }
        }

    private fun HTMLElement.row() = assertNotNull(querySelector(".lapis-setting-row") as? HTMLElement)

    @Test
    fun shellIsWide(): Promise<Unit> =
        layoutTest("pg-layout-wide", stripeConfigured = true) { el ->
            assertNotNull(el.querySelector(".lapis-page--wide"), "the wide shell")
        }

    @Test
    fun warning_isTheNextSiblingOfTheRow_notADescendant(): Promise<Unit> =
        layoutTest("pg-layout-warning", stripeConfigured = false) { el ->
            awaitUntil("the warning text") { el.textContent.orEmpty().contains("serverseitig noch nicht vollständig konfiguriert") }
            val row = el.row()
            val warning = assertNotNull(el.querySelector("#payment-provider-warning") as? HTMLElement)
            assertNull(row.querySelector("#payment-provider-warning"), "the warning is not inside the row")
            assertEquals(warning, row.nextElementSibling, "DOM order: row, then warning")
            val button = el.buttonNamed("Online-Zahlung aktivieren …")
            assertEquals("payment-provider-warning", button.getAttribute("aria-describedby"))
        }

    @Test
    fun noWarning_noAriaDescribedby_whenConfigured(): Promise<Unit> =
        layoutTest("pg-layout-configured", stripeConfigured = true) { el ->
            val button = el.buttonNamed("Online-Zahlung aktivieren …")
            assertNull(button.getAttribute("aria-describedby"))
        }

    @Test
    fun buttonDoesNotWrap_andSelectHasRoom(): Promise<Unit> =
        layoutTest("pg-layout-measure", stripeConfigured = true) { el ->
            val button = el.buttonNamed("Online-Zahlung aktivieren …")
            assertTrue(button.classList.contains("lapis-setting-row__action"))
            assertEquals("nowrap", window.getComputedStyle(button).getPropertyValue("white-space"))
            val select = assertNotNull(el.querySelector(".lapis-setting-row select") as? HTMLElement)
            val box = (select.closest(".lapis-setting-row__select") as? HTMLElement ?: select).getBoundingClientRect()
            assertTrue(box.width >= 223.5, "select width ${box.width}")
            assertEquals("Online-Zahlung aktivieren …", button.textContent?.trim())
        }
}
