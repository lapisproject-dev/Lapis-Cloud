package network.lapis.cloud.client

import io.kvision.i18n.I18n
import io.kvision.i18n.tr
import network.lapis.cloud.client.ReportRowKind.DATA
import network.lapis.cloud.client.ReportRowKind.NOTE
import network.lapis.cloud.client.ReportRowKind.SECTION
import network.lapis.cloud.client.ReportRowKind.SUBSECTION
import network.lapis.cloud.client.ReportRowKind.TOTAL
import network.lapis.cloud.shared.domain.PostingSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import network.lapis.cloud.client.ReportRowKind.BALANCE as BAL

/**
 * Welle V1.4.27 (W3), commit 1: the GOLDEN strings of every report row -- pinned once, complete (blank cells and
 * the [ReportRowKind] of every row included) and never changed by a layout commit. If a later commit turns this
 * test red, a figure changed.
 *
 * The expected cells were not produced from the new derivation code: `ReportCellOracleDomTest` proves the same
 * derivation against the old renderer's DOM (all figure rows), the amounts are spelled `d(x)` + " €" -- the two
 * things `formatMoney` is made of.
 *
 * The "Summe ..." total label of a statement section (audit fix B): before, `gettext("Summe %1", title)` with a
 * `tr(...)` title leaked the raw i18n marker ("Summe ###KvI18nS###Einnahmen") and this test pinned the leak. Now the
 * label is a composed `tr` string ([trFormat], see `ReportRows.kt`): the RAW model text pins the encoding ([sum]), and
 * [statementSection_sumLabelReadsCleanlyAndFollowsTheLanguage] pins what the user reads -- "Summe Einnahmen", no
 * marker, translated and with the title translated too.
 */
class ReportGoldenTest {
    private fun m(value: Double) = "${d(value)} €"

    private val marker = "###KvI18nS###"

    /**
     * A static label of the model: `tr(...)` in `ReportRows.kt`, so it carries the marker and is resolved by the
     * renderer at paint time -- it follows a language switch. (`gettext` here would freeze it in the language
     * of the moment the report was loaded; see [staticLabels_stayLiveTranslatable_notResolvedOnce].)
     */
    private fun t(label: String) = marker + label

    /** The composed total label: the template's marker prefix, then the title argument behind the separator. */
    private fun sum(title: String) = "${marker}Summe %1\u0001$marker$title"

