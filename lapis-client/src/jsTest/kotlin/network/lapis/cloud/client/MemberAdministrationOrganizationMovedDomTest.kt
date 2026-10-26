package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.221 -- the organization-wide settings moved to the page Organisation. What the member administration still shows: the pointer (ADMIN
 * only) and the READ-ONLY status line of the public member count for TREASURER, BOARD and ADMIN. No settings card, no switch.
 */
class MemberAdministrationOrganizationMovedDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private suspend fun scenario(
        role: AccountRole,
        block: suspend (HTMLElement) -> Unit,
    ) {
        AppState.setSession(session(role))
        val list =
            routeOf {
                rpcService<IMemberService>().listMembersForAdministration(
                    network.lapis.cloud.shared.domain
                        .MemberAdminQuery(),
                )
            }
        val chapters = routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() }
        val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == list ->
                        request.answerWith(jsonOf(MemberAdminPageDto.serializer(), MemberAdminPageDto(emptyList(), 0, emptyMap(), 25, 0)))
                    request.rpcRoute == chapters -> request.answerWith("[]")
                    request.rpcRoute == settings ->
                        request.answerWith(
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
                                    showPublicMemberCount = true,
                                ),
                            ),
                        )
                    else -> request.answerWith("[]")
                }
            },
        ) { _ ->
            mountedForm("org-moved-${role.name.lowercase()}") { root, element ->
                renderMemberAdministrationScreen(root)
                awaitAppScopeIdle("the screen finished loading")
                block(element())
            }
        }
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    private fun HTMLElement.headings() = allOf("h2").map { it.textContent.orEmpty().trim() }

    @Test
    fun anAdmin_seesThePointerWithALink_andNoSettingsCardAndNoSwitch(): Promise<Unit> =
        test {
            scenario(AccountRole.ADMIN) { page ->
                val headings = page.headings()
                assertFalse("Zeitzone der Organisation" in headings)
                assertFalse("Videokonferenz" in headings)
                assertFalse("Ausschreibungen" in headings)
                assertNull(page.querySelector("button[role=switch]"))
                val link = page.allOf("a").first { it.textContent?.trim() == "Zu Organisation" }
                assertEquals("#${Routes.ORGANIZATION}", link.getAttribute("href"))
                assertTrue(page.textContent.orEmpty().contains("finden Sie jetzt unter Organisation"))
                // the status line stays, with a link to the setting
                assertTrue(page.textContent.orEmpty().contains("Mitgliederzahl öffentlich:"))
                assertNotNull(page.allOf("a").firstOrNull { it.getAttribute("href") == "#${Routes.ORGANIZATION}?section=mitgliederzahl" })
                assertTrue(page.allOf("button").none { it.textContent?.trim() == "Verbergen" || it.textContent?.trim() == "Anzeigen" })
            }
        }

    @Test
    fun aTreasurer_seesOnlyTheReadOnlyStatusLine(): Promise<Unit> =
        test {
            scenario(AccountRole.TREASURER) { page ->
                assertTrue(page.textContent.orEmpty().contains("Mitgliederzahl öffentlich:"))
                assertTrue(page.textContent.orEmpty().contains("Sichtbar"))
                assertTrue(page.textContent.orEmpty().contains("Ein ADMIN kann diese Einstellung ändern."))
                assertTrue(page.allOf("a").none { it.getAttribute("href")?.startsWith("#${Routes.ORGANIZATION}") == true })
                assertFalse(page.textContent.orEmpty().contains("finden Sie jetzt unter Organisation"))
                assertTrue(page.allOf("button").none { it.textContent?.trim() == "Verbergen" })
            }
        }

    @Test
    fun aBoardMember_seesTheStatusLineReadOnly_andNoPointer(): Promise<Unit> =
        test {
            scenario(AccountRole.BOARD) { page ->
                assertTrue(page.textContent.orEmpty().contains("Mitgliederzahl öffentlich:"))
                assertTrue(page.textContent.orEmpty().contains("Ein ADMIN kann diese Einstellung ändern."))
                assertFalse(page.textContent.orEmpty().contains("finden Sie jetzt unter Organisation"))
                assertTrue(page.allOf("button").none { it.textContent?.trim() == "Verbergen" })
            }
        }
}
