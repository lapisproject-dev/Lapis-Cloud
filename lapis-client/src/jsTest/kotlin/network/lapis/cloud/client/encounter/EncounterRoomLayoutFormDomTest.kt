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
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterTablesConfig
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

/**
 * V1.9.152 (stage 2c) -- the template choice of the room form, in a real mounted root with the real stylesheet: a fieldset with a legend and four native
 * radios in tiles of at least 140 x 112 px, the tiles that do not fit the chosen room kind are disabled WITH a reason in text, a kind change resets a
 * template that no longer fits and says so in a `role="status"` line, a template built around tables switches the tables on and pre-fills them, an open
 * room freezes the choice. Arrow-key movement inside the group is the browser's own (one `name`, native radios, no script): the test pins that the
 * markup is such a group.
 */
class EncounterRoomLayoutFormDomTest {
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

    private fun session() =
        SessionInfoDto(
            memberId = "me",
            displayName = "Ich Selbst",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private suspend fun withScreen(
        spaces: List<EncounterSpaceDto>,
        block: suspend (HTMLElement, List<network.lapis.cloud.client.RecordedRequest>, String) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        AppState.setSession(session())
        val list = routeOf { rpcService<IEncounterSpaceService>().listSpaces() }
        val create = routeOf { rpcService<IEncounterSpaceService>().createSpace(EncounterSpaceInput(title = "x")) }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(EncounterSpaceDto.serializer()), spaces))
                    else -> request.answerWith("null")
                }
            },
        ) { requests ->
            mountedForm("encounter-layout-form") { root, element ->
                renderEncounterSpaceScreen(root)
                awaitUntil("the rows are shown") { element().querySelectorAll(".lapis-encounter-space-row").length == spaces.size }
                block(element(), requests, create)
            }
        }
    }

    private val layoutValues = listOf("NAVE", "FOYER", "HALL", "SMALL_GROUP")

    private fun HTMLElement.layoutRadios(): List<HTMLInputElement> =
        allOf("input[type=radio]").map { it as HTMLInputElement }.filter { it.getAttribute("value") in layoutValues }

    private fun HTMLElement.layoutRadio(layout: String): HTMLInputElement = layoutRadios().first { it.getAttribute("value") == layout }

    private fun HTMLElement.profileRadio(profile: String): HTMLInputElement =
        allOf("input[type=radio]").first { it.getAttribute("value") == profile } as HTMLInputElement

    private fun HTMLElement.checkboxOf(label: String): HTMLInputElement {
        val labelElement = allOf("label").first { it.textContent.orEmpty().trim() == label }
        return assertNotNull(document.getElementById(labelElement.getAttribute("for").orEmpty()) as? HTMLInputElement, "checkbox of $label")
    }

    private fun HTMLElement.textInputOf(label: String): HTMLInputElement {
        val labelElement =
            allOf("label").first {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith(label)
            }
        return assertNotNull(document.getElementById(labelElement.getAttribute("for").orEmpty()) as? HTMLInputElement, "input of $label")
    }

    private fun HTMLElement.status(): HTMLElement =
        assertNotNull(querySelector(".lapis-encounter-layout-status") as? HTMLElement, "status line")

    private fun HTMLElement.tileOf(layout: String): HTMLElement = layoutRadio(layout).parentElement as HTMLElement

    private suspend fun openCreateForm(element: HTMLElement) {
        element.buttonNamed("Neuer Begegnungsraum").click()
        awaitUntil("the form is open") { element.querySelector("[id='lapis-create-encounter-space'] .lapis-form") != null }
    }

    @Test
    fun theChoice_isAFieldsetWithALegend_andFourNativeRadiosOfOneGroup_inTilesOfAtLeast140x112(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _ ->
                openCreateForm(element)
                val fieldset = assertNotNull(element.querySelector("fieldset.lapis-encounter-layout-fieldset") as? HTMLElement)
                assertEquals("Raumvorlage", fieldset.querySelector("legend")!!.textContent!!.trim())
                val radios = element.layoutRadios()
                assertEquals(layoutValues, radios.map { it.getAttribute("value") })
                assertEquals(
                    1,
                    radios.map { it.getAttribute("name") }.toSet().size,
                    "one name = one native radio group (arrow keys come from the browser)",
                )
                radios.forEach {
                    assertEquals("radio", it.type)
                    assertTrue(it.getAttribute("tabindex") == null, "no script-managed tab stop: the browser owns the group")
                    val name = document.getElementById(it.getAttribute("aria-labelledby").orEmpty())
                    assertTrue(!name?.textContent.isNullOrBlank(), "every radio is named by its tile name")
                }
                // the tile is the visible face of the radio: 140 x 112 at least
                layoutValues.forEach { value ->
                    val box = element.tileOf(value).getBoundingClientRect()
                    assertTrue(box.width >= 139.5 && box.height >= 111.5, "$value: ${box.width} x ${box.height}")
                }
            }
        }

    @Test
    fun beforeTheKindIsChosen_nothingIsChoosable_andEveryTileSaysWhy(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _ ->
                openCreateForm(element)
                assertTrue(element.layoutRadios().all { it.hasAttribute("disabled") })
                assertTrue(element.layoutRadios().none { it.checked }, "no preselection")
                assertEquals(4, element.querySelectorAll(".lapis-encounter-layout-reason").length)
                assertTrue(
                    element
                        .allOf(
                            ".lapis-encounter-layout-reason",
                        ).all { it.textContent.orEmpty().contains("Wählen Sie zuerst die Raumart.") },
                )
            }
        }

    @Test
    fun aFreshChurchRoomGetsTheNaveQuietly_andAnAssemblyReplacesItWithTheHallAndSaysSo(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _ ->
                openCreateForm(element)
                element.profileRadio("CHURCH_SERVICE").click()
                awaitUntil("the nave is chosen") { element.layoutRadio("NAVE").checked }
                assertEquals(
                    "",
                    element
                        .status()
                        .textContent
                        .orEmpty()
                        .trim(),
                    "the default for a fresh room is not announced",
                )
                // a church service offers the nave and the hall, not the foyer and the small-group room
                assertTrue(
                    element.layoutRadio("FOYER").hasAttribute("disabled") && element.layoutRadio("SMALL_GROUP").hasAttribute("disabled"),
                )
                assertTrue(
                    element
                        .tileOf("FOYER")
                        .textContent
                        .orEmpty()
                        .contains("Nur in der Raumart Versammlung."),
                )
                assertFalse(element.layoutRadio("NAVE").hasAttribute("disabled"))
                assertFalse(element.layoutRadio("HALL").hasAttribute("disabled"))
                // an assembly does not fit the nave: it is replaced and the replacement is said
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("the hall is chosen") { element.layoutRadio("HALL").checked }
                assertTrue(element.layoutRadio("NAVE").hasAttribute("disabled"))
                assertTrue(
                    element
                        .tileOf("NAVE")
                        .textContent
                        .orEmpty()
                        .contains("Nur in der Raumart Gottesdienst."),
                )
                listOf("FOYER", "HALL", "SMALL_GROUP").forEach { assertFalse(element.layoutRadio(it).hasAttribute("disabled"), it) }
                assertTrue(
                    element
                        .status()
                        .textContent
                        .orEmpty()
                        .contains("Die Raumvorlage wurde auf Saal gesetzt, passend zur Raumart."),
                )
                // the hall fits both kinds and stays when the kind changes back
                element.profileRadio("CHURCH_SERVICE").click()
                awaitUntil("the nave is offered again") { !element.layoutRadio("NAVE").hasAttribute("disabled") }
                assertTrue(element.layoutRadio("HALL").checked, "a template that still fits is kept")
            }
        }

    @Test
    fun anAssemblyFreshlyChosen_getsTheHallQuietly(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _ ->
                openCreateForm(element)
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("the hall is chosen") { element.layoutRadio("HALL").checked }
                assertEquals(
                    "",
                    element
                        .status()
                        .textContent
                        .orEmpty()
                        .trim(),
                )
            }
        }

    @Test
    fun choosingTheFoyer_switchesTheTablesOnAndPrefillsThem_saysSoInAStatusLine_andSendsTheTemplate(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, requests, create ->
                openCreateForm(element)
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("the hall is chosen") { element.layoutRadio("HALL").checked }
                assertFalse(element.checkboxOf("Tische mit eigener Audiogruppe").checked)
                element.layoutRadio("FOYER").click()
                awaitUntil("the tables are on") { element.checkboxOf("Tische mit eigener Audiogruppe").checked }
                assertEquals("6", element.textInputOf("Anzahl der Tische").value)
                assertEquals("4", element.textInputOf("Plätze je Tisch").value)
                assertEquals("status", element.status().getAttribute("role"))
                assertTrue(
                    element
                        .status()
                        .textContent
                        .orEmpty()
                        .contains("Die Tische wurden eingeschaltet: 6 Tische mit je 4 Plätzen."),
                )
                element.typeInto("Titel", "Café-Treff")
                element.buttonNamed("Begegnungsraum anlegen").click()
                awaitUntil("the create call was sent") { requests.any { it.isRpc && it.rpcRoute == create } }
                val input = requests.first { it.isRpc && it.rpcRoute == create }.rpcParam(0)
                assertEquals("ASSEMBLY", input.profile.toString())
                assertEquals("FOYER", input.roomLayout.toString())
                assertEquals(true, input.tables.enabled)
                assertEquals(6, input.tables.count)
                assertEquals(4, input.tables.seats)
            }
        }

    @Test
    fun theSmallGroupRoom_prefillsThreeTablesOfEight(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _ ->
                openCreateForm(element)
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("the hall is chosen") { element.layoutRadio("HALL").checked }
                element.layoutRadio("SMALL_GROUP").click()
                awaitUntil("the tables are on") { element.checkboxOf("Tische mit eigener Audiogruppe").checked }
                assertEquals("3", element.textInputOf("Anzahl der Tische").value)
                assertEquals("8", element.textInputOf("Plätze je Tisch").value)
            }
        }

    @Test
    fun changingTheKindAwayFromAssembly_resetsAFoyer_switchesTheTablesOff_andSaysSo(): Promise<Unit> =
        formTest {
            withScreen(emptyList()) { element, _, _ ->
                openCreateForm(element)
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("the hall is chosen") { element.layoutRadio("HALL").checked }
                element.layoutRadio("FOYER").click()
                awaitUntil("the tables are on") { element.checkboxOf("Tische mit eigener Audiogruppe").checked }
                element.profileRadio("CHURCH_SERVICE").click()
                awaitUntil("the nave is chosen") { element.layoutRadio("NAVE").checked }
                assertFalse(element.checkboxOf("Tische mit eigener Audiogruppe").checked, "tables belong to the assembly only")
                assertTrue(
                    element
                        .status()
                        .textContent
                        .orEmpty()
                        .contains("Die Raumvorlage wurde auf Kirchenschiff gesetzt, passend zur Raumart."),
                )
                assertEquals("status", element.status().getAttribute("role"))
            }
        }

    @Test
    fun anOpenRoom_freezesTheChoice_andShowsTheStoredTemplate(): Promise<Unit> =
        formTest {
            val open =
                testSpace(
                    id = "open-1",
                    title = "Café",
                    open = true,
                    profile = EncounterProfile.ASSEMBLY,
                    roomLayout = EncounterRoomLayout.FOYER,
                    canModerate = true,
                    tables = EncounterTablesConfig(enabled = true, count = 6, seats = 4),
                )
            withScreen(listOf(open)) { element, _, _ ->
                element.buttonNamed("Bearbeiten").click()
                awaitUntil("the edit form is shown") { element.layoutRadios().isNotEmpty() }
                assertTrue(element.layoutRadios().all { it.hasAttribute("disabled") }, "frozen while open")
                assertTrue(element.layoutRadio("FOYER").checked, "the stored template is shown")
                assertTrue(element.textContent.orEmpty().contains("Die Raumvorlage lässt sich nur bei geschlossenem Raum ändern."))
            }
        }

    @Test
    fun aClosedRoom_keepsItsTemplate_whenOnlyTheTitleChanges(): Promise<Unit> =
        formTest {
            val closed =
                testSpace(
                    id = "closed-1",
                    title = "Café",
                    open = false,
                    profile = EncounterProfile.ASSEMBLY,
                    roomLayout = EncounterRoomLayout.SMALL_GROUP,
                    canModerate = true,
                )
            withScreen(listOf(closed)) { element, requests, _ ->
                val update = routeOf { rpcService<IEncounterSpaceService>().updateSpace("s", EncounterSpaceInput(title = "x")) }
                element.buttonNamed("Bearbeiten").click()
                awaitUntil("the edit form is shown") { element.layoutRadios().isNotEmpty() }
                assertTrue(element.layoutRadio("SMALL_GROUP").checked)
                assertFalse(element.layoutRadio("SMALL_GROUP").hasAttribute("disabled"))
                element.typeInto("Titel", "Café (neu)")
                element.buttonNamed("Speichern").click()
                awaitUntil("the update call was sent") { requests.any { it.isRpc && it.rpcRoute == update } }
                assertEquals(
                    "SMALL_GROUP",
                    requests
                        .first { it.isRpc && it.rpcRoute == update }
                        .rpcParam(1)
                        .roomLayout
                        .toString(),
                )
            }
        }
}