    @Test
    fun statementSection_isSortedByAccountNumber() {
        val rows = statementSectionRows(tr("Einnahmen"), incomeLines, d(1513.0), showSectionLabel = true)
        assertEquals(
            listOf(
                listOf(t("Einnahmen")),
                listOf("0400 · Mitgliedsbeiträge", "4", m(300.0)),
                listOf("400 · Spenden", "4", m(12.5)),
                listOf("8400 · Erlöse 19 %", "8", m(1200.5)),
                listOf(sum("Einnahmen"), "", m(1513.0)),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(SECTION, DATA, DATA, DATA, TOTAL), rows.kinds())
    }

    @Test
    fun statementSection_sumLabelReadsCleanlyAndFollowsTheLanguage() {
        val rows = statementSectionRows(tr("Einnahmen"), incomeLines, d(1513.0), showSectionLabel = true)
        val label = rows.last().cells[0].text
        // what the user reads: no marker anywhere, in the source language ...
        assertEquals("Summe Einnahmen", I18n.trans(label))
        assertTrue(!I18n.trans(label).contains("###"), "the i18n marker leaked into the label")
        // ... and after a switch: template AND title are looked up in the new language
        withTranslations(mapOf("Summe %1" to "Total %1", "Einnahmen" to "Income")) {
            assertEquals("Total Income", I18n.trans(label))
        }
        // a title without a translation stays as it is (no marker, no crash)
        withTranslations(mapOf("Summe %1" to "Total %1")) {
            assertEquals("Total Einnahmen", I18n.trans(label))
        }
    }

    @Test
    fun statementSection_emptyListSaysSoAndStillHasItsTotal() {
        val rows = statementSectionRows(tr("Ausgaben"), emptyList(), d(0.0), showSectionLabel = true)
        assertEquals(
            listOf(listOf(t("Ausgaben")), listOf(t("Keine Buchungen in diesem Abschnitt.")), listOf(sum("Ausgaben"), "", m(0.0))),
            rows.cellTexts(),
        )
        assertEquals(listOf(SECTION, NOTE, TOTAL), rows.kinds())
    }

    @Test
    fun statementSection_withoutSectionLabel_hasNoSectionRow() {
        val rows = statementSectionRows(tr("Aktiva"), balanceSheet.assetLines, d(5100.5), showSectionLabel = false)
        assertEquals(
            listOf(
                listOf("1000 · Kasse", "1", m(100.5)),
                listOf("1200 · Bank", "1", m(5000.0)),
                listOf(sum("Aktiva"), "", m(5100.5)),
            ),
            rows.cellTexts(),
        )
    }

    @Test
    fun incomeStatement_profit_endsWithTheServersResult() {
        val rows = incomeStatementRows(incomeStatement)
        assertEquals(
            listOf(
                listOf(t("Einnahmen")),
                listOf("0400 · Mitgliedsbeiträge", "4", m(300.0)),
                listOf("400 · Spenden", "4", m(12.5)),
                listOf("8400 · Erlöse 19 %", "8", m(1200.5)),
                listOf(sum("Einnahmen"), "", m(1513.0)),
                listOf(t("Ausgaben")),
                listOf("6100 · Porto", "6", m(40.5)),
                listOf("6300 · Raummiete", "6", m(250.0)),
                listOf(sum("Ausgaben"), "", m(290.5)),
                listOf(t("Ergebnis"), "", m(1222.5)),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(SECTION, DATA, DATA, DATA, TOTAL, SECTION, DATA, DATA, TOTAL, TOTAL), rows.kinds())
        assertTrue((rows.last().cells[2] as ReportCell.Money).warnIfNegative, "the result is warned when negative")
    }

    @Test
    fun incomeStatement_onlyTheClosingResultRowIsStrong() {
        val rows = incomeStatementRows(incomeStatement)
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, true), rows.map { it.strong })
        assertTrue(balanceSheetRows(balanceSheet).none { it.strong }, "only the GuV result closes a report this heavily")
    }

    @Test
    fun incomeStatement_lossKeepsTheServersSignAndFlagsIt() {
        val rows = incomeStatementRows(lossStatement)
        assertEquals(listOf(t("Ergebnis"), "", m(-290.5)), rows.cellTexts().last())
        assertTrue(rows.cellTexts().last()[2].startsWith("-"))
        assertTrue((rows.last().cells[2] as ReportCell.Money).warnIfNegative)
    }

    @Test
    fun balanceSheet_hasSectionsSubsectionsTheAccumulatedResultAndBothTotals() {
        val rows = balanceSheetRows(balanceSheet)
        assertEquals(
            listOf(
                listOf(t("Aktiva")),
                listOf("1000 · Kasse", "1", m(100.5)),
                listOf("1200 · Bank", "1", m(5000.0)),
                listOf(sum("Aktiva"), "", m(5100.5)),
                listOf(t("Passiva")),
                listOf(t("Verbindlichkeiten")),
                listOf("1700 · Verbindlichkeiten LuL", "1", m(300.0)),
                listOf(sum("Verbindlichkeiten"), "", m(300.0)),
                listOf(t("Eigenkapital (gebucht)")),
                listOf(t("Keine Buchungen in diesem Abschnitt.")),
                listOf(sum("Eigenkapital (gebucht)"), "", m(0.0)),
                listOf(t("Kumuliertes Ergebnis (Σ Einnahmen − Ausgaben seit Gründung)"), "", m(4800.5)),
                listOf(t("Summe Passiva + Eigenkapital"), "", m(5100.5)),
            ),
            rows.cellTexts(),
        )
        assertEquals(
            listOf(SECTION, DATA, DATA, TOTAL, SECTION, SUBSECTION, DATA, TOTAL, SUBSECTION, NOTE, TOTAL, BAL, TOTAL),
            rows.kinds(),
        )
    }

