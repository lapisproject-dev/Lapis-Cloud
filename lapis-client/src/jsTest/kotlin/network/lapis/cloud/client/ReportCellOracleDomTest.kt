package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PostingSnapshot
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.27 (W3): the ORACLE of the report rows. The expected values of `ReportGoldenTest` are not typed from
 * the new code. In commit 1 of the wave they were proven against what the OLD renderer (`hPanel` rows) really
 * put on the page (see the git history of this file); since the migration the same derivations are proven
 * against the `<table>`s the report screens render now ([assertTableOracle]) -- so the proof chain for the
 * figures runs "legacy DOM -> golden strings -> table DOM" and this file is the "golden strings -> table DOM"
 * half. A change to `ReportRows.kt` therefore has to keep the table and the derivation in agreement, not just
 * the derivation with itself.
 *
 * Strict comparison, blank cells included; only blanks at the END of a row are dropped on both sides (the action
 * column is not a figure). Text cells of the model carry the `tr(...)` marker (so a label follows a language
 * switch, see `ReportRows.kt`); the DOM shows it resolved, so the expected side is resolved the same way
 * ([resolvedAttributeText]) before comparing.
 */
class ReportCellOracleDomTest {
    private val figureKinds = setOf(ReportRowKind.DATA, ReportRowKind.BALANCE, ReportRowKind.TOTAL)

    /**
     * Scrapes the `<table>`s of [host] (figure rows only -- no section/note rows,
     * nothing inside a collapsed detail row) and compares STRICTLY with the derivation, blank cells included
     * (only blanks at the END of a row are dropped on both sides: the action column is not a figure).
     */
    private fun scrapeTableRows(host: HTMLElement): List<List<String>> {
        val trs = host.querySelectorAll("tbody tr")
        return (0 until trs.length)
            .map { trs.item(it) as Element }
            .filter {
                !it.classList.contains("lapis-section-row") &&
                    !it.classList.contains("lapis-note-row") &&
                    it.closest("tr.d-none") == null
            }.map { row ->
                trimTrailingBlanks(
                    (0 until row.children.length).map {
                        row.children
                            .item(it)!!
                            .textContent
                            .orEmpty()
                            .trim()
                    },
                )
            }
    }

    private fun trimTrailingBlanks(cells: List<String>): List<String> = cells.dropLastWhile { it.isBlank() }

    private fun assertTableOracle(
        id: String,
        rows: List<ReportRow>,
        render: (SimplePanel) -> Unit,
    ) {
        withMountedRoot(id) { root, element ->
            render(root)
            val expected =
                rows.filter { it.kind in figureKinds }.map { row ->
                    trimTrailingBlanks(row.cells.map { resolvedAttributeText(it.text) })
                }
            assertEquals(expected, scrapeTableRows(element()), "$id: the table shows something else than the derivation (a figure changed)")
        }
    }

    @Test
    fun statementSection_incomeLines() =
        assertTableOracle(
            "oracle-statement-section",
            statementSectionRows(io.kvision.i18n.tr("Einnahmen"), incomeLines, d(1513.0), showSectionLabel = true),
        ) { renderStatementSectionTable(it, io.kvision.i18n.tr("Einnahmen"), incomeLines, d(1513.0)) }

    @Test
    fun statementSection_emptyLines() =
        assertTableOracle(
            "oracle-statement-empty",
            statementSectionRows(io.kvision.i18n.tr("Ausgaben"), emptyList(), d(0.0), showSectionLabel = true),
        ) { renderStatementSectionTable(it, io.kvision.i18n.tr("Ausgaben"), emptyList(), d(0.0)) }

    @Test
    fun incomeStatement_profit() =
        assertTableOracle("oracle-guv", incomeStatementRows(incomeStatement)) {
            renderIncomeStatementBody(it, incomeStatement)
        }

    @Test
    fun incomeStatement_lossKeepsSign() =
        assertTableOracle("oracle-guv-loss", incomeStatementRows(lossStatement)) {
            renderIncomeStatementBody(it, lossStatement)
        }

    @Test
    fun balanceSheet() =
        assertTableOracle("oracle-bilanz", balanceSheetRows(balanceSheet)) {
            renderBalanceSheetBody(it, balanceSheet)
        }

    @Test
    fun annualStatement_wholeView() =
        assertTableOracle(
            "oracle-jahresabschluss",
            incomeStatementRows(annualStatement.incomeStatement) +
                balanceSheetRows(annualStatement.balanceSheet) +
                annualKeyFigureRows(annualStatement),
        ) { renderAnnualFinancialStatementBody(it, annualStatement) }

    @Test
    fun fourSphere() =
        assertTableOracle("oracle-vier-sphaeren", fourSphereRows(fourSphereStatement)) {
            renderFourSphereIncomeStatementBody(it, fourSphereStatement)
        }

    @Test
    fun useOfFunds() = assertTableOracle("oracle-mittelverwendung", useOfFundsRows(useOfFunds)) { renderUseOfFundsBody(it, useOfFunds) }

