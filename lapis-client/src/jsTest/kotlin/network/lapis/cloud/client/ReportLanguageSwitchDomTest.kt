package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.27 (W3), review finding: a report already on screen must follow a language switch COMPLETELY --
 * caption, column headers AND the row labels (section rows, sum rows, notes, balances). KVision's language switch
 * only sets `I18n.language` and restarts the root in place (it does not re-render the screens): `tr(...)` marker
 * strings are re-resolved by that restart, an already resolved `gettext` string is not. The row labels of the
 * model must therefore stay markers ([ReportGoldenTest.staticLabels_stayLiveTranslatable_notResolvedOnce]); this
 * test proves the end-to-end effect on a real table.
 */
class ReportLanguageSwitchDomTest {
    private val english =
        mapOf(
            "Einnahmen" to "Income",
            "Ausgaben" to "Expenses",
            "Ergebnis" to "Result",
            "Keine Buchungen in diesem Abschnitt." to "No postings in this section.",
            "Gesamt" to "Total",
            "Eröffnungssaldo" to "Opening balance",
            "Schlusssaldo" to "Closing balance",
            "Summe %1" to "Total %1",
        )

    private fun texts(
        host: HTMLElement,
        selector: String,
    ): List<String> {
        val nodes = host.querySelectorAll(selector)
        return (0 until nodes.length).map {
            nodes
                .item(it)!!
                .textContent
                .orEmpty()
                .trim()
        }
    }

    @Test
    fun incomeStatement_rowLabelsSwitchLanguageInPlace() {
        withMountedRoot("report-language-guv") { root, element ->
            renderIncomeStatementBody(root, lossStatement)
            assertTrue(texts(element(), "tbody tr.lapis-section-row > th").containsAll(listOf("Einnahmen", "Ausgaben")))
            assertTrue("Ergebnis" in texts(element(), "tbody tr.lapis-total-row > th:first-child"))

            withTranslations(english) {
                root.restart()
                assertEquals(listOf("Income", "Expenses"), texts(element(), "tbody tr.lapis-section-row > th"))
                val totals = texts(element(), "tbody tr.lapis-total-row > th:first-child")
                assertTrue("Result" in totals, "the result row stayed German: $totals")
            }
        }
    }

    @Test
    fun incomeStatement_sumLabelsReadCleanlyAndSwitchLanguageInPlace() {
        withMountedRoot("report-language-sum") { root, element ->
            renderIncomeStatementBody(root, incomeStatement)
            val before = texts(element(), "tbody tr.lapis-total-row > th:first-child")
            // audit fix B: no `###KvI18nS###` in front of the title, in the source language ...
            assertEquals(listOf("Summe Einnahmen", "Summe Ausgaben", "Ergebnis"), before)
            assertTrue(before.none { it.contains("###") }, "the i18n marker leaked into a sum label: $before")

            // ... and the WHOLE label (template and title) follows a language switch
            withTranslations(english) {
                root.restart()
                val switched = texts(element(), "tbody tr.lapis-total-row > th:first-child")
                assertEquals(listOf("Total Income", "Total Expenses", "Result"), switched)
            }
        }
    }

    @Test
    fun balanceSheet_everySumLabelIsCleanInBothLanguages() {
        withMountedRoot("report-language-bilanz") { root, element ->
            renderBalanceSheetBody(root, balanceSheet)
            val sums = texts(element(), "tbody tr.lapis-total-row > th:first-child")
            assertEquals(
                listOf("Summe Aktiva", "Summe Verbindlichkeiten", "Summe Eigenkapital (gebucht)", "Summe Passiva + Eigenkapital"),
                sums,
            )
            withTranslations(english + mapOf("Aktiva" to "Assets", "Verbindlichkeiten" to "Liabilities")) {
                root.restart()
                val switched = texts(element(), "tbody tr.lapis-total-row > th:first-child")
                assertTrue(switched.first() == "Total Assets", "the first sum label after the switch: $switched")
                assertTrue(switched.none { it.contains("###") }, "a marker leaked after the switch: $switched")
            }
        }
    }

    @Test
    fun generalLedger_balanceLabelsSwitchLanguageInPlace() {
        withMountedRoot("report-language-ledger") { root, element ->
            renderHauptbuchLines(root, generalLedger)
            withTranslations(english) {
                root.restart()
                val balances = texts(element(), "tbody tr.lapis-balance-row > th")
                assertTrue("Opening balance" in balances, "opening balance stayed German: $balances")
                assertTrue("Closing balance" in balances, "closing balance stayed German: $balances")
            }
        }
    }

    @Test
    fun emptySection_noteSwitchesLanguageInPlace() {
        withMountedRoot("report-language-note") { root, element ->
            renderStatementSectionTable(root, io.kvision.i18n.tr("Ausgaben"), emptyList(), d(0.0))
            withTranslations(english) {
                root.restart()
                assertEquals(listOf("No postings in this section."), texts(element(), "tbody tr.lapis-note-row > td"))
            }
        }
    }
}
