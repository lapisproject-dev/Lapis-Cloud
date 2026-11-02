package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.262 -- the ADMIN block "Standardansicht bei Aufzeichnung und Stream" (first block of "Aufzeichnung & Übertragung"): two radios that save
 * at once and spring back on a refusal, a status line for assistive technology, and the always-enabled button that counts first, asks before it
 * applies, and says plainly when there is nothing to change.
 */
class ConferenceBroadcastViewDefaultSettingsDomTest {
    private fun settings(default: ConferenceBroadcastView) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto(
                "o1",
                "Verein",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                conferenceBroadcastViewDefault = default,
            ),
        )

    private class Routes(
        val get: String,
        val set: String,
        val count: String,
        val apply: String,
    )

    private suspend fun routes() =
        Routes(
            get = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() },
            set = routeOf { rpcService<IOrganizationSettingsService>().setConferenceBroadcastViewDefault(ConferenceBroadcastView.RASTER) },
            count = routeOf { rpcService<IOrganizationSettingsService>().countRoomsDifferingFromBroadcastViewDefault() },
            apply = routeOf { rpcService<IOrganizationSettingsService>().applyBroadcastViewDefaultToExistingRooms() },
        )

    private fun HTMLElement.radio(view: ConferenceBroadcastView): HTMLInputElement {
        val label = conferenceBroadcastViewLabelForTest(view)
        val input =
            allOf("label")
                .first { it.textContent?.trim() == label }
                .let { assertNotNull(querySelector("#" + it.getAttribute("for"))) }
        return input as HTMLInputElement
    }

    private fun conferenceBroadcastViewLabelForTest(view: ConferenceBroadcastView) = conferenceBroadcastViewLabel(view)

    private val applyLabel = "Auf alle bestehenden Räume anwenden"

    @Test
    fun theBlockShowsTheStoredValue_theHint_andAnIconlessOutlineButton(): Promise<Unit> =
        formTest {
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.get -> rpcResult(request.json.id as Int, settings(ConferenceBroadcastView.SPEAKER))
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) {
                mountedForm("bvd-markup") { root, element ->
                    renderConferenceRecordingSettings(root)
                    awaitUntil("the block is loaded") { element().querySelector("fieldset legend") != null }
                    assertEquals(
                        "Standardansicht bei Aufzeichnung und Stream",
                        element().querySelector("fieldset legend")?.textContent?.trim(),
                    )
                    assertTrue(element().radio(ConferenceBroadcastView.SPEAKER).checked)
                    assertFalse(element().radio(ConferenceBroadcastView.RASTER).checked)
                    assertTrue(element().textContent.orEmpty().contains("Gilt für neue Räume und als Vorbelegung beim Stream-Start."))
                    val button = element().buttonNamed(applyLabel)
                    assertTrue(button.classList.contains("btn-outline-secondary"))
                    assertEquals(null, button.querySelector("i"), "no icon")
                    assertFalse(button.hasAttribute("disabled"), "always enabled")
                    val status = assertNotNull(element().querySelector(".lapis-broadcast-view-default-status"))
                    assertEquals("polite", status.getAttribute("aria-live"))
                }
            }
        }

    @Test
    fun aChange_sendsTheNewValue_andAnnouncesItInTheStatusLine(): Promise<Unit> =
        formTest {
            val r = routes()
            val sent = mutableListOf<String>()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.get -> rpcResult(request.json.id as Int, settings(ConferenceBroadcastView.RASTER))
                        request.rpcRoute == r.set -> {
                            val wanted = request.rpcParam(0) as String
                            sent += wanted
                            rpcResult(request.json.id as Int, settings(ConferenceBroadcastView.valueOf(wanted)))
                        }
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) {
                mountedForm("bvd-change") { root, element ->
                    renderConferenceRecordingSettings(root)
                    awaitUntil("the block is loaded") { element().querySelector("fieldset legend") != null }
                    element().radio(ConferenceBroadcastView.SPEAKER).click()
                    awaitUntil("the status line names the saved view") {
                        element().querySelector(".lapis-broadcast-view-default-status")?.textContent?.trim() ==
                            "Standardansicht „Sprecher groß“ gespeichert."
                    }
                    assertEquals(listOf("SPEAKER"), sent)
                    assertTrue(element().radio(ConferenceBroadcastView.SPEAKER).checked)
                }
            }
        }

    @Test
    fun aRefusal_putsTheRadioBack(): Promise<Unit> =
        formTest {
            val r = routes()
            var setCalls = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.get -> rpcResult(request.json.id as Int, settings(ConferenceBroadcastView.RASTER))
                        request.rpcRoute == r.set -> {
                            setCalls++
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ForbiddenException")
                        }
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) {
                mountedForm("bvd-refused") { root, element ->
                    renderConferenceRecordingSettings(root)
                    awaitUntil("the block is loaded") { element().querySelector("fieldset legend") != null }
                    element().radio(ConferenceBroadcastView.SPEAKER).click()
                    awaitUntil("the refused call came back and the radios are free again") {
                        setCalls == 1 && !element().radio(ConferenceBroadcastView.SPEAKER).hasAttribute("disabled")
                    }
                    assertTrue(element().radio(ConferenceBroadcastView.RASTER).checked, "the stored value is selected again")
                    assertFalse(element().radio(ConferenceBroadcastView.SPEAKER).checked)
                }
            }
        }

    private fun runApplyScenario(
        id: String,
        count: Int,
        block: suspend (element: () -> HTMLElement, applies: () -> Int) -> Unit,
    ): Promise<Unit> =
        formTest {
            val r = routes()
            var applies = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.get -> rpcResult(request.json.id as Int, settings(ConferenceBroadcastView.SPEAKER))
                        request.rpcRoute == r.count -> rpcResult(request.json.id as Int, count.toString())
                        request.rpcRoute == r.apply -> {
                            applies++
                            rpcResult(request.json.id as Int, count.toString())
                        }
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) {
                mountedForm(id) { root, element ->
                    renderConferenceRecordingSettings(root)
                    awaitUntil("the block is loaded") { element().querySelector("fieldset legend") != null }
                    element().buttonNamed(applyLabel).click()
                    awaitUntil("a dialog is shown") { document.querySelector(".modal.show") != null }
                    block(element) { applies }
                }
            }
        }

    @Test
    fun theButton_countsFirst_asksWithFocusOnCancel_andCancelSendsNothing(): Promise<Unit> =
        runApplyScenario("bvd-cancel", 3) { _, applies ->
            val text = lastOpenModal().textContent.orEmpty()
            assertTrue(text.contains("3 Räume werden auf „Sprecher groß“ umgestellt."), text)
            assertTrue(text.contains("Laufende Aufzeichnungen und Streams bleiben unverändert"), text)
            awaitUntil("the focus sits on Abbrechen") { document.activeElement?.textContent?.trim() == "Abbrechen" }
            lastOpenModal().buttonNamed("Abbrechen").click()
            delay(200)
            assertEquals(0, applies())
        }

    @Test
    fun confirming_applies_andTheStatusLineNamesTheCount(): Promise<Unit> =
        runApplyScenario("bvd-confirm", 3) { element, applies ->
            lastOpenModal().buttonNamed("Anwenden").click()
            awaitUntil("the status line names the count") {
                element().querySelector(".lapis-broadcast-view-default-status")?.textContent?.trim() ==
                    "3 Räume umgestellt."
            }
            assertEquals(1, applies())
        }

    @Test
    fun oneRoom_usesTheSingular(): Promise<Unit> =
        runApplyScenario("bvd-one", 1) { element, applies ->
            assertTrue(lastOpenModal().textContent.orEmpty().contains("1 Raum wird auf „Sprecher groß“ umgestellt."))
            lastOpenModal().buttonNamed("Anwenden").click()
            awaitUntil("singular status") {
                element().querySelector(".lapis-broadcast-view-default-status")?.textContent?.trim() ==
                    "1 Raum umgestellt."
            }
            assertEquals(1, applies())
        }

    @Test
    fun nothingToChange_saysSo_andOffersOnlyClose(): Promise<Unit> =
        runApplyScenario("bvd-zero", 0) { _, applies ->
            val modal = lastOpenModal()
            assertTrue(modal.textContent.orEmpty().contains("Alle bestehenden Räume nutzen bereits „Sprecher groß“."))
            assertTrue(modal.allOf("button").none { it.textContent?.trim() == "Anwenden" })
            assertNotNull(modal.allOf("button").firstOrNull { it.textContent?.trim() == "Schließen" })
            assertEquals(0, applies())
        }

    @Test
    fun aStalePage_namesTheStoredValueInTheQuestion_notItsOwnCopy(): Promise<Unit> =
        formTest {
            val r = routes()
            var gets = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.get -> {
                            gets++
                            val view = if (gets == 1) ConferenceBroadcastView.RASTER else ConferenceBroadcastView.SPEAKER
                            rpcResult(request.json.id as Int, settings(view))
                        }
                        request.rpcRoute == r.count -> rpcResult(request.json.id as Int, "2")
                        else -> rpcResult(request.json.id as Int, "null")
                    }
                },
            ) {
                mountedForm("bvd-stale") { root, element ->
                    renderConferenceRecordingSettings(root)
                    awaitUntil("the block is loaded") { element().querySelector("fieldset legend") != null }
                    element().buttonNamed(applyLabel).click()
                    awaitUntil("a dialog is shown") { document.querySelector(".modal.show") != null }
                    val text = lastOpenModal().textContent.orEmpty()
                    assertTrue(text.contains("2 Räume werden auf „Sprecher groß“ umgestellt."), text)
                }
            }
        }
}
