package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.CostCenterReportDto
import network.lapis.cloud.shared.domain.CostCenterResultDto
import network.lapis.cloud.shared.rpc.IAccountingService
import kotlin.time.Clock

/**
 * Accounting UI wave, screen 4 of 5 -- "Kostenstellen" (cost-center CRUD + report), per the
 * approved plan + UI/UX-Design-Team review on `feature/accounting-ui`. See plan "Screen 4 --
 * CostCentersScreen.kt" and design decisions D5, D6, D9 (`activeStatusBadge`), D12, D14. No
 * cross-links into Crowdfunding/Auction this wave, per the task's explicit scope limit.
 *
 * Role gating (verified against `AccountingService.kt`, plan's role-gating table):
 * `createCostCenter`/`deactivateCostCenter` are `TREASURY_ROLES` (TREASURER/ADMIN);
 * `listCostCenters`/`getCostCenterReport` are `ACCOUNTING_READ_ROLES` (TREASURER/BOARD/ADMIN).
 * `Routing.kt` already gates the whole `/cost-centers` route on TREASURER/BOARD/ADMIN; the
 * narrower `canManage = AppState.hasRole(TREASURER, ADMIN)` additionally hides/disables every
 * mutating affordance for a BOARD caller, mirroring `LedgerScreen.kt`'s exact posture.
 *
 * Every monetary figure below is a `Decimal` returned verbatim by `IAccountingService` and
 * rendered through [formatMoney]/[moneySpan] (`Money.kt`) -- this screen never parses, sums, or
 * re-rounds a figure the server has already computed, including [CostCenterReportDto.totalIncome]/
 * [CostCenterReportDto.totalExpense]/[CostCenterReportDto.result], which already reconcile
 * server-side across the named cost centers plus the unassigned bucket (see that DTO's own KDoc)
 * -- this screen displays them as-is rather than re-summing [CostCenterResultDto] rows itself.
 */
fun renderCostCentersScreen(container: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1("Kostenstellen")

    // ---- List (Kostenstellen-Übersicht) ----------------------------------------------------
    root.h2("Übersicht")
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveCheck = filterRow.checkBox(label = "Inaktive Kostenstellen anzeigen")
    val refreshButton = filterRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val listPanel = root.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        AppScope.launch {
            val costCenters =
                guarded {
                    rpcService<IAccountingService>().listCostCenters(activeOnly = !includeInactiveCheck.value)
                } ?: return@launch
            if (costCenters.isEmpty()) {
                listPanel.p("Noch keine Kostenstellen angelegt.")
                return@launch
            }
            costCenters.forEach { costCenter ->
                renderCostCenterRow(listPanel, costCenter, canManage, ::refreshList)
            }
        }
    }
    refreshButton.onClick { refreshList() }
    refreshList()

    if (canManage) {
        root.h2("Neue Kostenstelle anlegen")
        renderCostCenterCreationForm(root, ::refreshList)
    }

    // ---- Report -----------------------------------------------------------------------------
    root.h2("Kostenstellenbericht")
    renderCostCenterReportView(root)
}

// ============================================================================================
// List row + creation form
// ============================================================================================

private fun renderCostCenterRow(
    panel: SimplePanel,
    costCenter: CostCenterDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(costCenterLabel(costCenter)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.activeStatusBadge(costCenter.active)

    costCenter.description?.takeIf { it.isNotBlank() }?.let { description ->
        row.div(description) { addCssClasses("text-muted small") }
    }

    if (canManage && costCenter.active) {
        val actionRow = row.hPanel(spacing = 8)
        val deactivateButton = actionRow.button("Deaktivieren", style = ButtonStyle.OUTLINEDANGER)
        deactivateButton.onClick {
            confirmDialog(
                title = "Kostenstelle deaktivieren",
                message =
                    "\"${costCenter.code} · ${costCenter.name}\" wirklich deaktivieren? Bestehende Buchungen " +
                        "bleiben erhalten, die Kostenstelle steht aber für neue Buchungen nicht mehr zur Verfügung.",
                confirmLabel = "Deaktivieren",
            ) {
                AppScope.launch {
                    val result = guarded { rpcService<IAccountingService>().deactivateCostCenter(costCenter.id) }
                    if (result != null) {
                        notifyInfo("Kostenstelle wurde deaktiviert.")
                        onChanged()
                    }
                }
            }
        }
    }
}

/**
 * Client-side non-blank pre-check only (`Validation.isNonBlank`, mirroring `LedgerScreen`'s own
 * account-creation form) -- the server remains the authority on a duplicate/blank
 * [CostCenterInput.code] (`BadRequestException`/`ConflictException`, see `IAccountingService
 * .createCostCenter` KDoc); a race or a validation this client does not mirror correctly still
 * surfaces through `guarded()`'s generic error toast.
 */
private fun renderCostCenterCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val codeInput = panel.text(label = "Code (eindeutig, z. B. SOMMERFEST-2027)")
    val nameInput = panel.text(label = "Name")
    val descriptionInput = panel.text(label = "Beschreibung (optional)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button("Kostenstelle anlegen", style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val code = codeInput.value.orEmpty().trim()
        val name = nameInput.value.orEmpty().trim()
        val description = descriptionInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (!Validation.isNonBlank(code) || !Validation.isNonBlank(name)) {
            errorBox.content = "Bitte Code und Name angeben."
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IAccountingService>().createCostCenter(
                        CostCenterInput(code = code, name = name, description = description, active = true),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess("Kostenstelle \"$code · $name\" wurde angelegt.")
                codeInput.value = null
                nameInput.value = null
                descriptionInput.value = null
                onCreated()
            }
        }
    }
}

