package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Table
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.OpenItemAgingBucketDto
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemInput
import network.lapis.cloud.shared.domain.OpenItemSettlementDto
import network.lapis.cloud.shared.domain.OpenItemSettlementKind
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.OpenItemSummaryDto
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.ReceivableDunningLevelDto
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeDto
import network.lapis.cloud.shared.domain.ReceivableDunningSettingsDto
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.ICrmService
import network.lapis.cloud.shared.rpc.IOpenItemService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IReceivableDunningService

/** S8: hart gedeckelte "Mehr laden"-Kette -- höchstens 20 Ladevorgänge je Filterzustand (bei
 *  Seitengröße 200 also 4.000 Zeilen), danach nur noch der Hinweis, den Filter einzuschränken. */
private const val OPEN_ITEMS_MAX_LOADS = 20
private const val OPEN_ITEMS_SEARCH_DEBOUNCE_MS = 250
private const val OPEN_ITEMS_PREFILL_DEBOUNCE_MS = 500
private const val OPEN_ITEMS_DEFAULT_DUE_DAYS = 14
private const val CRM_CONTACT_LIMIT = 200

/**
 * Welle V1.4.21 "Bedienoberfläche Offene Posten" -- `/open-items`, TREASURER/BOARD/ADMIN lesend,
 * TREASURER/ADMIN schreibend ([OpenItemAuthzUi]; BOARD sieht weder das Formular noch irgendeine
 * Schreibaktion). **Ein** Screen mit drei Segmenten (Alle/Kreditoren/Debitoren) statt drei Screens
 * (Design-Team-Ruling 1): dieselben Objekte, dieselbe Grammatik.
 *
 * ## Rulings, die im Code sichtbar bleiben
 *
 * - **Segment erbt nie die Richtung** (Jobs, Stolperfalle S11): das Anlegen-Formular hat ein
 *   sichtbares, editierbares Pflichtfeld "Richtung", aus dem Segment nur *vorbelegt* -- in "Alle"
 *   ohne Vorbelegung, damit die Wahl bewusst geschieht.
 * - **Drei Kacheln + Klappabschnitt** (Rams): [openItemMetricTiles]; die Altersstruktur ist
 *   eingeklappt. Nie ein saldierter Wert aus Verbindlichkeiten und Forderungen.
 * - **Filter wirken nur auf geladene Zeilen** (Jobs, Punkt 3): `listOpenItems` hat weder Status-
 *   Filter noch Suche. Normans Etikett "N von M geladenen Posten" sagt das mit Zahlen an; ein
 *   Backend-Parameter wäre eine Schema-/Signaturänderung außerhalb dieser Welle.
 * - **Genau ein Überfällig-Badge** (Ive), daneben die Klartext-Tageszahl aus `daysOverdue`
 *   (server-berechnet, nie client-seitig neu hergeleitet -- Stolperfalle S5).
 *
 * [selectedItemId] kommt aus `?item=<uuid>` im Hash-Fragment (Muster `BANK_IMPORT`, gelesen in der
 * Routen-Registrierung, nicht hier) -- ein verlinkbarer Zustand, z. B. aus dem Prüfprotokoll.
 * Freitext (Gegenpartei, Belegnummer, Notiz, Gründe) geht ausschließlich über KVision-Widgets mit
 * Text-Inhalt, nie als HTML (Stolperfalle S9).
 */
private class OpenItemsState(
    var selectedId: String?,
) {
    var segment: OpenItemSegment = OpenItemSegment.ALL
    var filter: OpenItemListFilter = OpenItemListFilter()
    var pageSize: Int = 50
    var summary: OpenItemSummaryDto? = null
    val loaded: MutableList<OpenItemDto> = mutableListOf()
    var cursorDueDate: LocalDate? = null
    var cursorItemId: String? = null
    var hasMore: Boolean = false
    var loadCount: Int = 0
    var generation: Int = 0
    var loadedIncludesClosed: Boolean = false
    var accounts: List<LedgerAccountDto> = emptyList()

    /**
     * Welle V1.4.22: die Zahlungskonten-Zuordnung der Organisation (`getOrganizationSettings` ist
     * TREASURER/BOARD/ADMIN -- genau die Leserollen dieses Screens, also kein stiller 403).
     * **`null` heißt "noch nicht bekannt"**, nicht "nichts zugeordnet": scheitert der Abruf, bleibt
     * der Ausgleichen-Dialog beim bisherigen Verhalten, statt zu behaupten, es sei kein
     * Standard-Bankkonto hinterlegt (siehe [settlementBankChoice]).
     */
    var paymentMapping: PaymentAccountMapping? = null

    /** Welle V1.4.21 Audit-Fund M5: `null`, solange der Abruf nicht (erfolgreich) durch ist. */
    var dunningSettings: ReceivableDunningSettingsDto? = null

    /** Nur die AKTIVEN Stufen -- gebraucht für die Gebühr im Bestätigungstext (Audit-Fund M5). */
    var dunningLevels: List<ReceivableDunningLevelDto> = emptyList()
}

