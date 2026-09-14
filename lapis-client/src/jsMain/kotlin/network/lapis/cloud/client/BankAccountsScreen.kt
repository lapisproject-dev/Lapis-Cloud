package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.check.checkBox
import io.kvision.form.text.password
import io.kvision.form.text.text
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
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
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
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Bankkonten"))

    val warningBand = root.div().apply { hide() }
    val tableHost = root.div()
    val actionsRow = root.hPanel(spacing = 8)

    fun reload() {
        AppScope.launch {
            val accounts = guarded { rpcService<IBankAccountService>().listBankAccounts() } ?: return@launch
            renderWarningBand(warningBand, accounts)
            renderAccountsTable(tableHost, accounts) { reload() }
        }
    }

    if (BankAccountAuthzUi.canWrite(AppState.session?.role)) {
        val createButton = actionsRow.button(tr("Bankkonto anlegen"), style = ButtonStyle.PRIMARY)
        createButton.onClick { bankAccountEditModal(null) { reload() } }
    }

    reload()
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
    tableHost: Div,
    accounts: List<BankAccountDto>,
    onChanged: () -> Unit,
) {
    tableHost.removeAll()
    val currentRole = AppState.session?.role
    val canWrite = BankAccountAuthzUi.canWrite(currentRole)
    val canManageFinTs = BankAccountAuthzUi.canManageFinTs(currentRole)
    val headers =
        buildList {
            add(tr("Bezeichnung"))
            add(tr("IBAN"))
            add(tr("Bank"))
            add(tr("Standard"))
            add(tr("FinTS-Live-Abruf"))
            if (canWrite) add(tr("Aktionen"))
        }
    val table = tableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))
    accounts.forEach { account -> renderAccountRow(table, account, canWrite, canManageFinTs, onChanged) }
}

