package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.radioGroup
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.shared.domain.CalcResult
import network.lapis.cloud.shared.domain.DayInput
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseCalculator
import network.lapis.cloud.shared.domain.TravelExpenseDayInput
import network.lapis.cloud.shared.domain.TravelExpenseDeadline
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLElement

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
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Reisekosten"), intro = PageIntros.forRoute(Routes.TRAVEL_EXPENSES))
    // The create button gets a slot of its own: it appears only after a successful load without an open draft.
    val draftButtonSlot = header.actionSlot.div().apply { hide() }

    // V1.9.122: the contact person comes first -- a member with a question finds the answer before the form.
    // V1.9.199: contact person left, organisation-wide documents right (one column on a narrow screen, see theme.css).
    val contactPanel = root.div(className = "lapis-te-contact-row")
    val ratesBanner = root.vPanel(spacing = 4)
    val newDraftHost = root.vPanel(spacing = 10)
    val editorPanel = root.vPanel(spacing = 10)
    root.h2(tr("Meine Anträge")) { addCssClasses("h5 mt-3") }
    val listPanel = root.vPanel(spacing = 10)

    var newDraftForm: CollapsibleCreateFormController<Unit>? = null
    var loadGeneration = 0

    fun reload() {
        // `reload` never touches `newDraftHost`: text typed into the open form survives a reload (withdraw, copy as draft ...).
        val generation = ++loadGeneration
        draftButtonSlot.hide()
        editorPanel.removeAll()
        listPanel.removeAll()
        AppScope.launch {
            // R34: ein fehlgeschlagener Ladevorgang ist ein Fehlerzustand mit Wiederholung, kein leeres Panel.
            val rates = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            if (rates == null) {
                ratesBanner.removeAll()
                contactPanel.removeAll()
                editorPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }
            val reports = guarded { rpcService<ITravelExpenseService>().listMyReports() }
            if (reports == null) {
                listPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }

            contactPanel.removeAll()
            renderTravelExpenseContactBlock(contactPanel, rates)
            renderTravelExpenseDocumentsList(contactPanel, rates)
            ratesBanner.removeAll()
            renderRatesBanner(ratesBanner, rates)

            editorPanel.removeAll()
            val draft = reports.firstOrNull { it.status == TravelExpenseReportStatus.DRAFT }
            if (draft != null) {
                renderDraftEditor(editorPanel, draft, rates, ::reload)
                // A draft exists (maybe created elsewhere meanwhile): an open create form is closed -- asking first when it was changed.
                newDraftForm?.requestClose {}
            } else if (generation == loadGeneration) {
                draftButtonSlot.show()
            }

            listPanel.removeAll()
            val ordered = reports.filter { it.status != TravelExpenseReportStatus.DRAFT }.sortedByDescending { it.createdAt.toString() }
            if (ordered.isEmpty()) {
                listPanel.p(tr("Noch keine eingereichten Anträge.")) { addCssClasses("text-muted small") }
            }
            ordered.forEach { report -> renderOwnReportCard(listPanel, report, focusedReportId, hasOpenDraft = draft != null, ::reload) }
        }
    }
    newDraftForm =
        collapsibleCreateForm<Unit>(
            actionSlot = draftButtonSlot,
            formHost = newDraftHost,
            buttonLabel = tr("Neuer Reisekostenantrag"),
            formId = "travel-expense-create",
        ) { _, close ->
            // V1.9.49 (R36B): creating a draft; it exists only while no draft is open (one draft at a time).
            val formPanel = vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
            renderReportHeaderForm(formPanel, existing = null, collapse = close) { purpose, from, to ->
                val result =
                    guarded {
                        rpcService<ITravelExpenseService>().createDraft(
                            AppState.session?.memberId.orEmpty(),
                            TravelExpenseReportInput(purpose = purpose, travelFrom = from, travelTo = to),
                        )
                    }
                if (result != null) {
                    close(true)
                    reload()
                }
            }
        }
    reload()
}

