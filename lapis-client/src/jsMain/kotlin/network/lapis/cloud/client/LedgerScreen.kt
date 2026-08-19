package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService
import kotlin.time.Clock

/**
 * Accounting UI wave, screen 1 of 5 -- "Kontenplan & Journal" (SKR42 chart of accounts CRUD; the
 * draft/post Journal workflow; per-account Hauptbuch/Kassenbuch drill-down), per the approved plan
 * + UI/UX-Design-Team review on `feature/accounting-ui`. See plan "Screen 1 -- LedgerScreen.kt" and
 * design decisions D1-D3, D5, D6, D8, D9 (LedgerAccountType only -- the other Accounting enums this
 * screen also touches, [GemeinnuetzigkeitSphere]/[ReserveType]/[DonorCategory], get their labels
 * from the shared `AccountingLabels.kt`), D10, D11, D12, D13.
 *
 * Role gating (verified against `AccountingService.kt`, plan's role-gating table): `Routing.kt`
 * already gates the whole `/ledger` route on TREASURER/BOARD/ADMIN. Every mutating action below
 * (createLedgerAccount/deactivateLedgerAccount/saveDraftEntry/postJournalEntry/postDraftEntry) is
 * additionally gated on the narrower `canManage = AppState.hasRole(TREASURER, ADMIN)` -- a BOARD
 * caller reaches this whole screen read-only, mirroring `TREASURY_ROLES` vs `ACCOUNTING_READ_ROLES`
 * server-side exactly (BOARD is never in `TREASURY_ROLES`).
 *
 * Every monetary figure on this screen is either (a) a [Decimal] returned verbatim by
 * `IAccountingService` and rendered through [formatMoney]/`Money.kt` with zero re-rounding, or (b) a
 * *pre-submission* sum this screen computes itself purely so a treasurer can visually verify a new
 * booking balances before confirming it (see [sumPostingLines] KDoc) -- never a re-derivation of a
 * figure the server already computed and returned.
 *
 * First use of a reactive `.subscribe { }` form binding in this client (D11's live disabled-state
 * toggling on the account-creation form's `type` select, and the donor-block's choice select) --
 * `MotionsScreen.kt`'s own KDoc explains why it never needed one (its forms had no field whose
 * validity/visibility depends live on another field's value); D11 explicitly requires exactly that
 * here, so this file is the first to reach for KVision's `ObservableState.subscribe` API. Every
 * subscription's initial state is also applied once immediately after wiring (not solely relied on
 * to fire on subscribe), so the form starts consistent regardless of that timing detail.
 *
 * First use of a dynamic add/remove form-row list in this client (Journal posting lines, see
 * [renderPostingLinesRow]) -- `MotionsScreen.renderOpenVoteForm`'s comma-separated-text shortcut for
 * Vote option labels only works for a flat list of strings; a posting line carries five structured
 * fields (account/side/amount/sphere/cost center) that cannot be flattened the same way.
 *
 * Mail-merge/Postal-Dispatch UI wave, design decision D3: [renderDonorInfo] additionally renders a
 * "Spendenbescheinigung (PDF)" download link ([MailmergeHttp.receiptUrl]) when the journal entry is
 * `POSTED` and has a member-attributed donor (`donorMemberId != null`) -- the receipt route requires
 * a donor's postal address, which only a Member record carries; an external/anonymous donor has no
 * receipt route to link to. No extra in-screen gating needed -- `renderJournalEntryDetail`'s only
 * caller is already TREASURER/BOARD/ADMIN-only via the `/ledger` route, matching
 * `MailmergeRoutes.kt`'s `FINANCIAL_DOC_ROLES` exactly (see [MailmergeHttp] KDoc).
 *
 * Design decision D5: the same block additionally renders a "Per Post versenden" postal-dispatch
 * trigger (`IPostalMailService.dispatchSpendenbescheinigungByPost`, matching
 * `FINANCIAL_DISPATCH_ROLES` -- the same TREASURER/BOARD/ADMIN tier as the route itself), gated by
 * [isPostalMailEnabled] (D7) and confirmed via [postalDispatchConfirmDialog] (D5) -- see
 * `PostalMailScreen.kt`'s file KDoc for the "address never touches the browser" load-bearing
 * finding that shapes that dialog's copy.
 */
fun renderLedgerScreen(container: SimplePanel) {
    val canManage = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Kontenplan & Journal"))

    // ---- Accounts (Kontenplan) -------------------------------------------------------------
    root.h2(tr("Konten (SKR42 Kontenplan)"))
    val accountsFilterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveAccountsCheck = accountsFilterRow.checkBox(label = tr("Inaktive Konten anzeigen"))
    val accountsRefreshButton = accountsFilterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val accountListPanel = root.vPanel(spacing = 6)

    root.h2(tr("Kontodetails"))
    val accountDetailPanel = root.vPanel(spacing = 10)
    accountDetailPanel.p(tr("Konto oben auswählen, um Hauptbuch/Kassenbuch zu sehen."))

    fun selectAccount(account: LedgerAccountDto) {
        renderAccountDrillDown(accountDetailPanel, account)
    }

    fun refreshAccounts() {
        accountListPanel.removeAll()
        AppScope.launch {
            val accounts =
                guarded {
                    rpcService<IAccountingService>().listLedgerAccounts(activeOnly = !includeInactiveAccountsCheck.value)
                } ?: return@launch
            if (accounts.isEmpty()) {
                accountListPanel.p(tr("Noch keine Konten angelegt."))
                return@launch
            }
            accounts.sortedBy { it.accountNumber }.forEach { account ->
                renderAccountRow(accountListPanel, account, canManage, ::selectAccount, ::refreshAccounts)
            }
        }
    }
    accountsRefreshButton.onClick { refreshAccounts() }
    refreshAccounts()

    if (canManage) {
        root.h2(tr("Neues Konto anlegen"))
        renderAccountCreationForm(root, ::refreshAccounts)
    }

    // ---- Kontenzuordnung Zahlungsverkehr (V1.2.1 Zahlungs-Fundament) ----------------------
    // Deliberately its OWN, narrower role check -- ADMIN-only, NOT the screen-wide `canManage`
    // (TREASURER/ADMIN) -- see renderPaymentAccountMappingSection KDoc "Role gate" (Review Round 1,
    // 2026-08-19, MINOR-5): OrganizationSettingsService.updateOrganizationSettings itself requires
    // AccountRole.ADMIN only, so a TREASURER must not see an editable form that always fails.
    root.h2(tr("Kontenzuordnung Zahlungsverkehr"))
    renderPaymentAccountMappingSection(root, AppState.hasRole(AccountRole.ADMIN))

    // ---- Journal (Grundbuch) --------------------------------------------------------------
    root.h2(tr("Journal (Grundbuch)"))
    val journalFilterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val journalDateFilter = journalFilterRow.dateRangeFilter(toLabel = tr("Bis (JJJJ-MM-TT, optional)"))
    val journalStatusOptions = listOf("" to tr("Alle Status")) + JournalEntryStatus.entries.map { it.name to journalEntryStatusLabel(it) }
    val journalStatusSelect = journalFilterRow.select(options = journalStatusOptions, value = "", label = tr("Status"))
    val journalRefreshButton = journalFilterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val journalListPanel = root.vPanel(spacing = 6)

    root.h2(tr("Journaldetails"))
    val journalDetailPanel = root.vPanel(spacing = 10)
    journalDetailPanel.p(tr("Buchung oben auswählen, um Details zu sehen."))

    var newEntryPrefill: ((JournalEntryDto) -> Unit)? = null
    var currentJournalEntryId: String? = null
    // `refreshJournalDetail` (below) needs to trigger a list refresh after a post/draft-save
    // succeeds, and `refreshJournal` (declared after it) needs to trigger a detail refresh on row
    // click -- a genuine mutual reference local `fun` declarations cannot express directly (Kotlin
    // resolves local declarations by lexical/textual order, so neither could be declared "first").
    // A nullable function-reference var, assigned once `refreshJournal` exists, breaks the cycle --
    // the same pattern already used for `newEntryPrefill` above.
    var refreshJournalFn: (() -> Unit)? = null

    fun selectJournalEntry(id: String) {
        currentJournalEntryId = id
    }

    fun refreshJournalDetail() {
        val id = currentJournalEntryId ?: return
        renderJournalEntryDetail(
            journalDetailPanel,
            id,
            canManage,
            onChanged = {
                refreshJournalDetail()
                refreshJournalFn?.invoke()
            },
            onDuplicate = { entry -> newEntryPrefill?.invoke(entry) },
        )
    }

    fun refreshJournal() {
        journalListPanel.removeAll()
        AppScope.launch {
            val status = journalStatusSelect.value?.takeIf { it.isNotBlank() }?.let { JournalEntryStatus.valueOf(it) }
            val entries =
                guarded {
                    rpcService<IAccountingService>().listJournal(journalDateFilter.parseFrom(), journalDateFilter.parseTo(), status)
                } ?: return@launch
            if (entries.isEmpty()) {
                journalListPanel.p(tr("Keine Buchungen im gewählten Zeitraum."))
                return@launch
            }
            entries.forEach { entry ->
                renderJournalRow(journalListPanel, entry) { id ->
                    selectJournalEntry(id)
                    refreshJournalDetail()
                }
            }
        }
    }
    refreshJournalFn = ::refreshJournal

    journalRefreshButton.onClick { refreshJournal() }
    refreshJournal()

    if (canManage) {
        root.h2(tr("Neue Buchung"))
        val newEntryPanel = root.vPanel(spacing = 6)
        newEntryPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val accounts = guarded { rpcService<IAccountingService>().listLedgerAccounts(activeOnly = true) } ?: emptyList()
            val costCenters = guarded { rpcService<IAccountingService>().listCostCenters(activeOnly = true) } ?: emptyList()
            val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
            val externalDonors = guarded { rpcService<IAccountingService>().listExternalDonors(activeOnly = true) } ?: emptyList()
            val settings = guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }

            newEntryPanel.removeAll()
            if (accounts.isEmpty()) {
                newEntryPanel.p(tr("Noch keine aktiven Konten -- zuerst oben mindestens zwei Konten anlegen."))
                return@launch
            }
            newEntryPrefill =
                renderNewEntryForm(
                    newEntryPanel,
                    accounts,
                    costCenters,
                    members,
                    externalDonors,
                    settings?.isPoliticalParty ?: false,
                    onSaved = { refreshJournal() },
                )
        }
    }
}