    @Test
    fun annualKeyFigures_keepPeriodAndAccumulatedResultApart() {
        assertEquals(
            listOf(
                listOf(t("Jahresergebnis (dieses Geschäftsjahr)"), m(1222.5)),
                listOf(t("Kumuliertes Ergebnis (seit Gründung)"), m(-10.0)),
            ),
            annualKeyFigureRows(annualStatement).cellTexts(),
        )
    }

    @Test
    fun fourSphere_isInServerOrderWithTheGrandTotalLast() {
        val rows = fourSphereRows(fourSphereStatement)
        assertEquals(
            listOf(
                listOf("Ideeller Bereich", m(100.0), m(40.5), m(59.5)),
                listOf("Vermögensverwaltung", m(200.0), m(81.0), m(119.0)),
                listOf("Zweckbetrieb", m(300.0), m(121.5), m(178.5)),
                listOf("Wirtschaftlicher Geschäftsbetrieb", m(400.0), m(162.0), m(-12.5)),
                listOf(t("Gesamt"), m(1000.0), m(405.0), m(595.0)),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(DATA, DATA, DATA, DATA, TOTAL), rows.kinds())
        assertTrue(rows[0].cells[0] is ReportCell.Badge)
    }

    @Test
    fun useOfFunds_marksOnlyAPositiveOverdueAmount() {
        val rows = useOfFundsRows(useOfFunds)
        assertEquals(
            listOf(
                listOf("2025", m(900.0), m(400.5), m(100.0), m(399.5), m(0.0)),
                listOf("2026", m(800.0), m(300.0), m(-5.5), m(500.0), m(120.5)),
                listOf(t("Gesamt"), m(1700.0), m(700.5), m(94.5), m(899.5), m(120.5)),
            ),
            rows.cellTexts(),
        )
        assertEquals(false, (rows[0].cells[5] as ReportCell.Money).emphasize)
        assertEquals(true, (rows[1].cells[5] as ReportCell.Money).emphasize)
    }

    @Test
    fun useOfFunds_noYearsSaysSo() {
        val empty = useOfFunds.copy(years = emptyList())
        assertEquals(listOf(NOTE, TOTAL), useOfFundsRows(empty).kinds())
        assertEquals(listOf(t("Keine Buchungen im gewählten Zeitraum.")), useOfFundsRows(empty).cellTexts().first())
    }

    @Test
    fun reserveMovements_freeReserveGetsItsCaveatAsANoteRowRightAfterIt() {
        val rows = reserveMovementRows(reserveMovements)
        assertEquals(
            listOf(
                listOf("Projektrücklage (§62 Abs.1 Nr.1 AO)", m(500.0), m(1500.0)),
                listOf("Freie Rücklage (§62 Abs.1 Nr.3 AO)", m(-20.5), m(80.0)),
                listOf(t("(gesetzliche Obergrenze hier nicht geprüft)")),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(DATA, DATA, NOTE), rows.kinds())
        // Audit MINOR-8: a year without movements shows one dash row, not a table that is only a header
        assertEquals(listOf(listOf("—")), reserveMovementRows(emptyList()).cellTexts())
        assertEquals(listOf(NOTE), reserveMovementRows(emptyList()).kinds())
    }

    @Test
    fun sphereAmounts_keepServerOrderAndZero() {
        assertEquals(
            listOf(listOf("Ideeller Bereich", m(700.0)), listOf("Zweckbetrieb", m(0.0))),
            sphereAmountRows(sphereAmounts).cellTexts(),
        )
        // Audit MINOR-8: no amounts at all -> one dash row (the figures of a non-empty list are untouched)
        assertEquals(listOf(listOf("—")), sphereAmountRows(emptyList()).cellTexts())
        assertEquals(listOf(NOTE), sphereAmountRows(emptyList()).kinds())
    }

    @Test
    fun vatRateLines_haveATotalOnlyInTheVatColumn_andNothingWhenEmpty() {
        assertEquals(
            listOf(
                listOf("19 %", m(119.0), m(100.0), m(19.0), "3"),
                listOf("7 %", m(107.0), m(100.0), m(7.0), "1"),
                listOf(t("Gesamt"), "", "", m(26.0), ""),
            ),
            vatRateLineRows(vatLines, d(26.0)).cellTexts(),
        )
        assertEquals(emptyList(), vatRateLineRows(emptyList(), d(0.0)).cellTexts())
    }

    @Test
    fun generalLedger_leavesTheOtherSideBlank_andKeepsBothBalanceRows() {
        val rows = generalLedgerRows(generalLedger)
        assertEquals(
            listOf(
                listOf("", t("Eröffnungssaldo"), "", "", m(1000.0)),
                listOf("2026-03-01", "Beitrag März", m(200.0), "", m(1200.0)),
                listOf("2026-03-05", "Miete", "", m(49.5), m(1150.5)),
                listOf("", t("Schlusssaldo"), "", "", m(1150.5)),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(BAL, DATA, DATA, BAL), rows.kinds())
    }

    @Test
    fun generalLedger_emptyPeriodHasANoteBetweenTheBalances() {
        assertEquals(listOf(BAL, NOTE, BAL), generalLedgerRows(emptyGeneralLedger).kinds())
        assertEquals(listOf(t("Keine Buchungen im gewählten Zeitraum.")), generalLedgerRows(emptyGeneralLedger).cellTexts()[1])
    }

    @Test
    fun kassenbuch_keepsTheGoBdSequenceAndShowsAMissingVoucherAsDashes() {
        val rows = kassenbuchRows(kassenbuch)
        assertEquals(
            listOf(
                listOf("", "", t("Eröffnungssaldo"), "", "", "", m(50.0)),
                listOf("1", "2026-04-01", "Spende Fest", "B-17", m(30.5), "", m(80.5)),
                listOf("2", "2026-04-02", "Getränke", "--", "", m(20.0), m(60.5)),
                listOf("", "", t("Schlusssaldo"), "", "", "", m(60.5)),
            ),
            rows.cellTexts(),
        )
    }

    @Test
    fun costCenterReport_hasTheUnassignedBucketBeforeTheServersGrandTotal() {
        val rows = costCenterReportRows(costCenterReport)
        assertEquals(
            listOf(
                listOf("FEST · Sommerfest", m(300.0), m(120.5), m(179.5)),
                listOf("WEB · Website", m(0.0), m(80.0), m(-80.0)),
                listOf(t("— Nicht zugeordnet —"), m(10.0), m(5.5), m(4.5)),
                listOf(t("Gesamt"), m(310.0), m(206.0), m(104.0)),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(DATA, DATA, BAL, TOTAL), rows.kinds())
        val empty = costCenterReportRows(costCenterReport.copy(costCenters = emptyList()))
        assertEquals(listOf(NOTE, BAL, TOTAL), empty.kinds())
    }

    @Test
    fun postingConfirm_showsTheCallersSumsAndOptionallyTheVatColumn() {
        val lines =
            listOf(
                PostingLineDisplay("1200 · Bank", PostingSide.DEBIT, d(200.0), "Ideeller Bereich", null, "19 %", d(31.9)),
                PostingLineDisplay("8400 · Erlöse", PostingSide.CREDIT, d(200.0), "Zweckbetrieb", "FEST · Sommerfest", "7 %", null),
            )
        assertEquals(
            listOf(
                listOf("1200 · Bank", m(200.0), "", "Ideeller Bereich", "--", "19 % (${m(31.9)})"),
                listOf("8400 · Erlöse", "", m(200.0), "Zweckbetrieb", "FEST · Sommerfest", "7 %"),
                listOf("Σ", m(200.0), m(200.0), "", "", ""),
            ),
            postingConfirmRows(lines, showVatColumn = true, debitSum = d(200.0), creditSum = d(200.0)).cellTexts(),
        )
        assertEquals(
            listOf(
                listOf("1200 · Bank", m(200.0), "", "Ideeller Bereich", "--"),
                listOf("8400 · Erlöse", "", m(200.0), "Zweckbetrieb", "FEST · Sommerfest"),
                listOf("Σ", m(200.0), m(200.0), "", ""),
            ),
            postingConfirmRows(lines, showVatColumn = false, debitSum = d(200.0), creditSum = d(200.0)).cellTexts(),
        )
    }

    @Test
    fun journalPostings_leaveTheOtherSideBlank_inServerOrder_withoutASumRow() {
        val rows = journalPostingRows(journalPostings)
        assertEquals(
            listOf(
                listOf("1200 · Bank", m(200.0), "", "Ideeller Bereich", "--", "19 %"),
                listOf("8400 · Erlöse", "", m(200.0), "Zweckbetrieb", "FEST · Sommerfest", "Keine USt-Einordnung"),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(DATA, DATA), rows.kinds())
        assertEquals(emptyList(), journalPostingRows(emptyList()).cellTexts())
    }

    @Test
    fun bigAmountsKeepEveryDigit_noSeparatorNoRoundingNoScientificNotation() {
        // The old renderers showed `"$amount €"` (`formatMoney`) -- every digit the server sent, nothing added.
        // 1234567.891 has more decimals than a cent and more digits than a thousand separator would fit.
        assertEquals("1234567.891 €", m(1234567.891))
        assertEquals("-1234567.891 €", m(-1234567.891))
        val rows = useOfFundsRows(bigSingleYearUseOfFunds)
        assertEquals(
            listOf(
                listOf("2026", m(1234567.891), m(300.0), m(-5.5), m(500.0), m(120.5)),
                listOf(t("Gesamt"), m(1234567.891), m(300.0), m(-5.5), m(500.0), m(120.5)),
            ),
            rows.cellTexts(),
        )
        assertEquals(listOf(DATA, TOTAL), rows.kinds())
        val guv = incomeStatementRows(bigAmountStatement)
        assertEquals(listOf(t("Ergebnis"), "", m(1234267.891)), guv.cellTexts().last())
    }

    @Test
    fun donorReports_carryBadgesAndTheirDuties() {
        assertEquals(
            listOf(
                listOf(
                    "Ada Lovelace",
                    "Extern",
                    "Deutsche natürliche Person",
                    m(12000.5),
                    "Unverzügliche MeldepflichtOffenlegungspflicht (Rechenschaftsbericht)",
                ),
                listOf(
                    "Grace Hopper",
                    "Mitglied",
                    "EU-Bürger:in / EU-Rechtsperson",
                    m(3500.0),
                    "Offenlegungspflicht (Rechenschaftsbericht)",
                ),
            ),
            donorDutyRows(donorDuties).cellTexts(),
        )
        assertEquals(
            listOf(listOf("2026-05-01", m(600.5), "Weiterleitungspflicht")),
            anonymousForwardingRows(anonymousForwarding).cellTexts(),
        )
    }

    @Test
    fun staticLabels_stayLiveTranslatable_notResolvedOnce() {
        // Every static label of every report derivation is a `tr(...)` marker string: KVision's language switch
        // re-resolves markers in place, but never an already resolved string -- a `gettext` label would stay in
        // the old language until the user reloaded the report.
        val staticRows: List<ReportRow> =
            incomeStatementRows(incomeStatement) +
                balanceSheetRows(balanceSheet) +
                annualKeyFigureRows(annualStatement) +
                fourSphereRows(fourSphereStatement) +
                useOfFundsRows(useOfFunds) +
                reserveMovementRows(reserveMovements) +
                generalLedgerRows(generalLedger) +
                kassenbuchRows(kassenbuch) +
                costCenterReportRows(costCenterReport) +
                costCenterReportRows(costCenterReport.copy(costCenters = emptyList()))
        val staticTexts =
            setOf(
                "Einnahmen",
                "Ausgaben",
                "Ergebnis",
                "Aktiva",
                "Passiva",
                "Gesamt",
                "Eröffnungssaldo",
                "Schlusssaldo",
                "— Nicht zugeordnet —",
                "Keine Kostenstelle mit Buchungen im gewählten Zeitraum.",
                "(gesetzliche Obergrenze hier nicht geprüft)",
                "Jahresergebnis (dieses Geschäftsjahr)",
            )
        val found = staticRows.flatMap { it.cells }.map { it.text }.filter { it.removePrefix(marker) in staticTexts }
        assertTrue(found.isNotEmpty(), "the probe found none of the static labels")
        assertTrue(found.all { it.startsWith(marker) }, "static labels without the tr marker: ${found.filterNot { it.startsWith(marker) }}")
    }
}
