package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.40 -- the four pilot screens of rule R36B (Committees, Board membership, Meetings, Motions): after the load the create form is
 * collapsed, its button sits in the title row, a press opens exactly that form, and a save folds it back and reloads the list. The
 * request bodies are pinned by the existing body tests (`FormSubmitBodyPart2/3DomTest`), which call the unchanged `render...Form`
 * functions directly.
 */
class CollapsibleCreateFormScreensDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun committee() =
        CommitteeDto(
            id = "committee-1",
            name = "Vorstand",
            type = CommitteeType.EXECUTIVE_BOARD,
            description = "",
            active = true,
            quorumPercent = 50,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    private fun committeesJson(list: List<CommitteeDto>) = jsonOf(ListSerializer(CommitteeDto.serializer()), list)

    private fun HTMLElement.actionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.hasField(label: String): Boolean =
        allOf("label").any {
            it.textContent
                .orEmpty()
                .trim()
                .removeSuffix("*")
                .trim()
                .startsWith(label)
        }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    // ---- Committees ------------------------------------------------------------------------------------------------------

    @Test
    fun committees_theFormIsCollapsed_theButtonIsInTheTitleRow_andASaveFoldsItBackAndReloadsTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val list = routeOf { rpcService<IGovernanceService>().listCommittees(true) }
            val create = routeOf { rpcService<IGovernanceService>().createCommittee(CommitteeInput("n", CommitteeType.OTHER, "d")) }
            val created = committee().copy(id = "committee-2", name = "Finanzausschuss")
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(committeesJson(listOf(committee())))
                        request.rpcRoute == create -> request.answerWith(jsonOf(CommitteeDto.serializer(), created))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-committees") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the committee list is shown") { element().shows("Vorstand") }
                    val screen = element()
                    assertEquals(listOf("Neues Gremium"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hasField("Quorum in %"), "collapsed: no form fields on the page")
                    assertFalse(screen.shows("Neues Gremium anlegen"), "the old always-visible section title is gone")

                    openCreateForm(screen, "lapis-create-committee")
                    assertTrue(screen.hasField("Quorum in %"))
                    screen.typeInto("Name", "Finanzausschuss")
                    screen.buttonNamed("Gremium anlegen").click()
                    awaitUntil("createCommittee") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { screen.querySelector("[id='lapis-create-committee'] .lapis-form") == null }
                    awaitUntil("the list was reloaded") { calls.toRoute(list).size == 2 }
                    assertEquals("false", createFormButton(screen, "lapis-create-committee").getAttribute("aria-expanded"))
                }
            }
        }

    @Test
    fun committees_aMemberGetsNoButtonAndNoEmptyActionArea(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val list = routeOf { rpcService<IGovernanceService>().listCommittees(true) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith("[]") else StubResponse()
                },
            ) { _ ->
                mountedForm("r36b-committees-member") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the empty state is shown") { element().shows("Noch keine Gremien vorhanden.") }
                    assertTrue(element().actionButtons().isEmpty())
                    assertTrue(element().allOf(".lapis-page-action").isEmpty(), "no empty action area")
                }
            }
        }

    @Test
    fun committees_theEmptyStateNamesTheButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val list = routeOf { rpcService<IGovernanceService>().listCommittees(true) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) request.answerWith("[]") else StubResponse()
                },
            ) { _ ->
                mountedForm("r36b-committees-empty") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the empty state names the button") {
                        element().shows("Noch keine Gremien. Mit \"Neues Gremium\" legen Sie eines an.")
                    }
                }
            }
        }

    // ---- Board membership ------------------------------------------------------------------------------------------------

    @Test
    fun board_theAppointmentFormIsCollapsedBehindNeueBestellung(): Promise<Unit> =
        formTest {
            val members = routeOf { rpcService<IMemberService>().listMembers() }
            val board = routeOf { rpcService<IBoardMembershipService>().listCurrentBoard() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == members || request.rpcRoute == board -> request.answerWith("[]")
                        else -> request.answerWith("null")
                    }
                },
            ) { _ ->
                mountedForm("r36b-board") { root, element ->
                    renderBoardMembershipScreen(root)
                    val screen = element()
                    awaitUntil("the header button is there") { screen.actionButtons().isNotEmpty() }
                    assertEquals(listOf("Neue Bestellung"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hasField("Rolle"), "collapsed: no appointment fields")
                    assertTrue(screen.shows("Manuelle Eintragung"), "the section title and its caption stay")
                    openCreateForm(screen, "lapis-create-board-appointment")
                    assertTrue(screen.hasField("Rolle"))
                    // Opening does not lose the data-less picker preselection as a "change": Escape closes without a question.
                    delay(200)
                    screen.buttonNamed("Abbrechen").click()
                    awaitUntil("closed without a question") {
                        screen.querySelector("[id='lapis-create-board-appointment'] .lapis-form") ==
                            null
                    }
                }
            }
        }

    // ---- Meetings --------------------------------------------------------------------------------------------------------

    @Test
    fun meetings_aBoardMemberSeesNeueSitzung_collapsed_andTheEmptyStateNamesIt(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            val members = routeOf { rpcService<IMemberService>().listMembers() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == committees -> request.answerWith(committeesJson(listOf(committee())))
                        request.rpcRoute == members -> request.answerWith("[]")
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-meetings") { root, element ->
                    renderMeetingsScreen(root)
                    val screen = element()
                    awaitUntil("the button appears once the roster rights are known") { screen.actionButtons().isNotEmpty() }
                    assertEquals(listOf("Neue Sitzung"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hasField("Sitzungsleitung"), "collapsed: no form fields")
                    awaitUntil("the empty state names the button") {
                        screen.shows("Noch keine Sitzungen. Mit \"Neue Sitzung\" legen Sie eine an.")
                    }
                    openCreateForm(screen, "lapis-create-meeting")
                    assertTrue(screen.hasField("Sitzungsleitung"))
                    assertFalse(screen.shows("Neue Sitzung anlegen"), "the old always-visible section title is gone")
                }
            }
        }

    @Test
    fun meetings_aMemberWithoutAChairRole_getsNoButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            val roster = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == committees -> request.answerWith(committeesJson(listOf(committee())))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-meetings-member") { root, element ->
                    renderMeetingsScreen(root)
                    awaitUntil("the empty state is shown") { element().shows("Noch keine Sitzungen vorhanden.") }
                    // The roster check has run (the committee's roster was asked for) and found nothing to manage.
                    awaitUntil("the roster check ran") { calls.toRoute(roster).isNotEmpty() }
                    delay(100)
                    assertTrue(element().actionButtons().isEmpty())
                    assertTrue(element().allOf(".lapis-page-action").isEmpty(), "no empty action area")
                }
            }
        }

    // ---- Motions ---------------------------------------------------------------------------------------------------------

    @Test
    fun motions_aBoardMemberSeesNeuerAntrag_collapsed_andOpeningBuildsTheSubmissionForm(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == committees -> request.answerWith(committeesJson(listOf(committee())))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-motions") { root, element ->
                    renderMotionsScreen(root)
                    val screen = element()
                    awaitUntil("the button appears once the committees are loaded") { screen.actionButtons().isNotEmpty() }
                    assertEquals(listOf("Neuer Antrag"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hasField("Antragstext"), "collapsed: no form fields")
                    awaitUntil("the empty state names the button") {
                        screen.shows("Noch keine Anträge. Mit \"Neuer Antrag\" reichen Sie einen ein.")
                    }
                    openCreateForm(screen, "lapis-create-motion")
                    assertTrue(screen.hasField("Antragstext"))
                    assertNotNull(screen.querySelector("[id='lapis-create-motion'] textarea"))
                    assertFalse(screen.shows("Neuen Antrag einreichen"), "the old always-visible section title is gone")
                }
            }
        }
}
