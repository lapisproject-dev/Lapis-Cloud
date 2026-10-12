package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * Welle V1.4.11, Vorstands-Warteschlange -- 1:1 nach `ContributionReliefQueueScreen`s Vorbild.
 * Route-Gate ist BOARD/ADMIN, **nicht** TREASURER -- siehe `Routes.TRAVEL_EXPENSE_APPROVALS` KDoc.
 *
 * **Kartenliste, keine Tabelle (Welle V1.4.27, W3 -- Richtlinie P3, ein Objekt hat nie beide Grammatiken):**
 * Kriterium: braucht eine Zeile ein Freitextfeld oder trägt sie eine Entscheidung mit Begründung, ist sie eine
 * Karte. Jede Karte dieser Warteschlange trägt ein Pflicht-Textfeld "Entscheidungsnotiz" und zwei
 * Entscheidungsknöpfe -- das ist ein Formular pro Antrag, keine Zeile eines Rasters. Deshalb stehen die Karten in
 * `.lapis-card-list` / `.lapis-data-card` (dieselben Klassen, die auch die Kartenliste von `dataTable` unter
 * 768 px nutzt) und nicht in einer `dataTable`. Die Entscheidungspanels selbst sind Formulare (W4).
 *
 * R24/R29 (W4d batch 4): drei echte Formulare sind [LapisForm]s -- die Sätze-Pflege (Kilometersatz/Tagespauschale,
 * nur ADMIN), sowie die beiden Entscheidungs-Panels (Entscheidungsnotiz, Pflicht), 1:1 nach
 * `ContributionReliefQueueScreen.renderReliefRequestedDecidePanel`/`renderReliefApprovedRetryPanel`s Vorbild
 * (Genehmigen/Wiederholen ist die Primäraktion, Ablehnen steht in der Gefahrenzone unter der Knopfzeile). Der
 * Status-Filter oben (`statusSelect`) ist ein FILTER, kein Formular (nie abgesendet, keine Pflichtfelder) --
 * justiert in `R24B_JUSTIFIED`. Jeder schreibende `AppScope.launch` (Sätze speichern, Genehmigen/Ablehnen,
 * Buchung wiederholen) läuft durch `form.submit`/`form.runBusy`, damit R29 nicht steigt, wenn die Datei der
 * strengen Menge beitritt.
 */
fun renderTravelExpenseApprovalsScreen(container: SimplePanel) {
    val currentMemberId = AppState.session?.memberId
    val root = container.dataScreenRoot(spacing = 14)
    root.pageHeader(tr("Reisekosten-Freigaben"))

    root.h2(tr("Sätze")) { addCssClass("h6") }
    val ratesPanel = root.vPanel(spacing = 6)
    renderRatesAdminSection(ratesPanel)

    val filterRow = root.lapisToolbar()
    val statusOptions =
        listOf("" to tr("Alle Status")) +
            TravelExpenseReportStatus.entries.filter { it != TravelExpenseReportStatus.DRAFT }.map {
                it.name to
                    travelExpenseStatusLabel(
                        it,
                    )
            }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.actionButton(ActionIcon.FILTER, tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.simplePanel { addCssClass("lapis-card-list") }
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var cursor: TravelExpensePageCursor? = null

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            cursor = null
        }
        val status = parseOptionalEnum<TravelExpenseReportStatus>(statusSelect.value)
        AppScope.launch {
            val page =
                guarded {
                    rpcService<ITravelExpenseService>().listReports(
                        status = status,
                        afterSubmittedAt = cursor?.submittedAt,
                        afterId = cursor?.id,
                    )
                }
            if (page == null) {
                // Welle V1.4.27 (W3): a failed first page is an error state with a retry, not an empty list.
                if (reset) listPanel.dataErrorState(onRetry = { loadPage(reset = true) })
                return@launch
            }
            if (page.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Anträge für diese Filter gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            page.forEach { report -> renderApprovalCard(listPanel, report, currentMemberId) { loadPage(reset = true) } }
            cursor = nextTravelExpenseCursor(page)
            if (travelExpenseHasMorePages(page.size)) loadMoreButton.show() else loadMoreButton.hide()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)
}

private fun renderRatesAdminSection(panel: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.ADMIN)

    fun load() {
        panel.removeAll()
        panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val rates = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() } ?: return@launch
            panel.removeAll()
            val rateSet = rates.rateSet
            if (rateSet != null) {
                panel.p(
                    gettext(
                        "Tagegeld: ab %1 Std. %2 · ab %3 Std. %4 · ab %5 Std. %6 · Kilometersatz: %7",
                        rateSet.tierHours[0],
                        formatMoney(centsToDecimal(rateSet.tierCents[0])),
                        rateSet.tierHours[1],
                        formatMoney(centsToDecimal(rateSet.tierCents[1])),
                        rateSet.tierHours[2],
                        formatMoney(centsToDecimal(rateSet.tierCents[2])),
                        rateSet.mileageCentsPerKm?.let { "${formatMoney(centsToDecimal(it))}/km" } ?: gettext("nicht konfiguriert"),
                    ),
                )
            }
            if (canManage) {
                // V1.9.122: the editor moved to its own page, where the tiers, the percentages and the contact person live too.
                val settingsLink = panel.button(tr("Reisekosten-Einstellungen öffnen"), style = ButtonStyle.LINK)
                settingsLink.onClick { navigateTo(Routes.TRAVEL_EXPENSE_SETTINGS) }
            }
        }
    }
    load()
}

