package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.27 (W3): the migrated report/list screens in a REAL, mounted [Root].
 *
 * Under Karma there is no server, so every RPC fails with a 404 and `guarded {}` turns that into `null` -- the
 * "first load failed" situation. Lehre 7: before W3 these screens left "Wird geladen ..." standing forever; now the
 * failure must be a visible error state with a retry, and it must still be there after the user applied a filter.
 */
class ReportScreensDomTest {
    // NOT `AppScope.promise`: AppScope has no SupervisorJob, one failing test would cancel it for the whole run.
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun errorBoxes(element: HTMLElement): Int = element.querySelectorAll("[role=alert].alert-danger").length

    private suspend fun awaitErrorBox(element: () -> HTMLElement) {
        repeat(200) {
            if (errorBoxes(element()) > 0) return
            delay(50)
        }
        assertTrue(false, "the failed load never produced the error box")
    }

    private fun buttonWithText(
        element: HTMLElement,
        text: String,
    ): HTMLElement? {
        val buttons = element.getElementsByTagName("button")
        return (0 until buttons.length).map { buttons[it] as HTMLElement }.firstOrNull { it.textContent?.trim() == text }
    }

    /**
     * A failed first load shows the alert with "Erneut versuchen"; pressing the filter's [loadLabel] reloads and
     * the failure is STILL an error state (never a stuck "Wird geladen ..." and never a false empty text).
     */
    private suspend fun assertFailedLoadIsAnErrorState(
        id: String,
        loadLabel: String,
        expectedBoxes: Int = 1,
        otherLoadingTexts: Boolean = false,
        render: (Root) -> Unit,
    ) {
        withMountedRoot(id) { root, element ->
            render(root)
            awaitErrorBox(element)
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Erneut versuchen"), "$id: no retry button")
            // Other, not yet migrated sections of the same screen (the LTR balance card) may still say so.
            if (!otherLoadingTexts) assertTrue(!text.contains("Wird geladen"), "$id: still 'Wird geladen ...' after the failure")
            val loadButton = assertNotNull(buttonWithText(element(), loadLabel), "$id: no '$loadLabel' button")
            loadButton.click()
            delay(200)
            awaitErrorBox(element)
            assertEquals(expectedBoxes, errorBoxes(element()), "$id: the error state(s) after the filter reload")
        }
    }

    @Test
    fun financialReports_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-financial", "Laden") { renderFinancialReportsScreen(it) } }

    @Test
    fun financialReports_segmentedControlHasOneActiveSegmentAndItMoves(): Promise<Unit> =
        test {
            withMountedRoot("report-screens-segmented") { root, element ->
                renderFinancialReportsScreen(root)
                val scroll = assertNotNull(element().querySelector(".lapis-segmented-scroll"), "no scroll wrapper")
                val group = assertNotNull(scroll.querySelector(".btn-group[role=group]"), "no btn-group inside the wrapper")

                fun pressed() = (0 until group.children.length).count { group.children[it]?.getAttribute("aria-pressed") == "true" }
                assertEquals(5, group.children.length)
                assertEquals(1, pressed(), "exactly one segment is active")
                assertEquals("true", group.children[0]?.getAttribute("aria-pressed"), "GuV is the initial report")
                (group.children[1] as HTMLElement).click()
                repeat(100) {
                    if (group.children[1]?.getAttribute("aria-pressed") == "true") return@repeat
                    delay(20)
                }
                assertEquals("true", group.children[1]?.getAttribute("aria-pressed"), "the active state did not move to Bilanz")
                assertEquals(1, pressed())
            }
        }

    @Test
    fun nonprofitReports_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-nonprofit", "Laden") { renderNonprofitComplianceReportsScreen(it) } }

    @Test
    fun fourSphereBody_hasFourToggleButtonsWithFourNamesAndRealTargets() {
        withMountedRoot("report-screens-four-sphere") { root, element ->
            renderFourSphereIncomeStatementBody(root, fourSphereStatement)
            val buttons = element().querySelectorAll("button[aria-controls]")
            assertEquals(4, buttons.length)
            val names = (0 until buttons.length).map { (buttons[it] as HTMLElement).getAttribute("aria-label").orEmpty() }
            assertEquals(4, names.toSet().size, "four different accessible names expected: $names")
            names.forEach { assertTrue(!it.contains("###"), "i18n marker in the accessible name: $it") }
            (0 until buttons.length).forEach { index ->
                val target = (buttons[index] as HTMLElement).getAttribute("aria-controls").orEmpty()
                assertNotNull(element().querySelector("[id='$target']"), "aria-controls '$target' points nowhere")
            }
            assertEquals(4, element().querySelectorAll("tr.d-none").length, "the four detail rows start collapsed")
            assertEquals(0, element().querySelectorAll("thead button").length, "a report header has no sort buttons")
        }
    }

    private val cashAccount =
        LedgerAccountDto(
            id = "acc-1000",
            accountNumber = "1000",
            name = "Kasse",
            accountClass = 1,
            type = LedgerAccountType.ASSET,
            active = true,
            isCashRegister = true,
        )

