package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
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
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaMandateDto
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.ISepaService

/**
 * V1.2.2 SEPA-Client-UI wave -- Plan §2.6/§4.2. Route-gated TREASURER/BOARD/ADMIN (see
 * `Routes.SEPA_MANDATES` KDoc); the narrower TREASURER/ADMIN on-behalf-grant tier is gated
 * in-screen via [SepaAuthzUi.canGrantOnBehalf].
 */
fun renderSepaMandatesScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 860.px
            marginTop = 24.px
        }
    root.h1(tr("SEPA-Mandate"))

    val canGrantOnBehalf = SepaAuthzUi.canGrantOnBehalf(AppState.session?.role)

    root.h2(tr("Mandate")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val statusOptions =
        listOf(
            "" to tr("Alle"),
            SepaMandateStatus.ACTIVE.name to sepaMandateStatusLabel(SepaMandateStatus.ACTIVE),
            SepaMandateStatus.REVOKED.name to sepaMandateStatusLabel(SepaMandateStatus.REVOKED),
            SepaMandateStatus.EXPIRED.name to sepaMandateStatusLabel(SepaMandateStatus.EXPIRED),
        )
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var lastGrantedAt: LocalDateTime? = null
    var currentTable: Table? = null

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            lastGrantedAt = null
            currentTable = null
        }
        val status = statusSelect.value?.takeIf { it.isNotBlank() }?.let { SepaMandateStatus.valueOf(it) }
        AppScope.launch {
            val mandates =
                sepaGuarded(tr(SEPA_READ_CONFLICT_MESSAGE)) {
                    rpcService<ISepaService>().listMandates(status = status, beforeGrantedAt = if (reset) null else lastGrantedAt)
                }
            if (mandates == null) {
                loadMoreButton.hide()
                return@launch
            }
            if (mandates.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Mandate für diese Filter gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            val table =
                currentTable ?: listPanel
                    .table(
                        headerNames =
                            listOf(
                                tr("Mitglied"),
                                tr("Mandatsreferenz"),
                                tr("IBAN"),
                                tr("Status"),
                                tr("Erteilt am"),
                                tr("Erfasst von"),
                                tr("Aktionen"),
                            ),
                        types = setOf(TableType.STRIPED, TableType.HOVER),
                    ).also { currentTable = it }
            mandates.forEach { mandate -> renderSepaMandateRow(table, mandate) { loadPage(reset = true) } }
            lastGrantedAt = mandates.last().grantedAt
            if (mandates.size < SEPA_MANDATES_PAGE_SIZE) loadMoreButton.hide() else loadMoreButton.show()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)

    if (canGrantOnBehalf) {
        root.h2(tr("Mandat im Namen eines Mitglieds erfassen")) { addCssClass("h5") }
        val formHost = root.vPanel(spacing = 4)
        AppScope.launch {
            val members = guarded { rpcService<IMemberService>().listMembers() } ?: return@launch
            val memberOptions = members.map { it.id to it.displayName }
            renderSepaMandateForm(
                container = formHost,
                onBehalf = true,
                defaultDebtorName = "",
                memberOptions = memberOptions,
            ) {
                loadPage(reset = true)
            }
        }
        root.p(tr("Es können höchstens 10 Mandate pro Minute erfasst werden.")) { addCssClasses("text-muted small") }
    }
}

private const val SEPA_MANDATES_PAGE_SIZE = 50

internal const val SEPA_READ_CONFLICT_MESSAGE = "SEPA-Lastschrift ist für diese Organisation nicht aktiviert."

private fun renderSepaMandateRow(
    table: Table,
    mandate: SepaMandateDto,
    onChanged: () -> Unit,
) {
    table.row {
        cell(mandate.memberDisplayName)
        cell(mandate.mandateReference)
        cell(formatIbanLast4(mandate.debtorIbanLast4))
        val statusCell = cell()
        statusCell.statusBadge(sepaMandateStatusLabel(mandate.status), sepaMandateStatusColor(mandate.status))
        cell(mandate.grantedAt.toString())
        cell(if (mandate.createdBySelf) tr("Selbst") else mandate.createdByDisplayName)

        val actionsCell = cell()
        val ownMandate = mandate.memberId == AppState.session?.memberId
        if (SepaAuthzUi.canRevokeMandateOf(AppState.session?.role, ownMandate, mandate.status)) {
            val revokeButton = actionsCell.button(tr("Widerrufen"), style = ButtonStyle.OUTLINEDANGER)
            revokeButton.onClick {
                confirmWithReasonDialog(
                    title = tr("Mandat widerrufen"),
                    message = tr("Mandat wirklich widerrufen?"),
                    reasonLabel = tr("Grund (optional)"),
                    reasonRequired = false,
                    confirmLabel = tr("Widerrufen"),
                ) { reason ->
                    revokeButton.disabled = true
                    AppScope.launch {
                        val result = guarded { rpcService<ISepaService>().revokeMandate(mandate.id, reason) }
                        revokeButton.disabled = false
                        if (result != null) {
                            notifySuccess(tr("Mandat widerrufen."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}