// ============================================================================================
// Kontenzuordnung Zahlungsverkehr (V1.2.1 "Zahlungs-Fundament")
// ============================================================================================

/**
 * Welle V1.2.1 "Zahlungs-Fundament" — lets an ADMIN pick which SKR42 [LedgerAccountDto]s
 * [ContributionPostingBridge][network.lapis.cloud.server.rpc.ContributionPostingBridge] books a
 * manually marked-paid contribution into (`OrganizationSettingsDto.paymentBankAccountId`/
 * `.paymentFeeAccountId`/`.contributionIncomeAccountId`). Unlike `renderPoliticianRankingToggle`
 * (`PoliticianScreen.kt`) these three fields ARE part of the generic `updateOrganizationSettings`
 * write-set (plain configuration, not a liability-relevant feature toggle) -- see
 * `OrganizationSettingsDto` KDoc. Same "wholesale-replace every OTHER field unchanged" idiom that
 * KDoc's own `toInputWithPoliticianRankingEnabled` helper establishes -- see
 * [OrganizationSettingsDto.toInputWithPaymentAccountMapping] below.
 *
 * **Role gate (Review Round 1, 2026-08-19, MINOR-5):** [canManage] here is deliberately
 * `AppState.hasRole(ADMIN)` ONLY, narrower than [renderLedgerScreen]'s own screen-wide `canManage`
 * (`TREASURER`/`ADMIN`) that gates account creation/journal posting -- because
 * `OrganizationSettingsService.updateOrganizationSettings` itself requires `AccountRole.ADMIN` only.
 * Widening the endpoint to accept `TREASURER` was rejected: `updateOrganizationSettings` is a broad,
 * wholesale settings-update method that also writes bank IBAN/BIC, tax-exemption data, and other
 * org-wide fields with no established TREASURER-write precedent elsewhere in this codebase, so
 * narrowing the CLIENT-side gate on just this section to match the actual, unwidened endpoint
 * requirement was the least invasive fix.
 *
 * While unconfigured (any of the three still unset), a paid contribution's status still transitions
 * but no journal entry is booked -- the empty option in each select IS a valid, savable choice
 * (clears that mapping back to `null`), not merely a placeholder.
 */
private fun renderPaymentAccountMappingSection(
    root: SimplePanel,
    canManage: Boolean,
) {
    val panel = root.vPanel(spacing = 8)
    panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun load() {
        panel.removeAll()
        panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val accounts = guarded { rpcService<IAccountingService>().listLedgerAccounts(activeOnly = true) } ?: return@launch
            val settings = guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } ?: return@launch
            panel.removeAll()

            if (!canManage) {
                val unconfigured = tr("(nicht konfiguriert)")
                panel.p(
                    gettext(
                        "Bankkonto: %1 · Gebührenkonto: %2 · Beitragserlöskonto: %3",
                        accounts.find { it.id == settings.paymentBankAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.paymentFeeAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.contributionIncomeAccountId }?.name ?: unconfigured,
                    ),
                )
                return@launch
            }

            panel.p(
                tr(
                    "Solange eines der drei Konten nicht zugeordnet ist, wird ein als bezahlt markierter " +
                        "Beitrag NICHT gebucht -- der Status wechselt trotzdem auf \"bezahlt\".",
                ),
            ) { addCssClasses("text-muted small") }

            val accountOptions = listOf("" to tr("(nicht konfiguriert)")) + accounts.map { it.id to "${it.accountNumber} · ${it.name}" }

            val bankSelect =
                panel.select(
                    options = accountOptions,
                    value = settings.paymentBankAccountId.orEmpty(),
                    label = tr("Bankkonto"),
                )
            val feeSelect =
                panel.select(options = accountOptions, value = settings.paymentFeeAccountId.orEmpty(), label = tr("Gebührenkonto"))
            val incomeSelect =
                panel.select(
                    options = accountOptions,
                    value = settings.contributionIncomeAccountId.orEmpty(),
                    label = tr("Beitragserlöskonto"),
                )

            val saveButton = panel.button(tr("Kontenzuordnung speichern"), style = ButtonStyle.PRIMARY)
            saveButton.onClick {
                saveButton.disabled = true
                AppScope.launch {
                    try {
                        val result =
                            guarded {
                                rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                                    settings.toInputWithPaymentAccountMapping(
                                        paymentBankAccountId = bankSelect.value?.takeIf { it.isNotBlank() },
                                        paymentFeeAccountId = feeSelect.value?.takeIf { it.isNotBlank() },
                                        contributionIncomeAccountId = incomeSelect.value?.takeIf { it.isNotBlank() },
                                    ),
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Kontenzuordnung gespeichert."))
                            load()
                        }
                    } finally {
                        // Review Round 4 (2026-08-19): guarded() rethrows CancellationException -- a
                        // plain post-guarded() re-enable never runs if this coroutine is cancelled
                        // mid-flight, leaving the button permanently disabled until a page refresh.
                        // Same bug class as ContributionsScreen.kt's payButton (Review Round 2,
                        // SHOULD-3) -- that fix's own comment named this call site as its model, but
                        // the fix itself wasn't applied here at the time.
                        saveButton.disabled = false
                    }
                }
            }
        }
    }
    load()
}

