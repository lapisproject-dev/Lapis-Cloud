package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountingExportBlockerKind
import network.lapis.cloud.shared.domain.AccountingExportConnectionDto
import network.lapis.cloud.shared.domain.AccountingExportItemDto
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportPreviewDto
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunDto
import network.lapis.cloud.shared.domain.AccountingExportRunStatus
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import network.lapis.cloud.shared.rpc.IAccountingExportService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- fifth toggle on the Finanzberichte screen, alongside
 * GuV/Bilanz/Jahresabschluss/DATEV-Export (see `FinancialReportsScreen.renderFinancialReportsScreen`).
 * Modeless throughout (Raskin/Tesler: no confirmation dialog anywhere on this screen), same posture
 * [renderDatevExportView] already establishes for its own preview-then-download flow.
 *
 * A DELIBERATE scope simplification versus the original design memo: connection management (token,
 * connection test, zero-VAT disclaimer, category mapping) lives on THIS SAME screen rather than
 * split across `LedgerScreen.kt`'s Kontenzuordnung section -- one self-contained view is easier to
 * reason about and keeps this wave's client-side footprint to one new file, at the cost of the
 * connection settings not sitting next to the (unrelated) DATEV Berater-/Mandantennummer fields.
 * Revisit if a later wave (e.g. the sevDesk adapter, V1.4.5.4) makes a shared connection-settings
 * area worthwhile.
 */
fun renderAccountingExportView(panel: SimplePanel) {
    val role = AppState.session?.role
    panel.h2(tr("Lexware Office"))
    if (!AccountingExportAuthzUi.canManage(role)) {
        panel.p(tr("Diese Ansicht ist Schatzmeister/Admin vorbehalten.")) { addCssClasses("text-muted") }
        return
    }

    val provider = AccountingExportProvider.LEXOFFICE
    val connectionPanel = panel.vPanel(spacing = 6)
    panel.div { addCssClass("mt-3") }
    val exportPanel = panel.vPanel(spacing = 10)

    fun reloadConnection() {
        connectionPanel.removeAll()
        exportPanel.removeAll()
        AppScope.launch {
            val connection = guarded { rpcService<IAccountingExportService>().getConnection(provider) } ?: return@launch
            renderConnectionSection(connectionPanel, provider, connection) { reloadConnection() }
            renderExportSection(exportPanel, provider, connection)
        }
    }
    reloadConnection()
}

// ============================================================================================
// Connection (Token, Verbindungstest, 0%-USt-Quittung)
// ============================================================================================

