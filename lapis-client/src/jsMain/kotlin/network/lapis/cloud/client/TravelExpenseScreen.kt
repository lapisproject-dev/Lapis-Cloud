package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.radioGroup
import io.kvision.form.upload.upload
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
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
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
    val header = root.pageHeader(tr("Reisekosten"))
    // The create button gets a slot of its own: it appears only after a successful load without an open draft.
    val draftButtonSlot = header.actionSlot.div().apply { hide() }

    // V1.9.122: the contact person comes first -- a member with a question finds the answer before the form.
    val contactPanel = root.vPanel(spacing = 4)
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
                formatDate(TravelExpenseDeadline.deadlineFor(draft.travelTo)),
            ),
            className = "alert alert-warning mb-0",
        )
    } else {
        card.div(
            gettext("Einreichen bis %1", formatDate(TravelExpenseDeadline.deadlineFor(draft.travelTo))),
            className = "text-muted small",
        )
    }
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

    // Two columns on a desktop (the totals bar sticks on the right), one column on a phone (the bar sticks to the bottom edge).
    val layout = card.div(className = "lapis-te-editor")
    val main = layout.vPanel(spacing = 8)
    val bar = TravelExpenseTotalsBar(layout)
    var settlementAdvanceCents = draft.advanceCents
    var dayHandle: TravelExpenseDayGrid? = null

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
    }

    // Weitere Kosten
    main.h2(tr("Weitere Kosten")) { addCssClass("h6") }
    val addButtonsRow = main.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val addLinePanel = main.vPanel(spacing = 6)
    TRAVEL_EXPENSE_ADDABLE_KINDS.forEach { kind ->
        val enabled = travelExpenseLineKindEnabled(kind, rates)
        val addButton =
            addButtonsRow.button(
                gettext("%1 hinzufügen", resolvedAttributeText(travelExpenseLineKindLabel(kind))),
                style = ButtonStyle.OUTLINESECONDARY,
            ) {
                icon = travelExpenseLineKindIcon(kind)
                disabled = !enabled
                if (!enabled && kind == TravelExpenseLineKind.MILEAGE) title = tr("Kilometersatz ist noch nicht konfiguriert.")
            }
        addButton.onClick { renderLineForm(addLinePanel, draft, kind, line = null, rates = rates, onChanged = onChanged) }
    }
    val otherLines = draft.lines.filter { it.kind != TravelExpenseLineKind.DAY_ALLOWANCE }
    if (otherLines.isEmpty()) {
        main.p(tr("Noch keine weiteren Kosten erfasst.")) { addCssClasses("text-muted small") }
    }
    otherLines.forEach { line -> renderLineCard(main, draft, line, rates, onChanged) }

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
        main.p(blockReason) { addCssClasses("text-muted small") }
    }
    val submitButton =
        main.actionButton(ActionIcon.SEND, tr("Zur Freigabe einreichen"), style = ButtonStyle.SUCCESS) {
            disabled =
                blockReason != null
        }
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