fun renderOpenItemsScreen(
    container: SimplePanel,
    selectedItemId: String? = null,
) {
    val root = container.dataScreenRoot()
    root.h1(tr("Offene Posten"))

    val role = AppState.session?.role
    val canWrite = OpenItemAuthzUi.canWrite(role)
    val state = OpenItemsState(selectedId = selectedItemId?.takeIf { looksLikeOpenItemUuid(it) })

    val bandHost = root.vPanel(spacing = 4)
    val segmentRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val summaryHost = root.hPanel(spacing = 12) { addCssClasses("flex-wrap") }
    val agingToggle = root.button(tr("Altersstruktur anzeigen"), style = ButtonStyle.LINK)
    agingToggle.setAttribute("aria-expanded", "false")
    val agingHost = root.vPanel(spacing = 6).apply { hide() }

    val actionRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val formHost = root.vPanel(spacing = 8)

    root.h2(tr("Posten")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val statusChecks: Map<OpenItemStatus, CheckBox> =
        OpenItemStatus.entries.associateWith { status ->
            filterRow.checkBox(value = status in state.filter.statuses, label = openItemStatusLabel(status))
        }
    val overdueCheck = filterRow.checkBox(value = false, label = tr("Nur überfällige"))
    val searchInput = filterRow.text(label = tr("Suche (Gegenpartei, Beleg)"))
    val pageSizeSelect =
        filterRow.select(
            options = listOf("50" to "50", "100" to "100", "200" to "200"),
            value = "50",
            label = tr("Seitengröße"),
        )
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    val capHint =
        root
            .div(tr("Anzeige begrenzt — bitte Filter einschränken."))
            .apply {
                addCssClasses("text-muted small")
                hide()
            }

    root.h2(tr("Details")) { addCssClass("h5") }
    val detailPanel = root.vPanel(spacing = 10)
    detailPanel.p(tr("Posten oben auswählen, um Details zu sehen.")) { addCssClasses("text-muted small") }

    // ── Vorwärtsdeklarationen (lokale Funktionen rufen sich gegenseitig) ─────────────────────────
    var reloadAll: () -> Unit = {}
    var openDetail: (String) -> Unit = {}
    var renderListFn: () -> Unit = {}

    /**
     * Welle V1.4.22 Audit-Nachtrag (MAJOR-2): Nachladen der Zahlungskonten-Zuordnung. Nur für
     * Schreibrollen belegt (nur sie können ausgleichen), deshalb hier vorwärtsdeklariert -- die
     * Zeilen-Aktion unten entsteht vor dem `canWrite`-Block.
     */
    var loadPaymentMappingFn: () -> Unit = {}

    /**
     * Die Zuordnung für den Ausgleichen-Dialog -- und, falls sie fehlt, ein neuer Abruf. Der erste
     * Abruf beim Bildschirmaufbau hat keinen Wiederholversuch (anders als `loadAccounts`, das der
     * Anlegen-Knopf notfalls erneut auslöst); ohne diesen Haken blieb ein einmal gescheiterter Abruf
     * für die ganze Sitzung fehlend, und der Dialog konnte dauerhaft weder die Standard-Option
     * ehrlich anbieten noch das Forderungskonto herausfiltern. Der laufende Abruf kommt zu spät für
     * DIESEN Dialog (er wird einmal aufgebaut) -- deshalb sagt [paymentAccountsUnknownHint], dass ein
     * erneutes Öffnen die Auswahl bringt.
     */
    fun paymentMappingOrReload(): PaymentAccountMapping? {
        if (state.paymentMapping == null) loadPaymentMappingFn()
        return state.paymentMapping
    }

    // Audit-Fund (zweiter Durchgang) zu M5: `refreshDunningContext()` und `openDetail(selectedId)`
    // starten PARALLEL, und der Detailbereich liest den Mahnkontext zum RENDER-Zeitpunkt
    // (`renderDunningDisabledBand`, Gebührenbetrag im Bestätigungstext). Trifft der Kontext NACH dem
    // Detail ein -- beim ersten Laden mit `?item=<uuid>` der Normalfall --, fehlte das Warnband.
    // Deshalb wird gemerkt, WELCHER Kontext im angezeigten Detail steckt; nach dem Abruf wird genau
    // dann einmal neu gerendert, wenn er sich wirklich geändert hat: kein zweiter Abruf, kein
    // zweiter Toast, keine Schleife (`showDetail` ruft `refreshDunningContext` nicht).
    var shownDetail: OpenItemDetailDto? = null
    var shownDunningContext: Pair<ReceivableDunningSettingsDto?, List<ReceivableDunningLevelDto>>? = null

    // ── Kennzahlen ────────────────────────────────────────────────────────────────────────────────
    fun applyFilterFromControls() {
        state.filter =
            OpenItemListFilter(
                statuses = statusChecks.filterValues { it.value }.keys,
                search = searchInput.value.orEmpty(),
                onlyOverdue = overdueCheck.value,
            )
        state.pageSize = pageSizeSelect.value?.toIntOrNull() ?: 50
    }

    fun renderTiles() {
        summaryHost.removeAll()
        val summary = state.summary ?: return
        openItemMetricTiles(state.segment, summary, canWrite).forEach { tile ->
            val card = summaryHost.div { addCssClasses("lapis-surface border rounded p-3 flex-fill") }
            card.div(openItemMetricLabel(tile.kind)) { addCssClasses("text-muted small") }
            when (tile) {
                is OpenItemMetricTile.Money -> {
                    card.div { addCssClasses("h4 mb-0") }.moneySpan(tile.amount)
                    tile.count?.let { count -> card.div(gettext("%1 Posten", count)) { addCssClasses("text-muted small") } }
                    if (tile.kind == OpenItemMetricKind.OVERDUE && state.segment == OpenItemSegment.ALL) {
                        // N9: in "Alle" summiert diese Kachel BEIDE Richtungen (nie ein Saldo, siehe
                        // `overdueTotalBothDirections`) -- das muss an der Kachel stehen, nicht nur im KDoc.
                        card.div(tr("(Kreditoren + Debitoren)")) { addCssClasses("text-muted small") }
                    }
                    if (tile.kind == OpenItemMetricKind.OVERDUE) {
                        // Überfällig-Kachel als Filter-Schalter: Klick = "nur überfällige" an/aus.
                        val toggle =
                            card.button(
                                if (state.filter.onlyOverdue) tr("Alle Posten zeigen") else tr("Nur diese zeigen"),
                                style = ButtonStyle.LINK,
                            )
                        toggle.addCssClass("p-0")
                        toggle.setAttribute("aria-pressed", state.filter.onlyOverdue.toString())
                        toggle.onClick {
                            // The assignment synchronously runs the `overdueCheck.subscribe` observer, which
                            // already applies the filter and re-renders tiles + list (and removes this very
                            // button) -- repeating those calls here would rebuild everything twice.
                            overdueCheck.value = !state.filter.onlyOverdue
                        }
                    }
                }
                is OpenItemMetricTile.Count -> {
                    card.div(tile.count.toString()) { addCssClasses("h4 mb-0") }
                    card.div(tr("Kreditor und Debitor mit gleicher Gegenpartei")) { addCssClasses("text-muted small") }
                }
            }
        }
    }

    fun renderAging() {
        agingHost.removeAll()
        val summary = state.summary ?: return

        fun bucketTable(
            title: String?,
            buckets: List<OpenItemAgingBucketDto>,
        ) {
            title?.let { agingHost.div(it) { addCssClasses("fw-bold") } }
            val table =
                agingHost.standardTable(
                    listOf(
                        TableHeader(title = tr("Fälligkeit")),
                        TableHeader(title = tr("Anzahl"), numeric = true),
                        TableHeader(title = tr("Betrag"), numeric = true),
                    ),
                )
            buckets.forEach { bucket ->
                table.row {
                    cell(openItemAgingBucketLabel(bucket.bucket))
                    numCell(bucket.count.toString())
                    numCell { moneySpan(bucket.totalAmount) }
                }
            }
        }
        when (state.segment) {
            OpenItemSegment.ALL -> {
                bucketTable(openItemSegmentLabel(OpenItemSegment.PAYABLE), summary.payableBuckets)
                bucketTable(openItemSegmentLabel(OpenItemSegment.RECEIVABLE), summary.receivableBuckets)
            }
            OpenItemSegment.PAYABLE -> bucketTable(null, summary.payableBuckets)
            OpenItemSegment.RECEIVABLE -> bucketTable(null, summary.receivableBuckets)
        }
    }

    fun renderBand() {
        bandHost.removeAll()
        val summary = state.summary
        if (!OpenItemAuthzUi.showAccountsNotConfiguredBand(summary)) return
        val band = bandHost.div { addCssClasses("alert alert-warning") }
        band.div(
            tr("Forderungs- und Verbindlichkeitenkonto sind nicht zugeordnet -- neue Posten werden angelegt, aber nicht gebucht."),
        ) { addCssClass("fw-bold") }
        if (AppState.hasRole(AccountRole.ADMIN)) {
            band.button(tr("Jetzt zuordnen (Kontenplan)"), style = ButtonStyle.LINK).onClick { navigateTo(Routes.LEDGER) }
        } else {
            band.div(
                tr(
                    "Ein Administrator muss im Kontenplan unter „Kontenzuordnung Zahlungsverkehr\" ein Forderungs- " +
                        "und ein Verbindlichkeitenkonto zuordnen.",
                ),
            ) { addCssClasses("text-muted small") }
        }
    }

    fun refreshSummary() {
        AppScope.launch {
            val summary = guarded { rpcService<IOpenItemService>().getOpenItemSummary(null) }
            if (summary == null) {
                // The toast is out already; the age structure (the only part that can be opened on demand)
                // gets a state with a way out instead of staying blank.
                agingHost.removeAll()
                agingHost.dataErrorState(onRetry = { refreshSummary() })
                return@launch
            }
            state.summary = summary
            renderBand()
            renderTiles()
            renderAging()
        }
    }

    // ── Liste ─────────────────────────────────────────────────────────────────────────────────────
    fun renderList() {
        listPanel.removeAll()
        // N11: GENAU EIN Filterdurchlauf. Vorher liefen `openItemFilterCounts` und
        // `applyOpenItemFilter` beide über alle geladenen Zeilen (und damit `CounterpartyKey.of`
        // doppelt je Zeile und Tastendruck) -- die Zähler kommen jetzt aus demselben Ergebnis.
        val visibleItems = applyOpenItemFilter(state.loaded, state.filter)
        val countsText = gettext("%1 von %2 geladenen Posten angezeigt", visibleItems.size, state.loaded.size)
        val tileHint = gettext("Kacheln zählen alle Posten, die Liste nur die geladenen.")
        val moreHint = tr("Weitere Posten liegen noch auf dem Server; Filter und Suche wirken nur auf die geladenen Zeilen.")
        countsLabel.content =
            when {
                state.loaded.isEmpty() -> ""
                state.hasMore -> "$countsText · $moreHint · $tileHint"
                else -> "$countsText · $tileHint"
            }
        if (visibleItems.isEmpty()) {
            listPanel.p(tr("Keine Posten für diese Filter.")) { addCssClasses("text-muted") }
            return
        }
        val showLevelColumn = state.segment != OpenItemSegment.PAYABLE
        val headers =
            buildList {
                add(TableHeader(title = tr("Gegenpartei")))
                add(TableHeader(title = tr("Beleg")))
                add(TableHeader(title = tr("Fällig am"), numeric = true))
                add(TableHeader(title = tr("Betrag"), numeric = true))
                add(TableHeader(title = tr("Offen"), numeric = true))
                add(TableHeader(title = tr("Status")))
                if (showLevelColumn) add(TableHeader(title = tr("Mahnstufe")))
                add(TableHeader(title = ""))
            }
        val table = listPanel.standardTable(headers)
        visibleItems.forEach { item ->
            appendOpenItemRow(
                table = table,
                item = item,
                role = role,
                showLevelColumn = showLevelColumn,
                selected = item.id == state.selectedId,
                onSelect = { openDetail(item.id) },
                // N12: `accounts` als Lambda, nicht als Momentaufnahme -- eine Kontenliste, die erst
                // NACH dem Rendern der Zeile eintrifft, war im Zeilen-Dialog sonst leer. Dasselbe
                // gilt für die Zahlungskonten-Zuordnung (V1.4.22).
                onSettle = {
                    openItemSettlementDialog(
                        item = item,
                        accounts = { state.accounts },
                        paymentMapping = { paymentMappingOrReload() },
                        knownSettlementIds = null,
                    ) { reloadAll() }
                },
                onRetry = {
                    val result = guarded { rpcService<IOpenItemService>().retryOpenItemPosting(item.id) }
                    if (result != null) {
                        notifyRetryOutcome(result.item)
                        reloadAll()
                    }
                },
            )
        }
    }
    renderListFn = ::renderList

    fun loadPage(reset: Boolean) {
        if (reset) {
            state.generation++
            state.loaded.clear()
            state.cursorDueDate = null
            state.cursorItemId = null
            state.hasMore = false
            state.loadCount = 0
            listPanel.removeAll()
            listPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
            capHint.hide()
            // Kein "Mehr laden" mit Cursor null während eines Resets (sonst würde Seite 1 doppelt angehängt).
            loadMoreButton.hide()
            countsLabel.content = ""
        }
        val generation = state.generation
        val includeClosed = state.filter.requiresClosedRows()
        loadMoreButton.disabled = true
        AppScope.launch {
            val items =
                guarded {
                    rpcService<IOpenItemService>().listOpenItems(
                        direction = state.segment.toDirection(),
                        onlyOpen = !includeClosed,
                        limit = state.pageSize,
                        afterDueDate = state.cursorDueDate,
                        afterOpenItemId = state.cursorItemId,
                    )
                }
            if (generation != state.generation) return@launch // ein neuerer Ladevorgang hat übernommen
            loadMoreButton.disabled = false
            if (items == null) {
                // S3: guarded() hat den Toast schon ausgelöst. Beim Neuladen (Reset) ersetzt ein Fehlerzustand
                // mit "Erneut versuchen" den Platzhalter -- vorher blieb hier ein stilles, leeres Panel; das
                // Zähler-Etikett wird konsistent geleert. Beim Nachladen ("Mehr laden") bleibt die Ansicht
                // bestehen, dort genügt der Toast.
                if (reset) {
                    listPanel.removeAll()
                    countsLabel.content = ""
                    loadMoreButton.hide()
                    listPanel.dataErrorState(onRetry = { loadPage(reset = true) })
                }
                return@launch
            }
            state.loadedIncludesClosed = includeClosed
            state.loaded.addAll(items)
            items.lastOrNull()?.let {
                state.cursorDueDate = it.dueDate
                state.cursorItemId = it.id
            }
            state.loadCount++
            // Exakter Größenvergleich: `listOpenItems` liefert nie mehr als `limit` Zeilen.
            state.hasMore = items.size == state.pageSize
            val capped = state.hasMore && state.loadCount >= OPEN_ITEMS_MAX_LOADS
            if (state.hasMore && !capped) loadMoreButton.show() else loadMoreButton.hide()
            if (capped) capHint.show() else capHint.hide()
            renderList()
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────────────────────────────
    fun showDetail(detail: OpenItemDetailDto) {
        detailPanel.removeAll()
        state.selectedId = detail.item.id
        shownDetail = detail
        shownDunningContext = state.dunningSettings to state.dunningLevels
        renderOpenItemDetail(
            container = detailPanel,
            detail = detail,
            role = role,
            accounts = { state.accounts },
            paymentMapping = { paymentMappingOrReload() },
            dunningSettings = { state.dunningSettings },
            dunningLevels = { state.dunningLevels },
            onChanged = { changed ->
                showDetail(changed)
                refreshSummary()
                loadPage(reset = true)
            },
            onReloadNeeded = { reloadAll() },
        )
    }

    openDetail = { id ->
        detailPanel.removeAll()
        // Ab hier steht KEIN Detail mehr im Bereich -- ein Mahnkontext, der jetzt eintrifft, darf
        // kein altes Detail über den Ladeplatzhalter bzw. die Fehlermeldung zurückholen.
        shownDetail = null
        shownDunningContext = null
        detailPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val loaded = guarded { rpcService<IOpenItemService>().getOpenItem(id) }
            detailPanel.removeAll()
            if (loaded == null) {
                // N13: gescheiterter Abruf ist NICHT "nicht gefunden" -- `guarded()` hat den Fehler
                // schon als Toast gezeigt, der Posten existiert vermutlich weiterhin. Deshalb auch
                // `state.selectedId` nicht löschen (die Auswahl bleibt, "Aktualisieren" hilft).
                detailPanel.dataErrorState(onRetry = { openDetail(id) })
                return@launch
            }
            val detail = loaded.firstOrNull()
            if (detail == null) {
                state.selectedId = null
                detailPanel.p(tr("Posten nicht gefunden.")) { addCssClasses("text-muted small") }
                return@launch
            }
            showDetail(detail)
            // Only re-render (selection highlight) once the list has data; otherwise the pending
            // "Wird geladen …" placeholder would be replaced by a false "Keine Posten" message.
            if (state.loadCount > 0) renderList()
        }
    }

    // Audit-Fund M5: Zustand des Forderungs-Mahnwesens + die aktiven Stufen. Beide RPCs sind
    // TREASURER/BOARD/ADMIN -- genau die Leserollen dieses Screens, also kein stiller 403 (K3).
    // Scheitert der Abruf, bleibt `dunningSettings` null und das Warnband unterbleibt, statt eine
    // Deaktivierung zu behaupten, die niemand bestätigt hat.
    fun refreshDunningContext() {
        AppScope.launch {
            val service = rpcService<IReceivableDunningService>()
            guarded { service.getReceivableDunningSettings() }?.let { state.dunningSettings = it }
            guarded { service.listReceivableDunningLevels(includeInactive = false) }?.let { state.dunningLevels = it }
            // Genau EIN Nachrendern, und nur wenn das angezeigte Detail einen anderen Kontext zeigt
            // als den jetzt bekannten (siehe `shownDunningContext`). `showDetail` rendert rein aus dem
            // bereits geladenen `OpenItemDetailDto` -- kein RPC, kein Toast, kein Rekursionspfad.
            val detail = shownDetail ?: return@launch
            if (shownDunningContext != state.dunningSettings to state.dunningLevels) showDetail(detail)
        }
    }

    reloadAll = {
        refreshSummary()
        refreshDunningContext()
        // V1.4.22: "Aktualisieren" holt die Zahlungskonten-Zuordnung mit -- ein Administrator kann sie
        // in einem anderen Tab gerade erst gesetzt haben, und ohne diesen Abruf bliebe der
        // Ausgleichen-Dialog bis zum Seiten-Neuladen bei "kein Standard-Bankkonto hinterlegt".
        loadPaymentMappingFn()
        loadPage(reset = true)
        state.selectedId?.let { openDetail(it) }
    }

    // ── Steuerelemente verdrahten ────────────────────────────────────────────────────────────────
    segmentRow.segmentedControl(
        options = OpenItemSegment.entries.map { it to openItemSegmentLabel(it) },
        selected = state.segment,
        ariaLabel = tr("Richtung"),
    ) { segment ->
        state.segment = segment
        renderTiles()
        renderAging()
        loadPage(reset = true)
    }

    agingToggle.onClick {
        if (agingHost.visible) {
            agingHost.hide()
            agingToggle.text = tr("Altersstruktur anzeigen")
            agingToggle.setAttribute("aria-expanded", "false")
        } else {
            agingHost.show()
            agingToggle.text = tr("Altersstruktur ausblenden")
            agingToggle.setAttribute("aria-expanded", "true")
        }
    }

    statusChecks.values.forEach { check ->
        check.subscribe {
            val wasClosed = state.filter.requiresClosedRows()
            applyFilterFromControls()
            val nowClosed = state.filter.requiresClosedRows()
            // Nur wenn erstmals geschlossene Zeilen gebraucht werden, ist ein neuer Server-Abruf nötig.
            if (nowClosed && !wasClosed && !state.loadedIncludesClosed) loadPage(reset = true) else renderList()
        }
    }
    overdueCheck.subscribe {
        applyFilterFromControls()
        renderTiles()
        renderList()
    }
    var searchTimer: Int? = null
    // Gleicher Guard wie unten: der synthetische Erst-Event würde sonst nach dem Debounce
    // `renderList()` auf leerem `state.loaded` auslösen und den Ladeplatzhalter fälschlich durch
    // "Keine Posten für diese Filter." ersetzen (siehe `LedgerScreen.kt`).
    var isInitialSearchEvent = true
    searchInput.subscribe {
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        searchTimer?.let { window.clearTimeout(it) }
        searchTimer =
            window.setTimeout({
                applyFilterFromControls()
                renderList()
            }, OPEN_ITEMS_SEARCH_DEBOUNCE_MS)
    }
    // KVision's `subscribe` fires once synchronously on registration -- without the guard that
    // synthetic event would issue a full `listOpenItems` call the explicit `loadPage(reset = true)`
    // below immediately supersedes (see `MemberFamiliesScreen.kt`).
    var isInitialPageSizeEvent = true
    pageSizeSelect.subscribe {
        if (isInitialPageSizeEvent) {
            isInitialPageSizeEvent = false
            return@subscribe
        }
        applyFilterFromControls()
        loadPage(reset = true)
    }
    refreshButton.onClick { reloadAll() }
    loadMoreButton.onClick { loadPage(reset = false) }

    // ── Aktionsleiste (nur TREASURER/ADMIN) ──────────────────────────────────────────────────────
    if (canWrite) {
        // Set by the open create form; invoked once the ledger accounts have (re)loaded.
        var onAccountsLoaded: (() -> Unit)? = null

        fun loadAccounts() {
            AppScope.launch {
                val loaded = guarded { rpcService<IAccountingService>().listLedgerAccounts(activeOnly = true) } ?: return@launch
                state.accounts = loaded
                onAccountsLoaded?.invoke()
            }
        }

        // Welle V1.4.22: Welches Konto ist das Standard-Zahlungskonto, welches sind die
        // Sammelkonten? Ohne diese Antwort kann der Ausgleichen-Dialog weder die Standard-Option
        // ehrlich anbieten noch das Forderungskonto aus der Liste nehmen. Nur für Schreibrollen
        // geladen -- nur sie können überhaupt ausgleichen (wie `loadAccounts` oben).
        fun loadPaymentMapping() {
            AppScope.launch {
                val settings = guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() } ?: return@launch
                state.paymentMapping = settings.toPaymentAccountMapping()
            }
        }
        loadPaymentMappingFn = ::loadPaymentMapping

        val createButton = actionRow.button(tr("Posten anlegen"), style = ButtonStyle.PRIMARY)
        val nettingButton = actionRow.button(tr("Verrechnen …"), style = ButtonStyle.OUTLINESECONDARY)
        createButton.onClick {
            formHost.removeAll()
            val defaultDirection =
                when (state.segment) {
                    OpenItemSegment.ALL -> null // S11: in "Alle" keine Vorbelegung -- bewusste Wahl erzwingen
                    OpenItemSegment.PAYABLE -> OpenItemDirection.PAYABLE
                    OpenItemSegment.RECEIVABLE -> OpenItemDirection.RECEIVABLE
                }
            renderOpenItemCreateForm(
                host = formHost,
                role = role,
                accounts = { state.accounts },
                defaultDirection = defaultDirection,
                registerAccountsLoadedListener = { listener -> onAccountsLoaded = listener },
                onCreated = { created ->
                    onAccountsLoaded = null
                    formHost.removeAll()
                    notifyCreatedOutcome(created.item)
                    showDetail(created)
                    refreshSummary()
                    loadPage(reset = true)
                },
                onCancel = {
                    onAccountsLoaded = null
                    formHost.removeAll()
                },
            )
            // The initial load may still be in flight or may have failed (no automatic retry) -- an
            // empty list would leave the form's "Gegenkonto" select without any option.
            if (state.accounts.isEmpty()) loadAccounts()
        }
        nettingButton.onClick { openItemNettingDialog { reloadAll() } }
        // Konten für Gegenkonto-/Zahlungskonto-Selects laden; ohne sie bleibt das Formular leer.
        loadAccounts()
        loadPaymentMapping()
    }

    refreshSummary()
    refreshDunningContext()
    loadPage(reset = true)
    state.selectedId?.let { openDetail(it) }
}

private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Ein Deep-Link-Parameter, der keine UUID ist, würde als ungültige Anfrage-Toast enden -- lieber ignorieren. */
internal fun looksLikeOpenItemUuid(value: String): Boolean = UUID_SHAPE.matches(value.trim())

/**
 * K2: ein Posten kann angelegt, aber NICHT gebucht sein (`creationPostingError`) -- das ist kein
 * Anlegen-Fehler, der Posten existiert. Der Klartext nennt die Ursache; ein unbekannter Code wird
 * roh gezeigt, nie verschluckt.
 */
private fun notifyCreatedOutcome(item: OpenItemDto) {
    val error = item.creationPostingError
    if (error == null) {
        notifySuccess(tr("Posten angelegt."))
        return
    }
    notifyError(gettext("Posten angelegt, aber nicht gebucht: %1", openItemPostingErrorMessage(error) ?: error))
}

/** Ergebnis von `retryOpenItemPosting`: Erfolg nur, wenn der Fehlercode weg ist -- sonst bleibt der Posten ungebucht. */
private fun notifyRetryOutcome(item: OpenItemDto) {
    val error = item.creationPostingError
    if (error == null) {
        notifySuccess(tr("Buchung nachgeholt."))
        return
    }
    notifyError(gettext("Weiterhin nicht gebucht: %1", openItemPostingErrorMessage(error) ?: error))
}

// ================================================================================================
// Liste (Zeile)
// ================================================================================================

private fun appendOpenItemRow(
    table: Table,
    item: OpenItemDto,
    role: AccountRole?,
    showLevelColumn: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onSettle: () -> Unit,
    // N4: `suspend`, damit die Knopfsperre (`runGuardedAction`) HIER um den Aufruf liegt -- die
    // Zeilen-Aktion lief vorher ohne Sperre, anders als dieselbe Aktion in der Detail-Aktionsleiste.
    onRetry: suspend () -> Unit,
) {
    table.row {
        if (selected) addCssClass("table-active")
        val nameCell = cell()
        nameCell.span(item.counterpartyName)
        nameCell.typeBadge(openItemDirectionLabel(item.direction), openItemDirectionColor(item.direction)).addCssClass("ms-2")
        cell(item.reference.orEmpty())
        val dueCell = numCell()
        dueCell.span(item.dueDate.toString())
        if (item.daysOverdue > 0 && item.status in OpenItemStatusSets.SETTLEABLE) {
            // Ive: genau ein Badge; die Tageszahl kommt server-berechnet aus `daysOverdue` (S5).
            dueCell.statusBadge(openItemOverdueLabel(), "danger")
            dueCell.span(openItemOverdueDaysLabel(item.daysOverdue)) { addCssClasses("text-muted small") }
        }
        numCell { moneySpan(item.amount) }
        numCell { moneySpan(item.openAmount) }
        val statusCell = cell()
        statusCell.statusBadge(openItemStatusLabel(item.status), openItemStatusColor(item.status))
        if (item.creationPostingError != null) {
            statusCell.typeBadge(tr("Nicht gebucht"), "warning")
        }
        if (showLevelColumn) {
            cell(if (item.direction == OpenItemDirection.RECEIVABLE) receivableDunningLevelLabel(item.highestDunningLevelNumber) else "–")
        }
        val actionsCell = cell()
        val actions = actionsCell.tableActionGroup()
        actions.tableActionButton("fas fa-eye", gettext("Details anzeigen")).onClick { onSelect() }
        if (OpenItemAuthzUi.canSettle(role, item)) {
            actions.tableActionButton("fas fa-money-bill-wave", gettext("Ausgleichen")).onClick { onSettle() }
        }
        if (OpenItemAuthzUi.canRetryPosting(role, item)) {
            val retry = actions.tableActionButton("fas fa-rotate-right", gettext("Nachbuchen"))
            retry.onClick { runGuardedAction(retry) { onRetry() } }
        }
    }
}

// ================================================================================================
// Detailbereich
// ================================================================================================

@Suppress("LongParameterList")
private fun renderOpenItemDetail(
    container: SimplePanel,
    detail: OpenItemDetailDto,
    role: AccountRole?,
    accounts: () -> List<LedgerAccountDto>,
    paymentMapping: () -> PaymentAccountMapping?,
    dunningSettings: () -> ReceivableDunningSettingsDto?,
    dunningLevels: () -> List<ReceivableDunningLevelDto>,
    onChanged: (OpenItemDetailDto) -> Unit,
    onReloadNeeded: () -> Unit,
) {
    val item = detail.item
    val canWrite = OpenItemAuthzUi.canWrite(role)
    val surface = container.div { addCssClasses("lapis-surface border rounded p-3") }
    val panel = surface.vPanel(spacing = 8)

    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(item.counterpartyName) { addCssClasses("fw-bold flex-grow-1") }
    headerRow.typeBadge(openItemDirectionLabel(item.direction), openItemDirectionColor(item.direction))
    headerRow.statusBadge(openItemStatusLabel(item.status), openItemStatusColor(item.status))

    val amountRow = panel.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    amountRow.hPanel(spacing = 6) {
        div(tr("Betrag:")) { addCssClasses("text-muted small") }
        moneySpan(item.amount)
    }
    amountRow.hPanel(spacing = 6) {
        div(tr("Offen:")) { addCssClasses("text-muted small") }
        moneySpan(item.openAmount)
    }
    panel.div(gettext("Belegdatum %1 · fällig am %2", item.itemDate, item.dueDate)) { addCssClasses("text-muted small") }
    if (item.daysOverdue > 0 && item.status in OpenItemStatusSets.SETTLEABLE) {
        val overdueRow = panel.hPanel(spacing = 6) { addCssClasses("align-items-center") }
        overdueRow.statusBadge(openItemOverdueLabel(), "danger")
        overdueRow.span(openItemOverdueDaysLabel(item.daysOverdue)) { addCssClasses("text-muted small") }
    }
    panel.div(
        gettext(
            "Gegenkonto %1 · %2 · %3",
            item.contraAccountNumber,
            item.contraAccountName,
            sphereLabel(item.sphere),
        ),
    ) { addCssClasses("text-muted small") }
    item.reference?.takeIf { it.isNotBlank() }?.let { panel.div(gettext("Beleg: %1", it)) { addCssClasses("text-muted small") } }
    item.note?.takeIf { it.isNotBlank() }?.let { panel.div(gettext("Notiz: %1", it)) { addCssClasses("text-muted small") } }
    if (item.status == OpenItemStatus.CANCELLED) {
        panel.div(
            gettext("Storniert am %1 -- Grund: %2", item.cancelledAt?.toString().orEmpty(), item.cancellationReason.orEmpty()),
        ) { addCssClasses("text-muted small") }
    }
    item.creationPostingError?.let { code ->
        val band = panel.div { addCssClasses("alert alert-warning mb-0") }
        band.div(tr("Dieser Posten ist angelegt, aber nicht gebucht.")) { addCssClass("fw-bold") }
        band.div(openItemPostingErrorMessage(code) ?: code) { addCssClasses("small") }
    }

    renderSettlementsSection(panel, detail, role, onChanged, onReloadNeeded)
    if (item.direction == OpenItemDirection.RECEIVABLE) {
        renderDunningNoticesSection(panel, detail, role, onChanged)
        renderDunningDisabledBand(panel, item, role, dunningSettings())
    }
    if (canWrite) {
        renderOpenItemActionBar(
            panel = panel,
            detail = detail,
            role = role,
            accounts = accounts,
            paymentMapping = paymentMapping,
            dunningLevels = dunningLevels,
            onChanged = onChanged,
            onReloadNeeded = onReloadNeeded,
        )
    }
}

/**
 * Audit-Fund M5: **Manuelles Mahnen ignoriert den Schalter bewusst.**
 * `ReceivableDunningService.issueReceivableDunningNotice` prüft `receivableDunningEnabled` nicht --
 * der Schalter steuert den AUTOMATISCHEN Mahnlauf (`ReceivableDunningPoller`), nicht den manuellen
 * Einzelfall. Das bleibt so (eine Schatzmeisterin muss auch bei abgeschaltetem Mahnlauf einen
 * Einzelfall mahnen können), darf aber nicht unsichtbar sein: solange der Schalter aus ist, sagt
 * dieses Band, dass ein manuell ausgestellter Hinweis trotzdem ausgestellt und eine hinterlegte
 * Gebühr gebucht wird.
 *
 * Nur für Rollen, die überhaupt ausstellen dürfen (BOARD liest mit, kann aber nichts auslösen), und
 * nur bei einem bestätigten `false` -- ein gescheiterter Abruf lässt [settings] `null` und das Band
 * unterbleibt.
 *
 * Audit-Fund (zweiter Durchgang): zusätzlich nur, wenn es überhaupt eine nächste Stufe gibt
 * ([OpenItemDto.nextDunningLevelNumber] `!= null`). Ohne diese Bedingung kündigte das Band eine
 * Gebührenbuchung an, die hier gar nicht auslösbar ist -- die Aktionsleiste sagt daneben "Keine
 * weitere Mahnstufe verfügbar", und `issueReceivableDunningNotice` würde mit `ConflictException`
 * ablehnen.
 */
private fun renderDunningDisabledBand(
    panel: SimplePanel,
    item: OpenItemDto,
    role: AccountRole?,
    settings: ReceivableDunningSettingsDto?,
) {
    if (settings == null || settings.receivableDunningEnabled) return
    if (item.nextDunningLevelNumber == null) return
    if (!OpenItemAuthzUi.canIssueDunningNotice(role, item)) return
    val band = panel.div { addCssClasses("alert alert-warning mb-0") }
    band.div(
        tr(
            "Forderungs-Mahnwesen ist deaktiviert; ein manuell ausgestellter Hinweis wird trotzdem ausgestellt " +
                "und eine hinterlegte Gebühr gebucht.",
        ),
    )
}

private fun renderSettlementsSection(
    panel: SimplePanel,
    detail: OpenItemDetailDto,
    role: AccountRole?,
    onChanged: (OpenItemDetailDto) -> Unit,
    onReloadNeeded: () -> Unit,
) {
    if (detail.settlements.isEmpty()) return
    panel.h2(tr("Ausgleiche")) { addCssClass("h6") }
    val table =
        panel.standardTable(
            listOf(
                TableHeader(title = tr("Art")),
                TableHeader(title = tr("Betrag"), numeric = true),
                TableHeader(title = tr("Datum"), numeric = true),
                TableHeader(title = tr("Status")),
                TableHeader(title = ""),
            ),
        )
    detail.settlements.sortedBy { it.createdAt }.forEach { settlement ->
        table.row {
            cell(openItemSettlementKindLabel(settlement.kind))
            numCell { moneySpan(settlement.amount) }
            numCell(settlement.settledOn.toString())
            val statusCell = cell()
            val postingError = settlement.postingError
            when {
                settlement.reversedAt != null -> {
                    statusCell.statusBadge(tr("Storniert"), "dark")
                    settlement.reversalReason?.let { statusCell.div(it) { addCssClasses("text-muted small") } }
                }
                postingError != null -> {
                    statusCell.statusBadge(tr("Nicht gebucht"), "warning")
                    statusCell.div(openItemPostingErrorMessage(postingError) ?: postingError) { addCssClasses("text-muted small") }
                }
                else -> statusCell.statusBadge(tr("Gebucht"), "success")
            }
            val actionsCell = cell()
            if (OpenItemAuthzUi.canWrite(role) && settlement.reversedAt == null) {
                renderSettlementActions(actionsCell, settlement, onChanged, onReloadNeeded)
            }
        }
    }
}

private fun renderSettlementActions(
    cell: SimplePanel,
    settlement: OpenItemSettlementDto,
    onChanged: (OpenItemDetailDto) -> Unit,
    onReloadNeeded: () -> Unit,
) {
    val actions = cell.tableActionGroup()
    if (settlement.postingError != null) {
        val retry = actions.tableActionButton("fas fa-rotate-right", gettext("Nachbuchen"))
        retry.onClick {
            runGuardedAction(retry) {
                // V1.4.22: `openItemGuarded` -- dieser Aufruf hat KEIN explizites Konto und braucht
                // deshalb immer das Standard-Bankkonto der Organisation. Fehlt es, war der Grund
                // vorher im generischen Konflikt-Toast unsichtbar.
                val result = openItemGuarded { rpcService<IOpenItemService>().retrySettlementPosting(settlement.id) }
                if (result != null) {
                    notifySuccess(tr("Buchung nachgeholt."))
                    onChanged(result)
                }
            }
        }
    }
    if (settlement.kind == OpenItemSettlementKind.PAYMENT && settlement.journalEntryId != null) {
        val reverse = actions.tableActionButton("fas fa-rotate-left", gettext("Zahlung stornieren"), ButtonStyle.OUTLINEDANGER)
        reverse.onClick {
            confirmWithReasonDialog(
                title = tr("Zahlung stornieren"),
                message = tr("Die Zahlung wird durch eine Gegenbuchung storniert; der Posten ist danach wieder in Höhe des Betrags offen."),
                reasonLabel = tr("Grund für die Stornierung"),
                reasonRequired = true,
                confirmLabel = tr("Zahlung stornieren"),
                reasonMaxLength = MAX_OPEN_ITEM_REASON_LENGTH,
            ) { reason ->
                runGuardedAction(reverse) {
                    val result = guarded { rpcService<IOpenItemService>().reverseSettlement(settlement.id, reason.orEmpty()) }
                    if (result != null) {
                        notifySuccess(tr("Zahlung storniert."))
                        onChanged(result)
                    }
                }
            }
        }
    }
    val nettingId = settlement.nettingId
    if (settlement.kind == OpenItemSettlementKind.NETTING && nettingId != null) {
        val reverse = actions.tableActionButton("fas fa-rotate-left", gettext("Verrechnung stornieren"), ButtonStyle.OUTLINEDANGER)
        reverse.onClick {
            confirmWithReasonDialog(
                title = tr("Verrechnung stornieren"),
                message =
                    tr(
                        "Die Verrechnung wird für BEIDE beteiligten Posten (Kreditor und Debitor) durch eine " +
                            "Gegenbuchung storniert. Das ist nur einmal möglich.",
                    ),
                reasonLabel = tr("Grund für die Stornierung"),
                reasonRequired = true,
                confirmLabel = tr("Verrechnung stornieren"),
                reasonMaxLength = MAX_OPEN_ITEM_REASON_LENGTH,
            ) { reason ->
                runGuardedAction(reverse) {
                    // S14: nach Erfolg sofort neu laden, damit der (einmalige) Knopf verschwindet.
                    val result = guarded { rpcService<IOpenItemService>().reverseNetting(nettingId, reason.orEmpty()) }
                    if (result != null) {
                        notifySuccess(tr("Verrechnung storniert."))
                        onReloadNeeded()
                    }
                }
            }
        }
    }
}

private fun renderDunningNoticesSection(
    panel: SimplePanel,
    detail: OpenItemDetailDto,
    role: AccountRole?,
    onChanged: (OpenItemDetailDto) -> Unit,
) {
    if (detail.dunningNotices.isEmpty()) return
    panel.h2(tr("Mahnhinweise")) { addCssClass("h6") }
    val table =
        panel.standardTable(
            listOf(
                TableHeader(title = tr("Stufe"), numeric = true),
                TableHeader(title = tr("Name")),
                TableHeader(title = tr("Status")),
                TableHeader(title = tr("Ausgestellt am"), numeric = true),
                TableHeader(title = tr("Antwort bis"), numeric = true),
                TableHeader(title = tr("Gebühr"), numeric = true),
                TableHeader(title = tr("Stornogrund")),
                TableHeader(title = ""),
            ),
        )
    detail.dunningNotices
        .sortedWith(compareBy({ it.cycleNumber }, { it.levelNumber }, { it.issuedAt }))
        .forEach { notice -> renderNoticeRow(table, notice, role, onChanged) }
}

private fun renderNoticeRow(
    table: Table,
    notice: ReceivableDunningNoticeDto,
    role: AccountRole?,
    onChanged: (OpenItemDetailDto) -> Unit,
) {
    table.row {
        numCell(notice.levelNumber.toString())
        cell(notice.levelName)
        val statusCell = cell()
        statusCell.statusBadge(receivableDunningNoticeStatusLabel(notice.status), receivableDunningNoticeStatusColor(notice.status))
        numCell(notice.issuedAt.toString())
        numCell(notice.respondBy.toString())
        numCell { notice.feeAmount?.let { moneySpan(it) } ?: div("–") }
        cell(notice.cancellationReason.orEmpty())
        val actionsCell = cell()
        if (OpenItemAuthzUi.canCancelDunningNotice(role, notice.status)) {
            val cancel = actionsCell.tableActionButton("fas fa-rotate-left", gettext("Stornieren"), ButtonStyle.OUTLINEDANGER)
            cancel.onClick {
                confirmWithReasonDialog(
                    title = tr("Mahnhinweis stornieren"),
                    message = tr("Der Mahnhinweis wird als storniert vermerkt."),
                    dangerNote =
                        tr(
                            "Eine bereits gebuchte Mahngebühr wird dadurch NICHT automatisch zurückgebucht. " +
                                "Es gibt weder PDF noch Briefversand -- der Hinweis existiert nur in dieser Ansicht.",
                        ),
                    reasonLabel = tr("Grund für die Stornierung"),
                    reasonRequired = true,
                    confirmLabel = tr("Stornieren"),
                    reasonMaxLength = MAX_OPEN_ITEM_REASON_LENGTH,
                ) { reason ->
                    runGuardedAction(cancel) {
                        val result =
                            guarded { rpcService<IReceivableDunningService>().cancelReceivableDunningNotice(notice.id, reason.orEmpty()) }
                        if (result != null) {
                            notifySuccess(tr("Mahnhinweis storniert."))
                            onChanged(result)
                        }
                    }
                }
            }
        }
    }
}

// ================================================================================================
// Aktionsleiste (Detail)
// ================================================================================================

@Suppress("LongParameterList")
private fun renderOpenItemActionBar(
    panel: SimplePanel,
    detail: OpenItemDetailDto,
    role: AccountRole?,
    accounts: () -> List<LedgerAccountDto>,
    paymentMapping: () -> PaymentAccountMapping?,
    dunningLevels: () -> List<ReceivableDunningLevelDto>,
    onChanged: (OpenItemDetailDto) -> Unit,
    onReloadNeeded: () -> Unit,
) {
    val item = detail.item
    val actionsRow = panel.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center mt-2") }

    if (OpenItemAuthzUi.canSettle(role, item)) {
        actionsRow.button(tr("Ausgleichen …"), style = ButtonStyle.PRIMARY).onClick {
            // N3: die bereits bekannten Ausgleich-Ids mitgeben, damit der Ergebnis-Toast den
            // WIRKLICH neu angelegten Ausgleich beschreibt und nicht den mit dem höchsten `createdAt`.
            openItemSettlementDialog(
                item = item,
                accounts = accounts,
                paymentMapping = paymentMapping,
                knownSettlementIds = detail.settlements.map { it.id }.toSet(),
            ) { onReloadNeeded() }
        }
    } else if (item.status in OpenItemStatusSets.SETTLEABLE && item.creationJournalEntryId == null) {
        actionsRow.div(tr("Noch nicht gebucht -- bitte zuerst nachbuchen, dann ausgleichen.")) { addCssClasses("text-muted small") }
    }

    if (OpenItemAuthzUi.canRetryPosting(role, item)) {
        val retry = actionsRow.button(tr("Nachbuchen"), style = ButtonStyle.OUTLINEWARNING)
        retry.onClick {
            runGuardedAction(retry) {
                val result = guarded { rpcService<IOpenItemService>().retryOpenItemPosting(item.id) }
                if (result != null) {
                    notifyRetryOutcome(result.item)
                    onChanged(result)
                }
            }
        }
    }

    if (item.status != OpenItemStatus.CANCELLED) {
        actionsRow.button(tr("Beleg/Notiz bearbeiten"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            openItemMetadataDialog(item, onChanged)
        }
    }

    val hasActiveSettlements = detail.settlements.any { it.reversedAt == null }
    if (item.status in OpenItemStatusSets.SETTLEABLE && !hasActiveSettlements) {
        val cancel = actionsRow.button(tr("Posten stornieren"), style = ButtonStyle.OUTLINEDANGER)
        cancel.onClick {
            confirmWithReasonDialog(
                title = tr("Posten stornieren"),
                message = tr("Der Posten wird storniert; eine bereits erfolgte Buchung wird durch eine Gegenbuchung aufgehoben."),
                reasonLabel = tr("Grund für die Stornierung"),
                reasonRequired = true,
                confirmLabel = tr("Posten stornieren"),
                reasonMaxLength = MAX_OPEN_ITEM_REASON_LENGTH,
            ) { reason ->
                runGuardedAction(cancel) {
                    val result = guarded { rpcService<IOpenItemService>().cancelOpenItem(item.id, reason.orEmpty()) }
                    if (result != null) {
                        notifySuccess(tr("Posten storniert."))
                        onChanged(result)
                    }
                }
            }
        }
    } else if (item.status in OpenItemStatusSets.SETTLEABLE) {
        actionsRow.div(tr("Zum Stornieren zuerst alle Ausgleiche stornieren.")) { addCssClasses("text-muted small") }
    }

    if (OpenItemAuthzUi.canIssueDunningNotice(role, item)) renderDunningActions(actionsRow, item, role, dunningLevels(), onChanged)
}

private fun renderDunningActions(
    actionsRow: SimplePanel,
    item: OpenItemDto,
    role: AccountRole?,
    activeLevels: List<ReceivableDunningLevelDto>,
    onChanged: (OpenItemDetailDto) -> Unit,
) {
    val nextLevel = item.nextDunningLevelNumber
    if (nextLevel == null) {
        actionsRow.div(tr("Keine weitere Mahnstufe verfügbar (alle aktiven Stufen genutzt oder keine konfiguriert).")) {
            addCssClasses("text-muted small")
        }
        return
    }
    val issue = actionsRow.button(gettext("Mahnhinweis Stufe %1 ausstellen", nextLevel), style = ButtonStyle.OUTLINEWARNING)
    // Audit-Fund (zweiter Durchgang): `OpenItemDto.nextDunningLevelDueOn` lag seit V1.4.15 auf der
    // Leitung und wurde seit dem B1-Fix auch gefüllt, war aber nirgends sichtbar. Der Stichtag steht
    // jetzt neben dem Knopf -- server-berechnet (Fälligkeit + Wartefrist der Stufe), nie hier
    // hergeleitet (S5). Kein Sperrgrund: manuelles Mahnen ist auch vor dem Stichtag erlaubt.
    item.nextDunningLevelDueOn?.let { dueOn ->
        actionsRow.div(gettext("Nächste Stufe fällig am %1", dueOn)) { addCssClasses("text-muted small") }
    }
    // Audit-Fund M5: die Gebühr benennen, statt sie mit "ist für die Stufe eine Gebühr hinterlegt"
    // offenzulassen -- die aktiven Stufen liegen dem Screen vor. Fehlt die Stufe in der Liste (Abruf
    // gescheitert oder gerade geändert), bleibt der bisherige, bewusst unbestimmte Text stehen.
    val nextLevelFee = activeLevels.firstOrNull { it.levelNumber == nextLevel }?.feeAmount
    issue.onClick {
        confirmDialog(
            title = tr("Mahnhinweis ausstellen"),
            message =
                if (nextLevelFee != null && nextLevelFee.toDouble() > 0.0) {
                    gettext(
                        "Mahnhinweis der Stufe %1 jetzt ausstellen? Dabei wird eine Mahngebühr von %2 gebucht. " +
                            "Es wird kein PDF erzeugt und kein Brief versendet.",
                        nextLevel,
                        formatMoney(nextLevelFee),
                    )
                } else {
                    gettext(
                        "Mahnhinweis der Stufe %1 jetzt ausstellen? Ist für die Stufe eine Gebühr hinterlegt, wird sie " +
                            "gebucht. Es wird kein PDF erzeugt und kein Brief versendet.",
                        nextLevel,
                    )
                },
            confirmLabel = tr("Jetzt ausstellen"),
        ) {
            runGuardedAction(issue) {
                val result = guarded { rpcService<IReceivableDunningService>().issueReceivableDunningNotice(item.id) }
                if (result != null) {
                    notifySuccess(tr("Mahnhinweis ausgestellt."))
                    onChanged(result)
                }
            }
        }
    }
    if (OpenItemAuthzUi.canSkipDunningLevel(role, item)) {
        val skip = actionsRow.button(tr("Stufe überspringen"), style = ButtonStyle.OUTLINESECONDARY)
        skip.onClick {
            confirmWithReasonDialog(
                title = tr("Mahnstufe überspringen"),
                message = tr("Die nächste Mahnstufe wird ohne Mahnhinweis als übersprungen vermerkt."),
                reasonLabel = tr("Grund"),
                reasonRequired = true,
                confirmLabel = tr("Überspringen"),
                reasonMaxLength = MAX_OPEN_ITEM_REASON_LENGTH,
            ) { reason ->
                runGuardedAction(skip) {
                    val result = guarded { rpcService<IReceivableDunningService>().skipReceivableDunningLevel(item.id, reason.orEmpty()) }
                    if (result != null) {
                        notifySuccess(tr("Mahnstufe übersprungen."))
                        onChanged(result)
                    }
                }
            }
        }
    }
}

/**
 * S4: Doppelklick-Schutz. `guarded {}` wirft [kotlinx.coroutines.CancellationException] weiter -- ein
 * `button.disabled = false` NACH dem `guarded`-Aufruf liefe bei einem Abbruch nie. Deshalb immer
 * try/finally (Muster `LedgerScreen.saveButton`).
 */
internal fun runGuardedAction(
    button: Button?,
    block: suspend () -> Unit,
) {
    button?.disabled = true
    AppScope.launch {
        try {
            block()
        } finally {
            button?.disabled = false
        }
    }
}

// ================================================================================================
// Anlegen-Formular
// ================================================================================================

private fun renderOpenItemCreateForm(
    host: SimplePanel,
    role: AccountRole?,
    accounts: () -> List<LedgerAccountDto>,
    defaultDirection: OpenItemDirection?,
    onCreated: (OpenItemDetailDto) -> Unit,
    onCancel: () -> Unit,
    // Called by the screen when the ledger accounts arrive (or are re-fetched) while the form is
    // already open, so the "Gegenkonto" select is rebuilt instead of staying at "(bitte wählen)".
    registerAccountsLoadedListener: ((() -> Unit) -> Unit) = {},
) {
    val surface = host.div { addCssClasses("lapis-surface border rounded p-3") }
    val panel = surface.vPanel(spacing = 6)
    panel.h2(tr("Posten anlegen")) { addCssClass("h6") }

    val directionSelect =
        panel.select(
            options =
                listOf("" to tr("(bitte wählen)")) +
                    OpenItemDirection.entries.map { it.name to openItemDirectionLabel(it) },
            value = defaultDirection?.name.orEmpty(),
            label = tr("Richtung"),
        )
    val nameInput = panel.text(label = tr("Gegenpartei"))
    val prefillHint = panel.div().apply { addCssClasses("text-muted small") }
    val today = todayLocalDate()
    val itemDateInput = panel.text(value = today.toString(), label = tr("Belegdatum (JJJJ-MM-TT)"))
    val dueDateInput =
        panel.text(
            value = today.plus(DatePeriod(days = OPEN_ITEMS_DEFAULT_DUE_DAYS)).toString(),
            label = tr("Fällig am (JJJJ-MM-TT)"),
        )
    val amountInput = panel.text(label = tr("Betrag in EUR (z. B. 1234,56)"))
    val contraSelect = panel.select(options = listOf("" to tr("(bitte wählen)")), value = "", label = tr("Gegenkonto"))
    val contraHint = panel.div().apply { addCssClasses("text-muted small") }
    val sphereSelect =
        panel.select(
            options = GemeinnuetzigkeitSphere.entries.map { it.name to sphereLabel(it) },
            value = GemeinnuetzigkeitSphere.IDEELLER_BEREICH.name,
            label = tr("Sphäre"),
        )
    val referenceInput = panel.text(label = tr("Belegnummer (optional)"))
    val noteInput = panel.text(label = tr("Notiz (optional)"))

    // CRM-Verknüpfung: `ICrmService.listContacts` ist BOARD/ADMIN -- ein TREASURER bekäme einen stillen
    // 403-Toast (K3-Verwandter), deshalb nur für [OpenItemAuthzUi.canLinkCrmContact].
    val crmSelect =
        if (OpenItemAuthzUi.canLinkCrmContact(role)) {
            panel.select(options = listOf("" to tr("(kein CRM-Kontakt)")), value = "", label = tr("CRM-Kontakt (optional)"))
        } else {
            null
        }
    if (crmSelect != null) {
        AppScope.launch {
            val page = guarded { rpcService<ICrmService>().listContacts(limit = CRM_CONTACT_LIMIT) } ?: return@launch
            crmSelect.options = listOf("" to tr("(kein CRM-Kontakt)")) + page.items.map { it.id to it.displayName }
        }
    }

    fun selectedDirection(): OpenItemDirection? = directionSelect.value?.let { runCatching { OpenItemDirection.valueOf(it) }.getOrNull() }

    fun rebuildContraOptions() {
        val direction = selectedDirection()
        if (direction == null) {
            contraSelect.options = listOf("" to tr("(bitte wählen)"))
            contraHint.content = tr("Zuerst die Richtung wählen -- sie bestimmt den passenden Kontotyp.")
            return
        }
        val expected = expectedContraAccountType(direction)
        val candidates = accounts().filter { it.type == expected }
        // Keep a still-valid selection (an accounts reload must not wipe a choice already made); a
        // direction change switches the expected type, so the old id is then no longer a candidate.
        val previous = contraSelect.value
        contraSelect.options = listOf("" to tr("(bitte wählen)")) + candidates.map { it.id to "${it.accountNumber} · ${it.name}" }
        contraSelect.value = if (previous != null && candidates.any { it.id == previous }) previous else ""
        contraHint.content =
            if (direction == OpenItemDirection.PAYABLE) {
                tr("Zur Auswahl stehen Aufwandskonten.")
            } else {
                tr("Zur Auswahl stehen Ertragskonten.")
            }
    }
    rebuildContraOptions()
    registerAccountsLoadedListener { rebuildContraOptions() }

    // Raskin-Ruling: Vorbelegung aus dem letzten Posten derselben Gegenpartei. Nie eine getroffene Wahl überschreiben.
    var prefillTimer: Int? = null

    fun requestPrefill() {
        val direction = selectedDirection() ?: return
        val name = nameInput.value?.trim().orEmpty()
        if (name.isEmpty() || !contraSelect.value.isNullOrBlank()) return
        AppScope.launch {
            val defaults =
                guarded { rpcService<IOpenItemService>().getCounterpartyDefaults(name, direction) }?.firstOrNull() ?: return@launch
            if (!contraSelect.value.isNullOrBlank()) return@launch
            if (accounts().any { it.id == defaults.contraAccountId && it.type == expectedContraAccountType(direction) }) {
                contraSelect.value = defaults.contraAccountId
                sphereSelect.value = defaults.sphere.name
                prefillHint.content = tr("Konto und Sphäre aus dem letzten Posten dieser Gegenpartei vorbelegt.")
            }
        }
    }
    nameInput.subscribe {
        prefillHint.content = ""
        prefillTimer?.let { window.clearTimeout(it) }
        prefillTimer = window.setTimeout({ requestPrefill() }, OPEN_ITEMS_PREFILL_DEBOUNCE_MS)
    }
    directionSelect.subscribe {
        rebuildContraOptions()
        prefillHint.content = ""
        requestPrefill()
    }

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttons = panel.hPanel(spacing = 8)
    val saveButton = buttons.button(tr("Posten anlegen"), style = ButtonStyle.PRIMARY)
    buttons.button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).onClick { onCancel() }

    saveButton.onClick {
        errorBox.hide()
        val direction = selectedDirection()
        val itemDate = runCatching { LocalDate.parse(itemDateInput.value.orEmpty().trim()) }.getOrNull()
        val dueDate = runCatching { LocalDate.parse(dueDateInput.value.orEmpty().trim()) }.getOrNull()
        val amount = parseAmountInput(amountInput.value)
        val contraAccountId = contraSelect.value?.takeIf { it.isNotBlank() }
        val reference = referenceInput.value?.trim()?.takeIf { it.isNotEmpty() }
        val note = noteInput.value?.trim()?.takeIf { it.isNotEmpty() }
        val validationError =
            validateOpenItemForm(direction, nameInput.value.orEmpty(), itemDate, dueDate, amount, contraAccountId, reference, note)
        if (validationError != null) {
            errorBox.content = validationError
            errorBox.show()
            return@onClick
        }
        val input =
            OpenItemInput(
                direction = direction!!,
                counterpartyName = nameInput.value.orEmpty().trim(),
                crmContactId = crmSelect?.value?.takeIf { it.isNotBlank() },
                reference = reference,
                itemDate = itemDate!!,
                dueDate = dueDate!!,
                amount = (amount as AmountInput.Valid).value,
                contraAccountId = contraAccountId!!,
                sphere =
                    sphereSelect.value?.let { runCatching { GemeinnuetzigkeitSphere.valueOf(it) }.getOrNull() }
                        ?: GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                note = note,
            )
        runGuardedAction(saveButton) {
            val created = guarded { rpcService<IOpenItemService>().createOpenItem(input) }
            if (created != null) onCreated(created)
        }
    }
}