private fun renderConnectionSection(
    panel: SimplePanel,
    provider: AccountingExportProvider,
    connection: AccountingExportConnectionDto,
    onChanged: () -> Unit,
) {
    panel.h3(tr("Verbindung"))
    if (connection.connected) {
        panel.div(
            gettext(
                "Verbunden mit: %1",
                connection.connectedCompanyName ?: tr("(Name unbekannt -- lexoffice hat keinen Firmennamen geliefert)"),
            ),
        ) { addCssClasses("text-success") }
    } else if (connection.tokenLast4 != null) {
        panel.div(tr("Token hinterlegt, aber noch nicht erfolgreich getestet.")) { addCssClasses("text-warning") }
    } else {
        panel.div(tr("Nicht verbunden.")) { addCssClasses("text-muted") }
    }
    connection.tokenLast4?.let { last4 -> panel.div(gettext("Token: ••••%1", last4)) { addCssClasses("text-muted small") } }

    panel.p(
        tr(
            "Ihr persönliches API-Zugangstoken erzeugen Sie in Ihrem eigenen Lexware-Office-Konto unter " +
                "Einstellungen → Erweiterungen → Public API (app.lexware.de/addons/public-api).",
        ),
    ) { addCssClasses("text-muted small") }

    // Security review Runde 3, Befund 2 (Fund 2026-09-07): this codebase's own convention for
    // exactly this secret-input shape is `password(...)`, not `text(...)` -- see
    // ConferenceStreamDestinationsScreen.kt's stream-key fields, which this mirrors. A plain
    // type=text field shows the token in the clear on screen (shoulder-surfing/screenshare during
    // a board session) and is a browser autofill/form-history candidate that type=password is not.
    val tokenInput = panel.password(label = if (connection.tokenLast4 == null) tr("Token") else tr("Token ersetzen"))
    val actionsRow = panel.hPanel(spacing = 8)
    val saveButton = actionsRow.button(tr("Token speichern"), style = ButtonStyle.PRIMARY)
    val testButton = actionsRow.button(tr("Verbindung prüfen"), style = ButtonStyle.OUTLINESECONDARY)
    val removeButton = actionsRow.button(tr("Token entfernen"), style = ButtonStyle.OUTLINEDANGER)
    testButton.disabled = connection.tokenLast4 == null
    removeButton.disabled = connection.tokenLast4 == null

    saveButton.onClick {
        val token = tokenInput.value?.trim().orEmpty()
        if (token.isBlank()) {
            notifyError(tr("Bitte ein Token eingeben."))
            return@onClick
        }
        saveButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IAccountingExportService>().setToken(provider, token) }
                if (result != null) {
                    // The plaintext token stays in this password field's DOM value/in-memory state
                    // until explicitly cleared -- reloadConnection() (via onChanged()) rebuilds the
                    // whole panel from scratch so this input is discarded either way, but clearing it
                    // here first (same idiom ConferenceStreamDestinationsScreen.kt's key field uses)
                    // means the plaintext is gone even if a future refactor makes the panel rebuild
                    // conditional instead of unconditional.
                    tokenInput.value = null
                    notifySuccess(tr("Token gespeichert."))
                    onChanged()
                }
            } finally {
                saveButton.disabled = false
            }
        }
    }
    testButton.onClick {
        testButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IAccountingExportService>().testConnection(provider) }
                if (result != null) {
                    notifySuccess(tr("Verbindung erfolgreich getestet."))
                    onChanged()
                }
            } finally {
                testButton.disabled = false
            }
        }
    }
    removeButton.onClick {
        removeButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IAccountingExportService>().removeToken(provider) }
                if (result != null) {
                    notifySuccess(tr("Token entfernt."))
                    onChanged()
                }
            } finally {
                removeButton.disabled = false
            }
        }
    }

    renderZeroVatSection(panel, provider, connection, onChanged)
}

private fun renderZeroVatSection(
    panel: SimplePanel,
    provider: AccountingExportProvider,
    connection: AccountingExportConnectionDto,
    onChanged: () -> Unit,
) {
    if (connection.zeroVatAcknowledged) {
        panel.div(
            gettext("Hinweis zur Umsatzsteuer quittiert am %1.", connection.zeroVatAcknowledgedAt?.toString().orEmpty()),
        ) { addCssClasses("text-muted small") }
        return
    }
    panel.h3(tr("Hinweis zur Umsatzsteuer"))
    val textBox = panel.div { addCssClasses("border rounded p-2 small") }
    textBox.content = tr("Wird geladen …")
    val ackRow = panel.hPanel(spacing = 8)
    val ackCheck = ackRow.checkBox(label = tr("Zur Kenntnis genommen"))
    val ackButton = ackRow.button(tr("Bestätigen"), style = ButtonStyle.OUTLINEPRIMARY)
    var disclaimerSha256 = ""
    ackButton.disabled = true

    AppScope.launch {
        val disclaimer = guarded { rpcService<IAccountingExportService>().getZeroVatDisclaimer() } ?: return@launch
        textBox.content = disclaimer.text
        disclaimerSha256 = disclaimer.sha256
    }
    ackCheck.onClick { ackButton.disabled = ackCheck.value != true }
    ackButton.onClick {
        if (disclaimerSha256.isBlank()) return@onClick
        ackButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<IAccountingExportService>().acknowledgeZeroVat(provider, disclaimerSha256) }
                if (result != null) {
                    notifySuccess(tr("Hinweis quittiert."))
                    onChanged()
                }
            } finally {
                ackButton.disabled = false
            }
        }
    }
}

// ============================================================================================
// Export (Vorschau, Kontenzuordnung, Übertragen, Lauf-Status)
// ============================================================================================

