package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
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
import network.lapis.cloud.shared.domain.AnonymousDonationDutyDto
import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonationDutyReportDto
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.DonorDutyDto
import network.lapis.cloud.shared.domain.DonorType
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.ExternalDonorInput
import network.lapis.cloud.shared.rpc.IAccountingService
import kotlin.time.Clock

/**
 * Accounting UI wave, screen 5 of 5 -- "Spender" (external-donor CRM-lite CRUD + the §25 PartG
 * Spendenrecht-Pflichten-Report), per the approved plan + UI/UX-Design-Team review on
 * `feature/accounting-ui`. See plan "Screen 5 -- DonorsScreen.kt" and design decisions D5/D6
 * (money formatting), D9 (`donorCategoryLabel`/`donorCategoryColor` from the shared
 * `AccountingLabels.kt`; `DonorType`/`DonationDuty` labels defined locally below, single-screen
 * enums per that file's own scoping rule), D12 (`fiscalYearFilter`, reused here for the report's
 * `calendarYear` scoping even though the parameter name differs from "fiscal year" -- same `Int`
 * shape, same "pre-filled, visibly-editable default" UX).
 *
 * Role gating (verified against `AccountingService.kt`, plan's role-gating table):
 * `createExternalDonor`/`deactivateExternalDonor` are `TREASURY_ROLES` (TREASURER/ADMIN);
 * `listExternalDonors`/`getExternalDonor`/`getDonationDutyReport` are `ACCOUNTING_READ_ROLES`
 * (TREASURER/BOARD/ADMIN). `Routing.kt` already gates the whole `/donors` route on
 * TREASURER/BOARD/ADMIN; the narrower `canManage = AppState.hasRole(TREASURER, ADMIN)` additionally
 * hides/disables every mutating affordance for a BOARD caller, mirroring `LedgerScreen.kt`'s/
 * `CostCentersScreen.kt`'s exact posture.
 *
 * **No conflation with `MemberAdministrationScreen`'s member list** -- [ExternalDonorDto] is a
 * distinct, non-Member CRM-lite entity (see that DTO's own KDoc); this screen never reads from or
 * links into the Members screen, per the task's explicit instruction.
 *
 * Every monetary figure below ([DonorDutyDto.annualTotal], [AnonymousDonationDutyDto.amount]) is a
 * `Decimal` returned verbatim by `IAccountingService` and rendered through [formatMoney] (`Money.kt`)
 * -- this screen never parses, sums, or re-rounds a figure the server has already computed. Neither
 * field is ever legitimately negative per its own KDoc (both are donation totals/amounts), so
 * `moneySpan(..., warnIfNegative = true)` is deliberately never used on this screen -- matches D6's
 * "only where the DTO documents it may be negative" rule.
 *
 * The donor-detail expansion ([renderDonorRow]) deliberately does re-fetch via `getExternalDonor(id)`
 * on click rather than rendering the address fields already present on the row's own
 * [ExternalDonorDto] (returned in full by `listExternalDonors`) -- this exercises the RPC method the
 * plan's scope explicitly names ("Detail view via `getExternalDonor(id)`") and gives a genuinely
 * fresh read for a caller who has had the list open for a while, matching the same expand-in-place
 * accordion grammar `LedgerScreen.kt`'s account row and `NonprofitComplianceReportsScreen.kt`'s
 * sphere/year rows already established for this wave -- not a parallel pattern.
 */
fun renderDonorsScreen(container: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1("Spender")
    root.div(
        "Externe Spender sind keine Mitglieder -- eine eigenständige Adressverwaltung für " +
            "Spendenbescheinigungen und die §25-PartG-Pflichtenprüfung, getrennt von der " +
            "Mitgliederverwaltung.",
    ) { addCssClasses("text-muted small") }

    // ---- External donor list (Spenderstamm) -------------------------------------------------
    root.h2("Externe Spender")
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveCheck = filterRow.checkBox(label = "Inaktive Spender anzeigen")
    val refreshButton = filterRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val listPanel = root.vPanel(spacing = 6)

    fun refreshList() {
        listPanel.removeAll()
        AppScope.launch {
            val donors =
                guarded {
                    rpcService<IAccountingService>().listExternalDonors(activeOnly = !includeInactiveCheck.value)
                } ?: return@launch
            if (donors.isEmpty()) {
                listPanel.p("Noch keine externen Spender angelegt.")
                return@launch
            }
            donors.forEach { donor -> renderDonorRow(listPanel, donor, canManage, ::refreshList) }
        }
    }
    refreshButton.onClick { refreshList() }
    refreshList()

    if (canManage) {
        root.h2("Neuen Spender anlegen")
        renderDonorCreationForm(root, ::refreshList)
    }

    // ---- Spendenrecht-Pflichten-Report (§25 PartG) -------------------------------------------
    root.h2("Spendenrecht-Pflichten-Report (§25 PartG)")
    renderDonationDutyReportView(root)
}