private fun renderRatesBanner(
    panel: SimplePanel,
    rates: TravelExpenseRatesDto,
) {
    val rateSet = rates.rateSet
    val mileage = rateSet?.mileageCentsPerKm
    if (mileage == null) {
        panel.p(tr("Der Kilometersatz ist noch nicht konfiguriert -- bitte die Verwaltung der Instanz informieren.")) {
            addCssClasses("alert alert-warning")
        }
    }
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
                mileage?.let { "${formatMoney(centsToDecimal(it))}/km" } ?: gettext("nicht konfiguriert"),
            ),
        ) { addCssClasses("text-muted small") }
    }
    if (!rates.expenseAccountConfigured || !rates.bankAccountConfigured) {
        panel.p(
            tr(
                "Ein Antrag ist möglich, aber die Buchung scheitert, bis die Verwaltung der Instanz die Kontenzuordnung vervollständigt.",
            ),
        ) {
            addCssClasses("alert alert-warning")
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

    // Kopfdaten: the membership number is read-only (it comes from the member record, never typed in), the deadline is a hint that never blocks.
    card.div(
        draft.membershipNumber?.let { gettext("Mitgliedsnummer: %1", it) } ?: gettext("Mitgliedsnummer: nicht hinterlegt"),
        className = "text-muted small",
    )
    if (travelExpenseDeadlineOverdue(draft, organizationToday())) {
        card.div(
            gettext(
                "Die Frist für diese Reise (%1) ist überschritten. Sie können trotzdem einreichen -- die Verwaltung entscheidet.",
                localeDateString(TravelExpenseDeadline.deadlineFor(draft.travelTo)),
            ),
            className = "alert alert-warning mb-0",
        )
    } else {
        card.div(
            gettext("Einreichen bis %1", localeDateString(TravelExpenseDeadline.deadlineFor(draft.travelTo))),
            className = "text-muted small",
        )
    }
    // V1.9.181: the day grid is built further down; until then the header form's "dates changed" state waits here.
    var dayHandle: TravelExpenseDayGrid? = null
    var rangeStale = false
    renderReportHeaderForm(card, draft, onRangeDirtyChanged = { dirty ->
        rangeStale = dirty
        dayHandle?.setRangeStale(dirty)
    }) { purpose, from, to ->
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

    // Two columns on a desktop (the totals bar sticks on the right), one column on a phone (the bar sticks to the bottom edge).
    val layout = card.div(className = "lapis-te-editor")
    val main = layout.vPanel(spacing = 8)
    val bar = TravelExpenseTotalsBar(layout)
    // V1.9.132: the live breakdown follows the totals bar in the DOM (and in the focus order); `theme.css` puts it under the form on a desktop.
    val breakdown = TravelExpenseBreakdownView(layout)
    var settlementAdvanceCents = draft.advanceCents

    fun refreshTotals() {
        val entries = dayHandle?.entries() ?: emptyList()
        val rateSet = rates.rateSet
        if (rateSet == null) {
            bar.show(
                TravelExpenseCalculator.totals(
                    draft.lines.map { it.amountCents },
                    draft.lines.map { it.deductionCents },
                    settlementAdvanceCents,
                ),
                dirty = false,
            )
            return
        }
        val preview = travelExpensePreview(draft, rateSet, entries, settlementAdvanceCents)
        bar.show(preview.totals, dirty = (dayHandle?.isDirty() ?: false) || settlementAdvanceCents != draft.advanceCents)
        breakdown.update(buildBreakdown(draft, rateSet, entries, settlementAdvanceCents))
    }

    // Tagesraster
    val rateSet = rates.rateSet
    if (rateSet != null) {
        dayHandle =
            renderTravelExpenseDayGrid(
                panel = main,
                draft = draft,
                rates = rateSet,
                onPreviewChanged = { refreshTotals() },
                onSaved = onChanged,
            )
        dayHandle?.setRangeStale(rangeStale)
    }

    // V1.9.198: when the missing receipt is why submitting is blocked, the hint is wired to the first affected line's file chooser.
    val missingReceiptBlocks = travelExpenseSubmitBlockReason(draft, rates) == travelExpenseMissingReceiptText()

    // Weitere Kosten
    renderExtraCostsSection(
        main,
        draft,
        rates,
        onChanged,
        missingReceiptLineId = if (missingReceiptBlocks) travelExpenseFirstLineMissingReceipt(draft) else null,
        missingReceiptHintId = TRAVEL_EXPENSE_MISSING_RECEIPT_HINT_ID,
    )

    // V1.9.198: report-level attachments (an invitation, an itinerary); optional.
    renderTravelExpenseAttachments(main, draft, editable = true, onChanged = onChanged)

    // Vorschuss und Auszahlung
    main.h2(tr("Vorschuss und Auszahlung")) { addCssClass("h6") }
    val settlementForm = main.lapisForm()
    val advanceField =
        settlementForm.textField(
            label = tr("Bereits erhaltener Vorschuss (EUR)"),
            value = if (draft.advanceCents > 0) travelExpenseCentsText(draft.advanceCents) else null,
            hint = tr("Leer lassen, wenn Sie keinen Vorschuss erhalten haben. Beispiel: 50,00."),
            rule = { travelExpenseAdvanceCheck(it) },
        )
    val payoutRadio =
        settlementForm.panel.radioGroup(
            options = TravelExpensePayoutChoice.entries.map { it.name to travelExpensePayoutChoiceLabel(it) },
            // Deliberately NO preselection: the member decides consciously whether to be paid or to waive.
            value = draft.payoutChoice?.name,
            label = tr("Wie soll abgerechnet werden?"),
        )
    settlementForm.register(
        control = payoutRadio,
        label = tr("Wie soll abgerechnet werden?"),
        required = true,
        requiredMessage = tr("Bitte wählen Sie, ob ausgezahlt oder auf die Erstattung verzichtet werden soll."),
    )
    settlementForm.panel.div(tr("Die Zuwendungsbestätigung wird außerhalb von Lapis ausgestellt."), className = "text-muted small")
    // V1.9.123: the account the refund is transferred to. The full IBAN is stored encrypted and never shown again -- only the masked
    // end ("IBAN •••• 2051") -- so changing it means typing it anew. Visible only while "Auszahlung" is chosen. Only the person the
    // money goes to may name the account: for a draft filed on someone's behalf the subject does it after submission.
    val isSubject = AppState.session?.memberId == draft.subjectMemberId
    val ibanField =
        settlementForm.textField(
            label = tr("IBAN für Erstattungen"),
            hint =
                draft.refundIbanLast4?.let { gettext("Gespeichert: IBAN •••• %1. Zum Ändern die neue IBAN eingeben.", it) }
                    ?: tr("Wird für die Überweisung benötigt. Die IBAN wird verschlüsselt gespeichert."),
            rule = { travelExpenseRefundIbanCheck(it) },
        )
    val holderField =
        settlementForm.textField(
            label = tr("Kontoinhaber"),
            value = draft.refundAccountHolder ?: draft.subjectDisplayName,
            rule = { FormRules.maxLength(it, TRAVEL_EXPENSE_MAX_HOLDER_LENGTH) },
        )
    val requesterNote =
        settlementForm.panel.div(
            tr("Die IBAN für die Erstattung trägt die reisende Person nach dem Einreichen selbst bei der Abrechnung ein."),
        ) { addCssClasses("text-muted small") }

    fun updateRefundFieldVisibility(choice: String?) {
        val payout = choice == TravelExpensePayoutChoice.PAYOUT.name
        ibanField.setVisible(payout && isSubject)
        holderField.setVisible(payout && isSubject)
        requesterNote.visible = payout && !isSubject
    }
    updateRefundFieldVisibility(draft.payoutChoice?.name)
    payoutRadio.subscribe { updateRefundFieldVisibility(it) }
    if (isSubject && draft.refundIbanLast4 == null) {
        // Prefill from the member's own earlier report; the server only ever answers the subject.
        AppScope.launch {
            val prefill = guarded { rpcService<ITravelExpenseService>().getRefundAccountPrefill(draft.id) }
            if (prefill?.iban != null && ibanField.value.isBlank()) {
                ibanField.setValue(prefill.iban)
                prefill.accountHolder?.let { holderField.setValue(it) }
            }
        }
    }
    advanceField.subscribe {
        settlementAdvanceCents = travelExpenseParseCents(it) ?: settlementAdvanceCents
        refreshTotals()
    }
    val settlementSave = newActionButton(ActionIcon.SAVE, tr("Vorschuss und Auszahlung speichern"), ButtonStyle.PRIMARY)
    settlementForm.buttons(primary = settlementSave)
    settlementSave.onClick {
        settlementForm.submit(settlementSave) {
            val cents = travelExpenseParseCents(advanceField.value) ?: 0L
            val choice = payoutRadio.value?.let { value -> TravelExpensePayoutChoice.entries.firstOrNull { it.name == value } }
            val result =
                guarded {
                    rpcService<ITravelExpenseService>().updateDraft(
                        draft.id,
                        TravelExpenseReportInput(
                            purpose = draft.purpose,
                            travelFrom = draft.travelFrom,
                            travelTo = draft.travelTo,
                            advanceCents = cents,
                            payoutChoice = choice,
                        ),
                    )
                }
            if (result != null) {
                // V1.9.123: only a typed IBAN is sent -- an empty field leaves the stored account untouched. A changed holder with
                // no typed IBAN is sent as a holder-only update, so a corrected name is never dropped silently.
                val request =
                    if (isSubject && choice == TravelExpensePayoutChoice.PAYOUT) {
                        travelExpenseRefundAccountRequest(
                            typedIban = ibanField.value,
                            typedHolder = holderField.value,
                            storedLast4 = draft.refundIbanLast4,
                            storedHolder = draft.refundAccountHolder,
                        )
                    } else {
                        null
                    }
                if (request != null) {
                    val withAccount =
                        guarded {
                            rpcService<ITravelExpenseService>().setRefundAccount(
                                reportId = draft.id,
                                iban = request.iban,
                                accountHolder = request.holder,
                            )
                        }
                    if (withAccount == null) return@submit
                }
                notifySuccess(tr("Entwurf gespeichert."))
                onChanged()
            }
        }
    }
    refreshTotals()

    val blockReason = travelExpenseSubmitBlockReason(draft, rates, daysDirty = dayHandle?.isDirty() ?: false)
    if (blockReason != null) {
        main.p(blockReason) {
            addCssClasses("text-muted small")
            if (missingReceiptBlocks) setAttribute("id", TRAVEL_EXPENSE_MISSING_RECEIPT_HINT_ID)
        }
    }
    val submitButton =
        main.actionButton(ActionIcon.SEND, tr("Zur Freigabe einreichen"), style = ButtonStyle.SUCCESS) {
            // The missing-receipt block stays focusable (aria-disabled + aria-describedby, pattern of V1.9.158): a click moves the focus to
            // the file chooser of the first affected line instead of doing nothing. Every other reason keeps the native `disabled`.
            disabled = blockReason != null && !missingReceiptBlocks
            if (missingReceiptBlocks) {
                setAttribute("aria-disabled", "true")
                setAttribute("aria-describedby", TRAVEL_EXPENSE_MISSING_RECEIPT_HINT_ID)
            }
        }
    submitButton.onClick {
        if (missingReceiptBlocks) {
            (document.querySelector("[data-lapis-te-first-missing] input[type=file]") as? HTMLElement)?.focus()
            return@onClick
        }
        runGuardedAction(submitButton) {
            val result = guarded { rpcService<ITravelExpenseService>().submitReport(draft.id) }
            if (result != null) {
                notifySuccess(tr("Antrag zur Freigabe eingereicht."))
                onChanged()
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
    card.div(
        gettext("%1 bis %2", localeDateString(report.travelFrom), localeDateString(report.travelTo)),
    ) { addCssClasses("text-muted small") }
    // V1.9.132: how the amount comes about, from the report's FROZEN rates (never the current ones).
    TravelExpenseBreakdownView(card, collapsible = true).update(buildBreakdown(report, report.ratesSnapshot, null, report.advanceCents))
    if (report.advanceCents > 0 || report.payoutChoice != null) {
        card.div(
            gettext(
                "Vorschuss: %1 · Auszahlungsbetrag: %2",
                formatMoney(centsToDecimal(report.advanceCents)),
                formatMoney(centsToDecimal(report.payoutCents)),
            ),
        ) { addCssClasses("text-muted small") }
    }
    if (report.payoutChoice == TravelExpensePayoutChoice.DONATION_WAIVER) {
        card.typeBadge(tr("Spendenquittung gewünscht"), "info")
    }
    if (report.decisionNote != null) {
        card.div(gettext("Begründung: %1", report.decisionNote)) { addCssClasses("text-muted small") }
    }
    if (report.status == TravelExpenseReportStatus.APPROVED) {
        card.div(travelExpensePostingErrorMessage(report.executionError)) { addCssClasses("alert alert-danger") }
    }
    if (report.status == TravelExpenseReportStatus.EXECUTED) {
        card.div(travelExpensePayoutDisclaimer()) { addCssClasses("text-muted small") }
    }

    // V1.9.198: read-only attachments (download only) -- an own report is never editable here (a draft has its own editor).
    renderTravelExpenseAttachments(card, report, editable = false, onChanged = onChanged)

    if (travelExpenseCanSetRefundAccount(report, AppState.session?.memberId)) renderRefundAccountForm(card, report, onChanged)

    val actionsRow = card.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    if (travelExpenseCanWithdraw(report)) {
        val withdrawButton = actionsRow.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), style = ButtonStyle.OUTLINEDANGER)
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
                                advanceCents = report.advanceCents,
                                payoutChoice = report.payoutChoice,
                            ),
                        )
                    }
                if (newDraft != null) {
                    // The day grid of the copy starts from the times/meals of the original (the server created one empty line per day).
                    val days = travelExpenseCopiedDays(report)
                    if (days.isNotEmpty()) guarded { rpcService<ITravelExpenseService>().saveDays(newDraft.id, days) }
                    travelExpenseCopiedLines(report).forEach { input ->
                        guarded { rpcService<ITravelExpenseService>().addLine(newDraft.id, input) }
                    }
                    notifySuccess(tr("Als neuer Entwurf kopiert. Belege und Anlagen müssen erneut hochgeladen werden."))
                    onChanged()
                }
            }
        }
    }
}

