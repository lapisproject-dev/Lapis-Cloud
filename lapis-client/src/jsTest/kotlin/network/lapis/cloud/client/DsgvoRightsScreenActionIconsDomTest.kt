package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.js.Promise
import kotlin.test.Test

/**
 * V1.9.50 -- rule R57 on "Meine Daten": the two verb buttons of the screen itself ("Auskunftsübersicht anzeigen" = VIEW, "Löschung
 * beantragen" = SEND) carry their icon and keep their accessible name. The cards (photo, public profile, KI access, address, card revoke)
 * have their own icon tests next to their other DOM tests.
 */
class DsgvoRightsScreenActionIconsDomTest {
    @Test
    fun theTwoVerbButtonsOfTheScreen_carryTheirIcons(): Promise<Unit> =
        formTest {
            AppState.setSession(
                SessionInfoDto(
                    memberId = "member-1",
                    displayName = "Dana Keller",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.ACTIVE,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) {
                mountedForm("r50-icons-dsgvo") { root, element ->
                    renderDsgvoRightsScreen(root)
                    awaitUntil("the self-service section is built") {
                        element().textContent.orEmpty().contains("Löschung beantragen")
                    }
                    assertButtonIcon(element(), "Auskunftsübersicht anzeigen", "fa-eye")
                    assertButtonIcon(element(), "Löschung beantragen", "fa-paper-plane")
                }
            }
        }
}