// ============================================================================================
// List row + creation form
// ============================================================================================

/**
 * List row stays terse (Name + Kategorie + Aktiv/Inaktiv, the same three-signal shape
 * `CostCentersScreen.renderCostCenterRow` already established); the address block is only fetched
 * and shown once a caller actually asks for it, via the "Details anzeigen" toggle -- see file KDoc
 * for why this deliberately calls `getExternalDonor(id)` rather than reusing the row's already-held
 * [ExternalDonorDto].
 */
private fun renderDonorRow(
    panel: SimplePanel,
    donor: ExternalDonorDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(donor.displayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(donorCategoryLabel(donor.donorCategory), donorCategoryColor(donor.donorCategory))
    headerRow.activeStatusBadge(donor.active)
    val detailButton = headerRow.button("Details anzeigen", style = ButtonStyle.OUTLINESECONDARY)

    val detailPanel = row.vPanel(spacing = 2) { hide() }
    var expanded = false
    detailButton.onClick {
        expanded = !expanded
        if (!expanded) {
            detailPanel.hide()
            return@onClick
        }
        detailPanel.removeAll()
        detailPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        detailPanel.show()
        AppScope.launch {
            val fresh = guarded { rpcService<IAccountingService>().getExternalDonor(donor.id) }
            detailPanel.removeAll()
            if (fresh == null) {
                detailPanel.show()
                return@launch
            }
            detailPanel.div(donorAddressLine(fresh)) { addCssClasses("text-muted small") }
        }
    }

    if (canManage && donor.active) {
        val actionRow = row.hPanel(spacing = 8)
        val deactivateButton = actionRow.button("Deaktivieren", style = ButtonStyle.OUTLINEDANGER)
        deactivateButton.onClick {
            confirmDialog(
                title = "Spender deaktivieren",
                message =
                    "\"${donor.displayName}\" wirklich deaktivieren? Bestehende Buchungen mit diesem " +
                        "Spender bleiben erhalten, er steht aber für neue Buchungen nicht mehr zur " +
                        "Verfügung.",
                confirmLabel = "Deaktivieren",
            ) {
                AppScope.launch {
                    val result = guarded { rpcService<IAccountingService>().deactivateExternalDonor(donor.id) }
                    if (result != null) {
                        notifyInfo("Spender wurde deaktiviert.")
                        onChanged()
                    }
                }
            }
        }
    }
}

/**
 * Client-side non-blank pre-check only (`Validation.isNonBlank`, mirroring
 * `CostCentersScreen.renderCostCenterCreationForm`'s own precedent) -- the server remains the
 * authority on a blank [ExternalDonorInput.displayName] (`BadRequestException`, see
 * `IAccountingService.createExternalDonor` KDoc). [donorCategory] has no pre-selected default --
 * the same "legally-loaded classification, must be chosen deliberately" posture `LedgerScreen.kt`'s
 * donor block (D13) and posting-line `sphere` select already apply, extended here to this screen's
 * own creation form for consistency across the wave.
 */
private fun renderDonorCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val displayNameInput = panel.text(label = "Name")
    val categoryOptions =
        listOf("" to "-- Spenderkategorie wählen --") + donorCategoryCreationOrder.map { it.name to donorCategoryLabel(it) }
    val categorySelect = panel.select(options = categoryOptions, value = "", label = "Spenderkategorie")
    val streetInput = panel.text(label = "Straße (optional)")
    val postalCodeInput = panel.text(label = "PLZ (optional)")
    val cityInput = panel.text(label = "Ort (optional)")
    val countryInput = panel.text(label = "Land (optional)")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button("Spender anlegen", style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val displayName = displayNameInput.value.orEmpty().trim()
        val categoryValue = categorySelect.value.orEmpty()
        val category = runCatching { DonorCategory.valueOf(categoryValue) }.getOrNull()

        if (!Validation.isNonBlank(displayName) || category == null) {
            errorBox.content = "Bitte Name und Spenderkategorie angeben."
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IAccountingService>().createExternalDonor(
                        ExternalDonorInput(
                            displayName = displayName,
                            donorCategory = category,
                            street = streetInput.value?.trim()?.takeIf { it.isNotBlank() },
                            postalCode = postalCodeInput.value?.trim()?.takeIf { it.isNotBlank() },
                            city = cityInput.value?.trim()?.takeIf { it.isNotBlank() },
                            country = countryInput.value?.trim()?.takeIf { it.isNotBlank() },
                            active = true,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess("Spender \"$displayName\" wurde angelegt.")
                displayNameInput.value = null
                categorySelect.value = ""
                streetInput.value = null
                postalCodeInput.value = null
                cityInput.value = null
                countryInput.value = null
                onCreated()
            }
        }
    }
}

// ============================================================================================
// Spendenrecht-Pflichten-Report (getDonationDutyReport)
// ============================================================================================

/**
 * `calendarYear` is a plain `Int`, same shape `UseOfFundsStatementDto`'s fiscal-year filters use --
 * [fiscalYearFilter] (`AccountingFilters.kt`, D12) is reused verbatim here with a report-specific
 * label rather than introducing a second, near-identical year-input control.
 */
private fun renderDonationDutyReportView(panel: SimplePanel) {
    panel.div(
        "Zeigt offene Melde-/Offenlegungs-/Weiterleitungspflichten nach §25 PartG für ein " +
            "Kalenderjahr, damit der Vorstand sie manuell abarbeiten kann. Berechnet keine " +
            "unzulässigen Spenden -- solche werden beim Buchen serverseitig blockiert und können " +
            "hier nie erscheinen.",
    ) { addCssClasses("text-muted small") }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val yearControls = filterRow.fiscalYearFilter(currentYear = currentYear(), label = "Kalenderjahr (JJJJ)")
    val loadButton = filterRow.button("Laden", style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 10)

    fun load() {
        errorBox.hide()
        val calendarYear = yearControls.parseYear()
        if (calendarYear == null) {
            errorBox.content = "Bitte ein gültiges Kalenderjahr angeben (JJJJ)."
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val report =
                guarded { rpcService<IAccountingService>().getDonationDutyReport(calendarYear) } ?: return@launch
            resultPanel.removeAll()
            renderDonationDutyReportBody(resultPanel, report)
        }
    }
    loadButton.onClick { load() }
    load()
}

/**
 * When [DonationDutyReportDto.partyRulesApply] is `false`, the view says so plainly and renders
 * nothing else -- a documented no-op for a plain gemeinnütziger Verein (see that DTO's own KDoc),
 * not an empty-state bug the treasurer should worry about.
 */
private fun renderDonationDutyReportBody(
    panel: SimplePanel,
    report: DonationDutyReportDto,
) {
    panel.div("Kalenderjahr ${report.calendarYear}") { addCssClasses("text-muted small") }

    if (!report.partyRulesApply) {
        panel.div(
            "Diese Organisation ist nicht als politische Partei markiert (Organisationseinstellungen) " +
                "-- §25 PartG gilt nur für politische Parteien. Für diese Organisation ist dieser " +
                "Bericht daher bewusst ein No-Op, keine Fehlermeldung.",
        ) { addCssClasses("alert alert-light border") }
        return
    }

    renderDonorDutiesTable(panel, report.donorDuties)
    renderAnonymousForwardingTable(panel, report.anonymousForwarding)
}

private fun renderDonorDutiesTable(
    panel: SimplePanel,
    duties: List<DonorDutyDto>,
) {
    panel.p("Melde-/Offenlegungspflichten pro Spender") { addCssClasses("fw-bold small") }
    if (duties.isEmpty()) {
        panel.p("Keine offenen Melde-/Offenlegungspflichten im gewählten Kalenderjahr.") {
            addCssClasses("text-muted small")
        }
        return
    }

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1 small") }
    headerRow.div("Spender") { addCssClasses("flex-grow-1") }
    headerRow.div("Typ") { width = 100.px }
    headerRow.div("Kategorie") { width = 220.px }
    headerRow.div("Jahressumme") { width = 120.px }
    headerRow.div("Pflichten") { width = 260.px }

    duties.forEach { duty ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(duty.donorDisplayName) { addCssClasses("flex-grow-1") }
        val typeCell = row.div { width = 100.px }
        typeCell.typeBadge(donorTypeLabel(duty.donorType), donorTypeColor(duty.donorType))
        val categoryCell = row.div { width = 220.px }
        categoryCell.typeBadge(donorCategoryLabel(duty.donorCategory), donorCategoryColor(duty.donorCategory))
        row.div(formatMoney(duty.annualTotal)) { width = 120.px }
        val dutiesCell = row.hPanel(spacing = 4) { width = 260.px }
        if (duty.promptReportRequired) {
            dutiesCell.statusBadge(
                donationDutyLabel(DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED),
                donationDutyColor(DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED),
            )
        }
        if (duty.annualDisclosureRequired) {
            dutiesCell.statusBadge(
                donationDutyLabel(DonationDuty.ANNUAL_DISCLOSURE_REQUIRED),
                donationDutyColor(DonationDuty.ANNUAL_DISCLOSURE_REQUIRED),
            )
        }
    }
}

/**
 * Explicitly NOT a prohibited-donation list -- those are hard-blocked at post time and can never
 * appear here (see [AnonymousDonationDutyDto] KDoc and this screen's file-level KDoc); the caption
 * below states that plainly rather than letting the table's presence imply otherwise.
 */
private fun renderAnonymousForwardingTable(
    panel: SimplePanel,
    forwarding: List<AnonymousDonationDutyDto>,
) {
    panel.p("Weiterleitungspflichtige anonyme Spenden") { addCssClasses("fw-bold small") }
    if (forwarding.isEmpty()) {
        panel.p("Keine anonymen Spenden über dem Schwellenwert im gewählten Kalenderjahr.") {
            addCssClasses("text-muted small")
        }
        return
    }

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1 small") }
    headerRow.div("Datum") { width = 110.px }
    headerRow.div("Betrag") { width = 120.px }
    headerRow.div("Pflicht") { addCssClasses("flex-grow-1") }

    forwarding.forEach { entry ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(entry.entryDate.toString()) { width = 110.px }
        row.div(formatMoney(entry.amount)) { width = 120.px }
        val dutyCell = row.div { addCssClasses("flex-grow-1") }
        dutyCell.statusBadge(
            donationDutyLabel(DonationDuty.ANONYMOUS_FORWARDING_REQUIRED),
            donationDutyColor(DonationDuty.ANONYMOUS_FORWARDING_REQUIRED),
        )
    }
}

// ============================================================================================
// Pure helpers -- covered by DonorsScreenTest.kt
// ============================================================================================

/** Same natural-person-categories-first ordering `LedgerScreen.renderNewEntryForm`'s member donor
 * block already applies to its `DonorCategory` picker (D13) -- kept as a local `val` here rather
 * than a shared export, since this screen is the only other caller and the ordering is a one-line
 * UX nicety, not a rule the server enforces (every [DonorCategory] literal stays selectable either
 * way). */
private val donorCategoryCreationOrder: List<DonorCategory> =
    listOf(DonorCategory.GERMAN_NATURAL_PERSON, DonorCategory.EU_NATURAL_PERSON, DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON)
        .let { naturalPersonFirst -> naturalPersonFirst + (DonorCategory.entries - naturalPersonFirst.toSet()) }

/** `DonorType` is single-screen (only this file's report table uses it), so per `AccountingLabels
 * .kt`'s own scoping rule its label/color table lives here rather than in that shared file. */
fun donorTypeLabel(type: DonorType): String =
    when (type) {
        DonorType.MEMBER -> "Mitglied"
        DonorType.EXTERNAL -> "Extern"
    }

fun donorTypeColor(type: DonorType): String =
    when (type) {
        DonorType.MEMBER -> "primary"
        DonorType.EXTERNAL -> "secondary"
    }

/**
 * D9: all three [DonationDuty] literals deliberately share the same `warning` hue -- the duties are
 * additive (0..3 may apply to the same donation simultaneously, see that enum's own KDoc), and
 * differently-colored badges would visually imply a severity ranking that does not exist.
 */
fun donationDutyLabel(duty: DonationDuty): String =
    when (duty) {
        DonationDuty.ANONYMOUS_FORWARDING_REQUIRED -> "Weiterleitungspflicht"
        DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED -> "Unverzügliche Meldepflicht"
        DonationDuty.ANNUAL_DISCLOSURE_REQUIRED -> "Offenlegungspflicht (Rechenschaftsbericht)"
    }

fun donationDutyColor(duty: DonationDuty): String {
    // Deliberate exception to "one hue per literal" -- see KDoc above.
    return when (duty) {
        DonationDuty.ANONYMOUS_FORWARDING_REQUIRED -> "warning"
        DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED -> "warning"
        DonationDuty.ANNUAL_DISCLOSURE_REQUIRED -> "warning"
    }
}

/** Shared "Straße, PLZ Ort, Land" address-line formatting for a fully-loaded [ExternalDonorDto] --
 * a donor with no address fields at all renders a plain, honest "Keine Adresse hinterlegt" instead
 * of an empty/blank line. */
fun donorAddressLine(donor: ExternalDonorDto): String {
    val streetLine = donor.street
    val cityLine = listOfNotNull(donor.postalCode, donor.city).joinToString(" ").takeIf { it.isNotBlank() }
    val parts = listOfNotNull(streetLine, cityLine, donor.country).filter { it.isNotBlank() }
    return if (parts.isEmpty()) "Keine Adresse hinterlegt" else parts.joinToString(", ")
}

private fun currentYear(): Int =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date.year