private fun OrganizationSettingsDto.toInputWithPaymentAccountMapping(
    paymentBankAccountId: String?,
    paymentFeeAccountId: String?,
    contributionIncomeAccountId: String?,
) = OrganizationSettingsInput(
    name = name,
    street = street,
    postalCode = postalCode,
    city = city,
    country = country,
    bankIban = bankIban,
    bankBic = bankBic,
    taxExemptionAuthority = taxExemptionAuthority,
    taxExemptionDate = taxExemptionDate,
    isPoliticalParty = isPoliticalParty,
    postalMailEnabled = postalMailEnabled,
    politicianRankingEnabled = politicianRankingEnabled,
    paymentBankAccountId = paymentBankAccountId,
    paymentFeeAccountId = paymentFeeAccountId,
    contributionIncomeAccountId = contributionIncomeAccountId,
)

// ============================================================================================
// Accounts (Kontenplan)
// ============================================================================================

private fun renderAccountRow(
    panel: SimplePanel,
    account: LedgerAccountDto,
    canManage: Boolean,
    onSelect: (LedgerAccountDto) -> Unit,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(gettext("%1 · %2", account.accountNumber, account.name)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(ledgerAccountTypeLabel(account.type), ledgerAccountTypeColor(account.type))
    headerRow.activeStatusBadge(account.active)

    val metaParts = mutableListOf(gettext("Kontenklasse %1", account.accountClass))
    account.reserveType?.let { metaParts.add(reserveTypeLabel(it)) }
    if (account.isCashRegister) metaParts.add(tr("Kasse"))
    row.div(metaParts.joinToString(" · ")) { addCssClasses("text-muted small") }

    val actionRow = row.hPanel(spacing = 8)
    val showButton = actionRow.button(tr("Details anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(account) }

    if (canManage && account.active) {
        val deactivateButton = actionRow.button(tr("Deaktivieren"), style = ButtonStyle.OUTLINEDANGER)
        deactivateButton.onClick {
            confirmDialog(
                title = tr("Konto deaktivieren"),
                message =
                    gettext(
                        "\"%1 · %2\" wirklich deaktivieren? Bestehende Buchungen bleiben erhalten, das Konto steht " +
                            "aber für neue Buchungen nicht mehr zur Verfügung.",
                        account.accountNumber,
                        account.name,
                    ),
                confirmLabel = tr("Deaktivieren"),
            ) {
                AppScope.launch {
                    val result = guarded { rpcService<IAccountingService>().deactivateLedgerAccount(account.id) }
                    if (result != null) {
                        notifyInfo(tr("Konto wurde deaktiviert."))
                        onChanged()
                    }
                }
            }
        }
    }
}

/**
 * D11: `reserveType`/`isCashRegister` are disabled-not-hidden and reset when [type] leaves the
 * Kontotyp they apply to, kept live via the account-type select's `.subscribe { }` -- the initial
 * state is additionally applied once right after wiring, so the form starts consistent regardless
 * of whether `subscribe` itself fires immediately on subscription.
 */
private fun renderAccountCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val typeOptions = LedgerAccountType.entries.map { it.name to ledgerAccountTypeLabel(it) }
    val panel = root.vPanel(spacing = 6)
    val numberInput = panel.text(label = tr("Kontonummer (SKR42)"))
    val nameInput = panel.text(label = tr("Name"))
    val classInput = panel.text(label = tr("Kontenklasse (erste Ziffer der Kontonummer, 0-9)"))
    val typeSelect = panel.select(options = typeOptions, value = LedgerAccountType.ASSET.name, label = tr("Kontotyp"))
    val reserveOptions = listOf("" to tr("-- keine Rücklage --")) + ReserveType.entries.map { it.name to reserveTypeLabel(it) }
    val reserveSelect = panel.select(options = reserveOptions, value = "", label = tr("Rücklagenart"))
    panel.div(tr("Nur bei Kontotyp „Eigenkapitalkonto\" wählbar.")) { addCssClasses("text-muted small") }
    val cashRegisterCheck = panel.checkBox(label = tr("Kasse (Kassenbuch-fähig)"))
    panel.div(tr("Nur bei Kontotyp „Aktivkonto\" wählbar.")) { addCssClasses("text-muted small") }
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    fun applyTypeGating(typeValue: String?) {
        val isEquity = typeValue == LedgerAccountType.EQUITY.name
        val isAsset = typeValue == LedgerAccountType.ASSET.name
        reserveSelect.disabled = !isEquity
        if (!isEquity) reserveSelect.value = ""
        cashRegisterCheck.disabled = !isAsset
        if (!isAsset) cashRegisterCheck.value = false
    }
    applyTypeGating(typeSelect.value)
    typeSelect.subscribe { applyTypeGating(it) }

    val createButton = panel.button(tr("Konto anlegen"), style = ButtonStyle.PRIMARY)
    createButton.onClick {
        errorBox.hide()
        val accountNumber = numberInput.value.orEmpty().trim()
        val name = nameInput.value.orEmpty().trim()
        val accountClass =
            classInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val typeValue = typeSelect.value

        if (!Validation.isNonBlank(accountNumber) ||
            !Validation.isNonBlank(name) ||
            accountClass == null ||
            accountClass !in 0..9 ||
            typeValue == null
        ) {
            errorBox.content = tr("Bitte Kontonummer, Name, Kontenklasse (0-9) und Kontotyp angeben.")
            errorBox.show()
            return@onClick
        }

        val type = LedgerAccountType.valueOf(typeValue)
        val reserveType =
            if (type == LedgerAccountType.EQUITY) {
                reserveSelect.value?.takeIf { it.isNotBlank() }?.let { ReserveType.valueOf(it) }
            } else {
                null
            }
        val isCashRegister = type == LedgerAccountType.ASSET && cashRegisterCheck.value

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IAccountingService>().createLedgerAccount(
                        LedgerAccountInput(
                            accountNumber = accountNumber,
                            name = name,
                            accountClass = accountClass,
                            type = type,
                            active = true,
                            reserveType = reserveType,
                            isCashRegister = isCashRegister,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Konto \"%1 · %2\" wurde angelegt.", accountNumber, name))
                numberInput.value = null
                nameInput.value = null
                classInput.value = null
                reserveSelect.value = ""
                cashRegisterCheck.value = false
                onCreated()
            }
        }
    }
}

// ============================================================================================
// Account drill-down: Hauptbuch (general ledger) / Kassenbuch (cash book)
// ============================================================================================

