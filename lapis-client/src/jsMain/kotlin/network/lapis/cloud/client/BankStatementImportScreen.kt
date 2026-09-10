package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportDto
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementImportResultDto
import network.lapis.cloud.shared.domain.BankStatementLineDto
import network.lapis.cloud.shared.domain.BankStatementLineQuery
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementMatchCandidateDto
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IBankStatementService
import network.lapis.cloud.shared.rpc.IMemberService

private const val IMPORT_PAGE_SIZE = 20
private const val LINE_PAGE_SIZE = 50
private const val SEARCH_DEBOUNCE_MS = 300
private const val SEARCH_MIN_CHARS = 3

/**
 * Welle V1.4.5.1.1 -- `/bank-import`, TREASURER/BOARD/ADMIN lesend, TREASURER/ADMIN buchend
 * ([BankStatementAuthzUi]). EINE Flaeche, keine Tabs: es gibt hier genau eine Sache -- ein Import
 * und seine Zeilen; alles andere ist Navigation dorthin (Design-Team, Raskin/Tesler).
 *
 * [selectedImportIdParam] kommt aus `?import=<uuid>` im Hash-Fragment (Muster `MEMBER_HONORS`),
 * damit ein Zustand verlinkbar ist ("schau dir Import X an"). Die Zuordnungsarbeit (Zone 3) ist der
 * Standardzustand, nicht der Upload (Duarte) -- der Upload-Bereich startet eingeklappt.
 *
 * Pragmatische Abweichung vom Wellen-Plan: die "aufgeklappte Zeile" der Zuordnungs-Arbeitsflaeche
 * ist hier ein EIGENER Bereich unterhalb der Tabelle (nicht eine inline expandierte Tabellenzeile)
 * -- gleiche Wirkung (genau eine Zeile ist gerade in Bearbeitung), einfacher zu bauen und zu lesen
 * in diesem Modul ohne eigenes Tabellenzeilen-Expansions-Framework.
 */
