package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the two dunning configuration screens (found beyond the debt ledger: their "Mahnstufe anlegen" form was always
 * visible under the list). The form now sits behind "Neue Mahnstufe" in the title row of "Mahnstufen"; the edit modal is unchanged.
 */
class RestGroupFormsDunningDomTest {
    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private suspend fun checkScreen(
        id: String,
        formId: String,
        render: (io.kvision.panel.Root) -> Unit,
    ) {
        withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
            mountedForm(id) { root, element ->
                render(root)
                awaitUntil("the empty level list") { element().shows("Noch keine Mahnstufen konfiguriert.") }
                val screen = element()
                assertEquals(listOf("Neue Mahnstufe"), screen.createButtonLabels())
                assertFalse(screen.hostOpen(formId), "collapsed after the load")
                assertFalse(screen.shows("Mahnstufe anlegen"), "no always-visible create section any more")

                val host = openCreateForm(screen, formId)
                assertTrue(host.shows("Stufennummer"))
                pressEscape(host)
                awaitUntil("closed without a question") { !screen.hostOpen(formId) }

                val reopened = openCreateForm(screen, formId)
                reopened.buttonNamed("Mahnstufe anlegen").click()
                awaitUntil("the fields report the missing values") { reopened.shownErrors().isNotEmpty() }
                assertTrue(screen.hostOpen(formId), "a validation error never folds the form back")

                reopened.typeInto("Name", "Erste Erinnerung")
                pressEscape(reopened)
                answerDiscardDialog("Weiter bearbeiten")
                assertTrue(screen.hostOpen(formId), "'Weiter bearbeiten' keeps the typed form")
                reopened.buttonNamed("Abbrechen").click()
                answerDiscardDialog("Verwerfen")
                awaitUntil("closed after discarding") { !screen.hostOpen(formId) }
                assertEquals(1, screen.createButtonLabels().size, "still exactly one button")
            }
        }
    }

    @Test
    fun memberDues_dunningLevelsFormIsCollapsedBehindOneButton(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            checkScreen("r49-dunning-dues", "dunning-level-create") { renderDunningSettingsScreen(it) }
        }

    @Test
    fun receivables_dunningLevelsFormIsCollapsedBehindOneButton(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            checkScreen("r49-dunning-receivables", "receivable-dunning-level-create") { renderReceivableDunningSettingsScreen(it) }
        }
}