// R24 (W4d): migrated to the form grammar. V1.9.122: one form for adding AND editing a "weitere Kosten" line -- Beschreibung plus, depending on
// [kind], Kilometer + Mitfahrer (mileage), Betrag (public transport / hospitality / other) or Erste Nacht + Nächte + Betrag + "mit Frühstück"
// (accommodation). Money travels as whole cents; the server computes everything else.
internal fun renderLineForm(
    panel: SimplePanel,
    draft: TravelExpenseReportDto,
    kind: TravelExpenseLineKind,
    line: TravelExpenseLineDto?,
    rates: TravelExpenseRatesDto,
    onChanged: () -> Unit,
) {
    panel.removeAll()
    val wrapper = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
    val form = wrapper.lapisForm()
    val descriptionField =
        form.textField(
            label = if (kind == TravelExpenseLineKind.ACCOMMODATION) tr("Unterkunft (Hotel, Ort)") else tr("Beschreibung"),
            value = line?.description,
            required = true,
            requiredMessage = tr("Bitte eine Beschreibung angeben."),
            rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH) },
        )
    val kilometersField =
        if (kind == TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Kilometer"),
                value =
                    line
                        ?.kilometers
                        ?.toDouble()
                        ?.toLong()
                        ?.toString(),
                required = true,
                hint = tr("Ganze Kilometer, 1 bis 5000."),
                rule = { FormRules.intInRange(it, 1, TravelExpenseAmountRules.MAX_KM_PER_LINE) },
            )
        } else {
            null
        }
    val passengersField =
        if (kind == TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Mitfahrer"),
                value = (line?.passengerCount ?: 0).toString(),
                hint = tr("Anzahl der Mitfahrenden, 0 bis 8."),
                rule = { FormRules.intInRange(it, 0, TravelExpenseAmountRules.MAX_PASSENGERS) },
            )
        } else {
            null
        }
    // V1.9.125: the carpool centre may know how many people rode along -- offered as a SUGGESTION under the field, never set by itself.
    if (passengersField != null) renderPassengerHint(host = form.panel, draft = draft, passengersField = passengersField)
    val firstNightField =
        if (kind == TravelExpenseLineKind.ACCOMMODATION) {
            form.textField(
                label = tr("Erste Nacht (JJJJ-MM-TT)"),
                value = line?.dayDate?.toString() ?: draft.travelFrom.toString(),
                required = true,
                hint = tr("Beispiel: 2026-03-14."),
                rule = { travelExpenseFirstNightCheck(it, draft.travelFrom, draft.travelTo) },
            )
        } else {
            null
        }
    val nightsField =
        if (kind == TravelExpenseLineKind.ACCOMMODATION) {
            form.textField(
                label = tr("Nächte"),
                value = line?.nights?.toString() ?: "1",
                required = true,
                rule = { FormRules.intInRange(it, 1, TravelExpenseAmountRules.MAX_NIGHTS) },
            )
        } else {
            null
        }
    val amountField =
        if (kind != TravelExpenseLineKind.MILEAGE) {
            form.textField(
                label = tr("Betrag"),
                value = line?.amountCents?.let { travelExpenseCentsText(it) },
                required = true,
                hint = tr("Beispiel: 1234,56."),
                rule = { travelExpenseCentsAmountCheck(it) },
            )
        } else {
            null
        }
    val breakfastField =
        if (kind == TravelExpenseLineKind.ACCOMMODATION) {
            form.checkField(label = tr("mit Frühstück"), value = line?.breakfastIncluded == true)
        } else {
            null
        }
    if (kind == TravelExpenseLineKind.ACCOMMODATION && nightsField != null && firstNightField != null) {
        form.crossFieldRule(field = nightsField) {
            val first = runCatching { LocalDate.parse(firstNightField.value.trim()) }.getOrNull()
            val nights = nightsField.value.trim().toIntOrNull()
            if (first != null && nights != null && !travelExpenseAccommodationFits(first, nights, draft.travelFrom, draft.travelTo)) {
                FieldCheck.Invalid(gettext("Die Übernachtungen müssen innerhalb der Reise liegen."))
            } else {
                FieldCheck.Ok
            }
        }
    }
    val saveButton =
        newActionButton(
            if (line == null) ActionIcon.ADD else ActionIcon.SAVE,
            if (line == null) tr("Zeile hinzufügen") else tr("Zeile speichern"),
            ButtonStyle.PRIMARY,
        )
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val description = descriptionField.value.trim()
            val input =
                when (kind) {
                    TravelExpenseLineKind.MILEAGE ->
                        TravelExpenseLineInput(
                            kind = kind,
                            description = description,
                            kilometers =
                                checkNotNull(kilometersField)
                                    .value
                                    .trim()
                                    .toInt()
                                    .toDouble()
                                    .toDecimal(),
                            passengerCount =
                                checkNotNull(passengersField)
                                    .value
                                    .trim()
                                    .ifEmpty { "0" }
                                    .toInt(),
                        )
                    TravelExpenseLineKind.ACCOMMODATION ->
                        TravelExpenseLineInput(
                            kind = kind,
                            description = description,
                            dayDate = LocalDate.parse(checkNotNull(firstNightField).value.trim()),
                            nights = checkNotNull(nightsField).value.trim().toInt(),
                            breakfastIncluded = checkNotNull(breakfastField).value == "true",
                            amountCents = travelExpenseParseCents(checkNotNull(amountField).value),
                        )
                    else ->
                        TravelExpenseLineInput(
                            kind = kind,
                            description = description,
                            amountCents = travelExpenseParseCents(checkNotNull(amountField).value),
                        )
                }
            val result =
                guarded {
                    if (line == null) {
                        rpcService<ITravelExpenseService>().addLine(draft.id, input)
                    } else {
                        rpcService<ITravelExpenseService>().updateLine(line.id, input)
                    }
                }
            if (result != null) {
                notifySuccess(if (line == null) tr("Zeile hinzugefügt.") else tr("Zeile gespeichert."))
                onChanged()
            }
        }
    }
}

