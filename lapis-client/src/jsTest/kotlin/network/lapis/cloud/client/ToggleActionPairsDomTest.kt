package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.DunningComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuctionService
import network.lapis.cloud.shared.rpc.IDunningService
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import network.lapis.cloud.shared.rpc.IReceivableDunningService
import network.lapis.cloud.shared.rpc.ISepaService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.180 -- the five on/off screens (auction, dunning, receivable dunning, online payment, SEPA) show only the action that applies to the
 * current state. The other button is not in the DOM. Switching re-renders at once and moves the focus to the status text.
 */
class ToggleActionPairsDomTest {
    private class Case(
        val name: String,
        val enableLabel: String,
        val disableLabel: String,
        val render: (Root) -> Unit,
        val settingsJson: (Boolean) -> String,
        val getRoute: String,
        val enableRoute: String,
        val disableRoute: String,
        val disclaimerRoute: String?,
        val enableConfirm: String,
    )

    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val ackLabel = "Ich bestätige, den aktuellen Text gelesen zu haben"
    private val disclaimerJson = """{"version":"v1","text":"Hinweis","sha256":"abc"}"""

    private suspend fun cases(): List<Case> =
        listOf(
            Case(
                name = "auction",
                enableLabel = "Auktion aktivieren …",
                disableLabel = "Auktion deaktivieren",
                render = { renderAuctionScreen(it) },
                settingsJson = { e ->
                    """{"auctionEnabled":$e,"auctionMaxValueLtr":null,"lastAcknowledgedByDisplayName":null,""" +
                        """"lastAcknowledgedAt":null,"lastDisclaimerVersion":null}"""
                },
                getRoute = routeOf { rpcService<IAuctionService>().getAuctionSettings() },
                enableRoute = routeOf { rpcService<IAuctionService>().enableAuction(AuctionComplianceAcknowledgmentInput("v", "h")) },
                disableRoute = routeOf { rpcService<IAuctionService>().disableAuction() },
                disclaimerRoute = routeOf { rpcService<IAuctionService>().getAuctionComplianceDisclaimer() },
                enableConfirm = ackLabel,
            ),
            Case(
                name = "dunning",
                enableLabel = "Mahnwesen aktivieren …",
                disableLabel = "Mahnwesen deaktivieren",
                render = { renderDunningSettingsScreen(it) },
                settingsJson = { e ->
                    """{"dunningEnabled":$e,"pollerEnabled":false,"postalDispatchEnabled":false,"postalMailEnabled":false,""" +
                        """"activeLevelCount":1,"lastDisclaimerVersion":null,"lastAcknowledgedAt":null}"""
                },
                getRoute = routeOf { rpcService<IDunningService>().getDunningSettings() },
                enableRoute = routeOf { rpcService<IDunningService>().enableDunning(DunningComplianceAcknowledgmentInput("v", "h")) },
                disableRoute = routeOf { rpcService<IDunningService>().disableDunning() },
                disclaimerRoute = routeOf { rpcService<IDunningService>().getDunningComplianceDisclaimer() },
                enableConfirm = ackLabel,
            ),
            Case(
                name = "receivable dunning",
                enableLabel = "Forderungs-Mahnwesen aktivieren",
                disableLabel = "Forderungs-Mahnwesen deaktivieren",
                render = { renderReceivableDunningSettingsScreen(it) },
                settingsJson = { e -> """{"receivableDunningEnabled":$e,"pollerEnabled":true,"activeLevelCount":1}""" },
                getRoute = routeOf { rpcService<IReceivableDunningService>().getReceivableDunningSettings() },
                enableRoute = routeOf { rpcService<IReceivableDunningService>().enableReceivableDunning() },
                disableRoute = routeOf { rpcService<IReceivableDunningService>().disableReceivableDunning() },
                disclaimerRoute = null,
                enableConfirm = "Aktivieren",
            ),
            Case(
                name = "online payment",
                enableLabel = "Online-Zahlung aktivieren …",
                disableLabel = "Online-Zahlung deaktivieren",
                render = { renderPaymentGatewaySettingsScreen(it) },
                settingsJson = { e ->
                    """{"paymentGatewayEnabled":$e,"paymentGatewayProvider":${if (e) "\"STRIPE\"" else "null"},""" +
                        """"lastAcknowledgedByDisplayName":null,"lastAcknowledgedAt":null,"lastDisclaimerVersion":null}"""
                },
                getRoute = routeOf { rpcService<IPaymentGatewayService>().getPaymentGatewaySettings() },
                enableRoute =
                    routeOf {
                        rpcService<IPaymentGatewayService>().enablePaymentGateway(
                            PaymentProvider.STRIPE,
                            PaymentGatewayComplianceAcknowledgmentInput("v", "h"),
                        )
                    },
                disableRoute = routeOf { rpcService<IPaymentGatewayService>().disablePaymentGateway() },
                disclaimerRoute = routeOf { rpcService<IPaymentGatewayService>().getPaymentGatewayComplianceDisclaimer() },
                enableConfirm = ackLabel,
            ),
            Case(
                name = "sepa",
                enableLabel = "SEPA-Lastschrift aktivieren …",
                disableLabel = "SEPA-Lastschrift deaktivieren",
                render = { renderSepaSettingsScreen(it) },
                settingsJson = { e ->
                    """{"sepaDebitEnabled":$e,"lastAcknowledgedByDisplayName":null,"lastAcknowledgedAt":null,"lastDisclaimerVersion":null}"""
                },
                getRoute = routeOf { rpcService<ISepaService>().getSepaSettings() },
                enableRoute = routeOf { rpcService<ISepaService>().enableSepaDebit(SepaComplianceAcknowledgmentInput("v", "h")) },
                disableRoute = routeOf { rpcService<ISepaService>().disableSepaDebit() },
                disclaimerRoute = routeOf { rpcService<ISepaService>().getSepaComplianceDisclaimer() },
                enableConfirm = ackLabel,
            ),
        )

