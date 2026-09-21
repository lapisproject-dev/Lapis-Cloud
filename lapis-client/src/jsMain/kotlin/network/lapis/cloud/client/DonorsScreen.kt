package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
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
 * The donor detail panel ([selectDonor], wired from [renderDonorRow]'s "Details anzeigen" icon
 * button) deliberately re-fetches via `getExternalDonor(id)` on click rather than rendering the
 * address fields already present on the row's own [ExternalDonorDto] (returned in full by
 * `listExternalDonors`) -- this exercises the RPC method the plan's scope explicitly names ("Detail
 * view via `getExternalDonor(id)`") and gives a genuinely fresh read for a caller who has had the
 * list open for a while.
 *
 * **Nachtrag Design-Team-Sitzung 2026-09-18 (Nachmittag), siehe `DataScreenLayout.kt` KDoc:** die
 * Liste ist jetzt eine echte Tabelle (Spender/Kategorie/Status/Aktionen), das per-Zeile expandierende
 * `vPanel("border rounded p-2")`-Akkordeon ist entfallen -- eine Tabellenzeile traegt kein sauber
 * ausklappbares Detail-Panel. Stattdessen sitzt EIN Detail-Panel unterhalb der Tabelle, das die
 * Zeilenauswahl (`onSelect`/[selectDonor]) fuellt, analog zu [renderLedgerScreen]s
 * Kontenplan-Zeile → `accountDetailPanel`-Kontenauswahl.
 */