/**
 * V1.9.123: the refund-account form on a submitted, approved or executed report. Only the person the money goes to sees it. It is the
 * recovery path for a report without a (correct) IBAN -- filed on the member's behalf, submitted without one, booked before the
 * feature existed, or returned by the bank after the export. The stored IBAN is never shown, only its masked end.
 */
private fun renderRefundAccountForm(
    card: SimplePanel,
    report: TravelExpenseReportDto,
    onChanged: () -> Unit,
) {
    val hasAccount = report.refundIbanLast4 != null
    val box = card.vPanel(spacing = 4) { addCssClasses("border-top pt-2") }
    if (!hasAccount) {
        box.div(tr("Noch keine IBAN hinterlegt: Ohne IBAN kann die Erstattung nicht überwiesen werden.")) {
            addCssClasses("alert alert-warning")
        }
    }
    if (report.sepaExportedAt != null) {
        box.div(
            tr(
                "Die Erstattung wurde bereits überwiesen. Ein geändertes Konto wird erst durch eine erneute Exportdatei der " +
                    "Kassenführung verwendet.",
            ),
        ) { addCssClasses("text-muted small") }
    }
    val form = box.lapisForm()
    val ibanField =
        form.textField(
            label = tr("IBAN für Erstattungen"),
            hint =
                report.refundIbanLast4?.let { gettext("Gespeichert: IBAN •••• %1. Zum Ändern die neue IBAN eingeben.", it) }
                    ?: tr("Wird für die Überweisung benötigt. Die IBAN wird verschlüsselt gespeichert."),
            required = !hasAccount,
            requiredMessage = tr("Bitte geben Sie die IBAN ein."),
            rule = { travelExpenseRefundIbanCheck(it) },
        )
    val holderField =
        form.textField(
            label = tr("Kontoinhaber"),
            value = report.refundAccountHolder ?: report.subjectDisplayName,
            rule = { FormRules.maxLength(it, TRAVEL_EXPENSE_MAX_HOLDER_LENGTH) },
        )
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Konto speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val request =
                travelExpenseRefundAccountRequest(
                    typedIban = ibanField.value,
                    typedHolder = holderField.value,
                    storedLast4 = report.refundIbanLast4,
                    storedHolder = report.refundAccountHolder,
                )
            if (request == null) {
                notifySuccess(tr("Erstattungskonto gespeichert."))
                return@submit
            }
            val result =
                guarded {
                    rpcService<ITravelExpenseService>().setRefundAccount(
                        reportId = report.id,
                        iban = request.iban,
                        accountHolder = request.holder,
                    )
                }
            if (result != null) {
                notifySuccess(tr("Erstattungskonto gespeichert."))
                onChanged()
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
        TravelExpenseLineKind.MILEAGE -> rates.rateSet?.mileageCentsPerKm != null
        TravelExpenseLineKind.PER_DIEM -> rates.perDiemRate != null
        else -> true
    }

/**
 * Why "Zur Freigabe einreichen" is still disabled, or `null`. Mirrors what `TravelExpenseService.submitReport` refuses (the server stays the
 * authority): no payout choice, an advance above the total, unsaved day entries, a day whose times are not complete, a missing receipt, an
 * unconfigured mileage rate.
 */
internal fun travelExpenseSubmitBlockReason(
    report: TravelExpenseReportDto,
    rates: TravelExpenseRatesDto,
    daysDirty: Boolean = false,
): String? {
    if (report.lines.isEmpty()) return tr("Mindestens eine Zeile ist erforderlich.")
    if (daysDirty) return tr("Bitte speichern Sie zuerst die Reisetage.")
    if (report.payoutChoice == null) return tr("Bitte wählen Sie, wie abgerechnet werden soll (Auszahlung oder Spendenquittung).")
    if (report.advanceCents > report.lines.sumOf { it.amountCents }) {
        return tr("Der Vorschuss ist höher als die Abrechnung -- bitte wenden Sie sich an den Ansprechpartner.")
    }
    if (travelExpenseHasIncompleteDay(report)) return tr("Bitte tragen Sie für jeden Reisetag die Uhrzeiten ein und speichern Sie.")
    if (report.lines.any { it.kind.requiresReceipt && it.receipts.isEmpty() }) {
        return travelExpenseMissingReceiptText()
    }
    if (report.lines.any { it.kind == TravelExpenseLineKind.MILEAGE } && rates.rateSet?.mileageCentsPerKm == null) {
        return tr("Der Kilometersatz ist nicht konfiguriert.")
    }
    if (report.lines.any { it.kind == TravelExpenseLineKind.PER_DIEM } && rates.perDiemRate == null) {
        return tr("Die Tagespauschale ist nicht konfiguriert.")
    }
    return null
}

/** The (unchanged) block text for a receipted line without a receipt. */
internal fun travelExpenseMissingReceiptText(): String = tr("Jede Beleg-Kosten-Zeile benötigt mindestens einen Beleg.")

/** Id of the hint paragraph that `aria-describedby` of the submit button and of the first affected file chooser point to. */
internal const val TRAVEL_EXPENSE_MISSING_RECEIPT_HINT_ID = "lapis-te-missing-receipt"

/** Id of the first non-day line that needs a receipt and has none, or `null`. */
internal fun travelExpenseFirstLineMissingReceipt(report: TravelExpenseReportDto): String? =
    report.lines.firstOrNull { it.kind.requiresReceipt && it.receipts.isEmpty() }?.id

/** `true` if a saved day line misses the times its position needs (the shared calculator says so -- no rule of its own here). */
internal fun travelExpenseHasIncompleteDay(report: TravelExpenseReportDto): Boolean =
    report.lines
        .filter { it.kind == TravelExpenseLineKind.DAY_ALLOWANCE && it.dayDate != null }
        .any { line ->
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = requireNotNull(line.dayDate),
                        timeFrom = line.timeFrom,
                        timeTo = line.timeTo,
                        breakfastProvided = line.breakfastProvided,
                        lunchProvided = line.lunchProvided,
                        dinnerProvided = line.dinnerProvided,
                    ),
                travelFrom = report.travelFrom,
                travelTo = report.travelTo,
                forcedBreakfastDates = emptySet(),
                rates = TravelExpenseDefaults.pdvTemplate(),
            ) is CalcResult.Err
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

