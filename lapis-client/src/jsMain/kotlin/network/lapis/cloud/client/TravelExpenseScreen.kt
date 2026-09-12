package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import io.kvision.form.text.text
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger", Selbstbedienungsseite.
 * `requireAuth` (jedes authentifizierte Mitglied) -- siehe `Routes.TRAVEL_EXPENSES` KDoc.
 *
 * Struktur einspaltig, nie `<table>` für Eingaben (Duarte-Ruling) -- Kartengrammatik nach
 * `ContributionReliefQueueScreen`s Vorbild. Beträge kommen IMMER aus dem vom Server
 * zurückgegebenen [TravelExpenseReportDto] -- der Client addiert nie selbst
 * ([TravelExpenseLabels]/dieser Datei eigene reine Hilfsfunktionen ausgenommen, die nur für die
 * Anzeige-Aufschlüsselung nach Zeilenart summieren, niemals für den Gesamtbetrag selbst).
 */
fun renderTravelExpenseScreen(
    container: SimplePanel,
    focusedReportId: String?,
) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Reisekosten"))

    val ratesBanner = root.vPanel(spacing = 4)
    val editorPanel = root.vPanel(spacing = 10)
    root.h2(tr("Meine Anträge")) { addCssClass("h5 mt-3") }
    val listPanel = root.vPanel(spacing = 10)

    fun reload() {
        AppScope.launch {
            val rates = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() } ?: return@launch
            val reports = guarded { rpcService<ITravelExpenseService>().listMyReports() } ?: return@launch

            ratesBanner.removeAll()
            renderRatesBanner(ratesBanner, rates)

            editorPanel.removeAll()
            val draft = reports.firstOrNull { it.status == TravelExpenseReportStatus.DRAFT }
            if (draft != null) {
                renderDraftEditor(editorPanel, draft, rates, ::reload)
            } else {
                renderNewDraftButton(editorPanel, ::reload)
            }

            listPanel.removeAll()
            val ordered = reports.filter { it.status != TravelExpenseReportStatus.DRAFT }.sortedByDescending { it.createdAt.toString() }
            if (ordered.isEmpty()) {
                listPanel.p(tr("Noch keine eingereichten Anträge.")) { addCssClasses("text-muted small") }
            }
            ordered.forEach { report -> renderOwnReportCard(listPanel, report, focusedReportId, hasOpenDraft = draft != null, ::reload) }
        }
    }
    reload()
}

private fun renderRatesBanner(
    panel: SimplePanel,
    rates: TravelExpenseRatesDto,
) {
    val mileage = rates.mileageRatePerKm
    val perDiem = rates.perDiemRate
    if (mileage == null && perDiem == null) {
        panel.p(tr("Es sind noch keine Sätze konfiguriert -- bitte eine Administratorin oder einen Administrator informieren.")) {
            addCssClasses("alert alert-warning")
        }
    } else {
        panel.p(
            gettext(
                "Kilometersatz: %1 · Tagespauschale: %2",
                mileage?.let { "${formatMoney(it)}/km" } ?: tr("nicht konfiguriert"),
                perDiem?.let { formatMoney(it) } ?: tr("nicht konfiguriert"),
            ),
        ) { addCssClasses("text-muted small") }
    }
    if (!rates.expenseAccountConfigured || !rates.bankAccountConfigured) {
        panel.p(
            tr(
                "Ein Antrag ist möglich, aber die Buchung scheitert, bis eine Administratorin oder ein Administrator die Kontenzuordnung vervollständigt.",
            ),
        ) {
            addCssClasses("alert alert-warning")
        }
    }
}

private fun renderNewDraftButton(
    panel: SimplePanel,
    onChanged: () -> Unit,
) {
    val button = panel.button(tr("Neuen Antrag anlegen"), style = ButtonStyle.PRIMARY)
    button.onClick {
        val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
        button.hide()
        renderReportHeaderForm(formPanel, null) { purpose, from, to ->
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<ITravelExpenseService>().createDraft(
                            AppState.session?.memberId.orEmpty(),
                            TravelExpenseReportInput(purpose = purpose, travelFrom = from, travelTo = to),
                        )
                    }
                if (result != null) onChanged()
            }
        }
    }
}