fun renderDonorsScreen(container: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)

    val root = container.dataScreenRoot()
    root.h1(tr("Spender"))
    root.div(
        tr(
            "Externe Spender sind keine Mitglieder -- eine eigenständige Adressverwaltung für " +
                "Spendenbescheinigungen und die §25-PartG-Pflichtenprüfung, getrennt von der " +
                "Mitgliederverwaltung.",
        ),
    ) { addCssClasses("text-muted small") }

    // ---- External donor list (Spenderstamm) -------------------------------------------------
    root.h2(tr("Externe Spender"))
    // Design-Team-Welle 2026-09-18: Live-Suche analog `LedgerScreen.kt`s Kontenplan-Suche -- rein
    // clientseitige Filterung ueber `displayName`, kein RPC-Roundtrip pro Tastendruck.
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val donorSearchInput = filterRow.text(label = tr("Spender suchen (Name)"))
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Spender anzeigen"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val statusRegion = root.dataStatusRegion()
    val listPanel = root.vPanel(spacing = 6)

    root.h2(tr("Spenderdetails"))
    val donorDetailPanel = root.vPanel(spacing = 10)
    donorDetailPanel.p(tr("Spender oben auswählen, um Details zu sehen."))

    var selectedDonorId: String? = null

    fun selectDonor(donor: ExternalDonorDto) {
        if (selectedDonorId == donor.id) {
            // Erneuter Klick auf denselben Spender schliesst das Detail-Panel wieder -- ersetzt die
            // vorherige Akkordeon-Zeile, die per erneutem Klick genauso zuklappte.
            selectedDonorId = null
            donorDetailPanel.removeAll()
            donorDetailPanel.p(tr("Spender oben auswählen, um Details zu sehen."))
            return
        }
        selectedDonorId = donor.id
        donorDetailPanel.removeAll()
        renderDonorDetail(donorDetailPanel, donor.id)
    }

    // Zuletzt geladene, ungefilterte Spenderliste -- die Live-Suche filtert auf dieser Kopie, statt
    // pro Tastendruck erneut `listExternalDonors` zu rufen (Muster von `LedgerScreen.kt`).
    var loadedDonors: List<ExternalDonorDto> = emptyList()

    // Vorwaertsreferenz analog `LedgerScreen.refreshAccounts`: `renderDonorList` reicht das Neuladen
    // als `onChanged` an jede Zeile weiter, `refreshList` ruft seinerseits `renderDonorList`.
    var refreshList: () -> Unit = {}

    var generation = 0
    var loading = false
    // Fehlerzustand des Abrufs: solange er steht, darf die Suche ihn weder wegzeichnen noch die Liste des
    // vorigen Filters darüber malen.
    var failed = false

    fun renderDonorList(query: String) {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): während eines laufenden Abrufs sagt allein die
        // Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld die zuletzt
        // geladene (jetzt veraltete) Liste unter den Ladehinweis -- zwei Zustände gleichzeitig.
        if (loading) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen.
        if (failed) return
        listPanel.removeAll()
        if (loadedDonors.isEmpty()) {
            countsLabel.content = ""
            // Welle V1.4.26 (W2): der Leertext nennt jetzt auch, welcher Ausschnitt gemeint ist -- eine
            // leere Liste bei aktivem „nur aktive"-Filter heisst nicht „noch keine Spender angelegt".
            val text =
                if (includeInactiveCheck.value) {
                    tr("Noch keine externen Spender angelegt.")
                } else {
                    tr("Kein aktiver externer Spender vorhanden. Inaktive Spender einblenden, um auch stillgelegte zu sehen.")
                }
            listPanel.p(text) { addCssClasses("text-muted") }
            return
        }
        val filtered = filterExternalDonors(loadedDonors, query)
        // Trefferzähler jetzt IMMER, nicht nur bei nicht-leerer Suche (R19/Richtlinie 2.4).
        countsLabel.content = gettext("%1 von %2 Spendern", filtered.size, loadedDonors.size)
        if (filtered.isEmpty()) {
            listPanel.p(gettext("Kein Spender passt zu \"%1\".", query.trim())) { addCssClasses("text-muted") }
            return
        }
        listPanel.dataTable(
            columns = donorColumns(),
            rows = filtered,
            actions = { actions, donor -> actions.renderDonorActions(donor, canManage, ::selectDonor) { refreshList() } },
        )
    }

    refreshList = {
        generation++
        val mine = generation
        listPanel.removeAll()
        countsLabel.content = ""
        statusRegion.showLoading()
        loading = true
        failed = false
        AppScope.launch {
            val donors =
                guarded {
                    rpcService<IAccountingService>().listExternalDonors(activeOnly = !includeInactiveCheck.value)
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat übernommen
            statusRegion.clearStatus()
            loading = false
            if (donors == null) {
                // Vorher blieb hier ein stumm leeres Panel zurück (`?: return@launch`).
                failed = true
                loadedDonors = emptyList()
                listPanel.dataErrorState(onRetry = { refreshList() })
                return@launch
            }
            loadedDonors = donors
            renderDonorList(donorSearchInput.value.orEmpty())
        }
    }
    refreshButton.onClick { refreshList() }
    // Der Inaktiv-Filter wirkt sofort (er ist eine Server-Abfrage, kein Client-Filter) -- Guard gegen das
    // synthetische erste `subscribe`-Ereignis, siehe `MemberAdministrationScreen`.
    var isInitialInactiveEvent = true
    includeInactiveCheck.subscribe {
        if (isInitialInactiveEvent) {
            isInitialInactiveEvent = false
            return@subscribe
        }
        refreshList()
    }

    // Kein Debounce -- rein clientseitige Filterung (gleiche Entscheidung wie `LedgerScreen.kt`).
    var isInitialSearchEvent = true
    donorSearchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        renderDonorList(value.orEmpty())
    }

    refreshList()

    if (canManage) {
        root.h2(tr("Neuen Spender anlegen"))
        renderDonorCreationForm(root) { refreshList() }
    }

    // ---- Spendenrecht-Pflichten-Report (§25 PartG) -------------------------------------------
    root.h2(tr("Spendenrecht-Pflichten-Report (§25 PartG)"))
    renderDonationDutyReportView(root)
}

// ============================================================================================
// List row + creation form
// ============================================================================================

