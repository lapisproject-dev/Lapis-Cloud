package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Overflow
import io.kvision.form.text.text
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.icon
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.FinTsComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.FinTsSetupInput
import network.lapis.cloud.shared.domain.FinTsSetupResultDto
import network.lapis.cloud.shared.domain.FinTsStatus
import network.lapis.cloud.shared.rpc.IBankAccountService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- Wave 1 shipped [IBankAccountService]
 * backend-only (see that interface's own KDoc), so this screen is the FIRST bank-accounts UI in
 * this codebase: Wave 1 CRUD (create/edit/delete/set-default, gated in-screen via
 * [BankAccountAuthzUi.canWrite], structure mirrors [BankStatementImportScreen]'s own "one surface,
 * gate in-screen, never a second route" posture) plus the five Wave 2 FinTS controls, ADMIN-only
 * via [BankAccountAuthzUi.canManageFinTs].
 *
 * Design decisions carried from the vault plan (Design-Team review):
 *
 * - **D-REAUTH**: the FinTS status cell IS the action -- `REAUTH_REQUIRED` renders a warning
 *   triangle glyph (never color alone, WCAG 1.4.1) plus a "Neu anmelden" button in the SAME cell,
 *   not a separate column. A page-top warning band repeats the same information once, aggregated,
 *   so it's visible without reading every row.
 * - **D-PIN**: the PIN field is a real `password()` input, NEVER pre-filled, `autocomplete="new-password"`
 *   + `autocapitalize="off"` + `spellcheck="false"` on BOTH the PIN and the userid field -- a
 *   banking PIN must never be offered to a browser password manager. After a successful
 *   activation the field is visibly cleared, never re-displayed.
 * - **D-TAN**: the TAN step reuses the SAME modal (swaps an inner panel, never closes/reopens the
 *   modal itself), disables further credential edits, shows the bank's own prompt text and the
 *   handle's expiry time. "Abbrechen" calls [IBankAccountService.cancelFinTsSetup] server-side, not
 *   just a client-side close. No auto-submit.
 * - **D-DISCLAIMER**: the full disclaimer text in a scrollable box + an un-checked checkbox;
 *   "Aktivieren" stays disabled until it is checked (same [SepaSettingsScreen] precedent).
 * - **D-UNAVAILABLE**: `finTsAvailable == false` renders NO FinTS controls at all (never a greyed-
 *   out button) -- a plain explanatory line instead.
 */
fun renderBankAccountsScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    // Warnband über dem Titel (Richtlinie 2.8: es betrifft den ganzen Screen) -- unverändert gegenüber
    // V1.4.14, nur die Reihenfolge stimmt jetzt mit der Richtlinie überein.
    val warningBand = root.div().apply { hide() }
    root.h1(tr("Bankkonten"))

    val actionsRow = root.hPanel(spacing = 8)

    // Welle V1.4.26 (W2): der Abruf liegt in einem `dataSection` -- Lade-, Fehler- und Leerzustand statt
    // eines stumm leeren Bereichs, wenn `listBankAccounts` scheitert (`?: return@launch` vorher).
    lateinit var section: DataSection
    section =
        root.dataSection<List<BankAccountDto>>(
            emptyText = tr("Noch kein Bankkonto angelegt."),
            isEmpty = { it.isEmpty() },
            // Nur bei ERFOLG neu zeichnen: `orEmpty()` auf einem gescheiterten Abruf hätte das Warnband
            // versteckt und damit „keine erneute FinTS-Anmeldung nötig" behauptet, obwohl der Client
            // gerade nichts weiß (Audit dieser Welle).
            onSettled = { accounts -> if (accounts != null) renderWarningBand(warningBand, accounts) },
            load = { guarded { rpcService<IBankAccountService>().listBankAccounts() } },
            render = { panel, accounts -> renderAccountsTable(panel, accounts) { section.reload() } },
        )

    if (BankAccountAuthzUi.canWrite(AppState.session?.role)) {
        val createButton = actionsRow.button(tr("Bankkonto anlegen"), style = ButtonStyle.PRIMARY)
        createButton.onClick { bankAccountEditModal(null) { section.reload() } }
    }

    section.reload()
}

private fun renderWarningBand(
    warningBand: Div,
    accounts: List<BankAccountDto>,
) {
    warningBand.removeAll()
    val reauth = accounts.filter { it.finTsStatus == FinTsStatus.REAUTH_REQUIRED }
    if (reauth.isEmpty()) {
        warningBand.hide()
        return
    }
    warningBand.show()
    warningBand.addCssClasses("alert alert-warning d-flex align-items-center gap-2")
    warningBand.icon("fas fa-triangle-exclamation")
    warningBand.div(
        gettext(
            "%1 Bankkonto(-en) benötigen eine erneute FinTS-Anmeldung, bevor der Live-Abruf weiterläuft.",
            reauth.size,
        ),
    )
}

private fun renderAccountsTable(
    tableHost: SimplePanel,
    accounts: List<BankAccountDto>,
    onChanged: () -> Unit,
) {
    val currentRole = AppState.session?.role
    val canWrite = BankAccountAuthzUi.canWrite(currentRole)
    val canManageFinTs = BankAccountAuthzUi.canManageFinTs(currentRole)
    // Bewusst kein Suchfeld: eine Organisation führt eine Handvoll Bankkonten, nie die 20+ Zeilen, ab
    // denen die Richtlinie (R19) ein Suchfeld verlangt. Ebenso keine Sortierköpfe -- `listBankAccounts`
    // liefert eine kurze, serverseitig geordnete Liste.
    tableHost.dataTable(
        columns = bankAccountColumns(canManageFinTs = canManageFinTs, onChanged = onChanged),
        rows = accounts,
        actions =
            if (!canWrite) {
                null
            } else {
                { actions, account -> actions.renderBankAccountActions(account, onChanged) }
            },
    )
}

/** Spalten der Bankkonten-Tabelle / Kartenliste; die Bezeichnung ist die Identität der Zeile. */
private fun bankAccountColumns(
    canManageFinTs: Boolean,
    onChanged: () -> Unit,
): List<DataColumn<BankAccountDto>> =
    listOf(
        textColumn(title = tr("Bezeichnung"), primary = true) { account: BankAccountDto -> account.label },
        textColumn(title = tr("IBAN"), numeric = true) { account: BankAccountDto -> account.ibanMasked },
        textColumn(title = tr("Bank")) { account: BankAccountDto -> account.bankName ?: "—" },
        DataColumn(
            title = tr("Standard"),
            cell = { container, account ->
                // Welle V1.4.26 (W2): ein `typeBadge` mit dem Wort „Standard" statt eines nackten
                // Sternchen-Glyphs. Das Sternchen war der einzige Kanal -- ein Screenreader las an
                // dieser Stelle gar nichts, und in der Kartenliste stand ein `dd` mit einem Icon ohne
                // Bedeutung. „Standard" ist eine feste Kategorie, kein Lebenszyklus, also `typeBadge`
                // (Badge-Grammatik 2.3).
                if (account.isDefault) container.typeBadge(tr("Standard"), "primary")
            },
        ),
        DataColumn(
            title = tr("FinTS-Live-Abruf"),
            cell = { container, account -> renderFinTsCell(container, account, canManageFinTs, onChanged) },
        ),
    )

/**
 * Zeilenaktionen. Rollen-Gate ([BankAccountAuthzUi.canWrite], vom Aufrufer geprüft) und die
 * Standard-Konto-Bedingung (`!isDefault` für Standard-Setzen und Löschen) sind gegenüber V1.4.14
 * unverändert. Zwei Änderungen dieser Welle: Icon-Knöpfe statt drei Volltext-Knöpfe, die eine
 * Bankkonto-Zeile auf drei Zeilen Höhe trieben (R38) -- und, als notwendige Folge davon, eine
 * **Bestätigung vor dem Löschen**. Ein unbeschrifteter Mülleimer neben zwei weiteren Icons ist
 * erheblich leichter versehentlich zu treffen als ein Knopf mit dem Wort „Löschen"; die Löschung lief
 * bisher ohne jeden zweiten Schritt (Richtlinie P6/R30).
 */
private fun Container.renderBankAccountActions(
    account: BankAccountDto,
    onChanged: () -> Unit,
) {
    val group = tableActionGroup()
    val editButton = group.tableActionButton("fas fa-pen", tr("Bearbeiten"))
    editButton.onClick { bankAccountEditModal(account) { onChanged() } }
    if (account.isDefault) return
    val setDefaultButton = group.tableActionButton("fas fa-star", tr("Als Standard setzen"))
    setDefaultButton.onClick {
        runGuardedAction(setDefaultButton) {
            guarded { rpcService<IBankAccountService>().setDefaultBankAccount(account.id) } ?: return@runGuardedAction
            notifySuccess(tr("Standardkonto gesetzt."))
            onChanged()
        }
    }
    val deleteButton = group.tableActionButton("fas fa-trash", tr("Löschen"), ButtonStyle.OUTLINEDANGER)
    deleteButton.onClick {
        confirmDialog(
            title = tr("Bankkonto löschen"),
            message =
                gettext(
                    "Bankkonto %1 (%2) löschen? Bereits gebuchte Kontoauszüge bleiben erhalten.",
                    account.label,
                    account.ibanMasked,
                ),
            confirmLabel = tr("Löschen"),
            onConfirm = {
                runGuardedAction(deleteButton) {
                    guarded { rpcService<IBankAccountService>().deleteBankAccount(account.id) } ?: return@runGuardedAction
                    notifySuccess(tr("Bankkonto gelöscht."))
                    onChanged()
                }
            },
        )
    }
}

/** D-REAUTH: the status cell carries the action, D-UNAVAILABLE: no controls at all when `finTsAvailable == false`. */
private fun renderFinTsCell(
    panel: Container,
    account: BankAccountDto,
    canManageFinTs: Boolean,
    onChanged: () -> Unit,
) {
    if (!account.finTsAvailable) {
        panel.div(tr("Live-Abruf nicht verfügbar: LAPIS_SECRET_ENCRYPTION_KEY ist nicht konfiguriert.")) {
            addCssClasses("text-muted small")
        }
        return
    }
    when (account.finTsStatus) {
        FinTsStatus.NOT_CONFIGURED -> {
            panel.div(tr("Kein Live-Abruf")) { addCssClasses("text-muted small") }
            if (canManageFinTs) {
                val activateButton = panel.button(tr("Aktivieren …"), style = ButtonStyle.OUTLINEPRIMARY)
                activateButton.onClick { finTsSetupModal(account, onChanged) }
            }
        }
        FinTsStatus.ACTIVE -> {
            // Review fix (MEDIUM): an ACTIVE account with a persistent finTsLastErrorCode (poller
            // deliberately leaves STATEMENT_FORMAT_UNSUPPORTED/PROTOCOL_ERROR/BANK_UNAVAILABLE/
            // TIMEOUT on ACTIVE rather than flipping to REAUTH_REQUIRED -- see FinTsPoller.tick's own
            // `when (result.code)` branching) used to render an identical green checkmark to a
            // healthy account, with no mail ever sent for this case either. Surfacing the last error
            // code here -- the DTO field already existed, just unread by this screen -- is the
            // minimal fix; the ADMIN at least sees something is wrong instead of a permanently
            // reassuring "Live-Abruf aktiv" for an account that has never once succeeded.
            val hasPersistentError = account.finTsLastErrorCode != null
            val statusRow = panel.hPanel(spacing = 6) { addCssClasses("align-items-center") }
            // Review fix (MEDIUM, test coverage): icon/text-variant selection now goes through
            // [finTsActiveStatusIconClass]/[finTsActiveStatusVariant] -- see [BankAccountLabels.kt]
            // KDoc for why this split makes the decision itself unit-testable (see
            // [BankAccountLabelsTest]) without needing a KVision/DOM test harness.
            statusRow.icon(finTsActiveStatusIconClass(hasPersistentError))
            val activeStatusVariant =
                finTsActiveStatusVariant(hasLastSuccess = account.finTsLastSuccessAt != null, hasPersistentError = hasPersistentError)
            statusRow.div(
                when (activeStatusVariant) {
                    FinTsActiveStatusVariant.LAST_SUCCESS ->
                        gettext("Live-Abruf aktiv · letzter Abruf %1", account.finTsLastSuccessAt.toString())
                    FinTsActiveStatusVariant.ERROR_NO_SUCCESS ->
                        gettext(
                            "Live-Abruf aktiv · noch kein erfolgreicher Abruf (Fehlercode: %1)",
                            account.finTsLastErrorCode.orEmpty(),
                        )
                    FinTsActiveStatusVariant.NO_ERROR_NO_SUCCESS -> tr("Live-Abruf aktiv")
                },
            )
            if (canManageFinTs) {
                val disableButton = panel.button(tr("Deaktivieren"), style = ButtonStyle.OUTLINESECONDARY)
                disableButton.onClick {
                    runGuardedAction(disableButton) {
                        guarded { rpcService<IBankAccountService>().disableFinTs(account.id) } ?: return@runGuardedAction
                        notifySuccess(tr("FinTS-Live-Abruf deaktiviert."))
                        onChanged()
                    }
                }
            }
        }
        FinTsStatus.REAUTH_REQUIRED -> {
            val statusRow = panel.hPanel(spacing = 6) { addCssClasses("align-items-center") }
            statusRow.icon("fas fa-triangle-exclamation text-warning")
            statusRow.div(tr("Erneute Anmeldung erforderlich"))
            if (canManageFinTs) {
                val reauthButton = panel.button(tr("Neu anmelden …"), style = ButtonStyle.WARNING)
                reauthButton.onClick { finTsSetupModal(account, onChanged) }
            }
        }
    }
    // Review fix (MEDIUM, Runde 3): unlike account.finTsLastErrorCode == "FETCH_WINDOW_GAP" (a
    // CURRENT-tick signal the LAST_SUCCESS branch above structurally hides, AND which self-heals
    // within one poll interval either way -- see FinTsPoller.handleMt940's own KDoc), this notice
    // reads the DURABLE finTsGapFrom/finTsGapTo/finTsGapDetectedAt fields, so it renders regardless
    // of which branch above just ran and stays visible for as long as no NEWER gap overwrites it.
    renderFinTsGapNotice(panel = panel, account = account)
}

/** See [renderFinTsCell]'s own "Review fix (MEDIUM, Runde 3)" call-site comment. */
private fun renderFinTsGapNotice(
    panel: Container,
    account: BankAccountDto,
) {
    val detectedAt = account.finTsGapDetectedAt ?: return
    panel.div(
        gettext(
            "Hinweis: Datenlücke im Live-Abruf erkannt am %1 (übersprungener Zeitraum: %2 – %3).",
            detectedAt.toString(),
            account.finTsGapFrom.toString(),
            account.finTsGapTo.toString(),
        ),
    ) {
        addCssClasses("text-warning small")
    }
}

// ================================================================================================
// Wave 1 CRUD -- minimal create/edit form, structure mirrors SepaSettingsScreen's own modal idiom.
// ================================================================================================

internal fun bankAccountEditModal(
    existing: BankAccountDto?,
    onSaved: () -> Unit,
) {
    val modal = Modal(caption = if (existing == null) tr("Bankkonto anlegen") else tr("Bankkonto bearbeiten"))
    // W4c: das Konto-Modal ist ein [LapisForm] (Knöpfe in der Modal-Fußleiste, deshalb `finish()`).
    val form = modal.lapisForm()
    val labelField = form.textField(label = tr("Bezeichnung"), value = existing?.label, required = true)
    // Regeln = die Prüfung des Servers (`BankAccountStore`: `IbanValidator.requireValid` -- Länge je Land, Prüfsumme, SEPA-Raum;
    // `BicValidator`, dieselbe Regex). Die Feldregel ist die lockere Spiegelung ([Validation.looksLikeIban]: Form + Prüfsumme, KEINE
    // Länderliste) und damit nie strenger als der Server, der maßgeblich bleibt. Kein Browser-Vorschlag für eine IBAN.
    val ibanField =
        form.textField(
            label = tr("IBAN"),
            value = existing?.iban,
            required = true,
            autocomplete = Autocomplete.OFF,
            rule = { value ->
                if (Validation.looksLikeIban(value)) FieldCheck.Ok else FieldCheck.Invalid(gettext("Die IBAN ist ungültig."))
            },
        )
    val bicField =
        form.textField(
            label = tr("BIC"),
            value = existing?.bic,
            autocomplete = Autocomplete.OFF,
            rule = { value ->
                if (Validation.looksLikeBic(value)) FieldCheck.Ok else FieldCheck.Invalid(gettext("Die BIC ist ungültig."))
            },
        )
    val bankNameField = form.textField(label = tr("Bankname"), value = existing?.bankName)
    form.finish()

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        form.submit(saveButton) {
            val input =
                BankAccountInput(
                    label = labelField.value.trim(),
                    iban = ibanField.value.trim(),
                    bic = bicField.value.trim().takeIf { it.isNotBlank() },
                    bankName = bankNameField.value.trim().takeIf { it.isNotBlank() },
                )
            guarded {
                if (existing == null) {
                    rpcService<IBankAccountService>().createBankAccount(input)
                } else {
                    rpcService<IBankAccountService>().updateBankAccount(existing.id, input)
                }
            } ?: return@submit
            modal.hide()
            notifySuccess(tr("Gespeichert."))
            onSaved()
        }
    }
    modal.addButton(saveButton)
    modal.show()
}