/** The day entries of a rejected report, as the day grid of its copy starts with them. */
internal fun travelExpenseCopiedDays(report: TravelExpenseReportDto): List<TravelExpenseDayInput> =
    report.lines
        .filter { it.kind == TravelExpenseLineKind.DAY_ALLOWANCE && it.dayDate != null }
        .map { line ->
            TravelExpenseDayInput(
                dayDate = requireNotNull(line.dayDate),
                timeFrom = line.timeFrom,
                timeTo = line.timeTo,
                breakfastProvided = line.breakfastProvided,
                lunchProvided = line.lunchProvided,
                dinnerProvided = line.dinnerProvided,
                destination = line.destination,
            )
        }

/**
 * The non-day lines of a rejected report as the inputs of its copy. The two legacy kinds cannot be created any more: a legacy receipted line
 * becomes "Sonstiges" (OTHER) with the same amount, a legacy flat per-day line is dropped (the day grid replaces it).
 */
internal fun travelExpenseCopiedLines(report: TravelExpenseReportDto): List<TravelExpenseLineInput> =
    report.lines.mapNotNull { line ->
        when (line.kind) {
            TravelExpenseLineKind.DAY_ALLOWANCE, TravelExpenseLineKind.PER_DIEM -> null
            TravelExpenseLineKind.MILEAGE ->
                TravelExpenseLineInput(
                    kind = line.kind,
                    description = line.description,
                    kilometers = line.kilometers,
                    passengerCount = line.passengerCount,
                )
            TravelExpenseLineKind.ACCOMMODATION ->
                TravelExpenseLineInput(
                    kind = line.kind,
                    description = line.description,
                    dayDate = line.dayDate,
                    nights = line.nights,
                    breakfastIncluded = line.breakfastIncluded,
                    amountCents = line.amountCents,
                )
            TravelExpenseLineKind.RECEIPTED ->
                TravelExpenseLineInput(kind = TravelExpenseLineKind.OTHER, description = line.description, amountCents = line.amountCents)
            TravelExpenseLineKind.PUBLIC_TRANSPORT, TravelExpenseLineKind.HOSPITALITY, TravelExpenseLineKind.OTHER ->
                TravelExpenseLineInput(kind = line.kind, description = line.description, amountCents = line.amountCents)
        }
    }

