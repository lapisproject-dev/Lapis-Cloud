package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDocumentService
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.4.31 audit fix M9: the irreversible / state-changing write paths that ran behind a confirm dialog but whose TRIGGER stayed
 * clickable while the request ran (a second click opened a second dialog and, confirmed, sent a second write): membership exit,
 * member-card issue (a new card invalidates the previous one) and document delete. Two quick clicks -> ONE write.
 */
class WritePathsSingleShotDomTest {
    @AfterTest
    fun reset() {
        AppState.setSession(null)
    }

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Testperson",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2026, 9, 8, 12, 0),
        )

    private fun shownModals() = document.querySelectorAll(".modal.show").length

    private fun modalButton(text: String): HTMLElement {
        val buttons = document.querySelectorAll(".modal.show .modal-footer button")
        return (0 until buttons.length).map { buttons.item(it) as HTMLElement }.first { it.textContent?.trim() == text }
    }

    private suspend fun awaitModal() = awaitUntil("the confirm modal is shown") { shownModals() > 0 }

    @Test
    fun membershipExit_twoQuickClicksAndADoubleConfirm_sendOneRequest(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val route = routeOf { rpcService<IRegistrationService>().leaveMembership() }
            // slow, failing answer: the request stays in flight while the second click comes; a failure keeps `navigateTo` out of the test
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (request.isRpc &&
                    request.rpcRoute == route
                ) {
                    StubResponse(networkError = true, delayMs = 400)
                } else {
                    rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond) { calls ->
                mountedForm("m9-exit") { root, element ->
                    renderAccountActions(root)
                    val trigger = element().buttonNamed("Austritt (Mitgliedschaft beenden)")
                    trigger.click()
                    awaitModal()
                    val confirm = modalButton("Austritt bestätigen")
                    confirm.click()
                    confirm.click() // a double click on the dialog itself
                    delay(50)
                    trigger.click() // ... and a click on the trigger while the request is in flight
                    delay(150)
                    assertEquals(0, shownModals(), "the disabled trigger must not open a second dialog while the request runs")
                    awaitUntil("the request finished") { calls.toRoute(route).isNotEmpty() }
                    delay(600)
                    assertEquals(1, calls.toRoute(route).size, "exactly one leaveMembership request")
                    assertTrue(
                        !(trigger.asDynamic().disabled as Boolean),
                        "the trigger is released after a failed attempt (a retry is possible)",
                    )
                }
            }
        }

    @Test
    fun documentDelete_secondClickWhileTheRequestRuns_opensNoSecondDialog(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val route = routeOf { rpcService<IDocumentService>().deleteDocument("x") }
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (request.isRpc &&
                    request.rpcRoute == route
                ) {
                    StubResponse(networkError = true, delayMs = 400)
                } else {
                    rpcResult(request.json.id as Int, "null")
                }
            }
            val doc =
                DocumentDto(
                    id = "d1",
                    folderId = "f1",
                    title = "Satzung",
                    currentVersionId = null,
                    createdBy = "member-1",
                    createdByDisplayName = "Testperson",
                    createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                    isDeleted = false,
                )
            withFetchStub(respond) { calls ->
                mountedForm("m9-delete") { root, element ->
                    root.renderDocumentDeleteAction(doc) {}
                    val trigger = element().querySelector("button[aria-label='Löschen']") as HTMLElement
                    trigger.click()
                    awaitModal()
                    val confirm = modalButton("Löschen")
                    confirm.click()
                    confirm.click()
                    delay(50)
                    trigger.click()
                    delay(150)
                    assertEquals(0, shownModals(), "no second dialog while the delete runs")
                    delay(700)
                    assertEquals(1, calls.toRoute(route).size, "exactly one deleteDocument request")
                }
            }
        }

    @Test
    fun memberCard_aSecondIssueCannotBeStartedRightAfterTheFirst(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            var submits = 0
            val prototype = js("HTMLFormElement.prototype")
            val realSubmit = prototype.submit
            prototype.submit = { submits++ }
            try {
                withFetchStub { _ ->
                    mountedForm("m9-card") { root, element ->
                        renderMemberCard(root, MemberStatus.ACTIVE)
                        val trigger = element().buttonNamed("Mitgliedsausweis herunterladen")
                        trigger.click()
                        awaitModal()
                        val confirm = modalButton("Ausstellen und herunterladen")
                        confirm.click()
                        confirm.click()
                        delay(100)
                        assertEquals(1, submits, "one card issued, however often the dialog button was clicked")
                        assertTrue(trigger.asDynamic().disabled as Boolean, "the trigger is disabled for the cool-down after an issue")
                        trigger.click()
                        delay(150)
                        assertEquals(0, shownModals(), "the disabled trigger opens no second issuing dialog")
                        assertEquals(1, submits)
                    }
                }
            } finally {
                prototype.submit = realSubmit
            }
        }
}