// ================================================================================================
// Wave 2 FinTS setup -- disclaimer -> credentials -> (optional) TAN, all in ONE modal, ONE footer
// "Abbrechen" button that never changes identity across the whole flow.
// ================================================================================================

private fun finTsSetupModal(
    account: BankAccountDto,
    onChanged: () -> Unit,
) {
    AppScope.launch {
        val disclaimer = guarded { rpcService<IBankAccountService>().getFinTsComplianceDisclaimer() } ?: return@launch
        showFinTsSetupModal(account, disclaimer, onChanged)
    }
}

internal fun showFinTsSetupModal(
    account: BankAccountDto,
    disclaimer: FinTsComplianceDisclaimerDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = gettext("FinTS-Live-Abruf: %1", account.label))
    var openHandle: String? = null
    var inTanStep = false

    val cancelButton =
        Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply {
            onClick {
                val handle = openHandle
                modal.hide()
                if (handle != null) {
                    runGuardedAction(null) { guarded { rpcService<IBankAccountService>().cancelFinTsSetup(handle) } }
                }
            }
        }
    modal.addButton(cancelButton)

    val credentialsPanel = modal.vPanel(spacing = 10)
    val tanPanel = modal.vPanel(spacing = 10).apply { hide() }

    credentialsPanel.div(tr("Rechtlicher Hinweis (bitte vollständig lesen):")) { addCssClass("fw-bold") }
    credentialsPanel.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 250.px
        overflow = Overflow.AUTO
        content = disclaimer.text
    }
    // W4c: die Zugangsdaten sind ein [LapisForm]. Das Bestätigungskästchen ist eine Pflicht-Checkbox: der frühere graue "Aktivieren"-Knopf
    // ohne Erklärung (`disabled = checked != true`) sagte nicht, was fehlt -- jetzt steht die Meldung am Kästchen (K2-Beschluss W4b).
    val credentialsForm = credentialsPanel.lapisForm()
    val acceptField =
        credentialsForm.checkField(label = tr("Ich habe den Hinweistext vollständig gelesen und bestätige ihn."), required = true)
    // Review fix (MINOR): warn BEFORE the confirmation, not only after a failed delete attempt --
    // BankAccountStore.delete() permanently refuses to delete any account with a FinTS-disclaimer
    // acknowledgment row (Art. 5(2) DSGVO retention, see that function's own KDoc), and that row is
    // written the moment this checkbox is confirmed and submitted -- BEFORE the bank dialog even
    // runs, so it applies even to an attempt that then fails (wrong PIN, unreachable bank, ...).
    // Without this notice an ADMIN who mistypes the IBAN/BLZ on this very form has no way to know,
    // until a LATER delete attempt throws a ConflictException, that the mistake is now permanent.
    credentialsForm.panel.div(
        tr(
            "Hinweis: Mit der Bestätigung wird eine unlöschbare Nachweiszeile gespeichert (Art. 5 Abs. 2 DSGVO) -- " +
                "dieses Bankkonto kann danach nicht mehr gelöscht werden, auch wenn der Live-Abruf fehlschlägt.",
        ),
    ) { addCssClasses("text-muted small") }

    val blzField = credentialsForm.textField(label = tr("Bankleitzahl (BLZ)"), required = true)
    val urlField = credentialsForm.textField(label = tr("FinTS/HBCI-URL"), value = account.finTsUrl, required = true)
    // D-PIN: keine Passwortmanager-Angebote für Benutzerkennung/PIN -- der dokumentierte Insert-Hook [hardenSecretInput] bleibt
    // unverändert (`autocomplete="new-password"` hält auch Chrome vom Ausfüllen des Lapis-Passworts ab; `autocomplete="off"` von
    // `suppressManagers` ignoriert Chrome bei Passwortfeldern).
    val userIdRow = credentialsForm.panel.hPanel()
    val userIdField = credentialsForm.passwordField(label = tr("Benutzerkennung"), required = true, host = userIdRow)
    hardenSecretInput(userIdRow)
    val pinRow = credentialsForm.panel.hPanel()
    val pinField = credentialsForm.passwordField(label = tr("PIN"), required = true, host = pinRow)
    hardenSecretInput(pinRow)
    credentialsForm.finish()

    val activateButton = Button(tr("Aktivieren"), style = ButtonStyle.PRIMARY)
    modal.addButton(activateButton)

    val tanPromptLabel = tanPanel.div()
    val tanExpiryLabel = tanPanel.div { addCssClasses("text-muted small") }
    val tanForm = tanPanel.lapisForm()
    val tanField = tanForm.textField(label = tr("TAN"), required = true)
    tanForm.finish()
    val submitTanButton = Button(tr("TAN bestätigen"), style = ButtonStyle.PRIMARY).apply { hide() }
    modal.addButton(submitTanButton)

    fun enterTanStep(outcome: FinTsSetupResultDto.TanRequested) {
        openHandle = outcome.handle
        inTanStep = true
        credentialsPanel.hide()
        activateButton.hide()
        tanPanel.show()
        submitTanButton.show()
        tanPromptLabel.content = outcome.bankPrompt
        tanExpiryLabel.content = gettext("Gültig bis %1 Uhr.", outcome.expiresAt.toString())
        tanField.reset()
    }

    fun handleSetupResult(result: FinTsSetupResultDto?) {
        when (result) {
            is FinTsSetupResultDto.Verified -> {
                pinField.reset()
                userIdField.reset()
                modal.hide()
                notifySuccess(tr("FinTS-Live-Abruf aktiviert."))
                onChanged()
            }
            is FinTsSetupResultDto.TanRequested -> enterTanStep(result)
            is FinTsSetupResultDto.Failed -> {
                if (inTanStep) {
                    tanForm.showFormError(result.message)
                } else {
                    credentialsForm.showFormError(result.message)
                }
            }
            null -> Unit
        }
    }

    activateButton.onClick {
        credentialsForm.submit(activateButton) {
            val result =
                guarded {
                    rpcService<IBankAccountService>().beginFinTsSetup(
                        FinTsSetupInput(
                            bankAccountId = account.id,
                            blz = blzField.value.trim(),
                            url = urlField.value.trim(),
                            userId = userIdField.value,
                            pin = pinField.value,
                            disclaimerVersion = disclaimer.version,
                            disclaimerSha256 = disclaimer.sha256,
                        ),
                    )
                }
            handleSetupResult(result)
        }
    }

    submitTanButton.onClick {
        val handle = openHandle ?: return@onClick
        // Eine TAN-Einreichung ist unwiederholbar: der Doppelklickschutz ([LapisForm.submit] -> `runGuardedAction`) ist Pflicht.
        tanForm.submit(submitTanButton) {
            val result = guarded { rpcService<IBankAccountService>().submitFinTsTan(handle, tanField.value.trim()) }
            handleSetupResult(result)
        }
    }

    modal.show()
}

/** D-PIN: no browser password-manager offer for a banking PIN/userid -- see screen KDoc. */
private fun hardenSecretInput(row: SimplePanel) {
    row.addAfterInsertHook { vnode ->
        val rowElement = vnode.elm as? HTMLElement
        val inputElement = rowElement?.querySelector("input") as? HTMLInputElement
        inputElement?.setAttribute("autocomplete", "new-password")
        inputElement?.setAttribute("autocapitalize", "off")
        inputElement?.setAttribute("spellcheck", "false")
    }
}
