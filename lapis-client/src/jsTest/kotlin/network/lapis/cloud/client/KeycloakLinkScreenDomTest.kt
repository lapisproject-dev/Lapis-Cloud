package network.lapis.cloud.client

import io.kvision.panel.simplePanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.UnlinkedMemberDto
import network.lapis.cloud.shared.rpc.IKeycloakLinkService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [DataScreenLayout.tableActionButton] is icon-only: its accessible name is `aria-label`, not text content. */
private fun HTMLElement.iconButtonLabeled(label: String): HTMLElement =
    assertNotNull(allOf("button").firstOrNull { it.getAttribute("aria-label") == label }, "no icon button '$label'")

/**
 * V1.7.2 sub-wave 2b -- `KeycloakLinkScreen.kt`'s admin-only "Keycloak-Verknüpfung" panel
 * ([renderKeycloakLinkSection]/[openKeycloakLinkDialog]) and the per-roster-row unlink action
 * ([renderKeycloakUnlinkAction]). Driven the way a person does (click, type, click), same idiom
 * `TravelExpenseScreenFormDomTest.kt` establishes -- see that file's class KDoc.
 */
class KeycloakLinkScreenDomTest {
    private val adminSession =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Amara Okafor",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            keycloakMode = true,
        )

    private val unlinkedMember = UnlinkedMemberDto(memberId = "member-2", displayName = "Bo Svensson", email = "bo@example.org")

    private fun row(id: String = "member-3") =
        MemberAdminRowDto(
            id = id,
            displayName = "Cato Nilsson",
            email = "cato@example.org",
            status = MemberStatus.ACTIVE,
            role = AccountRole.MEMBER,
            joinedAt = LocalDate(2020, 1, 1),
        )

    // ── renderKeycloakLinkSection / openKeycloakLinkDialog ──────────────────────────────────────

    @Test
    fun linkPanel_happyPath_typesTheSubjectAndCallsLinkMemberWithTheTrimmedValue(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val listRoute = routeOf { rpcService<IKeycloakLinkService>().listUnlinkedMembers() }
            val linkRoute = routeOf { rpcService<IKeycloakLinkService>().linkMember("x", "x") }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else {
                        when (request.rpcRoute) {
                            listRoute -> request.answerWith(jsonOf(ListSerializer(UnlinkedMemberDto.serializer()), listOf(unlinkedMember)))
                            else -> request.answerWith("null")
                        }
                    }
                },
            ) { calls ->
                mountedForm("keycloak-link-happy") { root, element ->
                    renderKeycloakLinkSection(root)
                    awaitUntil("unlinked member row rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Bo Svensson")
                    }
                    element().iconButtonLabeled("Verknüpfen").click()
                    delay(80)
                    val modal = lastOpenModal()
                    modal.typeInto("Keycloak-Subject", "  a1b2c3d4-keycloak-subject  ")
                    modal.buttonNamed("Verknüpfen").click()
                    awaitUntil("linkMember", timeoutMs = 800) { calls.toRoute(linkRoute).size == 1 }
                    val call = calls.singleCall(linkRoute)
                    assertEquals("member-2", call.rpcParam(0) as String)
                    assertEquals("a1b2c3d4-keycloak-subject", call.rpcParam(1) as String, "the subject is sent, trimmed")
                }
            }
        }

    @Test
    fun linkDialog_emptySubject_sendsNoRequestAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            withFetchStub(
                respond = { request -> if (request.isRpc) rpcResult(request.json.id as Int, "null") else StubResponse() },
            ) { calls ->
                mountedForm("keycloak-link-invalid") { _, _ ->
                    openKeycloakLinkDialog(unlinkedMember) {}
                    val modal = lastOpenModal()
                    modal.buttonNamed("Verknüpfen").click()
                    delay(80)
                    assertTrue(modal.shownErrors().isNotEmpty(), "the empty required subject is reported on the field")
                    assertEquals(0, calls.rpcCount, "no RPC call at all for an invalid submission")
                }
            }
        }

    // ── renderKeycloakUnlinkAction ───────────────────────────────────────────────────────────────

    @Test
    fun unlinkAction_isGuardedByAConfirmationDialog_andCallsUnlinkMemberOnlyAfterConfirming(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val unlinkRoute = routeOf { rpcService<IKeycloakLinkService>().unlinkMember("x") }
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() },
            ) { calls ->
                mountedForm("keycloak-unlink-guard") { root, element ->
                    val actionsCell = root.simplePanel()
                    renderKeycloakUnlinkAction(actionsCell, row(), onChanged = {})
                    element().iconButtonLabeled("Keycloak-Verknüpfung entfernen").click()
                    delay(80)
                    // The confirmation dialog is open; no RPC call has gone out yet (R29 guard).
                    assertEquals(0, calls.toRoute(unlinkRoute).size, "unlinkMember must not fire before confirmation")
                    val modal = lastOpenModal()
                    modal.buttonNamed("Verknüpfung entfernen").click()
                    awaitUntil("unlinkMember", timeoutMs = 800) { calls.toRoute(unlinkRoute).size == 1 }
                    assertEquals("member-3", calls.singleCall(unlinkRoute).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun unlinkAction_isNotRenderedOutsideKeycloakMode() {
        AppState.setSession(adminSession.copy(keycloakMode = false))
        withMountedRoot("keycloak-unlink-hidden-non-keycloak") { root, element ->
            val actionsCell = root.simplePanel()
            renderKeycloakUnlinkAction(actionsCell, row(), onChanged = {})
            assertEquals(0, element().querySelectorAll("button").length, "no button at all outside Keycloak mode")
        }
        AppState.setSession(null)
    }

    @Test
    fun unlinkAction_isNotRenderedForANonAdminCaller() {
        AppState.setSession(adminSession.copy(role = AccountRole.BOARD))
        withMountedRoot("keycloak-unlink-hidden-non-admin") { root, element ->
            val actionsCell = root.simplePanel()
            renderKeycloakUnlinkAction(actionsCell, row(), onChanged = {})
            assertEquals(0, element().querySelectorAll("button").length, "no button at all for a non-ADMIN caller")
        }
        AppState.setSession(null)
    }
}
