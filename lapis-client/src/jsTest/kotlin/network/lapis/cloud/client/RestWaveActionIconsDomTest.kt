package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.50 -- rule R57 / R36 for the buttons the tripwire S2-S4 found outside the earlier groups. The accessible names stay as they were
 * (the label is the visible text, the icon is `aria-hidden`); a create entry that opens a dialog sits in the title row like every other
 * create entry. The full list of converted buttons is the tripwire's own finding list (`ClientToolbarIconTripwireTest`, ledger R57_LEDGER).
 */
class RestWaveActionIconsDomTest {
    private fun session() =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    @Test
    fun theFamilyCreateButton_isThePrimaryActionOfTheTitleRow_withThePlus(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) {
                mountedForm("r50-icons-families") { root, element ->
                    renderMemberFamiliesScreen(root, null)
                    awaitUntil("the page header is built") { element().querySelector(".lapis-page-header") != null }
                    val slotButtons = element().allOf(".lapis-page-action button")
                    assertEquals(listOf("Familie anlegen"), slotButtons.map { it.textContent.orEmpty().trim() })
                    assertActionIcon(slotButtons.single(), "Familie anlegen", "fa-plus")
                    assertTrue(
                        element().allOf(".lapis-toolbar button").none { it.textContent?.trim() == "Familie anlegen" },
                        "no create button left in the search toolbar",
                    )
                }
            }
        }
}