// ============================================================================================
// Report (getCostCenterReport)
// ============================================================================================

/**
 * `to` is a required `LocalDate` server-side (like `getIncomeStatement`, unlike the Journal/
 * Hauptbuch/Kassenbuch filters' optional `to`) -- pre-filled to today so the first render shows a
 * meaningful report instead of an immediate validation error before the treasurer has touched
 * anything, mirroring `FinancialReportsScreen.renderIncomeStatementView`'s identical precedent.
 */
private fun renderCostCenterReportView(panel: SimplePanel) {
    val filterControls = panel.dateRangeFilter()
    filterControls.toInput.value = todayIso()
    val loadButton = panel.button("Laden", style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 8)

    fun load() {
        errorBox.hide()
        val to = filterControls.parseTo()
        if (to == null) {
            errorBox.content = "Bitte ein gültiges \"Bis\"-Datum angeben (JJJJ-MM-TT)."
            errorBox.show()
            return
        }
        val from = filterControls.parseFrom()
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val report =
                guarded { rpcService<IAccountingService>().getCostCenterReport(from, to) } ?: return@launch
            resultPanel.removeAll()
            renderCostCenterReportBody(resultPanel, report)
        }
    }
    loadButton.onClick { load() }
    load()
}

/**
 * D14: the "Nicht zugeordnet" bucket is rendered as the last row after every code-sorted named
 * cost center (already sorted server-side, see [CostCenterReportDto.costCenters] KDoc), styled
 * muted+italic with a thin top border so it does not read as just another named cost center, then
 * a heavier bold-bordered grand-total row below that. [CostCenterReportDto.totalIncome]/
 * [totalExpense]/[result] are the server's own already-reconciled figures (named cost centers +
 * unassigned bucket) -- rendered verbatim, never re-summed from [CostCenterResultDto] rows here.
 */
private fun renderCostCenterReportBody(
    panel: SimplePanel,
    report: CostCenterReportDto,
) {
    panel.div(periodRangeCaption(report.from, report.to)) { addCssClasses("text-muted small") }

    if (report.costCenters.isEmpty()) {
        panel.p("Keine Kostenstelle mit Buchungen im gewählten Zeitraum.") { addCssClasses("text-muted small") }
    } else {
        val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
        headerRow.div("Kostenstelle") { addCssClasses("flex-grow-1") }
        headerRow.div("Einnahmen") { width = 120.px }
        headerRow.div("Ausgaben") { width = 120.px }
        headerRow.div("Ergebnis") { width = 120.px }

        report.costCenters.forEach { costCenter ->
            val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            row.div(costCenterResultLabel(costCenter)) { addCssClasses("flex-grow-1") }
            row.div(formatMoney(costCenter.totalIncome)) { width = 120.px }
            row.div(formatMoney(costCenter.totalExpense)) { width = 120.px }
            row.moneySpan(costCenter.result, warnIfNegative = true).width = 120.px
        }
    }

    // D14: "Nicht zugeordnet" -- muted/italic, thin top border, visually distinct from a named row.
    val unassignedRow =
        panel.hPanel(spacing = 8) {
            addCssClasses("border-top py-1 align-items-center fst-italic text-muted")
        }
    unassignedRow.div("— Nicht zugeordnet —") { addCssClasses("flex-grow-1") }
    unassignedRow.div(formatMoney(report.unassignedIncome)) { width = 120.px }
    unassignedRow.div(formatMoney(report.unassignedExpense)) { width = 120.px }
    unassignedRow.moneySpan(report.unassignedResult, warnIfNegative = true).width = 120.px

    val totalRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1 align-items-center") }
    totalRow.div("Gesamt") { addCssClasses("flex-grow-1") }
    totalRow.div(formatMoney(report.totalIncome)) { width = 120.px }
    totalRow.div(formatMoney(report.totalExpense)) { width = 120.px }
    totalRow.moneySpan(report.result, warnIfNegative = true).width = 120.px
}

// ============================================================================================
// Pure helpers -- covered by CostCentersScreenTest.kt
// ============================================================================================

/** Shared "code · name" label formatting for a [CostCenterDto] row -- factored out so the list
 * row and (via [costCenterResultLabel]) the report table can never drift into two slightly
 * different renderings of the same identity. */
fun costCenterLabel(costCenter: CostCenterDto): String = "${costCenter.code} · ${costCenter.name}"

/** Same "code · name" identity as [costCenterLabel], but for a [CostCenterResultDto] row (the
 * report table's shape, which does not carry the full [CostCenterDto]) -- kept as a separate
 * function rather than a shared interface, since the two DTOs otherwise have no supertype in
 * common and coercing one would be more machinery than the one-line duplication it avoids. */
fun costCenterResultLabel(costCenter: CostCenterResultDto): String = "${costCenter.code} · ${costCenter.name}"

/** Mirrors `LedgerScreen.kt`/`FinancialReportsScreen.kt`'s own private `todayIso()` -- no shared
 * date-util file exists in this client (each screen that needs "today as JJJJ-MM-TT" carries its
 * own copy). */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()
