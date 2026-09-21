package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.AnnualFinancialStatementDto
import network.lapis.cloud.shared.domain.AnonymousDonationDutyDto
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.CostCenterReportDto
import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonorDutyDto
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.KassenbuchDto
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PostingSnapshot
import network.lapis.cloud.shared.domain.ReserveMovementDto
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.SphereAmountDto
import network.lapis.cloud.shared.domain.StatementLineDto
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto
import network.lapis.cloud.shared.domain.VatRateLineDto

/**
 * The row model of the report grammar (Welle V1.4.27, W3 "Pseudo-Tabellen") -- the "figures vault" of the
 * wave: pure, no widget, no DOM, testable under `jsTest` without any harness.
 *
 * ## Why a separate model
 *
 * Before W3 every report screen assembled its `hPanel` rows with the figures interleaved with layout
 * (`width = 130.px`). Moving those screens onto a real `<table>` must not change a single displayed
 * figure. So the derivation of "which strings does this report show, in which order" lives HERE, once per
 * report, transcribed word for word from the old renderer; the renderer ([reportRows] in
 * `DataScreenLayout.kt`) only paints [ReportRow]s. `ReportGoldenTest` pins the strings, and
 * `ReportCellOracleDomTest` proves the pinned strings equal what the table actually shows.
 *
 * ## Rules for every derivation below
 *
 * - Server order is server order. The ONLY sort is the existing `sortedBy { it.accountNumber }` in
 *   [statementSectionRows].
 * - No sum, sign or rounding is recomputed. The single exception is the caller-supplied Soll/Haben sum
 *   of the posting confirmation ([postingConfirmRows]) -- a not-yet-posted entry has no server figure.
 * - Empty cells stay [ReportCell.Blank] (text `""`), a missing voucher stays `"--"`.
 * - `warnIfNegative` exactly where the old renderer had it.
 *
 * **The "Summe ..." label (audit fix B):** [statementSectionRows] builds its total label with
 * `trFormat(tr("Summe %1"), title)`. Before, it was `gettext("Summe %1", title)` with a `tr(...)`-marked title, exactly
 * like the old `renderStatementLineTable`: `I18n.trans` resolves a marker only as a PREFIX, so the visible label was
 * "Summe ###KvI18nS###Einnahmen" (a defect that existed before W3 and was first pinned by the golden test, then fixed
 * in the audit round of the same wave). Now the label is a composed `tr` string: it keeps the marker prefix, so the
 * widget re-resolves it on every render, and `I18nCatalogManager` resolves the title argument per language -- the
 * label reads "Summe Einnahmen" and follows a language switch like every other label of the model.
 */
enum class ReportRowKind { SECTION, SUBSECTION, DATA, BALANCE, TOTAL, NOTE }

/**
 * One cell of a report. [text] is the string that MUST appear in the DOM -- golden tests pin only [text].
 * Amounts carry the [Decimal] on, so the renderer calls the unchanged `moneySpan(amount, warnIfNegative)`
 * (no re-formatting, no rounding).
 */
sealed interface ReportCell {
    val text: String

    data class Text(
        override val text: String,
        val muted: Boolean = false,
        val italic: Boolean = false,
    ) : ReportCell

    data class Money(
        val amount: Decimal,
        val warnIfNegative: Boolean = false,
        val emphasize: Boolean = false,
    ) : ReportCell {
        override val text: String get() = formatMoney(amount)
    }

    data class Ltr(
        val amount: Decimal,
        val warnIfNegative: Boolean = false,
    ) : ReportCell {
        override val text: String get() = formatLtr(amount)
    }

    /** `typeBadge` (outline) or, with [filled], `statusBadge`; [text] is the badge label. */
    data class Badge(
        override val text: String,
        val color: String,
        val filled: Boolean = false,
    ) : ReportCell

    /** Several badges in one cell (the two §25-PartG duty badges); [text] is their `textContent`, i.e. the labels concatenated. */
    data class Badges(
        val items: List<Badge>,
    ) : ReportCell {
        override val text: String get() = items.joinToString("") { it.text }
    }

    data object Blank : ReportCell {
        override val text: String get() = ""
    }
}

/**
 * [strong] is a presentation modifier of a [ReportRowKind.TOTAL] row that closes a whole report (the GuV result, audit
 * MINOR-3): it must read heavier than the section sums above it. It is NOT a figure and not part of [cellTexts].
 */