/** Feldregel für [renderRatesAdminSection]s Sätze-Formular -- pure, siehe [parseTravelExpenseRateInput]. */
internal fun travelExpenseRateFieldCheck(
    value: String,
    invalidMessage: String,
): FieldCheck =
    if (parseTravelExpenseRateInput(value) is TravelExpenseRateInput.Invalid) {
        FieldCheck.Invalid(invalidMessage)
    } else {
        FieldCheck.Ok
    }

private fun renderApprovalCard(
    panel: SimplePanel,
    report: TravelExpenseReportDto,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClass("lapis-data-card") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(travelExpenseStatusLabel(report.status), travelExpenseStatusColor(report.status))
    headerRow.untrustedCardTitle(report.subjectDisplayName)
    if (report.requestedBy != report.subjectMemberId) {
        headerRow.typeBadge(gettext("Im Namen von %1 gestellt", report.requestedByDisplayName), "secondary")
    }
    headerRow.div(formatMoney(report.totalAmount)) { addCssClasses("fw-bold") }

    card.div(gettext("%1 bis %2", formatDate(report.travelFrom), formatDate(report.travelTo))) { addCssClasses("text-muted small") }
    card.untrustedDiv(report.purpose)
    report.membershipNumber?.let { card.untrustedDiv(gettext("Mitgliedsnummer: %1", it), className = "text-muted small") }

    // V1.9.122: the day entries as a read-only data table (caption + column headers), then the other costs, then the settlement.
    val dayLines =
        report.lines
            .filter { it.kind == TravelExpenseLineKind.DAY_ALLOWANCE && it.dayDate != null }
            .sortedBy { it.dayDate }
    if (dayLines.isNotEmpty()) {
        val table = card.reportTable(caption = tr("Reisetage"), headers = TRAVEL_EXPENSE_DAY_HEADERS)
        table.reportRows(travelExpenseDayRows(dayLines), TRAVEL_EXPENSE_DAY_HEADERS)
    }
    val otherLines = report.lines.filter { it.kind != TravelExpenseLineKind.DAY_ALLOWANCE }
    if (otherLines.isNotEmpty()) card.h2(tr("Weitere Kosten")) { addCssClasses("h6 mb-0") }
    otherLines.forEach { line ->
        val lineRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        lineRow.typeBadge(travelExpenseLineKindLabel(line.kind), travelExpenseLineKindColor(line.kind))
        lineRow.untrustedDiv(line.description, className = "flex-grow-1 small")
        lineRow.div(formatMoney(line.amount)) { addCssClasses("small") }
        line.receipts.forEach { receipt ->
            lineRow.untrustedLink(receipt.originalFilename, url = TravelExpenseHttp.receiptDownloadUrl(receipt.id), target = "_blank") {
                addCssClasses("small")
            }
        }
    }
    card.div(
        gettext(
            "Vorschuss: %1 · Abzüge: %2 · Auszahlungsbetrag: %3",
            formatMoney(centsToDecimal(report.advanceCents)),
            formatMoney(centsToDecimal(report.deductionCents)),
            formatMoney(centsToDecimal(report.payoutCents)),
        ),
    ) { addCssClasses("small") }
    if (report.payoutChoice == TravelExpensePayoutChoice.DONATION_WAIVER) {
        card.typeBadge(tr("Spendenquittung gewünscht"), "info")
    }

    if (report.decisionNote != null) {
        card.div(gettext("Begründung: %1", report.decisionNote)) { addCssClasses("text-muted small") }
    }
    if (report.status == TravelExpenseReportStatus.EXECUTED) {
        card.div(travelExpensePayoutDisclaimer()) { addCssClasses("text-muted small") }
    }

    if (travelExpenseDecisionBlockedBySelf(report, currentMemberId)) {
        card.div(tr("Eigener Antrag — Entscheidung durch ein anderes Vorstandsmitglied.")) { addCssClasses("text-muted small fst-italic") }
        return
    }

    when (report.status) {
        TravelExpenseReportStatus.REQUESTED -> renderRequestedDecisionPanel(card, report, onChanged)
        TravelExpenseReportStatus.APPROVED -> renderApprovedRetryPanel(card, report, onChanged)
        TravelExpenseReportStatus.REJECTED,
        TravelExpenseReportStatus.EXECUTED,
        TravelExpenseReportStatus.WITHDRAWN,
        TravelExpenseReportStatus.DRAFT,
        -> Unit
    }
}