/** D10: the Kassenbuch toggle is entirely ABSENT (not disabled) unless [LedgerAccountDto
 * .isCashRegister] -- `getKassenbuch` rejects a non-cash-register account with a
 * `ConflictException` server-side, and this screen must never offer a call the server will reject. */
private fun renderAccountDrillDown(
    panel: SimplePanel,
    account: LedgerAccountDto,
) {
    panel.removeAll()
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(gettext("%1 · %2", account.accountNumber, account.name)) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(ledgerAccountTypeLabel(account.type), ledgerAccountTypeColor(account.type))
    account.reserveType?.let { headerRow.typeBadge(reserveTypeLabel(it), reserveTypeColor(it)) }
    headerRow.activeStatusBadge(account.active)

    val toggleRow = panel.hPanel(spacing = 8)
    val hauptbuchButton = toggleRow.button(tr("Hauptbuch"), style = ButtonStyle.OUTLINEPRIMARY)
    val kassenbuchButton = if (account.isCashRegister) toggleRow.button(tr("Kassenbuch"), style = ButtonStyle.OUTLINEPRIMARY) else null
    val contentPanel = panel.vPanel(spacing = 8)

    hauptbuchButton.onClick {
        contentPanel.removeAll()
        renderHauptbuchView(contentPanel, account)
    }
    kassenbuchButton?.onClick {
        contentPanel.removeAll()
        renderKassenbuchView(contentPanel, account)
    }
    renderHauptbuchView(contentPanel, account)
}

private fun renderHauptbuchView(
    panel: SimplePanel,
    account: LedgerAccountDto,
) {
    val filterControls = panel.dateRangeFilter(toLabel = tr("Bis (JJJJ-MM-TT, optional)"))
    val loadButton = panel.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val linesPanel = panel.vPanel(spacing = 2)

    fun load() {
        linesPanel.removeAll()
        linesPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val ledger =
                guarded {
                    rpcService<IAccountingService>().getGeneralLedgerAccount(
                        account.id,
                        filterControls.parseFrom(),
                        filterControls.parseTo(),
                    )
                } ?: return@launch
            linesPanel.removeAll()

            val headerRow = linesPanel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
            headerRow.div(tr("Datum")) { width = 100.px }
            headerRow.div(tr("Beschreibung")) { addCssClasses("flex-grow-1") }
            headerRow.div(tr("Soll")) { width = 110.px }
            headerRow.div(tr("Haben")) { width = 110.px }
            headerRow.div(tr("Saldo")) { width = 110.px }

            val openingRow = linesPanel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1") }
            openingRow.div("") { width = 100.px }
            openingRow.div(tr("Eröffnungssaldo")) { addCssClasses("flex-grow-1 fst-italic text-muted") }
            openingRow.div("") { width = 110.px }
            openingRow.div("") { width = 110.px }
            openingRow.div(formatMoney(ledger.openingBalance)) { width = 110.px }

            if (ledger.lines.isEmpty()) {
                linesPanel.p(tr("Keine Buchungen im gewählten Zeitraum."))
            }
            ledger.lines.forEach { line ->
                val row = linesPanel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1") }
                row.div(line.entryDate.toString()) { width = 100.px }
                row.div(line.description) { addCssClasses("flex-grow-1") }
                row.div(if (line.side == PostingSide.DEBIT) formatMoney(line.amount) else "") { width = 110.px }
                row.div(if (line.side == PostingSide.CREDIT) formatMoney(line.amount) else "") { width = 110.px }
                row.div(formatMoney(line.runningBalance)) { width = 110.px }
            }

            val closingRow = linesPanel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
            closingRow.div("") { width = 100.px }
            closingRow.div(tr("Schlusssaldo")) { addCssClasses("flex-grow-1") }
            closingRow.div("") { width = 110.px }
            closingRow.div("") { width = 110.px }
            closingRow.div(formatMoney(ledger.closingBalance)) { width = 110.px }
        }
    }
    loadButton.onClick { load() }
    load()
}

private fun renderKassenbuchView(
    panel: SimplePanel,
    account: LedgerAccountDto,
) {
    val filterControls = panel.dateRangeFilter(toLabel = tr("Bis (JJJJ-MM-TT, optional)"))
    val loadButton = panel.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val linesPanel = panel.vPanel(spacing = 2)

    fun load() {
        linesPanel.removeAll()
        linesPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val kassenbuch =
                guarded {
                    rpcService<IAccountingService>().getKassenbuch(account.id, filterControls.parseFrom(), filterControls.parseTo())
                } ?: return@launch
            linesPanel.removeAll()

            val headerRow = linesPanel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
            headerRow.div(tr("Nr.")) { width = 50.px }
            headerRow.div(tr("Datum")) { width = 100.px }
            headerRow.div(tr("Beschreibung")) { addCssClasses("flex-grow-1") }
            headerRow.div(tr("Beleg")) { width = 110.px }
            headerRow.div(tr("Einnahme")) { width = 110.px }
            headerRow.div(tr("Ausgabe")) { width = 110.px }
            headerRow.div(tr("Saldo")) { width = 110.px }

            val openingRow = linesPanel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1") }
            openingRow.div("") { width = 50.px }
            openingRow.div("") { width = 100.px }
            openingRow.div(tr("Eröffnungssaldo")) { addCssClasses("flex-grow-1 fst-italic text-muted") }
            openingRow.div("") { width = 110.px }
            openingRow.div("") { width = 110.px }
            openingRow.div("") { width = 110.px }
            openingRow.div(formatMoney(kassenbuch.openingBalance)) { width = 110.px }

            if (kassenbuch.lines.isEmpty()) {
                linesPanel.p(tr("Keine Buchungen im gewählten Zeitraum."))
            }
            kassenbuch.lines.forEach { line ->
                val row = linesPanel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1") }
                row.div(line.kassenbuchNumber.toString()) { width = 50.px }
                row.div(line.entryDate.toString()) { width = 100.px }
                row.div(line.description) { addCssClasses("flex-grow-1") }
                row.div(line.voucherReference ?: "--") { width = 110.px }
                row.div(if (line.amountIn.toDouble() != 0.0) formatMoney(line.amountIn) else "") { width = 110.px }
                row.div(if (line.amountOut.toDouble() != 0.0) formatMoney(line.amountOut) else "") { width = 110.px }
                row.div(formatMoney(line.runningBalance)) { width = 110.px }
            }

            val closingRow = linesPanel.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
            closingRow.div("") { width = 50.px }
            closingRow.div("") { width = 100.px }
            closingRow.div(tr("Schlusssaldo")) { addCssClasses("flex-grow-1") }
            closingRow.div("") { width = 110.px }
            closingRow.div("") { width = 110.px }
            closingRow.div("") { width = 110.px }
            closingRow.div(formatMoney(kassenbuch.closingBalance)) { width = 110.px }
        }
    }
    loadButton.onClick { load() }
    load()
}

// ============================================================================================
// Journal (Grundbuch): list, detail, posting/duplicate actions
// ============================================================================================

/**
 * Deliberately shows a posting-line COUNT, not a Σ amount -- the plan's own suggested "Σamount"
 * column would require this screen to sum already-persisted [Decimal] figures purely for a
 * read-only list display, which is exactly the kind of client-side re-derivation of backend-owned
 * monetary data this wave's brief warns against (see file KDoc). [sumPostingLines] intentionally
 * stays scoped to the pre-submission confirm dialog, where the sum is the whole point of the step,
 * not an incidental display convenience.
 */