/** Longest account holder name the server accepts (and a pain.001 `Nm` element allows). */
internal const val TRAVEL_EXPENSE_MAX_HOLDER_LENGTH = 70

/** V1.9.123 Feldregel für die Erstattungs-IBAN (optional): leer ist gültig, sonst Länderkennung, Länge und Prüfsumme (lockere Spiegelung des Servers). */
internal fun travelExpenseRefundIbanCheck(value: String): FieldCheck =
    if (value.isBlank() || Validation.looksLikeIban(value.trim())) FieldCheck.Ok else FieldCheck.Invalid(gettext("Die IBAN ist ungültig."))

/** What to send to `setRefundAccount`: [iban] `null` with a [holder] is the holder-only update, never a clearing. */
internal class TravelExpenseRefundAccountRequest(
    val iban: String?,
    val holder: String?,
)

/**
 * The `setRefundAccount` call a save needs, or `null` when nothing changed. A typed IBAN is sent with the holder; without one, a
 * holder that differs from the stored one is sent alone (a corrected name must not be dropped silently, and `iban = null` with no
 * holder would CLEAR the account, so that is never produced).
 */
internal fun travelExpenseRefundAccountRequest(
    typedIban: String,
    typedHolder: String,
    storedLast4: String?,
    storedHolder: String?,
): TravelExpenseRefundAccountRequest? {
    val iban = typedIban.trim()
    val holder = typedHolder.trim().ifBlank { null }
    return when {
        iban.isNotEmpty() -> TravelExpenseRefundAccountRequest(iban = iban, holder = holder)
        storedLast4 != null && holder != null && holder != storedHolder -> TravelExpenseRefundAccountRequest(iban = null, holder = holder)
        else -> null
    }
}

