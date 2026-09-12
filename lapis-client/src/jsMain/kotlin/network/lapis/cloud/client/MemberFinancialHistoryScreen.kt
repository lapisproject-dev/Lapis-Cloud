package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_DEBIT_IN_FLIGHT
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_OUTSTANDING
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_PAID
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_WAIVED
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.DONATION
import network.lapis.cloud.shared.domain.FinancialHistoryYearDto
import network.lapis.cloud.shared.domain.MemberFinancialHistoryDto
import network.lapis.cloud.shared.rpc.IContributionReliefService
import network.lapis.cloud.shared.rpc.IMemberFinancialHistoryService

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- reachable two ways: [ContributionsScreen]'s "Vollständige
 * Beitragshistorie" link (own history, `requestedMemberId == null`), and
 * [MemberAdministrationScreen]'s roster row action (TREASURER/BOARD/ADMIN viewing another member's
 * history, `requestedMemberId` set). Route-level auth is `requireAuth` only, see
 * `Routes.MEMBER_FINANCES` KDoc -- the narrower cross-member threshold is enforced entirely
 * server-side by `MemberFinancialHistoryService` ([network.lapis.cloud.shared.rpc
 * .ForbiddenException] surfaces as a toast via [guarded]).
 *
 * **Vier getrennte Kacheln, keine Gesamtsumme** -- see [MemberFinancialHistoryDto] KDoc. The four
 * pure helper functions below ([financialHistoryKindLabel]/[financialHistoryKindColor]/
 * [memberFinancesRoute]/[financialHistoryYearHeading]/[financialHistoryEmptyStateText]) are the
 * only DOM-free surface of this screen -- see `MemberFinancialHistoryScreenTest`.
 */
fun renderMemberFinancialHistoryScreen(
    container: SimplePanel,
    requestedMemberId: String?,
) {
    val session =
        AppState.session ?: run {
            navigateTo(Routes.LOGIN)
            return
        }
    val effectiveId = requestedMemberId ?: session.memberId
    val isSelf = effectiveId == session.memberId

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 760.px
            marginTop = 24.px
        }

    AppScope.launch {
        val dto = guarded { rpcService<IMemberFinancialHistoryService>().getMemberFinancialHistory(effectiveId) } ?: return@launch
        renderFinancialHistoryHead(root, dto, isSelf)
        renderExemptionBadge(root, effectiveId, isSelf)
        renderFinancialHistoryTiles(root, dto, isSelf)
        renderFinancialHistoryYears(root, dto)
    }
}

/**
 * Welle V1.4.10.1, Punkt D -- **Korrektur der ursprünglichen Task-Beschreibung**: kein `MemberDto`-
 * Feld (README/CHANGELOG bestätigen explizit "exemption data is not exposed on `MemberDto`") --
 * separater [IContributionReliefService.getExemptionState]-RPC-Aufruf. Rollen-Gate exakt wie im
 * Interface-KDoc dokumentiert ("Self-or-BOARD/ADMIN/TREASURER, gleiches Gate wie
 * `IContributionService.getMemberContributionSummary`"): ein `TREASURER`, der die Historie eines
 * FREMDEN Mitglieds ansieht, sieht auch dessen Befreiungsstatus -- ein `MEMBER`, der (per Rollen-
 * Gate von [renderMemberFinancialHistoryScreen] selbst gar nicht erst erreichbar) versucht, die
 * Historie eines anderen Mitglieds zu öffnen, würde stattdessen eine `ForbiddenException` von
 * `getMemberFinancialHistory` selbst sehen -- dieser zusätzliche Check hier ist eine bewusste
 * Verteidigung in der Tiefe, kein Ersatz für das server-seitige Gate von [IContributionReliefService
 * .getExemptionState] selbst.
 */
private fun renderExemptionBadge(
    root: SimplePanel,
    effectiveId: String,
    isSelf: Boolean,
) {
    if (!isSelf && !AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) return
    AppScope.launch {
        val state = guarded { rpcService<IContributionReliefService>().getExemptionState(effectiveId) } ?: return@launch
        val from = state.exemptFrom ?: return@launch
        val label =
            state.exemptUntil?.let { until -> gettext("Beitragsbefreit ab %1 bis %2", from, until) }
                ?: gettext("Beitragsbefreit ab %1", from)
        root.typeBadge(label, "info")
    }
}

private fun renderFinancialHistoryHead(
    root: SimplePanel,
    dto: MemberFinancialHistoryDto,
    isSelf: Boolean,
) {
    val headPanel = root.vPanel(spacing = 2)
    if (isSelf) {
        headPanel.h1(tr("Ihre Beitragshistorie"))
    } else {
        if (dto.anonymized) {
            val nameRow = headPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            nameRow.h1(dto.memberDisplayName)
            nameRow.typeBadge(tr("DSGVO-gelöscht"), "secondary")
        } else {
            headPanel.h1(dto.memberDisplayName)
        }
        headPanel.h2(tr("Beitragshistorie"))
    }
    headPanel.div(gettext("Mitglied seit %1", dto.joinedAt))
    dto.friendSince?.let { friendSince -> headPanel.div(gettext("Förderer seit %1", friendSince)) }
}