private fun renderJournalRow(
    panel: SimplePanel,
    entry: JournalEntryDto,
    onSelect: (String) -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(entry.description) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(journalEntryStatusLabel(entry.status), journalEntryStatusColor(entry.status))

    val postingsCount = entry.postings.size
    val postingsNoun = if (postingsCount == 1) gettext("1 Buchungszeile") else gettext("%1 Buchungszeilen", postingsCount)
    row.div(gettext("%1 · %2 · erfasst von %3", entry.entryDate, postingsNoun, entry.createdByDisplayName)) {
        addCssClasses("text-muted small")
    }

    val showButton = row.button(tr("Details anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    showButton.onClick { onSelect(entry.id) }
}

/**
 * D1: the lifecycle caption states the irreversibility difference in plain text beneath the header
 * (the [statusBadge] pill itself stays terse, per that design decision).
 */
private fun renderJournalEntryDetail(
    panel: SimplePanel,
    entryId: String,
    canManage: Boolean,
    onChanged: () -> Unit,
    onDuplicate: (JournalEntryDto) -> Unit,
) {
    panel.removeAll()
    panel.p(tr("Wird geladen …"))
    AppScope.launch {
        val entry = guarded { rpcService<IAccountingService>().getJournalEntry(entryId) } ?: return@launch
        panel.removeAll()

        val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        headerRow.div(entry.description) { addCssClasses("flex-grow-1 fw-bold") }
        headerRow.statusBadge(journalEntryStatusLabel(entry.status), journalEntryStatusColor(entry.status))

        val caption =
            if (entry.status == JournalEntryStatus.DRAFT) {
                gettext(
                    "Entwurf von %1 am %2 -- noch nicht Teil offizieller Berichte.",
                    entry.createdByDisplayName,
                    entry.createdAt,
                )
            } else {
                gettext("Gebucht am %1 von %2 -- unveränderlich.", entry.postedAt, entry.createdByDisplayName)
            }
        panel.div(caption) { addCssClasses("text-muted small") }
        panel.div(gettext("Datum: %1", entry.entryDate)) { addCssClasses("text-muted small") }
        entry.voucherReference?.let { panel.div(gettext("Beleg: %1", it)) { addCssClasses("text-muted small") } }

        renderDonorInfo(panel, entry)

        panel.h2(tr("Buchungszeilen")) { addCssClass("h6") }
        renderPostingsTable(panel, entry.postings)

        if (entry.status == JournalEntryStatus.DRAFT && canManage) {
            // D3: the no-edit-draft gap -- `saveDraftEntry`/`postDraftEntry` offer no update/delete
            // path, so a wrong draft can only be superseded, never corrected in place.
            val callout = panel.vPanel(spacing = 4) { addCssClasses("alert alert-light border") }
            callout.div(
                tr(
                    "Entwürfe können nicht nachträglich geändert oder gelöscht werden. Ist dieser Entwurf " +
                        "fehlerhaft, nutzen Sie „Als neuen Entwurf duplizieren\" unten, um eine korrigierte Kopie zu " +
                        "erstellen, und lassen Sie diesen Entwurf ungebucht liegen.",
                ),
            )

            val actionRow = panel.hPanel(spacing = 8)
            val postButton = actionRow.button(tr("Buchen"), style = ButtonStyle.PRIMARY)
            postButton.onClick {
                postingConfirmDialog(entry.entryDate, entry.description, entry.voucherReference, postingDtosToDisplay(entry.postings)) {
                    // The confirm modal itself hides on the first click of "Endgültig buchen", which
                    // removes its backdrop and leaves this now-stale detail view's "Buchen" button
                    // fully clickable again while the RPC call below is still in flight -- disable it
                    // for the duration so an impatient double-click cannot fire `postDraftEntry` twice
                    // concurrently. `postDraftEntry` itself is idempotent (status-checked server-side,
                    // see its KDoc), so this is defense-in-depth against a confusing double toast, not
                    // a data-integrity fix -- unlike [renderNewEntryForm]'s `postDirectButton`, where
                    // the same guard prevents an actual duplicate POSTED entry (see that button's own
                    // KDoc for why `postJournalEntry` has no such server-side idempotency check at all).
                    postButton.disabled = true
                    AppScope.launch {
                        val result = guarded { rpcService<IAccountingService>().postDraftEntry(entry.id) }
                        postButton.disabled = false
                        if (result != null) {
                            notifySuccess(tr("Buchung wurde gebucht."))
                            onChanged()
                        }
                    }
                }
            }
            val duplicateButton = actionRow.button(tr("Als neuen Entwurf duplizieren"), style = ButtonStyle.OUTLINESECONDARY)
            duplicateButton.onClick { onDuplicate(entry) }
        }
    }
}

private fun renderDonorInfo(
    panel: SimplePanel,
    entry: JournalEntryDto,
) {
    val label =
        when {
            entry.donorMemberId != null -> gettext("Spender: Mitglied %1", entry.donorMemberDisplayName)
            entry.externalDonorId != null -> gettext("Spender: %1 (extern)", entry.externalDonorDisplayName)
            entry.donorCategory == DonorCategory.ANONYMOUS -> tr("Spender: ausdrücklich anonym")
            else -> null
        }
    if (label != null) {
        val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        row.div(label) { addCssClasses("flex-grow-1") }
        entry.donorCategory?.let { row.typeBadge(donorCategoryLabel(it), donorCategoryColor(it)) }
    }

    // D3: receipt route requires a POSTED entry with a member-attributed donor (external-donor-only
    // or anonymous entries have no `/api/mailmerge/donations/{id}/receipt.pdf` route to link to).
    if (entry.status == JournalEntryStatus.POSTED && entry.donorMemberId != null) {
        val actionRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        actionRow.link(tr("Spendenbescheinigung (PDF)"), url = MailmergeHttp.receiptUrl(entry.id), target = "_blank")
        val outcomePanel = panel.vPanel(spacing = 2)

        // D5/D7: postal dispatch trigger next to the PDF link, same TREASURER/BOARD/ADMIN tier as
        // the route itself -- fetched once here rather than threaded down from the caller, since
        // this block only exists for a POSTED entry with a donor attribution in the first place.
        AppScope.launch {
            if (isPostalMailEnabled()) {
                val postalButton = actionRow.button(tr("Per Post versenden"), style = ButtonStyle.OUTLINEDANGER)
                postalButton.onClick {
                    postalDispatchConfirmDialog(
                        caption = tr("Spendenbescheinigung per Post versenden"),
                        recipientDisplayName = entry.donorMemberDisplayName ?: entry.donorMemberId.orEmpty(),
                        documentLabel = gettext("Spendenbescheinigung %1", entry.entryDate),
                    ) {
                        postalButton.disabled = true
                        outcomePanel.removeAll()
                        AppScope.launch {
                            val result = guarded { rpcService<IPostalMailService>().dispatchSpendenbescheinigungByPost(entry.id) }
                            postalButton.disabled = false
                            if (result != null) {
                                if (result.status == PostalDeliveryStatus.SENT) {
                                    notifySuccess(gettext("Brief an %1 wurde an Letterxpress übergeben.", result.recipientDisplayName))
                                } else {
                                    notifyError(tr("Postversand fehlgeschlagen."))
                                }
                                outcomePanel.renderPostalDispatchOutcome(result)
                            }
                        }
                    }
                }
            } else {
                actionRow.postalMailDisabledNotice()
            }
        }
    }
}

/** D8: two-column Soll/Haben layout -- the amount lands in exactly one of the two columns per its
 * [PostingDto.side], never a single "Betrag" column plus a side badge. */
private fun renderPostingsTable(
    panel: SimplePanel,
    postings: List<PostingDto>,
) {
    if (postings.isEmpty()) {
        panel.p(tr("Keine Buchungszeilen."))
        return
    }
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div(tr("Konto")) { addCssClasses("flex-grow-1") }
    headerRow.div(tr("Soll")) { width = 110.px }
    headerRow.div(tr("Haben")) { width = 110.px }
    headerRow.div(tr("Sphäre")) { width = 200.px }
    headerRow.div(tr("Kostenstelle")) { width = 150.px }

    postings.forEach { posting ->
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
        row.div(gettext("%1 · %2", posting.ledgerAccountNumber, posting.ledgerAccountName)) { addCssClasses("flex-grow-1") }
        row.div(if (posting.side == PostingSide.DEBIT) formatMoney(posting.amount) else "") { width = 110.px }
        row.div(if (posting.side == PostingSide.CREDIT) formatMoney(posting.amount) else "") { width = 110.px }
        val sphereCell = row.div { width = 200.px }
        sphereCell.typeBadge(sphereLabel(posting.sphere), sphereColor(posting.sphere))
        val costCenterCell =
            row.div {
                width = 150.px
                addCssClasses("text-muted small")
            }
        costCenterCell.content = posting.costCenterCode?.let { gettext("%1 · %2", it, posting.costCenterName) } ?: "--"
    }
}

// ============================================================================================
// New Journal entry form (posting-lines editor + D13 donor block)
// ============================================================================================

private class PostingLineRow(
    val panel: SimplePanel,
    val accountSelect: Select,
    val sideSelect: Select,
    val amountInput: Text,
    val sphereSelect: Select,
    val costCenterSelect: Select,
)

/**
 * Returns a prefill function ([onDuplicate] in the caller) so the D3 "Als neuen Entwurf
 * duplizieren" action on an existing draft's detail view can populate this same, already-rendered
 * form rather than opening a second one.
 */
private fun renderNewEntryForm(
    root: SimplePanel,
    accounts: List<LedgerAccountDto>,
    costCenters: List<CostCenterDto>,
    members: List<MemberSummaryDto>,
    externalDonors: List<ExternalDonorDto>,
    isPoliticalParty: Boolean,
    onSaved: () -> Unit,
): (JournalEntryDto) -> Unit {
    val panel = root.vPanel(spacing = 8)
    val dateInput = panel.text(value = todayIso(), label = tr("Datum (JJJJ-MM-TT)"))
    val descriptionInput = panel.text(label = tr("Beschreibung"))
    val voucherInput = panel.text(label = tr("Belegnummer (optional)"))

    panel.p(tr("Buchungszeilen")) { addCssClass("fw-bold") }
    val rowsPanel = panel.vPanel(spacing = 4)
    val rows = mutableListOf<PostingLineRow>()

    val accountOptions =
        listOf("" to tr("-- Konto wählen --")) + accounts.map { it.id to gettext("%1 · %2", it.accountNumber, it.name) }
    val sideOptions = PostingSide.entries.map { it.name to postingSideLabel(it) }
    // GemeinnuetzigkeitSphere KDoc: no escape-hatch literal, no default -- the blank placeholder
    // option makes "not yet chosen" visible in the actual rendered <select>, not just internally.
    val sphereOptions = listOf("" to tr("-- Sphäre wählen --")) + GemeinnuetzigkeitSphere.entries.map { it.name to sphereLabel(it) }
    val costCenterOptions = listOf("" to tr("-- keine --")) + costCenters.map { it.id to gettext("%1 · %2", it.code, it.name) }

    fun addRow(
        accountId: String = "",
        side: PostingSide = if (rows.size % 2 == 0) PostingSide.DEBIT else PostingSide.CREDIT,
        amount: String = "",
        sphere: GemeinnuetzigkeitSphere? = null,
        costCenterId: String = "",
    ) {
        renderPostingLinesRow(
            rowsPanel,
            accountOptions,
            sideOptions,
            sphereOptions,
            costCenterOptions,
            accountId,
            side,
            amount,
            sphere,
            costCenterId,
            rows,
        )
    }
    addRow()
    addRow()
    val addRowButton = panel.button(tr("Buchungszeile hinzufügen"), style = ButtonStyle.OUTLINESECONDARY)
    addRowButton.onClick { addRow() }

    // D13 donor block
    panel.p(tr("Spender-Zuordnung (optional)")) { addCssClass("fw-bold") }
    panel.div(
        if (isPoliticalParty) {
            tr("(optional -- bei Zuordnung greifen die §25-PartG-Spendenannahme-Prüfungen dieser Partei)")
        } else {
            tr("(optional, für Spendenbescheinigungen)")
        },
    ) { addCssClasses("text-muted small") }
    val donorChoiceOptions =
        listOf(
            "" to tr("-- kein Spender --"),
            "MEMBER" to tr("Mitglied"),
            "EXTERNAL" to tr("Externer Spender"),
            "ANONYMOUS" to tr("Ausdrücklich anonym"),
        )
    val donorChoiceSelect = panel.select(options = donorChoiceOptions, value = "", label = tr("Spendertyp"))

    val memberPanel = panel.vPanel(spacing = 4)
    val memberSelect = memberPanel.select(options = members.map { it.id to it.displayName }, label = tr("Mitglied"))
    val naturalPersonFirst =
        listOf(DonorCategory.GERMAN_NATURAL_PERSON, DonorCategory.EU_NATURAL_PERSON, DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON)
    val donorCategoryOrder = naturalPersonFirst + (DonorCategory.entries - naturalPersonFirst.toSet())
    val memberCategoryOptions =
        listOf("" to tr("-- Spenderkategorie wählen --")) + donorCategoryOrder.map { it.name to donorCategoryLabel(it) }
    val memberCategorySelect = memberPanel.select(options = memberCategoryOptions, value = "", label = tr("Spenderkategorie"))

    val externalPanel = panel.vPanel(spacing = 4)
    val externalSelect =
        externalPanel.select(options = externalDonors.map { it.id to it.displayName }, label = tr("Externer Spender"))

    fun applyDonorGating(choice: String?) {
        if (choice == "MEMBER") memberPanel.show() else memberPanel.hide()
        if (choice == "EXTERNAL") externalPanel.show() else externalPanel.hide()
    }
    applyDonorGating(donorChoiceSelect.value)
    donorChoiceSelect.subscribe { applyDonorGating(it) }

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    fun collectPostings(): List<PostingInput>? {
        val result = mutableListOf<PostingInput>()
        for (row in rows) {
            val accountId = row.accountSelect.value?.takeIf { it.isNotBlank() } ?: return null
            val side = row.sideSelect.value?.let { runCatching { PostingSide.valueOf(it) }.getOrNull() } ?: return null
            val amountText =
                row.amountInput.value
                    .orEmpty()
                    .trim()
            if (!Validation.isPositiveDecimal(amountText)) return null
            val sphere = row.sphereSelect.value?.let { runCatching { GemeinnuetzigkeitSphere.valueOf(it) }.getOrNull() } ?: return null
            val costCenterId = row.costCenterSelect.value?.takeIf { it.isNotBlank() }
            result.add(
                PostingInput(
                    ledgerAccountId = accountId,
                    side = side,
                    amount = amountText.toDouble().toDecimal(),
                    sphere = sphere,
                    costCenterId = costCenterId,
                ),
            )
        }
        return result
    }

    fun collectDonor(): Triple<String?, String?, DonorCategory?>? =
        when (donorChoiceSelect.value) {
            "MEMBER" -> {
                val memberId = memberSelect.value ?: return null
                val categoryValue = memberCategorySelect.value?.takeIf { it.isNotBlank() } ?: return null
                val category = runCatching { DonorCategory.valueOf(categoryValue) }.getOrNull() ?: return null
                if (category == DonorCategory.ANONYMOUS) return null
                Triple(memberId, null, category)
            }
            "EXTERNAL" -> {
                val externalId = externalSelect.value ?: return null
                Triple(null, externalId, null)
            }
            "ANONYMOUS" -> Triple(null, null, DonorCategory.ANONYMOUS)
            else -> Triple(null, null, null)
        }

    fun buildInput(): JournalEntryInput? {
        val entryDate = runCatching { LocalDate.parse(dateInput.value.orEmpty().trim()) }.getOrNull() ?: return null
        val description = descriptionInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(description)) return null
        val voucherReference = voucherInput.value?.trim()?.takeIf { it.isNotBlank() }
        val postings = collectPostings() ?: return null
        val (donorMemberId, externalDonorId, donorCategory) = collectDonor() ?: return null
        return JournalEntryInput(
            entryDate = entryDate,
            description = description,
            voucherReference = voucherReference,
            postings = postings,
            donorMemberId = donorMemberId,
            externalDonorId = externalDonorId,
            donorCategory = donorCategory,
        )
    }

    fun showValidationError() {
        errorBox.content =
            tr(
                "Bitte Datum, Beschreibung und für jede Buchungszeile Konto, Betrag und Sphäre angeben -- und, " +
                    "falls ein Spendertyp gewählt ist, dessen Felder vollständig ausfüllen.",
            )
        errorBox.show()
    }

    fun resetForm() {
        dateInput.value = todayIso()
        descriptionInput.value = null
        voucherInput.value = null
        rowsPanel.removeAll()
        rows.clear()
        addRow()
        addRow()
        donorChoiceSelect.value = ""
        applyDonorGating("")
    }

    val actionRow = panel.hPanel(spacing = 8)
    val saveDraftButton = actionRow.button(tr("Als Entwurf speichern"), style = ButtonStyle.PRIMARY)
    val postDirectButton = actionRow.button(tr("Direkt buchen"), style = ButtonStyle.OUTLINEDANGER)

    saveDraftButton.onClick {
        errorBox.hide()
        val input = buildInput()
        if (input == null) {
            showValidationError()
            return@onClick
        }
        saveDraftButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IAccountingService>().saveDraftEntry(input) }
            saveDraftButton.disabled = false
            if (result != null) {
                notifySuccess(tr("Entwurf gespeichert."))
                resetForm()
                onSaved()
            }
        }
    }

    postDirectButton.onClick {
        errorBox.hide()
        val input = buildInput()
        if (input == null) {
            showValidationError()
            return@onClick
        }
        val lines = postingInputsToDisplay(input.postings, accounts, costCenters)
        postingConfirmDialog(input.entryDate, input.description, input.voucherReference, lines) {
            // Unlike `postDraftEntry` (status-checked against an existing row -- a second concurrent
            // call is safely rejected server-side, see `renderJournalEntryDetail`'s own `postButton`
            // comment), `postJournalEntry` unconditionally inserts a brand-new POSTED, immutable entry
            // on every call with no idempotency key. The confirm modal hides on the first click of
            // "Endgültig buchen" (removing its backdrop) while this RPC call is still in flight and the
            // form beneath it is untouched (`resetForm()` only runs after a successful response) -- an
            // impatient double-click on "Direkt buchen" while the first request is still pending would
            // open a second confirm dialog for the identical input and, if confirmed, create a genuine
            // duplicate booking. Disabling the button for the round trip closes that window.
            postDirectButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IAccountingService>().postJournalEntry(input) }
                postDirectButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Buchung wurde gebucht."))
                    resetForm()
                    onSaved()
                }
            }
        }
    }

    fun prefill(entry: JournalEntryDto) {
        errorBox.hide()
        dateInput.value = entry.entryDate.toString()
        descriptionInput.value = entry.description
        voucherInput.value = entry.voucherReference
        rowsPanel.removeAll()
        rows.clear()
        entry.postings.forEach { posting ->
            addRow(
                accountId = posting.ledgerAccountId,
                side = posting.side,
                amount = posting.amount.toString(),
                sphere = posting.sphere,
                costCenterId = posting.costCenterId.orEmpty(),
            )
        }
        if (entry.postings.isEmpty()) {
            addRow()
            addRow()
        }
        when {
            entry.donorMemberId != null -> {
                donorChoiceSelect.value = "MEMBER"
                memberSelect.value = entry.donorMemberId
                memberCategorySelect.value = entry.donorCategory?.name.orEmpty()
            }
            entry.externalDonorId != null -> {
                donorChoiceSelect.value = "EXTERNAL"
                externalSelect.value = entry.externalDonorId
            }
            entry.donorCategory == DonorCategory.ANONYMOUS -> donorChoiceSelect.value = "ANONYMOUS"
            else -> donorChoiceSelect.value = ""
        }
        applyDonorGating(donorChoiceSelect.value)
        notifyInfo(gettext("Entwurf \"%1\" als neuer Entwurf übernommen -- bitte prüfen und speichern.", entry.description))
    }

    return ::prefill
}

