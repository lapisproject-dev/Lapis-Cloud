package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * Welle V1.4.11, Vorstands-Warteschlange -- 1:1 nach `ContributionReliefQueueScreen`s Vorbild.
 * Route-Gate ist BOARD/ADMIN, **nicht** TREASURER -- siehe `Routes.TRAVEL_EXPENSE_APPROVALS` KDoc.
 */
fun renderTravelExpenseApprovalsScreen(container: SimplePanel) {
    val currentMemberId = AppState.session?.memberId
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Reisekosten-Freigaben"))

    root.h2(tr("Sätze")) { addCssClass("h6") }
    val ratesPanel = root.vPanel(spacing = 6)
    renderRatesAdminSection(ratesPanel)

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val statusOptions =
        listOf("" to tr("Alle Status")) +
            TravelExpenseReportStatus.entries.filter { it != TravelExpenseReportStatus.DRAFT }.map {
                it.name to
                    travelExpenseStatusLabel(
                        it,
                    )
            }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 8)
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
                } ?: return@launch
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
            if (!canManage) {
                panel.p(
                    gettext(
                        "Kilometersatz: %1 · Tagespauschale: %2",
                        rates.mileageRatePerKm?.let { "${formatMoney(it)}/km" } ?: tr("nicht konfiguriert"),
                        rates.perDiemRate?.let { formatMoney(it) } ?: tr("nicht konfiguriert"),
                    ),
                )
                return@launch
            }
            panel.p(tr("Diese Sätze werden nicht automatisch an Gesetzesänderungen angepasst.")) { addCssClasses("text-muted small") }
            val mileageInput = panel.text(value = rates.mileageRatePerKm?.toString(), label = tr("Kilometersatz (EUR/km)"))
            val perDiemInput = panel.text(value = rates.perDiemRate?.toString(), label = tr("Tagespauschale (EUR)"))
            // Review MINOR fix: same inline-error discipline TravelExpenseScreen's addLine form
            // already applies (kilometers/days/amount) -- an unparseable value must never be
            // silently sent as `null` (which `updateTravelExpenseRates` interprets as "delete this
            // rate", disabling the whole line kind for every member) just because a decimal comma
            // instead of a dot didn't parse.
            val errorBox =
                panel.div().apply {
                    addCssClass("text-danger")
                    hide()
                }
            val saveButton = panel.button(tr("Sätze speichern"), style = ButtonStyle.PRIMARY)
            saveButton.onClick {
                errorBox.hide()
                val mileage = parseTravelExpenseRateInput(mileageInput.value)
                if (mileage is TravelExpenseRateInput.Invalid) {
                    errorBox.content = tr("Bitte einen gültigen Kilometersatz angeben (z. B. 0.30).")
                    errorBox.show()
                    return@onClick
                }
                val perDiem = parseTravelExpenseRateInput(perDiemInput.value)
                if (perDiem is TravelExpenseRateInput.Invalid) {
                    errorBox.content = tr("Bitte eine gültige Tagespauschale angeben (z. B. 14.00).")
                    errorBox.show()
                    return@onClick
                }
                saveButton.disabled = true
                AppScope.launch {
                    try {
                        val result =
                            guarded {
                                rpcService<ITravelExpenseService>().updateTravelExpenseRates(
                                    (mileage as? TravelExpenseRateInput.Valid)?.value?.toDecimal(),
                                    (perDiem as? TravelExpenseRateInput.Valid)?.value?.toDecimal(),
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Sätze gespeichert."))
                            load()
                        }
                    } finally {
                        saveButton.disabled = false
                    }
                }
            }
        }
    }
    load()
}

private fun renderApprovalCard(
    panel: SimplePanel,
    report: TravelExpenseReportDto,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(travelExpenseStatusLabel(report.status), travelExpenseStatusColor(report.status))
    headerRow.div(report.subjectDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    if (report.requestedBy != report.subjectMemberId) {
        headerRow.typeBadge(gettext("Im Namen von %1 gestellt", report.requestedByDisplayName), "secondary")
    }
    headerRow.div(formatMoney(report.totalAmount)) { addCssClasses("fw-bold") }

    card.div(gettext("%1 bis %2", report.travelFrom, report.travelTo)) { addCssClasses("text-muted small") }
    card.div(report.purpose)
    report.lines.forEach { line ->
        val lineRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        lineRow.typeBadge(travelExpenseLineKindLabel(line.kind), travelExpenseLineKindColor(line.kind))
        lineRow.div(line.description) { addCssClasses("flex-grow-1 small") }
        lineRow.div(formatMoney(line.amount)) { addCssClasses("small") }
        line.receipts.forEach { receipt ->
            lineRow.link(receipt.originalFilename, url = TravelExpenseHttp.receiptDownloadUrl(receipt.id), target = "_blank") {
                addCssClasses("small")
            }
        }
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

private fun renderRequestedDecisionPanel(
    card: SimplePanel,
    report: TravelExpenseReportDto,
    onChanged: () -> Unit,
) {
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = tr("Entscheidungsnotiz (Pflicht)"), rows = 2) { maxlength = 1000 }
    val errorBox =
        decidePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonsRow = decidePanel.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val approveButton = buttonsRow.button(tr("Genehmigen und buchen"), style = ButtonStyle.SUCCESS)
    val rejectButton = buttonsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

    fun decide(approve: Boolean) {
        errorBox.hide()
        val note = noteInput.value?.trim()
        if (!travelExpenseDecisionNoteIsValid(note)) {
            errorBox.content = tr("Bitte eine Entscheidungsnotiz eingeben.")
            errorBox.show()
            return
        }
        approveButton.disabled = true
        rejectButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().decideReport(report.id, approve, note) }
                if (result != null) {
                    notifySuccess(if (approve) tr("Antrag genehmigt und gebucht.") else tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                approveButton.disabled = false
                rejectButton.disabled = false
            }
        }
    }
    approveButton.onClick { decide(true) }
    rejectButton.onClick { decide(false) }
}

private fun renderApprovedRetryPanel(
    card: SimplePanel,
    report: TravelExpenseReportDto,
    onChanged: () -> Unit,
) {
    card.div(travelExpensePostingErrorMessage(report.executionError)) { addCssClasses("alert alert-danger") }
    val decidePanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val noteInput = decidePanel.textArea(label = tr("Entscheidungsnotiz (Pflicht)"), rows = 2) { maxlength = 1000 }
    val errorBox =
        decidePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonsRow = decidePanel.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val retryButton = buttonsRow.button(tr("Buchung wiederholen"), style = ButtonStyle.PRIMARY)
    val rejectButton = buttonsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

    retryButton.onClick {
        retryButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().retryPosting(report.id) }
                if (result != null) {
                    notifySuccess(tr("Buchung wiederholt."))
                    onChanged()
                }
            } finally {
                retryButton.disabled = false
            }
        }
    }
    rejectButton.onClick {
        errorBox.hide()
        val note = noteInput.value?.trim()
        if (!travelExpenseDecisionNoteIsValid(note)) {
            errorBox.content = tr("Bitte eine Entscheidungsnotiz eingeben.")
            errorBox.show()
            return@onClick
        }
        retryButton.disabled = true
        rejectButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().decideReport(report.id, false, note) }
                if (result != null) {
                    notifySuccess(tr("Antrag abgelehnt."))
                    onChanged()
                }
            } finally {
                retryButton.disabled = false
                rejectButton.disabled = false
            }
        }
    }
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
