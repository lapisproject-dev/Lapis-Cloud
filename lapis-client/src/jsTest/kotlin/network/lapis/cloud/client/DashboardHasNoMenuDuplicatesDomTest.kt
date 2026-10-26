package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.224 -- the dashboard shows only what the menu does not: for every kind of person it links to no destination of the sidebar (no tiles, no
 * section headings, no "Schnellzugriff"). The one exception is the sentence that tells where password, card and leaving are -- "Meine Daten".
 */
class DashboardHasNoMenuDuplicatesDomTest {
    private fun session(
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Person",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = status,
        aiAssistantEnabled = true,
        mcpEnabled = true,
    )

    private fun check(
        id: String,
        session: SessionInfoDto,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            withFetchStub {
                mountedForm(id) { root, element ->
                    renderDashboardScreen(
                        root,
                        OnlineCountsRpc { error("unused") },
                        WindowOnlineCountsScheduler,
                        DashboardSignalsRpc { emptyList() },
                    )
                    awaitUntil("the empty sentence") { element().textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
                    val el: HTMLElement = element()
                    val menuRoutes = NavRegistry.entries.map { it.route }.toSet() - Routes.DSGVO_RIGHTS
                    val links = el.querySelectorAll("a[href]").asList().map { (it as HTMLElement).getAttribute("href")!!.removePrefix("#") }
                    assertEquals(
                        emptyList(),
                        links.filter { it in menuRoutes },
                        "links to menu destinations for ${session.role}/${session.status}",
                    )
                    assertEquals(1, links.count { it == Routes.DSGVO_RIGHTS }, "exactly one link to Meine Daten")
                    assertNull(el.querySelector(".lapis-dashboard-tile"), "no tiles")
                    assertNull(el.querySelector(".lapis-dashboard-grid"), "no tile grid")
                    assertNull(el.querySelector(".lapis-dashboard-section"), "no section per sidebar group")
                    assertTrue(!el.textContent.orEmpty().contains("Schnellzugriff"))
                    assertTrue(!el.textContent.orEmpty().contains("Nächste Veranstaltung"))
                }
            }
        }

    @Test fun member() = check("nodup-member", session(AccountRole.MEMBER))

    @Test fun friend() = check("nodup-friend", session(AccountRole.MEMBER, MemberStatus.FRIEND))

    @Test fun guest() = check("nodup-guest", session(AccountRole.MEMBER, MemberStatus.GUEST))

    @Test fun board() = check("nodup-board", session(AccountRole.BOARD))

    @Test fun treasurer() = check("nodup-treasurer", session(AccountRole.TREASURER))

    @Test fun admin() = check("nodup-admin", session(AccountRole.ADMIN))
}
