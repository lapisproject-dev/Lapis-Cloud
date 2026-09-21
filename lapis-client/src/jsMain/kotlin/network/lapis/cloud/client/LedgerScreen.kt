package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
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
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.KassenbuchDto
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.VatRate
import network.lapis.cloud.shared.domain.suggestedVatRate
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

    val root = container.dataScreenRoot()
    root.h1(tr("Kontenplan & Journal"))

    // ---- Accounts (Kontenplan) -------------------------------------------------------------
    root.h2(tr("Konten (SKR42 Kontenplan)"))
    // Design-Team-Welle 2026-09-18, Punkt 3: bis dahin gab es hier NUR die
    // "Inaktive Konten anzeigen"-Checkbox -- ein SKR42-Kontenplan hat aber von Anfang an dutzende
    // Zeilen, und wer ein bestimmtes Konto sucht, hat bisher gescrollt oder Strg+F benutzt. Muster
    // uebernommen von der Dokumentenablage-Suchwelle (`DocumentsScreen.kt`, 2026-09-16): rein
    // clientseitige Live-Filterung ohne RPC-Roundtrip pro Tastendruck, kein Server-Pagination-
    // Aufwand -- ein Kontenplan bleibt ueberschaubar gross.
    //
    // ANDERS als in `DocumentsScreen` gibt es hier bewusst KEINE Sichtbarkeitsschwelle
    // (`shouldShowDocumentSearch(count) = count >= 5`): das Suchfeld liegt in der Filterzeile, die
    // -- anders als `accountListPanel` -- bei `refreshAccounts()` NICHT geleert wird. Genau das ist
    // der Grund, warum es ausserhalb des Listen-Panels sitzt: sonst risse jeder Tastendruck das
    // eigene Eingabefeld ab und wuerfe den Fokus heraus (dieselbe Falle, die `DocumentsScreen.kt`
    // in seinem eigenen Kommentar beschreibt). Ein bedingt sichtbares Feld haette zusaetzlich nach
    // jedem Ladevorgang umgeschaltet werden muessen -- und ein leerer Kontenplan existiert
    // praktisch nicht, weil die SKR42-Grundausstattung beim Anlegen der Organisation gesetzt wird.
    val accountsFilterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val accountSearchInput = accountsFilterRow.text(label = tr("Konto suchen (Nummer oder Name)"))
    val includeInactiveAccountsCheck = accountsFilterRow.checkBox(label = tr("Inaktive Konten anzeigen"))
    val accountsRefreshButton = accountsFilterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val accountsCountsLabel = root.div().apply { addCssClasses("text-muted small") }
    val accountsStatusRegion = root.dataStatusRegion()
    val accountListPanel = root.vPanel(spacing = 6)

    root.h2(tr("Kontodetails"))
    val accountDetailPanel = root.vPanel(spacing = 10)
    accountDetailPanel.p(tr("Konto oben auswählen, um Hauptbuch/Kassenbuch zu sehen."))

    fun selectAccount(account: LedgerAccountDto) {
        renderAccountDrillDown(accountDetailPanel, account)
    }

    // Zuletzt geladene, ungefilterte Kontenliste -- die Live-Suche filtert auf dieser Kopie, statt
    // pro Tastendruck erneut `listLedgerAccounts` zu rufen.
    var loadedAccounts: List<LedgerAccountDto> = emptyList()

    // Vorwaertsreferenz: `renderAccountList` reicht das Neuladen als `onChanged` an jede Zeile
    // weiter, `refreshAccounts` ruft seinerseits `renderAccountList` -- eine echte Zyklus-Beziehung,
    // die sich mit zwei lokalen `fun`s nicht ausdruecken laesst (die zweite waere im Rumpf der
    // ersten noch nicht sichtbar).
    var refreshAccounts: () -> Unit = {}

    var accountsGeneration = 0
    var accountsLoading = false
    // Fehlerzustand des Abrufs: solange er steht, darf die Suche ihn weder wegzeichnen noch die Liste des
    // vorigen Filters darüber malen.
    var accountsFailed = false
    // Welle V1.4.26 (W2): der eine aktive Sortierzustand der Kontenliste. Startwert Kontonummer
    // aufsteigend -- genau die Ordnung, die `refreshAccounts` bisher fest verdrahtet hatte.
    var accountSort = SortState(key = ACCOUNT_SORT_NUMBER, direction = SortDirection.ASC)
    // Welche Spaltenkopf-Schaltfläche nach dem nächsten Rendern den Tastaturfokus bekommt. Bewusst ein
    // schlichtes `var` statt `SortFocusRequest`: dort löst ein ASYNCHRONER Ladevorgang das Rendern aus
    // (Mitgliederverzeichnis), hier ist das Sortieren rein clientseitig und synchron -- es gibt kein
    // Zeitfenster, in dem ein überholter Ladevorgang den Wunsch ins nächste Rendern schleppen könnte,
    // solange jedes Rendern ihn genau einmal verbraucht (siehe `renderAccountList` unten).
    var pendingAccountSortFocus: String? = null

    fun renderAccountList(query: String) {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): während eines laufenden Abrufs sagt allein die
        // Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld die zuletzt
        // geladene (jetzt veraltete) Liste unter den Ladehinweis -- zwei Zustände gleichzeitig.
        if (accountsLoading) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen.
        if (accountsFailed) return
        accountListPanel.removeAll()
        // Genau einmal verbraucht -- auch wenn dieses Rendern in einem Leer-/Fehlerzweig endet, sonst
        // würde der Fokuswunsch in ein späteres, unverwandtes Rendern durchschlagen.
        val sortFocusKey = pendingAccountSortFocus
        pendingAccountSortFocus = null
        if (loadedAccounts.isEmpty()) {
            accountsCountsLabel.content = ""
            // Welle V1.4.26 (W2): der Leertext nennt den gewählten Ausschnitt -- eine leere Liste bei
            // aktivem „nur aktive" heisst nicht „noch keine Konten angelegt".
            val text =
                if (includeInactiveAccountsCheck.value) {
                    tr("Noch keine Konten angelegt.")
                } else {
                    tr("Kein aktives Konto vorhanden. Inaktive Konten einblenden, um auch stillgelegte zu sehen.")
                }
            accountListPanel.p(text) { addCssClasses("text-muted") }
            return
        }
        val filtered = filterLedgerAccounts(loadedAccounts, query)
        // Trefferzähler jetzt IMMER, nicht nur bei nicht-leerer Suche (Richtlinie 2.4).
        accountsCountsLabel.content = gettext("%1 von %2 Konten", filtered.size, loadedAccounts.size)
        if (filtered.isEmpty()) {
            accountListPanel.p(gettext("Kein Konto passt zu \"%1\".", query.trim())) { addCssClasses("text-muted") }
            return
        }
        // UI theme redesign wave (2026-08-20): real Bootstrap table, Welle V1.4.26 (W2): `dataTable` --
        // kompakte Dichte, Kartenliste unter 768 px, klickbare Sortierköpfe für Kontonummer, Typ und
        // Name. Die Kontenliste ist VOLLSTÄNDIG geladen, deshalb sortiert sie clientseitig über eine
        // reine, testbare Funktion ([sortLedgerAccounts]) -- `listLedgerAccounts` kennt keinen
        // Sortierparameter, ein Server-Sortieren wäre hier eine Schnittstellenänderung.
        accountListPanel.dataTable(
            columns = ledgerAccountColumns(),
            rows = sortLedgerAccounts(filtered, accountSort),
            sort = accountSort,
            onSort = { clicked ->
                val next = clicked ?: accountSort
                accountSort = next
                pendingAccountSortFocus = next.key
                // Rein clientseitig: kein Nachladen, also direkt neu rendern.
                renderAccountList(accountSearchInput.value.orEmpty())
            },
            actions = { actions, account ->
                actions.renderAccountActions(account, canManage, ::selectAccount) { refreshAccounts() }
            },
            focusSortKey = sortFocusKey,
        )
    }

    refreshAccounts = {
        accountsGeneration++
        val mine = accountsGeneration
        accountListPanel.removeAll()
        accountsCountsLabel.content = ""
        accountsStatusRegion.showLoading()
        accountsLoading = true
        accountsFailed = false
        AppScope.launch {
            val accounts =
                guarded {
                    rpcService<IAccountingService>().listLedgerAccounts(activeOnly = !includeInactiveAccountsCheck.value)
                }
            if (mine != accountsGeneration) return@launch // ein neuerer Ladevorgang hat übernommen
            accountsStatusRegion.clearStatus()
            accountsLoading = false
            if (accounts == null) {
                // Vorher blieb hier ein stumm leeres Panel zurück (`?: return@launch`).
                accountsFailed = true
                loadedAccounts = emptyList()
                accountListPanel.dataErrorState(onRetry = { refreshAccounts() })
                return@launch
            }
            loadedAccounts = accounts.sortedBy { it.accountNumber }
            renderAccountList(accountSearchInput.value.orEmpty())
        }
    }
    accountsRefreshButton.onClick { refreshAccounts() }
    // Der Inaktiv-Filter wirkt sofort (Server-Abfrage) -- Guard gegen das synthetische erste Ereignis.
    var isInitialInactiveEvent = true
    includeInactiveAccountsCheck.subscribe {
        if (isInitialInactiveEvent) {
            isInitialInactiveEvent = false
            return@subscribe
        }
        refreshAccounts()
    }

    // Kein Debounce -- rein clientseitige Filterung, kein RPC pro Tastendruck (gleiche Entscheidung
    // wie in `DocumentsScreen.kt`). Der `isInitialSearchEvent`-Guard ist noetig, weil KVisions
    // `subscribe` bei der Registrierung sofort einmal synthetisch mit dem aktuellen Wert feuert --
    // ohne den Guard wuerde die Liste einmal gerendert, bevor `refreshAccounts()` ueberhaupt Daten
    // geladen hat.
    var isInitialSearchEvent = true
    accountSearchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        renderAccountList(value.orEmpty())
    }

    refreshAccounts()

    if (canManage) {
        root.h2(tr("Neues Konto anlegen"))
        renderAccountCreationForm(root) { refreshAccounts() }
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
    val journalFilterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val journalSearchInput = journalFilterRow.text(label = tr("Buchung suchen (Beschreibung)"))
    val journalStatusSegmentHost = journalFilterRow.simplePanel()
    val journalDateFilter = journalFilterRow.dateRangeFilter(fromLabel = tr("Von"), toLabel = tr("Bis"))
    val journalRefreshButton = journalFilterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    // Das Datumsformat steht im Hinweis, nie im Label (W4c): ein Beispielsatz stimmt in jeder Sprache.
    root.div(tr("Beispiel: 2026-03-14.")) { addCssClasses("text-muted small") }
    val journalCountsLabel = root.div().apply { addCssClasses("text-muted small") }
    val journalStatusRegion = root.dataStatusRegion()
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

    // Welle V1.4.26 (W2): die geladene Journal-Seite wird gehalten, damit Suche und Sortierung
    // clientseitig darauf wirken können, ohne pro Tastendruck erneut `listJournal` zu rufen.
    var loadedJournal: List<JournalEntryDto> = emptyList()
    var journalStatusFilter: JournalEntryStatus? = null
    var journalSearchTerm = ""
    var journalSort = SortState(key = JOURNAL_SORT_DATE, direction = SortDirection.DESC)
    var pendingJournalSortFocus: String? = null
    var journalGeneration = 0
    var journalLoading = false
    // Fehlerzustand des Abrufs: siehe `accountsFailed`.
    var journalFailed = false

    fun renderJournalList() {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): während eines laufenden Abrufs sagt allein die
        // Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld die zuletzt
        // geladene (jetzt veraltete) Liste unter den Ladehinweis -- zwei Zustände gleichzeitig.
        if (journalLoading) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen.
        if (journalFailed) return
        journalListPanel.removeAll()
        val sortFocusKey = pendingJournalSortFocus
        pendingJournalSortFocus = null
        if (loadedJournal.isEmpty()) {
            journalCountsLabel.content = ""
            journalListPanel.p(journalEmptyText(journalStatusFilter, journalDateFilter.hasRange())) {
                addCssClasses("text-muted")
            }
            return
        }
        val filtered = filterJournalEntries(loadedJournal, journalSearchTerm)
        journalCountsLabel.content =
            dataCountText(shown = filtered.size, loaded = loadedJournal.size, filtered = journalSearchTerm.isNotBlank())
        if (filtered.isEmpty()) {
            journalListPanel.p(gettext("Keine Buchung passt zu \"%1\".", journalSearchTerm.trim())) {
                addCssClasses("text-muted")
            }
            return
        }
        // UI theme redesign wave (2026-08-20): real Bootstrap table, Welle V1.4.26 (W2): `dataTable`
        // mit kompakter Dichte, Kartenliste unter 768 px und clientseitig sortierbaren Spaltenköpfen
        // (Datum, Beschreibung) -- `listJournal` liefert die gefilterte Menge vollständig und kennt
        // keinen Sortierparameter.
        journalListPanel.dataTable(
            columns = journalEntryColumns(),
            rows = sortJournalEntries(filtered, journalSort),
            sort = journalSort,
            onSort = { clicked ->
                val next = clicked ?: journalSort
                journalSort = next
                pendingJournalSortFocus = next.key
                renderJournalList()
            },
            // Erster Klick auf „Datum" zeigt die neuesten Buchungen zuerst -- die Reihenfolge, in der
            // ein Schatzmeister ein Journal liest (gleiche Entscheidung wie „Beitritt" im Roster).
            sortOptions = JOURNAL_SORT_OPTIONS,
            actions = { actions, entry ->
                val showButton = actions.tableActionButton("fas fa-eye", tr("Details anzeigen"))
                showButton.onClick {
                    selectJournalEntry(entry.id)
                    refreshJournalDetail()
                }
            },
            focusSortKey = sortFocusKey,
        )
    }

    fun refreshJournal() {
        journalGeneration++
        val mine = journalGeneration
        journalListPanel.removeAll()
        journalCountsLabel.content = ""
        journalStatusRegion.showLoading()
        journalLoading = true
        journalFailed = false
        val status = journalStatusFilter
        AppScope.launch {
            val entries =
                guarded {
                    rpcService<IAccountingService>().listJournal(journalDateFilter.parseFrom(), journalDateFilter.parseTo(), status)
                }
            if (mine != journalGeneration) return@launch // ein neuerer Ladevorgang hat übernommen
            journalStatusRegion.clearStatus()
            journalLoading = false
            if (entries == null) {
                // Vorher blieb hier ein stumm leeres Panel zurück (`?: return@launch`).
                journalFailed = true
                loadedJournal = emptyList()
                journalListPanel.dataErrorState(onRetry = { refreshJournalFn?.invoke() })
                return@launch
            }
            loadedJournal = entries
            renderJournalList()
        }
    }
    refreshJournalFn = ::refreshJournal

    // Der Status-`select` mit „Alle Status" + drei Lebenszyklus-Werten ist ein `segmentedControl`
    // geworden: gegenseitig ausschliessend, mit Aktivzustand und `aria-pressed` (R20/R48).
    journalStatusSegmentHost.segmentedControl(
        options = listOf(null to tr("Alle")) + JournalEntryStatus.entries.map { it to journalEntryStatusLabel(it) },
        selected = journalStatusFilter,
        ariaLabel = tr("Status"),
    ) { status ->
        journalStatusFilter = status
        refreshJournal()
    }
    journalRefreshButton.onClick { refreshJournal() }

    var isInitialJournalSearchEvent = true
    journalSearchInput.subscribe { value ->
        if (isInitialJournalSearchEvent) {
            isInitialJournalSearchEvent = false
            return@subscribe
        }
        // Kein Debounce -- rein clientseitige Filterung über die bereits geladene Seite, gleiche
        // Entscheidung wie bei der Kontensuche darüber.
        journalSearchTerm = value.orEmpty()
        renderJournalList()
    }
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
                    settings?.vatEnabled ?: false,
                    settings?.isKleinunternehmer ?: false,
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
 *
 * **Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6)** added a FOURTH mapping,
 * `donationIncomeAccountId` -- where [DonationPostingBridge][network.lapis.cloud.server.rpc.DonationPostingBridge]
 * books a gateway donation's brutto amount. Kept on THIS SAME section (not duplicated onto
 * `PaymentGatewaySettingsScreen.kt`, which calls this function too, `internal` visibility) --
 * single source of truth for "which ledger accounts does a payment channel book into", same
 * reasoning [OrganizationSettingsService.updateOrganizationSettings] already gives for treating
 * all four fields identically (plain configuration, ADMIN-only).
 */
