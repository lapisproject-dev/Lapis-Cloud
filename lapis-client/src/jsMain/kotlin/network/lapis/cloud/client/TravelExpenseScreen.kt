package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.upload.upload
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
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
 *
 * R24/R29 (W4d batch 3): die drei echten Eingabeformulare (Kopfdaten Zweck/Von/Bis, eine Zeile
 * hinzufügen, ein Beleg hochladen) sind [LapisForm]s; jeder schreibende `AppScope.launch` läuft
 * durch `form.submit`/`runGuardedAction`. Die Sätze-/Konfigurationsbänder ([renderRatesBanner])
 * bleiben unverändert im Textkörper -- sie sind datengetriebene Anzeigeflächen, kein Formular (siehe
 * "Known gaps" in `docs/architecture/ui-ux-guideline.adoc`).
 */
fun renderTravelExpenseScreen(
    container: SimplePanel,
    focusedReportId: String?,
) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Reisekosten"))

    val ratesBanner = root.vPanel(spacing = 4)
    val editorPanel = root.vPanel(spacing = 10)
    root.h2(tr("Meine Anträge")) { addCssClasses("h5 mt-3") }
    val listPanel = root.vPanel(spacing = 10)

    fun reload() {
        editorPanel.removeAll()
        listPanel.removeAll()
        AppScope.launch {
            // R34: ein fehlgeschlagener Ladevorgang ist ein Fehlerzustand mit Wiederholung, kein leeres Panel.
            val rates = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            if (rates == null) {
                ratesBanner.removeAll()
                editorPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }
            val reports = guarded { rpcService<ITravelExpenseService>().listMyReports() }
            if (reports == null) {
                listPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }

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
                mileage?.let { "${formatMoney(it)}/km" } ?: gettext("nicht konfiguriert"),
                perDiem?.let { formatMoney(it) } ?: gettext("nicht konfiguriert"),
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

// R24 (W4d): migrated to the form grammar -- Zweck (required text), Von/Bis (required date text, cross-field rule).
private fun renderReportHeaderForm(
    panel: SimplePanel,
    existing: TravelExpenseReportDto?,
    onSave: suspend (purpose: String, travelFrom: LocalDate, travelTo: LocalDate) -> Unit,
) {
    val form = panel.lapisForm()
    val purposeField =
        form.textField(
            label = tr("Zweck der Reise"),
            value = existing?.purpose,
            required = true,
            rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_PURPOSE_LENGTH) },
        )
    val fromField =
        form.textField(
            label = tr("Von (JJJJ-MM-TT)"),
            value = existing?.travelFrom?.toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(it) },
        )
    val toField =
        form.textField(
            label = tr("Bis (JJJJ-MM-TT)"),
            value = existing?.travelTo?.toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(it) },
        )
    // Das Bis-Datum darf nicht vor dem Von-Datum liegen -- am Bis-Feld gezeigt, geprüft sobald beide echte Daten sind.
    form.crossFieldRule(field = toField) {
        val from = runCatching { LocalDate.parse(fromField.value.trim()) }.getOrNull()
        val to = runCatching { LocalDate.parse(toField.value.trim()) }.getOrNull()
        if (from != null && to != null && to < from) {
            FieldCheck.Invalid(gettext("Das Bis-Datum darf nicht vor dem Von-Datum liegen."))
        } else {
            FieldCheck.Ok
        }
    }
    val saveButton = Button(if (existing == null) tr("Entwurf anlegen") else tr("Entwurf speichern"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val purpose = purposeField.value.trim()
            val from = LocalDate.parse(fromField.value.trim())
            val to = LocalDate.parse(toField.value.trim())
            onSave(purpose, from, to)
        }
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

    val addButtonsRow = card.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val addLinePanel = card.vPanel(spacing = 6)
    TravelExpenseLineKind.entries.forEach { kind ->
        val enabled = travelExpenseLineKindEnabled(kind, rates)
        val addButton =
            addButtonsRow.button(
                gettext("%1 hinzufügen", resolvedAttributeText(travelExpenseLineKindLabel(kind))),
                style = ButtonStyle.OUTLINESECONDARY,
            ) {
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
        runGuardedAction(submitButton) {
            val result = guarded { rpcService<ITravelExpenseService>().submitReport(draft.id) }
            if (result != null) {
                notifySuccess(tr("Antrag zur Freigabe eingereicht."))
                onChanged()
            }
        }
    }
}

// R24 (W4d): migrated to the form grammar -- Beschreibung (required text) plus exactly one of
// Kilometer/Tage/Betrag depending on [kind] (same discriminated-flat-row idiom [TravelExpenseLineInput] uses).
private fun renderAddLineForm(
    panel: SimplePanel,
    reportId: String,
    kind: TravelExpenseLineKind,
    onChanged: () -> Unit,
) {
    panel.removeAll()
    val wrapper = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    val form = wrapper.lapisForm()
    val descriptionField =
        form.textField(
            label = tr("Beschreibung"),
            required = true,
            requiredMessage = tr("Bitte eine Beschreibung angeben."),
            rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH) },
        )
    val kilometersField =
        if (kind == TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Kilometer"),
                required = true,
                hint = tr("Beispiel: 1234,56."),
                rule = { travelExpenseKilometersCheck(it) },
            )
        } else {
            null
        }
    val daysField =
        if (kind == TravelExpenseLineKind.PER_DIEM) {
            form.textField(
                label = tr("Tage"),
                required = true,
                rule = { FormRules.intInRange(it, 1, TravelExpenseAmountRules.MAX_DAYS) },
            )
        } else {
            null
        }
    val amountField =
        if (kind == TravelExpenseLineKind.RECEIPTED) {
            form.textField(
                label = tr("Betrag"),
                required = true,
                hint = tr("Beispiel: 1234,56."),
                rule = { travelExpenseLineAmountCheck(it) },
            )
        } else {
            null
        }
    val addButton = Button(tr("Zeile hinzufügen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = addButton)
    addButton.onClick {
        form.submit(addButton) {
            val description = descriptionField.value.trim()
            val input =
                when (kind) {
                    TravelExpenseLineKind.MILEAGE ->
                        TravelExpenseLineInput(
                            kind = kind,
                            description = description,
                            kilometers = travelExpenseParseDecimal(checkNotNull(kilometersField).value),
                        )
                    TravelExpenseLineKind.PER_DIEM ->
                        TravelExpenseLineInput(
                            kind = kind,
                            description = description,
                            days = checkNotNull(daysField).value.trim().toInt(),
                        )
                    TravelExpenseLineKind.RECEIPTED ->
                        TravelExpenseLineInput(
                            kind = kind,
                            description = description,
                            amount = travelExpenseParseDecimal(checkNotNull(amountField).value),
                        )
                }
            val result = guarded { rpcService<ITravelExpenseService>().addLine(reportId, input) }
            if (result != null) {
                notifySuccess(tr("Zeile hinzugefügt."))
                onChanged()
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
    headerRow.untrustedDiv(line.description, className = "flex-grow-1")
    headerRow.div(formatMoney(line.amount)) { addCssClasses("fw-bold") }
    val removeButton = headerRow.button(tr("Zeile entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.onClick {
        runGuardedAction(removeButton) {
            val result = guarded { rpcService<ITravelExpenseService>().removeLine(line.id) }
            if (result != null) onChanged()
        }
    }
    when (line.kind) {
        TravelExpenseLineKind.MILEAGE ->
            card.div(
                gettext(
                    "%1 km × %2/km",
                    line.kilometers?.let { formatPlainAmountIn(I18n.language, it) } ?: "?",
                    line.rateSnapshot?.let { formatMoney(it) } ?: "?",
                ),
            ) {
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
                receiptRow.untrustedLink(
                    receipt.originalFilename,
                    url = TravelExpenseHttp.receiptDownloadUrl(receipt.id),
                    target = "_blank",
                )
                receiptRow.div(receiptSizeLabel(receipt.sizeBytes)) { addCssClasses("text-muted small") }
                val deleteReceiptButton = receiptRow.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
                deleteReceiptButton.onClick {
                    runGuardedAction(deleteReceiptButton) {
                        val error = TravelExpenseHttp.deleteReceipt(receipt.id)
                        if (error != null) notifyError(error) else onChanged()
                    }
                }
            }
            // R24 (W4d): migrated to the form grammar -- the raw Upload control registered via `register`
            // (pattern `DocumentsScreen.kt`'s `renderVersionUpload`), same reason not Kilua RPC (see
            // `TravelExpenseHttp` KDoc): receipt bytes travel over a dedicated HTTP route.
            val uploadRow = card.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
            val uploadForm = uploadRow.lapisForm()
            val fileUpload = uploadForm.panel.upload(label = tr("Beleg hochladen"))

            fun selectedNativeFile() = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }
            val fileField =
                uploadForm.register(
                    fileUpload,
                    label = tr("Beleg hochladen"),
                    required = true,
                    requiredMessage = tr("Bitte eine Datei auswählen."),
                )
            val uploadButton = Button(tr("Hochladen"), style = ButtonStyle.PRIMARY)
            uploadForm.buttons(primary = uploadButton)
            uploadButton.onClick {
                uploadForm.submit(uploadButton) {
                    val nativeFile = selectedNativeFile() ?: return@submit
                    val error = TravelExpenseHttp.uploadReceipt(line.id, nativeFile)
                    if (error != null) {
                        uploadForm.showFormError(error)
                    } else {
                        notifySuccess(tr("Beleg hochgeladen."))
                        fileField.reset()
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
    headerRow.untrustedCardTitle(report.purpose)
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
            runGuardedAction(withdrawButton) {
                val result = guarded { rpcService<ITravelExpenseService>().withdrawReport(report.id) }
                if (result != null) {
                    notifySuccess(tr("Antrag zurückgezogen."))
                    onChanged()
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
            runGuardedAction(copyButton) {
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

/**
 * Feldregel für Kilometer/Betrag dieses Formulars: [parseAmountInput] OHNE Obergrenzen-Prüfung ([TravelExpenseAmountRules.MAX_KILOMETERS]
 * bleibt reine Server-Autorität, siehe `submitReport` KDoc) -- nur Form (Komma/Punkt, keine Tausendertrennzeichen) und Positivität werden
 * hier gespiegelt, dieselben bereits im Katalog vorhandenen Meldungen wie [openItemAmountCheck]. Kein eigener neuer Meldungstext, damit
 * diese Welle keine neuen `msgid`s über alle sieben Kataloge nachziehen muss, wo eine bestehende Meldung genügt.
 */
internal fun travelExpenseKilometersCheck(value: String): FieldCheck =
    when (val parsed = parseAmountInput(value, allowZero = false, enforceMaxAmount = false)) {
        is AmountInput.Empty -> FieldCheck.Ok
        is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
        is AmountInput.Valid -> FieldCheck.Ok
    }

/**
 * Feldregel für den Belegkosten-Betrag: [parseAmountInput] MIT der Obergrenze [TravelExpenseAmountRules.MAX_LINE_AMOUNT] (die Meldung ist
 * bereits im Katalog vorhanden, gleiches Muster wie [FormRules.postingAmount]/[FormRules.returnFee]).
 */
internal fun travelExpenseLineAmountCheck(value: String): FieldCheck =
    when (val parsed = parseAmountInput(value, allowZero = false, enforceMaxAmount = false)) {
        is AmountInput.Empty -> FieldCheck.Ok
        is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
        is AmountInput.Valid ->
            if (parsed.value.toDouble() > TravelExpenseAmountRules.MAX_LINE_AMOUNT) {
                FieldCheck.Invalid(
                    gettext(
                        "Der Betrag ist zu groß (höchstens %1).",
                        formatMoney(TravelExpenseAmountRules.MAX_LINE_AMOUNT.toDouble().toDecimal()),
                    ),
                )
            } else {
                FieldCheck.Ok
            }
    }

/** Wandelt einen bereits über [travelExpenseKilometersCheck]/[travelExpenseLineAmountCheck] geprüften Feldwert in seinen [Decimal] um. */
internal fun travelExpenseParseDecimal(value: String): Decimal =
    (parseAmountInput(value, allowZero = false, enforceMaxAmount = false) as AmountInput.Valid).value
