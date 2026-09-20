package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.form.select.select
import io.kvision.form.text.text
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
    val amountInput = modal.text(value = item.openAmount.toString(), label = tr("Betrag in EUR"))
    val dateInput = modal.text(value = todayLocalDate().toString(), label = tr("Zahlungsdatum (JJJJ-MM-TT)"))
    val bankChoice = settlementBankChoice(accounts = accounts(), mapping = paymentMapping())
    val bankOptions =
        listOf(
            "" to if (bankChoice.selectionRequired) tr("(bitte wählen)") else tr("(Standard-Bankkonto der Organisation)"),
        ) + bankChoice.eligible.map { it.id to settlementBankOptionLabel(account = it, choice = bankChoice) }
    val bankSelect = modal.select(options = bankOptions, value = "", label = tr("Zahlungskonto (Bank oder Kasse)"))
    if (bankChoice.selectionRequired) {
        modal.div(missingDefaultBankAccountHint(bankChoice)) { addCssClasses("text-muted small") }
    }
    if (!bankChoice.contextKnown) {
        // Audit-Nachtrag (MAJOR-2): ohne Kontenliste UND Zuordnung wird keine halb gefilterte Liste
        // angeboten -- ohne die Zuordnung ist das Forderungskonto von einem Bankkonto nicht zu
        // unterscheiden (beide Kontenklasse 1). Das Select bleibt gesperrt, der Ausgleich läuft über
        // das Standardkonto der Organisation (der Server entscheidet), und der Hinweis sagt, wie man
        // zur Auswahl kommt.
        bankSelect.disabled = true
        modal.div(paymentAccountsUnknownHint()) { addCssClasses("text-warning small") }
    }
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
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
    amountInput.subscribe { invalidateConfirmation() }
    dateInput.subscribe { invalidateConfirmation() }
    bankSelect.subscribe { invalidateConfirmation() }

    settleButton.onClick {
        errorBox.hide()
        // Erst der Riegel, dann die Vorprüfung: ein abgelehnter Klick darf keine Bestätigung öffnen,
        // eine gescheiterte Vorprüfung darf den Riegel nicht zuziehen (sonst wäre der Knopf nach
        // einem Eingabefehler gesperrt, ohne dass je eine Bestätigung sichtbar war).
        if (gate.blocked) return@onClick
        val amount = parseAmountInput(amountInput.value)
        val settledOn = runCatching { LocalDate.parse(dateInput.value.orEmpty().trim()) }.getOrNull()
        // V1.4.22: die Kontowahl ist Pflicht, sobald es kein Standard-Bankkonto gibt -- vorher war
        // "(Standard-Bankkonto der Organisation)" auch dann vorausgewählt, und der Server lehnte den
        // Ausgleich mit einem Grund ab, den der generische Konflikt-Toast nicht nannte.
        val bankProblem = settlementBankAccountProblem(selected = bankSelect.value, choice = bankChoice)
        val problem =
            when {
                amount is AmountInput.Empty -> tr("Bitte einen Betrag angeben.")
                amount is AmountInput.Invalid -> amount.reason
                amount is AmountInput.Valid && amount.value.toDouble() > item.openAmount.toDouble() ->
                    gettext("Der Betrag darf den offenen Betrag (%1) nicht übersteigen.", formatMoney(item.openAmount))
                settledOn == null -> tr("Bitte ein gültiges Datum angeben.")
                bankProblem != null -> bankProblem
                else -> null
            }
        if (problem != null || amount !is AmountInput.Valid || settledOn == null) {
            errorBox.content = problem ?: tr("Bitte die Eingaben prüfen.")
            errorBox.show()
            return@onClick
        }
        val bankAccountId = bankSelect.value?.takeIf { it.isNotBlank() }
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
        val confirmRow = confirmBox.hPanel(spacing = 8)
        val finalButton = confirmRow.button(tr("Jetzt ausgleichen"), style = ButtonStyle.DANGER)
        confirmRow.button(tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            syncSettleButton()
        }
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
    val referenceInput = modal.text(value = item.reference, label = tr("Belegnummer"))
    val noteInput = modal.text(value = item.note, label = tr("Notiz"))
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val reference = referenceInput.value?.trim()?.takeIf { it.isNotEmpty() }
        val note = noteInput.value?.trim()?.takeIf { it.isNotEmpty() }
        val problem =
            when {
                (reference?.length ?: 0) > MAX_OPEN_ITEM_REFERENCE_LENGTH ->
                    gettext("Die Belegnummer darf höchstens %1 Zeichen lang sein.", MAX_OPEN_ITEM_REFERENCE_LENGTH)
                (note?.length ?: 0) > MAX_OPEN_ITEM_NOTE_LENGTH ->
                    gettext("Die Notiz darf höchstens %1 Zeichen lang sein.", MAX_OPEN_ITEM_NOTE_LENGTH)
                else -> null
            }
        if (problem != null) {
            errorBox.content = problem
            errorBox.show()
            return@onClick
        }
        runGuardedAction(saveButton) {
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
    val candidateSelect =
        body.select(
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
            label = tr("Gegenpartei"),
        )
    val matchHint = body.div().apply { addCssClasses("text-warning small") }
    val amountInput = body.text(value = candidates.first().maxNettableAmount.toString(), label = tr("Betrag in EUR"))
    val maxHint = body.div().apply { addCssClasses("text-muted small") }
    val previewHost = body.vPanel(spacing = 4)
    val confirmBox = body.div { addCssClasses("border border-danger rounded p-2") }.apply { hide() }
    val actionRow = body.hPanel(spacing = 8)
    val executeButton = actionRow.button(tr("Verrechnen …"), style = ButtonStyle.PRIMARY)
    executeButton.disabled = true

    var lastPreviewed: NettingPreviewToken? = null
    var lastPreview: NettingPreviewDto? = null
    var previewTimer: Int? = null
    // Dasselbe Doppelklick-Muster wie im Ausgleichen-Dialog oben: `executeButton` war nur über
    // `canExecuteNetting` gesperrt, nicht über den Zustand der Bestätigung -- ein Klick während der
    // laufenden `executeNetting`-Anfrage hätte über `confirmBox.removeAll()` einen frischen,
    // entsperrten "Endgültig verrechnen"-Knopf erzeugt.
    val gate = InlineConfirmGate()

    fun selectedCandidate(): NettingCandidateDto? = candidateSelect.value?.toIntOrNull()?.let { candidates.getOrNull(it) }

    /** `null`, solange Kandidat oder Betrag ungültig sind (Betrag > 0, <= 2 Nachkommastellen, <= max. verrechenbar). */
    fun currentToken(): NettingPreviewToken? {
        val candidate = selectedCandidate() ?: return null
        val amount = parseAmountInput(amountInput.value) as? AmountInput.Valid ?: return null
        if (amount.value.toDouble() > candidate.maxNettableAmount.toDouble()) return null
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

    candidateSelect.subscribe {
        selectedCandidate()?.let { amountInput.value = it.maxNettableAmount.toString() }
        schedulePreview()
    }
    amountInput.subscribe { schedulePreview() }

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
        val confirmRow = confirmBox.hPanel(spacing = 8)
        val finalButton = confirmRow.button(tr("Endgültig verrechnen"), style = ButtonStyle.DANGER)
        confirmRow.button(tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            updateExecuteState()
        }
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