class ReportRow(
    val kind: ReportRowKind,
    val cells: List<ReportCell>,
    val strong: Boolean = false,
)

/** The cell strings of a row list -- the one form every golden test compares. */
fun List<ReportRow>.cellTexts(): List<List<String>> = map { row -> row.cells.map { it.text } }

/** The kind of every row -- pinned next to [cellTexts] so a `TOTAL` cannot silently become plain data. */
fun List<ReportRow>.kinds(): List<ReportRowKind> = map { it.kind }

private val BLANK = ReportCell.Blank

private fun text(value: String) = ReportCell.Text(value)

private fun money(
    amount: Decimal,
    warn: Boolean = false,
) = ReportCell.Money(amount, warnIfNegative = warn)

private fun row(
    kind: ReportRowKind,
    vararg cells: ReportCell,
) = ReportRow(kind, cells.toList())

private fun sectionRow(
    label: String,
    kind: ReportRowKind = ReportRowKind.SECTION,
) = row(kind, text(label))

private fun noteRow(label: String) = row(ReportRowKind.NOTE, text(label))

/** The single row of a detail table that has no data (a fiscal year without reserve movements): an em dash, no sentence. */
internal const val NO_ROWS_PLACEHOLDER = "—"

// ============================================================================================
// Finanzberichte
// ============================================================================================

/**
 * One StatementLineDto section: optional section label, the lines sorted by account number, the
 * "Summe {title}" total. Reused by GuV, Bilanz, Jahresabschluss and the Vier-Sphaeren details (3 columns:
 * Konto, Kontenklasse, Betrag).
 *
 * [title] is passed through untouched -- marker included -- into the total label ([trFormat], see the note on
 * this file) AND into the SECTION label: the renderer (`span`/`HeaderCell` content) resolves the `tr(...)`
 * marker, so the label follows a language switch. Resolving it here (`gettext`) would freeze it in the language of
 * the moment the report was loaded.
 */
fun statementSectionRows(
    title: String,
    lines: List<StatementLineDto>,
    total: Decimal,
    showSectionLabel: Boolean,
    sectionKind: ReportRowKind = ReportRowKind.SECTION,
): List<ReportRow> =
    buildList {
        if (showSectionLabel) add(sectionRow(title, sectionKind))
        if (lines.isEmpty()) {
            add(noteRow(tr("Keine Buchungen in diesem Abschnitt.")))
        } else {
            lines.sortedBy { it.accountNumber }.forEach { line ->
                add(
                    row(
                        ReportRowKind.DATA,
                        text(gettext("%1 · %2", line.accountNumber, line.name)),
                        text(line.accountClass.toString()),
                        money(line.balance),
                    ),
                )
            }
        }
        add(row(ReportRowKind.TOTAL, text(trFormat(tr("Summe %1"), title)), BLANK, money(total)))
    }

fun incomeStatementRows(dto: IncomeStatementDto): List<ReportRow> =
    statementSectionRows(tr("Einnahmen"), dto.incomeLines, dto.totalIncome, showSectionLabel = true) +
        statementSectionRows(tr("Ausgaben"), dto.expenseLines, dto.totalExpense, showSectionLabel = true) +
        ReportRow(ReportRowKind.TOTAL, listOf(text(tr("Ergebnis")), BLANK, money(dto.result, warn = true)), strong = true)

fun balanceSheetRows(dto: BalanceSheetDto): List<ReportRow> =
    buildList {
        add(sectionRow(tr("Aktiva")))
        addAll(statementSectionRows(tr("Aktiva"), dto.assetLines, dto.totalAssets, showSectionLabel = false))
        add(sectionRow(tr("Passiva")))
        addAll(
            statementSectionRows(
                tr("Verbindlichkeiten"),
                dto.liabilityLines,
                dto.totalLiabilities,
                showSectionLabel = true,
                sectionKind = ReportRowKind.SUBSECTION,
            ),
        )
        addAll(
            statementSectionRows(
                tr("Eigenkapital (gebucht)"),
                dto.equityLines,
                dto.bookedEquity,
                showSectionLabel = true,
                sectionKind = ReportRowKind.SUBSECTION,
            ),
        )
        add(
            row(
                ReportRowKind.BALANCE,
                ReportCell.Text(tr("Kumuliertes Ergebnis (Σ Einnahmen − Ausgaben seit Gründung)"), italic = true),
                BLANK,
                money(dto.accumulatedResult, warn = true),
            ),
        )
        add(row(ReportRowKind.TOTAL, text(tr("Summe Passiva + Eigenkapital")), BLANK, money(dto.totalEquityAndLiabilities)))
    }

