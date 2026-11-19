package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CLOSED_TEXT =
    "Ein Online-Mitgliedsantrag ist auf dieser Plattform nicht möglich. Bitte wenden Sie sich direkt an die Organisation."
private const val REGISTER_LINK = "Noch kein Konto? Jetzt Mitglied werden."
private const val FRIEND_PAGE_LINK = "Doch lieber Mitglied werden? Zum Beitrittsantrag."

private fun brand(
    enabled: Boolean,
    keycloakMode: Boolean = false,
) {
    document.getElementById("lapis-brand")?.remove()
    val script = document.createElement("script") as HTMLScriptElement
    script.type = "application/json"
    script.id = "lapis-brand"
    script.textContent =
        """{"title":"X","logoUrl":null,"keycloakMode":$keycloakMode,"emergencyAdminLoginEnabled":false,"membershipApplicationEnabled":$enabled}"""
    document.head?.appendChild(script)
}

/** V1.9.315 -- the screens follow the switch "Mitglied werden": links hidden, the registration form replaced by one fixed text. */
class MembershipApplicationSwitchDomTest {
    @AfterTest
    fun cleanup() {
        document.getElementById("lapis-brand")?.remove()
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun HTMLElement.hasLinkNamed(text: String): Boolean = allOf("a").any { it.textContent?.trim() == text }

    @Test
    fun loginScreen_showsTheMemberLink_onlyWhileTheApplicationIsOpen(): Promise<Unit> =
        test {
            withFetchStub {
                brand(enabled = true)
                withMountedRoot("switch-login-on") { root, element ->
                    renderLoginScreen(root)
                    assertTrue(element().hasLinkNamed(REGISTER_LINK))
                }
                brand(enabled = false)
                withMountedRoot("switch-login-off") { root, element ->
                    renderLoginScreen(root)
                    assertFalse(element().hasLinkNamed(REGISTER_LINK))
                    assertTrue(element().hasLinkNamed("Nur an einer Videokonferenz teilnehmen? Freund-Konto anlegen."), "friend link stays")
                }
                brand(enabled = true, keycloakMode = true)
                withMountedRoot("switch-login-keycloak") { root, element ->
                    renderLoginScreen(root)
                    assertFalse(element().hasLinkNamed(REGISTER_LINK))
                }
            }
        }

    @Test
    fun friendScreen_dropsTheMemberLink_whenClosed_butKeepsTheForm(): Promise<Unit> =
        test {
            val termsRoute = routeOf { rpcService<IRegistrationService>().getFriendTerms() }
            val termsJson = """{"version":"f3","text":"Freund-Bedingungen","sha256":"sha-terms-abc"}"""
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == termsRoute -> request.answerWith(termsJson)
                        else -> request.answerWith("null")
                    }
                },
            ) {
                brand(enabled = false)
                withMountedRoot("switch-friend-off") { root, element ->
                    renderFriendRegistrationScreen(root)
                    awaitUntil("the friend form") { element().querySelector("input[type=email]") != null }
                    assertFalse(element().hasLinkNamed(FRIEND_PAGE_LINK))
                    assertTrue(element().hasLinkNamed("Bereits ein Konto? Zur Anmeldung."))
                }
                brand(enabled = true)
                withMountedRoot("switch-friend-on") { root, element ->
                    renderFriendRegistrationScreen(root)
                    awaitUntil("the friend form") { element().querySelector("input[type=email]") != null }
                    assertTrue(element().hasLinkNamed(FRIEND_PAGE_LINK))
                }
            }
        }

    @Test
    fun registrationScreen_closedUpFront_showsTheFixedText_andMakesNoRpcCall(): Promise<Unit> =
        test {
            brand(enabled = false)
            withFetchStub { requests ->
                withMountedRoot("switch-registration-off") { root, element ->
                    renderRegistrationScreen(root)
                    val screen = element()
                    assertTrue(screen.textContent.orEmpty().contains(CLOSED_TEXT))
                    assertTrue(screen.hasLinkNamed("Zur Anmeldung"))
                    assertEquals(0, screen.querySelectorAll("input").length, "no form")
                    assertFalse(screen.hasLinkNamed(FRIEND_PAGE_LINK))
                    assertFalse(screen.textContent.orEmpty().contains("Keycloak"), "no reason is given")
                    assertEquals(0, requests.count { it.isRpc }, "no RPC call at all")
                }
            }
        }

    @Test
    fun registrationScreen_serverRefusesTheAgreement_showsTheSameText_withoutAToast(): Promise<Unit> =
        test {
            brand(enabled = true)
            val agreementRoute = routeOf { rpcService<IRegistrationService>().getMembershipAgreement() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == agreementRoute ->
                            serviceExceptionResult(
                                request.json.id as Int,
                                "network.lapis.cloud.shared.rpc.MembershipApplicationClosedException",
                            )
                        else -> request.answerWith("null")
                    }
                },
            ) {
                withMountedRoot("switch-registration-refused") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the closed text") { element().textContent.orEmpty().contains(CLOSED_TEXT) }
                    assertNotNull(element().allOf("a").firstOrNull { it.textContent?.trim() == "Zur Anmeldung" })
                    assertEquals(0, element().querySelectorAll("input").length)
                }
            }
        }
}
