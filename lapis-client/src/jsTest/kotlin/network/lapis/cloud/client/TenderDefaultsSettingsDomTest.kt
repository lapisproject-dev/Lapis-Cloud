package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.201 -- the ADMIN card "Ausschreibungen": one select with the three visibilities, pre-set to the stored default, saved through its own RPC with
 * the chosen value; a refusal leaves the select where the server has it. (That only an ADMIN sees the card is the caller's `if` in
 * `MemberAdministrationScreen`; the server enforces it on the call.)
 */
class TenderDefaultsSettingsDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun settings(visibility: TenderBidVisibility) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto("o1", "Verein", null, null, null, null, null, null, null, null, tenderDefaultVisibility = visibility),
        )

    private fun HTMLElement.saveButton(): HTMLElement {
        val buttons = querySelectorAll("button")
        for (i in 0 until buttons.length) {
            val button = buttons.item(i) as HTMLElement
            if (button.textContent.orEmpty().trim() == "Speichern") return button
        }
        error("no save button")
    }

    @Test
    fun theSelectOffersTheThreeVisibilities_showsTheStoredOne_andSavesTheChosenOne(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val setRoute = routeOf { rpcService<IOrganizationSettingsService>().updateTenderDefaultVisibility(TenderBidVisibility.OPEN) }
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(TenderBidVisibility.HIDDEN_UNTIL_DEADLINE))
                    request.rpcRoute == setRoute -> rpcResult(request.json.id as Int, settings(TenderBidVisibility.OPEN))
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) { requests ->
                withMountedRoot("tender-defaults-save") { root, element ->
                    renderTenderDefaultsCard(root)
                    awaitUntil("the card is loaded") { element().querySelector("select") != null }
                    assertEquals("Ausschreibungen", element().querySelector("h2")?.textContent?.trim())
                    val select = element().querySelector("select") as HTMLSelectElement
                    val values =
                        (0 until select.options.length).map {
                            select.options
                                .item(it)!!
                                .asDynamic()
                                .value as String
                        }
                    assertEquals(listOf("HIDDEN_UNTIL_DEADLINE", "OPEN", "OWN_ONLY"), values)
                    assertEquals("HIDDEN_UNTIL_DEADLINE", select.value)
                    assertTrue(element().textContent.orEmpty().contains("nach dem Veröffentlichen nicht mehr änderbar"))
                    // V1.9.202: the third option is named for what it does, and the sentence under the select follows the choice
                    val labels =
                        (0 until select.options.length).map {
                            select.options
                                .item(it)!!
                                .textContent
                                .orEmpty()
                        }
                    assertEquals(listOf("Verdeckt bis zur Frist", "Offen", "Nur eigenes Angebot sichtbar"), labels)
                    assertTrue(element().textContent.orEmpty().contains("Bis zum Ende der Frist sieht niemand fremde Angebote."))

                    select.value = "OPEN"
                    select.dispatchEvent(Event("change"))
                    awaitUntil("the explanation follows the choice") {
                        element().textContent.orEmpty().contains("Alle Beteiligten sehen die Angebote der anderen sofort.")
                    }
                    element().saveButton().click()
                    awaitUntil("the new visibility is sent") { requests.any { it.isRpc && it.rpcRoute == setRoute } }
                    assertEquals("OPEN", requests.first { it.rpcRoute == setRoute }.rpcParam(0).toString())
                }
            }
        }

    @Test
    fun aRefusal_putsTheSelectBack(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val setRoute = routeOf { rpcService<IOrganizationSettingsService>().updateTenderDefaultVisibility(TenderBidVisibility.OPEN) }
            var setCalls = 0
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(TenderBidVisibility.OWN_ONLY))
                    request.rpcRoute == setRoute -> {
                        setCalls++
                        serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ForbiddenException")
                    }
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("tender-defaults-refused") { root, element ->
                    renderTenderDefaultsCard(root)
                    awaitUntil("the card is loaded") { element().querySelector("select") != null }
                    val select = element().querySelector("select") as HTMLSelectElement
                    assertEquals("OWN_ONLY", select.value)
                    select.value = "OPEN"
                    select.dispatchEvent(Event("change"))
                    assertNotNull(element().saveButton()).click()
                    awaitUntil("the refused call came back") { setCalls == 1 }
                    awaitUntil("the select is back where the server has it") { select.value == "OWN_ONLY" }
                }
            }
        }
}
