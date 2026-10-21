package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaDebtorAccountOptionDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSepaBatchDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker
import network.lapis.cloud.shared.domain.TravelExpenseSepaContextDto
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.123 -- the "Zur Auszahlung bereit" section: what a person sees and can do before a payment file exists. Driven against a
 * stubbed `window.fetch`, same idiom as [TravelExpenseApprovalsScreenFormDomTest].
 */
class TravelExpenseSepaPanelDomTest {
    private val boardSession =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Board Member",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun report(
        id: String,
        name: String,
        payoutCents: Long,
        blocker: TravelExpenseSepaBlocker? = null,
        exported: Boolean = false,
        holder: String? = name,
    ) = TravelExpenseReportDto(
        id = id,
        subjectMemberId = "member-$id",
        subjectDisplayName = name,
        status = TravelExpenseReportStatus.EXECUTED,
        purpose = "Delegiertenversammlung",
        travelFrom = LocalDate(2026, 9, 10),
        travelTo = LocalDate(2026, 9, 12),
        totalAmount = (payoutCents / 100.0).toDecimal(),
        createdAt = LocalDateTime(2026, 9, 1, 9, 0),
        submittedAt = LocalDateTime(2026, 9, 1, 9, 5),
        requestedBy = "member-$id",
        requestedByDisplayName = name,
        payoutChoice = TravelExpensePayoutChoice.PAYOUT,
        payoutCents = payoutCents,
        refundIbanLast4 = "2051",
        refundAccountHolder = holder,
        sepaBlocker = blocker,
        sepaReference = "RK-" + id.uppercase().padEnd(8, '0').take(8),
        sepaExportedAt = if (exported) LocalDateTime(2026, 10, 1, 8, 0) else null,
        sepaExportBatchId = if (exported) "0123456789abcdef" else null,
        sepaExportedByDisplayName = if (exported) "Kassenwart" else null,
    )

    private val candidates =
        listOf(
            report("aaaa1111", "Erika Mustermann", 8_000),
            report("bbbb2222", "Max Beispiel", 4_505, blocker = TravelExpenseSepaBlocker.NO_REFUND_IBAN),
            report(
                "cccc3333",
                "Zoë Fremd",
                2_000,
                blocker = TravelExpenseSepaBlocker.ALREADY_EXPORTED,
                exported = true,
                holder = "Anderer Name",
            ),
        )

    private fun context(accounts: Int) =
        TravelExpenseSepaContextDto(
            bankAccounts =
                (1..accounts).map {
                    SepaDebtorAccountOptionDto(
                        id = "bank-$it",
                        label = "Konto $it",
                        bankName = "Testbank",
                        ibanLast4 = "300$it",
                        isDefault =
                            it == 1,
                    )
                },
            defaultExecutionDate = LocalDate(2026, 10, 14),
        )

    private val batches =
        listOf(
            TravelExpenseSepaBatchDto(
                id = "batch-1",
                debtorAccountLabel = "Konto 1",
                debtorIbanLast4 = "3001",
                requestedExecutionDate = LocalDate(2026, 10, 2),
                transactionCount = 3,
                controlSumCents = 25_000,
                createdAt = LocalDateTime(2026, 10, 1, 8, 0),
                createdByDisplayName = "Kassenwart",
                isReexport = true,
            ),
        )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private suspend fun withPanel(
        accounts: Int,
        block: suspend (() -> org.w3c.dom.HTMLElement) -> Unit,
    ) {
        AppState.setSession(boardSession)
        val candidatesRoute = routeOf { rpcService<ITravelExpenseService>().listSepaExportCandidates() }
        val contextRoute = routeOf { rpcService<ITravelExpenseService>().getSepaExportContext() }
        val batchesRoute = routeOf { rpcService<ITravelExpenseService>().listSepaBatches() }
        withFetchStub(
            respond =
                answering(
                    candidatesRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), candidates),
                    contextRoute to jsonOf(TravelExpenseSepaContextDto.serializer(), context(accounts)),
                    batchesRoute to jsonOf(ListSerializer(TravelExpenseSepaBatchDto.serializer()), batches),
                ),
        ) {
            mountedForm("travel-expense-sepa-panel") { root, element ->
                renderTravelExpenseSepaSection(root)
                awaitUntil("cards rendered", timeoutMs = 800) { element().allOf(".lapis-data-card").size >= 4 }
                delay(50)
                block(element)
            }
        }
    }

    @Test
    fun aBlockedReport_hasADisabledCheckbox_andTheReasonAsVisibleTextTiedToIt(): Promise<Unit> =
        formTest {
            withPanel(accounts = 1) { element ->
                val boxes = element().allOf("input[type=checkbox]").map { it as HTMLInputElement }
                assertEquals(3, boxes.size)
                val blocked = boxes.single { it.getAttribute("aria-label") == "Abrechnung RK-BBBB2222 auswählen" }
                assertTrue(blocked.disabled, "no checkbox for a report without a refund IBAN")
                val reason = element().ownerDocument!!.getElementById(blocked.getAttribute("aria-describedby").orEmpty())
                assertEquals("Keine Erstattungs-IBAN", reason?.textContent?.trim(), "the reason is text, tied by aria-describedby")
                val free = boxes.single { it.getAttribute("aria-label") == "Abrechnung RK-AAAA1111 auswählen" }
                assertFalse(free.disabled)
            }
        }

    @Test
    fun selecting_updatesTheSummaryAndTheButton_andTheIbanIsOnlyEverMasked(): Promise<Unit> =
        formTest {
            withPanel(accounts = 1) { element ->
                val generate = element().buttonNamed("SEPA-Datei erzeugen")
                assertTrue((generate as org.w3c.dom.HTMLButtonElement).disabled, "nothing selected yet")
                assertTrue(element().textContent.orEmpty().contains("0 ausgewählt"))
                val free =
                    element().allOf("input[type=checkbox]").single {
                        it.getAttribute("aria-label") ==
                            "Abrechnung RK-AAAA1111 auswählen"
                    }
                free.click()
                delay(60)
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("1 ausgewählt · Summe"), "the running sum: $text")
                assertTrue(text.contains("80,00"), "8000 cents are shown as 80,00 EUR: $text")
                assertFalse((generate as org.w3c.dom.HTMLButtonElement).disabled)
                assertTrue(text.contains("IBAN •••• 2051"), "the masked IBAN")
                assertTrue(element().allOf("[aria-label='IBAN endet auf 2051']").isNotEmpty(), "the masked IBAN has an accessible name")
                assertFalse(text.contains("DE02"), "never a full IBAN in the page")
            }
        }

    @Test
    fun anExportedReport_saysExportedWithItsDate_offersAReexport_andNeverShowsACheckMark(): Promise<Unit> =
        formTest {
            withPanel(accounts = 1) { element ->
                val card = element().allOf(".lapis-data-card").first { it.textContent.orEmpty().contains("Zoë Fremd") }
                val text = card.textContent.orEmpty()
                assertTrue(text.contains("Exportiert am"), text)
                assertTrue(text.contains("Kassenwart"), text)
                assertTrue(card.allOf("button").any { it.textContent?.trim() == "Erneut exportieren …" })
                assertTrue(text.contains("Kontoinhaber weicht vom Mitglied ab"), "a holder that is not the member is flagged")
                assertTrue(card.allOf(".fa-circle-check, .fa-check").isEmpty(), "exported is not paid: no check mark")
            }
        }

    @Test
    fun theListOfEarlierFiles_isMetadataOnly_withNoDownload(): Promise<Unit> =
        formTest {
            withPanel(accounts = 1) { element ->
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Bisherige SEPA-Dateien"))
                assertTrue(text.contains("Erneuter Export"))
                assertTrue(text.contains("Erzeugt von Kassenwart"))
                assertTrue(element().allOf("a[download], a[href*='sepa']").isEmpty(), "no link to an old file")
                assertFalse(text.contains("Herunterladen"))
            }
        }

    @Test
    fun theConfirmDialog_namesTheSum_andOffersAnAccountChoiceOnlyWithSeveralAccounts(): Promise<Unit> =
        formTest {
            useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
            withPanel(accounts = 2) { element ->
                element()
                    .allOf(
                        "input[type=checkbox]",
                    ).single { it.getAttribute("aria-label") == "Abrechnung RK-AAAA1111 auswählen" }
                    .click()
                delay(60)
                val modalsBefore = allModals()
                element().buttonNamed("SEPA-Datei erzeugen").click()
                // A modal of an earlier test may still be in the document: wait for a NEW one.
                awaitUntil("the dialog is open", timeoutMs = 1000) { allModals().any { it !in modalsBefore } }
                val modal = allModals().first { it !in modalsBefore }
                assertTrue(modal.textContent.orEmpty().contains("80,00"), "the sum is named")
                assertTrue(modal.textContent.orEmpty().contains("Belastungskonto"), "two accounts: a choice")
                assertTrue(modal.textContent.orEmpty().contains("Ausführungsdatum"))
                assertTrue(modal.textContent.orEmpty().contains("Format: TT.MM.JJJJ"), "the date field names its pattern")
            }
        }

    @Test
    fun theConfirmDialog_hasNoAccountChoice_withASingleAccount(): Promise<Unit> =
        formTest {
            withPanel(accounts = 1) { element ->
                element()
                    .allOf(
                        "input[type=checkbox]",
                    ).single { it.getAttribute("aria-label") == "Abrechnung RK-AAAA1111 auswählen" }
                    .click()
                delay(60)
                val modalsBefore = allModals()
                element().buttonNamed("SEPA-Datei erzeugen").click()
                // A modal of an earlier test may still be in the document: wait for a NEW one.
                awaitUntil("the dialog is open", timeoutMs = 1000) { allModals().any { it !in modalsBefore } }
                val modal = allModals().first { it !in modalsBefore }
                assertFalse(modal.textContent.orEmpty().contains("Belastungskonto"), "modal text: " + modal.textContent.orEmpty())
                assertTrue(modal.textContent.orEmpty().contains("1 Überweisungen von Konto 1"), modal.textContent.orEmpty())
            }
        }

    private fun allModals(): List<org.w3c.dom.HTMLElement> {
        val all = kotlinx.browser.document.querySelectorAll(".modal")
        return (0 until all.length).map { all.item(it) as org.w3c.dom.HTMLElement }
    }
}
