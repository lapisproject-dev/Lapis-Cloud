package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 audit (MA-3, MA-7): the restore form of the backup screen. A destructive action that may already have
 * written to the target must never fail SILENTLY: a dropped connection is an outcome with a visible message, the trigger
 * is released only after that message, and a successful restore resets the file field to "untouched".
 */
class BackupRestoreDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    private fun chooseFile(input: HTMLInputElement) {
        val transfer = js("new DataTransfer()")
        transfer.items.add(File(arrayOf<dynamic>("zip-bytes"), "backup.zip"))
        input.asDynamic().files = transfer.files
        input.dispatchEvent(Event("change"))
    }

    /** The confirm modal, once Bootstrap really shows it (a click before that would hide a modal that is not yet shown). */
    private suspend fun shownConfirmModal(): HTMLElement {
        awaitUntil("the confirm modal is shown") { document.querySelectorAll(".modal.show").length > 0 }
        return lastModal()
    }

    private fun lastModal(): HTMLElement {
        val modals = document.querySelectorAll(".modal")
        return assertNotNull(modals.item(modals.length - 1) as? HTMLElement, "no confirm modal")
    }

    private fun HTMLElement.restoreAlert(): HTMLElement = first(".lapis-form-alert")

    @Test
    fun restoreWithoutAFile_showsTheRequiredErrorAtTheField_opensNoDialog_andSendsNothing(): Promise<Unit> =
        test {
            withFetchStub { calls ->
                withMountedRoot("backup-no-file") { root, element ->
                    renderBackupScreen(root)
                    element().button("Wiederherstellen").click()
                    assertEquals("Bitte eine Datei auswählen.", element().first(".lapis-field-error--shown").textContent?.trim())
                    assertEquals(0, document.querySelectorAll(".modal").length, "no confirmation dialog without a file")
                    assertEquals(0, calls.count { it.url.contains("/api/backup/restore") })
                }
            }
        }

    @Test
    fun aDroppedConnection_isShownAsAMessage_theTriggerIsFreedOnlyAfterIt_andTheFileStaysChosen(): Promise<Unit> =
        test {
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    request.url.contains("/api/backup/restore") -> StubResponse(networkError = true)
                    else -> rpcResult(request.json.id as Int, "[]")
                }
            }
            withFetchStub(respond = respond) { calls ->
                withMountedRoot("backup-network-error") { root, element ->
                    renderBackupScreen(root)
                    val fileInput = element().first("input[type=file]") as HTMLInputElement
                    chooseFile(fileInput)
                    val restore = element().button("Wiederherstellen")
                    restore.click()
                    shownConfirmModal().button("Endgültig wiederherstellen").click()
                    awaitUntil("the failure message") {
                        element()
                            .restoreAlert()
                            .textContent
                            .orEmpty()
                            .isNotBlank()
                    }
                    assertTrue(
                        element()
                            .restoreAlert()
                            .textContent
                            .orEmpty()
                            .contains("Verbindung wurde unterbrochen"),
                        "the message names the dropped connection: ${element().restoreAlert().textContent}",
                    )
                    assertTrue(
                        element()
                            .restoreAlert()
                            .textContent
                            .orEmpty()
                            .contains("teilweise"),
                        "and warns that the target may be half-written",
                    )
                    assertFalse(restore.hasAttribute("disabled"), "the trigger is usable again, but only now that the message is visible")
                    assertEquals(1, calls.count { it.url.contains("/api/backup/restore") })
                    assertEquals(
                        1,
                        (fileInput.asDynamic().files.length as Int),
                        "after a failure the file stays chosen for the next attempt",
                    )
                }
            }
        }

    @Test
    fun aSuccessfulRestore_resetsTheFileField_noLateRequiredErrorOnAnUntouchedField(): Promise<Unit> =
        test {
            val successJson = """{"tablesRestored":3,"totalRowCount":12,"blobsRestored":1,"warnings":[]}"""
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (request.url.contains(
                        "/api/backup/restore",
                    )
                ) {
                    StubResponse(text = successJson)
                } else {
                    rpcResult(request.json.id as Int, "[]")
                }
            }
            withFetchStub(respond = respond) { calls ->
                withMountedRoot("backup-success") { root, element ->
                    renderBackupScreen(root)
                    val fileInput = element().first("input[type=file]") as HTMLInputElement
                    chooseFile(fileInput)
                    element().button("Wiederherstellen").click()
                    shownConfirmModal().button("Endgültig wiederherstellen").click()
                    awaitUntil("restore request") { calls.any { it.url.contains("/api/backup/restore") } }
                    awaitUntil(
                        "the success summary",
                    ) { element().textContent.orEmpty().contains("Wiederherstellung erfolgreich abgeschlossen.") }
                    awaitUntil("the field reset") { (fileInput.asDynamic().files.length as Int) == 0 }
                    fileInput.dispatchEvent(Event("blur"))
                    assertEquals(
                        0,
                        element().querySelectorAll(".lapis-field-error--shown").length,
                        "the reset field is untouched again: leaving it must not complain about a missing file",
                    )
                    assertFalse(fileInput.classList.contains("is-invalid"))
                }
            }
        }

    @Test
    fun theRestoreCall_returnsAnOutcomeInsteadOfThrowing_whenTheNetworkFails(): Promise<Unit> =
        test {
            withFetchStub(respond = { StubResponse(networkError = true) }) { _ ->
                val outcome = BackupHttp.restore(File(arrayOf<dynamic>("x"), "b.zip"), allowNonEmptyTarget = false)
                assertTrue(outcome is RestoreOutcome.Other)
                assertEquals(BackupHttp.NETWORK_FAILURE_STATUS, outcome.status)
                assertTrue(outcome.message.isNotBlank())
            }
        }
}
