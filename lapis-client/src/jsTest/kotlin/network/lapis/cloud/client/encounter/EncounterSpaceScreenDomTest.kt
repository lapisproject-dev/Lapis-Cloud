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
import network.lapis.cloud.client.lastOpenModal
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.typeInto
import network.lapis.cloud.client.withFetchStub
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the room list: what everybody sees, what only BOARD/ADMIN get, untrusted text, and the guarded actions. */
class EncounterSpaceScreenDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "me",
            displayName = "Ich Selbst",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private class Routes(
        val list: String,
        val create: String,
        val archive: String,
        val update: String,
        val roles: String,
        val members: String,
    )

    private suspend fun routes() =
        Routes(
            list = routeOf { rpcService<IEncounterSpaceService>().listSpaces() },
            create = routeOf { rpcService<IEncounterSpaceService>().createSpace(EncounterSpaceInput(title = "x")) },
            archive = routeOf { rpcService<IEncounterSpaceService>().archiveSpace("s") },
            update = routeOf { rpcService<IEncounterSpaceService>().updateSpace("s", EncounterSpaceInput(title = "x")) },
            roles = routeOf { rpcService<IEncounterSpaceService>().listSpaceRoles("s") },
            members = routeOf { rpcService<IMemberService>().listMembers() },
        )

    private val openSpace =
        testSpace(id = "open-1", title = "Sonntagsgottesdienst", open = true, presentCount = 2, pulpitNames = listOf("Pfarrer Paul"))
    private val closedSpace = testSpace(id = "closed-1", title = "Abendandacht", open = false, canModerate = false)

    private suspend fun withScreen(
        role: AccountRole,
        spaces: List<EncounterSpaceDto>,
        block: suspend (HTMLElement, List<network.lapis.cloud.client.RecordedRequest>, Routes) -> Unit,
    ) {
        AppState.setSession(session(role))
        val r = routes()
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == r.list -> request.answerWith(jsonOf(ListSerializer(EncounterSpaceDto.serializer()), spaces))
                    request.rpcRoute == r.roles ->
                        request.answerWith(
                            jsonOf(
                                ListSerializer(EncounterSpaceRoleDto.serializer()),
                                listOf(EncounterSpaceRoleDto("m1", "Pfarrer Paul", EncounterSpaceRole.PULPIT)),
                            ),
                        )
                    request.rpcRoute == r.members ->
                        request.answerWith(
                            jsonOf(
                                ListSerializer(MemberSummaryDto.serializer()),
                                listOf(MemberSummaryDto("m1", "Pfarrer Paul"), MemberSummaryDto("m2", "Anna Keller")),
                            ),
                        )
                    else -> request.answerWith("null")
                }
            },
        ) { requests ->
            mountedForm("encounter-space-screen-$role") { root, element ->
                renderEncounterSpaceScreen(root)
                awaitUntil("the rows are shown") { element().querySelectorAll(".lapis-encounter-space-row").length == spaces.size }
                block(element(), requests, r)
            }
        }
    }

    @Test
    fun everybodySeesTheRooms_withStatusAsTextAndPresenceAsANumber_butNoManagementControl(): Promise<Unit> =
        formTest {
            withScreen(AccountRole.MEMBER, listOf(openSpace, closedSpace)) { element, _, _ ->
                assertEquals(2, element.allOf(".lapis-encounter-space-row").size)
                val text = element.textContent.orEmpty()
                assertTrue(text.contains("Sonntagsgottesdienst") && text.contains("Abendandacht"))
                assertTrue(text.contains("Geöffnet") && text.contains("Geschlossen"), "the state is a word, not only a colour")
                assertTrue(text.contains("2 anwesend"), "a number")
                assertTrue(text.contains("Kanzel: Pfarrer Paul"))
                val links = element.allOf("a").filter { it.textContent.orEmpty().contains("Zum Raum") }
                assertEquals(listOf("#/begegnung/open-1", "#/begegnung/closed-1"), links.map { it.getAttribute("href") })
                val buttons = element.allOf("button").map { it.textContent.orEmpty().trim() }
                listOf("Bearbeiten", "Ämter", "Archivieren", "Neuer Begegnungsraum").forEach { assertFalse(it in buttons, "$it: $buttons") }
                assertNull(element.querySelector("[aria-controls]"), "no create form button")
            }
        }

    @Test
    fun aRoomTitleIsText_neverMarkup(): Promise<Unit> =
        formTest {
            val nasty = testSpace(id = "n1", title = "<img src=x onerror=alert(1)>")
            withScreen(AccountRole.MEMBER, listOf(nasty)) { element, _, _ ->
                assertNull(element.querySelector("img"))
                assertTrue(element.textContent.orEmpty().contains("<img src=x onerror=alert(1)>"))
            }
        }

    @Test
    fun boardSeesOneCreateButtonInTheTitleRow_andTheFormIsCollapsed(): Promise<Unit> =
        formTest {
            withScreen(AccountRole.BOARD, listOf(openSpace)) { element, _, _ ->
                val buttons = element.allOf(".lapis-page-header .lapis-page-action button")
                assertEquals(listOf("Neuer Begegnungsraum"), buttons.map { it.textContent?.trim() })
                val host = assertNotNull(element.querySelector("[id='lapis-create-encounter-space']"))
                assertEquals(0, host.childElementCount, "closed: the host is empty")
            }
        }

    @Test
    fun creatingARoom_sendsTrimmedValues_andAnEmptyNoticeAsNull(): Promise<Unit> =
        formTest {
            withScreen(AccountRole.BOARD, listOf(openSpace)) { element, requests, r ->
                element.buttonNamed("Neuer Begegnungsraum").click()
                awaitUntil("the form is open") { element.querySelector("[id='lapis-create-encounter-space'] .lapis-form") != null }
                element.typeInto("Titel", "   Neuer Raum  ")
                element.profileRadio("CHURCH_SERVICE").click()
                element.buttonNamed("Begegnungsraum anlegen").click()
                awaitUntil("the create call was sent") { requests.any { it.isRpc && it.rpcRoute == r.create } }
                val input = requests.first { it.isRpc && it.rpcRoute == r.create }.rpcParam(0)
                assertEquals("Neuer Raum", input.title.toString())
                assertEquals("CHURCH_SERVICE", input.profile.toString())
                assertTrue(
                    input.guestPolicy == undefined || input.guestPolicy.toString() == "MEMBERS_ONLY",
                    "the default policy: members only",
                )
                assertTrue(input.closedNotice == null || input.closedNotice == undefined, "an empty notice is not sent as text")
            }
        }

    @Test
    fun anOpenRoomCannotBeArchived_aClosedOneAsksFirst(): Promise<Unit> =
        formTest {
            val closedManaged = testSpace(id = "closed-1", title = "Abendandacht", open = false, canModerate = true)
            withScreen(AccountRole.ADMIN, listOf(openSpace, closedManaged)) { element, requests, r ->
                val rows = element.allOf(".lapis-encounter-space-row")
                val openRow = rows.first { it.textContent.orEmpty().contains("Sonntagsgottesdienst") }
                val closedRow = rows.first { it.textContent.orEmpty().contains("Abendandacht") }
                assertTrue(openRow.buttonNamed("Archivieren").hasAttribute("disabled"), "the doors have to be closed first")
                assertTrue(openRow.textContent.orEmpty().contains("Erst Türen schließen, dann archivieren."))
                closedRow.buttonNamed("Archivieren").click()
                val modal = lastOpenModal()
                assertTrue(modal.textContent.orEmpty().contains("Den Raum archivieren?"))
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == r.archive }, "nothing before the confirmation")
                modal.buttonNamed("Archivieren").click()
                awaitUntil("the archive call was sent") { requests.count { it.isRpc && it.rpcRoute == r.archive } == 1 }
                assertEquals("closed-1", requests.first { it.isRpc && it.rpcRoute == r.archive }.rpcParam(0).toString())
            }
        }

    @Test
    fun theOfficeEditor_usesAPersonSearchSelect_andListsTheCurrentOffices(): Promise<Unit> =
        formTest {
            withScreen(AccountRole.BOARD, listOf(openSpace)) { element, _, _ ->
                element.buttonNamed("Ämter").click()
                awaitUntil("the editor is loaded") { element.querySelector("input[role=combobox]") != null }
                val text = element.textContent.orEmpty()
                assertTrue(text.contains("Pfarrer Paul"), "the current office holder")
                assertTrue(text.contains("Kanzel (spricht)"), "the role in words")
                assertNull(element.querySelector("select option[value='m2']"), "a person is never a plain select")
                val names = element.allOf("button").map { it.textContent.orEmpty().trim() }
                assertTrue("Amt vergeben" in names && "Ämter speichern" in names, names.toString())
            }
        }

    private fun HTMLElement.profileRadio(profile: String): HTMLInputElement =
        allOf("input[type=radio]").first { it.getAttribute("value") == profile } as HTMLInputElement

    private fun HTMLElement.reactionBox(label: String): HTMLInputElement {
        val labelElement = allOf("label").first { it.textContent.orEmpty().trim() == label }
        return assertNotNull(document.getElementById(labelElement.getAttribute("for").orEmpty()) as? HTMLInputElement, "checkbox of $label")
    }

    @Test
    fun theCreateForm_hasNoPreselectedRoomType_andAMissingChoiceBlocksSubmittingWithAnErrorAtTheField(): Promise<Unit> =
        formTest {
            withScreen(AccountRole.BOARD, listOf(openSpace)) { element, requests, r ->
                element.buttonNamed("Neuer Begegnungsraum").click()
                awaitUntil("the form is open") { element.querySelector("[id='lapis-create-encounter-space'] .lapis-form") != null }
                val radios = element.allOf("input[type=radio]").map { it as HTMLInputElement }
                assertEquals(setOf("CHURCH_SERVICE", "ASSEMBLY"), radios.map { it.getAttribute("value") }.toSet())
                assertTrue(radios.none { it.checked }, "no room type is preselected")
                element.typeInto("Titel", "Ohne Raumart")
                element.buttonNamed("Begegnungsraum anlegen").click()
                awaitUntil("the field shows its error") { element.textContent.orEmpty().contains("Bitte wählen Sie eine Raumart.") }
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == r.create }, "nothing is sent without a room type")
            }
        }

    @Test
    fun theHandReactionIsAlwaysOnAndLocked_andChoosingAnAssemblyOffersApplause(): Promise<Unit> =
        formTest {
            withScreen(AccountRole.BOARD, listOf(openSpace)) { element, requests, r ->
                element.buttonNamed("Neuer Begegnungsraum").click()
                awaitUntil("the form is open") { element.querySelector("[id='lapis-create-encounter-space'] .lapis-form") != null }
                val hand = element.reactionBox("Hand heben")
                assertTrue(hand.checked && hand.disabled, "the hand is always on and cannot be switched off")
                element.profileRadio("ASSEMBLY").click()
                awaitUntil("the assembly defaults are set") { element.reactionBox("Applaus").checked }
                assertFalse(element.reactionBox("Amen").checked)
                element.typeInto("Titel", "Mitgliederversammlung")
                element.reactionBox("Herz").click()
                element.buttonNamed("Begegnungsraum anlegen").click()
                awaitUntil("the create call was sent") { requests.any { it.isRpc && it.rpcRoute == r.create } }
                val input = requests.first { it.isRpc && it.rpcRoute == r.create }.rpcParam(0)
                assertEquals("ASSEMBLY", input.profile.toString())
                assertEquals("[\"HAND\",\"APPLAUSE\",\"HEART\"]", js("JSON.stringify")(input.reactions).toString())
            }
        }

    @Test
    fun editingAnOpenRoom_locksTheRoomTypeAndTheReactions_aClosedOneKeepsThemEditable(): Promise<Unit> =
        formTest {
            val closedManaged = testSpace(id = "closed-1", title = "Abendandacht", open = false, canModerate = true)
            withScreen(AccountRole.BOARD, listOf(openSpace, closedManaged)) { element, _, _ ->
                val rows = element.allOf(".lapis-encounter-space-row")
                rows.first { it.textContent.orEmpty().contains("Sonntagsgottesdienst") }.buttonNamed("Bearbeiten").click()
                awaitUntil("the edit form of the open room is shown") { element.querySelector("input[type=radio]") != null }
                assertTrue(element.allOf("input[type=radio]").all { it.hasAttribute("disabled") }, "room type locked while open")
                assertTrue(element.reactionBox("Amen").disabled, "reactions locked while open")
                assertTrue(element.textContent.orEmpty().contains("nur bei geschlossenem Raum ändern"))
                assertTrue(element.profileRadio("CHURCH_SERVICE").checked, "the current room type is selected")
            }
        }

    @Test
    fun editingAClosedRoom_keepsANonDefaultReactionSet_whenOnlyTheTitleChanges(): Promise<Unit> =
        formTest {
            val closedManaged =
                testSpace(
                    id = "closed-1",
                    title = "Abendandacht",
                    open = false,
                    canModerate = true,
                    profile = EncounterProfile.CHURCH_SERVICE,
                    reactions = listOf(EncounterReactionOption.HAND, EncounterReactionOption.AMEN, EncounterReactionOption.HEART),
                )
            withScreen(AccountRole.BOARD, listOf(closedManaged)) { element, requests, r ->
                element.buttonNamed("Bearbeiten").click()
                awaitUntil("the edit form is shown") { element.querySelector("input[type=radio]") != null }
                assertTrue(element.reactionBox("Herz").checked, "the stored reaction is shown as set")
                element.typeInto("Titel", "Abendandacht (korrigiert)")
                element.buttonNamed("Speichern").click()
                awaitUntil("the update was sent") { requests.any { it.isRpc && it.rpcRoute == r.update } }
                val sent = requests.first { it.isRpc && it.rpcRoute == r.update }.rpcParam(1)
                assertEquals("[\"HAND\",\"AMEN\",\"HEART\"]", js("JSON.stringify")(sent.reactions).toString())
            }
        }

    @Test
    fun theList_namesTheRoomTypeAsText(): Promise<Unit> =
        formTest {
            val assembly =
                testSpace(id = "v1", title = "Mitgliederversammlung", profile = EncounterProfile.ASSEMBLY, pulpitNames = listOf("Pia"))
            withScreen(AccountRole.MEMBER, listOf(openSpace, assembly)) { element, _, _ ->
                val rows = element.allOf(".lapis-encounter-space-row")
                assertTrue(
                    rows[0].textContent.orEmpty().contains(
                        "Gottesdienst",
                    ) &&
                        rows[0].textContent.orEmpty().contains("Kanzel: Pfarrer Paul"),
                )
                val second = rows[1].textContent.orEmpty()
                assertTrue(second.contains("Versammlung") && second.contains("Podium: Pia"), second)
                assertFalse(second.contains("Kanzel"), second)
            }
        }
}