/** The refund-account form on an own report: the subject only, a payout report, still alive (not a draft -- that has its own editor). */
internal fun travelExpenseCanSetRefundAccount(
    report: TravelExpenseReportDto,
    memberId: String?,
): Boolean =
    memberId != null &&
        report.subjectMemberId == memberId &&
        !report.paidOutsideLapis &&
        report.payoutChoice == TravelExpensePayoutChoice.PAYOUT &&
        report.status in setOf(TravelExpenseReportStatus.REQUESTED, TravelExpenseReportStatus.APPROVED, TravelExpenseReportStatus.EXECUTED)

/** Feldregel für den Vorschuss (optional, leer = 0): ganze Cent, nicht negativ, höchstens [TravelExpenseAmountRules.MAX_ADVANCE_CENTS]. */
internal fun travelExpenseAdvanceCheck(value: String): FieldCheck {
    val cents = travelExpenseParseCents(value)
    return when {
        cents == null -> FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1234,56 eingeben (ohne Tausendertrennzeichen)."))
        cents > TravelExpenseAmountRules.MAX_ADVANCE_CENTS ->
            FieldCheck.Invalid(
                gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(centsToDecimal(TravelExpenseAmountRules.MAX_ADVANCE_CENTS))),
            )
        else -> FieldCheck.Ok
    }
}