/** Factored out of [renderNewEntryForm] purely to keep that function's length manageable -- adds
 * one posting-line row to [rowsPanel] and registers it in [rows], with a self-removing "Entfernen"
 * button (first dynamic add/remove-row pattern in this client, see file KDoc). */
private fun renderPostingLinesRow(
    rowsPanel: SimplePanel,
    accountOptions: List<Pair<String, String>>,
    sideOptions: List<Pair<String, String>>,
    sphereOptions: List<Pair<String, String>>,
    costCenterOptions: List<Pair<String, String>>,
    accountId: String,
    side: PostingSide,
    amount: String,
    sphere: GemeinnuetzigkeitSphere?,
    costCenterId: String,
    rows: MutableList<PostingLineRow>,
) {
    val rowPanel = rowsPanel.hPanel(spacing = 8) { addCssClasses("align-items-end border-bottom pb-2") }
    val accountSelect = rowPanel.select(options = accountOptions, value = accountId, label = tr("Konto"))
    val sideSelect = rowPanel.select(options = sideOptions, value = side.name, label = tr("Soll/Haben"))
    val amountInput = rowPanel.text(value = amount.ifBlank { null }, label = tr("Betrag"))
    val sphereSelect = rowPanel.select(options = sphereOptions, value = sphere?.name ?: "", label = tr("Sphäre"))
    val costCenterSelect = rowPanel.select(options = costCenterOptions, value = costCenterId, label = tr("Kostenstelle"))
    val removeButton = rowPanel.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)

    val row = PostingLineRow(rowPanel, accountSelect, sideSelect, amountInput, sphereSelect, costCenterSelect)
    rows.add(row)
    removeButton.onClick {
        rowsPanel.remove(rowPanel)
        rows.remove(row)
    }
}