    /** State holder of one stub: [enabled] flips when the enable/disable route is called. */
    private class Backend(
        val case: Case,
        var enabled: Boolean,
        val settingsDelayMs: Int = 0,
        val settingsFails: Boolean = false,
        val disableFails: Boolean = false,
        val pspStatusJson: String = "null",
        val statusRoute: String? = null,
    )

    private fun respond(backend: Backend): (RecordedRequest) -> StubResponse =
        { request ->
            if (!request.isRpc) {
                StubResponse()
            } else {
                val case = backend.case
                when (request.rpcRoute) {
                    case.getRoute ->
                        if (backend.settingsFails) {
                            StubResponse(networkError = true)
                        } else {
                            val answer = request.answerWith(case.settingsJson(backend.enabled))
                            StubResponse(answer.status, answer.text, delayMs = backend.settingsDelayMs)
                        }
                    case.enableRoute -> {
                        backend.enabled = true
                        request.answerWith(case.settingsJson(true))
                    }
                    case.disableRoute ->
                        if (backend.disableFails) {
                            StubResponse(networkError = true)
                        } else {
                            backend.enabled = false
                            request.answerWith(case.settingsJson(false))
                        }
                    case.disclaimerRoute -> request.answerWith(disclaimerJson)
                    backend.statusRoute -> request.answerWith(backend.pspStatusJson)
                    else -> request.answerWith("[]")
                }
            }
        }

    private fun modalButton(text: String): HTMLElement {
        val buttons = document.querySelectorAll(".modal.show .modal-footer button")
        return assertNotNull(
            (0 until buttons.length).map { buttons.item(it) as HTMLElement }.firstOrNull { it.textContent?.trim() == text },
            "no modal button '$text'",
        )
    }

    private suspend fun awaitModal() = awaitUntil("a modal is shown") { document.querySelectorAll(".modal.show").length > 0 }

    private fun HTMLElement.hasButton(text: String) = allOf("button").any { it.textContent?.trim() == text }

    private fun HTMLElement.statusText() = querySelector(".toggle-status")?.textContent?.trim()