    @Test
    fun reserveMovements() =
        assertTableOracle("oracle-ruecklagen", reserveMovementRows(reserveMovements)) {
            renderReserveMovementsTable(it, reserveMovements)
        }

    @Test
    fun sphereAmounts() =
        assertTableOracle(
            "oracle-sphaeren-betraege",
            sphereAmountRows(sphereAmounts),
        ) { renderSphereAmountTable(it, "Titel", sphereAmounts) }

    @Test
    fun vatRateLines() =
        assertTableOracle("oracle-ust", vatRateLineRows(vatLines, d(26.0))) { renderVatRateLinesTable(it, "Titel", vatLines, d(26.0)) }

    @Test
    fun generalLedger() =
        assertTableOracle("oracle-hauptbuch", generalLedgerRows(generalLedger)) { renderHauptbuchLines(it, generalLedger) }

    @Test
    fun generalLedger_empty() =
        assertTableOracle("oracle-hauptbuch-leer", generalLedgerRows(emptyGeneralLedger)) { renderHauptbuchLines(it, emptyGeneralLedger) }

    @Test
    fun kassenbuch() = assertTableOracle("oracle-kassenbuch", kassenbuchRows(kassenbuch)) { renderKassenbuchLines(it, kassenbuch) }

    @Test
    fun costCenterReport() =
        assertTableOracle("oracle-kostenstellen", costCenterReportRows(costCenterReport)) {
            renderCostCenterReportBody(it, costCenterReport)
        }

    @Test
    fun postingConfirm_withoutVat() {
        val lines = confirmLines(withVat = false)
        assertTableOracle("oracle-buchungsbestaetigung", postingConfirmRows(lines, false, d(200.0), d(200.0))) {
            renderPostingConfirmTable(it, lines, false)
        }
    }

    @Test
    fun postingConfirm_withVat() {
        val lines = confirmLines(withVat = true)
        assertTableOracle("oracle-buchungsbestaetigung-ust", postingConfirmRows(lines, true, d(200.0), d(200.0))) {
            renderPostingConfirmTable(it, lines, true)
        }
    }

    @Test
    fun journalSnapshotPostings() {
        val snapshot = journalSnapshot()
        assertTableOracle("oracle-snapshot", journalPostingSnapshotRows(snapshot.postings)) {
            renderJournalEntrySnapshotBody(it, snapshot)
        }
    }

    @Test
    fun journalPostings() {
        assertTableOracle("oracle-journal-buchungszeilen", journalPostingRows(journalPostings)) {
            renderPostingsTable(it, journalPostings)
        }
    }

    // Audit H: a large amount with more decimals than a cent, and a single fiscal year -- the strings of the tables equal
    // the strings of the derivation, which the golden test pins against the old `"$amount €"` path.
    @Test
    fun bigAmounts_useOfFundsWithASingleYear() =
        assertTableOracle("oracle-gross-mittelverwendung", useOfFundsRows(bigSingleYearUseOfFunds)) {
            renderUseOfFundsBody(it, bigSingleYearUseOfFunds)
        }

    @Test
    fun bigAmounts_incomeStatement() =
        assertTableOracle("oracle-gross-guv", incomeStatementRows(bigAmountStatement)) {
            renderIncomeStatementBody(it, bigAmountStatement)
        }

    @Test
    fun donorDuties() = assertTableOracle("oracle-spender", donorDutyRows(donorDuties)) { renderDonorDutiesTable(it, donorDuties) }

    @Test
    fun anonymousForwarding() =
        assertTableOracle(
            "oracle-anonym",
            anonymousForwardingRows(anonymousForwarding),
        ) { renderAnonymousForwardingTable(it, anonymousForwarding) }

    private fun confirmLines(withVat: Boolean) =
        listOf(
            PostingLineDisplay(
                "1200 · Bank",
                PostingSide.DEBIT,
                d(200.0),
                "Ideeller Bereich",
                null,
                if (withVat) "19 %" else null,
                if (withVat) d(31.9) else null,
            ),
            PostingLineDisplay(
                "8400 · Erlöse",
                PostingSide.CREDIT,
                d(200.0),
                "Zweckbetrieb",
                "FEST · Sommerfest",
                if (withVat) "7 %" else null,
                null,
            ),
        )

    private fun journalSnapshot() =
        JournalEntrySnapshot(
            entryDate = LocalDate(2026, 3, 1),
            description = "Beitrag",
            voucherReference = null,
            status = JournalEntryStatus.POSTED,
            postedAt = null,
            createdBy = "m-1",
            donorMemberId = null,
            externalDonorId = null,
            donorCategory = null,
            postings =
                listOf(
                    PostingSnapshot("acc-1200", PostingSide.DEBIT, d(200.0), GemeinnuetzigkeitSphere.IDEELLER_BEREICH, null),
                    PostingSnapshot("acc-8400", PostingSide.CREDIT, d(200.0), GemeinnuetzigkeitSphere.ZWECKBETRIEB, null),
                ),
        )
}