private fun renderReportHeaderForm(
    panel: SimplePanel,
    existing: TravelExpenseReportDto?,
    onSave: (purpose: String, travelFrom: LocalDate, travelTo: LocalDate) -> Unit,
) {
    val purposeInput = panel.text(value = existing?.purpose, label = tr("Zweck der Reise"))
    val fromInput = panel.text(value = existing?.travelFrom?.toString(), label = tr("Von (JJJJ-MM-TT)"))
    val toInput = panel.text(value = existing?.travelTo?.toString(), label = tr("Bis (JJJJ-MM-TT)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val saveButton = panel.button(if (existing == null) tr("Entwurf anlegen") else tr("Entwurf speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val purpose = purposeInput.value?.trim().orEmpty()
        val from = runCatching { LocalDate.parse(fromInput.value.orEmpty().trim()) }.getOrNull()
        val to = runCatching { LocalDate.parse(toInput.value.orEmpty().trim()) }.getOrNull()
        if (purpose.isBlank() || from == null || to == null) {
            errorBox.content = tr("Bitte Zweck und ein gültiges Datum (JJJJ-MM-TT) für Von/Bis angeben.")
            errorBox.show()
            return@onClick
        }
        if (to < from) {
            errorBox.content = tr("Das Bis-Datum darf nicht vor dem Von-Datum liegen.")
            errorBox.show()
            return@onClick
        }
        onSave(purpose, from, to)
    }
}

private fun renderDraftEditor(
    panel: SimplePanel,
    draft: TravelExpenseReportDto,
    rates: TravelExpenseRatesDto,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
    card.div(tr("Entwurf")) { addCssClass("fw-bold") }
    renderReportHeaderForm(card, draft) { purpose, from, to ->
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ITravelExpenseService>().updateDraft(
                        draft.id,
                        TravelExpenseReportInput(purpose = purpose, travelFrom = from, travelTo = to),
                    )
                }
            if (result != null) {
                notifySuccess(tr("Entwurf gespeichert."))
                onChanged()
            }
        }
    }

    val addButtonsRow = card.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val addLinePanel = card.vPanel(spacing = 6)
    TravelExpenseLineKind.entries.forEach { kind ->
        val enabled = travelExpenseLineKindEnabled(kind, rates)
        val addButton =
            addButtonsRow.button(gettext("%1 hinzufügen", travelExpenseLineKindLabel(kind)), style = ButtonStyle.OUTLINESECONDARY) {
                icon = travelExpenseLineKindIcon(kind)
                disabled = !enabled
                if (!enabled) {
                    title =
                        when (kind) {
                            TravelExpenseLineKind.MILEAGE -> tr("Kilometersatz ist noch nicht konfiguriert.")
                            TravelExpenseLineKind.PER_DIEM -> tr("Tagespauschale ist noch nicht konfiguriert.")
                            TravelExpenseLineKind.RECEIPTED -> ""
                        }
                }
            }
        addButton.onClick { renderAddLineForm(addLinePanel, draft.id, kind, onChanged) }
    }

    if (draft.lines.isEmpty()) {
        card.p(tr("Noch keine Zeilen erfasst.")) { addCssClasses("text-muted small") }
    }
    draft.lines.forEach { line -> renderLineCard(card, line, onChanged) }

    val subtotals = travelExpenseSubtotals(draft)
    card.div(
        gettext(
            "Fahrtkosten: %1 · Tagespauschalen: %2 · Belegkosten: %3 · Gesamt: %4",
            formatMoney(subtotals[TravelExpenseLineKind.MILEAGE] ?: 0.0.toDecimal()),
            formatMoney(subtotals[TravelExpenseLineKind.PER_DIEM] ?: 0.0.toDecimal()),
            formatMoney(subtotals[TravelExpenseLineKind.RECEIPTED] ?: 0.0.toDecimal()),
            formatMoney(draft.totalAmount),
        ),
    ) { addCssClasses("fw-bold") }

    val blockReason = travelExpenseSubmitBlockReason(draft, rates)
    if (blockReason != null) {
        card.p(blockReason) { addCssClasses("text-muted small") }
    }
    val submitButton = card.button(tr("Zur Freigabe einreichen"), style = ButtonStyle.SUCCESS) { disabled = blockReason != null }
    submitButton.onClick {
        submitButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().submitReport(draft.id) }
                if (result != null) {
                    notifySuccess(tr("Antrag zur Freigabe eingereicht."))
                    onChanged()
                }
            } finally {
                submitButton.disabled = false
            }
        }
    }
}