internal fun renderPaymentAccountMappingSection(
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
                        "Bankkonto: %1 · Gebührenkonto: %2 · Beitragserlöskonto: %3 · Spendenerlöskonto: %4 · Veranstaltungserlöskonto: %5 (%6) · Reisekosten-Aufwandskonto: %7 · Ehrenamtspauschalen-Aufwandskonto: %8",
                        accounts.find { it.id == settings.paymentBankAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.paymentFeeAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.contributionIncomeAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.donationIncomeAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.eventIncomeAccountId }?.name ?: unconfigured,
                        sphereLabel(settings.eventIncomeSphere),
                        accounts.find { it.id == settings.travelExpenseAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.volunteerAllowanceAccountId }?.name ?: unconfigured,
                    ),
                )
                // Welle V1.4.21 (Offene Posten): eigener Absatz statt Erweiterung des obigen
                // %1..%8-Formatstrings -- dessen msgid bleibt in allen Katalogen unveraendert.
                panel.p(
                    gettext(
                        "Forderungskonto (Debitoren): %1 · Verbindlichkeitenkonto (Kreditoren): %2",
                        accounts.find { it.id == settings.receivablesAccountId }?.name ?: unconfigured,
                        accounts.find { it.id == settings.payablesAccountId }?.name ?: unconfigured,
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

            // W4c: alle Zuordnungen sind ein Formular. Jedes Feld ist freiwillig -- die leere Auswahl IST eine gültige Antwort
            // (sie setzt die Zuordnung zurück auf `null`), deshalb kein Stern und keine Legende.
            val form = panel.lapisForm()
            val bankField =
                form.selectField(label = tr("Bankkonto"), options = accountOptions, value = settings.paymentBankAccountId.orEmpty())
            val feeField =
                form.selectField(label = tr("Gebührenkonto"), options = accountOptions, value = settings.paymentFeeAccountId.orEmpty())
            val incomeField =
                form.selectField(
                    label = tr("Beitragserlöskonto"),
                    options = accountOptions,
                    value = settings.contributionIncomeAccountId.orEmpty(),
                )
            val donationIncomeField =
                form.selectField(
                    label = tr("Spendenerlöskonto"),
                    options = accountOptions,
                    value = settings.donationIncomeAccountId.orEmpty(),
                )
            // Review MAJOR fix -- previously reachable only via a direct RPC call, no UI field at
            // all (see EventFeePostingBridge KDoc); this panel already establishes the pattern every
            // OTHER account-mapping field uses.
            val eventIncomeField =
                form.selectField(
                    label = tr("Veranstaltungserlöskonto"),
                    options = accountOptions,
                    value = settings.eventIncomeAccountId.orEmpty(),
                )
            val eventIncomeSphereOptions = GemeinnuetzigkeitSphere.entries.map { it.name to sphereLabel(it) }
            val eventIncomeSphereField =
                form.selectField(
                    label = tr("Sphäre der Veranstaltungserlöse"),
                    options = eventIncomeSphereOptions,
                    value = settings.eventIncomeSphere.name,
                )
            // Welle V1.4.11 "Reisekostenabrechnung" -- erste EXPENSE-Kontenzuordnung dieses
            // Panels (jede andere ist INCOME/ASSET), siehe TravelExpensePostingBridge KDoc.
            val travelExpenseField =
                form.selectField(
                    label = tr("Reisekosten-Aufwandskonto"),
                    options = accountOptions,
                    value = settings.travelExpenseAccountId.orEmpty(),
                )
            // Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- zweite EXPENSE-Kontenzuordnung
            // dieses Panels, siehe VolunteerAllowancePostingBridge KDoc.
            val volunteerAllowanceField =
                form.selectField(
                    label = tr("Ehrenamtspauschalen-Aufwandskonto"),
                    options = accountOptions,
                    value = settings.volunteerAllowanceAccountId.orEmpty(),
                )

            // Welle V1.4.21 "Offene Posten" -- Forderungs-/Verbindlichkeitenkonto (Sammelkonten der
            // Debitoren-/Kreditorenbuchhaltung). NUR Typfilter (ASSET bzw. LIABILITY), keine
            // SKR42-Nummernlogik im Client -- der Server validiert (`receivables_account_not_asset_type`/
            // `payables_account_not_liability_type`). Ohne beide Zuordnungen bucht kein offener Posten.
            form.panel.p(
                tr(
                    "Ohne Forderungs- und Verbindlichkeitenkonto werden offene Posten angelegt, aber nicht gebucht.",
                ),
            ) { addCssClasses("text-muted small") }
            val receivablesOptions =
                listOf("" to tr("(nicht konfiguriert)")) +
                    accounts.filter { it.type == LedgerAccountType.ASSET }.map { it.id to "${it.accountNumber} · ${it.name}" }
            val payablesOptions =
                listOf("" to tr("(nicht konfiguriert)")) +
                    accounts.filter { it.type == LedgerAccountType.LIABILITY }.map { it.id to "${it.accountNumber} · ${it.name}" }
            val receivablesField =
                form.selectField(
                    label = tr("Forderungskonto (Debitoren)"),
                    options = receivablesOptions,
                    value = settings.receivablesAccountId.orEmpty(),
                )
            val payablesField =
                form.selectField(
                    label = tr("Verbindlichkeitenkonto (Kreditoren)"),
                    options = payablesOptions,
                    value = settings.payablesAccountId.orEmpty(),
                )
            // Welle V1.4.22 Audit-Nachtrag (MAJOR-3): `updateOrganizationSettings` lehnt eine widersprüchliche Zuordnung mit
            // `ConflictException` ab -- deren Meldung erreicht den Browser nie (Kilua RPC überträgt nur den Typ). Dieselbe Regel,
            // hier vor dem Round-Trip: `paymentAccountMappingProblem`. Eine Querregel (sie betrifft DREI Felder), die Meldung
            // steht in der Sammelfläche.
            form.crossFieldRule(
                focusOn = bankField.control.input as? Widget,
                watch = listOf(bankField, receivablesField, payablesField).mapNotNull { it.control.input as? Widget },
            ) {
                val problem =
                    paymentAccountMappingProblem(
                        PaymentAccountMapping(
                            defaultBankAccountId = bankField.value.takeIf { it.isNotBlank() },
                            receivablesAccountId = receivablesField.value.takeIf { it.isNotBlank() },
                            payablesAccountId = payablesField.value.takeIf { it.isNotBlank() },
                        ),
                    )
                if (problem == null) FieldCheck.Ok else FieldCheck.Invalid(resolvedAttributeText(problem))
            }

            // Welle V1.4.5.2 "DATEV-Format-Export". Kein Fehlertext bei leerem Zustand -- eine
            // Anweisung (Zhuo/Jobs): der Steuerberater vergibt beide Nummern, nicht der Verein
            // selbst. Diese zwei Felder gehören hierher, weil dies bereits die einzige
            // ADMIN-Schreiboberfläche für `organization_settings` ist.
            form.panel.p(
                tr("Berater- und Mandantennummer erhalten Sie von Ihrem Steuerberater."),
            ) { addCssClasses("text-muted small") }
            // Review-Fund (2026-09): "Feld leer" und "Feld enthält keine ganze Zahl" sind zwei Zustände -- `.toIntOrNull()` allein
            // machte beide zu `null`, und updateOrganizationSettings ersetzt den ganzen Feldsatz, ein Tippfehler (`1OO1`) setzte also
            // eine gesetzte Beraternummer still zurück. Siehe [parseDatevNumberInput]. Die Regel sieht nur nicht-leere Werte;
            // ein leeres Feld ist [DatevNumberInput.Empty] und gültig.
            val datevBeraterField =
                form.textField(
                    label = tr("DATEV-Beraternummer"),
                    value = settings.datevBeraterNummer?.toString().orEmpty(),
                    rule = {
                        if (parseDatevNumberInput(it) is DatevNumberInput.Invalid) {
                            FieldCheck.Invalid(gettext("DATEV-Beraternummer ist keine gültige Zahl."))
                        } else {
                            FieldCheck.Ok
                        }
                    },
                )
            val datevMandantField =
                form.textField(
                    label = tr("DATEV-Mandantennummer"),
                    value = settings.datevMandantNummer?.toString().orEmpty(),
                    rule = {
                        if (parseDatevNumberInput(it) is DatevNumberInput.Invalid) {
                            FieldCheck.Invalid(gettext("DATEV-Mandantennummer ist keine gültige Zahl."))
                        } else {
                            FieldCheck.Ok
                        }
                    },
                )

            val saveButton = Button(tr("Kontenzuordnung speichern"), style = ButtonStyle.PRIMARY)
            form.buttons(primary = saveButton)
            saveButton.onClick {
                // `form.submit` = Prüfung + Doppelklick-Schutz (`runGuardedAction`, `disabled` im `finally` zurückgesetzt -- auch bei
                // einem Abbruch der Coroutine, Review Round 4, 2026-08-19).
                form.submit(saveButton) {
                    val beraterInput = parseDatevNumberInput(datevBeraterField.value)
                    val mandantInput = parseDatevNumberInput(datevMandantField.value)
                    val selectedEventIncomeSphere =
                        eventIncomeSphereField.value
                            .let { runCatching { GemeinnuetzigkeitSphere.valueOf(it) }.getOrNull() }
                            ?: GemeinnuetzigkeitSphere.ZWECKBETRIEB
                    val result =
                        guarded {
                            rpcService<IOrganizationSettingsService>().updateOrganizationSettings(
                                settings.toInputWithPaymentAccountMapping(
                                    paymentBankAccountId = bankField.value.takeIf { it.isNotBlank() },
                                    paymentFeeAccountId = feeField.value.takeIf { it.isNotBlank() },
                                    contributionIncomeAccountId = incomeField.value.takeIf { it.isNotBlank() },
                                    donationIncomeAccountId = donationIncomeField.value.takeIf { it.isNotBlank() },
                                    eventIncomeAccountId = eventIncomeField.value.takeIf { it.isNotBlank() },
                                    eventIncomeSphere = selectedEventIncomeSphere,
                                    datevBeraterNummer = (beraterInput as? DatevNumberInput.Valid)?.value,
                                    datevMandantNummer = (mandantInput as? DatevNumberInput.Valid)?.value,
                                    travelExpenseAccountId = travelExpenseField.value.takeIf { it.isNotBlank() },
                                    volunteerAllowanceAccountId = volunteerAllowanceField.value.takeIf { it.isNotBlank() },
                                    receivablesAccountId = receivablesField.value.takeIf { it.isNotBlank() },
                                    payablesAccountId = payablesField.value.takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    if (result != null) {
                        notifySuccess(tr("Kontenzuordnung gespeichert."))
                        load()
                    }
                }
            }
        }
    }
    load()
}

/**
 * Result of parsing ONE DATEV-Berater-/Mandantennummer text field -- distinguishes "field left
 * empty" ([Empty], a valid state: the number is simply not configured yet) from "field contains
 * something that is not a whole number" ([Invalid], an error the user must be told about instead
 * of silently saving `null`). `internal` (not `private`) so [LedgerScreenTest] can cover this
 * directly without a DOM harness -- same testability reasoning [toInputWithPaymentAccountMapping]'s
 * own KDoc gives.
 */
internal sealed interface DatevNumberInput {
    data object Empty : DatevNumberInput

    data class Valid(
        val value: Int,
    ) : DatevNumberInput

    data object Invalid : DatevNumberInput
}

/** See [DatevNumberInput] KDoc. Trims first so a value that is only whitespace counts as [DatevNumberInput.Empty],
 * not [DatevNumberInput.Invalid]. */
internal fun parseDatevNumberInput(raw: String?): DatevNumberInput {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return DatevNumberInput.Empty
    val parsed = trimmed.toIntOrNull() ?: return DatevNumberInput.Invalid
    return DatevNumberInput.Valid(parsed)
}

/**
 * `internal` (not `private`) so [LedgerScreenTest] can cover the "never silently drop/reset a
 * field" contract directly -- same testability reasoning [PoliticianScreen.kt]'s own
 * `toInputWithPoliticianRankingEnabled` KDoc gives for its `internal` visibility.
 *
 * Audit-Fund M2: baut [OrganizationSettingsInput] nicht mehr selbst Feld für Feld, sondern über
 * [toInput] plus `copy(...)` der Felder, die dieser Bildschirm wirklich bearbeitet. Damit kann ein
 * NEUES Settings-Feld hier nicht mehr vergessen werden -- siehe [toInput] KDoc für die fünf
 * Vorfälle, die genau daran lagen.
 */
internal fun OrganizationSettingsDto.toInputWithPaymentAccountMapping(
    paymentBankAccountId: String?,
    paymentFeeAccountId: String?,
    contributionIncomeAccountId: String?,
    donationIncomeAccountId: String?,
    eventIncomeAccountId: String?,
    eventIncomeSphere: GemeinnuetzigkeitSphere,
    datevBeraterNummer: Int?,
    datevMandantNummer: Int?,
    travelExpenseAccountId: String?,
    volunteerAllowanceAccountId: String?,
    receivablesAccountId: String?,
    payablesAccountId: String?,
) = toInput().copy(
    // Genau die Felder, die die "Kontenzuordnung Zahlungsverkehr"-Sektion dieses Bildschirms selbst
    // bearbeitet (eigene Selects/Textfelder). Alles andere -- inklusive `isKleinunternehmer` und
    // `receivableDunningEnabled`, die hier KEIN Formularfeld haben -- kommt unverändert aus [toInput].
    paymentBankAccountId = paymentBankAccountId,
    paymentFeeAccountId = paymentFeeAccountId,
    contributionIncomeAccountId = contributionIncomeAccountId,
    donationIncomeAccountId = donationIncomeAccountId,
    eventIncomeAccountId = eventIncomeAccountId,
    eventIncomeSphere = eventIncomeSphere,
    datevBeraterNummer = datevBeraterNummer,
    datevMandantNummer = datevMandantNummer,
    travelExpenseAccountId = travelExpenseAccountId,
    volunteerAllowanceAccountId = volunteerAllowanceAccountId,
    receivablesAccountId = receivablesAccountId,
    payablesAccountId = payablesAccountId,
)

// ============================================================================================
// Accounts (Kontenplan)
// ============================================================================================

/**
 * Reines, DOM-unabhaengiges Filter-Praedikat der Kontenplan-Live-Suche -- testbar ohne
 * Rendering-Harness (analog [filterDocuments] in `DocumentsScreen.kt`).
 *
 * Gesucht wird ueber **Kontonummer UND Kontoname**, weil beide Zugriffswege real vorkommen: wer den
 * SKR42 kennt, tippt "4200"; wer ihn nicht kennt, tippt "Spenden". `accountNumber` ist ein `String`
 * (nicht `Int`), Teiltreffer wie "42" auf "4200" funktionieren deshalb ohne Sonderbehandlung.
 *
 * `ignoreCase = true`: der Gelegenheitsnutzer (Schatzmeister, einmal im Monat) tippt nicht auf
 * Grossschreibung.
 */
internal fun filterLedgerAccounts(
    accounts: List<LedgerAccountDto>,
    query: String,
): List<LedgerAccountDto> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return accounts
    return accounts.filter {
        it.accountNumber.contains(trimmed, ignoreCase = true) || it.name.contains(trimmed, ignoreCase = true)
    }
}

/** Sortierschlüssel der Kontenliste (Welle V1.4.26 / W2) -- die drei Spalten, die eine Ordnung tragen. */
internal const val ACCOUNT_SORT_NUMBER = "accountNumber"
internal const val ACCOUNT_SORT_NAME = "name"
internal const val ACCOUNT_SORT_TYPE = "type"

/**
 * Clientseitige Sortierung der VOLLSTÄNDIG geladenen Kontenliste -- pur und testbar (siehe
 * `LedgerScreenTest`). Kontonummern werden als **Text** verglichen, nicht als Zahl: ein SKR42-Konto ist
 * eine Kontonummer, keine Menge, und kann führende Nullen tragen (`0400`) -- eine numerische Sortierung
 * würde `0400` und `400` zusammenwerfen und bei nicht rein numerischen Nummern gar nicht greifen. Der
 * Typ wird über sein übersetztes Label sortiert, damit die Reihenfolge der Sprache des Lesers folgt und
 * nicht der Deklarationsreihenfolge des Enums. Sekundärschlüssel ist immer die Kontonummer, damit die
 * Ordnung bei gleichen Werten stabil bleibt.
 */
internal fun sortLedgerAccounts(
    accounts: List<LedgerAccountDto>,
    sort: SortState,
): List<LedgerAccountDto> {
    val byKey: Comparator<LedgerAccountDto> =
        when (sort.key) {
            ACCOUNT_SORT_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ACCOUNT_SORT_TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { ledgerAccountTypeLabel(it.type) }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.accountNumber }
        }
    val comparator = byKey.thenBy(String.CASE_INSENSITIVE_ORDER) { it.accountNumber }
    return accounts.sortedWith(if (sort.direction == SortDirection.ASC) comparator else comparator.reversed())
}