// ============================================================================================
// D2: bespoke posting-confirmation modal -- shared by both post call sites
// ============================================================================================

private data class PostingLineDisplay(
    val accountLabel: String,
    val side: PostingSide,
    val amount: Decimal,
    val sphereLabel: String,
    val costCenterLabel: String?,
)

private fun postingDtosToDisplay(postings: List<PostingDto>): List<PostingLineDisplay> =
    postings.map { posting ->
        PostingLineDisplay(
            accountLabel = gettext("%1 · %2", posting.ledgerAccountNumber, posting.ledgerAccountName),
            side = posting.side,
            amount = posting.amount,
            sphereLabel = sphereLabel(posting.sphere),
            costCenterLabel = posting.costCenterCode?.let { gettext("%1 · %2", it, posting.costCenterName) },
        )
    }

private fun postingInputsToDisplay(
    postings: List<PostingInput>,
    accounts: List<LedgerAccountDto>,
    costCenters: List<CostCenterDto>,
): List<PostingLineDisplay> =
    postings.map { posting ->
        val account = accounts.find { it.id == posting.ledgerAccountId }
        val costCenter = posting.costCenterId?.let { id -> costCenters.find { it.id == id } }
        PostingLineDisplay(
            accountLabel = account?.let { gettext("%1 · %2", it.accountNumber, it.name) } ?: posting.ledgerAccountId,
            side = posting.side,
            amount = posting.amount,
            sphereLabel = sphereLabel(posting.sphere),
            costCenterLabel = costCenter?.let { gettext("%1 · %2", it.code, it.name) },
        )
    }

