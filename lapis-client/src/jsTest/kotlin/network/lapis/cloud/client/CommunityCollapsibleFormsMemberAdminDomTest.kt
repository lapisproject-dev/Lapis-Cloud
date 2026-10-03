package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.48 -- rule R36B for the member administration: "Mitglied direkt anlegen" is one collapsed form behind a page header button
 * (BOARD and ADMIN only). It is built exactly once -- also when the chapter options fail to load --, asks before discarding (the
 * password field counts), and a save folds it back and reloads the roster with the CURRENT filter.
 */
class CommunityCollapsibleFormsMemberAdminDomTest {
    private val marker = "###KvI18nS###"
    private val createLabel = "Mitglied direkt anlegen"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun row(
        id: String,
        name: String,
    ) = MemberAdminRowDto(
        id = id,
        displayName = name,
        email = "$id@example.org",
        status = MemberStatus.ACTIVE,
        role = AccountRole.MEMBER,
        joinedAt = LocalDate(2020, 1, 1),
    )

    private fun page(vararg rows: MemberAdminRowDto) =
        jsonOf(
            MemberAdminPageDto.serializer(),
            MemberAdminPageDto(rows.toList(), rows.size, mapOf(MemberStatus.ACTIVE to rows.size), 25, 0),
        )

    /** The number of status filters of a roster request (the empty default is not encoded at all). */
    private fun statusCount(request: RecordedRequest): Int = (request.rpcParam(0).statuses as? Array<*>)?.size ?: 0

    private fun HTMLElement.pageActionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hostOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun dismissDialogWith(label: String) {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        lastOpenModal().buttonNamed(label).click()
        awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
    }

    @Test
    fun adminSeesTheButtonInTheHeader_theFormIsCollapsed_andASaveReloadsTheRosterWithTheCurrentFilter(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery()) }
            val chapters = routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() }
            val create =
                routeOf {
                    rpcService<IRegistrationService>().createMemberDirect(AdminCreateMemberInput("n", "e", AccountRole.MEMBER, "p"))
                }
            val created =
                MemberDto("m-new", "Kofi Mensah", "kofi@example.org", MemberStatus.ACTIVE, LocalDate(2026, 10, 3), AccountRole.MEMBER)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(row("m-1", "Berta Beides")))
                        request.rpcRoute == chapters -> request.answerWith("[]")
                        request.rpcRoute == create -> request.answerWith(jsonOf(MemberDto.serializer(), created))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-member-direct") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().shows("Berta Beides") }
                    val screen = element()
                    assertEquals(listOf(createLabel), screen.pageActionButtons().map { it.textContent?.trim() })
                    assertTrue(
                        screen.allOf("h2").none { it.textContent?.trim() == createLabel },
                        "the old always-visible section title is gone",
                    )
                    assertFalse(screen.hostOpen("lapis-create-member-direct"), "collapsed after the load")

                    // a status filter set BEFORE the save must survive the reload
                    screen.allOf("button").first { it.textContent.orEmpty().startsWith("Aktiv") }.click()
                    awaitUntil("the filtered roster was requested") {
                        calls.toRoute(list).any { statusCount(it) == 1 }
                    }

                    val host = openCreateForm(screen, "lapis-create-member-direct")
                    assertTrue(document.activeElement is HTMLInputElement, "the focus moved into the first field")
                    // only the password typed: still a change, so Escape asks
                    host.typeInto("Vorläufiges Passwort", "  pass word 4711 x  ")
                    escape(host)
                    dismissDialogWith("Weiter bearbeiten")
                    assertTrue(screen.hostOpen("lapis-create-member-direct"), "keep editing keeps the form")

                    host.typeInto("Name", "  Kofi Mensah  ")
                    host.typeInto("E-Mail", "  kofi@example.org ")
                    val listCallsBefore = calls.toRoute(list).size
                    host.buttonNamed("Mitglied anlegen").click()
                    awaitUntil("createMemberDirect was called") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen("lapis-create-member-direct") }
                    assertEquals("Kofi Mensah", calls.singleCall(create).rpcParam(0).displayName as String)
                    awaitUntil("the roster was reloaded") { calls.toRoute(list).size > listCallsBefore }
                    assertEquals(1, statusCount(calls.toRoute(list).last()), "the reload keeps the filter")
                    val button = createFormButton(screen, "lapis-create-member-direct")
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                }
            }
        }

    @Test
    fun whenTheChapterOptionsFail_thereIsStillExactlyOneButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val list = routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery()) }
            val chapters = routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(row("m-1", "Berta Beides")))
                        request.rpcRoute == chapters -> StubResponse(networkError = true)
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-member-direct-options-fail") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().shows("Berta Beides") }
                    assertEquals(listOf(createLabel), element().pageActionButtons().map { it.textContent?.trim() })
                    assertEquals(1, element().allOf("button[aria-controls='lapis-create-member-direct']").size)
                }
            }
        }

    @Test
    fun aTreasurerSeesTheRosterButNoCreateButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val list = routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery()) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(page(row("m-1", "Berta Beides")))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-member-direct-treasurer") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().shows("Berta Beides") }
                    assertTrue(element().pageActionButtons().isEmpty(), "no header action for a treasurer")
                    assertTrue(element().querySelector("[id='lapis-create-member-direct']") == null, "no form host either")
                }
            }
        }

    @Test
    fun hostileMemberAndChapterNames_areNotResolvedAsMarkers(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery()) }
            val chapters = routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(row("m-1", "${marker}Quorum heißt")))
                        request.rpcRoute == chapters ->
                            request.answerWith(
                                jsonOf(
                                    ListSerializer(RegionalChapterRefDto.serializer()),
                                    listOf(RegionalChapterRefDto("c1", "${marker}Nord")),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-member-direct-hostile") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().shows("Quorum") }
                    val host = openCreateForm(element(), "lapis-create-member-direct")
                    awaitUntil("the chapter option is offered") { host.shows("Nord") }
                    assertFalse(element().shows(marker), "the marker never reaches the DOM text")
                    assertTrue(element().allOf("option").none { it.textContent.orEmpty().contains(marker) })
                }
            }
        }
}
