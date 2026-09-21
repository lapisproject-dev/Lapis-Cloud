package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.31 (W5): the screens with a special header decision render exactly ONE `h1` per state (R6), with the
 * constant title in it and the data value in the subtitle. Driven through the real screen functions in a mounted root
 * with a stubbed `window.fetch` (every RPC answers `null`, so no screen ever shows data -- the header stands regardless).
 */
class PageHeaderScreensDomTest {
    @AfterTest
    fun reset() {
        AppState.setSession(null)
        PageFocus.consume()
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private fun session(role: AccountRole = AccountRole.ADMIN) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Testperson",
            role = role,
            expiresAt = LocalDateTime(2026, 9, 8, 12, 0),
        )

    private fun HTMLElement.h1Texts(): List<String> =
        (0 until querySelectorAll("h1").length).map { querySelectorAll("h1").item(it)!!.textContent.orEmpty() }

    private suspend fun assertOneH1(
        id: String,
        expectedTitle: String,
        render: (Root) -> Unit,
    ) {
        withFetchStub {
            withMountedRoot(id) { root, element ->
                render(root)
                delay(50)
                assertEquals(listOf(expectedTitle), element().h1Texts(), "$id: exactly one h1 with the constant title")
            }
        }
    }

    @Test
    fun login_hasOneH1(): Promise<Unit> = test { assertOneH1("ph-login", "Anmelden") { renderLoginScreen(it) } }

    @Test
    fun registration_hasOneH1(): Promise<Unit> = test { assertOneH1("ph-reg", "Mitglied werden") { renderRegistrationScreen(it) } }

    @Test
    fun friendRegistration_hasOneH1(): Promise<Unit> =
        test { assertOneH1("ph-friend", "Freund-Konto anlegen") { renderFriendRegistrationScreen(it) } }

    @Test
    fun verifyEmail_withoutToken_hasOneH1(): Promise<Unit> =
        test { assertOneH1("ph-verify", "E-Mail-Adresse bestätigen") { renderVerifyEmailScreen(it, null) } }

    @Test
    fun passwordReset_withoutToken_hasOneH1(): Promise<Unit> =
        test { assertOneH1("ph-reset", "Neues Passwort setzen") { renderPasswordResetScreen(it, null) } }

    @Test
    fun dashboard_titleIsTheSidebarName_greetingIsTheSubtitle(): Promise<Unit> =
        test {
            AppState.setSession(session())
            withFetchStub {
                withMountedRoot("ph-dashboard") { root, element ->
                    renderDashboardScreen(root)
                    assertEquals(listOf("Dashboard"), element().h1Texts())
                    assertTrue(element().textContent.orEmpty().contains("Willkommen, Testperson"))
                }
            }
        }

    @Test
    fun memberFinancialHistory_ownAndOther_haveOneH1Each(): Promise<Unit> =
        test {
            AppState.setSession(session())
            assertOneH1("ph-history-self", "Ihre Beitragshistorie") { renderMemberFinancialHistoryScreen(it, null) }
            assertOneH1("ph-history-other", "Beitragshistorie") { renderMemberFinancialHistoryScreen(it, "member-2") }
        }

    @Test
    fun eventCheckIn_titleIsConstant_offlineBandAboveIt(): Promise<Unit> =
        test {
            AppState.setSession(session())
            withFetchStub {
                withMountedRoot("ph-checkin") { root, element ->
                    renderEventCheckInScreen(root, "event-1")
                    delay(50)
                    assertEquals(listOf("Veranstaltungs-Check-in"), element().h1Texts())
                    val text = element().textContent.orEmpty()
                    assertTrue(
                        text.indexOf("Keine Verbindung") < text.indexOf("Veranstaltungs-Check-in"),
                        "the offline band stands ABOVE the title (R37)",
                    )
                }
            }
        }

    @Test
    fun memberHonors_titleIsConstant(): Promise<Unit> =
        test {
            AppState.setSession(session())
            assertOneH1("ph-honors", "Ehrungen & Auszeichnungen") { renderMemberHonorsScreen(it, "member-2") }
        }

    @Test
    fun accountingExportView_isEmbedded_soItBuildsNoH1_butKeepsItsH2(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.TREASURER))
            withFetchStub {
                withMountedRoot("ph-export") { root, element ->
                    renderAccountingExportView(root)
                    assertEquals(0, element().querySelectorAll("h1").length, "the surrounding screen owns the h1")
                    val h2 = assertNotNull(element().querySelector("h2"))
                    assertTrue(h2.classList.contains("h5"), "section titles are .h5 (R7)")
                }
            }
        }

    @Test
    fun conferenceLobby_hasOneH1_andAReRenderKeepsAnAlreadyMarkedTitle(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.MEMBER))
            withFetchStub {
                withMountedRoot("ph-conf") { root, element ->
                    renderConferenceScreen(root)
                    delay(50)
                    assertEquals(listOf("Videokonferenz"), element().h1Texts())
                    // The recording marker is written by `ConferenceScreen` on top of the header's title; a header
                    // built afterwards (a re-render) simply writes the plain title again -- no prefix survives it.
                    kotlinx.browser.document.title = "● ${kotlinx.browser.document.title}"
                    root.pageHeader("Videokonferenz")
                    assertEquals("Videokonferenz – ${Branding.title}", kotlinx.browser.document.title)
                }
            }
        }
}
