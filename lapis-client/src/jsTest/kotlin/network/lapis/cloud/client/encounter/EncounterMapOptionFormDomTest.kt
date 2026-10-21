package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.client.AppState
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.typeInto
import network.lapis.cloud.client.withFetchStub
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.166 -- the room form's map option: off by default, assembly only, frozen (ON) while the room is open, and sent as `null` when locked. */
class EncounterMapOptionFormDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val label = "Karte (Positionen der Mitglieder)"

    private suspend fun withScreen(
        spaces: List<EncounterSpaceDto>,
        block: suspend (HTMLElement, List<network.lapis.cloud.client.RecordedRequest>, String, String) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        AppState.setSession(
            SessionInfoDto(
                memberId = "me",
                displayName = "Ich Selbst",
                role = AccountRole.BOARD,
                status = MemberStatus.ACTIVE,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            ),
        )
        val list = routeOf { rpcService<IEncounterSpaceService>().listSpaces() }
        val create = routeOf { rpcService<IEncounterSpaceService>().createSpace(EncounterSpaceInput(title = "x")) }
        val update = routeOf { rpcService<IEncounterSpaceService>().updateSpace("s", EncounterSpaceInput(title = "x")) }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(EncounterSpaceDto.serializer()), spaces))
                    else -> request.answerWith("null")
                }
            },
        ) { requests ->
            mountedForm("encounter-map-option-form") { root, element ->
                renderEncounterSpaceScreen(root)
                awaitUntil("the rows are shown") { element().querySelectorAll(".lapis-encounter-space-row").length == spaces.size }
                block(element(), requests, create, update)
            }
        }
    }

    private fun HTMLElement.mapBox(): HTMLInputElement {
        val labelElement = allOf("label").first { it.textContent.orEmpty().trim() == label }
        return assertNotNull(document.getElementById(labelElement.getAttribute("for").orEmpty()) as? HTMLInputElement)
    }

    private fun HTMLElement.profileRadio(profile: String): HTMLInputElement =
        allOf("input[type=radio]").first { it.getAttribute("value") == profile } as HTMLInputElement

    private suspend fun openCreateForm(element: HTMLElement) {
        element.buttonNamed("Neuer Begegnungsraum").click()
        awaitUntil("the form is open") { element.querySelector("[id='lapis-create-encounter-space'] .lapis-form") != null }
    }

    @Test
    fun theOption_isOffByDefault_disabledUntilTheAssemblyIsChosen_andSwitchedOffAgainByTheChurch(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _, _ ->
                openCreateForm(element)
                val box = element.mapBox()
                assertFalse(box.checked)
                assertTrue(box.disabled, "no map before the assembly is chosen")
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("enabled") { !element.mapBox().disabled }
                element.mapBox().click()
                awaitUntil("checked") { element.mapBox().checked }
                element.profileRadio("CHURCH_SERVICE").click()
                awaitUntil("off and locked again") { !element.mapBox().checked && element.mapBox().disabled }
            }
        }

    @Test
    fun theChoice_isSentWithTheInput(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, requests, create, _ ->
                openCreateForm(element)
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("enabled") { !element.mapBox().disabled }
                element.mapBox().click()
                awaitUntil("checked") { element.mapBox().checked }
                element.typeInto("Titel", "Versammlung")
                element.buttonNamed("Begegnungsraum anlegen").click()
                awaitUntil("sent") { requests.any { it.isRpc && it.rpcRoute == create } }
                assertEquals(true, requests.first { it.isRpc && it.rpcRoute == create }.rpcParam(0).mapEnabled)
            }
        }

    @Test
    fun anOpenRoomWithoutAMap_cannotSwitchItOn_andSaysWhy_aLockedBoxSendsNull(): Promise<Unit> =
        formTest {
            val open = testSpace(id = "open-1", title = "Treffen", open = true, profile = EncounterProfile.ASSEMBLY, canModerate = true)
            withScreen(listOf(open)) { element, requests, _, update ->
                element.buttonNamed("Bearbeiten").click()
                awaitUntil("the edit form") { element.allOf("label").any { it.textContent.orEmpty().trim() == label } }
                assertTrue(element.mapBox().disabled)
                assertTrue(element.textContent.orEmpty().contains("Nur bei geschlossenem Raum einschaltbar"))
                element.buttonNamed("Speichern").click()
                awaitUntil("sent") { requests.any { it.isRpc && it.rpcRoute == update } }
                assertEquals(null, requests.first { it.isRpc && it.rpcRoute == update }.rpcParam(1).mapEnabled ?: null)
            }
        }

    @Test
    fun anOpenRoomWithAMap_canStillSwitchItOff(): Promise<Unit> =
        formTest {
            val open =
                testSpace(
                    id = "open-2",
                    title = "Treffen",
                    open = true,
                    profile = EncounterProfile.ASSEMBLY,
                    canModerate = true,
                    mapEnabled = true,
                )
            withScreen(listOf(open)) { element, requests, _, update ->
                element.buttonNamed("Bearbeiten").click()
                awaitUntil("the edit form") { element.allOf("label").any { it.textContent.orEmpty().trim() == label } }
                assertTrue(element.mapBox().checked)
                assertFalse(element.mapBox().disabled, "OFF is always allowed")
                element.mapBox().click()
                awaitUntil("unchecked") { !element.mapBox().checked }
                element.buttonNamed("Speichern").click()
                awaitUntil("sent") { requests.any { it.isRpc && it.rpcRoute == update } }
                assertEquals(false, requests.first { it.isRpc && it.rpcRoute == update }.rpcParam(1).mapEnabled)
            }
        }
}