private fun renderExportSection(
    panel: SimplePanel,
    provider: AccountingExportProvider,
    connection: AccountingExportConnectionDto,
) {
    val role = AppState.session?.role
    panel.h3(tr("Export"))
    renderUnknownItemsSection(panel.vPanel(spacing = 4), provider)
    val filterControls = panel.dateRangeFilter(fromLabel = tr("Von (JJJJ-MM-TT)"), toLabel = tr("Bis (JJJJ-MM-TT)"))
    filterControls.fromInput.value = "${currentYear()}-01-01"
    filterControls.toInput.value = todayIso()
    val checkButton = panel.button(tr("Prüfen"), style = ButtonStyle.OUTLINESECONDARY)
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val resultPanel = panel.vPanel(spacing = 8)
    val runPanel = panel.vPanel(spacing = 8)

    fun check() {
        errorBox.hide()
        val from = filterControls.parseFrom()
        val to = filterControls.parseTo()
        if (from == null || to == null) {
            errorBox.content = tr("Bitte Von- und Bis-Datum angeben (JJJJ-MM-TT).")
            errorBox.show()
            return
        }
        resultPanel.removeAll()
        resultPanel.p(tr("Wird geprüft …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val preview = guarded { rpcService<IAccountingExportService>().previewExport(provider, from, to) } ?: return@launch
            resultPanel.removeAll()
            renderPreviewBody(resultPanel, provider, preview, role) { check() }
            renderStartButton(resultPanel, provider, from, to, preview, role, runPanel)
        }
    }
    checkButton.onClick { check() }

    // Duarte: a reload survives the run -- show the most recent run immediately, without requiring
    // "Prüfen" first.
    AppScope.launch {
        val latest = guarded { rpcService<IAccountingExportService>().getLatestRun(provider) }?.firstOrNull()
        if (latest != null) renderRunSection(runPanel, latest)
    }
}

private fun renderPreviewBody(
    panel: SimplePanel,
    provider: AccountingExportProvider,
    preview: AccountingExportPreviewDto,
    role: network.lapis.cloud.shared.domain.AccountRole?,
    onMappingChanged: () -> Unit,
) {
    panel.div(periodRangeCaption(preview.from, preview.to)) { addCssClasses("text-muted small") }

    val summaryRow = panel.hPanel(spacing = 16) { addCssClasses("flex-wrap") }
    summaryRow.div(gettext("Buchungen: %1", preview.entryCount))
    summaryRow.div(gettext("Bereits übertragen: %1", preview.alreadyExportedCount))
    summaryRow.div(gettext("Werden gesendet: %1", preview.toSendCount))
    summaryRow.div(gettext("Σ %1", formatMoney(preview.totalGross)))

    if (preview.unmappedAccounts.isNotEmpty()) {
        panel.p(tr("Konten ohne Kategorie-Zuordnung:")) { addCssClasses("fw-bold text-warning") }
        AppScope.launch {
            val categories = guarded { rpcService<IAccountingExportService>().listCategories(provider) } ?: emptyList()
            val options = categories.map { it.id to gettext("%1 (%2)", it.name, it.groupName ?: "") }
            preview.unmappedAccounts.forEach { account ->
                val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                row.div(gettext("%1 · %2 (%3x)", account.accountNumber, account.accountName, account.entryCount)) {
                    addCssClass("small")
                }
                val categorySelect = row.select(options = options, label = null)
                val assignButton = row.button(tr("Zuordnen"), style = ButtonStyle.OUTLINEPRIMARY)
                assignButton.onClick {
                    val categoryId = categorySelect.value
                    if (categoryId.isNullOrBlank()) {
                        notifyError(tr("Bitte eine Kategorie auswählen."))
                        return@onClick
                    }
                    val categoryName = categories.firstOrNull { it.id == categoryId }?.name
                    assignButton.disabled = true
                    AppScope.launch {
                        try {
                            guarded {
                                rpcService<IAccountingExportService>().mapAccount(
                                    provider,
                                    account.ledgerAccountId,
                                    categoryId,
                                    categoryName,
                                )
                            }
                            onMappingChanged()
                        } finally {
                            assignButton.disabled = false
                        }
                    }
                }
            }
        }
    }

    if (preview.blockers.isNotEmpty()) {
        panel.p(tr("Dieser Zeitraum kann nicht (vollständig) übertragen werden:")) { addCssClasses("fw-bold text-danger") }
        preview.blockers.forEach { blocker ->
            val box = panel.div { addCssClasses("text-danger small mb-1") }
            box.div(accountingExportBlockerLabel(blocker.kind)) { addCssClass("fw-bold") }
            box.div(blocker.detail)
        }
    }

    if (preview.sampleLines.isNotEmpty()) {
        val table =
            panel.table(
                headerNames = listOf(tr("Datum"), tr("Beleg"), tr("Art"), tr("Kategorie"), tr("Betrag"), tr("Status")),
                types = setOf(TableType.STRIPED),
            )
        preview.sampleLines.forEach { line ->
            table.row {
                cell(line.entryDate.toString())
                cell(line.voucherNumber)
                cell(if (line.voucherType == "salesinvoice") tr("Einnahme") else tr("Ausgabe"))
                cell(line.categoryName ?: tr("(keine Zuordnung)"))
                cell(formatMoney(line.grossAmount))
                cell(if (line.alreadyExported) tr("bereits übertragen") else tr("wird gesendet"))
            }
        }
        if (preview.totalLineCount > preview.sampleLines.size) {
            panel.div(gettext("… und %1 weitere.", preview.totalLineCount - preview.sampleLines.size)) { addCssClasses("text-muted small") }
        }
    }
}

private fun renderStartButton(
    panel: SimplePanel,
    provider: AccountingExportProvider,
    from: LocalDate,
    to: LocalDate,
    preview: AccountingExportPreviewDto,
    role: network.lapis.cloud.shared.domain.AccountRole?,
    runPanel: SimplePanel,
) {
    val startButton = panel.button(tr("Übertragen"), style = ButtonStyle.SUCCESS)
    startButton.disabled = !AccountingExportAuthzUi.canStartRun(role, preview)
    startButton.onClick {
        startButton.disabled = true
        AppScope.launch {
            try {
                val run = guarded { rpcService<IAccountingExportService>().startExport(provider, from, to) }
                if (run != null) {
                    runPanel.removeAll()
                    renderRunSection(runPanel, run)
                }
            } finally {
                startButton.disabled = false
            }
        }
    }
}

/**
 * Security review Fund 2026-09-07 (Runde 5, MAJOR): the client-side counterpart of
 * `IAccountingExportService.listUnknownItems` -- see that method's own KDoc for the "an `UNKNOWN`
 * item from an earlier run becomes unreachable once a later run starts" gap this closes. Rendered
 * ABOVE the date-range filter/`renderRunSection` below, deliberately independent of whichever run
 * [renderExportSection]'s own `getLatestRun` happens to show -- this spans every run of [provider].
 * Renders nothing at all (no header, no empty table) once every item is resolved -- Rams/Raskin: no
 * permanent chrome for a state that is usually empty.
 */
private fun renderUnknownItemsSection(
    panel: SimplePanel,
    provider: AccountingExportProvider,
) {
    fun reload() {
        AppScope.launch {
            val items = guarded { rpcService<IAccountingExportService>().listUnknownItems(provider, 0, 200) } ?: return@launch
            panel.removeAll()
            if (items.isEmpty()) return@launch
            panel.h3(tr("Ungeklärte Belege (alle Läufe)"))
            panel.p(
                tr(
                    "Diese Belege haben einen ungeklärten lexoffice-Sendestatus aus einem früheren Lauf. Bitte in " +
                        "Lexware Office prüfen und hier auflösen -- die betroffenen Zeiträume bleiben sonst dauerhaft " +
                        "nicht erneut übertragbar.",
                ),
            ) { addCssClasses("text-muted small") }
            val table =
                panel.table(
                    headerNames = listOf(tr("Datum"), tr("Beleg"), tr("Betrag"), tr("Hinweis"), ""),
                    types = setOf(TableType.STRIPED),
                )
            items.forEach { item ->
                table.row {
                    cell(item.entryDate.toString())
                    cell(item.voucherNumber)
                    cell(formatMoney(item.grossAmount))
                    cell(
                        gettext("Status unklar -- bitte in Lexware Office unter Belegnummer %1 prüfen.", item.voucherNumber),
                    ) { addCssClasses("text-muted small") }
                    cell { renderResolveUnknownActions(item) { reload() } }
                }
            }
        }
    }
    reload()
}

private fun renderRunSection(
    panel: SimplePanel,
    initialRun: AccountingExportRunDto,
) {
    panel.removeAll()
    panel.h3(tr("Letzter Lauf"))
    val summaryBox = panel.div()
    val itemsPanel = panel.vPanel(spacing = 4)
    val actionsRow = panel.hPanel(spacing = 8)
    val retryButton = actionsRow.button(tr("Nur fehlgeschlagene erneut versuchen"), style = ButtonStyle.OUTLINESECONDARY)
    val abortButton = actionsRow.button(tr("Lauf abbrechen"), style = ButtonStyle.OUTLINEDANGER)

    fun renderSummary(run: AccountingExportRunDto) {
        summaryBox.removeAll()
        summaryBox.div(
            gettext(
                "%1 von %2 übertragen, %3 fehlgeschlagen, %4 übersprungen, %5 unklar (%6)",
                run.succeeded,
                run.total,
                run.failed,
                run.skipped,
                run.unknown,
                accountingExportRunStatusLabel(run.status),
            ),
        )
        val terminal =
            run.status == AccountingExportRunStatus.COMPLETED ||
                run.status == AccountingExportRunStatus.COMPLETED_WITH_ERRORS ||
                run.status == AccountingExportRunStatus.ABORTED
        retryButton.disabled = !terminal || run.failed == 0
        abortButton.disabled = terminal
    }
    renderSummary(initialRun)

    // Security review Fund 2026-09-07 (Runde 4, Befund 2): a run-level refresh (not just a
    // `loadItems` re-fetch) -- resolving an item can move the RUN's own status/counts too (see
    // `AccountingExportStore.resolveUnknown`'s `recomputeRunCountsInternal` call), so the summary
    // line and retry/abort button states need the same re-render `retryButton`/`abortButton` below
    // already trigger, not just the items table.
    fun refreshRun() {
        AppScope.launch {
            val updated = guarded { rpcService<IAccountingExportService>().getRun(initialRun.id) }
            if (updated != null) renderRunSection(panel, updated)
        }
    }

    fun loadItems(runId: String) {
        AppScope.launch {
            val items = guarded { rpcService<IAccountingExportService>().listRunItems(runId, null, 0, 200) } ?: return@launch
            itemsPanel.removeAll()
            if (items.isEmpty()) return@launch
            val table =
                itemsPanel.table(
                    headerNames = listOf(tr("Beleg"), tr("Betrag"), tr("Status"), tr("Fehler"), ""),
                    types = setOf(TableType.STRIPED),
                )
            items.forEach { item ->
                table.row {
                    cell(item.voucherNumber)
                    cell(formatMoney(item.grossAmount))
                    cell { accountingExportItemStatusBadge(item.status) }
                    cell(
                        when (item.status) {
                            AccountingExportItemStatus.UNKNOWN ->
                                gettext("Status unklar -- bitte in Lexware Office unter Belegnummer %1 prüfen.", item.voucherNumber)
                            else -> item.errorMessage ?: ""
                        },
                    ) { addCssClasses("text-muted small") }
                    cell {
                        if (item.status == AccountingExportItemStatus.UNKNOWN) {
                            renderResolveUnknownActions(item) { refreshRun() }
                        }
                    }
                }
            }
        }
    }

    retryButton.onClick {
        retryButton.disabled = true
        AppScope.launch {
            val updated = guarded { rpcService<IAccountingExportService>().retryFailed(initialRun.id) }
            if (updated != null) pollRun(panel, updated)
        }
    }
    abortButton.onClick {
        abortButton.disabled = true
        AppScope.launch {
            val updated = guarded { rpcService<IAccountingExportService>().abortRun(initialRun.id) }
            if (updated != null) renderRunSection(panel, updated)
        }
    }

    loadItems(initialRun.id)
    if (initialRun.status == AccountingExportRunStatus.PLANNED || initialRun.status == AccountingExportRunStatus.RUNNING) {
        pollRun(panel, initialRun)
    }
}

/** Polls `getRun` every 3s while non-terminal (same `AppScope.launch` + `while` + `delay` idiom
 * `PaymentReturnScreen.kt` already establishes) -- no hard attempt limit while the run stays
 * RUNNING, only a terminal status stops the loop. */
private fun pollRun(
    panel: SimplePanel,
    run: AccountingExportRunDto,
) {
    AppScope.launch {
        var current = run
        while (current.status == AccountingExportRunStatus.PLANNED || current.status == AccountingExportRunStatus.RUNNING) {
            delay(3.seconds)
            current = guarded { rpcService<IAccountingExportService>().getRun(current.id) } ?: return@launch
        }
        renderRunSection(panel, current)
    }
}

private fun accountingExportBlockerLabel(kind: AccountingExportBlockerKind): String =
    when (kind) {
        AccountingExportBlockerKind.NOT_CONNECTED -> tr("Nicht verbunden")
        AccountingExportBlockerKind.ZERO_VAT_NOT_ACKNOWLEDGED -> tr("Hinweis zur Umsatzsteuer nicht bestätigt")
        AccountingExportBlockerKind.UNMAPPED_ACCOUNT -> tr("Konten ohne Kategorie-Zuordnung")
        AccountingExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY -> tr("Nicht abbildbare Sammelbuchung (n:m)")
        AccountingExportBlockerKind.UNDETERMINABLE_VOUCHER_TYPE -> tr("Richtung nicht bestimmbar")
        AccountingExportBlockerKind.EMPTY_PERIOD -> tr("Keine Buchungen im Zeitraum")
        AccountingExportBlockerKind.TOO_MANY_ENTRIES -> tr("Zu viele Buchungen im Zeitraum")
        AccountingExportBlockerKind.RUN_ALREADY_IN_PROGRESS -> tr("Export läuft bereits")
        AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS -> tr("Ungeklärter Sendestatus aus einem vorherigen Lauf")
    }

private fun accountingExportRunStatusLabel(status: AccountingExportRunStatus): String =
    when (status) {
        AccountingExportRunStatus.PLANNED -> tr("geplant")
        AccountingExportRunStatus.RUNNING -> tr("läuft")
        AccountingExportRunStatus.COMPLETED -> tr("abgeschlossen")
        AccountingExportRunStatus.COMPLETED_WITH_ERRORS -> tr("abgeschlossen mit Fehlern")
        AccountingExportRunStatus.ABORTED -> tr("abgebrochen")
    }

private fun currentYear(): Int =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date.year

private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

/** Security review Fund 2026-09-07 (Runde 4, Befund 2): the client-side counterpart of
 * `IAccountingExportService.resolveUnknownItem` -- the only UI path to resolve an
 * [AccountingExportItemStatus.UNKNOWN] item (and thereby lift
 * [AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS] for its journal entry). A single small
 * optional text field for the lexoffice voucher id, plus two buttons -- both disable each other
 * while a request is in flight, same idiom every other action button on this screen already uses.
 * [onResolved] re-renders the WHOLE run section (see [refreshRun]), not just this row -- resolving
 * can change the run's own summary counts. */
private fun SimplePanel.renderResolveUnknownActions(
    item: AccountingExportItemDto,
    onResolved: () -> Unit,
) {
    val voucherIdInput = text(label = tr("Lexoffice-Belegnummer (optional)")) { addCssClasses("form-control-sm") }
    val actions = hPanel(spacing = 4) { addCssClass("mt-1") }
    val foundButton = actions.button(tr("In Lexoffice gefunden"), style = ButtonStyle.OUTLINESUCCESS)
    val notFoundButton = actions.button(tr("Nicht gefunden"), style = ButtonStyle.OUTLINEDANGER)

    fun resolve(resolution: AccountingExportUnknownItemResolution) {
        foundButton.disabled = true
        notFoundButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IAccountingExportService>().resolveUnknownItem(
                        item.id,
                        resolution,
                        if (resolution == AccountingExportUnknownItemResolution.CONFIRMED_SENT) {
                            voucherIdInput.value?.trim()?.ifBlank { null }
                        } else {
                            null
                        },
                    )
                }
            if (result != null) {
                notifySuccess(tr("Beleg-Status geklärt."))
                onResolved()
            } else {
                foundButton.disabled = false
                notFoundButton.disabled = false
            }
        }
    }
    foundButton.onClick { resolve(AccountingExportUnknownItemResolution.CONFIRMED_SENT) }
    notFoundButton.onClick { resolve(AccountingExportUnknownItemResolution.CONFIRMED_NOT_SENT) }
}

private fun SimplePanel.accountingExportItemStatusBadge(status: AccountingExportItemStatus) {
    val (label, color) =
        when (status) {
            AccountingExportItemStatus.PENDING -> tr("wartet") to "secondary"
            AccountingExportItemStatus.SENDING -> tr("wird gesendet") to "info"
            AccountingExportItemStatus.SUCCEEDED -> tr("übertragen") to "success"
            AccountingExportItemStatus.FAILED -> tr("fehlgeschlagen") to "danger"
            AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED -> tr("übersprungen") to "secondary"
            AccountingExportItemStatus.UNKNOWN -> tr("unklar") to "warning"
        }
    statusBadge(label, color)
}