/** The two key figures of the Jahresabschluss -- period result and accumulated result stay separate rows. */
fun annualKeyFigureRows(dto: AnnualFinancialStatementDto): List<ReportRow> =
    listOf(
        row(ReportRowKind.DATA, text(tr("Jahresergebnis (dieses Geschäftsjahr)")), money(dto.periodResult, warn = true)),
        row(ReportRowKind.DATA, text(tr("Kumuliertes Ergebnis (seit Gründung)")), money(dto.accumulatedResult, warn = true)),
    )

// ============================================================================================
// Gemeinnuetzigkeits-Berichte
// ============================================================================================

/** The action column (detail toggle) is NOT part of the row: it is not a figure. */
fun fourSphereRows(dto: FourSphereIncomeStatementDto): List<ReportRow> =
    buildList {
        dto.spheres.forEach { sphere ->
            add(
                row(
                    ReportRowKind.DATA,
                    ReportCell.Badge(sphereLabel(sphere.sphere), sphereColor(sphere.sphere)),
                    money(sphere.totalIncome),
                    money(sphere.totalExpense),
                    money(sphere.result, warn = true),
                ),
            )
        }
        add(
            row(
                ReportRowKind.TOTAL,
                text(tr("Gesamt")),
                money(dto.totalIncome),
                money(dto.totalExpense),
                money(dto.result, warn = true),
            ),
        )
    }

fun useOfFundsRows(dto: UseOfFundsStatementDto): List<ReportRow> =
    buildList {
        if (dto.years.isEmpty()) add(noteRow(tr("Keine Buchungen im gewählten Zeitraum.")))
        dto.years.forEach { year ->
            add(
                row(
                    ReportRowKind.DATA,
                    text(year.fiscalYear.toString()),
                    money(year.fundsReceived),
                    money(year.fundsUsed),
                    money(year.fundsAllocatedToReserves, warn = true),
                    money(year.timelyUseObligationRemaining),
                    ReportCell.Money(year.overdueAmount, emphasize = hasOverdueAmount(year.overdueAmount)),
                ),
            )
        }
        add(
            row(
                ReportRowKind.TOTAL,
                text(tr("Gesamt")),
                money(dto.totalFundsReceived),
                money(dto.totalFundsUsed),
                money(dto.totalFundsAllocatedToReserves, warn = true),
                money(dto.closingTimelyUseObligation),
                money(dto.closingOverdue),
            ),
        )
    }

/**
 * The `FREIE_RUECKLAGE` caveat ("(gesetzliche Obergrenze hier nicht geprüft)") is a NOTE row directly after
 * the affected row -- a `div` between table rows is invalid markup.
 */
fun reserveMovementRows(movements: List<ReserveMovementDto>): List<ReportRow> =
    buildList {
        // Audit MINOR-8: a year without reserve movements shows one "—" row instead of a table that is only a header.
        if (movements.isEmpty()) add(noteRow(NO_ROWS_PLACEHOLDER))
        movements.forEach { movement ->
            add(
                row(
                    ReportRowKind.DATA,
                    ReportCell.Badge(reserveTypeLabel(movement.reserveType), reserveTypeColor(movement.reserveType)),
                    money(movement.allocated, warn = true),
                    money(movement.closingBalance),
                ),
            )
            if (movement.reserveType == ReserveType.FREIE_RUECKLAGE) {
                add(noteRow(tr("(gesetzliche Obergrenze hier nicht geprüft)")))
            }
        }
    }

fun sphereAmountRows(amounts: List<SphereAmountDto>): List<ReportRow> =
    if (amounts.isEmpty()) {
        listOf(noteRow(NO_ROWS_PLACEHOLDER)) // Audit MINOR-8, see reserveMovementRows
    } else {
        amounts.map { entry ->
            row(ReportRowKind.DATA, ReportCell.Badge(sphereLabel(entry.sphere), sphereColor(entry.sphere)), money(entry.amount))
        }
    }

