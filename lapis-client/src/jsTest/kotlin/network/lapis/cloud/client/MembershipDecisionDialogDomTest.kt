package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.rpc.DecisionNotificationOutcome
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.118 -- the decision dialogs of the pending applications: the notification tick box (on by default), the mail-language
 * choice (only while ticked, default German), the reworded reason hint, and the toast text per notification outcome.
 */
class MembershipDecisionDialogDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            closeOpenModals(timeoutMs = 300)
            try {
                block()
            } finally {
                closeOpenModals(timeoutMs = 400)
            }
        }

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    private fun lastModal(): HTMLElement {
        val modals = document.querySelectorAll(".modal")
        return assertNotNull(modals.item(modals.length - 1) as? HTMLElement, "no modal")
    }

    private fun HTMLElement.tickBox(): HTMLInputElement =
        assertNotNull(querySelector("input[type=checkbox]") as? HTMLInputElement, "no tick box")

    private fun HTMLElement.languageSelect(): HTMLSelectElement {
        val label =
            all("label").first {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith("Sprache der E-Mail")
            }
        return assertNotNull(document.getElementById(label.getAttribute("for").orEmpty()) as? HTMLSelectElement, "no language select")
    }

    @Test
    fun approveDialog_defaultsToNotifyInGerman(): Promise<Unit> =
        test {
            withMountedRoot("decision-approve-default") { _, _ ->
                var got: Pair<Boolean, String>? = null
                approveApplicationDialog("Kofi") { notify, language -> got = notify to language }
                val modal = lastModal()
                assertTrue(modal.tickBox().checked, "the tick box is on by default")
                assertEquals("de", modal.languageSelect().value)
                assertTrue(modal.textContent.orEmpty().contains("kurze Standardnachricht mit Anmeldelink"))
                modal.button("Annehmen").click()
                assertEquals(true to "de", got)
                closeOpenModals(timeoutMs = 1000)
            }
        }

    @Test
    fun approveDialog_passesTheChosenLanguage(): Promise<Unit> =
        test {
            withMountedRoot("decision-approve-lang") { _, _ ->
                var got: Pair<Boolean, String>? = null
                approveApplicationDialog("Kofi") { notify, language -> got = notify to language }
                val modal = lastModal()
                val select = modal.languageSelect()
                select.value = "pl"
                select.dispatchEvent(Event("change"))
                modal.button("Annehmen").click()
                assertEquals(true to "pl", got)
                closeOpenModals(timeoutMs = 1000)
            }
        }

    @Test
    fun approveDialog_unticking_hidesTheLanguageAndSendsNotifyFalse(): Promise<Unit> =
        test {
            withMountedRoot("decision-approve-off") { _, _ ->
                var got: Pair<Boolean, String>? = null
                approveApplicationDialog("Kofi") { notify, language -> got = notify to language }
                val modal = lastModal()
                val select = modal.languageSelect()
                assertNotNull(modal.languageSelect(), "the language choice is shown while ticked")
                modal.tickBox().click()
                assertFalse(modal.tickBox().checked)
                awaitUntil(
                    "the language choice is gone when not ticked",
                    timeoutMs = 600,
                ) {
                    modal.all("label").none {
                        it.textContent
                            .orEmpty()
                            .trim()
                            .startsWith("Sprache der E-Mail")
                    }
                }

                modal.button("Annehmen").click()
                assertEquals(false, got?.first)
                assertEquals("de", got?.second)
                closeOpenModals(timeoutMs = 1000)
            }
        }

    @Test
    fun rejectDialog_hasTheNewHintAndTheSameControls(): Promise<Unit> =
        test {
            withMountedRoot("decision-reject") { _, _ ->
                var got: Triple<String, Boolean, String>? = null
                rejectApplicationDialog("Kofi") { reason, notify, language -> got = Triple(reason, notify, language) }
                val modal = lastModal()
                val text = modal.textContent.orEmpty()
                assertTrue(text.contains("Er wird nur intern gespeichert und nicht an die Person gesendet."))
                assertFalse(text.contains("wird beim Mitglied gespeichert"))
                assertTrue(text.contains("kurze, neutrale Nachricht ohne Begründung"))
                assertTrue(modal.tickBox().checked)
                val reason = modal.all("textarea").first() as HTMLTextAreaElement
                reason.value = "  Passt nicht  "
                reason.dispatchEvent(Event("input"))
                reason.dispatchEvent(Event("blur"))
                val select = modal.languageSelect()
                select.value = "fr"
                select.dispatchEvent(Event("change"))
                modal.button("Ablehnen").click()
                assertEquals(Triple("Passt nicht", true, "fr"), got)
                closeOpenModals(timeoutMs = 1000)
            }
        }

    @Test
    fun rejectDialog_untickedSendsNotifyFalse(): Promise<Unit> =
        test {
            withMountedRoot("decision-reject-off") { _, _ ->
                var got: Triple<String, Boolean, String>? = null
                rejectApplicationDialog("Kofi") { reason, notify, language -> got = Triple(reason, notify, language) }
                val modal = lastModal()
                val reason = modal.all("textarea").first() as HTMLTextAreaElement
                reason.value = "Grund"
                reason.dispatchEvent(Event("input"))
                reason.dispatchEvent(Event("blur"))
                modal.tickBox().click()
                modal.button("Ablehnen").click()
                assertEquals(Triple("Grund", false, "de"), got)
                closeOpenModals(timeoutMs = 1000)
            }
        }

    @Test
    fun toastText_perOutcome(): Promise<Unit> =
        test {
            val name = "Kofi"

            fun approved(o: DecisionNotificationOutcome) = decisionToast(name, o, approved = true)

            fun rejected(o: DecisionNotificationOutcome) = decisionToast(name, o, approved = false)
            assertEquals(DecisionToastKind.SUCCESS, approved(DecisionNotificationOutcome.QUEUED).kind)
            assertEquals(
                "Kofi wurde aufgenommen. Die Benachrichtigung ist unterwegs.",
                approved(DecisionNotificationOutcome.QUEUED).message,
            )
            assertEquals("Kofi wurde aufgenommen.", approved(DecisionNotificationOutcome.NOT_REQUESTED).message)
            assertEquals(DecisionToastKind.SUCCESS, approved(DecisionNotificationOutcome.NOT_REQUESTED).kind)
            assertEquals("Kofi wurde abgelehnt.", rejected(DecisionNotificationOutcome.NOT_REQUESTED).message)
            assertEquals(DecisionToastKind.INFO, rejected(DecisionNotificationOutcome.NOT_REQUESTED).kind)
            assertEquals(DecisionToastKind.INFO, rejected(DecisionNotificationOutcome.QUEUED).kind)
            listOf(
                DecisionNotificationOutcome.MAIL_DISABLED,
                DecisionNotificationOutcome.NO_VALID_ADDRESS,
                DecisionNotificationOutcome.FAILED,
            ).forEach { o ->
                assertEquals(DecisionToastKind.WARNING, approved(o).kind, "approved $o")
                assertEquals(DecisionToastKind.WARNING, rejected(o).kind, "rejected $o")
                assertTrue(approved(o).message.startsWith("Kofi wurde aufgenommen. "))
                assertTrue(rejected(o).message.startsWith("Kofi wurde abgelehnt. "))
            }
            assertTrue(approved(DecisionNotificationOutcome.FAILED).message.contains("konnte nicht versendet werden"))
            assertTrue(approved(DecisionNotificationOutcome.NO_VALID_ADDRESS).message.contains("keine gültige E-Mail-Adresse"))
            assertTrue(approved(DecisionNotificationOutcome.MAIL_DISABLED).message.contains("kein E-Mail-Versand eingerichtet"))
        }
}