// R24 (W4d batch 4): migrated to the form grammar -- 1:1 nach
// `ContributionReliefQueueScreen.renderReliefRequestedDecidePanel`s Vorbild: "Genehmigen" ist die
// Primäraktion, "Ablehnen" steht in der Gefahrenzone unter der Knopfzeile (Richtlinie 2.5). Ein einziges
// Pflichtfeld => Stern + Legende "* Pflichtfeld".
private fun renderRequestedDecisionPanel(
    card: SimplePanel,
    report: TravelExpenseReportDto,
    onChanged: () -> Unit,
) {
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val form = decidePanel.lapisForm()
    val noteField = travelExpenseDecisionNoteField(form)
    val approveButton = Button(tr("Genehmigen und buchen"), style = ButtonStyle.SUCCESS)
    val rejectButton = newActionButton(ActionIcon.REJECT, tr("Ablehnen"), ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = approveButton, destructive = rejectButton)

    fun decide(
        approve: Boolean,
        pressed: Button,
        other: Button,
    ) {
        form.submit(pressed) {
            other.disabled = true
            try {
                val note = noteField.value.trim()
                val result = guarded { rpcService<ITravelExpenseService>().decideReport(report.id, approve, note) }
                if (result != null) {
                    notifySuccess(if (approve) tr("Antrag genehmigt und gebucht.") else tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                other.disabled = false
            }
        }
    }
    approveButton.onClick { decide(true, approveButton, rejectButton) }
    rejectButton.onClick { decide(false, rejectButton, approveButton) }
}

/** Die Pflicht-Notiz einer Entscheidung, 1:1 nach `ContributionReliefQueueScreen.reliefDecisionNoteField`s Vorbild. */
private fun travelExpenseDecisionNoteField(
    form: LapisForm,
    hint: String? = null,
): LapisField =
    form.textAreaField(
        label = tr("Entscheidungsnotiz"),
        rows = 2,
        required = true,
        hint = hint,
        requiredMessage = gettext("Bitte eine Entscheidungsnotiz eingeben."),
        init = { it.maxlength = 1000 },
    )

// R24 (W4d batch 4): migrated to the form grammar -- 1:1 nach
// `ContributionReliefQueueScreen.renderReliefApprovedRetryPanel`s Vorbild: die Wiederholung braucht keine
// Notiz und läuft OHNE Prüfung (`form.runBusy`), nur "Ablehnen" prüft das Pflichtfeld.
private fun renderApprovedRetryPanel(
    card: SimplePanel,
    report: TravelExpenseReportDto,
    onChanged: () -> Unit,
) {
    card.div(travelExpensePostingErrorMessage(report.executionError)) { addCssClasses("alert alert-danger") }
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val form = decidePanel.lapisForm()
    val noteField = travelExpenseDecisionNoteField(form, hint = gettext("Nur für \"Ablehnen\" erforderlich."))
    val retryButton = Button(tr("Buchung wiederholen"), style = ButtonStyle.PRIMARY)
    val rejectButton = newActionButton(ActionIcon.REJECT, tr("Ablehnen"), ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = retryButton, destructive = rejectButton)

    retryButton.onClick {
        rejectButton.disabled = true
        form.runBusy(retryButton) {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().retryPosting(report.id) }
                if (result != null) {
                    notifySuccess(tr("Buchung wiederholt."))
                    onChanged()
                }
            } finally {
                rejectButton.disabled = false
            }
        }
    }
    rejectButton.onClick {
        form.submit(rejectButton) {
            retryButton.disabled = true
            try {
                val note = noteField.value.trim()
                val result = guarded { rpcService<ITravelExpenseService>().decideReport(report.id, false, note) }
                if (result != null) {
                    notifySuccess(tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                retryButton.disabled = false
            }
        }
    }
}

/** The columns of the day table in a reviewer's card (V1.9.122); the caption is the table's accessible name. */
private val TRAVEL_EXPENSE_DAY_HEADERS =
    listOf(
        TableHeader(title = tr("Datum")),
        TableHeader(title = tr("Zeit")),
        TableHeader(title = tr("Stunden"), numeric = true),
        TableHeader(title = tr("Basis"), numeric = true),
        TableHeader(title = tr("Abzüge"), numeric = true),
        TableHeader(title = tr("Betrag"), numeric = true),
    )

/** One row per day: the dates and amounts come from the stored (server-computed) day line, never from a client calculation. */
internal fun travelExpenseDayRows(dayLines: List<TravelExpenseLineDto>): List<ReportRow> =
    dayLines.map { line ->
        val date = requireNotNull(line.dayDate)
        val from = line.timeFrom?.let { formatTimeComponents(it.hour, it.minute) } ?: "00:00"
        val to = line.timeTo?.let { formatTimeComponents(it.hour, it.minute) } ?: "24:00"
        ReportRow(
            kind = ReportRowKind.DATA,
            cells =
                listOf(
                    ReportCell.Text(formatDate(date)),
                    ReportCell.Text("$from – $to"),
                    ReportCell.Text(travelExpenseDuration(line.computedMinutes ?: 0)),
                    ReportCell.Money(centsToDecimal(line.baseCents ?: 0L)),
                    ReportCell.Money(centsToDecimal(line.deductionCents)),
                    ReportCell.Money(line.amount),
                ),
        )
    }

/** Server-seitige Seitengröße (`TravelExpenseService.MAX_LIST_RESULTS`), gleiche Rolle wie `ContributionReliefQueueScreen.RELIEF_PAGE_SIZE`. */
private const val TRAVEL_EXPENSE_PAGE_SIZE = 200

internal fun travelExpenseHasMorePages(
    pageSize: Int,
    capacity: Int = TRAVEL_EXPENSE_PAGE_SIZE,
): Boolean = pageSize >= capacity

internal data class TravelExpensePageCursor(
    val submittedAt: LocalDateTime,
    val id: String,
)

internal fun nextTravelExpenseCursor(page: List<TravelExpenseReportDto>): TravelExpensePageCursor? =
    page.lastOrNull()?.submittedAt?.let { submittedAt -> TravelExpensePageCursor(submittedAt = submittedAt, id = page.last().id) }

internal fun travelExpenseDecisionBlockedBySelf(
    report: TravelExpenseReportDto,
    currentMemberId: String?,
): Boolean = report.subjectMemberId == currentMemberId || report.requestedBy == currentMemberId

internal fun travelExpenseDecisionNoteIsValid(note: String?): Boolean = !note.isNullOrBlank()

/**
 * Review MINOR fix -- extracted so [renderRatesAdminSection]'s "never silently send a typo as
 * null" discipline (see that function's own comment) is unit-testable, same posture
 * [travelExpenseDecisionNoteIsValid] already has. A blank input means "the ADMIN deliberately
 * wants to clear this rate" ([Cleared]); a non-blank input that fails to parse means "the ADMIN
 * mistyped something" ([Invalid]) and must never be treated the same as [Cleared].
 */
internal sealed interface TravelExpenseRateInput {
    data object Cleared : TravelExpenseRateInput

    data object Invalid : TravelExpenseRateInput

    data class Valid(
        val value: Double,
    ) : TravelExpenseRateInput
}

internal fun parseTravelExpenseRateInput(raw: String?): TravelExpenseRateInput {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return TravelExpenseRateInput.Cleared
    val value = text.toDoubleOrNull() ?: return TravelExpenseRateInput.Invalid
    return TravelExpenseRateInput.Valid(value)
}