private fun renderFinancialHistoryTiles(
    root: SimplePanel,
    dto: MemberFinancialHistoryDto,
    isSelf: Boolean,
) {
    val tiles = root.hPanel(spacing = 16) { addCssClasses("flex-wrap") }

    fun tile(
        headline: String,
        amount: Decimal,
    ): SimplePanel {
        val tile = tiles.vPanel(spacing = 2) { addCssClasses("border rounded p-2") }
        tile.div(headline) { addCssClass("text-muted") }
        tile.moneySpan(amount)
        return tile
    }

    tile(gettext("Gezahlte Beiträge seit %1", dto.joinedAt), dto.contributionsPaid)
    tile(tr("Erlassen"), dto.contributionsWaived)
    val outstandingTile = tile(tr("Offen"), dto.contributionsOutstanding)
    tile(tr("Gespendet"), dto.donationsTotal)

    // D-Selbstsicht-Sonderfall: nur die eigene Sicht bekommt den Zahlungsweg-Link, und nur wenn
    // tatsächlich etwas offen ist -- ein Betrag wird hier nicht nur festgestellt, sondern mit dem
    // Zahlungsweg verbunden. Keine string-inspizierende Prüfung -- typisierter Vergleich, gleiche
    // Konvention wie [moneySpan]'s eigener `warnIfNegative`.
    if (isSelf && dto.contributionsOutstanding.toDouble() > 0.0) {
        val payButton = outstandingTile.button(tr("Offene Beiträge bezahlen"), style = ButtonStyle.LINK)
        payButton.onClick { navigateTo(Routes.CONTRIBUTIONS) }
    }
}

private fun renderFinancialHistoryYears(
    root: SimplePanel,
    dto: MemberFinancialHistoryDto,
) {
    if (dto.years.isEmpty()) {
        root.p(financialHistoryEmptyStateText(dto.joinedAt))
        return
    }
    dto.years.forEach { year -> renderFinancialHistoryYear(root, year) }
}

private fun renderFinancialHistoryYear(
    root: SimplePanel,
    year: FinancialHistoryYearDto,
) {
    root.h2(financialHistoryYearHeading(year))
    // A year block can carry `entries.isEmpty()` on purpose -- a negatively netting year (a storno
    // booked in a LATER year) is kept via the server's `donationsTotal.signum() != 0` filter
    // (MemberFinancialHistoryService.kt) so its non-zero Spenden total still shows in the heading
    // above. There is nothing to put IN a body row for it, though -- rendering the table anyway
    // would draw a striped table with the five column headers and zero data rows, the same empty
    // shell the server-side fix (Review MINOR, net-zero year) was meant to prevent (Review MINOR).
    if (year.entries.isEmpty()) return
    val tableWrapper = root.div { addCssClass("table-responsive") }
    val table =
        tableWrapper.table(
            headerNames = listOf(tr("Datum"), tr("Art"), tr("Beschreibung"), tr("Betrag"), tr("Status")),
            types = setOf(TableType.STRIPED, TableType.HOVER),
        )
    year.entries.forEach { entry ->
        table.row {
            cell(entry.date.toString())
            cell { typeBadge(financialHistoryKindLabel(entry.kind), financialHistoryKindColor(entry.kind)) }
            cell(entry.label)
            cell { moneySpan(entry.amount) }
            cell {
                val status = entry.contributionStatus
                if (status != null) statusBadge(contributionStatusLabel(status), contributionStatusColor(status))
            }
        }
    }
}

internal fun financialHistoryKindLabel(kind: FinancialHistoryEntryKind): String =
    when (kind) {
        CONTRIBUTION_PAID -> gettext("Beitrag bezahlt")
        CONTRIBUTION_WAIVED -> gettext("Beitrag erlassen")
        CONTRIBUTION_OUTSTANDING -> gettext("Beitrag offen")
        CONTRIBUTION_DEBIT_IN_FLIGHT -> gettext("Lastschrift läuft")
        DONATION -> gettext("Spende")
    }

internal fun financialHistoryKindColor(kind: FinancialHistoryEntryKind): String =
    when (kind) {
        CONTRIBUTION_PAID -> "success"
        CONTRIBUTION_WAIVED -> "info"
        CONTRIBUTION_OUTSTANDING -> "warning"
        CONTRIBUTION_DEBIT_IN_FLIGHT -> "secondary"
        DONATION -> "primary"
    }

/** `memberFinancesRoute(null) == Routes.MEMBER_FINANCES`; a set [memberId] appends `?member=<id>`. */
internal fun memberFinancesRoute(memberId: String?): String =
    if (memberId == null) Routes.MEMBER_FINANCES else "${Routes.MEMBER_FINANCES}?member=$memberId"

internal fun financialHistoryYearHeading(year: FinancialHistoryYearDto): String =
    gettext("%1 · Beiträge %2 · Spenden %3", year.year, formatMoney(year.contributionsPaid), formatMoney(year.donationsTotal))

internal fun financialHistoryEmptyStateText(joinedAt: LocalDate): String =
    gettext("Noch keine Zahlungen erfasst — Mitglied seit %1.", joinedAt)
