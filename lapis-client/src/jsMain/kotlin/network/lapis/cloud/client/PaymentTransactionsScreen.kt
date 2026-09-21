package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PaymentTransactionDto
import network.lapis.cloud.shared.domain.PaymentTransactionQuery
import network.lapis.cloud.shared.rpc.IPaymentGatewayService

/** Server-default page size. */
private const val PAYMENT_TRANSACTIONS_PAGE_SIZE = 50

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `/payment-transactions`, TREASURER/
 * BOARD/ADMIN. Paged table over `listPaymentTransactions`, with a prominent "Nur nicht gebucht"
 * filter (`unreconciledOnly`) -- the treasurer's work queue for `UNPOSTED`-adjacent rows (a
 * `payment_transaction` with `journalEntryId == null`), each showing its `reconciliationNote`.
 *
 * Welle V1.4.26 (W2 der UI/UX-Richtlinie): die Tabelle laeuft ueber [dataTable] (kompakte Dichte,
 * Betrag/Datum rechtsbuendig mit Tabellenziffern, unter 768 px Kartenliste). Die Offset-Paginierung mit
 * "Mehr laden" ist unveraendert; neu sind Lade-, Fehler- und getrennte Leerzustaende sowie eine
 * clientseitige Suche ueber die geladene Teilmenge (der Server hat keinen Suchparameter -- deshalb sagt
 * der Zaehler ausdruecklich, dass gezaehlt wird, was geladen ist, siehe [dataCountText]).
 */
fun renderPaymentTransactionsScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    root.pageHeader(tr("Zahlungseingänge"))

    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val searchInput = filterRow.text(label = tr("Suche nach Mitglied oder Hinweis"))
    val unreconciledOnlyCheck = filterRow.checkBox(label = tr("Nur nicht gebuchte Zahlungen"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)

    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val statusRegion = root.dataStatusRegion()
    val tableHost = root.vPanel(spacing = 8)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var offset = 0
    var totalCount = 0
    var hasMore = false
    var searchTerm = ""
    var generation = 0
    val loaded = mutableListOf<PaymentTransactionDto>()
    var loading = false
    // Fehlerzustand des Erstabrufs: solange er steht, darf die Suche ihn nicht wegzeichnen.
    var failed = false

    lateinit var loadPage: (Boolean) -> Unit

    fun renderList() {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): solange der erste Ladevorgang läuft, sagt allein
        // die Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld während
        // des Ladens den Leertext über den Ladehinweis -- „lädt" und „keine Daten" gleichzeitig.
        if (loading && loaded.isEmpty()) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen (kein „Noch keine …" darüber).
        if (failed) return
        tableHost.removeAll()
        if (loaded.isEmpty()) {
            countsLabel.content = ""
            tableHost.p(paymentTransactionsEmptyText(unreconciledOnlyCheck.value)) { addCssClasses("text-muted") }
            return
        }
        val visible = filterPaymentTransactions(loaded, searchTerm)
        countsLabel.content =
            dataCountText(
                shown = visible.size,
                loaded = loaded.size,
                total = totalCount,
                hasMore = hasMore,
                filtered = searchTerm.isNotBlank(),
            )
        if (visible.isEmpty()) {
            tableHost.p(loadedSubsetNoMatchText(term = searchTerm.trim(), hasMore = hasMore)) { addCssClasses("text-muted") }
            return
        }
        tableHost.dataTable(columns = paymentTransactionColumns(), rows = visible)
    }

    loadPage = { reset ->
        if (reset) {
            generation++
            offset = 0
            totalCount = 0
            hasMore = false
            loaded.clear()
            tableHost.removeAll()
            countsLabel.content = ""
            loadMoreButton.hide()
            statusRegion.showLoading()
            loading = true
            failed = false
        }
        val mine = generation
        val requestOffset = offset
        val requestUnreconciledOnly = unreconciledOnlyCheck.value
        loadMoreButton.disabled = true
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IPaymentGatewayService>().listPaymentTransactions(
                        PaymentTransactionQuery(
                            unreconciledOnly = requestUnreconciledOnly,
                            limit = PAYMENT_TRANSACTIONS_PAGE_SIZE,
                            offset = requestOffset,
                        ),
                    )
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat übernommen
            statusRegion.clearStatus()
            loading = false
            loadMoreButton.disabled = false
            if (page == null) {
                loadMoreButton.hide()
                // `guarded` hat den Toast schon gezeigt; beim Neuladen ersetzt der Fehlerzustand mit
                // `Erneut versuchen` das vorher stumm leere Panel.
                if (reset) {
                    failed = true
                    tableHost.dataErrorState(onRetry = { loadPage(true) })
                }
                return@launch
            }
            loaded += page.rows
            offset += page.rows.size
            totalCount = page.totalCount
            hasMore = loaded.size < totalCount && page.rows.isNotEmpty()
            if (hasMore) loadMoreButton.show() else loadMoreButton.hide()
            renderList()
        }
    }

    refreshButton.onClick { loadPage(true) }
    loadMoreButton.onClick { loadPage(false) }
    // Der Arbeitsvorrat-Filter wirkt sofort -- gleiches `subscribe`-Muster wie die Statusfilter in
    // `OpenItemsScreen`; `Aktualisieren` bleibt für das erneute Holen derselben Auswahl. Der Guard gegen
    // das synthetische erste `subscribe`-Ereignis verhindert einen zweiten Abruf beim Seitenaufbau.
    var isInitialFilterEvent = true
    unreconciledOnlyCheck.subscribe {
        if (isInitialFilterEvent) {
            isInitialFilterEvent = false
            return@subscribe
        }
        loadPage(true)
    }

    var isInitialSearchEvent = true
    var debounceHandle: Int? = null
    searchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        debounceHandle?.let { window.clearTimeout(it) }
        debounceHandle =
            window.setTimeout({
                searchTerm = value.orEmpty()
                renderList()
            }, 300)
    }

    loadPage(true)
}