fun renderBankStatementImportScreen(
    container: SimplePanel,
    selectedImportIdParam: String?,
) {
    val canWrite = BankStatementAuthzUi.canWrite(AppState.session?.role)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 1100.px
            marginTop = 24.px
        }

    // Zone 1 -- Kopfzeile.
    val headerRow = root.hPanel(spacing = 12) { addCssClasses("align-items-center justify-content-between") }
    headerRow.h1(tr("Kontoauszüge"))
    val uploadPanel =
        root.vPanel(spacing = 6) {
            addCssClasses("border rounded p-3")
            hide()
        }
    if (canWrite) {
        val uploadToggle = headerRow.button(tr("Auszug hochladen"), style = ButtonStyle.PRIMARY)
        uploadToggle.onClick { if (uploadPanel.visible) uploadPanel.hide() else uploadPanel.show() }
    } else {
        headerRow.div(tr("Sie sehen diese Seite mit Leserechten.")) { addCssClasses("text-muted small") }
    }

    // Zone 2 -- Import-Auswahl.
    val resultBannerHost = root.vPanel(spacing = 4)
    val importPickerHost = root.vPanel(spacing = 4)
    val loadMoreImportsButton = root.button(tr("Weitere Importe laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    // Zone 3 -- Zeilen-Arbeitsflaeche.
    val filterRow = root.hPanel(spacing = 6) { addCssClasses("flex-wrap align-items-center") }
    val includeNonPositiveCheck = root.checkBox(label = tr("Auch Abbuchungen anzeigen"))
    val linesHost = root.vPanel(spacing = 8) { hide() }
    val lineTableHost = linesHost.vPanel(spacing = 4)
    val loadMoreLinesButton = linesHost.button(tr("Weitere Zeilen laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    val workbenchHost = linesHost.vPanel(spacing = 8)

    var selectedImportId: String? = selectedImportIdParam
    var importOffset = 0
    val loadedImports = mutableListOf<BankStatementImportDto>()
    // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): `selectImport` used to only set `selectedImportId`
    // and reload the lines/banner, never touching the picker rows already on screen -- the
    // `border-primary` highlight was evaluated ONCE at row-build time (see `renderImports` below)
    // and then never revisited, so after a click it kept marking the PREVIOUSLY selected import,
    // never the new one. Tracked here so `selectImport` can move the highlight without a full
    // `renderImports(reset = true)` (which would also throw away already-paginated-in rows).
    val importRowById = mutableMapOf<String, Div>()
    var lineStatusFilter: BankStatementLineStatus? = null
    var lineOffset = 0
    lateinit var lineTable: Table

    // Review fix (Welle V1.4.5.1, MAJOR finding, Runde 2): neither `loadLines` nor `renderImports`
    // guarded against a stale in-flight response overwriting what a NEWER call already rendered --
    // same race as "Race beim Anbieterwechsel" in `AccountingExportScreen.kt` (`loadGeneration`),
    // just untreated here. Each function claims the next generation synchronously before suspending;
    // a response that returns after a later call already claimed a newer generation is discarded
    // instead of rendered/counted, so a fast import switch, a fast filter-chip switch, or a
    // double-click on "Weitere Zeilen laden" / "Weitere Importe laden" can no longer interleave rows
    // from two different imports/pages into the same table, nor double-advance the offset.
    var lineLoadGeneration = 0
    var importLoadGeneration = 0

    var members: List<MemberSummaryDto> = emptyList()
    var externalDonors: List<ExternalDonorDto> = emptyList()

    fun setUrl(importId: String?) {
        window.history.replaceState(null, "", bankStatementImportHash(importId))
    }

    fun loadLines(reset: Boolean) {
        val importId = selectedImportId
        if (importId == null) {
            linesHost.hide()
            return
        }
        linesHost.show()
        if (reset) {
            lineOffset = 0
            workbenchHost.removeAll()
            lineTableHost.removeAll()
            val headers =
                if (canWrite) {
                    listOf(tr("Buchungsdatum"), tr("Betrag"), tr("Gegenpartei"), tr("Verwendungszweck"), tr("Status"), tr("Aktionen"))
                } else {
                    listOf(tr("Buchungsdatum"), tr("Betrag"), tr("Gegenpartei"), tr("Verwendungszweck"), tr("Status"))
                }
            lineTable = lineTableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))
        }
        val generation = ++lineLoadGeneration
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IBankStatementService>().listLines(
                        BankStatementLineQuery(
                            importId = importId,
                            status = lineStatusFilter,
                            includeNonPositiveAmounts = includeNonPositiveCheck.value,
                            limit = LINE_PAGE_SIZE,
                            offset = lineOffset,
                        ),
                    )
                } ?: return@launch
            if (generation != lineLoadGeneration) return@launch
            page.rows.forEach { line ->
                renderLineRow(lineTable, line, canWrite) {
                    renderAssignmentWorkbench(workbenchHost, line, members, externalDonors) { loadLines(reset = true) }
                }
            }
            lineOffset += page.rows.size
            if (lineOffset >= page.totalCount || page.rows.isEmpty()) loadMoreLinesButton.hide() else loadMoreLinesButton.show()
        }
    }
    // Review fix (Welle V1.4.5.1, MAJOR finding): the button was rendered and shown/hidden but never
    // wired -- `loadLines(reset = false)` was dead code, making the "reset=false" pagination path for
    // the line list unreachable for any import with more than one page. Wired here (after `loadLines`
    // is declared), same placement precedent `loadMoreImportsButton.onClick { renderImports(reset =
    // false) }` already establishes further down for the sibling import-list pagination button.
    loadMoreLinesButton.onClick { loadLines(reset = false) }

    fun rebuildFilterRow() {
        filterRow.removeAll()
        val entries: List<Pair<BankStatementLineStatus?, String>> =
            listOf(null to tr("Alle")) + BankStatementLineStatus.entries.map { it to bankStatementLineStatusLabel(it) }
        entries.forEach { (status, label) ->
            val active = status == lineStatusFilter
            val chip = filterRow.button(label, style = if (active) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY)
            chip.onClick {
                lineStatusFilter = status
                rebuildFilterRow()
                loadLines(reset = true)
            }
        }
    }
    rebuildFilterRow()
    // Review fix (Welle V1.4.5.1, MAJOR finding): KVision's `CheckInput.subscribe` invokes the
    // observer IMMEDIATELY with the field's current value on registration, not just on later user
    // input -- same guard idiom already established in `MemberFamiliesScreen.kt` and
    // `MemberAdministrationScreen.kt` (`isInitialSearchEvent`). Without it, this synthetic first
    // call fired `loadLines(reset = true)` a SECOND time on top of the explicit call further down
    // (deep-link `?import=<uuid>`, line 263) or right after `selectImport`/`onImported` -- since
    // `lineTable` is a captured `lateinit var`, the first in-flight coroutine keeps writing into
    // the table the second call just rebuilt, doubling every rendered row and leaving `lineOffset`
    // at 2*N instead of N (hiding "Weitere Zeilen laden" too early once 2*N >= totalCount).
    var isInitialIncludeNonPositiveEvent = true
    includeNonPositiveCheck.subscribe {
        if (isInitialIncludeNonPositiveEvent) {
            isInitialIncludeNonPositiveEvent = false
            return@subscribe
        }
        loadLines(reset = true)
    }

    fun selectImport(importId: String) {
        // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): move the `border-primary` highlight off the
        // OLD selection and onto the new one -- see `importRowById` KDoc above. `[selectedImportId]`
        // still holds the old id at this point (only reassigned on the next line), and a row from a
        // page loaded AFTER a switch (or a deep-link id with no matching row on screen at all) simply
        // has no map entry, so both lookups safely no-op via `?.`.
        importRowById[selectedImportId]?.removeCssClass("border-primary")
        importRowById[importId]?.addCssClass("border-primary")
        selectedImportId = importId
        setUrl(importId)
        // Review fix (Welle V1.4.5.1, MINOR finding): only `onImported` cleared the result banner,
        // so picking a different import from the list left the previous import's summary numbers
        // (e.g. "A: 42 Zeilen -- 12 automatisch gebucht ...") displayed above the now-switched line
        // table -- wrong figures shown right over an accounting-relevant screen.
        resultBannerHost.removeAll()
        lineStatusFilter = null
        rebuildFilterRow()
        loadLines(reset = true)
    }

    fun renderImports(reset: Boolean) {
        if (reset) {
            importOffset = 0
            loadedImports.clear()
            importPickerHost.removeAll()
            importRowById.clear()
        }
        val generation = ++importLoadGeneration
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IBankStatementService>().listImports(limit = IMPORT_PAGE_SIZE, offset = importOffset)
                } ?: return@launch
            if (generation != importLoadGeneration) return@launch
            page.rows.forEach { import ->
                loadedImports += import
                val row =
                    importPickerHost.div {
                        addCssClasses("border rounded p-2 mb-1 lapis-clickable-row")
                        if (import.id == selectedImportId) addCssClass("border-primary")
                    }
                importRowById[import.id] = row
                val formatLabel =
                    if (import.format == network.lapis.cloud.shared.domain.BankStatementFormat.CSV) {
                        bankCsvDialectLabel(import.dialect)
                    } else {
                        bankStatementFormatLabel(import.format)
                    }
                row.div(
                    gettext(
                        "%1 · %2 · %3 Zeilen, %4 automatisch gebucht",
                        import.fileName,
                        formatLabel,
                        import.lineCount,
                        import.autoPostedCount,
                    ),
                ) { addCssClass("fw-bold") }
                val secondLineParts =
                    listOfNotNull(
                        import.accountIbanMasked,
                        import.statementFrom?.let { from -> import.statementTo?.let { to -> gettext("%1 – %2", from, to) } },
                        import.uploadedByDisplayName,
                        import.uploadedAt.toString(),
                    )
                row.div(secondLineParts.joinToString(" · ")) { addCssClasses("text-muted small") }
                row.onClick { selectImport(import.id) }
            }
            importOffset += page.rows.size
            if (importOffset >= page.totalCount || page.rows.isEmpty()) loadMoreImportsButton.hide() else loadMoreImportsButton.show()
            if (loadedImports.isEmpty()) {
                importPickerHost.div(tr("Noch keine Kontoauszüge importiert.")) { addCssClasses("text-muted small") }
            }
        }
    }
    loadMoreImportsButton.onClick { renderImports(reset = false) }

    // §5.4 -- nach einem erfolgreichen Upload: Sprung zur Zuordnungsarbeit, Filter auf "zu prüfen"
    // vorgewaehlt (OF-4: EIN Aufruf statt einer clientseitigen Zwei-Status-Zusammenfuehrung).
    fun onImported(result: BankStatementImportResultDto) {
        selectedImportId = result.importId
        setUrl(result.importId)
        resultBannerHost.removeAll()
        renderResultBanner(resultBannerHost, result)
        lineStatusFilter =
            when {
                result.ambiguousCount > 0 -> BankStatementLineStatus.AMBIGUOUS
                result.unmatchedCount > 0 -> BankStatementLineStatus.UNMATCHED
                else -> null
            }
        rebuildFilterRow()
        renderImports(reset = true)
        loadLines(reset = true)
        uploadPanel.hide()
    }

    renderUploadPanel(uploadPanel) { result -> onImported(result) }
    renderImports(reset = true)

    AppScope.launch {
        members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        externalDonors = guarded { rpcService<IAccountingService>().listExternalDonors(activeOnly = true) } ?: emptyList()
    }

    if (selectedImportId != null) loadLines(reset = true)
}

