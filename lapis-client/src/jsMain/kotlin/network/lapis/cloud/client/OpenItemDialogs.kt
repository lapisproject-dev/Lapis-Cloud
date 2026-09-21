package network.lapis.cloud.client

import io.kvision.form.select.Select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.modal.ModalSize
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.NettingPreviewDto
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemSettlementDto
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.rpc.IOpenItemService

// Welle V1.4.21 -- die drei Modal-Dialoge des Offene-Posten-Screens (Ausgleichen, Beleg/Notiz
// bearbeiten, Verrechnen), ausgelagert aus `OpenItemsScreen.kt`, damit die Bildschirm-Datei bei der
// Liste/Detail-Grammatik bleibt. Freitext nie als HTML (S9); jede Schreibaktion mit
// try/finally-Knopfsperre (S4, runGuardedAction).

// ================================================================================================
// Ausgleichen (Zahlung)
// ================================================================================================

/**
 * [accounts] ist bewusst ein Lambda, keine Momentaufnahme (N12): der Zeilen-Aufrufer rendert die
 * Zeile, bevor `listLedgerAccounts` zurück ist -- eine zum Render-Zeitpunkt kopierte Liste war im
 * Dialog dann leer. [paymentMapping] ist aus demselben Grund ein Lambda (`getOrganizationSettings`
 * läuft parallel) und darf `null` sein, solange die Zuordnung nicht bekannt ist -- siehe
 * [settlementBankChoice].
 *
 * [knownSettlementIds] sind die Ausgleich-Ids, die dem Aufrufer VOR dieser Aktion schon bekannt
 * waren; daran erkennt [newSettlementPostingError] den wirklich neu angelegten Ausgleich (N3).
 * `null` heißt "Aufrufer kennt sie nicht" (die Liste hat nur den [OpenItemDto], nicht seine
 * Ausgleiche) -- dann greift der dokumentierte Notnagel in [newSettlementPostingError].
 *
 * **Welle V1.4.22** (zwei Bedienfehler, live auf Staging gefunden): das Konten-Select bietet nur noch
 * zahlungsfähige Konten an (Bank/Kasse, [SettlementBankChoice]) statt aller ASSET-Konten, die
 * Standard-Option verschwindet, wenn gar kein Standard-Bankkonto hinterlegt ist, und der Ausgleich
 * läuft über [openItemGuarded], damit der Server-Grund nicht mehr im generischen Konflikt-Toast
 * verschwindet.
 */