    private suspend fun forEachCase(
        id: String,
        enabled: Boolean,
        configure: (Case) -> Backend = { Backend(it, enabled) },
        check: suspend (Case, Backend, HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        AppState.setSession(admin)
        for (case in cases()) {
            val backend = configure(case)
            withFetchStub(respond(backend)) { calls ->
                mountedForm("$id-${case.name.replace(' ', '-')}") { root, element ->
                    case.render(root)
                    check(case, backend, element(), calls)
                }
            }
        }
    }

    @Test
    fun enabled_showsExactlyTheDeactivateButton(): Promise<Unit> =
        formTest {
            forEachCase("tp-enabled", enabled = true) { case, _, el, _ ->
                awaitUntil("${case.name}: the deactivate button") { el.hasButton(case.disableLabel) }
                assertFalse(el.hasButton(case.enableLabel), "${case.name}: no activate button")
                assertFalse(el.textContent.orEmpty().contains(case.enableLabel), "${case.name}: no activate text in the DOM")
                assertEquals("Status: Aktiviert", el.statusText(), case.name)
                val status = assertNotNull(el.querySelector(".toggle-status") as? HTMLElement)
                assertEquals("status", status.getAttribute("role"), case.name)
                assertEquals("-1", status.getAttribute("tabindex"), case.name)
                val button = el.buttonNamed(case.disableLabel)
                assertTrue(button.querySelector("i.fa-ban") != null, "${case.name}: the deactivate button keeps its REVOKE icon")
                assertTrue(button.classList.contains("btn-outline-danger"), case.name)
            }
        }

    @Test
    fun disabled_showsExactlyTheActivateButton(): Promise<Unit> =
        formTest {
            forEachCase("tp-disabled", enabled = false) { case, _, el, _ ->
                awaitUntil("${case.name}: the activate button") { el.hasButton(case.enableLabel) }
                assertFalse(el.hasButton(case.disableLabel), "${case.name}: no deactivate button")
                assertFalse(el.textContent.orEmpty().contains(case.disableLabel), "${case.name}: no deactivate text in the DOM")
                assertEquals("Status: Deaktiviert", el.statusText(), case.name)
                val button = el.buttonNamed(case.enableLabel)
                assertTrue(button.classList.contains("btn-primary"), case.name)
                assertTrue(button.querySelector("i") == null, "${case.name}: the activate button is a domain verb without an icon")
            }
        }

    @Test
    fun loading_showsNoButtonAtAll(): Promise<Unit> =
        formTest {
            forEachCase("tp-loading", enabled = true, configure = { Backend(it, true, settingsDelayMs = 3000) }) { case, _, el, _ ->
                awaitUntil("${case.name}: the loading line") { el.statusText() == "Wird geladen …" }
                assertFalse(el.hasButton(case.enableLabel), case.name)
                assertFalse(el.hasButton(case.disableLabel), case.name)
            }
        }

    @Test
    fun loadFailure_showsAnErrorLineAndNoButton(): Promise<Unit> =
        formTest {
            forEachCase("tp-failed", enabled = true, configure = { Backend(it, true, settingsFails = true) }) { case, _, el, _ ->
                awaitUntil("${case.name}: the error line") { el.statusText() == "Einstellungen konnten nicht geladen werden." }
                assertFalse(el.hasButton(case.enableLabel), case.name)
                assertFalse(el.hasButton(case.disableLabel), case.name)
                assertTrue(el.querySelector(".toggle-status.text-danger") != null, case.name)
            }
        }

    @Test
    fun deactivate_swapsTheButtonWithoutReload_andFocusesTheStatus(): Promise<Unit> =
        formTest {
            forEachCase("tp-switch-off", enabled = true) { case, backend, el, calls ->
                awaitUntil("${case.name}: the deactivate button") { el.hasButton(case.disableLabel) }
                el.buttonNamed(case.disableLabel).click()
                awaitModal()
                modalButton("Deaktivieren").click()
                awaitUntil("${case.name}: the activate button appears") { el.hasButton(case.enableLabel) }
                assertFalse(el.hasButton(case.disableLabel), "${case.name}: the deactivate button is gone")
                assertEquals("Status: Deaktiviert", el.statusText(), case.name)
                assertEquals(1, calls.toRoute(case.disableRoute).size, case.name)
                assertFalse(backend.enabled, case.name)
                awaitUntil("${case.name}: the status text has the focus") {
                    (document.activeElement as? HTMLElement)?.classList?.contains("toggle-status") == true
                }
            }
        }

    @Test
    fun activate_swapsTheButtonWithoutReload_andFocusesTheStatus(): Promise<Unit> =
        formTest {
            forEachCase("tp-switch-on", enabled = false, configure = { Backend(it, false) }) { case, backend, el, calls ->
                awaitUntil("${case.name}: the activate button") { el.hasButton(case.enableLabel) }
                el.buttonNamed(case.enableLabel).click()
                awaitModal()
                modalButton(case.enableConfirm).click()
                awaitUntil("${case.name}: the deactivate button appears") { el.hasButton(case.disableLabel) }
                assertFalse(el.hasButton(case.enableLabel), "${case.name}: the activate button is gone")
                assertEquals("Status: Aktiviert", el.statusText(), case.name)
                assertEquals(1, calls.toRoute(case.enableRoute).size, case.name)
                assertTrue(backend.enabled, case.name)
                awaitUntil("${case.name}: the status text has the focus") {
                    (document.activeElement as? HTMLElement)?.classList?.contains("toggle-status") == true
                }
            }
        }

    @Test
    fun cancellingTheDialog_changesNothing(): Promise<Unit> =
        formTest {
            forEachCase("tp-cancel", enabled = true) { case, _, el, calls ->
                awaitUntil("${case.name}: the deactivate button") { el.hasButton(case.disableLabel) }
                val button = el.buttonNamed(case.disableLabel)
                button.click()
                awaitModal()
                modalButton("Abbrechen").click()
                awaitUntil("${case.name}: the dialog is closed") { document.querySelectorAll(".modal.show").length == 0 }
                assertEquals(0, calls.toRoute(case.disableRoute).size, case.name)
                assertTrue(el.hasButton(case.disableLabel), case.name)
                assertFalse(el.hasButton(case.enableLabel), case.name)
                assertFalse(el.buttonNamed(case.disableLabel).asDynamic().disabled as Boolean, "${case.name}: the button is usable")
            }
        }

    @Test
    fun aFailedSwitch_keepsTheButtonAndMakesItUsableAgain(): Promise<Unit> =
        formTest {
            forEachCase(
                "tp-fail-switch",
                enabled = true,
                configure = { Backend(it, true, disableFails = true) },
            ) { case, backend, el, calls ->
                awaitUntil("${case.name}: the deactivate button") { el.hasButton(case.disableLabel) }
                el.buttonNamed(case.disableLabel).click()
                awaitModal()
                modalButton("Deaktivieren").click()
                awaitUntil("${case.name}: the request was made") { calls.toRoute(case.disableRoute).isNotEmpty() }
                awaitUntil("${case.name}: the button is usable again") {
                    el.hasButton(case.disableLabel) && !(el.buttonNamed(case.disableLabel).asDynamic().disabled as Boolean)
                }
                assertFalse(el.hasButton(case.enableLabel), case.name)
                assertTrue(backend.enabled, case.name)
                assertEquals("Status: Aktiviert", el.statusText(), case.name)
            }
        }

    @Test
    fun onlinePayment_warnsAboutAnUnconfiguredProviderOnlyWhileDisabled(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val case = cases().first { it.name == "online payment" }
            val statusRoute = routeOf { rpcService<IPaymentGatewayService>().getPspConfigStatus() }
            val unconfigured =
                """{"configuredProvider":null,"secretKeyConfigured":false,"webhookSecretConfigured":false,""" +
                    """"webhookUrl":"https://x.example/w",""" +
                    """"paypalClientIdConfigured":false,"paypalClientSecretConfigured":false,"paypalWebhookIdConfigured":false,""" +
                    """"paypalWebhookUrl":"https://x.example/p","publicBaseUrl":"https://x.example",""" +
                    """"paymentBankAccountConfigured":true,"contributionIncomeAccountConfigured":true,""" +
                    """"donationIncomeAccountConfigured":true,"paymentFeeAccountConfigured":true}"""
            val backend = Backend(case, enabled = false, pspStatusJson = unconfigured, statusRoute = statusRoute)
            withFetchStub(respond(backend)) {
                mountedForm("tp-psp-warning") { root, element ->
                    case.render(root)
                    val el = element()
                    awaitUntil("the activate button") { el.hasButton(case.enableLabel) }
                    awaitUntil(
                        "the warning while disabled",
                    ) { el.textContent.orEmpty().contains("serverseitig noch nicht vollständig konfiguriert") }
                    assertTrue(el.buttonNamed(case.enableLabel).asDynamic().disabled as Boolean, "the activate button is greyed out")
                    // the provider picker belongs to the DISABLED state only
                    assertTrue(el.querySelector("select") != null)
                }
            }
            val enabledBackend = Backend(case, enabled = true, pspStatusJson = unconfigured, statusRoute = statusRoute)
            withFetchStub(respond(enabledBackend)) {
                mountedForm("tp-psp-warning-on") { root, element ->
                    case.render(root)
                    val el = element()
                    awaitUntil("the deactivate button") { el.hasButton(case.disableLabel) }
                    assertFalse(
                        el.textContent.orEmpty().contains("serverseitig noch nicht vollständig konfiguriert"),
                        "no warning while enabled",
                    )
                    assertTrue(el.querySelector("select") == null, "no provider picker while enabled")
                }
            }
        }
}