/**
 * Clientseitige Suche über die geladene Teilmenge (pur, siehe `PaymentTransactionsScreenTest`):
 * Mitgliedsname oder Abstimmungshinweis, ohne Groß-/Kleinschreibung. Die rohe `memberId` ist bewusst
 * nicht durchsuchbar -- sie ist eine technische Kennung, kein Text, den jemand eintippt.
 */
internal fun filterPaymentTransactions(
    transactions: List<PaymentTransactionDto>,
    search: String,
): List<PaymentTransactionDto> {
    val term = search.trim()
    if (term.isEmpty()) return transactions
    return transactions.filter {
        it.memberDisplayName?.contains(term, ignoreCase = true) == true ||
            it.reconciliationNote?.contains(term, ignoreCase = true) == true
    }
}

/**
 * „Noch keine Daten" -- getrennt danach, ob der Arbeitsvorrat-Filter aktiv ist (R41). Ein leerer
 * „nur nicht gebucht"-Vorrat ist die gute Nachricht und wird auch so benannt.
 */
internal fun paymentTransactionsEmptyText(unreconciledOnly: Boolean): String =
    if (unreconciledOnly) {
        gettext("Alle Zahlungseingänge sind gebucht.")
    } else {
        gettext("Noch keine Zahlungseingänge erfasst.")
    }

/** Spalten der Zahlungstabelle / Kartenliste; das Mitglied ist die Identität der Zeile, also der Kartentitel. */
private fun paymentTransactionColumns(): List<DataColumn<PaymentTransactionDto>> =
    listOf(
        textColumn(title = tr("Mitglied"), primary = true) { transaction: PaymentTransactionDto ->
            transaction.memberDisplayName ?: transaction.memberId ?: "—"
        },
        textColumn(title = tr("Art")) { transaction: PaymentTransactionDto -> paymentIntentLabel(transaction.intent) },
        DataColumn(
            title = tr("Betrag"),
            numeric = true,
            cell = { container, transaction -> container.moneySpan(transaction.amount) },
        ),
        DataColumn(
            title = tr("Status"),
            cell = { container, transaction ->
                container.statusBadge(
                    paymentTransactionStatusLabel(transaction.status),
                    paymentTransactionStatusColor(transaction.status),
                )
            },
        ),
        textColumn(title = tr("Eingegangen"), numeric = true) { transaction: PaymentTransactionDto ->
            transaction.receivedAt.toString()
        },
        DataColumn(
            title = tr("Gebucht"),
            cell = { container, transaction ->
                // Farbe ist nie der einzige Kanal (R/P4): „Nein" steht als Wort da, die Rotfärbung
                // verstärkt es nur -- unverändert gegenüber V1.2.8.
                if (transaction.journalEntryId == null) {
                    container.div(tr("Nein")) { addCssClasses("text-danger fw-bold") }
                } else {
                    container.div(tr("Ja"))
                }
            },
        ),
        textColumn(title = tr("Hinweis")) { transaction: PaymentTransactionDto -> transaction.reconciliationNote.orEmpty() },
    )
