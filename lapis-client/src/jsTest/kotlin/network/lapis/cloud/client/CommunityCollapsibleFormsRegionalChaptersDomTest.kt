package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.IRegionalChapterService
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
 * V1.9.48 -- rule R36B for the chapter administration: "Landesverband anlegen" is one collapsed form behind a page header button
 * (ADMIN only). Button and form host live outside the data section, so a reload never builds a second button and never sweeps away a
 * form the person is typing in; at the chapter limit the button stays active and the opened form says why it cannot save.
 */
class CommunityCollapsibleFormsRegionalChaptersDomTest {
    private val marker = "###KvI18nS###"
    private val nameTaken = "network.lapis.cloud.shared.rpc.RegionalChapterNameTakenException"
    private val formId = "lapis-create-regional-chapter"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun chapter(
        id: String,
        name: String,
    ) = RegionalChapterDto(id = id, name = name, activeMemberCount = 0, assignedMemberCount = 0, activeOfficerCount = 0)

    private fun overview(vararg chapters: RegionalChapterDto) =
        jsonOf(RegionalChapterOverviewDto.serializer(), RegionalChapterOverviewDto(chapters.toList(), 0))

    private fun HTMLElement.pageActionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hostOpen(): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun dismissDialogWith(label: String) {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        lastOpenModal().buttonNamed(label).click()
        awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
    }

    @Test
    fun admin_oneButtonInTheHeader_nameTakenKeepsTheForm_saveFoldsBackReloadsAndRefreshesTheSession(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IRegionalChapterService>().listChapters() }
            val create = routeOf { rpcService<IRegionalChapterService>().createChapter("n") }
            val sessionRoute = routeOf { rpcService<IAuthService>().getSessionInfo() }
            val rows = mutableListOf(chapter("c1", "Nord"))
            var takenNext = true
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(overview(*rows.toTypedArray()))
                        request.rpcRoute == create ->
                            if (takenNext) {
                                takenNext = false
                                serviceExceptionResult(request.json.id as Int, nameTaken)
                            } else {
                                val added = chapter("c2", "Süd")
                                rows += added
                                request.answerWith(jsonOf(RegionalChapterDto.serializer(), added))
                            }
                        request.rpcRoute == sessionRoute ->
                            request.answerWith(
                                jsonOf(SessionInfoDto.serializer(), session(AccountRole.ADMIN)),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-chapters") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the chapter card") { element().shows("Nord") }
                    val screen = element()
                    assertEquals(listOf("Landesverband anlegen"), screen.pageActionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hostOpen(), "collapsed after the load")

                    val host = openCreateForm(screen, formId)
                    assertTrue(document.activeElement is HTMLInputElement, "the focus moved into the first field")
                    escape(host)
                    awaitUntil("closed without a question") { !screen.hostOpen() }

                    val reopened = openCreateForm(screen, formId)
                    reopened.typeInto("Name des Landesverbands", "Süd")
                    escape(reopened)
                    dismissDialogWith("Weiter bearbeiten")
                    assertTrue(screen.hostOpen())

                    reopened.buttonNamed("Landesverband anlegen").click()
                    awaitUntil("the first write was attempted") { calls.toRoute(create).size == 1 }
                    awaitUntil("the field shows the conflict") { reopened.shownErrors().any { it.contains("existiert bereits") } }
                    assertTrue(screen.hostOpen(), "a name conflict never folds the form back")

                    val listCallsBefore = calls.toRoute(list).size
                    reopened.buttonNamed("Landesverband anlegen").click()
                    awaitUntil("the second write was made") { calls.toRoute(create).size == 2 }
                    awaitUntil("the form folded back") { !screen.hostOpen() }
                    assertEquals("Süd", calls.toRoute(create).last().rpcParam(0) as String)
                    awaitUntil("the list was reloaded") { calls.toRoute(list).size > listCallsBefore }
                    awaitUntil("the new chapter is shown") { screen.shows("Süd") }
                    awaitUntil("the session was refreshed") { calls.toRoute(sessionRoute).isNotEmpty() }
                    assertEquals(1, screen.pageActionButtons().size, "still exactly one button after the reload")
                }
            }
        }

    @Test
    fun aTypedFormSurvivesABodyReload_andTheButtonIsNeverDoubled(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IRegionalChapterService>().listChapters() }
            val delete = routeOf { rpcService<IRegionalChapterService>().deleteChapter("x") }
            val rows = mutableListOf(chapter("c1", "Nord"), chapter("c2", "Ost"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(overview(*rows.toTypedArray()))
                        request.rpcRoute == delete -> {
                            rows.removeAll { it.id == "c2" }
                            request.answerWith("{}")
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-chapters-reload") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("both cards") { element().shows("Nord") && element().shows("Ost") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.typeInto("Name des Landesverbands", "Tippfehler")

                    // delete the second chapter through its card: the body reloads
                    val listCallsBefore = calls.toRoute(list).size
                    screen
                        .allOf("button")
                        .filter { it.textContent?.trim() == "Löschen" }
                        .last()
                        .click()
                    awaitUntil("the delete confirmation is shown") { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Löschen").click()
                    awaitUntil("the chapter was deleted and the list reloaded") {
                        calls.toRoute(delete).size == 1 && calls.toRoute(list).size > listCallsBefore && !screen.shows("Ost")
                    }
                    assertEquals(1, screen.pageActionButtons().size, "no second button after the reload")
                    assertTrue(screen.hostOpen(), "the open form survived")
                    assertEquals("Tippfehler", (host.controlOf("Name des Landesverbands") as HTMLInputElement).value)
                }
            }
        }

    @Test
    fun atTheLimit_theButtonStaysActive_andTheOpenedFormSaysWhyItCannotSave(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IRegionalChapterService>().listChapters() }
            val rows = (1..RegionalChapterRules.MAX_CHAPTERS).map { chapter("c$it", "Verband $it") }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc &&
                        request.rpcRoute == list
                    ) {
                        request.answerWith(overview(*rows.toTypedArray()))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-chapters-limit") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the cards") { element().shows("Verband 1") }
                    val screen = element()
                    val button = screen.pageActionButtons().single()
                    assertFalse(button.hasAttribute("disabled"), "the button is active at the limit")
                    val host = openCreateForm(screen, formId)
                    assertTrue(host.shows("Höchstens ${RegionalChapterRules.MAX_CHAPTERS} Landesverbände möglich."))
                    assertTrue(host.buttonNamed("Landesverband anlegen").hasAttribute("disabled"), "the submit is disabled")
                }
            }
        }

    @Test
    fun aBoardSessionHasNoCreateButton_andAHostileNameIsNotResolved(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val list = routeOf { rpcService<IRegionalChapterService>().listChapters() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(overview(chapter("c1", "${marker}Quorum heißt")))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-chapters-board") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the chapter card") { element().shows("Quorum") }
                    assertTrue(element().pageActionButtons().isEmpty(), "BOARD gets no create button")
                    assertTrue(element().querySelector("[id='$formId']") == null, "and no form host")
                    assertFalse(element().shows(marker))
                }
            }
        }
}