/**
 * Feldregel für den Betrag einer Beleg-Zeile: ganze Cent, größer als 0, höchstens [TravelExpenseAmountRules.MAX_RECEIPTED_CENTS]
 * (10 000,00 EUR). Leere behandelt `required`.
 */
internal fun travelExpenseCentsAmountCheck(value: String): FieldCheck {
    val cents = travelExpenseParseCents(value)
    return when {
        cents == null -> FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1234,56 eingeben (ohne Tausendertrennzeichen)."))
        cents < 1 -> FieldCheck.Invalid(gettext("Der Betrag muss größer als 0 sein."))
        cents > TravelExpenseAmountRules.MAX_RECEIPTED_CENTS ->
            FieldCheck.Invalid(
                gettext(
                    "Der Betrag ist zu groß (höchstens %1).",
                    formatMoney(centsToDecimal(TravelExpenseAmountRules.MAX_RECEIPTED_CENTS)),
                ),
            )
        else -> FieldCheck.Ok
    }
}

/** First night must be a date inside the travel period (the per-night range is the cross-field rule of the form). */
internal fun travelExpenseFirstNightRangeCheck(
    date: LocalDate,
    travelFrom: LocalDate,
    travelTo: LocalDate,
): FieldCheck =
    if (date < travelFrom || date > travelTo) {
        FieldCheck.Invalid(gettext("Die Übernachtungen müssen innerhalb der Reise liegen."))
    } else {
        FieldCheck.Ok
    }

