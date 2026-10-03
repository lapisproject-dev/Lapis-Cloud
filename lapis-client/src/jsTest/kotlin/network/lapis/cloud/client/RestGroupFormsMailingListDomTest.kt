package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMailingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the mailing lists: "Neue Mailingliste" is one collapsed form behind the title-row button of the admin section
 * "Mailinglisten verwalten" (BOARD/ADMIN). After saving, the new list is pre-selected in the manage selector. The list detail ("Verwalten")
 * with its message form is a documented exception: it only exists behind an explicit click.
 */
class RestGroupFormsMailingListDomTest {
    private val formId = "mailing-list-create"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun list(
        id: String,
        name: String,
    ) = MailingListDto(
        id = id,
        name = name,
        description = null,
        createdBy = "caller-1",
        subscriberCount = 0,
        isSubscribedByCurrentMember = false,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    @Test
    fun aMember_getsNoAdminSection_aBoardMemberGetsOneCollapsedButton(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                AppState.setSession(session(AccountRole.MEMBER))
                mountedForm("r49-mailing-member") { root, element ->
                    renderCommunicationScreen(root)
                    awaitUntil("the self-service section") { element().shows("Mailinglisten") }
                    assertFalse(element().shows("Mailinglisten verwalten"))
                    assertEquals(emptyList(), element().createButtonLabels())
                }
                AppState.setSession(session(AccountRole.BOARD))
                mountedForm("r49-mailing-board") { root, element ->
                    renderCommunicationScreen(root)
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    assertFalse(element().hostOpen(formId), "collapsed")
                    val host = openCreateForm(element(), formId)
                    assertFalse(host.shows("Neue Mailingliste anlegen"), "the old bold title line is gone, the button carries the meaning")
                    pressEscape(host)
                    awaitUntil("closed without a question") { !element().hostOpen(formId) }
                }
            }
        }

    @Test
    fun aTypedForm_asksBeforeDiscarding(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                mountedForm("r49-mailing-typed") { root, element ->
                    renderCommunicationScreen(root)
                    awaitUntil("the admin section") { element().createButtonLabels() == listOf("Neue Mailingliste") }
                    val host = openCreateForm(element(), formId)
                    host.typeInto("Name", "Vorstand")
                    pressEscape(host)
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(element().hostOpen(formId))
                    host.buttonNamed("Abbrechen").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("closed after discarding") { !element().hostOpen(formId) }
                }
            }
        }

    @Test
    fun aSavedList_foldsBack_reloadsBothPlaces_andIsPreselected(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val lists = routeOf { rpcService<IMailingService>().listMailingLists() }
            val create = routeOf { rpcService<IMailingService>().createMailingList("n", null) }
            val rows = mutableListOf(list("old", "Altliste"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == lists -> request.answerWith(jsonOf(ListSerializer(MailingListDto.serializer()), rows.toList()))
                        request.rpcRoute == create -> {
                            rows += list("new-1", "Vorstand")
                            request.answerWith(jsonOf(MailingListDto.serializer(), rows.last()))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-mailing-save") { root, element ->
                    renderCommunicationScreen(root)
                    awaitUntil("the old list in the self-service section") { element().shows("Altliste") }
                    val host = openCreateForm(element(), formId)
                    host.typeInto("Name", "Vorstand")
                    val before = calls.toRoute(lists).size
                    host.buttonNamed("Anlegen").click()
                    awaitUntil("the create call") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !element().hostOpen(formId) }
                    awaitUntil("both places reloaded the lists") { calls.toRoute(lists).size >= before + 2 }
                    awaitUntil("the new list is pre-selected in the manage selector") {
                        (element().controlOf("Mailingliste") as? HTMLSelectElement)?.value == "new-1"
                    }
                    assertEquals(1, element().createButtonLabels().size)
                }
            }
        }
}
