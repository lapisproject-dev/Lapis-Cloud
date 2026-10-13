package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.124 -- the ADMIN card "Videokonferenz": one `role="switch"` button that shows the stored instance default, saves a flip at once
 * through its own RPC with the NEW value, follows the server's answer, and springs back when the server refuses. (That only an ADMIN sees
 * the card is the caller's `if (callerRole == AccountRole.ADMIN)` in `MemberAdministrationScreen`; the server enforces it on the call.)
 */
class ConferenceDefaultsSettingsDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun settings(default: Boolean) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto("o1", "Verein", null, null, null, null, null, null, null, null, noiseSuppressionDefault = default),
        )

    private fun HTMLElement.switchButton() = assertNotNull(querySelector("button[role=switch]") as? HTMLElement)

    @Test
    fun theSwitchShowsTheStoredDefault_andAFlipSendsTheNewValue(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val setRoute = routeOf { rpcService<IOrganizationSettingsService>().updateNoiseSuppressionDefault(true) }
            val sent = mutableListOf<Boolean>()
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(false))
                    request.rpcRoute == setRoute -> {
                        val wanted = request.rpcParam(0) as Boolean
                        sent += wanted
                        rpcResult(request.json.id as Int, settings(wanted))
                    }
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("conference-defaults-flip") { root, element ->
                    renderConferenceDefaultsCard(root)
                    awaitUntil("the card is loaded") { element().querySelector("button[role=switch]") != null }
                    val button = element().switchButton()
                    assertEquals("false", button.getAttribute("aria-checked"))
                    assertEquals("Videokonferenz", element().querySelector("h2")?.textContent?.trim())
                    val label = assertNotNull(element().querySelector("#" + button.getAttribute("aria-labelledby")))
                    assertEquals("Rauschunterdrückung vorgeben", label.textContent?.trim())
                    assertTrue(
                        element()
                            .querySelector("#" + button.getAttribute("aria-describedby"))
                            ?.textContent
                            .orEmpty()
                            .isNotBlank(),
                    )
                    button.click()
                    awaitUntil("on") { button.getAttribute("aria-checked") == "true" && !button.hasAttribute("disabled") }
                    button.click()
                    awaitUntil("off again") { button.getAttribute("aria-checked") == "false" && !button.hasAttribute("disabled") }
                    assertEquals(listOf(true, false), sent)
                }
            }
        }

    @Test
    fun aStoredOn_isShownOn_andTheFirstClickSwitchesOff(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val setRoute = routeOf { rpcService<IOrganizationSettingsService>().updateNoiseSuppressionDefault(false) }
            val sent = mutableListOf<Boolean>()
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(true))
                    request.rpcRoute == setRoute -> {
                        val wanted = request.rpcParam(0) as Boolean
                        sent += wanted
                        rpcResult(request.json.id as Int, settings(wanted))
                    }
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("conference-defaults-on") { root, element ->
                    renderConferenceDefaultsCard(root)
                    awaitUntil("the card is loaded") { element().querySelector("button[role=switch]") != null }
                    val button = element().switchButton()
                    assertEquals("true", button.getAttribute("aria-checked"))
                    button.click()
                    awaitUntil("off") { button.getAttribute("aria-checked") == "false" && !button.hasAttribute("disabled") }
                    assertEquals(listOf(false), sent)
                }
            }
        }

    @Test
    fun aRefusal_putsTheSwitchBack(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val setRoute = routeOf { rpcService<IOrganizationSettingsService>().updateNoiseSuppressionDefault(true) }
            var setCalls = 0
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(false))
                    request.rpcRoute == setRoute -> {
                        setCalls++
                        serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ForbiddenException")
                    }
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("conference-defaults-refused") { root, element ->
                    renderConferenceDefaultsCard(root)
                    awaitUntil("the card is loaded") { element().querySelector("button[role=switch]") != null }
                    val button = element().switchButton()
                    button.click()
                    awaitUntil("the refused call came back") { setCalls == 1 && !button.hasAttribute("disabled") }
                    assertEquals("false", button.getAttribute("aria-checked"), "the switch is where the server has it")
                }
            }
        }
}