private fun renderLineCard(
    panel: SimplePanel,
    draft: TravelExpenseReportDto,
    line: TravelExpenseLineDto,
    rates: TravelExpenseRatesDto,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(travelExpenseLineKindLabel(line.kind), travelExpenseLineKindColor(line.kind))
    headerRow.untrustedDiv(line.description, className = "flex-grow-1")
    headerRow.div(formatMoney(line.amount)) { addCssClasses("fw-bold") }
    if (line.kind in TRAVEL_EXPENSE_ADDABLE_KINDS) {
        val editHolder = card.vPanel(spacing = 4)
        val editButton = headerRow.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
        editButton.onClick { renderLineForm(editHolder, draft, line.kind, line = line, rates = rates, onChanged = onChanged) }
    }
    val removeButton = headerRow.actionButton(ActionIcon.REMOVE, tr("Zeile entfernen"), style = ButtonStyle.OUTLINEDANGER)
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
                    "%1 km × %2/km, %3 Mitfahrer",
                    line.kilometers?.let { formatPlainAmountIn(I18n.language, it) } ?: "?",
                    line.rateSnapshot?.let { formatMoney(it) } ?: "?",
                    line.passengerCount ?: 0,
                ),
            ) {
                addCssClasses("text-muted small")
            }
        TravelExpenseLineKind.PER_DIEM ->
            card.div(gettext("%1 Tage × %2", line.days, line.rateSnapshot?.let { formatMoney(it) } ?: "?")) {
                addCssClasses("text-muted small")
            }
        TravelExpenseLineKind.ACCOMMODATION ->
            card.div(
                gettext(
                    "%1 Nächte ab %2%3",
                    line.nights ?: 0,
                    line.dayDate?.let { formatDate(it) } ?: "?",
                    if (line.breakfastIncluded == true) gettext(", mit Frühstück") else "",
                ),
            ) {
                addCssClasses("text-muted small")
            }
        else -> Unit
    }
    // Receipts: mandatory for the receipted kinds, an optional map proof for mileage. Day lines never carry one.
    if (line.kind.requiresReceipt || line.kind == TravelExpenseLineKind.MILEAGE) {
        line.receipts.forEach { receipt ->
            val receiptRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
            receiptRow.icon(receiptIcon(receipt.mimeType))
            receiptRow.untrustedLink(
                receipt.originalFilename,
                url = TravelExpenseHttp.receiptDownloadUrl(receipt.id),
                target = "_blank",
            )
            receiptRow.div(receiptSizeLabel(receipt.sizeBytes)) { addCssClasses("text-muted small") }
            val deleteReceiptButton = receiptRow.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
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
        val uploadLabel =
            if (line.kind ==
                TravelExpenseLineKind.MILEAGE
            ) {
                tr("Kartennachweis hochladen (optional)")
            } else {
                tr("Beleg hochladen")
            }
        val uploadRow = card.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
        val uploadForm = uploadRow.lapisForm()
        val fileUpload = uploadForm.panel.upload(label = uploadLabel)

        fun selectedNativeFile() = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }
        val fileField =
            uploadForm.register(
                fileUpload,
                label = uploadLabel,
                required = true,
                requiredMessage = tr("Bitte eine Datei auswählen."),
            )
        val uploadButton = newActionButton(ActionIcon.UPLOAD, tr("Hochladen"), ButtonStyle.PRIMARY)
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
    card.div(gettext("%1 bis %2", formatDate(report.travelFrom), formatDate(report.travelTo))) { addCssClasses("text-muted small") }
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
                    notifySuccess(tr("Als neuer Entwurf kopiert. Belege müssen erneut hochgeladen werden."))
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
        return tr("Jede Beleg-Kosten-Zeile benötigt mindestens einen Beleg.")
    }
    if (report.lines.any { it.kind == TravelExpenseLineKind.MILEAGE } && rates.rateSet?.mileageCentsPerKm == null) {
        return tr("Der Kilometersatz ist nicht konfiguriert.")
    }
    if (report.lines.any { it.kind == TravelExpenseLineKind.PER_DIEM } && rates.perDiemRate == null) {
        return tr("Die Tagespauschale ist nicht konfiguriert.")
    }
    return null
}

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
internal fun travelExpenseFirstNightCheck(
    value: String,
    travelFrom: LocalDate,
    travelTo: LocalDate,
): FieldCheck {
    val date = runCatching { LocalDate.parse(value.trim()) }.getOrNull() ?: return FormRules.isoDate(value)
    return if (date < travelFrom || date > travelTo) {
        FieldCheck.Invalid(gettext("Die Übernachtungen müssen innerhalb der Reise liegen."))
    } else {
        FieldCheck.Ok
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
private fun renderPassengerHint(
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
                gettext("Laut Mitfahrerzentrale %1 bestätigte Mitfahrer (mehrere Fahrten in diesem Zeitraum).", hint.confirmedPassengers)
            } else {
                gettext("Laut Mitfahrerzentrale %1 bestätigte Mitfahrer.", hint.confirmedPassengers)
            }
        val row = hintHost.hPanel(spacing = 8) { addCssClass("align-items-center") }
        row.div(text) { addCssClasses("text-muted small") }
        val adopt = row.actionButton(ActionIcon.APPROVE, tr("Übernehmen"), style = ButtonStyle.OUTLINESECONDARY, small = true)
        adopt.onClick { passengersField.setValue(hint.confirmedPassengers.toString()) }
    }
}