internal fun openItemSettlementDialog(
    item: OpenItemDto,
    accounts: () -> List<LedgerAccountDto>,
    paymentMapping: () -> PaymentAccountMapping?,
    knownSettlementIds: Set<String>?,
    onDone: () -> Unit,
) {
    val modal = Modal(caption = gettext("Posten ausgleichen: %1", item.counterpartyName))
    modal.div(gettext("Offen: %1", formatMoney(item.openAmount))) { addCssClasses("fw-bold mb-2") }
    // W4c: der Ausgleich ist ein [LapisForm] (die Knöpfe stehen in der Modal-Fußleiste, deshalb `finish()`). Die Fehler stehen am
    // Feld; die Regeln sind dieselben wie vorher (Betrag > 0 und <= offener Betrag, echtes Datum, Zahlungskonto).
    val form = modal.lapisForm()
    val amountField =
        form.textField(
            label = tr("Betrag in EUR"),
            value = displayDigits(item.openAmount),
            required = true,
            requiredMessage = tr("Bitte einen Betrag angeben."),
            rule = { text ->
                when (val parsed = parseAmountInput(text)) {
                    is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
                    is AmountInput.Valid ->
                        if (exceedsDisplayedAmount(entered = parsed.value, limit = item.openAmount)) {
                            FieldCheck.Invalid(
                                gettext("Der Betrag darf den offenen Betrag (%1) nicht übersteigen.", formatMoney(item.openAmount)),
                            )
                        } else {
                            FieldCheck.Ok
                        }
                    is AmountInput.Empty -> FieldCheck.Ok
                }
            },
        )
    val dateField =
        form.textField(
            label = tr("Zahlungsdatum"),
            value = todayLocalDate().toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            rule = { FormRules.isoDate(it) },
        )
    val bankChoice = settlementBankChoice(accounts = accounts(), mapping = paymentMapping())
    val bankOptions =
        listOf(
            "" to if (bankChoice.selectionRequired) tr("(bitte wählen)") else tr("(Standard-Bankkonto der Organisation)"),
        ) + bankChoice.eligible.map { it.id to settlementBankOptionLabel(account = it, choice = bankChoice) }
    // V1.4.22: die Kontowahl ist Pflicht, sobald es kein Standard-Bankkonto gibt -- vorher war "(Standard-Bankkonto der Organisation)"
    // auch dann vorausgewählt, und der Server lehnte den Ausgleich mit einem Grund ab, den der generische Konflikt-Toast nicht nannte.
    val bankField =
        form.selectField(
            label = tr("Zahlungskonto (Bank oder Kasse)"),
            options = bankOptions,
            value = "",
            required = bankChoice.selectionRequired,
            requiredMessage = settlementBankAccountProblem(selected = "", choice = bankChoice),
            hint = if (bankChoice.selectionRequired) missingDefaultBankAccountHint(bankChoice) else null,
        )
    if (!bankChoice.contextKnown) {
        // Audit-Nachtrag (MAJOR-2): ohne Kontenliste UND Zuordnung wird keine halb gefilterte Liste
        // angeboten -- ohne die Zuordnung ist das Forderungskonto von einem Bankkonto nicht zu
        // unterscheiden (beide Kontenklasse 1). Das Select bleibt gesperrt, der Ausgleich läuft über
        // das Standardkonto der Organisation (der Server entscheidet), und der Hinweis sagt, wie man
        // zur Auswahl kommt.
        (bankField.control as Select).disabled = true
        form.panel.div(paymentAccountsUnknownHint()) { addCssClasses("text-warning small") }
    }
    form.finish()
    // N5/N6: Bestätigung INLINE im selben Modal -- genau die Grammatik, die der Verrechnen-Dialog
    // weiter unten schon hat (Forstall-Ruling: kein zweites Modal). Vorher wurde dieses Modal VOR
    // einem `confirmDialog` versteckt: wer dort "Abbrechen" wählte, hatte Betrag, Datum und Bankkonto
    // verloren -- und die Bestätigung lief ohne Knopfsperre (`runGuardedAction(null)`), also war ein
    // doppelter Klick ein doppelter Ausgleich.
    val confirmBox = modal.div { addCssClasses("border border-danger rounded p-2 mt-2") }.apply { hide() }

    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val settleButton = Button(tr("Ausgleichen …"), style = ButtonStyle.PRIMARY)
    // Audit-Fund (zweiter Durchgang): `settleButton` war NIE gesperrt. Ein Klick während der
    // laufenden `settleOpenItem`-Anfrage machte `confirmBox.removeAll()` und erzeugte einen frischen,
    // entsperrten "Jetzt ausgleichen"-Knopf -- ein zweiter echter Teil-Ausgleich. Der Riegel sperrt
    // den äußeren Knopf, solange eine Bestätigung sichtbar ist oder die Anfrage läuft; "Zurück" und
    // jede Eingabeänderung geben ihn wieder frei.
    val gate = InlineConfirmGate()

    fun syncSettleButton() {
        settleButton.disabled = gate.blocked
    }

    /** Eingabe geändert ⇒ die sichtbare Bestätigung beschreibt sie nicht mehr (Betrag/Datum/Bankkonto). */
    fun invalidateConfirmation() {
        if (!gate.confirming) return
        if (!gate.cancelConfirmation()) return // Anfrage läuft -- Kasten bleibt stehen
        confirmBox.hide()
        syncSettleButton()
    }
    amountField.subscribe { invalidateConfirmation() }
    dateField.subscribe { invalidateConfirmation() }
    bankField.subscribe { invalidateConfirmation() }

    settleButton.onClick {
        // Erst der Riegel, dann die Vorprüfung: ein abgelehnter Klick darf keine Bestätigung öffnen,
        // eine gescheiterte Vorprüfung darf den Riegel nicht zuziehen (sonst wäre der Knopf nach
        // einem Eingabefehler gesperrt, ohne dass je eine Bestätigung sichtbar war).
        if (gate.blocked) return@onClick
        // Die Feldregeln prüfen Betrag, Datum und Zahlungskonto; die Fehler stehen an den Feldern.
        if (!form.validateAndReport()) return@onClick
        val amount = parseAmountInput(amountField.value) as? AmountInput.Valid ?: return@onClick
        val settledOn = runCatching { LocalDate.parse(dateField.value.trim()) }.getOrNull() ?: return@onClick
        val bankAccountId = bankField.value.takeIf { it.isNotBlank() }
        if (!gate.openConfirmation()) return@onClick
        syncSettleButton()
        // Norman-Regel: der Server verlangt keinen Grund, aber es bewegt sich Geld -> Bestätigung.
        confirmBox.removeAll()
        confirmBox.div(tr("Ausgleich bestätigen")) { addCssClasses("fw-bold") }
        confirmBox.div(
            gettext(
                "%1 für \"%2\" als bezahlt buchen (Zahlungsdatum %3)?",
                formatMoney(amount.value),
                item.counterpartyName,
                settledOn,
            ),
        ) { addCssClasses("mb-2") }
        // Abbrechen links, bestätigende Aktion rechts (Richtlinie 2.5 / R27): "Zurück" steht VOR "Jetzt ausgleichen".
        val confirmRow = confirmBox.hPanel(spacing = 8)
        confirmRow.button(tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            syncSettleButton()
        }
        val finalButton = confirmRow.button(tr("Jetzt ausgleichen"), style = ButtonStyle.DANGER)
        finalButton.onClick {
            // S4/N5: Knopfsperre mit try/finally um den Aufruf -- ein zweiter Klick auf "Jetzt
            // ausgleichen" darf nicht zu einem zweiten Ausgleich führen. Der Riegel deckt zusätzlich
            // den Weg über den äußeren Knopf ab (siehe [InlineConfirmGate]).
            if (!gate.beginRequest()) return@onClick
            syncSettleButton()
            runGuardedAction(finalButton) {
                var succeeded = false
                try {
                    // V1.4.22: `openItemGuarded`, nicht `guarded` -- die drei handlungsrelevanten
                    // Konflikte dieses Aufrufs bekommen einen eigenen Text (siehe dessen KDoc).
                    val result =
                        openItemGuarded { rpcService<IOpenItemService>().settleOpenItem(item.id, amount.value, settledOn, bankAccountId) }
                    if (result != null) {
                        succeeded = true
                        modal.hide()
                        notifySettlementOutcome(result, knownSettlementIds)
                        onDone()
                    }
                } finally {
                    gate.endRequest(succeeded)
                    syncSettleButton()
                }
            }
        }
        confirmBox.show()
    }
    modal.addButton(cancelButton)
    modal.addButton(settleButton)
    modal.show()
}