private fun renderAccountRow(
    table: Table,
    account: BankAccountDto,
    canWrite: Boolean,
    canManageFinTs: Boolean,
    onChanged: () -> Unit,
) {
    table.row {
        cell(account.label)
        cell(account.ibanMasked)
        cell(account.bankName ?: "—")
        cell { if (account.isDefault) icon("fas fa-star") }
        cell { renderFinTsCell(this, account, canManageFinTs, onChanged) }
        if (canWrite) {
            cell {
                val editButton = button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
                editButton.onClick { bankAccountEditModal(account) { onChanged() } }
                if (!account.isDefault) {
                    val setDefaultButton = button(tr("Als Standard setzen"), style = ButtonStyle.OUTLINESECONDARY)
                    setDefaultButton.onClick {
                        AppScope.launch {
                            val result = guarded { rpcService<IBankAccountService>().setDefaultBankAccount(account.id) } ?: return@launch
                            notifySuccess(tr("Standardkonto gesetzt."))
                            onChanged()
                        }
                    }
                    val deleteButton = button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
                    deleteButton.onClick {
                        AppScope.launch {
                            guarded { rpcService<IBankAccountService>().deleteBankAccount(account.id) } ?: return@launch
                            notifySuccess(tr("Bankkonto gelöscht."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/** D-REAUTH: the status cell carries the action, D-UNAVAILABLE: no controls at all when `finTsAvailable == false`. */
private fun renderFinTsCell(
    panel: SimplePanel,
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
                    AppScope.launch {
                        guarded { rpcService<IBankAccountService>().disableFinTs(account.id) } ?: return@launch
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
    panel: SimplePanel,
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

private fun bankAccountEditModal(
    existing: BankAccountDto?,
    onSaved: () -> Unit,
) {
    val modal = Modal(caption = if (existing == null) tr("Bankkonto anlegen") else tr("Bankkonto bearbeiten"))
    val labelInput = modal.text(label = tr("Bezeichnung")).apply { value = existing?.label }
    val ibanInput = modal.text(label = tr("IBAN")).apply { value = existing?.iban }
    val bicInput = modal.text(label = tr("BIC (optional)")).apply { value = existing?.bic }
    val bankNameInput = modal.text(label = tr("Bankname (optional)")).apply { value = existing?.bankName }
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Speichern"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                errorBox.hide()
                val label = labelInput.value?.trim().orEmpty()
                val iban = ibanInput.value?.trim().orEmpty()
                if (label.isBlank() || iban.isBlank()) {
                    errorBox.content = tr("Bezeichnung und IBAN sind Pflichtfelder.")
                    errorBox.show()
                    return@onClick
                }
                val input =
                    BankAccountInput(
                        label = label,
                        iban = iban,
                        bic = bicInput.value?.trim()?.takeIf { it.isNotBlank() },
                        bankName = bankNameInput.value?.trim()?.takeIf { it.isNotBlank() },
                    )
                AppScope.launch {
                    val result =
                        guarded {
                            if (existing == null) {
                                rpcService<IBankAccountService>().createBankAccount(input)
                            } else {
                                rpcService<IBankAccountService>().updateBankAccount(existing.id, input)
                            }
                        } ?: return@launch
                    modal.hide()
                    notifySuccess(tr("Gespeichert."))
                    onSaved()
                }
            }
        },
    )
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

private fun showFinTsSetupModal(
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
                    AppScope.launch { guarded { rpcService<IBankAccountService>().cancelFinTsSetup(handle) } }
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
    val acceptCheck = credentialsPanel.checkBox(label = tr("Ich habe den Hinweistext vollständig gelesen und bestätige ihn."))
    // Review fix (MINOR): warn BEFORE the confirmation, not only after a failed delete attempt --
    // BankAccountStore.delete() permanently refuses to delete any account with a FinTS-disclaimer
    // acknowledgment row (Art. 5(2) DSGVO retention, see that function's own KDoc), and that row is
    // written the moment this checkbox is confirmed and submitted -- BEFORE the bank dialog even
    // runs, so it applies even to an attempt that then fails (wrong PIN, unreachable bank, ...).
    // Without this notice an ADMIN who mistypes the IBAN/BLZ on this very form has no way to know,
    // until a LATER delete attempt throws a ConflictException, that the mistake is now permanent.
    credentialsPanel.div(
        tr(
            "Hinweis: Mit der Bestätigung wird eine unlöschbare Nachweiszeile gespeichert (Art. 5 Abs. 2 DSGVO) -- " +
                "dieses Bankkonto kann danach nicht mehr gelöscht werden, auch wenn der Live-Abruf fehlschlägt.",
        ),
    ) { addCssClasses("text-muted small") }

    val blzInput = credentialsPanel.text(label = tr("Bankleitzahl (BLZ)"))
    val urlInput = credentialsPanel.text(label = tr("FinTS/HBCI-URL")).apply { value = account.finTsUrl }
    val userIdRow = credentialsPanel.hPanel()
    val userIdInput = userIdRow.password(label = tr("Benutzerkennung"))
    hardenSecretInput(userIdRow)
    val pinRow = credentialsPanel.hPanel()
    val pinInput = pinRow.password(label = tr("PIN"))
    hardenSecretInput(pinRow)

    val credentialsError =
        credentialsPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val activateButton = Button(tr("Aktivieren"), style = ButtonStyle.PRIMARY).apply { disabled = true }
    acceptCheck.subscribe { checked -> activateButton.disabled = checked != true }
    modal.addButton(activateButton)

    val tanPromptLabel = tanPanel.div()
    val tanExpiryLabel = tanPanel.div { addCssClasses("text-muted small") }
    val tanInput = tanPanel.text(label = tr("TAN"))
    val tanError =
        tanPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
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
        tanInput.value = ""
    }

    fun handleSetupResult(result: FinTsSetupResultDto?) {
        when (result) {
            is FinTsSetupResultDto.Verified -> {
                pinInput.value = ""
                userIdInput.value = ""
                modal.hide()
                notifySuccess(tr("FinTS-Live-Abruf aktiviert."))
                onChanged()
            }
            is FinTsSetupResultDto.TanRequested -> enterTanStep(result)
            is FinTsSetupResultDto.Failed -> {
                if (inTanStep) {
                    tanError.content = result.message
                    tanError.show()
                } else {
                    credentialsError.content = result.message
                    credentialsError.show()
                }
            }
            null -> Unit
        }
    }

    activateButton.onClick {
        credentialsError.hide()
        val blz = blzInput.value?.trim().orEmpty()
        val url = urlInput.value?.trim().orEmpty()
        val userId = userIdInput.value.orEmpty()
        val pin = pinInput.value.orEmpty()
        if (blz.isBlank() || url.isBlank() || userId.isBlank() || pin.isBlank()) {
            credentialsError.content = tr("Alle Felder sind Pflichtfelder.")
            credentialsError.show()
            return@onClick
        }
        activateButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IBankAccountService>().beginFinTsSetup(
                        FinTsSetupInput(
                            bankAccountId = account.id,
                            blz = blz,
                            url = url,
                            userId = userId,
                            pin = pin,
                            disclaimerVersion = disclaimer.version,
                            disclaimerSha256 = disclaimer.sha256,
                        ),
                    )
                }
            activateButton.disabled = false
            handleSetupResult(result)
        }
    }

    submitTanButton.onClick {
        tanError.hide()
        val handle = openHandle ?: return@onClick
        val tan = tanInput.value?.trim().orEmpty()
        if (tan.isBlank()) return@onClick
        submitTanButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IBankAccountService>().submitFinTsTan(handle, tan) }
            submitTanButton.disabled = false
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
