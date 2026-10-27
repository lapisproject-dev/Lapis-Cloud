package network.lapis.cloud.client

import io.kvision.html.div
import network.lapis.cloud.shared.domain.PartgImportApplyResultDto
import network.lapis.cloud.shared.domain.PartgImportChangeDto
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgImportErrorCode
import network.lapis.cloud.shared.domain.PartgImportErrorDto
import network.lapis.cloud.shared.domain.PartgImportPreviewDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.225 -- the import dialog: errors block "Übernehmen" (aria-disabled), a clean preview lists the changes, apply sends the token and revisions. */
class PartyReportImportDialogDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun chooseFile(modal: HTMLElement) {
        val input: dynamic = assertNotNull(modal.querySelector("input[type=file]") as? HTMLInputElement)
        val transfer = js("new DataTransfer()")
        transfer.items.add(File(arrayOf("x"), "bericht.xlsx"))
        input.files = transfer.files
    }

    @Test
    fun errors_blockTheTakeOver_andAreListedWithRowColumnAndAFixedText(): Promise<Unit> =
        formTest {
            AppState.setSession(PartyReportDomSupport.session())
            mountedForm("party-report-import-errors") { root, _ ->
                root.div()
                openPartyReportImportDialog(
                    year = 2025,
                    unit = PartyReportDomSupport.federal,
                    previewCall = { _, _, _ ->
                        PartyReportHttp.PreviewResult.Ok(
                            PartgImportPreviewDto(
                                errors = listOf(PartgImportErrorDto(row = 9, column = "D", code = PartgImportErrorCode.NOT_A_NUMBER)),
                            ),
                        )
                    },
                    applyCall = { error("must not be called") },
                    onApplied = {},
                )
                val modal = lastOpenModal()
                assertTrue(modal.shows("Excel-Datei importieren"))
                assertEquals("true", modal.buttonNamed("Übernehmen").getAttribute("aria-disabled"), "disabled before anything was checked")
                chooseFile(modal)
                modal.buttonNamed("Prüfen").click()
                awaitUntil("the error list") { modal.shows("Hier steht kein gültiger Betrag.") }
                assertTrue(modal.shows("Zeile 9") && modal.shows("Spalte D"))
                assertEquals("true", modal.buttonNamed("Übernehmen").getAttribute("aria-disabled"))
                assertNotNull(modal.buttonNamed("Übernehmen").getAttribute("aria-describedby"))
            }
        }

    @Test
    fun aCleanPreview_listsTheChanges_andApplySendsTokenAndRevisions(): Promise<Unit> =
        formTest {
            AppState.setSession(PartyReportDomSupport.session())
            var applied: network.lapis.cloud.shared.domain.PartgImportApplyDto? = null
            var done = false
            mountedForm("party-report-import-clean") { root, _ ->
                root.div()
                openPartyReportImportDialog(
                    year = 2025,
                    unit = PartyReportDomSupport.federal,
                    previewCall = { _, _, _ ->
                        PartyReportHttp.PreviewResult.Ok(
                            PartgImportPreviewDto(
                                token = "tok",
                                recognized = 42,
                                changes = listOf(PartgImportChangeDto("E01", PartgImportColumn.CURRENT, null, 123_456)),
                                sheetRevision = 3,
                                priorRevision = 1,
                            ),
                        )
                    },
                    applyCall = { body ->
                        applied = body
                        PartyReportHttp.ApplyResult.Ok(PartgImportApplyResultDto(changed = 1, revision = 4, priorRevision = 1))
                    },
                    onApplied = { done = true },
                )
                val modal = lastOpenModal()
                chooseFile(modal)
                modal.buttonNamed("Prüfen").click()
                awaitUntil("the change list") { modal.shows("1.234,56") }
                assertFalse(modal.shows("Hier steht kein"))
                assertEquals("false", modal.buttonNamed("Übernehmen").getAttribute("aria-disabled"))
                modal.buttonNamed("Übernehmen").click()
                awaitUntil("the apply call") { applied != null }
                assertEquals("tok", applied!!.token)
                assertEquals(3L, applied!!.expectedRevision)
                assertEquals(1L, applied!!.expectedPriorRevision)
                awaitUntil("the dialog reports") { done }
            }
        }
}