/**
 * Buchungsfehler des soeben erzeugten Ausgleichs. Ein älterer Ausgleich mit Fehlercode (z. B.
 * Kassenbestand zu niedrig) darf den Toast für einen fehlerfrei gebuchten neuen Ausgleich nicht
 * kapern -- und umgekehrt.
 *
 * **Identifiziert über die Id, nicht über `createdAt`** (Audit-Fund N3): [knownIds] sind die
 * Ausgleiche, die der Aufrufer vor der Aktion schon kannte, also ist der neue genau der, dessen Id
 * dort fehlt. `createdAt` allein reicht nicht: `settleOpenItem` und ein unmittelbar vorheriger
 * Ausgleich können denselben Zeitstempel tragen (`DbClock.nowLocalDateTime()` hat
 * Sekunden-/Millisekunden-Auflösung), und die Server-Sortierung bricht Gleichstand über
 * `OpenItemSettlementTable.id ASC` -- eine ZUFÄLLIGE UUID, die genauso gut vor dem neuen Ausgleich
 * liegen kann.
 *
 * [knownIds] `null` heißt "Aufrufer kennt die Vorher-Ids nicht" (die Liste hält nur den
 * [OpenItemDto]): dann bleibt es beim alten Notnagel "letzter in Serverreihenfolge unter den
 * jüngsten" -- unverändert unscharf bei identischem `createdAt`, aber nie schlechter als vorher.
 */