/**
 * Design-Team-Welle 2026-09-18 (Nachmittag): vorher ein `vPanel("border rounded p-2")`-Kartenrow mit
 * eigenem In-Zeile-Akkordeon, jetzt eine echte Tabellenzeile -- Detail-Anzeige wandert an
 * [renderDonorsScreen]s `selectDonor`/`donorDetailPanel`, siehe file KDoc. Die Aktionsspalte wird
 * immer gerendert, damit alle Zeilen dieselbe Spaltenzahl behalten (Muster von
 * `LedgerScreen.renderAccountRow`).
 */
private fun donorColumns(): List<DataColumn<ExternalDonorDto>> =
    listOf(
        DataColumn(
            title = tr("Spender"),
            primary = true,
            cell = { container, donor -> container.span(donor.displayName) { addCssClass("fw-bold") } },
        ),
        DataColumn(
            title = tr("Kategorie"),
            cell = { container, donor ->
                container.typeBadge(donorCategoryLabel(donor.donorCategory), donorCategoryColor(donor.donorCategory))
            },
        ),
        DataColumn(
            title = tr("Status"),
            cell = { container, donor -> container.activeStatusBadge(donor.active) },
        ),
    )

/** Zeilenaktionen -- Rollen-Gate und Bestätigungsdialog unverändert gegenüber der Welle vom 2026-09-18. */
private fun Container.renderDonorActions(
    donor: ExternalDonorDto,
    canManage: Boolean,
    onSelect: (ExternalDonorDto) -> Unit,
    onChanged: () -> Unit,
) {
    val actionRow = tableActionGroup()
    val showButton = actionRow.tableActionButton("fas fa-eye", tr("Details anzeigen"))
    showButton.onClick { onSelect(donor) }

    if (!canManage || !donor.active) return
    val deactivateButton = actionRow.tableActionButton("fas fa-ban", tr("Deaktivieren"), ButtonStyle.OUTLINEDANGER)
    deactivateButton.onClick {
        confirmDialog(
            title = tr("Spender deaktivieren"),
            message =
                gettext(
                    "\"%1\" wirklich deaktivieren? Bestehende Buchungen mit diesem Spender bleiben " +
                        "erhalten, er steht aber für neue Buchungen nicht mehr zur Verfügung.",
                    donor.displayName,
                ),
            confirmLabel = tr("Deaktivieren"),
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IAccountingService>().deactivateExternalDonor(donor.id) }
                if (result != null) {
                    notifyInfo(tr("Spender wurde deaktiviert."))
                    onChanged()
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
    val displayNameInput = panel.text(label = tr("Name"))
    val categoryOptions =
        listOf("" to tr("-- Spenderkategorie wählen --")) + donorCategoryCreationOrder.map { it.name to donorCategoryLabel(it) }
    val categorySelect = panel.select(options = categoryOptions, value = "", label = tr("Spenderkategorie"))
    val streetInput = panel.text(label = tr("Straße (optional)"))
    val postalCodeInput = panel.text(label = tr("PLZ (optional)"))
    val cityInput = panel.text(label = tr("Ort (optional)"))
    val countryInput = panel.text(label = tr("Land (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val createButton = panel.button(tr("Spender anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val displayName = displayNameInput.value.orEmpty().trim()
        val categoryValue = categorySelect.value.orEmpty()
        val category = runCatching { DonorCategory.valueOf(categoryValue) }.getOrNull()

        if (!Validation.isNonBlank(displayName) || category == null) {
            errorBox.content = tr("Bitte Name und Spenderkategorie angeben.")
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
                notifySuccess(gettext("Spender \"%1\" wurde angelegt.", displayName))
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
        tr(
            "Zeigt offene Melde-/Offenlegungs-/Weiterleitungspflichten nach §25 PartG für ein " +
                "Kalenderjahr, damit der Vorstand sie manuell abarbeiten kann. Berechnet keine " +
                "unzulässigen Spenden -- solche werden beim Buchen serverseitig blockiert und können " +
                "hier nie erscheinen.",
        ),
    ) { addCssClasses("text-muted small") }

    val filterRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val yearControls = filterRow.fiscalYearFilter(currentYear = currentYear(), label = tr("Kalenderjahr (JJJJ)"))
    val loadButton = filterRow.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    // Welle V1.4.27 (W3): dataSection instead of a stuck "Wird geladen ..." on a failed load; the year validation
    // stays in front of the reload so an invalid year keeps its own message.
    val section =
        panel.dataSection<DonationDutyReportDto>(
            isEmpty = { false },
            load = {
                yearControls.parseYear()?.let { calendarYear ->
                    guarded { rpcService<IAccountingService>().getDonationDutyReport(calendarYear) }
                }
            },
            render = { body, report -> renderDonationDutyReportBody(body, report) },
        )

    fun load() {
        errorBox.hide()
        if (yearControls.parseYear() == null) {
            errorBox.content = tr("Bitte ein gültiges Kalenderjahr angeben (JJJJ).")
            errorBox.show()
            return
        }
        section.reload()
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
    panel.div(gettext("Kalenderjahr %1", report.calendarYear)) { addCssClasses("text-muted small") }

    if (!report.partyRulesApply) {
        panel.div(
            tr(
                "Diese Organisation ist nicht als politische Partei markiert (Organisationseinstellungen) " +
                    "-- §25 PartG gilt nur für politische Parteien. Für diese Organisation ist dieser " +
                    "Bericht daher bewusst ein No-Op, keine Fehlermeldung.",
            ),
        ) { addCssClasses("alert alert-light border") }
        return
    }

    renderDonorDutiesTable(panel, report.donorDuties)
    renderAnonymousForwardingTable(panel, report.anonymousForwarding)
}

private val DONOR_DUTY_HEADERS =
    listOf(
        TableHeader(title = tr("Spender")),
        TableHeader(title = tr("Typ")),
        TableHeader(title = tr("Kategorie")),
        TableHeader(title = tr("Jahressumme"), numeric = true),
        TableHeader(title = tr("Pflichten")),
    )

private val ANONYMOUS_FORWARDING_HEADERS =
    listOf(
        TableHeader(title = tr("Datum")),
        TableHeader(title = tr("Betrag"), numeric = true),
        TableHeader(title = tr("Pflicht")),
    )

/**
 * The §25-PartG report grids are documents (Welle V1.4.27, W3): a [reportTable] each -- the hand-built
 * `table-responsive` wrappers with `minWidth` are gone, the table's own frame scrolls it on a phone.
 */
internal fun renderDonorDutiesTable(
    panel: Container,
    duties: List<DonorDutyDto>,
) {
    if (duties.isEmpty()) {
        panel.p(tr("Melde-/Offenlegungspflichten pro Spender")) { addCssClasses("fw-bold small") }
        panel.p(tr("Keine offenen Melde-/Offenlegungspflichten im gewählten Kalenderjahr.")) {
            addCssClasses("text-muted small")
        }
        return
    }
    val report = panel.reportTable(caption = tr("Melde-/Offenlegungspflichten pro Spender"), headers = DONOR_DUTY_HEADERS)
    report.reportRows(donorDutyRows(duties), DONOR_DUTY_HEADERS)
}

/**
 * Explicitly NOT a prohibited-donation list -- those are hard-blocked at post time and can never
 * appear here (see [AnonymousDonationDutyDto] KDoc and this screen's file-level KDoc); the caption
 * below states that plainly rather than letting the table's presence imply otherwise.
 */
internal fun renderAnonymousForwardingTable(
    panel: Container,
    forwarding: List<AnonymousDonationDutyDto>,
) {
    if (forwarding.isEmpty()) {
        panel.p(tr("Weiterleitungspflichtige anonyme Spenden")) { addCssClasses("fw-bold small") }
        panel.p(tr("Keine anonymen Spenden über dem Schwellenwert im gewählten Kalenderjahr.")) {
            addCssClasses("text-muted small")
        }
        return
    }
    val report = panel.reportTable(caption = tr("Weiterleitungspflichtige anonyme Spenden"), headers = ANONYMOUS_FORWARDING_HEADERS)
    report.reportRows(anonymousForwardingRows(forwarding), ANONYMOUS_FORWARDING_HEADERS)
}

// ============================================================================================
// Pure helpers -- covered by DonorsScreenTest.kt
// ============================================================================================

/**
 * Reines, DOM-unabhaengiges Filter-Praedikat der Spender-Live-Suche -- testbar ohne
 * Rendering-Harness (analog [filterLedgerAccounts]/`filterCostCenters`).
 *
 * Gesucht wird nur ueber [ExternalDonorDto.displayName] -- anders als beim Kontenplan/den
 * Kostenstellen hat ein Spender keine zweite, kurze Kennung wie eine Kontonummer oder einen Code.
 * `ignoreCase = true`: der Gelegenheitsnutzer tippt nicht auf Grossschreibung.
 */
internal fun filterExternalDonors(
    donors: List<ExternalDonorDto>,
    query: String,
): List<ExternalDonorDto> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return donors
    return donors.filter { it.displayName.contains(trimmed, ignoreCase = true) }
}

/** Same natural-person-categories-first ordering `LedgerScreen.renderNewEntryForm`'s member donor
 * block already applies to its `DonorCategory` picker (D13) -- kept as a local `val` here rather
 * than a shared export, since this screen is the only other caller and the ordering is a one-line
 * UX nicety, not a rule the server enforces (every [DonorCategory] literal stays selectable either
 * way). */
private val donorCategoryCreationOrder: List<DonorCategory> =
    listOf(DonorCategory.GERMAN_NATURAL_PERSON, DonorCategory.EU_NATURAL_PERSON, DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON)
        .let { naturalPersonFirst -> naturalPersonFirst + (DonorCategory.entries - naturalPersonFirst.toSet()) }

/** `DonorType` is single-screen (only this file's report table uses it), so per `AccountingLabels
 * .kt`'s own scoping rule its label/color table lives here rather than in that shared file.
 *
 * gettext() here (not tr()), not decorative -- these functions return a plain String consumed
 * directly by callers/tests, not passed straight into a widget constructor. tr() defers
 * resolution to widget-render time via a marker prefix; a bare returned String never gets that
 * resolution pass, so gettext()'s immediate resolution is required. See I18nCatalogManager's KDoc
 * and TestI18nSetup.kt's KDoc for the concrete regression this fixes. */
fun donorTypeLabel(type: DonorType): String =
    when (type) {
        DonorType.MEMBER -> gettext("Mitglied")
        DonorType.EXTERNAL -> gettext("Extern")
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
        DonationDuty.ANONYMOUS_FORWARDING_REQUIRED -> gettext("Weiterleitungspflicht")
        DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED -> gettext("Unverzügliche Meldepflicht")
        DonationDuty.ANNUAL_DISCLOSURE_REQUIRED -> gettext("Offenlegungspflicht (Rechenschaftsbericht)")
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
    return if (parts.isEmpty()) gettext("Keine Adresse hinterlegt") else parts.joinToString(", ")
}

private fun currentYear(): Int =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date.year

/**
 * The detail of one external donor, re-fetched via `getExternalDonor(id)` (see the file KDoc: a genuinely fresh read).
 *
 * Audit V1.4.27 (F): `dataSection` instead of "Wird geladen ..." followed by an EMPTY panel (`fresh == null -> return`)
 * when the load fails -- the panel shows the error state with "Erneut versuchen". Every selection builds a NEW section in
 * the freshly cleared [panel], so a late answer of an earlier selection lands in a widget that is already gone and can
 * never overwrite the current detail (what the old `selectedDonorId` re-check guarded).
 */
internal fun renderDonorDetail(
    panel: SimplePanel,
    donorId: String,
) {
    panel
        .dataSection<ExternalDonorDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IAccountingService>().getExternalDonor(donorId) } },
            render = { body, fresh ->
                body.h2(fresh.displayName) { addCssClass("h6") }
                body.div(donorAddressLine(fresh)) { addCssClasses("text-muted small") }
            },
        ).reload()
}