/** Empty [lines] give an empty list -- the caller shows its own "no bookings" text instead of a table. */
fun vatRateLineRows(
    lines: List<VatRateLineDto>,
    total: Decimal,
): List<ReportRow> =
    if (lines.isEmpty()) {
        emptyList()
    } else {
        lines.map { line ->
            row(
                ReportRowKind.DATA,
                text(vatRateLabel(line.rate)),
                money(line.grossTotal),
                money(line.netTotal),
                money(line.vatTotal),
                text(line.postingCount.toString()),
            )
        } + row(ReportRowKind.TOTAL, text(tr("Gesamt")), BLANK, BLANK, money(total), BLANK)
    }

/** The three ReportCell columns of the two §25-PartG report grids (F2, DonorsScreen). */
fun donorDutyRows(duties: List<DonorDutyDto>): List<ReportRow> =
    duties.map { duty ->
        val dutyBadges =
            buildList {
                if (duty.promptReportRequired) {
                    val d = DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED
                    add(ReportCell.Badge(donationDutyLabel(d), donationDutyColor(d), filled = true))
                }
                if (duty.annualDisclosureRequired) {
                    val d = DonationDuty.ANNUAL_DISCLOSURE_REQUIRED
                    add(ReportCell.Badge(donationDutyLabel(d), donationDutyColor(d), filled = true))
                }
            }
        row(
            ReportRowKind.DATA,
            text(duty.donorDisplayName),
            ReportCell.Badge(donorTypeLabel(duty.donorType), donorTypeColor(duty.donorType)),
            ReportCell.Badge(donorCategoryLabel(duty.donorCategory), donorCategoryColor(duty.donorCategory)),
            money(duty.annualTotal),
            ReportCell.Badges(dutyBadges),
        )
    }

fun anonymousForwardingRows(forwarding: List<AnonymousDonationDutyDto>): List<ReportRow> =
    forwarding.map { entry ->
        val duty = DonationDuty.ANONYMOUS_FORWARDING_REQUIRED
        row(
            ReportRowKind.DATA,
            text(entry.entryDate.toString()),
            money(entry.amount),
            ReportCell.Badge(donationDutyLabel(duty), donationDutyColor(duty), filled = true),
        )
    }

// ============================================================================================
// Hauptbuch / Kassenbuch / Kostenstellen / Buchungsbestaetigung / Pruefprotokoll
// ============================================================================================

fun generalLedgerRows(dto: GeneralLedgerDto): List<ReportRow> =
    buildList {
        add(
            row(
                ReportRowKind.BALANCE,
                BLANK,
                ReportCell.Text(tr("Eröffnungssaldo"), muted = true, italic = true),
                BLANK,
                BLANK,
                money(dto.openingBalance),
            ),
        )
        if (dto.lines.isEmpty()) add(noteRow(tr("Keine Buchungen im gewählten Zeitraum.")))
        dto.lines.forEach { line ->
            add(
                row(
                    ReportRowKind.DATA,
                    text(line.entryDate.toString()),
                    text(line.description),
                    if (line.side == PostingSide.DEBIT) money(line.amount) else BLANK,
                    if (line.side == PostingSide.CREDIT) money(line.amount) else BLANK,
                    money(line.runningBalance),
                ),
            )
        }
        add(row(ReportRowKind.BALANCE, BLANK, text(tr("Schlusssaldo")), BLANK, BLANK, money(dto.closingBalance)))
    }

/** Nr. is the GoBD sequence -- rendered in server order, never sorted. */
fun kassenbuchRows(dto: KassenbuchDto): List<ReportRow> =
    buildList {
        add(
            row(
                ReportRowKind.BALANCE,
                BLANK,
                BLANK,
                ReportCell.Text(tr("Eröffnungssaldo"), muted = true, italic = true),
                BLANK,
                BLANK,
                BLANK,
                money(dto.openingBalance),
            ),
        )
        if (dto.lines.isEmpty()) add(noteRow(tr("Keine Buchungen im gewählten Zeitraum.")))
        dto.lines.forEach { line ->
            add(
                row(
                    ReportRowKind.DATA,
                    text(line.kassenbuchNumber.toString()),
                    text(line.entryDate.toString()),
                    text(line.description),
                    text(line.voucherReference ?: "--"),
                    if (line.amountIn.toDouble() != 0.0) money(line.amountIn) else BLANK,
                    if (line.amountOut.toDouble() != 0.0) money(line.amountOut) else BLANK,
                    money(line.runningBalance),
                ),
            )
        }
        add(row(ReportRowKind.BALANCE, BLANK, BLANK, text(tr("Schlusssaldo")), BLANK, BLANK, BLANK, money(dto.closingBalance)))
    }