internal fun newSettlementPostingError(
    settlements: List<OpenItemSettlementDto>,
    knownIds: Set<String>?,
): String? {
    if (knownIds != null) {
        val created = settlements.filter { it.id !in knownIds }
        // Genau einer ist zu erwarten; bei mehreren (paralleler zweiter Aktor) gewinnt der letzte in
        // Serverreihenfolge -- immer noch nachweislich NEU, nur nicht zwingend der eigene.
        return created.lastOrNull()?.postingError
    }
    return settlements.sortedBy { it.createdAt }.lastOrNull()?.postingError
}

/** Ein Ausgleich kann vermerkt, aber nicht gebucht sein -- dann ist das ein Buchungsproblem, kein Erfolg. */
private fun notifySettlementOutcome(
    result: OpenItemDetailDto,
    knownSettlementIds: Set<String>?,
) {
    val code = newSettlementPostingError(result.settlements, knownSettlementIds)
    if (code == null) {
        notifySuccess(tr("Ausgleich gebucht."))
    } else {
        notifyError(gettext("Ausgleich vermerkt, aber nicht gebucht: %1", openItemPostingErrorMessage(code) ?: code))
    }
}

// ================================================================================================
// Beleg/Notiz bearbeiten
// ================================================================================================

internal fun openItemMetadataDialog(
    item: OpenItemDto,
    onChanged: (OpenItemDetailDto) -> Unit,
) {
    val modal = Modal(caption = gettext("Beleg/Notiz bearbeiten: %1", item.counterpartyName))
    val form = modal.lapisForm()
    val referenceField =
        form.textField(
            label = tr("Belegnummer"),
            value = item.reference,
            rule = {
                if (it.trim().length > MAX_OPEN_ITEM_REFERENCE_LENGTH) {
                    FieldCheck.Invalid(gettext("Die Belegnummer darf höchstens %1 Zeichen lang sein.", MAX_OPEN_ITEM_REFERENCE_LENGTH))
                } else {
                    FieldCheck.Ok
                }
            },
        )
    val noteField =
        form.textField(
            label = tr("Notiz"),
            value = item.note,
            rule = {
                if (it.trim().length > MAX_OPEN_ITEM_NOTE_LENGTH) {
                    FieldCheck.Invalid(gettext("Die Notiz darf höchstens %1 Zeichen lang sein.", MAX_OPEN_ITEM_NOTE_LENGTH))
                } else {
                    FieldCheck.Ok
                }
            },
        )
    form.finish()
    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        form.submit(saveButton) {
            val reference = referenceField.value.trim().takeIf { it.isNotEmpty() }
            val note = noteField.value.trim().takeIf { it.isNotEmpty() }
            val result = guarded { rpcService<IOpenItemService>().updateOpenItemMetadata(item.id, reference, note) }
            if (result != null) {
                modal.hide()
                notifySuccess(tr("Gespeichert."))
                onChanged(result)
            }
        }
    }
    modal.addButton(cancelButton)
    modal.addButton(saveButton)
    modal.show()
}

// ================================================================================================
// Verrechnen (Kreditor gegen Debitor)
// ================================================================================================