/** Rein, DOM-frei, testbar: der Hash, den die Auswahl eines Imports in die URL schreibt. */
internal fun bankStatementImportHash(importId: String?): String =
    if (importId.isNullOrBlank()) "#${Routes.BANK_IMPORT}" else "#${Routes.BANK_IMPORT}?import=$importId"

private fun renderUploadPanel(
    panel: SimplePanel,
    onImported: (BankStatementImportResultDto) -> Unit,
) {
    panel.p(tr("CSV (Sparkassen-CAMT-Export, generischer Auffangdialekt) oder MT940, max. 5 MB.")) { addCssClasses("text-muted small") }
    val fileUpload = panel.upload(label = tr("Datei auswählen"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val uploadButton = panel.button(tr("Hochladen"), style = ButtonStyle.PRIMARY)
    uploadButton.onClick {
        errorBox.hide()
        val selected = fileUpload.value?.firstOrNull()
        val nativeFile = selected?.let { fileUpload.getNativeFile(it) }
        if (nativeFile == null) {
            errorBox.content = tr("Bitte eine Datei auswählen.")
            errorBox.show()
            return@onClick
        }
        if (exceedsBankStatementUploadLimit(nativeFile.size.toLong())) {
            errorBox.content =
                bankStatementRejectionMessage(BankStatementImportRejectionDto(code = BankStatementRejectionCode.FILE_TOO_LARGE))
            errorBox.show()
            return@onClick
        }
        uploadButton.disabled = true
        AppScope.launch {
            when (val outcome = BankStatementHttp.import(nativeFile)) {
                is BankStatementImportOutcome.Success -> {
                    notifySuccess(tr("Auszug importiert."))
                    fileUpload.clearInput()
                    uploadButton.disabled = false
                    onImported(outcome.result)
                }
                is BankStatementImportOutcome.Rejected -> {
                    uploadButton.disabled = false
                    val rejection = outcome.rejection
                    val details = listOfNotNull(bankStatementRejectionMessage(rejection), rejection.rawLineExcerpt)
                    errorBox.content = details.joinToString(" ")
                    errorBox.show()
                }
                is BankStatementImportOutcome.Other -> {
                    uploadButton.disabled = false
                    errorBox.content = outcome.message
                    errorBox.show()
                }
            }
        }
    }
}

private fun renderResultBanner(
    host: SimplePanel,
    result: BankStatementImportResultDto,
) {
    val banner = host.div { addCssClasses("border rounded p-3 bg-body-tertiary") }
    banner.div(
        gettext(
            "%1 Zeilen -- %2 automatisch gebucht, %3 Vorschläge, %4 mehrdeutig, %5 nicht zugeordnet, %6 ignoriert, %7 bereits vorhanden.",
            result.lineCount,
            result.autoPostedCount,
            result.suggestedCount,
            result.ambiguousCount,
            result.unmatchedCount,
            result.ignoredCount,
            result.duplicateCount,
        ),
    ) { addCssClass("fw-bold") }
    // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): rendered `result.warnings` directly -- raw,
    // untranslated German server prose (one variant even named the internal env var
    // `LAPIS_SECRET_ENCRYPTION_KEY` in the UI). The displayed message now always comes from
    // `warningCodes` via `bankStatementImportWarningMessage` (BankStatementLabels.kt), same split
    // `bankStatementRejectionMessage`/`BankStatementImportRejectionDto.detail` already established.
    if (result.warningCodes.isNotEmpty()) {
        val list = banner.div()
        result.warningCodes.forEach { code -> list.div(bankStatementImportWarningMessage(code)) { addCssClasses("text-muted small") } }
    }
}

private fun renderLineRow(
    table: Table,
    line: BankStatementLineDto,
    canWrite: Boolean,
    onEdit: () -> Unit,
) {
    table.row {
        cell(line.bookingDate.toString())
        cell(formatMoney(line.amount))
        cell {
            div(line.counterpartyName ?: "—")
            line.counterpartyIbanMasked?.let { masked -> div(masked) { addCssClasses("text-muted small") } }
        }
        cell {
            div(line.purpose.orEmpty())
            // Known gap, NOT part of this wave's i18n coverage (Review finding, Welle V1.4.5.1.1
            // Runde 2, see README.adoc "Bank Statement Import" > "What doesn't work yet"):
            // `matchExplanation` embeds per-line dynamic data (member names, amounts, dates,
            // reference codes) computed AND PERSISTED server-side in `BankStatementMatcher` --
            // unlike the fixed `BankStatementImportWarningCode`/`BankStatementRejectionCode`
            // strings, a structured/translatable replacement needs its own DB migration
            // (code + params, not a plain string column) and is deliberately deferred to a
            // follow-up wave rather than rushed into this fix.
            line.matchExplanation?.let { explanation -> div(explanation) { addCssClasses("text-muted small") } }
        }
        cell { statusBadge(bankStatementLineStatusLabel(line.status), bankStatementLineStatusColor(line.status)) }
        if (canWrite) {
            cell {
                if (line.status != BankStatementLineStatus.POSTED && line.status != BankStatementLineStatus.IGNORED) {
                    val editButton = button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
                    editButton.onClick { onEdit() }
                }
            }
        }
    }
}

/**
 * Zuordnungs-Arbeitsflaeche fuer genau eine Zeile -- Vorschlaege ([IBankStatementService
 * .suggestMatches]), Freitextsuche ([IBankStatementService.searchAssignmentTargets], entprellt ab
 * [SEARCH_MIN_CHARS] Zeichen), Spendenzuordnung (K6: Mitglied ODER externer Spender, KEINE
 * `ANONYMOUS`-Kategorie -- OF-3, eine Ueberweisung von einem benannten Konto ist nie "ausdruecklich
 * anonym", `LedgerScreen.collectDonor()` schliesst dieselbe Kombination bereits aktiv aus), und
 * Ignorieren (Pflichtbegruendung, [confirmWithReasonDialog]).
 */
private fun renderAssignmentWorkbench(
    host: SimplePanel,
    line: BankStatementLineDto,
    members: List<MemberSummaryDto>,
    externalDonors: List<ExternalDonorDto>,
    onChanged: () -> Unit,
) {
    host.removeAll()
    val panel = host.vPanel(spacing = 10) { addCssClasses("border rounded p-3") }
    panel.div(gettext("Zeile vom %1 -- %2", line.bookingDate.toString(), formatMoney(line.amount))) { addCssClass("fw-bold") }

    val noteInput = panel.text(label = tr("Notiz (optional)"))

    panel.div(tr("Vorschläge")) { addCssClasses("fw-bold mt-2") }
    val suggestionsHost = panel.vPanel(spacing = 4)
    suggestionsHost.div(tr("Lade Vorschläge …")) { addCssClasses("text-muted small") }

    fun renderCandidates(
        target: SimplePanel,
        candidates: List<BankStatementMatchCandidateDto>,
    ) {
        target.removeAll()
        if (candidates.isEmpty()) {
            target.div(tr("Keine Treffer.")) { addCssClasses("text-muted small") }
            return
        }
        candidates.forEach { candidate ->
            val row = target.hPanel(spacing = 8) { addCssClasses("align-items-center border-bottom py-1") }
            row.div(
                gettext(
                    "%1 -- %2, %3 – %4, %5",
                    candidate.memberDisplayName,
                    candidate.membershipTierName,
                    candidate.periodStart.toString(),
                    candidate.periodEnd.toString(),
                    formatMoney(candidate.amountDue),
                ),
            )
            val assignButton = row.button(tr("Diesem Beitrag zuordnen"), style = ButtonStyle.OUTLINESECONDARY)
            assignButton.onClick {
                AppScope.launch {
                    val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }
                    guarded {
                        rpcService<IBankStatementService>().assignLineToContribution(
                            lineId = line.id,
                            contributionId = candidate.contributionId,
                            note = note,
                        )
                    } ?: return@launch
                    notifySuccess(tr("Zeile zugeordnet."))
                    onChanged()
                }
            }
        }
    }

    AppScope.launch {
        val suggestions = guarded { rpcService<IBankStatementService>().suggestMatches(line.id) } ?: emptyList()
        renderCandidates(suggestionsHost, suggestions)
    }

    panel.div(tr("Freitextsuche")) { addCssClasses("fw-bold mt-2") }
    val searchInput = panel.text(label = tr("Mitgliedsname oder Beitragssatz"))
    val searchResultsHost = panel.vPanel(spacing = 4)
    var searchDebounceHandle: Int? = null
    var isInitialSearchEvent = true
    // Review fix (MINOR, Welle V1.4.5.1.1 Runde 2): `window.clearTimeout` only cancels a still-
    // PENDING debounce timer -- it cannot cancel a request that has already been sent and is now
    // in flight. Two searches fired close together ("Mül" then "Müller") can race on the network
    // (mobile latency, server load), and the SLOWER one used to win because `renderCandidates`
    // (:446) unconditionally replaces the panel's content with whatever answer arrives last,
    // regardless of arrival order -- same class of bug `lineLoadGeneration`/`importLoadGeneration`
    // above were introduced to close for `loadLines`/`renderImports`. Same fix shape: claim the
    // next generation synchronously (here: at debounce-fire time, since that's the actual dispatch
    // point), discard a response whose generation is no longer current.
    var searchGeneration = 0
    searchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        searchDebounceHandle?.let { window.clearTimeout(it) }
        val term = value.orEmpty().trim()
        if (term.length < SEARCH_MIN_CHARS) {
            // Review fix (MINOR, Runde 3): also bump the generation here, not just in the
            // setTimeout branch below -- otherwise a still-in-flight request from a longer term
            // (fired before the user backspaced below SEARCH_MIN_CHARS) arrives after this branch
            // has already cleared the list and renders its stale results into what the input now
            // shows as an empty/too-short search.
            ++searchGeneration
            searchResultsHost.removeAll()
            return@subscribe
        }
        searchDebounceHandle =
            window.setTimeout({
                val generation = ++searchGeneration
                AppScope.launch {
                    val results = guarded { rpcService<IBankStatementService>().searchAssignmentTargets(term, limit = 20) } ?: emptyList()
                    if (generation != searchGeneration) return@launch
                    renderCandidates(searchResultsHost, results)
                }
            }, SEARCH_DEBOUNCE_MS)
    }

    panel.div(tr("Als Spende zuordnen")) { addCssClasses("fw-bold mt-2") }
    val donorTypeSelect =
        panel.select(
            options = listOf("MEMBER" to tr("Mitglied"), "EXTERNAL" to tr("Externer Spender")),
            value = "MEMBER",
            label = tr("Spendertyp"),
        )
    val memberDonorPanel = panel.vPanel(spacing = 4)
    val memberSelect = memberDonorPanel.select(options = members.map { it.id to it.displayName }, label = tr("Mitglied"))
    val naturalPersonFirst =
        listOf(DonorCategory.GERMAN_NATURAL_PERSON, DonorCategory.EU_NATURAL_PERSON, DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON)
    // OF-3: ANONYMOUS bewusst NICHT in dieser Liste -- siehe Funktions-KDoc.
    val donorCategoryOrder = naturalPersonFirst + (DonorCategory.entries - naturalPersonFirst.toSet() - DonorCategory.ANONYMOUS)
    val donorCategoryOptions =
        listOf("" to tr("-- Spenderkategorie wählen --")) + donorCategoryOrder.map { it.name to donorCategoryLabel(it) }
    val donorCategorySelect = memberDonorPanel.select(options = donorCategoryOptions, value = "", label = tr("Spenderkategorie"))
    val externalDonorPanel = panel.vPanel(spacing = 4)
    val externalSelect = externalDonorPanel.select(options = externalDonors.map { it.id to it.displayName }, label = tr("Externer Spender"))

    fun applyDonorGating(choice: String?) {
        if (choice == "MEMBER") memberDonorPanel.show() else memberDonorPanel.hide()
        if (choice == "EXTERNAL") externalDonorPanel.show() else externalDonorPanel.hide()
    }
    applyDonorGating(donorTypeSelect.value)
    donorTypeSelect.subscribe { applyDonorGating(it) }

    val donationErrorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val assignDonationButton = panel.button(tr("Als Spende zuordnen"), style = ButtonStyle.OUTLINESECONDARY)
    assignDonationButton.onClick {
        donationErrorBox.hide()
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }
        val input =
            when (donorTypeSelect.value) {
                "MEMBER" -> {
                    val memberId = memberSelect.value
                    val category =
                        donorCategorySelect.value
                            ?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { DonorCategory.valueOf(it) }.getOrNull() }
                    if (memberId == null || category == null) {
                        donationErrorBox.content = tr("Bitte Mitglied und Spenderkategorie wählen.")
                        donationErrorBox.show()
                        null
                    } else {
                        BankStatementDonationAssignmentInput(donorMemberId = memberId, donorCategory = category, note = note)
                    }
                }
                "EXTERNAL" -> {
                    val externalId = externalSelect.value
                    if (externalId == null) {
                        donationErrorBox.content = tr("Bitte einen externen Spender wählen.")
                        donationErrorBox.show()
                        null
                    } else {
                        val donor = externalDonors.firstOrNull { it.id == externalId }
                        BankStatementDonationAssignmentInput(
                            externalDonorId = externalId,
                            donorCategory = donor?.donorCategory ?: DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION,
                            note = note,
                        )
                    }
                }
                else -> null
            }
        if (input != null) {
            AppScope.launch {
                guarded { rpcService<IBankStatementService>().assignLineToDonation(lineId = line.id, input = input) } ?: return@launch
                notifySuccess(tr("Zeile als Spende zugeordnet."))
                onChanged()
            }
        }
    }

    panel.div(tr("Ignorieren")) { addCssClasses("fw-bold mt-2") }
    val ignoreButton = panel.button(tr("Zeile ignorieren"), style = ButtonStyle.DANGER)
    ignoreButton.onClick {
        confirmWithReasonDialog(
            title = tr("Zeile ignorieren"),
            message = tr("Diese Zeile wird als ignoriert markiert und aus der weiteren Zuordnungsarbeit entfernt."),
            dangerNote = tr("Diese Aktion ist unumkehrbar -- eine Korrektur ist danach nur per Storno in der Buchhaltung möglich."),
            reasonLabel = tr("Begründung"),
            reasonRequired = true,
            confirmLabel = tr("Zeile ignorieren"),
        ) { reason ->
            AppScope.launch {
                guarded { rpcService<IBankStatementService>().ignoreLine(lineId = line.id, reason = reason.orEmpty()) } ?: return@launch
                notifySuccess(tr("Zeile ignoriert."))
                onChanged()
            }
        }
    }
}