    @Test
    fun hauptbuch_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-hauptbuch", "Laden") { renderHauptbuchView(it, cashAccount) } }

    @Test
    fun kassenbuch_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-kassenbuch", "Laden") { renderKassenbuchView(it, cashAccount) } }

    @Test
    fun costCenters_listAndReportBothShowTheirOwnErrorState(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-cost-centers", "Laden", expectedBoxes = 2) { renderCostCentersScreen(it) } }

    @Test
    fun donors_listAndDutyReportBothShowTheirOwnErrorState(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-donors", "Laden", expectedBoxes = 2) { renderDonorsScreen(it) } }

    @Test
    fun auditLog_failedFirstPageIsAnErrorStateWithRetry(): Promise<Unit> =
        test { assertFailedLoadIsAnErrorState("report-screens-audit-log", "Filtern") { renderAuditLogScreen(it) } }

    @Test
    fun ltrLedger_failedEntriesAndFailedBalanceAreBothAnErrorStateWithRetry(): Promise<Unit> =
        test {
            // Audit F: the balance card is a `dataSection` now -- before it said "Wird geladen ..." and then stayed EMPTY.
            assertFailedLoadIsAnErrorState("report-screens-ltr", "Aktualisieren", expectedBoxes = 2) { renderLtrLedgerScreen(it) }
        }

    // ---- Audit F: the detail panels and the ADMIN gate that were still "Wird geladen ..." forever ----

    @Test
    fun auditLogDetail_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test {
            assertFailedLoadIsAnErrorState("report-screens-audit-detail", "Erneut versuchen") { renderAuditLogDetail(it, "entry-1") }
        }

    @Test
    fun journalEntryDetail_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test {
            assertFailedLoadIsAnErrorState("report-screens-journal-detail", "Erneut versuchen") {
                renderJournalEntryDetail(it, "entry-1", canManage = true, onChanged = {}, onDuplicate = {})
            }
        }

    @Test
    fun donorDetail_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test {
            assertFailedLoadIsAnErrorState("report-screens-donor-detail", "Erneut versuchen") { renderDonorDetail(it, "donor-1") }
        }

    @Test
    fun vatAdminGate_failedLoadIsAnErrorStateWithRetry(): Promise<Unit> =
        test {
            assertFailedLoadIsAnErrorState("report-screens-vat-gate", "Erneut versuchen") { renderVatAdminGateSection(it) }
        }

    @Test
    fun useOfFunds_aYearWithoutMovementsShowsADashRowInsteadOfAHeaderOnlyTable() {
        withMountedRoot("report-screens-empty-detail") { root, element ->
            renderUseOfFundsBody(root, useOfFunds)
            val year2026 = assertNotNull(element().querySelector("tr[id='lapis-year-2026']"), "no detail row for 2026")
            val tables = year2026.querySelectorAll("table")
            assertEquals(3, tables.length, "reserve movements plus two sphere tables")
            (0 until tables.length).forEach { index ->
                val table = tables[index] as HTMLElement
                val body = table.querySelectorAll("tbody tr")
                assertTrue(body.length >= 1, "detail table $index is a header without a single row")
            }
            // the year WITH data keeps its figures and gets no dash
            val year2025 = assertNotNull(element().querySelector("tr[id='lapis-year-2025']"))
            assertTrue(!year2025.textContent.orEmpty().contains("—"), "a table with data got a dash row")
            assertTrue(year2026.textContent.orEmpty().contains("—"), "the empty year shows no dash")
        }
    }

    @Test
    fun documents_failedFolderListIsAnErrorStateNotAFalseEmptyStatement(): Promise<Unit> =
        test {
            assertFailedLoadIsAnErrorState("report-screens-documents", "Erneut versuchen") { renderDocumentsScreen(it) }
            withMountedRoot("report-screens-documents-text") { root, element ->
                renderDocumentsScreen(root)
                awaitErrorBox(element)
                assertTrue(
                    !element().textContent.orEmpty().contains("Noch keine Ordner vorhanden"),
                    "a failed load must not claim there are no folders",
                )
            }
        }

    @Test
    fun approvalQueues_failedFirstPageIsAnErrorStateWithRetry(): Promise<Unit> =
        test {
            assertFailedLoadIsAnErrorState("report-screens-relief", "Filtern") { renderContributionReliefQueueScreen(it) }
            assertFailedLoadIsAnErrorState("report-screens-travel", "Filtern", otherLoadingTexts = true) {
                renderTravelExpenseApprovalsScreen(it)
            }
            assertFailedLoadIsAnErrorState("report-screens-volunteer", "Filtern") { renderVolunteerAllowanceApprovalsScreen(it) }
        }

    @Test
    fun approvalQueues_keepTheirCardsInTheCardListGrammar() {
        withMountedRoot("report-screens-queue-grammar") { root, element ->
            renderVolunteerAllowanceApprovalsScreen(root)
            assertNotNull(element().querySelector(".lapis-card-list"), "the queue is a card list")
            assertEquals(0, element().querySelectorAll("table").length, "a queue of decisions is never a table (guideline P3)")
        }
    }
}
