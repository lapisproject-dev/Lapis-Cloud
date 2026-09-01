package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.i18n.tr
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
 */
fun renderPaymentTransactionsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 960.px
            marginTop = 24.px
        }
    root.h1(tr("Zahlungseingänge"))

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val unreconciledOnlyCheck = filterRow.checkBox(label = tr("Nur nicht gebuchte Zahlungen"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)

    val tableHost = root.vPanel(spacing = 8)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var offset = 0
    val loaded = mutableListOf<PaymentTransactionDto>()
    lateinit var table: Table

    fun renderRow(transaction: PaymentTransactionDto) {
        table.row {
            cell(paymentIntentLabel(transaction.intent))
            cell(formatMoney(transaction.amount))
            cell(transaction.memberDisplayName ?: transaction.memberId ?: "—")
            cell { statusBadge(paymentTransactionStatusLabel(transaction.status), paymentTransactionStatusColor(transaction.status)) }
            cell(transaction.receivedAt.toString())
            cell(transaction.journalEntryId?.let { tr("Ja") } ?: tr("Nein")) {
                if (transaction.journalEntryId == null) addCssClass("text-danger fw-bold")
            }
            cell(transaction.reconciliationNote.orEmpty())
        }
    }

    fun loadPage(reset: Boolean) {
        if (reset) {
            offset = 0
            loaded.clear()
            tableHost.removeAll()
            table =
                tableHost.table(
                    headerNames =
                        listOf(
                            tr("Art"),
                            tr("Betrag"),
                            tr("Mitglied"),
                            tr("Status"),
                            tr("Eingegangen"),
                            tr("Gebucht"),
                            tr("Hinweis"),
                        ),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
        }
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IPaymentGatewayService>().listPaymentTransactions(
                        PaymentTransactionQuery(
                            unreconciledOnly = unreconciledOnlyCheck.value,
                            limit = PAYMENT_TRANSACTIONS_PAGE_SIZE,
                            offset = offset,
                        ),
                    )
                } ?: return@launch
            page.rows.forEach { renderRow(it) }
            loaded += page.rows
            offset += page.rows.size
            loadMoreButton.show()
            if (loaded.size >= page.totalCount || page.rows.isEmpty()) {
                loadMoreButton.hide()
            }
            if (loaded.isEmpty()) {
                tableHost.div(tr("Keine Zahlungen gefunden.")) { addCssClasses("text-muted small") }
            }
        }
    }

    refreshButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }

    loadPage(reset = true)
}