/**
 * A genuinely new, client-side-only computation for THIS about-to-be-submitted set of lines -- not
 * a re-derivation of any figure the server has already returned (no such figure exists yet: the
 * entry has not been posted). This is the actual point of the D2 confirm dialog (Steve Jobs' review:
 * "Making the treasurer look at the actual Soll/Haben table, balanced, before they can even press
 * the button -- that's the product"), so summing here is required, not a violation of this wave's
 * "never re-derive a persisted monetary figure" rule -- see file KDoc.
 */
private fun sumPostingLines(
    lines: List<PostingLineDisplay>,
    side: PostingSide,
): Decimal = lines.filter { it.side == side }.sumOf { it.amount.toDouble() }.toDecimal()

/**
 * D2: one bespoke modal, two call sites ([renderJournalEntryDetail]'s "Buchen" on an existing draft,
 * [renderNewEntryForm]'s "Direkt buchen"). Shows the full postings exactly as they will be posted,
 * plus a bold Σ-Soll/Σ-Haben footer -- the actual re-verification step, not decoration.
 */
private fun postingConfirmDialog(
    entryDate: LocalDate,
    description: String,
    voucherReference: String?,
    lines: List<PostingLineDisplay>,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Buchung buchen -- unveränderlich"))
    modal.div(
        tr(
            "Diese Buchung wird nach dem Bestätigen unveränderlich und Teil der offiziellen Bücher. Es gibt keine " +
                "Funktion zum nachträglichen Ändern oder Stornieren.",
        ),
    ) { addCssClass("fw-bold") }
    val entrySummary =
        if (voucherReference != null) {
            gettext("%1 -- %2 (Beleg: %3)", entryDate, description, voucherReference)
        } else {
            gettext("%1 -- %2", entryDate, description)
        }
    modal.div(entrySummary) {
        addCssClasses("text-muted small mb-2")
    }

    val headerRow = modal.hPanel(spacing = 8) { addCssClasses("fw-bold border-bottom pb-1") }
    headerRow.div(tr("Konto")) { addCssClasses("flex-grow-1") }
    headerRow.div(tr("Soll")) { width = 100.px }
    headerRow.div(tr("Haben")) { width = 100.px }
    headerRow.div(tr("Sphäre")) { width = 170.px }
    headerRow.div(tr("Kostenstelle")) { width = 130.px }

    lines.forEach { line ->
        val row = modal.hPanel(spacing = 8) { addCssClasses("border-bottom py-1") }
        row.div(line.accountLabel) { addCssClasses("flex-grow-1") }
        row.div(if (line.side == PostingSide.DEBIT) formatMoney(line.amount) else "") { width = 100.px }
        row.div(if (line.side == PostingSide.CREDIT) formatMoney(line.amount) else "") { width = 100.px }
        row.div(line.sphereLabel) {
            width = 170.px
            addCssClasses("text-muted small")
        }
        row.div(line.costCenterLabel ?: "--") {
            width = 130.px
            addCssClasses("text-muted small")
        }
    }

    val footerRow = modal.hPanel(spacing = 8) { addCssClasses("fw-bold border-top pt-1") }
    footerRow.div("Σ") { addCssClasses("flex-grow-1") }
    footerRow.div(formatMoney(sumPostingLines(lines, PostingSide.DEBIT))) { width = 100.px }
    footerRow.div(formatMoney(sumPostingLines(lines, PostingSide.CREDIT))) { width = 100.px }
    footerRow.div("") { width = 170.px }
    footerRow.div("") { width = 130.px }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Endgültig buchen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** Mirrors `CommitteesScreen.kt`'s own private `todayIso()` -- no shared date-util file exists in
 * this client (each screen that needs "today as JJJJ-MM-TT" carries its own copy). */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

// ============================================================================================
// German label/badge-color tables -- LedgerAccountType/PostingSide/JournalEntryStatus stay local
// to this file (single-screen enums), per `AccountingLabels.kt`'s own posture; GemeinnuetzigkeitSphere/
// ReserveType/DonorCategory (shared with later screens) come from that shared file instead.
// ============================================================================================

fun ledgerAccountTypeLabel(type: LedgerAccountType): String =
    when (type) {
        LedgerAccountType.ASSET -> gettext("Aktivkonto")
        LedgerAccountType.LIABILITY -> gettext("Passivkonto")
        LedgerAccountType.EQUITY -> gettext("Eigenkapitalkonto")
        LedgerAccountType.INCOME -> gettext("Ertragskonto")
        LedgerAccountType.EXPENSE -> gettext("Aufwandskonto")
    }

fun ledgerAccountTypeColor(type: LedgerAccountType): String =
    when (type) {
        LedgerAccountType.ASSET -> "primary"
        LedgerAccountType.LIABILITY -> "secondary"
        LedgerAccountType.EQUITY -> "dark"
        LedgerAccountType.INCOME -> "success"
        LedgerAccountType.EXPENSE -> "warning"
    }

/** D8: literal "Soll"/"Haben" everywhere, never "Debit"/"Credit" or the raw enum names. */
fun postingSideLabel(side: PostingSide): String =
    when (side) {
        PostingSide.DEBIT -> gettext("Soll")
        PostingSide.CREDIT -> gettext("Haben")
    }

fun postingSideColor(side: PostingSide): String =
    when (side) {
        PostingSide.DEBIT -> "primary"
        PostingSide.CREDIT -> "secondary"
    }

/** D1: `statusBadge` grammar -- a lifecycle status, not a fixed classification. */
fun journalEntryStatusLabel(status: JournalEntryStatus): String =
    when (status) {
        JournalEntryStatus.DRAFT -> gettext("Entwurf")
        JournalEntryStatus.POSTED -> gettext("Gebucht")
    }

fun journalEntryStatusColor(status: JournalEntryStatus): String =
    when (status) {
        JournalEntryStatus.DRAFT -> "warning"
        JournalEntryStatus.POSTED -> "success"
    }