/**
 * Ein Dialog, der Kandidatenwahl, Betrag, **verpflichtende Vorschau** (Norman) und Ausführen
 * zusammenführt -- kein zweites Modal (Forstall): die Bestätigung erscheint inline im selben Dialog.
 *
 * Atkinson-Ruling: "Verrechnen" ist genau dann aktiv, wenn die ANGEZEIGTE Vorschau zu genau diesem
 * Tripel gehört ([canExecuteNetting]). Jede Änderung von Kandidat oder Betrag entwertet die Vorschau
 * sofort und sperrt den Knopf, bis eine neue eingetroffen ist. Die Vorschau ist entprellt
 * ([NETTING_PREVIEW_DEBOUNCE_MS]) -- kein RPC pro Tastendruck. `previewNetting` ist TREASURER/ADMIN
 * (K3); der Dialog wird deshalb nur für Schreibrollen angeboten.
 */
internal fun openItemNettingDialog(onDone: () -> Unit) {
    val modal = Modal(caption = tr("Kreditor und Debitor verrechnen"), size = ModalSize.LARGE)
    val body = modal.vPanel(spacing = 8)
    body.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    modal.addButton(Button(tr("Schließen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()

    AppScope.launch {
        val candidates = guarded { rpcService<IOpenItemService>().listNettingCandidates(limit = 200) }
        body.removeAll()
        if (candidates == null) {
            modal.hide()
            return@launch
        }
        if (candidates.isEmpty()) {
            body.div(
                tr("Keine verrechenbaren Gegenparteien: Es braucht offene Posten als Kreditor UND als Debitor derselben Gegenpartei."),
            ) {
                addCssClasses("text-muted")
            }
            return@launch
        }
        renderNettingBody(body, candidates) {
            modal.hide()
            onDone()
        }
    }
}

private fun renderNettingBody(
    body: io.kvision.panel.SimplePanel,
    candidates: List<NettingCandidateDto>,
    onExecuted: () -> Unit,
) {
    // W4c: Kandidat und Betrag sind ein [LapisForm]; die Vorschau, die Inline-Bestätigung und die Knopflogik ([canExecuteNetting],
    // [InlineConfirmGate]) sind unverändert. Die Feldfehler stehen am Feld; der graue "Verrechnen"-Knopf ohne Vorschau wird
    // zusätzlich durch den Satz in der Vorschau erklärt.
    val form = body.lapisForm()
    val candidateField =
        form.selectField(
            label = tr("Gegenpartei"),
            options =
                candidates.mapIndexed { index, candidate ->
                    index.toString() to
                        gettext(
                            "%1 · Kreditor offen %2 · Debitor offen %3",
                            candidate.counterpartyDisplayName,
                            formatMoney(candidate.payable.openAmount),
                            formatMoney(candidate.receivable.openAmount),
                        )
                },
            value = "0",
            // Ein Auswahlfeld ohne leere Option ist nie leer: `required` wäre hier nur Schein -- und kippte die Formularkennzeichnung auf
            // "zwei Felder, beide Pflicht: weder Stern noch Legende", sodass das einzige Feld, das der Nutzer wirklich ausfüllen muss (der
            // Betrag), seinen Stern verlöre.
            required = false,
        )
    val matchHint = form.panel.div().apply { addCssClasses("text-warning small") }

    fun candidateOf(index: String): NettingCandidateDto? = index.toIntOrNull()?.let { candidates.getOrNull(it) }
    val amountField =
        form.textField(
            label = tr("Betrag in EUR"),
            value = displayDigits(candidates.first().maxNettableAmount),
            required = true,
            requiredMessage = tr("Bitte einen Betrag angeben."),
            rule = { text ->
                when (val parsed = parseAmountInput(text)) {
                    is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
                    is AmountInput.Valid -> {
                        val max = candidateOf(candidateField.value)?.maxNettableAmount
                        if (max != null && exceedsDisplayedAmount(entered = parsed.value, limit = max)) {
                            FieldCheck.Invalid(
                                gettext("Der Betrag darf den verrechenbaren Betrag (%1) nicht übersteigen.", formatMoney(max)),
                            )
                        } else {
                            FieldCheck.Ok
                        }
                    }
                    is AmountInput.Empty -> FieldCheck.Ok
                }
            },
        )
    val maxHint = form.panel.div().apply { addCssClasses("text-muted small") }
    val previewHost = form.panel.vPanel(spacing = 4)
    val confirmBox = form.panel.div { addCssClasses("border border-danger rounded p-2") }.apply { hide() }
    val executeButton = Button(tr("Verrechnen …"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = executeButton)
    executeButton.disabled = true

    var lastPreviewed: NettingPreviewToken? = null
    var lastPreview: NettingPreviewDto? = null
    var previewTimer: Int? = null
    // Dasselbe Doppelklick-Muster wie im Ausgleichen-Dialog oben: `executeButton` war nur über
    // `canExecuteNetting` gesperrt, nicht über den Zustand der Bestätigung -- ein Klick während der
    // laufenden `executeNetting`-Anfrage hätte über `confirmBox.removeAll()` einen frischen,
    // entsperrten "Endgültig verrechnen"-Knopf erzeugt.
    val gate = InlineConfirmGate()

    fun selectedCandidate(): NettingCandidateDto? = candidateOf(candidateField.value)

    /** `null`, solange Kandidat oder Betrag ungültig sind (Betrag > 0, <= 2 Nachkommastellen, <= max. verrechenbar). */
    fun currentToken(): NettingPreviewToken? {
        val candidate = selectedCandidate() ?: return null
        val amount = parseAmountInput(amountField.value) as? AmountInput.Valid ?: return null
        if (exceedsDisplayedAmount(entered = amount.value, limit = candidate.maxNettableAmount)) return null
        return nettingPreviewToken(candidate, amount.value)
    }

    fun updateExecuteState() {
        executeButton.disabled = gate.blocked || !canExecuteNetting(currentToken(), lastPreviewed)
    }

    fun renderPreview(preview: NettingPreviewDto) {
        previewHost.removeAll()
        previewHost.div(tr("Vorschau der Buchung")) { addCssClasses("fw-bold") }
        previewHost.div(gettext("Soll: %1 · %2", preview.debitAccountNumber, preview.debitAccountName))
        previewHost.div(gettext("Haben: %1 · %2", preview.creditAccountNumber, preview.creditAccountName))
        previewHost.hPanel(spacing = 6) {
            div(tr("Betrag:")) { addCssClasses("text-muted small") }
            moneySpan(preview.amount)
        }
        previewHost.div(
            gettext(
                "Danach: Kreditor offen %1 (%2) · Debitor offen %3 (%4)",
                formatMoney(preview.payableOpenAmountAfter),
                openItemStatusLabel(preview.payableStatusAfter),
                formatMoney(preview.receivableOpenAmountAfter),
                openItemStatusLabel(preview.receivableStatusAfter),
            ),
        ) { addCssClasses("text-muted small") }
    }

    fun schedulePreview() {
        previewTimer?.let { window.clearTimeout(it) }
        lastPreviewed = null
        lastPreview = null
        // Solange die Buchung läuft, bleibt der Kasten stehen (`cancelConfirmation` = false) -- er
        // darf nicht unter der laufenden Anfrage weggezogen werden.
        if (gate.cancelConfirmation()) confirmBox.hide()
        updateExecuteState()
        val candidate = selectedCandidate()
        matchHint.content =
            if (candidate?.matchedByNameOnly == true) {
                tr(
                    "Nur über den Namen zugeordnet, nicht über einen gemeinsamen CRM-Kontakt -- bitte prüfen, ob dieselbe Gegenpartei gemeint ist.",
                )
            } else {
                ""
            }
        maxHint.content = candidate?.let { gettext("Höchstens verrechenbar: %1", formatMoney(it.maxNettableAmount)) } ?: ""
        val token = currentToken()
        previewHost.removeAll()
        if (token == null) {
            previewHost.div(tr("Bitte einen gültigen Betrag (größer 0, höchstens der verrechenbare Betrag) eingeben.")) {
                addCssClasses("text-muted small")
            }
            return
        }
        previewHost.div(tr("Vorschau wird geladen …")) { addCssClasses("text-muted small") }
        previewTimer =
            window.setTimeout({
                AppScope.launch {
                    val preview =
                        guarded {
                            rpcService<IOpenItemService>().previewNetting(token.payableItemId, token.receivableItemId, token.amount)
                        }?.firstOrNull()
                    // Nur übernehmen, wenn Kandidat/Betrag seither unverändert sind -- sonst wäre die
                    // angezeigte Vorschau die eines anderen Tripels.
                    val stillCurrent = currentToken()
                    if (preview == null || stillCurrent == null || !canExecuteNetting(stillCurrent, token)) return@launch
                    lastPreviewed = token
                    lastPreview = preview
                    renderPreview(preview)
                    updateExecuteState()
                }
            }, NETTING_PREVIEW_DEBOUNCE_MS)
    }

    candidateField.subscribe {
        selectedCandidate()?.let { amountField.setValue(displayDigits(it.maxNettableAmount)) }
        // Ein gesetzter Wert räumt einen stehenden Fehler nicht von selbst (siehe `LapisField.setValue`).
        amountField.validate(force = false)
        schedulePreview()
    }
    amountField.subscribe { schedulePreview() }

    executeButton.onClick {
        if (gate.blocked) return@onClick
        if (!canExecuteNetting(currentToken(), lastPreviewed)) return@onClick
        val preview = lastPreview ?: return@onClick
        if (!gate.openConfirmation()) return@onClick
        updateExecuteState()
        // Forstall: Bestätigung inline im selben Dialog, kein zweites Modal.
        confirmBox.removeAll()
        confirmBox.div(
            gettext(
                "Wirklich %1 verrechnen? Es wird sofort gebucht (Soll %2, Haben %3). Ein Storno ist möglich, aber nur einmal.",
                formatMoney(preview.amount),
                preview.debitAccountName,
                preview.creditAccountName,
            ),
        ) { addCssClasses("fw-bold mb-2") }
        // Abbrechen links, bestätigende Aktion rechts (Richtlinie 2.5 / R27): "Zurück" steht VOR "Endgültig verrechnen".
        val confirmRow = confirmBox.hPanel(spacing = 8)
        confirmRow.button(tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            updateExecuteState()
        }
        val finalButton = confirmRow.button(tr("Endgültig verrechnen"), style = ButtonStyle.DANGER)
        finalButton.onClick {
            val token = currentToken()
            // Zweite Absicherung: der Server hat die Vorschau nicht gemerkt, der Client prüft das Tripel erneut.
            if (token == null || !canExecuteNetting(token, lastPreviewed)) {
                if (gate.cancelConfirmation()) confirmBox.hide()
                updateExecuteState()
                return@onClick
            }
            if (!gate.beginRequest()) return@onClick
            updateExecuteState()
            runGuardedAction(finalButton) {
                var succeeded = false
                try {
                    val result =
                        guarded {
                            rpcService<IOpenItemService>().executeNetting(token.payableItemId, token.receivableItemId, token.amount)
                        }
                    if (result != null) {
                        // N15: KEIN "vermerkt, aber nicht gebucht"-Zweig. Anders als beim Anlegen und beim
                        // Ausgleichen gibt es für die Verrechnung keinen Nachbuchen-Pfad:
                        // `OpenItemService.executeNetting` wirft bei einer gescheiterten Buchung
                        // `ConflictException` und rollt den ganzen Versuch zurück (bewusst, siehe dessen
                        // Kommentar) -- ein `OpenItemNettingDto` mit `postingError` erreicht den Client
                        // also nie. Der Fehlerfall kommt hier als `result == null` an (Toast aus `guarded`).
                        succeeded = true
                        notifySuccess(tr("Verrechnung gebucht."))
                        onExecuted()
                    }
                } finally {
                    gate.endRequest(succeeded)
                    updateExecuteState()
                }
            }
        }
        confirmBox.show()
    }

    schedulePreview()
}