/**
 * Spalten der Kontenliste / Kartenliste. Konto (Nummer · Name) ist die Identität der Zeile und damit der
 * Kartentitel; es ist nach Nummer sortierbar, weil das die Ordnung ist, in der ein Schatzmeister einen
 * Kontenplan liest. Der Name bekommt eine eigene, alphabetisch sortierbare Spalte -- vorher steckte er
 * mit der Nummer in einer Zelle und war damit gar nicht sortierbar.
 */
private fun ledgerAccountColumns(): List<DataColumn<LedgerAccountDto>> =
    listOf(
        DataColumn(
            title = tr("Konto"),
            primary = true,
            sortKey = ACCOUNT_SORT_NUMBER,
            cell = { container, account ->
                container.span(gettext("%1 · %2", account.accountNumber, account.name)) { addCssClass("fw-bold") }
            },
        ),
        textColumn(title = tr("Name"), sortKey = ACCOUNT_SORT_NAME) { account: LedgerAccountDto -> account.name },
        DataColumn(
            title = tr("Typ"),
            sortKey = ACCOUNT_SORT_TYPE,
            cell = { container, account ->
                container.typeBadge(ledgerAccountTypeLabel(account.type), ledgerAccountTypeColor(account.type))
            },
        ),
        DataColumn(
            title = tr("Status"),
            cell = { container, account -> container.activeStatusBadge(account.active) },
        ),
        textColumn(title = tr("Details")) { account: LedgerAccountDto -> ledgerAccountMetaText(account) },
    )