private fun renderAddLineForm(
    panel: SimplePanel,
    reportId: String,
    kind: TravelExpenseLineKind,
    onChanged: () -> Unit,
) {
    panel.removeAll()
    val form = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    val descriptionInput = form.text(label = tr("Beschreibung"))
    val kilometersInput = if (kind == TravelExpenseLineKind.MILEAGE) form.text(label = tr("Kilometer")) else null
    val daysInput = if (kind == TravelExpenseLineKind.PER_DIEM) form.text(label = tr("Tage")) else null
    val amountInput = if (kind == TravelExpenseLineKind.RECEIPTED) form.text(label = tr("Betrag")) else null
    val errorBox =
        form.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val addButton = form.button(tr("Zeile hinzufügen"), style = ButtonStyle.PRIMARY)
    addButton.onClick {
        errorBox.hide()
        val description = descriptionInput.value?.trim().orEmpty()
        if (description.isBlank()) {
            errorBox.content = tr("Bitte eine Beschreibung angeben.")
            errorBox.show()
            return@onClick
        }
        val input =
            when (kind) {
                TravelExpenseLineKind.MILEAGE -> {
                    val km = kilometersInput?.value?.trim()?.toDoubleOrNull()
                    if (km == null || km <= 0.0) {
                        errorBox.content = tr("Bitte eine gültige Kilometerzahl angeben.")
                        errorBox.show()
                        return@onClick
                    }
                    TravelExpenseLineInput(kind = kind, description = description, kilometers = km.toDecimal())
                }
                TravelExpenseLineKind.PER_DIEM -> {
                    val days = daysInput?.value?.trim()?.toIntOrNull()
                    if (days == null || days <= 0) {
                        errorBox.content = tr("Bitte eine gültige Anzahl Tage angeben.")
                        errorBox.show()
                        return@onClick
                    }
                    TravelExpenseLineInput(kind = kind, description = description, days = days)
                }
                TravelExpenseLineKind.RECEIPTED -> {
                    val amount = amountInput?.value?.trim()?.toDoubleOrNull()
                    if (amount == null || amount <= 0.0) {
                        errorBox.content = tr("Bitte einen gültigen Betrag angeben.")
                        errorBox.show()
                        return@onClick
                    }
                    TravelExpenseLineInput(kind = kind, description = description, amount = amount.toDecimal())
                }
            }
        addButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().addLine(reportId, input) }
                if (result != null) {
                    notifySuccess(tr("Zeile hinzugefügt."))
                    onChanged()
                }
            } finally {
                addButton.disabled = false
            }
        }
    }
}

