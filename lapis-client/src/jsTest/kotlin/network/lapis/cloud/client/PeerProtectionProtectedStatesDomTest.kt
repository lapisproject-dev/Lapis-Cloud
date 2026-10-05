package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangePendingDto
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.UnlinkedMemberDto
import network.lapis.cloud.shared.rpc.IKeycloakLinkService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class NoopProposalRpc : MemberEmailProposalRpc {
    val overrideCalls = mutableListOf<List<String>>()
    val proposeCalls = mutableListOf<List<String>>()

    override suspend fun capability() = EmailChangeCapabilityDto(MailDeliveryState.HANDED_TO_SMTP, ownChangeAvailable = true)

    override suspend fun pending(memberId: String): EmailChangePendingDto? = null

    override suspend fun propose(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
    ): EmailChangePendingDto {
        proposeCalls += listOf(memberId, newEmail, newEmailRepeat)
        return EmailChangePendingDto("c1", "n***@example.org", EmailChangeKind.PROPOSAL, LocalDateTime(2026, 10, 12, 10, 0), null, false)
    }

    override suspend fun override(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
        reason: String,
    ): EmailChangePendingDto {
        overrideCalls += listOf(memberId, newEmail, newEmailRepeat, reason)
        return EmailChangePendingDto(
            "c2",
            "n***@example.org",
            EmailChangeKind.ADMIN_OVERRIDE,
            LocalDateTime(2026, 10, 12, 10, 0),
            null,
            false,
        )
    }

    override suspend fun withdraw(changeId: String) = Unit
}

/** Welle V1.9.57 -- the protected states next to the roster: emergency e-mail switch and manual Keycloak link against ANOTHER administrator. */
class PeerProtectionProtectedStatesDomTest {
    private fun session(role: AccountRole = AccountRole.ADMIN) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Caller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            keycloakMode = true,
        )

    private fun row(role: AccountRole) =
        MemberAdminRowDto(
            id = "member-5",
            displayName = "Amara Okafor",
            email = "amara@example.org",
            status = MemberStatus.ACTIVE,
            role = role,
            joinedAt = LocalDate(2026, 1, 1),
        )

    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private fun HTMLElement.checkbox(): HTMLInputElement? = allOf("input[type=checkbox]").firstOrNull() as? HTMLInputElement

    @Test
    fun emergencyEmailSwitch_againstAnotherAdmin_isDisabled_withTheReasonAsText(): Promise<Unit> {
        val rpc = NoopProposalRpc()
        return formTest {
            AppState.setSession(session())
            mountedForm("peer-email-protected") { root, element ->
                renderEmailChangeProposalSection(root, row(AccountRole.ADMIN), AccountRole.ADMIN, LegendGroup(), rpc, onChanged = {})
                awaitUntil("section rendered") { element().hasText("Änderung vorschlagen") }
                element().buttonNamed("Änderung vorschlagen").click()
                awaitUntil("form visible") { element().checkbox() != null }
                assertTrue(element().checkbox()!!.disabled, "the emergency switch is visible but disabled")
                assertTrue(
                    element()
                        .allOf(
                            "[data-peer-protection-notice]",
                        ).any { it.hasText("Notfalländerung der Adresse eines anderen Administrators") },
                )
            }
        }
    }

    @Test
    fun emergencyEmailSwitch_againstANonAdmin_staysAvailable_forAnAdmin(): Promise<Unit> {
        val rpc = NoopProposalRpc()
        return formTest {
            AppState.setSession(session())
            mountedForm("peer-email-open") { root, element ->
                renderEmailChangeProposalSection(root, row(AccountRole.MEMBER), AccountRole.ADMIN, LegendGroup(), rpc, onChanged = {})
                awaitUntil("section rendered") { element().hasText("Änderung vorschlagen") }
                element().buttonNamed("Änderung vorschlagen").click()
                awaitUntil("form visible") { element().checkbox() != null }
                assertTrue(!element().checkbox()!!.disabled)
                assertEquals(0, element().allOf("[data-peer-protection-notice]").size)
            }
        }
    }

    private fun HTMLElement.iconButtons(): List<HTMLElement> =
        allOf("button").filter {
            it.textContent
                .orEmpty()
                .trim()
                .isEmpty()
        }

    @Test
    fun keycloakLink_forAnotherAdmin_isDisabledWithTheReason_forOthersAvailable(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listRoute = routeOf { rpcService<IKeycloakLinkService>().listUnlinkedMembers() }
            val members =
                listOf(
                    UnlinkedMemberDto("member-2", "Anna Admin", "anna@example.org", role = AccountRole.ADMIN),
                    UnlinkedMemberDto("member-3", "Bo Svensson", "bo@example.org", role = AccountRole.MEMBER),
                    // the caller's OWN account may be linked
                    UnlinkedMemberDto("caller-1", "Caller", "caller@example.org", role = AccountRole.ADMIN),
                )
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == listRoute) {
                        request.answerWith(jsonOf(ListSerializer(UnlinkedMemberDto.serializer()), members))
                    } else if (request.isRpc) {
                        request.answerWith("null")
                    } else {
                        StubResponse()
                    }
                },
            ) { _ ->
                mountedForm("peer-keycloak") { root, element ->
                    renderKeycloakLinkSection(root)
                    awaitUntil("rows") { element().hasText("Anna Admin") && element().hasText("Bo Svensson") }
                    delay(100)
                    val linkButtons = element().iconButtons()
                    assertEquals(3, linkButtons.size)
                    val disabled = linkButtons.filter { (it as HTMLButtonElement).disabled }
                    assertEquals(1, disabled.size, "only the other administrator's row is protected")
                    assertTrue(
                        disabled
                            .single()
                            .getAttribute("aria-label")
                            .orEmpty()
                            .startsWith("Geschützt:"),
                    )
                    assertTrue(disabled.single().title.startsWith("Geschützt:"))
                }
            }
        }
}