/** Die Meta-Zeile eines Kontos („Kontenklasse 4 · Kasse") -- pur, siehe `LedgerScreenTest`. */
internal fun ledgerAccountMetaText(account: LedgerAccountDto): String {
    val metaParts = mutableListOf(gettext("Kontenklasse %1", account.accountClass))
    account.reserveType?.let { metaParts.add(reserveTypeLabel(it)) }
    if (account.isCashRegister) metaParts.add(gettext("Kasse"))
    return metaParts.joinToString(" · ")
}

/**
 * Zeilenaktionen der Kontenliste. Rollen-Gate (`canManage`) und Statusbedingung (`account.active`) sowie
 * der Bestätigungsdialog sind gegenüber der Welle vom 2026-09-18 unverändert.
 *
 * Design-Team-Welle 2026-09-18: vorher zwei Volltext-Knoepfe in einem `flex-wrap`-hPanel,
 * die in der schmalen Aktionsspalte untereinander umbrachen und jede Kontenzeile auf zwei
 * Knopfhoehen aufblaehten. Jetzt Icon-Knoepfe nebeneinander -- Tooltip/`aria-label` tragen
 * die Bedeutung (siehe `DataScreenLayout.tableActionButton` KDoc zu Don Normans Einwand).
 */
private fun Container.renderAccountActions(
    account: LedgerAccountDto,
    canManage: Boolean,
    onSelect: (LedgerAccountDto) -> Unit,
    onChanged: () -> Unit,
) {
    val actionRow = tableActionGroup()
    val showButton = actionRow.tableActionButton("fas fa-eye", tr("Details anzeigen"))
    showButton.onClick { onSelect(account) }

    if (!canManage || !account.active) return
    val deactivateButton = actionRow.tableActionButton("fas fa-ban", tr("Deaktivieren"), ButtonStyle.OUTLINEDANGER)
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
            // Der Zeilenknopf sperrt sich für die Dauer des Aufrufs (vorher `runGuardedAction(null)`: ein Schutz nur über die Einmal-Sperre des Dialogs).
            runGuardedAction(deactivateButton) {
                val result = guarded { rpcService<IAccountingService>().deactivateLedgerAccount(account.id) }
                if (result != null) {
                    notifyInfo(tr("Konto wurde deaktiviert."))
                    onChanged()
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
internal fun renderAccountCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
    val typeOptions = LedgerAccountType.entries.map { it.name to ledgerAccountTypeLabel(it) }
    val form = root.lapisForm()
    val numberField = form.textField(label = tr("Kontonummer (SKR42)"), required = true)
    val nameField = form.textField(label = tr("Name"), required = true)
    // Die Grenze steht als Platzhalter im Hinweis, nie im msgid (W4c): eine Konstante im msgid sagte sie in acht Katalogen.
    val classField =
        form.textField(
            label = tr("Kontenklasse"),
            required = true,
            hint = tr("Erste Ziffer der Kontonummer."),
            rule = { FormRules.intInRange(it, min = 0, max = 9) },
        )
    val typeField =
        form.selectField(label = tr("Kontotyp"), options = typeOptions, value = LedgerAccountType.ASSET.name, required = true)
    val reserveOptions = listOf("" to tr("-- keine Rücklage --")) + ReserveType.entries.map { it.name to reserveTypeLabel(it) }
    val reserveField =
        form.selectField(
            label = tr("Rücklagenart"),
            options = reserveOptions,
            value = "",
            hint = tr("Nur bei Kontotyp „Eigenkapitalkonto\" wählbar."),
        )
    val cashField =
        form.checkField(label = tr("Kasse (Kassenbuch-fähig)"), hint = tr("Nur bei Kontotyp „Aktivkonto\" wählbar."))

    // D11: gesperrt-statt-versteckt, und zurückgesetzt, sobald der Kontotyp verlassen wird. Werte laufen über LapisField, nie am
    // Control vorbei (`setValue` / `reset` räumen auch einen stehenden Fehler).
    fun applyTypeGating(typeValue: String?) {
        val isEquity = typeValue == LedgerAccountType.EQUITY.name
        val isAsset = typeValue == LedgerAccountType.ASSET.name
        (reserveField.control as Select).disabled = !isEquity
        if (!isEquity) {
            reserveField.setValue("")
            reserveField.validate(force = false)
        }
        (cashField.control as CheckBox).disabled = !isAsset
        if (!isAsset) cashField.reset()
    }
    applyTypeGating(typeField.value)
    typeField.subscribe { applyTypeGating(it) }

    val createButton = Button(tr("Konto anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)
    createButton.onClick {
        form.submit(createButton) {
            val accountNumber = numberField.value.trim()
            val name = nameField.value.trim()
            val accountClass = classField.value.trim().toInt()
            val type = LedgerAccountType.valueOf(typeField.value)
            val reserveType =
                if (type == LedgerAccountType.EQUITY) {
                    reserveField.value.takeIf { it.isNotBlank() }?.let { ReserveType.valueOf(it) }
                } else {
                    null
                }
            val isCashRegister = type == LedgerAccountType.ASSET && (cashField.control as CheckBox).value
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
            if (result != null) {
                notifySuccess(gettext("Konto \"%1 · %2\" wurde angelegt.", accountNumber, name))
                numberField.reset()
                nameField.reset()
                classField.reset()
                reserveField.setValue("")
                cashField.reset()
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

internal fun renderHauptbuchView(
    panel: SimplePanel,
    account: LedgerAccountDto,
) {
    val filterControls = panel.dateRangeFilter(fromLabel = tr("Von"), toLabel = tr("Bis"))
    panel.div(tr("Beispiel: 2026-03-14.")) { addCssClasses("text-muted small") }
    val loadButton = panel.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    // Welle V1.4.27 (W3): dataSection instead of a stuck "Wird geladen ..." when the load fails.
    val section =
        panel.dataSection<GeneralLedgerDto>(
            isEmpty = { false },
            load = {
                guarded {
                    rpcService<IAccountingService>().getGeneralLedgerAccount(
                        account.id,
                        filterControls.parseFrom(),
                        filterControls.parseTo(),
                    )
                }
            },
            render = { body, ledger -> renderHauptbuchLines(body, ledger) },
        )
    loadButton.onClick { section.reload() }
    section.reload()
}

private val HAUPTBUCH_HEADERS =
    listOf(
        TableHeader(title = tr("Datum")),
        TableHeader(title = tr("Beschreibung")),
        TableHeader(title = tr("Soll"), numeric = true),
        TableHeader(title = tr("Haben"), numeric = true),
        TableHeader(title = tr("Saldo"), numeric = true),
    )

/**
 * The Hauptbuch as a DOCUMENT (Welle V1.4.27, W3): a [reportTable] with the running balance -- a row means
 * nothing without its neighbours, so no sort, no card list. Opening and closing balance are balance rows.
 */
internal fun renderHauptbuchLines(
    linesPanel: Container,
    ledger: GeneralLedgerDto,
) {
    // The account header ("1200 · Bank" + badges) stands right above: the caption stays the table's accessible name
    // but is not shown a second time (audit MINOR-2).
    val report =
        linesPanel.reportTable(
            caption = gettext("%1 · %2", ledger.accountNumber, ledger.name),
            headers = HAUPTBUCH_HEADERS,
            captionVisible = false,
        )
    report.reportRows(generalLedgerRows(ledger), HAUPTBUCH_HEADERS)
}

internal fun renderKassenbuchView(
    panel: SimplePanel,
    account: LedgerAccountDto,
) {
    val filterControls = panel.dateRangeFilter(fromLabel = tr("Von"), toLabel = tr("Bis"))
    panel.div(tr("Beispiel: 2026-03-14.")) { addCssClasses("text-muted small") }
    val loadButton = panel.button(tr("Laden"), style = ButtonStyle.OUTLINESECONDARY)
    val section =
        panel.dataSection<KassenbuchDto>(
            isEmpty = { false },
            load = {
                guarded {
                    rpcService<IAccountingService>().getKassenbuch(account.id, filterControls.parseFrom(), filterControls.parseTo())
                }
            },
            render = { body, kassenbuch -> renderKassenbuchLines(body, kassenbuch) },
        )
    loadButton.onClick { section.reload() }
    section.reload()
}

private val KASSENBUCH_HEADERS =
    listOf(
        TableHeader(title = tr("Nr.")),
        TableHeader(title = tr("Datum")),
        TableHeader(title = tr("Beschreibung")),
        TableHeader(title = tr("Beleg")),
        TableHeader(title = tr("Einnahme"), numeric = true),
        TableHeader(title = tr("Ausgabe"), numeric = true),
        TableHeader(title = tr("Saldo"), numeric = true),
    )

/** The Kassenbuch as a document: **Nr.** is the GoBD sequence, so the table is rendered in server order, never sorted. */
internal fun renderKassenbuchLines(
    linesPanel: Container,
    kassenbuch: KassenbuchDto,
) {
    val report =
        linesPanel.reportTable(
            caption = gettext("%1 · %2", kassenbuch.accountNumber, kassenbuch.name),
            headers = KASSENBUCH_HEADERS,
            captionVisible = false,
        )
    report.reportRows(kassenbuchRows(kassenbuch), KASSENBUCH_HEADERS)
}

// ============================================================================================
// Journal (Grundbuch): list, detail, posting/duplicate actions
// ============================================================================================

/** Sortierschlüssel der Journal-Liste (Welle V1.4.26 / W2). */
internal const val JOURNAL_SORT_DATE = "entryDate"
internal const val JOURNAL_SORT_DESCRIPTION = "description"

/** Erster Klick auf „Datum" zeigt die neuesten Buchungen zuerst; jede andere Spalte beginnt aufsteigend. */
private val JOURNAL_SORT_OPTIONS =
    SortOptions(
        allowUnsorted = false,
        firstDirection = { key -> if (key == JOURNAL_SORT_DATE) SortDirection.DESC else SortDirection.ASC },
    )

/** Clientseitige Suche über die geladene Journal-Seite (pur, siehe `LedgerScreenTest`): Beschreibung. */
internal fun filterJournalEntries(
    entries: List<JournalEntryDto>,
    search: String,
): List<JournalEntryDto> {
    val term = search.trim()
    if (term.isEmpty()) return entries
    return entries.filter { it.description.contains(term, ignoreCase = true) }
}

/**
 * Clientseitige Sortierung der geladenen Journal-Seite -- pur und testbar (siehe `LedgerScreenTest`).
 * Sekundärschlüssel ist immer das Buchungsdatum, damit die Ordnung bei gleichen Beschreibungen stabil
 * bleibt. Das Datum wird über seine ISO-Textform verglichen (`LocalDate.toString()` ist `JJJJ-MM-TT` und
 * damit lexikografisch = chronologisch) -- so braucht diese reine Funktion keine Datumsarithmetik.
 */
internal fun sortJournalEntries(
    entries: List<JournalEntryDto>,
    sort: SortState,
): List<JournalEntryDto> {
    val byKey: Comparator<JournalEntryDto> =
        when (sort.key) {
            JOURNAL_SORT_DESCRIPTION -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.description }
            else -> compareBy { it.entryDate.toString() }
        }
    val comparator = byKey.thenBy { it.entryDate.toString() }
    return entries.sortedWith(if (sort.direction == SortDirection.ASC) comparator else comparator.reversed())
}

/**
 * „Noch keine Daten" der Journal-Liste, getrennt nach dem Ausschnitt, den der Leser gewählt hat (R41):
 * ein leeres Journal ist eine andere Aussage als „in diesem Zeitraum" oder „mit diesem Status". Pur,
 * siehe `LedgerScreenTest`.
 */
internal fun journalEmptyText(
    status: JournalEntryStatus?,
    hasDateRange: Boolean,
): String =
    when {
        status != null && hasDateRange ->
            gettext("Keine Buchung mit dem Status \"%1\" im gewählten Zeitraum.", journalEntryStatusLabel(status))
        status != null -> gettext("Keine Buchung mit dem Status \"%1\".", journalEntryStatusLabel(status))
        hasDateRange -> gettext("Keine Buchungen im gewählten Zeitraum.")
        else -> gettext("Noch keine Buchungen erfasst.")
    }

/**
 * Die Meta-Zeile einer Buchung („01.03.2026 · 2 Buchungszeilen · erfasst von …") -- pur, siehe
 * `LedgerSortAndEmptyStateTest`.
 *
 * Deliberately shows a posting-line COUNT, not a Σ amount -- the plan's own suggested "Σamount"
 * column would require this screen to sum already-persisted [Decimal] figures purely for a
 * read-only list display, which is exactly the kind of client-side re-derivation of backend-owned
 * monetary data this wave's brief warns against (see file KDoc). [sumPostingLines] intentionally
 * stays scoped to the pre-submission confirm dialog, where the sum is the whole point of the step,
 * not an incidental display convenience.
 */
internal fun journalEntryMetaText(entry: JournalEntryDto): String {
    val postingsCount = entry.postings.size
    val postingsNoun = if (postingsCount == 1) gettext("1 Buchungszeile") else gettext("%1 Buchungszeilen", postingsCount)
    return gettext("%1 · %2 · erfasst von %3", entry.entryDate, postingsNoun, entry.createdByDisplayName)
}

/**
 * Spalten der Journal-Liste / Kartenliste. Die Beschreibung ist die Identität der Buchung (Kartentitel);
 * das Buchungsdatum bekommt eine eigene, sortierbare Spalte -- vorher steckte es in der Meta-Zelle und war
 * damit weder sortierbar noch als Datum erkennbar. Die Aktion ist ein Icon-Knopf statt des vorherigen
 * Volltext-Knopfes „Details anzeigen" in der Tabellenzeile (R38).
 */
private fun journalEntryColumns(): List<DataColumn<JournalEntryDto>> =
    listOf(
        DataColumn(
            title = tr("Buchung"),
            primary = true,
            sortKey = JOURNAL_SORT_DESCRIPTION,
            cell = { container, entry -> container.span(entry.description) { addCssClass("fw-bold") } },
        ),
        textColumn(title = tr("Datum"), numeric = true, sortKey = JOURNAL_SORT_DATE) { entry: JournalEntryDto ->
            entry.entryDate.toString()
        },
        DataColumn(
            title = tr("Status"),
            cell = { container, entry ->
                container.statusBadge(journalEntryStatusLabel(entry.status), journalEntryStatusColor(entry.status))
            },
        ),
        DataColumn(
            title = tr("Details"),
            cell = { container, entry -> container.span(journalEntryMetaText(entry)) { addCssClasses("text-muted small") } },
        ),
    )

/**
 * D1: the lifecycle caption states the irreversibility difference in plain text beneath the header
 * (the [statusBadge] pill itself stays terse, per that design decision).
 */
internal fun renderJournalEntryDetail(
    panel: SimplePanel,
    entryId: String,
    canManage: Boolean,
    onChanged: () -> Unit,
    onDuplicate: (JournalEntryDto) -> Unit,
) {
    panel.removeAll()
    // Audit V1.4.27 (F): `dataSection` instead of "Wird geladen ..." forever (`?: return@launch`) when the load fails --
    // a failed detail is an error state in the page with "Erneut versuchen".
    panel
        .dataSection<JournalEntryDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IAccountingService>().getJournalEntry(entryId) } },
            render = { body, entry -> renderJournalEntryDetailBody(body, entry, canManage, onChanged, onDuplicate) },
        ).reload()
}

private fun renderJournalEntryDetailBody(
    panel: SimplePanel,
    entry: JournalEntryDto,
    canManage: Boolean,
    onChanged: () -> Unit,
    onDuplicate: (JournalEntryDto) -> Unit,
) {
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
                // The confirm modal hides on the first click of "Endgültig buchen" (one-shot, see [postingConfirmDialog]), which
                // removes its backdrop and leaves this now-stale detail view's "Buchen" button clickable again while the RPC call
                // is still in flight -- `runGuardedAction` locks it for the round trip. `postDraftEntry` itself is idempotent
                // (status-checked server-side), so this is defense in depth against a confusing double toast.
                runGuardedAction(postButton) {
                    val result = guarded { rpcService<IAccountingService>().postDraftEntry(entry.id) }
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
                        outcomePanel.removeAll()
                        runGuardedAction(postalButton) {
                            val result = guarded { rpcService<IPostalMailService>().dispatchSpendenbescheinigungByPost(entry.id) }
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

/**
 * D8: two-column Soll/Haben layout -- the amount lands in exactly one of the two columns per its
 * [PostingDto.side], never a single "Betrag" column plus a side badge.
 *
 * Audit V1.4.27 (E): a [reportTable], not a `dataTable`. The posting lines of an entry are a DOCUMENT (the
 * Soll/Haben pairing only makes sense across the rows, the order is the order of the entry) and
 * `renderPostingConfirmTable` / the audit log's snapshot show the very same lines as a report table -- one grammar
 * for the same object; a `dataTable` here turned unconditionally into a card list below 768 px. The caption
 * ("Buchungszeilen") names the table, so the detail view has no heading of its own above it.
 */
internal fun renderPostingsTable(
    panel: Container,
    postings: List<PostingDto>,
) {
    if (postings.isEmpty()) {
        panel.p(tr("Keine Buchungszeilen.")) { addCssClasses("text-muted") }
        return
    }
    val report = panel.reportTable(caption = tr("Buchungszeilen"), headers = JOURNAL_POSTING_HEADERS)
    report.reportRows(journalPostingRows(postings), JOURNAL_POSTING_HEADERS)
}

private val JOURNAL_POSTING_HEADERS =
    listOf(
        TableHeader(title = tr("Konto")),
        TableHeader(title = tr("Soll"), numeric = true),
        TableHeader(title = tr("Haben"), numeric = true),
        TableHeader(title = tr("Sphäre")),
        TableHeader(title = tr("Kostenstelle")),
        // Welle V1.4.13 "USt-Voranmeldung" -- Text, nie eine Farbe (siehe vatRateLabel KDoc).
        // Bewusst ohne Betrag zusaetzlich -- der Betrag oben ist BRUTTO inkl. USt, siehe
        // NonprofitComplianceReportsScreen.kt fuer den ausdruecklichen Brutto-Hinweis.
        TableHeader(title = tr("USt")),
    )

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
    /** Welle V1.4.13 -- `null` when USt is not usable for this organization (`!vatEnabled ||
     *  isKleinunternehmer`), matching the server's own [VatRate.UNCLASSIFIED] normalization for
     *  that case (`AccountingService.insertJournalEntry`'s `vatActive` gate). */
    val vatRateSelect: Select?,
    /** V1.4.30 (W4c): alle Felder dieser Zeile in Rendering-Reihenfolge -- der Entfernen-Knopf meldet sie ALLE ab
     *  ([LapisForm.unregister]), sonst blockierte das Feld einer entfernten Zeile das Absenden unsichtbar für immer. */
    val fields: List<LapisField>,
    /** Das Betragsfeld -- Quelle des Saldostreifens. */
    val amountField: LapisField,
    /** Feld und aufgelöster Basistext ("Betrag"), für die Neu-Nummerierung nach Hinzufügen/Entfernen ("Betrag · Zeile 2"). */
    val labelled: List<Pair<LapisField, String>>,
    /** Welle V1.4.13 -- `true` once the treasurer has touched [vatRateSelect] themselves; the
     *  sphere-change auto-suggestion (see [renderPostingLinesRow]) then stops overwriting their
     *  choice. */
    var vatRateUserTouched: Boolean = false,
)

/** "Betrag · Zeile 2": der Zeilenteil ist ein Platzhalter, nie eine Zahl im msgid. */
private fun postingFieldLabel(
    base: String,
    line: Int,
): String = gettext("%1 · Zeile %2", base, line)

private const val DASH = "—"

/** Ganze Cent eines Betragstextes; `null`, wenn er leer oder nach [FormRules.postingAmount] ungültig ist. */
private fun amountTextCents(text: String): Long? {
    // Ein Betrag über der Obergrenze ist ungültig (das Feld zeigt den Fehler) und darf nie in eine Summe eingehen -- sonst sättigt
    // `centsOf` bei 1.0E20 auf Long.MAX_VALUE und der Streifen behauptete einen ausgeglichenen Saldo mit einer erfundenen Zahl.
    if (FormRules.postingAmount(text) !is FieldCheck.Ok) return null
    return (parseAmountInput(text, allowZero = false, enforceMaxAmount = false) as? AmountInput.Valid)?.let { centsOf(it.value.toDouble()) }
}

/**
 * `Decimal` ist auf JS ein `Double`: `0.1 + 0.2` ergibt `0.30000000000000004`. Summen und Vergleiche laufen deshalb in ganzen Cent
 * (jeder Betrag hat höchstens zwei Nachkommastellen, siehe [FormRules.postingAmount]); erst die Anzeige geht zurück zu Euro.
 */
private fun centsOf(amount: Double): Long = kotlin.math.round(amount * 100.0).toLong()

/**
 * Ganze Cent als Geldtext -- über [formatMoney], dasselbe Format wie der Bestätigungsdialog und die Journaltabelle ("100.5 €", ohne
 * Auffüllung auf zwei Stellen, Dezimalpunkt): IM SELBEN Buchungsablauf gilt EIN Format (Audit V1.4.30, M3). Vorher stand im Saldostreifen
 * "1200,00 €" und im Bestätigungsdialog "1200 €". Das globale [formatMoney] ist bewusst NICHT geändert (bekannte Lücke der ganzen
 * Oberfläche, würde die Golden-Tests der Berichte kippen). Die Summe läuft trotzdem in ganzen Cent; erst die Anzeige geht zu Euro.
 */
internal fun moneyFromCents(cents: Long): String = formatMoney((cents / 100.0).toDecimal())

/**
 * Reine Anzeige unter dem Zeilenblock (V1.4.30, W4c): "informieren, nicht sperren". **Nie eine alte Summe**: sobald irgendein
 * Betrag leer oder ungültig ist, steht überall ein Gedankenstrich (dieselbe Lehre wie der grüne Türbanner der W4b-Audits). Kein
 * `role="alert"`, kein Blocker, keine Ampelfarbe. Rein und DOM-frei: bekommt die Rohtexte der Betragsfelder und die Seiten.
 */
internal fun postingBalanceText(
    amounts: List<String>,
    sides: List<PostingSide>,
): String {
    val cents = amounts.map { amountTextCents(it) }
    // Ohne Zeile gibt es nichts zu bilanzieren: ein "Soll 0,00 · Haben 0,00" behauptete einen ausgeglichenen Nullsaldo.
    if (cents.isEmpty() || cents.any { it == null }) return gettext("Soll %1 · Haben %2 · Differenz %3", DASH, DASH, DASH)
    var debit = 0L
    var credit = 0L
    cents.zip(sides).forEach { (value, side) -> if (side == PostingSide.DEBIT) debit += value ?: 0L else credit += value ?: 0L }
    return gettext(
        "Soll %1 · Haben %2 · Differenz %3",
        moneyFromCents(debit),
        moneyFromCents(credit),
        moneyFromCents(
            kotlin.math.abs(
                debit - credit,
            ),
        ),
    )
}

/**
 * Spiegel der Serverbedingungen von `JournalEntryBalance.validateBalanced`, die `saveDraftEntry` NICHT prüft: mindestens zwei
 * Zeilen, mindestens eine Soll- und eine Habenzeile, Σ Soll = Σ Haben (auf Cent). Rein, DOM-frei, testbar. Der Server bleibt die
 * Autorität; das hier spart den Round-Trip und nennt die Differenz. **Nur vor "Direkt buchen"**: ein Entwurf darf unausgeglichen
 * sein (der Server erlaubt es) -- eine Bilanzregel im Formular verböte eine heute erlaubte Handlung. `null` = ausgeglichen.
 */
internal fun journalPostingBalanceProblem(postings: List<PostingInput>): String? {
    if (postings.size < 2) return gettext("Eine Buchung braucht mindestens zwei Buchungszeilen.")
    val debitLines = postings.filter { it.side == PostingSide.DEBIT }
    val creditLines = postings.filter { it.side == PostingSide.CREDIT }
    if (debitLines.isEmpty() || creditLines.isEmpty()) {
        return gettext("Eine Buchung braucht mindestens eine Sollzeile und eine Habenzeile.")
    }
    val difference = debitLines.sumOf { centsOf(it.amount.toDouble()) } - creditLines.sumOf { centsOf(it.amount.toDouble()) }
    if (difference != 0L) {
        return gettext("Soll und Haben stimmen nicht überein (Differenz %1).", moneyFromCents(kotlin.math.abs(difference)))
    }
    return null
}

/** Die reine Kopie von `buildInput()` des Formulars (V1.4.30): erreichbar für Tests, ohne DOM. `null` bei ungültigem Datum/leerer Beschreibung. */
internal fun buildJournalEntryInput(
    dateText: String,
    description: String,
    voucherText: String?,
    postings: List<PostingInput>,
    donor: Triple<String?, String?, DonorCategory?>,
): JournalEntryInput? {
    val entryDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull() ?: return null
    val trimmedDescription = description.trim()
    if (!Validation.isNonBlank(trimmedDescription)) return null
    val (donorMemberId, externalDonorId, donorCategory) = donor
    return JournalEntryInput(
        entryDate = entryDate,
        description = trimmedDescription,
        voucherReference = voucherText?.trim()?.takeIf { it.isNotBlank() },
        postings = postings,
        donorMemberId = donorMemberId,
        externalDonorId = externalDonorId,
        donorCategory = donorCategory,
    )
}

/**
 * Returns a prefill function ([onDuplicate] in the caller) so the D3 "Als neuen Entwurf
 * duplizieren" action on an existing draft's detail view can populate this same, already-rendered
 * form rather than opening a second one.
 *
 * V1.4.30 (W4c): the form is a [LapisForm]. Posting lines are a table for the eyes (one visible column-header row, the field
 * NAME stays on every control for screen readers) and a form for everything else; the balance strip below them informs, it does
 * not block.
 */
internal fun renderNewEntryForm(
    root: SimplePanel,
    accounts: List<LedgerAccountDto>,
    costCenters: List<CostCenterDto>,
    members: List<MemberSummaryDto>,
    externalDonors: List<ExternalDonorDto>,
    isPoliticalParty: Boolean,
    // Welle V1.4.13 "USt-Voranmeldung" -- mirrors the server's own `vatActive` gate
    // (`AccountingService.insertJournalEntry`): the per-line USt control is only rendered while
    // both hold, exactly the condition under which the server would keep a sent rate anyway.
    vatEnabled: Boolean,
    isKleinunternehmer: Boolean,
    onSaved: () -> Unit,
): (JournalEntryDto) -> Unit {
    val vatUsable = vatEnabled && !isKleinunternehmer
    val form = root.lapisForm()
    val panel = form.panel
    val dateField =
        form.textField(
            label = tr("Buchungsdatum"),
            value = todayIso(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(it) },
        )
    val descriptionField =
        form.textField(
            label = tr("Beschreibung"),
            required = true,
            rule = { FormRules.maxLength(it, MAX_JOURNAL_DESCRIPTION_LENGTH) },
        )
    val voucherField =
        form.textField(label = tr("Belegnummer"), rule = { FormRules.maxLength(it, MAX_JOURNAL_VOUCHER_LENGTH) })

    panel.p(tr("Buchungszeilen")) { addCssClass("fw-bold") }
    // Die EINE sichtbare Spaltenkopfzeile über dem Block (unter 768 px ausgeblendet, dort trägt jede Karte ihre Beschriftung selbst).
    // `aria-hidden`: die echte Beschriftung hängt am Steuerelement.
    val headerRow =
        panel.hPanel(spacing = 8) {
            addCssClasses("lapis-posting-header text-muted small")
            setAttribute("aria-hidden", "true")
        }

    fun headerCell(
        text: String,
        required: Boolean,
    ) {
        headerRow.div {
            addCssClass("lapis-posting-cell")
            span(text)
            if (required) span(" *") { addCssClass("lapis-required-mark") }
        }
    }
    headerCell(tr("Konto"), required = true)
    headerCell(tr("Soll/Haben"), required = true)
    headerCell(tr("Betrag"), required = true)
    headerCell(tr("Sphäre"), required = true)
    headerCell(tr("Kostenstelle"), required = false)
    if (vatUsable) headerCell(tr("USt"), required = false)
    headerRow.div { addCssClass("lapis-posting-remove") }

    val rowsPanel = panel.vPanel(spacing = 4)
    val rows = mutableListOf<PostingLineRow>()

    val accountOptions =
        listOf("" to tr("-- Konto wählen --")) + accounts.map { it.id to gettext("%1 · %2", it.accountNumber, it.name) }
    val sideOptions = PostingSide.entries.map { it.name to postingSideLabel(it) }
    // GemeinnuetzigkeitSphere KDoc: no escape-hatch literal, no default -- the blank placeholder
    // option makes "not yet chosen" visible in the actual rendered <select>, not just internally.
    val sphereOptions = listOf("" to tr("-- Sphäre wählen --")) + GemeinnuetzigkeitSphere.entries.map { it.name to sphereLabel(it) }
    val costCenterOptions = listOf("" to tr("-- keine --")) + costCenters.map { it.id to gettext("%1 · %2", it.code, it.name) }
    // UNCLASSIFIED is NEVER offered here -- see VatRate KDoc "kein Guard, aber auch keine
    // Wahlmoeglichkeit fuer 'nie klassifiziert'". A treasurer either names a real classification
    // or the sphere-derived suggestion pre-fills one; there is no user-facing "unset" choice.
    val vatRateOptions = listOf(VatRate.NOT_SUBJECT, VatRate.ZERO, VatRate.REDUCED, VatRate.STANDARD).map { it.name to vatRateLabel(it) }

    val addRowButton = panel.button(tr("Buchungszeile hinzufügen"), style = ButtonStyle.OUTLINESECONDARY)
    val balanceStrip = panel.div { addCssClass("lapis-balance-strip") }

    // Erst nach `form.buttons(...)` entscheidet die Grammatik über die Sterne; eine später hinzugefügte Zeile markiert ihre Pflicht-
    // Labels selbst -- sonst fehlte der Stern genau dort, wo der Behandler ihn braucht.
    var formFinished = false

    fun updateBalance() {
        balanceStrip.content =
            postingBalanceText(
                amounts = rows.map { it.amountField.value },
                sides =
                    rows.map { row ->
                        row.sideSelect.value?.let { runCatching { PostingSide.valueOf(it) }.getOrNull() }
                            ?: PostingSide.DEBIT
                    },
            )
    }

    fun rowsChanged() {
        rows.forEachIndexed { index, row ->
            row.labelled.forEach { (field, base) -> field.relabel(postingFieldLabel(base, index + 1)) }
        }
        updateBalance()
    }

    fun addRow(
        accountId: String = "",
        side: PostingSide = if (rows.size % 2 == 0) PostingSide.DEBIT else PostingSide.CREDIT,
        amount: String = "",
        sphere: GemeinnuetzigkeitSphere? = null,
        costCenterId: String = "",
        vatRate: VatRate? = null,
    ) {
        renderPostingLinesRow(
            form,
            rowsPanel,
            accountOptions,
            sideOptions,
            sphereOptions,
            costCenterOptions,
            vatRateOptions,
            vatUsable,
            accountId,
            side,
            amount,
            sphere,
            costCenterId,
            vatRate,
            rows,
            markRequiredNow = formFinished,
            onBalanceChanged = ::updateBalance,
            onRowRemoved = ::rowsChanged,
        )
        rowsChanged()
    }

    /** Alle Zeilen abmelden UND entfernen: ohne `unregister` sammelte jeder Speichervorgang tote Felder an, und das Formular ließe
     *  sich nach dem zweiten Speichern nie wieder absenden. */
    fun clearRows() {
        rows.forEach { row -> row.fields.forEach { form.unregister(it) } }
        rowsPanel.removeAll()
        rows.clear()
    }
    addRow()
    addRow()
    addRowButton.onClick { addRow() }

    // D13 donor block
    panel.p(tr("Spender-Zuordnung")) { addCssClass("fw-bold") }
    panel.div(
        if (isPoliticalParty) {
            tr("Bei Zuordnung greifen die §25-PartG-Spendenannahme-Prüfungen dieser Partei.")
        } else {
            tr("Für Spendenbescheinigungen.")
        },
    ) { addCssClasses("text-muted small") }
    val donorChoiceOptions =
        listOf(
            "" to tr("-- kein Spender --"),
            "MEMBER" to tr("Mitglied"),
            "EXTERNAL" to tr("Externer Spender"),
            "ANONYMOUS" to tr("Ausdrücklich anonym"),
        )
    val donorChoiceField = form.selectField(label = tr("Spendertyp"), options = donorChoiceOptions, value = "")

    // Die Auswahlfelder des Spenderblocks werden über ihren CONTAINER ein-/ausgeblendet (`LapisField.setVisible` kann nur Text). Ihre
    // Pflicht gilt nur, wenn der Spendertyp sie braucht: eine Feldpflicht (`required`) blockierte das Absenden mit einem unsichtbaren
    // Fehler -- deshalb Querregeln unten, die den Spendertyp mitlesen.
    val memberPanel = panel.vPanel(spacing = 4)
    // Die Pflicht dieser drei Felder hängt am Spendertyp (Querregeln unten) -- ohne Hinweis läse ein sternloses Feld unter der sichtbaren
    // Legende "* Pflichtfeld" wie "freiwillig", obwohl es das Absenden blockiert.
    val donorRequiredHint = tr("Pflichtangabe für diesen Spendertyp.")
    val memberField =
        form.selectField(
            label = tr("Mitglied"),
            options = members.map { it.id to it.displayName },
            hint = donorRequiredHint,
            host = memberPanel,
        )
    val naturalPersonFirst =
        listOf(DonorCategory.GERMAN_NATURAL_PERSON, DonorCategory.EU_NATURAL_PERSON, DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON)
    val donorCategoryOrder = naturalPersonFirst + (DonorCategory.entries - naturalPersonFirst.toSet())
    val memberCategoryOptions =
        listOf("" to tr("-- Spenderkategorie wählen --")) + donorCategoryOrder.map { it.name to donorCategoryLabel(it) }
    val memberCategoryField =
        form.selectField(
            label = tr("Spenderkategorie"),
            options = memberCategoryOptions,
            value = "",
            hint = donorRequiredHint,
            host = memberPanel,
        )

    val externalPanel = panel.vPanel(spacing = 4)
    val externalField =
        form.selectField(
            label = tr("Externer Spender"),
            options = externalDonors.map { it.id to it.displayName },
            hint = donorRequiredHint,
            host = externalPanel,
        )

    fun applyDonorGating(choice: String?) {
        if (choice == "MEMBER") memberPanel.show() else memberPanel.hide()
        if (choice == "EXTERNAL") externalPanel.show() else externalPanel.hide()
    }
    applyDonorGating(donorChoiceField.value)
    donorChoiceField.subscribe { applyDonorGating(it) }

    val donorWidgets = listOf(donorChoiceField, memberField, memberCategoryField, externalField).mapNotNull { it.control.input as? Widget }
    form.crossFieldRule(focusOn = memberField.control.input as? Widget, watch = donorWidgets) {
        if (donorChoiceField.value == "MEMBER" && memberField.value.isBlank()) {
            FieldCheck.Invalid(gettext("Bitte ein Mitglied wählen."))
        } else {
            FieldCheck.Ok
        }
    }
    form.crossFieldRule(focusOn = memberCategoryField.control.input as? Widget, watch = donorWidgets) {
        when {
            donorChoiceField.value != "MEMBER" -> FieldCheck.Ok
            memberCategoryField.value.isBlank() -> FieldCheck.Invalid(gettext("Bitte eine Spenderkategorie wählen."))
            memberCategoryField.value == DonorCategory.ANONYMOUS.name ->
                FieldCheck.Invalid(gettext("Für ein Mitglied kann die Spenderkategorie nicht anonym sein."))
            else -> FieldCheck.Ok
        }
    }
    form.crossFieldRule(focusOn = externalField.control.input as? Widget, watch = donorWidgets) {
        if (donorChoiceField.value == "EXTERNAL" && externalField.value.isBlank()) {
            FieldCheck.Invalid(gettext("Bitte einen externen Spender wählen."))
        } else {
            FieldCheck.Ok
        }
    }

    fun collectPostings(): List<PostingInput>? {
        val result = mutableListOf<PostingInput>()
        for (row in rows) {
            val accountId = row.accountSelect.value?.takeIf { it.isNotBlank() } ?: return null
            val side = row.sideSelect.value?.let { runCatching { PostingSide.valueOf(it) }.getOrNull() } ?: return null
            val parsedAmount =
                parseAmountInput(row.amountField.value, allowZero = false, enforceMaxAmount = false) as? AmountInput.Valid ?: return null
            val sphere = row.sphereSelect.value?.let { runCatching { GemeinnuetzigkeitSphere.valueOf(it) }.getOrNull() } ?: return null
            val costCenterId = row.costCenterSelect.value?.takeIf { it.isNotBlank() }
            // Welle V1.4.13: hidden control (row.vatRateSelect == null, USt not usable for this
            // organization) -> UNCLASSIFIED, exactly the server's own vatActive-gate normalization
            // -- see AccountingService.insertJournalEntry KDoc.
            val vatRate =
                row.vatRateSelect?.value?.let { runCatching { VatRate.valueOf(it) }.getOrNull() } ?: VatRate.UNCLASSIFIED
            result.add(
                PostingInput(
                    ledgerAccountId = accountId,
                    side = side,
                    // V1.4.30 (W4c): die Feldregel ([FormRules.postingAmount]) lehnt mehr als zwei Nachkommastellen jetzt VORHER ab
                    // ("10,005" wurde bisher still zu 10,01 gebucht). Diese Rundung kann nur noch ein Rundungsartefakt glätten --
                    // sie bleibt als Netz stehen, weil auch `prefill()` diesen Pfad nimmt.
                    amount = Validation.roundToTwoDecimalPlaces(parsedAmount.value.toDouble()).toDecimal(),
                    sphere = sphere,
                    costCenterId = costCenterId,
                    vatRate = vatRate,
                ),
            )
        }
        return result
    }

    fun collectDonor(): Triple<String?, String?, DonorCategory?>? =
        when (donorChoiceField.value) {
            "MEMBER" -> {
                val memberId = memberField.value.takeIf { it.isNotBlank() } ?: return null
                val categoryValue = memberCategoryField.value.takeIf { it.isNotBlank() } ?: return null
                val category = runCatching { DonorCategory.valueOf(categoryValue) }.getOrNull() ?: return null
                if (category == DonorCategory.ANONYMOUS) return null
                Triple(memberId, null, category)
            }
            "EXTERNAL" -> {
                val externalId = externalField.value.takeIf { it.isNotBlank() } ?: return null
                Triple(null, externalId, null)
            }
            "ANONYMOUS" -> Triple(null, null, DonorCategory.ANONYMOUS)
            else -> Triple(null, null, null)
        }

    fun buildInput(): JournalEntryInput? {
        val postings = collectPostings() ?: return null
        val donor = collectDonor() ?: return null
        return buildJournalEntryInput(dateField.value, descriptionField.value, voucherField.value, postings, donor)
    }

    fun resetForm() {
        dateField.reset()
        dateField.setValue(todayIso())
        descriptionField.reset()
        voucherField.reset()
        clearRows()
        addRow()
        addRow()
        donorChoiceField.setValue("")
        donorChoiceField.validate(force = false)
        applyDonorGating("")
        form.clearFormError()
    }

    val saveDraftButton = Button(tr("Als Entwurf speichern"), style = ButtonStyle.PRIMARY)
    // "Direkt buchen" ist unwiderruflich: eigene Zone UNTER der Knopfzeile (Richtlinie 2.5 / R27).
    val postDirectButton = Button(tr("Direkt buchen"), style = ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = saveDraftButton, destructive = postDirectButton)
    formFinished = true

    saveDraftButton.onClick {
        // Ein Entwurf darf unausgeglichen sein (der Server erlaubt es): KEINE Bilanzregel hier.
        form.submit(saveDraftButton) {
            val input = buildInput() ?: return@submit
            val result = guarded { rpcService<IAccountingService>().saveDraftEntry(input) }
            if (result != null) {
                notifySuccess(tr("Entwurf gespeichert."))
                resetForm()
                onSaved()
            }
        }
    }

    postDirectButton.onClick {
        if (!form.validateAndReport()) return@onClick
        val input = buildInput() ?: return@onClick
        // Die drei Serverbedingungen, die `saveDraftEntry` nicht prüft, VOR dem Bestätigungsdialog: kein Dialog, kein RPC.
        val problem = journalPostingBalanceProblem(input.postings)
        if (problem != null) {
            form.showFormError(problem)
            return@onClick
        }
        val lines = postingInputsToDisplay(input.postings, accounts, costCenters)
        postingConfirmDialog(input.entryDate, input.description, input.voucherReference, lines) {
            // Unlike `postDraftEntry` (status-checked against an existing row -- a second concurrent
            // call is safely rejected server-side), `postJournalEntry` unconditionally inserts a brand-new POSTED, immutable entry
            // on every call with no idempotency key. The confirm modal hides on the first click of "Endgültig buchen" (removing
            // its backdrop) while this RPC call is still in flight -- `runBusy` locks the button for the round trip (an impatient
            // double-click would otherwise open a second confirm dialog for the identical input and create a genuine duplicate).
            form.runBusy(postDirectButton) {
                val result = guarded { rpcService<IAccountingService>().postJournalEntry(input) }
                if (result != null) {
                    notifySuccess(tr("Buchung wurde gebucht."))
                    resetForm()
                    onSaved()
                }
            }
        }
    }

    fun prefill(entry: JournalEntryDto) {
        form.clearFormError()
        dateField.setValue(entry.entryDate.toString())
        descriptionField.setValue(entry.description)
        voucherField.setValue(entry.voucherReference)
        clearRows()
        entry.postings.forEach { posting ->
            addRow(
                accountId = posting.ledgerAccountId,
                side = posting.side,
                amount = posting.amount.toString(),
                sphere = posting.sphere,
                costCenterId = posting.costCenterId.orEmpty(),
                vatRate = posting.vatRate.takeIf { it != VatRate.UNCLASSIFIED },
            )
        }
        if (entry.postings.isEmpty()) {
            addRow()
            addRow()
        }
        when {
            entry.donorMemberId != null -> {
                donorChoiceField.setValue("MEMBER")
                memberField.setValue(entry.donorMemberId)
                memberCategoryField.setValue(entry.donorCategory?.name.orEmpty())
            }
            entry.externalDonorId != null -> {
                donorChoiceField.setValue("EXTERNAL")
                externalField.setValue(entry.externalDonorId)
            }
            entry.donorCategory == DonorCategory.ANONYMOUS -> donorChoiceField.setValue("ANONYMOUS")
            else -> donorChoiceField.setValue("")
        }
        applyDonorGating(donorChoiceField.value)
        // Ein gesetzter Wert räumt einen stehenden Fehler nicht von selbst (siehe `LapisField.setValue`).
        form.fields.forEach { it.validate(force = false) }
        notifyInfo(gettext("Entwurf \"%1\" als neuer Entwurf übernommen -- bitte prüfen und speichern.", entry.description))
    }

    return ::prefill
}

/** Factored out of [renderNewEntryForm] purely to keep that function's length manageable -- adds
 * one posting-line row to [rowsPanel] and registers it in [rows], with a self-removing "Entfernen"
 * button (first dynamic add/remove-row pattern in this client, see file KDoc).
 *
 * V1.4.30 (W4c): six [LapisField]s per row. Each control sits in its own cell (`host`), but the error slots of the selects sit in ONE
 * full-width row beneath the cells (`slotHost`): `.invalid-feedback` has `width: 100%` and would squash six controls into one column. */
private fun renderPostingLinesRow(
    form: LapisForm,
    rowsPanel: SimplePanel,
    accountOptions: List<Pair<String, String>>,
    sideOptions: List<Pair<String, String>>,
    sphereOptions: List<Pair<String, String>>,
    costCenterOptions: List<Pair<String, String>>,
    vatRateOptions: List<Pair<String, String>>,
    vatUsable: Boolean,
    accountId: String,
    side: PostingSide,
    amount: String,
    sphere: GemeinnuetzigkeitSphere?,
    costCenterId: String,
    vatRate: VatRate?,
    rows: MutableList<PostingLineRow>,
    markRequiredNow: Boolean,
    onBalanceChanged: () -> Unit,
    onRowRemoved: () -> Unit,
): PostingLineRow {
    val line = rows.size + 1
    val rowBlock = rowsPanel.simplePanel()
    val rowPanel =
        rowBlock.hPanel(spacing = 8) {
            addCssClasses("lapis-posting-row lapis-posting-row--headed align-items-end border-bottom pb-2")
        }
    val slotRow = rowBlock.simplePanel()

    fun cell(): SimplePanel = rowPanel.simplePanel { addCssClass("lapis-posting-cell") }
    val labelled = mutableListOf<Pair<LapisField, String>>()

    val accountBase = gettext("Konto")
    val sideBase = gettext("Soll/Haben")
    val amountBase = gettext("Betrag")
    val sphereBase = gettext("Sphäre")
    val costCenterBase = gettext("Kostenstelle")
    val vatBase = gettext("USt")
    val accountField =
        form.selectField(
            label = postingFieldLabel(accountBase, line),
            options = accountOptions,
            value = accountId,
            required = true,
            host = cell(),
            slotHost = slotRow,
        )
    val sideField =
        form.selectField(
            label = postingFieldLabel(sideBase, line),
            options = sideOptions,
            value = side.name,
            required = true,
            host = cell(),
            slotHost = slotRow,
        )
    // Kein Vorzeichen: die Richtung ist und bleibt allein das Soll/Haben-Feld. Höchstens zwei Nachkommastellen (Servergrenze).
    val amountField =
        form.textField(
            label = postingFieldLabel(amountBase, line),
            value = amount.ifBlank { null },
            required = true,
            host = cell(),
            rule = { FormRules.postingAmount(it) },
        )
    val sphereField =
        form.selectField(
            label = postingFieldLabel(sphereBase, line),
            options = sphereOptions,
            value = sphere?.name ?: "",
            required = true,
            host = cell(),
            slotHost = slotRow,
        )
    val costCenterField =
        form.selectField(
            label = postingFieldLabel(costCenterBase, line),
            options = costCenterOptions,
            value = costCenterId,
            host = cell(),
            slotHost = slotRow,
        )
    labelled += accountField to accountBase
    labelled += sideField to sideBase
    labelled += amountField to amountBase
    labelled += sphereField to sphereBase
    labelled += costCenterField to costCenterBase
    // Welle V1.4.13 "USt-Voranmeldung" -- sechstes Control, NUR gerendert wenn USt fuer diese
    // Organisation nutzbar ist (vatUsable). UNCLASSIFIED ist absichtlich nicht waehlbar (siehe
    // vatRateOptions KDoc am Aufrufer) -- ein Vorschlag aus der Sphaere ist vorausgewaehlt, siehe
    // unten.
    val vatCell = if (vatUsable) cell() else null
    val vatField =
        vatCell?.let {
            form
                .selectField(
                    label = postingFieldLabel(vatBase, line),
                    options = vatRateOptions,
                    value = (vatRate ?: sphere?.let { suggestedVatRate(it) })?.name,
                    host = it,
                    slotHost = slotRow,
                ).also { field -> labelled += field to vatBase }
        }
    val removeButton = Button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER) { addCssClass("lapis-posting-remove") }
    rowPanel.add(removeButton)

    val fields = listOfNotNull(accountField, sideField, amountField, sphereField, costCenterField, vatField)
    if (markRequiredNow) fields.filter { it.required }.forEach { it.appendRequiredMark() }
    val vatRateSelect = vatField?.control as? Select
    val row =
        PostingLineRow(
            panel = rowBlock,
            accountSelect = accountField.control as Select,
            sideSelect = sideField.control as Select,
            amountInput = amountField.control as Text,
            sphereSelect = sphereField.control as Select,
            costCenterSelect = costCenterField.control as Select,
            vatRateSelect = vatRateSelect,
            fields = fields,
            amountField = amountField,
            labelled = labelled,
        )
    rows.add(row)
    // Der Saldostreifen folgt Betrag UND Seite (`subscribe` feuert auch bei `setValue`; der Streifen hat keine Nebenwirkung).
    amountField.subscribe { onBalanceChanged() }
    sideField.subscribe { onBalanceChanged() }

    if (vatField != null && vatCell != null && vatRateSelect != null) {
        // Duarte-Ruling: die USt-Auswahl folgt der Sphaere nur so lange, wie der Behandler sie
        // nicht selbst angefasst hat -- danach nie wieder ueberschrieben, auch nicht bei einem
        // weiteren Sphaerenwechsel. Ein sichtbarer Kurzhinweis erklaert die Herkunft des
        // Vorschlags; er verschwindet in dem Moment, in dem die Person selbst waehlt.
        val suggestionHint =
            vatCell.div(tr("Vorschlag aus der Sphäre -- jederzeit überschreibbar")) {
                addCssClasses("text-muted small")
            }
        if (vatRate != null) suggestionHint.hide()
        // Guards against the programmatic write below itself firing `vatField`'s own `subscribe` callback -- KVision's reactive
        // `.value` setter cannot distinguish "the user picked this" from "code just set this", so without this flag the
        // very first auto-suggestion would immediately mark itself as user-touched and the suggestion would never update again
        // on a later sphere change.
        var applyingSuggestion = false
        sphereField.subscribe {
            if (row.vatRateUserTouched) return@subscribe
            val newSphere =
                it
                    .takeIf { name ->
                        name.isNotBlank()
                    }?.let { name -> runCatching { GemeinnuetzigkeitSphere.valueOf(name) }.getOrNull() }
            applyingSuggestion = true
            vatField.setValue(newSphere?.let { s -> suggestedVatRate(s) }?.name)
            applyingSuggestion = false
            if (newSphere != null) suggestionHint.show() else suggestionHint.hide()
        }
        vatField.subscribe {
            if (applyingSuggestion) return@subscribe
            row.vatRateUserTouched = true
            suggestionHint.hide()
        }
    }

    removeButton.onClick {
        // ZUERST abmelden, dann das Widget entfernen: das Feld einer entfernten Zeile darf `validateAndReport()` nicht mehr blockieren.
        fields.forEach { form.unregister(it) }
        rowsPanel.remove(rowBlock)
        rows.remove(row)
        onRowRemoved()
    }
    return row
}

// ============================================================================================
// D2: bespoke posting-confirmation modal -- shared by both post call sites
// ============================================================================================

internal data class PostingLineDisplay(
    val accountLabel: String,
    val side: PostingSide,
    val amount: Decimal,
    val sphereLabel: String,
    val costCenterLabel: String?,
    /** Welle V1.4.13. `null` only when USt is not usable for this organization at all (every line
     *  would show UNCLASSIFIED, a column of nothing but "Keine USt-Einordnung" adds noise without
     *  information) -- see [postingConfirmDialog]'s own column-visibility check. */
    val vatRateLabel: String? = null,
    /** Welle V1.4.13. Only ever set from an already-loaded [PostingDto] ([postingDtosToDisplay]) --
     *  [postingInputsToDisplay] leaves this `null` on principle: a not-yet-posted [PostingInput]
     *  has no server-computed `vatAmount` yet, and this modal never re-derives a persisted
     *  monetary figure on its own (see [sumPostingLines] KDoc for the one narrow exception this
     *  file already documents, which does not apply here). */
    val vatAmount: Decimal? = null,
)

private fun postingDtosToDisplay(postings: List<PostingDto>): List<PostingLineDisplay> =
    postings.map { posting ->
        PostingLineDisplay(
            accountLabel = gettext("%1 · %2", posting.ledgerAccountNumber, posting.ledgerAccountName),
            side = posting.side,
            amount = posting.amount,
            sphereLabel = sphereLabel(posting.sphere),
            costCenterLabel = posting.costCenterCode?.let { gettext("%1 · %2", it, posting.costCenterName) },
            // null (both fields together) for UNCLASSIFIED -- see PostingLineDisplay KDoc: a set
            // of lines where every single one reads "Keine USt-Einordnung" (USt not usable for
            // this organization at all) should not even show the column, not show it full of that
            // one repeated label. Kept paired (never one null, the other set) so the rendering
            // code never has to handle a "label missing but amount present" half-state.
            vatRateLabel = posting.vatRate.takeIf { it != VatRate.UNCLASSIFIED }?.let { vatRateLabel(it) },
            vatAmount = posting.vatAmount.takeIf { posting.vatRate != VatRate.UNCLASSIFIED },
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
            vatRateLabel = posting.vatRate.takeIf { it != VatRate.UNCLASSIFIED }?.let { vatRateLabel(it) },
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
internal fun sumPostingLines(
    lines: List<PostingLineDisplay>,
    side: PostingSide,
): Decimal = lines.filter { it.side == side }.sumOf { it.amount.toDouble() }.toDecimal()

/**
 * The Soll/Haben table of the posting confirmation as a [reportTable]: the treasurer sees the actual postings,
 * balanced, before the entry becomes immutable. The Σ row takes the caller's sums ([sumPostingLines]).
 */
internal fun renderPostingConfirmTable(
    container: Container,
    lines: List<PostingLineDisplay>,
    showVatColumn: Boolean,
) {
    val headers =
        buildList {
            add(TableHeader(title = tr("Konto")))
            add(TableHeader(title = tr("Soll"), numeric = true))
            add(TableHeader(title = tr("Haben"), numeric = true))
            add(TableHeader(title = tr("Sphäre")))
            add(TableHeader(title = tr("Kostenstelle")))
            if (showVatColumn) add(TableHeader(title = tr("USt")))
        }
    val report = container.reportTable(caption = tr("Buchungszeilen"), headers = headers)
    val rows =
        postingConfirmRows(
            lines = lines,
            showVatColumn = showVatColumn,
            debitSum = sumPostingLines(lines, PostingSide.DEBIT),
            creditSum = sumPostingLines(lines, PostingSide.CREDIT),
        )
    report.reportRows(rows, headers)
}

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

    // Welle V1.4.13: the USt column only earns its place when at least one line actually carries a
    // real classification -- an all-UNCLASSIFIED set (USt not usable for this organization) would
    // otherwise add a column of pure noise. `vatRateLabel` is only ever non-null when USt is
    // usable (see PostingLineDisplay KDoc), so this also doubles as the "is USt usable here" check.
    val showVatColumn = lines.any { it.vatRateLabel != null }
    if (showVatColumn) {
        modal.div(tr("Beträge oben sind BRUTTO (inkl. USt).")) { addCssClasses("text-muted small") }
    }

    renderPostingConfirmTable(modal, lines, showVatColumn)

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    // Einmal-Objekt (R29): ohne die Sperre feuerte "Endgültig buchen" beliebig oft, bis das Modal weg ist -- bei `postJournalEntry`
    // (kein Idempotenzschlüssel) eine echte Doppelbuchung.
    val once = ConfirmOnce()
    val confirmButton = Button(tr("Endgültig buchen"), style = ButtonStyle.DANGER)
    confirmButton.onClick {
        once.run(confirmButton) {
            modal.hide()
            onConfirm()
        }
    }
    modal.addButton(confirmButton)
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
