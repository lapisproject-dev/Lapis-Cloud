package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Input
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.li
import io.kvision.html.ul
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgImportApplyDto
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgImportLimits
import network.lapis.cloud.shared.domain.PartgImportPreviewDto
import network.lapis.cloud.shared.domain.PartgUnitDto
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

private val IMPORT_CHANGE_HEADERS =
    listOf(
        TableHeader(title = tr("Position")),
        TableHeader(title = tr("Jahr")),
        TableHeader(title = tr("bisher"), numeric = true),
        TableHeader(title = tr("neu"), numeric = true),
    )

/**
 * The two-step Excel import of one unit: choose a file, "Prüfen" shows what would change and every error (row, column, a fixed text per code),
 * "Übernehmen" is only possible without errors and writes everything or nothing. A file for another unit or year is refused, never taken over.
 */
internal fun openPartyReportImportDialog(
    year: Int,
    unit: PartgUnitDto,
    // the two network calls are parameters so a test can answer them without a network (defaults are the real calls)
    previewCall: suspend (Int, String, File) -> PartyReportHttp.PreviewResult = PartyReportHttp::preview,
    applyCall: suspend (PartgImportApplyDto) -> PartyReportHttp.ApplyResult = PartyReportHttp::apply,
    onApplied: () -> Unit,
) {
    val modal = Modal(caption = gettext("Excel-Datei importieren: %1, %2", unitLabel(unit), year))
    modal.div(tr("Wählen Sie die ausgefüllte Vorlage (.xlsx, höchstens 2 MB). Leere Zellen verändern nichts.")) {
        addCssClasses("small text-muted")
    }
    val fileInput = Input(type = InputType.FILE, className = "form-control mt-2")
    fileInput.setAttribute("accept", ".xlsx")
    val fileLabel = resolvedAttributeText(tr("Excel-Datei"))
    fileInput.setAttribute("aria-label", fileLabel)
    modal.add(fileInput)
    val result = Div(className = "mt-3")
    modal.add(result)

    var preview: PartgImportPreviewDto? = null
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val check = newActionButton(ActionIcon.SEARCH, tr("Prüfen"), ButtonStyle.OUTLINEPRIMARY)
    val apply = newActionButton(ActionIcon.SAVE, tr("Übernehmen"), ButtonStyle.PRIMARY)

    fun updateApply() {
        val p = preview
        val enabled = p != null && p.token != null && p.errors.isEmpty() && p.changes.isNotEmpty()
        apply.setAttribute("aria-disabled", (!enabled).toString())
        apply.disabled = !enabled
        if (!enabled) {
            apply.setAttribute("aria-describedby", "partg-import-result")
        } else {
            apply.removeAttribute("aria-describedby")
        }
    }
    result.id = "partg-import-result"
    updateApply()

    fun showPreview(p: PartgImportPreviewDto) {
        result.removeAll()
        if (p.errors.isNotEmpty()) {
            result.div(tr("Die Datei kann nicht übernommen werden:")) { addCssClasses("fw-semibold text-danger") }
            result.ul {
                p.errors.forEach { error ->
                    val place =
                        listOfNotNull(
                            error.row?.let { gettext("Zeile %1", it) },
                            error.column?.let { gettext("Spalte %1", it) },
                            error.detail,
                        ).joinToString(", ")
                    li(listOf(place, partgImportErrorText(error.code)).filter { it.isNotBlank() }.joinToString(": "))
                }
            }
        } else if (p.changes.isEmpty()) {
            result.div(tr("Die Datei enthält keine Änderungen gegenüber dem gespeicherten Stand.")) { addCssClass("text-muted") }
        } else {
            result.div(gettext("%1 Werte würden übernommen:", p.changes.size)) { addCssClass("fw-semibold") }
            val table = result.standardTable(IMPORT_CHANGE_HEADERS)
            p.changes.forEach { change ->
                table.row {
                    textCell(partgLabel(change.positionCode) + if (change.fromFormula) " ƒ" else "")
                    textCell(
                        if (change.column ==
                            PartgImportColumn.CURRENT
                        ) {
                            gettext("Rechnungsjahr %1", year)
                        } else {
                            gettext("Vorjahr %1", year - 1)
                        },
                    )
                    numCell {
                        if (change.oldCents == null) div("–") else moneySpan(centsToDecimal(change.oldCents!!))
                    }
                    numCell { moneySpan(centsToDecimal(change.newCents)) }
                }
            }
            if (p.changes.any { it.fromFormula }) {
                result.div(tr("ƒ = der Wert stammt aus einer Formel; übernommen wird das in der Datei gespeicherte Ergebnis.")) {
                    addCssClasses("small text-muted")
                }
            }
        }
        updateApply()
    }

    check.onClick {
        val file: File? = (fileInput.getElement() as? HTMLInputElement)?.files?.item(0)
        if (file == null) {
            notifyError(tr("Bitte wählen Sie zuerst eine Datei."))
            return@onClick
        }
        if (file.size.toDouble() > PartgImportLimits.MAX_UPLOAD_BYTES) {
            notifyError(tr("Die Datei ist zu groß (höchstens 2 MB)."))
            return@onClick
        }
        check.disabled = true
        AppScope.launch {
            when (val outcome = previewCall(year, unit.unitKey, file)) {
                is PartyReportHttp.PreviewResult.Ok -> {
                    preview = outcome.preview
                    showPreview(outcome.preview)
                }
                is PartyReportHttp.PreviewResult.Rejected -> {
                    preview = null
                    notifyError(partgRejectionText(outcome.code))
                    updateApply()
                }
            }
            check.disabled = false
        }
    }
    apply.onClick {
        val p = preview ?: return@onClick
        val token = p.token ?: return@onClick
        apply.disabled = true
        AppScope.launch {
            when (val outcome = applyCall(PartgImportApplyDto(token, p.sheetRevision, p.priorRevision))) {
                is PartyReportHttp.ApplyResult.Ok -> {
                    modal.hide()
                    notifySuccess(gettext("%1 Werte übernommen.", outcome.result.changed))
                    onApplied()
                }
                is PartyReportHttp.ApplyResult.Rejected -> {
                    preview = null
                    notifyError(partgRejectionText(outcome.code))
                    updateApply()
                }
            }
        }
    }
    modal.addButton(cancel)
    modal.addButton(check)
    modal.addButton(apply)
    modal.show()
}
