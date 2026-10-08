package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.72: "Für alle beenden" sits next to "Verlassen", so the confirmation dialog is the slip protection. A real Bootstrap modal in a
 * mounted root (transitions off, see [disableModalTransitions]): the focus starts on "Abbrechen", nothing but a click on the confirming
 * button reaches `onConfirm`, a double click fires once, a second trigger while the dialog is open opens no second dialog.
 *
 * Limit (honest): Karma tests the extracted building blocks ([endRoomConfirmDialog], [EndRoomDialogGuard]), not the whole call screen --
 * its wiring is pinned by `ClientToolbarIconTripwireTest` and the production Webpack build. Safari / iOS / real devices are not covered.
 */
class EndRoomConfirmDialogDomTest {
    private class Calls {
        var confirmed = 0
        var closedWithoutConfirm = 0
        var closed = 0
    }

    private fun open(calls: Calls) =
        endRoomConfirmDialog(
            roomTitle = "Vorstandssitzung",
            onClosedWithoutConfirm = { calls.closedWithoutConfirm++ },
            onClosed = { calls.closed++ },
            onConfirm = { calls.confirmed++ },
        )

    private fun openModals(): List<HTMLElement> =
        document.querySelectorAll(".modal").let { l ->
            (0 until l.length).map {
                l.item(it) as HTMLElement
            }
        }

    private suspend fun awaitFocusOnCancel(modal: HTMLElement) {
        awaitUntil(
            "the focus is on 'Abbrechen'",
            detail = { "active=${(document.activeElement as? HTMLElement)?.textContent?.trim()}" },
        ) { (document.activeElement as? HTMLElement)?.textContent?.trim() == "Abbrechen" && modal.contains(document.activeElement) }
    }

    @Test
    fun opening_doesNotConfirm_andTheFocusStartsOnAbbrechen(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-open") { _, _ ->
                val calls = Calls()
                open(calls)
                val modal = lastOpenModal()
                assertEquals(0, calls.confirmed, "opening confirms nothing")
                awaitFocusOnCancel(modal)
                // the confirming button never carries the focus or an autofocus
                val confirm = modal.buttonNamed("Für alle beenden")
                assertNull(confirm.getAttribute("autofocus"))
                assertTrue(document.activeElement != confirm)
                // the consequence is named in words
                assertTrue(modal.textContent.orEmpty().contains("Vorstandssitzung"))
            }
        }

    @Test
    fun cancel_neverConfirms_andReportsAClosedWithoutConfirm(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-cancel") { _, _ ->
                val calls = Calls()
                open(calls)
                // wait until Bootstrap has shown the dialog (it ignores hide() before that, a person never clicks that fast)
                awaitFocusOnCancel(lastOpenModal())
                lastOpenModal().buttonNamed("Abbrechen").click()
                awaitUntil("the dialog reported its close") { calls.closed == 1 }
                assertEquals(0, calls.confirmed)
                assertEquals(1, calls.closedWithoutConfirm, "the screen returns the focus to the control then")
            }
        }

    @Test
    fun escape_neverConfirms(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-escape") { _, _ ->
                val calls = Calls()
                open(calls)
                val modal = lastOpenModal()
                awaitFocusOnCancel(modal)
                modal.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", cancelable = true, bubbles = true)))
                awaitUntil("Escape closed the dialog") { calls.closed == 1 }
                assertEquals(0, calls.confirmed)
                assertEquals(1, calls.closedWithoutConfirm)
            }
        }

    @Test
    fun aClickOnTheBackdrop_neverConfirms(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-backdrop") { _, _ ->
                val calls = Calls()
                open(calls)
                val modal = lastOpenModal()
                awaitFocusOnCancel(modal)
                // Bootstrap: the pointer must go down AND up on the .modal element itself (not on the dialog inside it)
                modal.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true, cancelable = true)))
                modal.dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))
                awaitUntil("a click on the backdrop closed the dialog") { calls.closed == 1 }
                assertEquals(0, calls.confirmed)
                assertEquals(1, calls.closedWithoutConfirm)
            }
        }

    @Test
    fun confirm_firesOnConfirm_andIsNotAClosedWithoutConfirm(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-confirm") { _, _ ->
                val calls = Calls()
                open(calls)
                awaitFocusOnCancel(lastOpenModal())
                lastOpenModal().buttonNamed("Für alle beenden").click()
                assertEquals(1, calls.confirmed)
                awaitUntil("the dialog reported its close") { calls.closed == 1 }
                assertEquals(0, calls.closedWithoutConfirm, "no focus return after a confirmation")
            }
        }

    @Test
    fun aDoubleClickAndTwoFastClicks_fireOnConfirmExactlyOnce(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-double") { _, _ ->
                val calls = Calls()
                open(calls)
                val confirm = lastOpenModal().buttonNamed("Für alle beenden")
                confirm.click()
                confirm.click()
                confirm.dispatchEvent(MouseEvent("dblclick", MouseEventInit(bubbles = true, cancelable = true)))
                confirm.click()
                assertEquals(1, calls.confirmed)
            }
        }

    @Test
    fun enterRightAfterOpening_cancels_becauseTheFocusIsOnAbbrechen(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-enter") { _, _ ->
                val calls = Calls()
                open(calls)
                val modal = lastOpenModal()
                awaitFocusOnCancel(modal)
                // Enter / Space activate the focused control: a click on it. The focused control is "Abbrechen".
                (document.activeElement as HTMLElement).click()
                awaitUntil("the dialog closed") { calls.closed == 1 }
                assertEquals(0, calls.confirmed)
            }
        }

    @Test
    fun theGuard_opensNoSecondDialogWhileOneIsOpen_andOpensAgainAfterTheClose(): Promise<Unit> =
        formTest {
            mountedForm("end-dialog-guard") { _, _ ->
                val guard = EndRoomDialogGuard()
                var confirmed = 0
                var withoutConfirm = 0
                assertNotNull(guard.show("Raum", onClosedWithoutConfirm = { withoutConfirm++ }) { confirmed++ })
                // autorepeat / double trigger / primary + twin: nothing opens
                assertNull(guard.show("Raum") { confirmed++ })
                assertNull(guard.show("Raum") { confirmed++ })
                assertEquals(1, openModals().size, "exactly one dialog is open")
                assertEquals(0, confirmed)
                awaitFocusOnCancel(lastOpenModal())
                lastOpenModal().buttonNamed("Abbrechen").click()
                awaitUntil("the guard is released by the close") { !guard.open }
                assertEquals(1, withoutConfirm)
                assertNotNull(guard.show("Raum") { confirmed++ }, "a new attempt after the close opens a new dialog")
                delay(10)
                closeOpenModals(timeoutMs = 1000)
            }
        }
}
