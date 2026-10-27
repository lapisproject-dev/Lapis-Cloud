package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.MailingListNotDeletableException
import network.lapis.cloud.shared.rpc.MailingListSendingInProgressException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.234 -- "Mailingliste loeschen": a trash button in the detail of a MANUAL list only, a confirmation that names the consequences with
 * the focus on "Abbrechen", exactly one RPC on confirming, none on cancelling, and the fixed texts of the two typed refusals.
 */
class MailingListDeleteDomTest {
    private fun list(
        name: String = "Rundbrief",
        kind: MailingListKind = MailingListKind.MANUAL,
        subscribers: Int = 3,
    ) = MailingListDto(
        id = "list-1",
        name = name,
        description = null,
        createdBy = "x",
        subscriberCount = subscribers,
        isSubscribedByCurrentMember = false,
        kind = kind,
        ruleJson = if (kind == MailingListKind.DYNAMIC) """{"v":1,"op":"ALL","conditions":[]}""" else null,
    )

    private fun scenario(
        id: String,
        dto: MailingListDto,
        check: suspend (root: () -> HTMLElement, deleteCalls: () -> Int, deleted: () -> Int) -> Unit,
    ): Promise<Unit> =
        formTest {
            val deleteRoute = routeOf { rpcService<IMailingService>().deleteMailingList("x") }
            // a Unit-returning RPC answers with an empty object
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("{}") else StubResponse() }) { calls ->
                // dialogs of earlier scenarios stay in the document; a stale one must never answer for this scenario
                document.querySelectorAll(".modal, .modal-backdrop").let { stale ->
                    (0 until stale.length).forEach { stale.item(it)?.let { n -> n.parentNode?.removeChild(n) } }
                }
                mountedForm(id) { root, element ->
                    var deleted = 0
                    renderMailingListDetail(root, dto, refreshSelfService = {}, onDeleted = { deleted++ })
                    delay(150)
                    check(element, { calls.count { it.isRpc && it.rpcRoute == deleteRoute } }, { deleted })
                }
            }
        }

    private fun modalText(): String = runCatching { lastOpenModal().textContent.orEmpty() }.getOrDefault("")

    @Test
    fun aManualList_hasATrashButtonWithAnAccessibleName_andNoAutomaticHint(): Promise<Unit> =
        scenario("mld-button", list()) { el, _, _ ->
            val button = el().iconButtonNamed("Mailingliste löschen")
            assertTrue(button.innerHTML.contains("fa-trash"), button.innerHTML)
            assertEquals("Mailingliste löschen", button.getAttribute("title"))
            assertFalse(el().textContent.orEmpty().contains("Automatische Mailinglisten können nicht gelöscht werden."))
        }

    @Test
    fun anAutomaticList_hasNoDeleteButton_butTheReason(): Promise<Unit> =
        scenario("mld-dynamic", list(kind = MailingListKind.DYNAMIC)) { el, _, _ ->
            assertTrue(el().allOf("button").none { it.getAttribute("aria-label") == "Mailingliste löschen" })
            assertTrue(el().textContent.orEmpty().contains("Automatische Mailinglisten können nicht gelöscht werden."))
        }

    @Test
    fun theConfirmation_namesTheConsequences_putsTheFocusOnCancel_andSendsNothingYet(): Promise<Unit> =
        scenario("mld-confirm", list(subscribers = 3)) { el, deleteCalls, _ ->
            el().iconButtonNamed("Mailingliste löschen").click()
            awaitUntil("the dialog") { modalText().contains("Mailingliste löschen?") }
            val text = modalText()
            assertTrue(text.contains("Die Mailingliste „Rundbrief“ wird gelöscht."), text)
            assertTrue(text.contains("3 Abonnenten werden ausgetragen."), text)
            assertTrue(text.contains("Entwürfe dieser Liste werden mit ihren Anhängen gelöscht."), text)
            assertTrue(text.contains("Bereits versendete Nachrichten bleiben als Nachweis erhalten."), text)
            assertTrue(text.contains("Das kann nicht rückgängig gemacht werden."), text)
            assertFalse(text.contains("###"), "no tr() marker on screen")
            awaitUntil("the focus is on Abbrechen") { (document.activeElement as? HTMLElement)?.textContent?.trim() == "Abbrechen" }
            assertEquals(0, deleteCalls())
        }

    @Test
    fun oneSubscriber_isSaidInTheSingular(): Promise<Unit> =
        scenario("mld-singular", list(subscribers = 1)) { el, _, _ ->
            el().iconButtonNamed("Mailingliste löschen").click()
            awaitUntil("the dialog") { modalText().contains("Mailingliste löschen?") }
            assertTrue(modalText().contains("1 Abonnent wird ausgetragen."), modalText())
        }

    @Test
    fun cancelling_callsNoRpc(): Promise<Unit> =
        scenario("mld-cancel", list()) { el, deleteCalls, deleted ->
            el().iconButtonNamed("Mailingliste löschen").click()
            awaitUntil("the dialog") { modalText().contains("Mailingliste löschen?") }
            lastOpenModal().buttonNamed("Abbrechen").click()
            delay(200)
            assertEquals(0, deleteCalls())
            assertEquals(0, deleted())
        }

    @Test
    fun confirming_callsTheRpcExactlyOnce_andReportsTheDeletion(): Promise<Unit> =
        scenario("mld-delete", list()) { el, deleteCalls, deleted ->
            el().iconButtonNamed("Mailingliste löschen").click()
            awaitUntil("the dialog") { modalText().contains("Mailingliste löschen?") }
            lastOpenModal().buttonNamed("Löschen").click()
            awaitUntil("the RPC was sent") { deleteCalls() == 1 }
            awaitUntil("the screen was told") { deleted() == 1 }
            delay(100)
            assertEquals(1, deleteCalls())
            assertEquals(1, deleted())
        }

    @Test
    fun aHostileListName_isShownAsText_neverAsMarkupOrMarker(): Promise<Unit> =
        scenario("mld-hostile", list(name = "<img src=x onerror=alert(1)> ###KvI18nS###Abbrechen %1")) { el, _, _ ->
            el().iconButtonNamed("Mailingliste löschen").click()
            awaitUntil("the dialog") { modalText().contains("Mailingliste löschen?") }
            assertEquals(0, lastOpenModal().allOf("img").size)
            assertTrue(modalText().contains("<img src=x onerror=alert(1)>"), modalText())
        }

    @Test
    fun theTwoTypedRefusals_haveTheirOwnFixedTexts(): Promise<Unit> =
        formTest {
            fun text(value: String?) = assertNotNull(value).removePrefix("###KvI18nS###")
            assertEquals(
                "Automatische Mailinglisten können nicht gelöscht werden.",
                text(typedRefusalText(MailingListNotDeletableException())),
            )
            assertEquals(
                "Diese Mailingliste wird gerade versendet. Bitte löschen Sie sie erst, wenn der Versand abgeschlossen ist.",
                text(typedRefusalText(MailingListSendingInProgressException())),
            )
        }
}