/**
 * D14: "Nicht zugeordnet" is a BALANCE row (muted/italic) after the code-sorted cost centers, the server's
 * own grand total a TOTAL row -- never re-summed from the rows.
 */
fun costCenterReportRows(report: CostCenterReportDto): List<ReportRow> =
    buildList {
        if (report.costCenters.isEmpty()) add(noteRow(tr("Keine Kostenstelle mit Buchungen im gewählten Zeitraum.")))
        report.costCenters.forEach { costCenter ->
            add(
                row(
                    ReportRowKind.DATA,
                    text(costCenterResultLabel(costCenter)),
                    money(costCenter.totalIncome),
                    money(costCenter.totalExpense),
                    money(costCenter.result, warn = true),
                ),
            )
        }
        add(
            row(
                ReportRowKind.BALANCE,
                ReportCell.Text(tr("— Nicht zugeordnet —"), muted = true, italic = true),
                money(report.unassignedIncome),
                money(report.unassignedExpense),
                money(report.unassignedResult, warn = true),
            ),
        )
        add(
            row(
                ReportRowKind.TOTAL,
                text(tr("Gesamt")),
                money(report.totalIncome),
                money(report.totalExpense),
                money(report.result, warn = true),
            ),
        )
    }

/**
 * The Soll/Haben table of the posting confirmation. [debitSum]/[creditSum] come from the caller
 * (`sumPostingLines`, unchanged) -- this is a not-yet-posted entry, no server figure exists.
 */
internal fun postingConfirmRows(
    lines: List<PostingLineDisplay>,
    showVatColumn: Boolean,
    debitSum: Decimal,
    creditSum: Decimal,
): List<ReportRow> =
    buildList {
        lines.forEach { line ->
            val cells =
                mutableListOf<ReportCell>(
                    text(line.accountLabel),
                    if (line.side == PostingSide.DEBIT) money(line.amount) else BLANK,
                    if (line.side == PostingSide.CREDIT) money(line.amount) else BLANK,
                    ReportCell.Text(line.sphereLabel, muted = true),
                    ReportCell.Text(line.costCenterLabel ?: "--", muted = true),
                )
            if (showVatColumn) {
                val vatText =
                    if (line.vatAmount != null) {
                        gettext("%1 (%2)", line.vatRateLabel, formatMoney(line.vatAmount))
                    } else {
                        line.vatRateLabel.orEmpty()
                    }
                cells += ReportCell.Text(vatText, muted = true)
            }
            add(ReportRow(ReportRowKind.DATA, cells))
        }
        val total =
            mutableListOf<ReportCell>(text("Σ"), money(debitSum), money(creditSum), BLANK, BLANK)
        if (showVatColumn) total += BLANK
        add(ReportRow(ReportRowKind.TOTAL, total))
    }

/**
 * The posting lines of a journal entry (LedgerScreen detail, audit fix E): the amount lands in exactly ONE of the two
 * columns Soll/Haben per [PostingDto.side]. Server order, no sum row -- the figures are the ones the server returned.
 */
fun journalPostingRows(postings: List<PostingDto>): List<ReportRow> =
    postings.map { posting ->
        row(
            ReportRowKind.DATA,
            text(gettext("%1 · %2", posting.ledgerAccountNumber, posting.ledgerAccountName)),
            if (posting.side == PostingSide.DEBIT) money(posting.amount) else BLANK,
            if (posting.side == PostingSide.CREDIT) money(posting.amount) else BLANK,
            ReportCell.Badge(sphereLabel(posting.sphere), sphereColor(posting.sphere)),
            text(posting.costCenterCode?.let { gettext("%1 · %2", it, posting.costCenterName) } ?: "--"),
            // V1.4.13: text, never a colour -- and no VAT amount here, the amount above is GROSS (see vatRateLabel).
            text(vatRateLabel(posting.vatRate)),
        )
    }

fun journalPostingSnapshotRows(postings: List<PostingSnapshot>): List<ReportRow> =
    postings.map { posting ->
        row(
            ReportRowKind.DATA,
            text(posting.ledgerAccountId),
            text(postingSideLabel(posting.side)),
            money(posting.amount),
            ReportCell.Badge(sphereLabel(posting.sphere), sphereColor(posting.sphere)),
        )
    }
