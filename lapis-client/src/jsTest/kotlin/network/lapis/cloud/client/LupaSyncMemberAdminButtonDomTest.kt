package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IRegistrationService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.226 -- "Mit LupaBB abgleichen" on the member administration: ADMIN only. */
class LupaSyncMemberAdminButtonDomTest {
    private suspend fun scenario(
        role: AccountRole,
        block: suspend (org.w3c.dom.HTMLElement) -> Unit,
    ) {
        AppState.setSession(LupaSyncDomSupport.session(role))
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
                    request.rpcRoute == chapters || request.rpcRoute == settings -> request.answerWith("null")
                    else -> request.answerWith("[]")
                }
            },
        ) { _ ->
            mountedForm("lupa-button-${role.name.lowercase()}") { root, element ->
                renderMemberAdministrationScreen(root)
                awaitAppScopeIdle("the screen finished loading")
                block(element())
            }
        }
    }

    @Test
    fun anAdmin_seesTheButton(): Promise<Unit> =
        formTest {
            scenario(AccountRole.ADMIN) { page ->
                assertEquals(1, page.allOf("button").count { it.textContent?.trim() == "Mit LupaBB abgleichen" })
            }
        }

    @Test
    fun boardAndTreasurer_doNotSeeTheButton(): Promise<Unit> =
        formTest {
            for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER)) {
                scenario(role) { page ->
                    assertTrue(page.allOf("button").none { it.textContent?.trim() == "Mit LupaBB abgleichen" })
                }
            }
        }

    @Test
    fun theSyncRoute_isASubPathOfTheMemberAdministration_soItsSidebarLinkStaysHighlighted() {
        assertTrue(NavRouteMatch.isActive(Routes.MEMBER_LUPABB_SYNC, Routes.MEMBERS))
        assertTrue(Routes.MEMBER_LUPABB_SYNC != Routes.MEMBERS)
    }
}
