package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.75: "Passwort ändern" carries the key, the exit button keeps its danger outline. V1.9.220: both now live on "Meine Daten"
 * (this test mounts the two blocks directly), and the dashboard itself offers none of the account actions any more -- only a sentence with a link to
 * "Meine Daten". Signing out is in the account menu of the navbar.
 */
class DashboardAccountActionsDomTest {
    private fun HTMLElement.buttons(): List<HTMLElement> =
        (0 until querySelectorAll("button").length).map { querySelectorAll("button").item(it) as HTMLElement }

    private fun session() =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Test",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @Test
    fun leaveMembership_keepsItsDangerOutline_underItsOwnHeading(): Promise<Unit> =
        formTest {
            mountedForm("account-leave") { root, element ->
                renderLeaveMembership(root)
                val exit = element().buttons().first { it.textContent?.trim()?.startsWith("Austritt") == true }
                assertTrue(exit.classList.contains("btn-outline-danger"), "Austritt keeps its danger outline")
                val headings =
                    (
                        0 until
                            element()
                                .querySelectorAll(
                                    "h2",
                                ).length
                    ).map { element().querySelectorAll("h2").item(it)!!.textContent }
                assertEquals(listOf("Mitgliedschaft beenden"), headings)
            }
        }

    @Test
    fun changePassword_hasTheKey_andNoHorizontalOverflowAt360px(): Promise<Unit> =
        formTest {
            mountedForm("dashboard-change-password") { root, element ->
                element().style.width = "360px"
                element().style.boxSizing = "border-box"
                renderChangePassword(root)
                renderLeaveMembership(root)
                val save = assertNotNull(element().buttons().firstOrNull { it.textContent?.trim() == "Passwort ändern" }, "no save button")
                assertNotNull(save.querySelector("i.fa-key"), "no key symbol: ${save.outerHTML}")
                assertTrue(save.classList.contains("btn-primary"))
                assertTrue(
                    element().scrollWidth <= element().clientWidth,
                    "horizontal overflow at 360 px: ${element().scrollWidth} > ${element().clientWidth}",
                )
            }
        }

    @Test
    fun theDashboard_hasNoPassword_noCard_noExit_noLogout_butOneLinkToMeineDaten(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withFetchStub {
                mountedForm("dashboard-no-account-actions") { root, element ->
                    renderDashboardScreen(
                        root,
                        OnlineCountsRpc { error("unused") },
                        WindowOnlineCountsScheduler,
                        DashboardSignalsRpc { emptyList() },
                    )
                    val el = element()
                    assertEquals(0, el.querySelectorAll("input[type=password]").length, "no password field")
                    val names = el.buttons().map { it.textContent?.trim().orEmpty() }
                    assertFalse(
                        names.any { it.startsWith("Austritt") || it == "Abmelden" || it.contains("Mitgliedsausweis") },
                        "buttons: $names",
                    )
                    val toMeineDaten = el.querySelectorAll("a[href='#${Routes.DSGVO_RIGHTS}']").length
                    assertEquals(1, toMeineDaten, "exactly one link to Meine Daten")
                }
            }
        }
}