private fun renderLineCard(
    panel: SimplePanel,
    line: TravelExpenseLineDto,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(travelExpenseLineKindLabel(line.kind), travelExpenseLineKindColor(line.kind))
    headerRow.div(line.description) { addCssClasses("flex-grow-1") }
    headerRow.div(formatMoney(line.amount)) { addCssClasses("fw-bold") }
    val removeButton = headerRow.button(tr("Zeile entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.onClick {
        removeButton.disabled = true
        AppScope.launch {
            try {
                val result = guarded { rpcService<ITravelExpenseService>().removeLine(line.id) }
                if (result != null) onChanged()
            } finally {
                removeButton.disabled = false
            }
        }
    }
    when (line.kind) {
        TravelExpenseLineKind.MILEAGE ->
            card.div(gettext("%1 km × %2/km", line.kilometers, line.rateSnapshot?.let { "${formatMoney(it)}" } ?: "?")) {
                addCssClasses("text-muted small")
            }
        TravelExpenseLineKind.PER_DIEM ->
            card.div(gettext("%1 Tage × %2", line.days, line.rateSnapshot?.let { formatMoney(it) } ?: "?")) {
                addCssClasses("text-muted small")
            }
        TravelExpenseLineKind.RECEIPTED -> {
            line.receipts.forEach { receipt ->
                val receiptRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
                receiptRow.icon(receiptIcon(receipt.mimeType))
                receiptRow.link(receipt.originalFilename, url = TravelExpenseHttp.receiptDownloadUrl(receipt.id), target = "_blank")
                receiptRow.div(receiptSizeLabel(receipt.sizeBytes)) { addCssClasses("text-muted small") }
                val deleteReceiptButton = receiptRow.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
                deleteReceiptButton.onClick {
                    deleteReceiptButton.disabled = true
                    AppScope.launch {
                        val error = TravelExpenseHttp.deleteReceipt(receipt.id)
                        deleteReceiptButton.disabled = false
                        if (error != null) notifyError(error) else onChanged()
                    }
                }
            }
            val uploadRow = card.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
            val fileUpload = uploadRow.upload(label = tr("Beleg hochladen"))
            val uploadErrorBox =
                uploadRow.div().apply {
                    addCssClass("text-danger")
                    hide()
                }
            val uploadButton = uploadRow.button(tr("Hochladen"), style = ButtonStyle.PRIMARY)
            uploadButton.onClick {
                uploadErrorBox.hide()
                val selected = fileUpload.value?.firstOrNull()
                val nativeFile = selected?.let { fileUpload.getNativeFile(it) }
                if (nativeFile == null) {
                    uploadErrorBox.content = tr("Bitte eine Datei auswählen.")
                    uploadErrorBox.show()
                    return@onClick
                }
                uploadButton.disabled = true
                AppScope.launch {
                    val error = TravelExpenseHttp.uploadReceipt(line.id, nativeFile)
                    uploadButton.disabled = false
                    if (error != null) {
                        uploadErrorBox.content = error
                        uploadErrorBox.show()
                    } else {
                        notifySuccess(tr("Beleg hochgeladen."))
                        fileUpload.clearInput()
                        onChanged()
                    }
                }
            }
        }
    }
}

private fun renderOwnReportCard(
    panel: SimplePanel,
    report: TravelExpenseReportDto,
    focusedReportId: String?,
    hasOpenDraft: Boolean,
    onChanged: () -> Unit,
) {
    val card =
        panel.vPanel(spacing = 6) {
            addCssClasses("border rounded p-3")
            if (report.id == focusedReportId) addCssClass("border-primary")
        }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(travelExpenseStatusLabel(report.status), travelExpenseStatusColor(report.status))
    headerRow.div(report.purpose) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.div(formatMoney(report.totalAmount))
    card.div(gettext("%1 bis %2", report.travelFrom, report.travelTo)) { addCssClasses("text-muted small") }
    if (report.decisionNote != null) {
        card.div(gettext("Begründung: %1", report.decisionNote)) { addCssClasses("text-muted small") }
    }
    if (report.status == TravelExpenseReportStatus.APPROVED) {
        card.div(travelExpensePostingErrorMessage(report.executionError)) { addCssClasses("alert alert-danger") }
    }
    if (report.status == TravelExpenseReportStatus.EXECUTED) {
        card.div(travelExpensePayoutDisclaimer()) { addCssClasses("text-muted small") }
    }

    val actionsRow = card.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    if (travelExpenseCanWithdraw(report)) {
        val withdrawButton = actionsRow.button(tr("Zurückziehen"), style = ButtonStyle.OUTLINEDANGER)
        withdrawButton.onClick {
            withdrawButton.disabled = true
            AppScope.launch {
                try {
                    val result = guarded { rpcService<ITravelExpenseService>().withdrawReport(report.id) }
                    if (result != null) {
                        notifySuccess(tr("Antrag zurückgezogen."))
                        onChanged()
                    }
                } finally {
                    withdrawButton.disabled = false
                }
            }
        }
    }
    if (report.status == TravelExpenseReportStatus.REJECTED && hasOpenDraft) {
        card.div(
            tr(
                "Es liegt bereits ein Entwurf vor -- bitte diesen zuerst einreichen oder zurückziehen, bevor ein neuer Entwurf aus dieser Ablehnung kopiert wird.",
            ),
        ) { addCssClasses("text-muted small") }
    }
    if (travelExpenseCanCopyAsDraft(report, hasOpenDraft)) {
        val copyButton = actionsRow.button(tr("Als Entwurf kopieren"), style = ButtonStyle.OUTLINESECONDARY)
        copyButton.onClick {
            copyButton.disabled = true
            AppScope.launch {
                try {
                    val newDraft =
                        guarded {
                            rpcService<ITravelExpenseService>().createDraft(
                                report.subjectMemberId,
                                TravelExpenseReportInput(
                                    purpose = report.purpose,
                                    travelFrom = report.travelFrom,
                                    travelTo = report.travelTo,
                                ),
                            )
                        }
                    if (newDraft != null) {
                        report.lines.forEach { line ->
                            val input =
                                when (line.kind) {
                                    TravelExpenseLineKind.MILEAGE ->
                                        TravelExpenseLineInput(
                                            kind = line.kind,
                                            description = line.description,
                                            kilometers = line.kilometers,
                                        )
                                    TravelExpenseLineKind.PER_DIEM ->
                                        TravelExpenseLineInput(kind = line.kind, description = line.description, days = line.days)
                                    TravelExpenseLineKind.RECEIPTED ->
                                        TravelExpenseLineInput(kind = line.kind, description = line.description, amount = line.amount)
                                }
                            guarded { rpcService<ITravelExpenseService>().addLine(newDraft.id, input) }
                        }
                        notifySuccess(tr("Als neuer Entwurf kopiert. Belege müssen erneut hochgeladen werden."))
                        onChanged()
                    }
                } finally {
                    copyButton.disabled = false
                }
            }
        }
    }
}

// ── Pure, testable prädikate ──────────────────────────────────────────────────────────────

internal fun travelExpenseLineKindEnabled(
    kind: TravelExpenseLineKind,
    rates: TravelExpenseRatesDto,
): Boolean =
    when (kind) {
        TravelExpenseLineKind.MILEAGE -> rates.mileageRatePerKm != null
        TravelExpenseLineKind.PER_DIEM -> rates.perDiemRate != null
        TravelExpenseLineKind.RECEIPTED -> true
    }

internal fun travelExpenseSubmitBlockReason(
    report: TravelExpenseReportDto,
    rates: TravelExpenseRatesDto,
): String? {
    if (report.lines.isEmpty()) return tr("Mindestens eine Zeile ist erforderlich.")
    if (report.lines.any { it.kind == TravelExpenseLineKind.RECEIPTED && it.receipts.isEmpty() }) {
        return tr("Jede Beleg-Kosten-Zeile benötigt mindestens einen Beleg.")
    }
    if (report.lines.any { it.kind == TravelExpenseLineKind.MILEAGE } && rates.mileageRatePerKm == null) {
        return tr("Der Kilometersatz ist nicht konfiguriert.")
    }
    if (report.lines.any { it.kind == TravelExpenseLineKind.PER_DIEM } && rates.perDiemRate == null) {
        return tr("Die Tagespauschale ist nicht konfiguriert.")
    }
    return null
}

internal fun travelExpenseSubtotals(report: TravelExpenseReportDto): Map<TravelExpenseLineKind, Decimal> =
    TravelExpenseLineKind.entries.associateWith { kind ->
        report.lines
            .filter { it.kind == kind }
            .sumOf { it.amount.toDouble() }
            .toDecimal()
    }

internal fun travelExpenseCanWithdraw(report: TravelExpenseReportDto): Boolean = report.status in TravelExpenseReportStatusSets.WITHDRAWABLE

internal fun travelExpenseCanCopyAsDraft(
    report: TravelExpenseReportDto,
    hasOpenDraft: Boolean,
): Boolean = report.status == TravelExpenseReportStatus.REJECTED && !hasOpenDraft