/**
 * [travelExpenseFirstNightRangeCheck] for TYPED text, in the date order [spec] (default: the current locale): a text that is not a date is the
 * date field's own format error, a date outside the trip the range error.
 */
internal fun travelExpenseFirstNightCheck(
    value: String,
    travelFrom: LocalDate,
    travelTo: LocalDate,
    spec: TemporalLocale = currentTemporalLocale(),
): FieldCheck {
    val parsed = parseLocalDate(value, spec)
    return if (parsed is DateParse.Ok) {
        travelExpenseFirstNightRangeCheck(
            parsed.date,
            travelFrom,
            travelTo,
        )
    } else {
        FormRules.localDate(value, spec)
    }
}

/** Whether `nights` nights from [firstNight] end inside the travel period: the morning after the last night must be <= [travelTo]. */
internal fun travelExpenseAccommodationFits(
    firstNight: LocalDate,
    nights: Int,
    travelFrom: LocalDate,
    travelTo: LocalDate,
): Boolean = nights >= 1 && firstNight >= travelFrom && firstNight.plus(nights, DateTimeUnit.DAY) <= travelTo

/**
 * V1.9.125 "Mitfahrten" -- a suggestion for the "Mitfahrer" field of a mileage line: the number of confirmed passengers on the person's OWN
 * offers within the travel period of the report. Shown ONLY to the subject of the report and only as an ACTIVE member (the question is about
 * their own offers); the answer is a number, never a name. A mileage line has no date of its own, so the whole travel period is asked and a
 * note says when several trips fall into it. **Never filled in automatically**: only "Übernehmen" writes the field, and the calculator and the
 * server stay untouched. A failed question is silently ignored (a missing suggestion is not an error worth a toast).
 */
internal fun renderPassengerHint(
    host: SimplePanel,
    draft: TravelExpenseReportDto,
    passengersField: LapisField,
) {
    val session = AppState.session ?: return
    if (session.memberId != draft.subjectMemberId || session.status != network.lapis.cloud.shared.domain.MemberStatus.ACTIVE) return
    val hintHost = host.vPanel(spacing = 2)
    AppScope.launch {
        val hint =
            runCatching { rpcService<network.lapis.cloud.shared.rpc.ICarpoolService>().getPassengerHint(draft.travelFrom, draft.travelTo) }
                .getOrNull() ?: return@launch
        if (hint.confirmedPassengers <= 0) return@launch
        val text =
            if (hint.multipleTrips) {
                gettext("Laut Mitfahrzentrale %1 bestätigte Mitfahrer (mehrere Fahrten in diesem Zeitraum).", hint.confirmedPassengers)
            } else {
                gettext("Laut Mitfahrzentrale %1 bestätigte Mitfahrer.", hint.confirmedPassengers)
            }
        val row = hintHost.hPanel(spacing = 8) { addCssClass("align-items-center") }
        row.div(text) { addCssClasses("text-muted small") }
        val adopt = row.actionButton(ActionIcon.APPROVE, tr("Übernehmen"), style = ButtonStyle.OUTLINESECONDARY, small = true)
        adopt.onClick { passengersField.setValue(hint.confirmedPassengers.toString()) }
    }
}
