package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.core.onEvent
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AccountingExportItemDto
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementLineDto
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemDto
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaReturnDto
import network.lapis.cloud.shared.domain.SepaReturnInput
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.rpc.IAccountingExportService
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.ISepaService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.4.30 audit round: the behaviours the independent audit found wrong in the finance forms -- the real error text of the return
 * fee, the focus of the "other reason" free text, a return form that pre-selects the NEXT member after a success, one money format in
 * the booking workflow, the cancelled resolve action, the pairing of stars and legends, the filter that silently ignored an unreadable
 * date, and the marker leak of `tr()` results inside `gettext` arguments. Every test drives a REAL mounted form against a stubbed
 * `window.fetch`; expected values come from the old behaviour and the server limits, not from the new implementation.
 */
class FormGrammarPart3AuditDomTest {
    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun todayIso(): String = todayLocalDate().toString()

    private fun thisYear(): Int = todayLocalDate().year

    // ── fixtures of the return form ──────────────────────────────────────────────────────────────────────────────

    private fun batch(id: String) =
        SepaDebitBatchDto(
            id = id,
            messageId = "m-$id",
            paymentInfoId = "p-$id",
            requestedCollectionDate = LocalDate(2026, 1, 15),
            sequenceType = SepaSequenceType.RCUR,
            status = SepaDebitBatchStatus.SUBMITTED,
            itemCount = 2,
            totalAmount = 20.0.toDecimal(),
            createdByDisplayName = "Kassenwart",
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            notifiedAt = null,
            requiredNoticeDays = null,
            fileGenerationAllowedFrom = null,
            generatedAt = null,
            generatedDocumentId = null,
            prenotificationDocumentId = null,
            submittedAt = null,
            submittedNote = null,
            settledAt = null,
            settlementEligibleFrom = null,
            cancelledAt = null,
            cancellationReason = null,
        )

    private fun item(
        id: String,
        name: String,
    ) = SepaDebitItemDto(
        id = id,
        batchId = "b1",
        contributionId = "c-$id",
        memberDisplayName = name,
        mandateId = "md-$id",
        mandateReference = "REF-$id",
        debtorIbanLast4 = "1234",
        endToEndId = "e2e-$id",
        amount = 10.0.toDecimal(),
        remittanceInformation = "Beitrag",
        status = SepaDebitItemStatus.PENDING,
        settleableAt = null,
        journalEntryId = null,
        returnReason = null,
    )

    private val detailJson =
        jsonOf(
            SepaDebitBatchDetailDto.serializer(),
            SepaDebitBatchDetailDto(batch = batch("b1"), items = listOf(item("i1", "Anna Muster"), item("i2", "Bert Beispiel"))),
        )

    private val returnJson =
        jsonOf(
            SepaReturnDto.serializer(),
            SepaReturnDto(
                id = "r1",
                debitItemId = "i1",
                batchId = "b1",
                contributionId = "c-i1",
                memberDisplayName = "Anna Muster",
                returnedAt = LocalDate(2026, 2, 11),
                reasonCode = SepaReturnReason.AC01,
                reasonText = null,
                returnFee = null,
                recordedByDisplayName = "Kassenwart",
                recordedAt = LocalDateTime(2026, 2, 12, 10, 0),
                mandateRevoked = false,
            ),
        )

    private suspend fun <T> withReturnForm(
        block: suspend (record: String, calls: List<RecordedRequest>, element: () -> HTMLElement) -> T,
    ): T {
        val list = routeOf { rpcService<ISepaService>().listBatches(status = SepaDebitBatchStatus.SUBMITTED, limit = 100) }
        val get = routeOf { rpcService<ISepaService>().getBatch("x") }
        val record = routeOf { rpcService<ISepaService>().recordReturn(SepaReturnInput("i", LocalDate(2026, 1, 1), SepaReturnReason.AC01)) }
        val batches = jsonOf(ListSerializer(SepaDebitBatchDto.serializer()), listOf(batch("b1")))
        return withFetchStub(respond = { r ->
            when {
                !r.isRpc -> StubResponse()
                r.rpcRoute == list -> if (r.rpcParam(0) == "SUBMITTED") r.answerWith(batches) else r.answerWith("[]")
                r.rpcRoute == get -> r.answerWith(detailJson)
                r.rpcRoute == record -> r.answerWith(returnJson)
                else -> r.answerWith("null")
            }
        }) { calls ->
            mountedForm("p3a-return") { root, element ->
                renderRecordReturnForm(root) {}
                awaitUntil("the first item is preselected", timeoutMs = 1500) {
                    (element().controlOf("Position") as HTMLSelectElement).value == "i1"
                }
                block(record, calls, element)
            }
        }
    }

    // ── M1: the real reason of a rejected return fee ─────────────────────────────────────────────────────────────

    @Test
    fun returnFee_showsTheRealReasonForEachKindOfBadInput_notAlwaysMustBePositive_andSendsNothing(): Promise<Unit> =
        formTest {
            withReturnForm { record, calls, element ->
                val submit = "Rücklastschrift erfassen"
                element().typeInto("Rücklastschriftgebühr in EUR", "3,005")
                element().buttonNamed(submit).click()
                delay(80)
                assertTrue(
                    element().shownErrors().any { it.contains("Höchstens 2 Nachkommastellen") },
                    "three decimals: the field names the scale rule, not 'must be positive': ${element().shownErrors()}",
                )
                element().typeInto("Rücklastschriftgebühr in EUR", "0")
                assertTrue(
                    element().shownErrors().any { it.contains("größer als 0") },
                    "zero: the field says it must be larger than 0: ${element().shownErrors()}",
                )
                element().typeInto("Rücklastschriftgebühr in EUR", "1.234,56")
                assertTrue(
                    element().shownErrors().any { it.contains("Tausendertrennzeichen") },
                    "a thousands separator: the field names it: ${element().shownErrors()}",
                )
                element().typeInto("Rücklastschriftgebühr in EUR", "2000000000")
                assertTrue(
                    element().shownErrors().any { it.contains("zu groß") },
                    "above the DECIMAL(12,2) column bound: ${element().shownErrors()}",
                )
                assertTrue(calls.toRoute(record).isEmpty(), "none of the bad fees was sent")
            }
        }

    // ── M2: the free text is focused, and the collective message follows it ──────────────────────────────────────

    @Test
    fun otherReasonWithoutFreeText_focusesTheFreeTextInputItself_andTheMessageClearsWhenItChanges(): Promise<Unit> =
        formTest {
            withReturnForm { record, calls, element ->
                element().chooseIn("Grund", SepaReturnReason.OTHER.name)
                element().buttonNamed("Rücklastschrift erfassen").click()
                delay(120)
                val freeText = element().controlOf("Freitext")
                assertTrue(document.activeElement === freeText, "the focus is on the free-text <input>, not on its wrapper <div>")
                assertTrue(
                    element()
                        .first(".lapis-form-alert--shown")
                        .textContent
                        .orEmpty()
                        .contains("Sonstiger Grund"),
                    "the collective message names the condition",
                )
                assertTrue(calls.toRoute(record).isEmpty(), "nothing was sent")

                (freeText as HTMLInputElement).value = "Konto aufgelöst"
                freeText.dispatchEvent(Event("input")) // KVision reads the value on `input`
                freeText.dispatchEvent(Event("change"))
                awaitUntil("the collective message clears when the free text changes", timeoutMs = 1500) {
                    element().querySelector(".lapis-form-alert--shown") == null
                }
            }
        }

    // ── M9: after a success nothing may pre-select the NEXT member ───────────────────────────────────────────────

    @Test
    fun afterARecordedReturn_thePositionIsEmpty_theDefaultsAreBack_andASecondClickWithoutAChoiceSendsNothing(): Promise<Unit> =
        formTest {
            withReturnForm { record, calls, element ->
                element().typeInto("Rücklastschrift-Datum", "2026-02-11")
                element().chooseIn("Grund", SepaReturnReason.MD01.name)
                element().typeInto("Freitext", "Widerspruch")
                element().typeInto("Rücklastschriftgebühr in EUR", "3,50")
                element().buttonNamed("Rücklastschrift erfassen").click()
                awaitUntil("recordReturn", timeoutMs = 1500) { calls.toRoute(record).size == 1 }
                // Wait for the follow-up reload of the item options (getBatch) to land.
                delay(300)

                assertEquals(
                    "",
                    (element().controlOf("Position") as HTMLSelectElement).value,
                    "no item is pre-selected: the placeholder shows",
                )
                assertEquals(
                    todayIso(),
                    (element().controlOf("Rücklastschrift-Datum") as HTMLInputElement).value,
                    "the date is back to today",
                )
                assertEquals(
                    SepaReturnReason.entries.first().name,
                    (element().controlOf("Grund") as HTMLSelectElement).value,
                    "the reason is back to the default reason",
                )
                assertEquals("", (element().controlOf("Freitext") as HTMLInputElement).value)
                assertEquals("", (element().controlOf("Rücklastschriftgebühr in EUR") as HTMLInputElement).value)

                element().buttonNamed("Rücklastschrift erfassen").click()
                delay(120)
                assertEquals(1, calls.toRoute(record).size, "the second click without a choice sends nothing")
                assertTrue(
                    element().shownErrors().contains("Bitte eine Position auswählen."),
                    "the empty position says so at the field: ${element().shownErrors()}",
                )
            }
        }

    // ── M3: ONE money format in the booking workflow ─────────────────────────────────────────────────────────────

    private val accounts =
        listOf(
            LedgerAccountDto(
                id = "a1",
                accountNumber = "1200",
                name = "Bank",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                active = true,
            ),
            LedgerAccountDto(
                id = "a2",
                accountNumber = "4200",
                name = "Spenden",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                active = true,
            ),
        )

    @Test
    fun balanceStripAndConfirmationDialog_writeTheSameAmountTheSameWay(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3a-format") { root, element ->
                    renderNewEntryForm(
                        root,
                        accounts,
                        emptyList(),
                        listOf(MemberSummaryDto("m1", "Amara")),
                        emptyList(),
                        false,
                        false,
                        false,
                    ) {}
                    element().typeInto("Beschreibung", "Format")
                    element().chooseIn("Konto", "a1", 0)
                    element().chooseIn("Soll/Haben", "DEBIT", 0)
                    element().typeInto("Betrag", "100,50", 0)
                    element().chooseIn("Sphäre", "IDEELLER_BEREICH", 0)
                    element().chooseIn("Konto", "a2", 1)
                    element().chooseIn("Soll/Haben", "CREDIT", 1)
                    element().typeInto("Betrag", "100,50", 1)
                    element().chooseIn("Sphäre", "IDEELLER_BEREICH", 1)
                    val strip = element().first(".lapis-balance-strip").textContent.orEmpty()
                    element().buttonNamed("Direkt buchen").click()
                    delay(80)
                    val dialog = lastOpenModal().textContent.orEmpty()
                    val amountInStrip = Regex("Soll (\\S+ €)").find(strip)?.groupValues?.get(1)
                    assertNotNull(amountInStrip, "the strip shows the debit sum: $strip")
                    assertEquals(
                        "100.5 €",
                        amountInStrip,
                        "the strip uses the app's money format ('100.5 €'), not a second one ('100,50 €')",
                    )
                    assertTrue(dialog.contains(amountInStrip), "the confirmation dialog writes the same amount the same way: $dialog")
                }
            }
        }

    // ── M5: a failed resolve call must not lock both buttons for ever (the `finally` also covers a CancellationException, which a
    //    DOM test cannot provoke: Kilua's fetch await is not cancellable, the coroutine only ends when the response arrives) ────────────────────────────────────────

    private fun unknownItem() =
        AccountingExportItemDto(
            id = "it1",
            runId = "run1",
            journalEntryId = "je1",
            entryDate = LocalDate(2026, 1, 1),
            voucherNumber = "V-1",
            grossAmount = 10.0.toDecimal(),
            status = AccountingExportItemStatus.UNKNOWN,
            externalVoucherId = null,
            errorCode = null,
            errorMessage = null,
        )

    @Test
    fun resolveUnknown_aDroppedConnection_releasesBothButtons(): Promise<Unit> =
        formTest {
            val resolve =
                routeOf {
                    rpcService<IAccountingExportService>().resolveUnknownItem(
                        "i",
                        AccountingExportUnknownItemResolution.CONFIRMED_NOT_SENT,
                        null,
                    )
                }
            withFetchStub(respond = { r -> if (r.isRpc) StubResponse(networkError = true) else StubResponse() }) { calls ->
                mountedForm("p3a-resolve2") { root, element ->
                    root.renderResolveUnknownActions(AccountingExportProvider.LEXOFFICE, unknownItem()) {}
                    val notFound = element().buttonNamed("Nicht gefunden")
                    notFound.click()
                    awaitUntil("the call went out", timeoutMs = 1500) { calls.toRoute(resolve).size == 1 }
                    awaitUntil("released after the failure", timeoutMs = 2000) { !notFound.hasAttribute("disabled") }
                }
            }
        }

    @Test
    fun resolveUnknown_theDangerousActionStandsApartInTheDangerZone_notNextToThePrimary(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3a-resolve3") { root, element ->
                    root.renderResolveUnknownActions(AccountingExportProvider.LEXOFFICE, unknownItem()) {}
                    val notFound = element().buttonNamed("Nicht gefunden")
                    assertNotNull(notFound.closest(".lapis-form-danger-zone"), "'Nicht gefunden' is separated in the danger zone")
                    val primary =
                        element().allOf("button").first {
                            it.textContent
                                .orEmpty()
                                .trim()
                                .startsWith("In ")
                        }
                    assertTrue(primary.closest(".lapis-form-danger-zone") == null)
                }
            }
        }

    // ── M7: stars and legends must not invert the meaning ───────────────────────────────────────────────────────

    private fun line() =
        BankStatementLineDto(
            id = "l1",
            importId = "imp1",
            bookingDate = LocalDate(2026, 1, 5),
            valueDate = null,
            amount = 25.0.toDecimal(),
            currency = "EUR",
            counterpartyName = "Muster",
            counterpartyIbanMasked = "DE••1234",
            purpose = "Spende",
            endToEndReference = null,
            bookingText = null,
            status = BankStatementLineStatus.UNMATCHED,
            matchExplanation = null,
            matchedContributionId = null,
            paymentTransactionId = null,
            resolvedByDisplayName = null,
            resolvedAt = null,
            resolutionNote = null,
        )

    @Test
    fun donationBlock_hasNoStarOnThePreselectedTypeAndNoLegend_theConditionalFieldsSayTheyAreRequired(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3a-stars") { root, element ->
                    renderAssignmentWorkbench(
                        host = root,
                        line = line(),
                        members = listOf(MemberSummaryDto("m1", "Amara Okafor")),
                        externalDonors =
                            listOf(
                                ExternalDonorDto(
                                    "x1",
                                    "Firma X",
                                    DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION,
                                    null,
                                    null,
                                    null,
                                    null,
                                    true,
                                ),
                            ),
                        onChanged = {},
                    )
                    assertTrue(
                        element().querySelector(".lapis-required-mark") == null,
                        "the only 'required' field was the preselected type: a star exactly there and none on the fields that block was the inversion",
                    )
                    assertFalse(
                        element().textContent.orEmpty().contains("* Pflichtfeld"),
                        "a visible legend next to unstarred blocking fields reads as 'optional'",
                    )

                    fun requiredHints() =
                        element().allOf(".form-text").count {
                            it.textContent.orEmpty().trim() ==
                                "Pflichtangabe für diesen Spendertyp."
                        }
                    assertEquals(2, requiredHints(), "type 'member': the member and the donor category say they are required for it")
                    element().chooseIn("Spendertyp", "EXTERNAL")
                    awaitUntil("the external donor panel shows", timeoutMs = 1500) { requiredHints() == 1 }
                }
            }
        }

    @Test
    fun entryForm_theDonorSelectsSayTheyAreRequiredForTheirDonorType(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3a-stars2") { root, element ->
                    renderNewEntryForm(
                        root,
                        accounts,
                        emptyList(),
                        listOf(MemberSummaryDto("m1", "Amara")),
                        emptyList(),
                        false,
                        false,
                        false,
                    ) {}

                    fun requiredHints() =
                        element().allOf(".form-text").count {
                            it.textContent.orEmpty().trim() ==
                                "Pflichtangabe für diesen Spendertyp."
                        }
                    element().chooseIn("Spendertyp", "MEMBER")
                    awaitUntil("the member panel shows", timeoutMs = 1500) { requiredHints() == 2 }
                    element().chooseIn("Spendertyp", "EXTERNAL")
                    awaitUntil("the external panel shows", timeoutMs = 1500) { requiredHints() == 1 }
                }
            }
        }

    // ── stale state and filters ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun upload_theOldResultIsClearedBeforeEverySubmit_alsoBeforeOneThatFailsTheFieldCheck(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3a-upload") { root, element ->
                    var cleared = 0
                    renderUploadPanel(root, onUploadStarted = { cleared++ }) {}
                    element().buttonNamed("Hochladen").click()
                    delay(80)
                    assertEquals(1, cleared, "a click that fails the file check still clears the previous success banner")
                    element().buttonNamed("Hochladen").click()
                    delay(80)
                    assertEquals(2, cleared)
                }
            }
        }

    @Test
    fun returnsFilter_anUnreadableDateIsAFieldError_theFullListIsNotShownAsAFilteredOne(): Promise<Unit> =
        formTest {
            val listReturns = routeOf { rpcService<ISepaService>().listReturns(null, null) }
            withFetchStub(respond = { r -> if (r.isRpc && r.rpcRoute == listReturns) r.answerWith("[]") else StubResponse() }) { calls ->
                mountedForm("p3a-returns-filter") { root, element ->
                    renderSepaReturnsSection(root, canRecordReturn = false)
                    awaitUntil("the initial load", timeoutMs = 1500) { calls.toRoute(listReturns).size == 1 }
                    val hint = element().first("#sepa-returns-filter-hint")
                    assertTrue(hint.textContent.orEmpty().contains("Beispiel: 2026-03-14."))
                    val from = element().controlOf("Von")
                    assertEquals("sepa-returns-filter-hint sepa-returns-filter-error", from.getAttribute("aria-describedby"))

                    element().typeInto("Von", "13.03.2026")
                    element().buttonNamed("Filtern").click()
                    delay(120)
                    assertEquals(1, calls.toRoute(listReturns).size, "an unreadable date is not a filter: no second request")
                    assertEquals(
                        "Bitte ein gültiges Datum angeben.",
                        element()
                            .first("#sepa-returns-filter-error")
                            .textContent
                            .orEmpty()
                            .trim(),
                    )
                    assertEquals("true", from.getAttribute("aria-invalid"))
                    assertFalse(
                        element().textContent.orEmpty().contains("Keine Rücklastschrift im gewählten Zeitraum"),
                        "and no false empty message about a period that was never asked for",
                    )

                    element().typeInto("Von", "2026-03-13")
                    element().buttonNamed("Filtern").click()
                    awaitUntil("a readable date filters", timeoutMs = 1500) { calls.toRoute(listReturns).size == 2 }
                    assertEquals(
                        "",
                        element()
                            .first("#sepa-returns-filter-error")
                            .textContent
                            .orEmpty()
                            .trim(),
                    )
                    assertEquals("false", from.getAttribute("aria-invalid"))
                }
            }
        }

    // ── the marker leak: a tr() result as a gettext argument ─────────────────────────────────────────────────────

    @Test
    fun gettext_neverPutsAMarkerOfATrArgumentIntoTheText() {
        val text = io.kvision.i18n.gettext("Posten angelegt, aber nicht gebucht: %1", io.kvision.i18n.tr("Kein Bankkonto zugeordnet."))
        assertFalse(text.contains("###KvI18nS###"), "the marker of a tr() argument must never be visible: $text")
        assertTrue(text.contains("Kein Bankkonto zugeordnet."))
        assertFalse(
            (openItemPostingErrorMessage("payables_account_not_configured") ?: "").contains("###"),
            "the posting-error helper returns resolved text",
        )
    }

    // ── the report filters start untouched, and say what they expect ────────────────────────────────────────────

    @Test
    fun costCenterReport_startsUntouched_loadsOnceWithTheDefaults_andAnUnreadableDateIsAFieldError(): Promise<Unit> =
        formTest {
            val report = routeOf { rpcService<IAccountingService>().getCostCenterReport(null, LocalDate(2026, 1, 1)) }
            withFetchStub { calls ->
                mountedForm("p3a-cc-report") { root, element ->
                    renderCostCenterReportView(root)
                    awaitUntil("the first load", timeoutMs = 1500) { calls.toRoute(report).size == 1 }
                    assertTrue(element().shownErrors().isEmpty(), "the freshly shown form has no error: ${element().shownErrors()}")
                    assertTrue(
                        element().querySelector(".lapis-form-alert--shown") == null,
                        "and no collective message: the first render does not run validateAndReport()",
                    )
                    element().typeInto("Bis", "morgen")
                    element().buttonNamed("Laden").click()
                    delay(120)
                    assertEquals(1, calls.toRoute(report).size, "an unreadable date: no second request")
                    assertTrue(element().shownErrors().contains("Bitte ein gültiges Datum angeben."), "shown: ${element().shownErrors()}")
                }
            }
        }

    @Test
    fun donationDutyReport_theYearIsAFieldWithAnExampleHint_startsUntouched_andABadYearIsAFieldErrorNotALooseSentence(): Promise<Unit> =
        formTest {
            val report = routeOf { rpcService<IAccountingService>().getDonationDutyReport(2026) }
            withFetchStub { calls ->
                mountedForm("p3a-duty-report") { root, element ->
                    renderDonationDutyReportView(root)
                    awaitUntil("the first load", timeoutMs = 1500) { calls.toRoute(report).size == 1 }
                    assertTrue(element().shownErrors().isEmpty(), "untouched on first render: ${element().shownErrors()}")
                    val year = (element().controlOf("Kalenderjahr") as HTMLInputElement)
                    assertEquals(thisYear().toString(), year.value, "the year is prefilled")
                    assertTrue(
                        element().allOf(".form-text").any { it.textContent.orEmpty().trim() == "Beispiel: ${thisYear()}." },
                        "the format stands in the hint",
                    )
                    element().typeInto("Kalenderjahr", "zweitausend")
                    element().buttonNamed("Laden").click()
                    delay(120)
                    assertEquals(1, calls.toRoute(report).size, "a bad year sends nothing")
                    assertTrue(
                        element().shownErrors().contains("Bitte ein gültiges Kalenderjahr angeben."),
                        "the error stands AT the field: ${element().shownErrors()}",
                    )
                    element().typeInto("Kalenderjahr", "2025")
                    element().buttonNamed("Laden").click()
                    awaitUntil("a readable year loads", timeoutMs = 1500) { calls.toRoute(report).size == 2 }
                    assertEquals(2025, calls.toRoute(report)[1].rpcParam(0).unsafeCast<Int>())
                }
            }
        }

    // ── a rejected statement: the raw line (IBAN, amount) is not read aloud ──────────────────────────────────────

    @Test
    fun rejectedStatement_theRawLineStandsInAnOrdinaryDetailArea_notInTheAlertRegion(): Promise<Unit> =
        formTest {
            val rejection =
                Json.encodeToString(
                    BankStatementImportRejectionDto.serializer(),
                    BankStatementImportRejectionDto(
                        code = BankStatementRejectionCode.PARSE_FAILED,
                        lineNumber = 3,
                        rawLineExcerpt = "2026-01-05;DE89370400440532013000;25,00",
                    ),
                )
            withFetchStub(respond = { r -> if (!r.isRpc) StubResponse(status = 422, text = rejection) else StubResponse() }) { calls ->
                mountedForm("p3a-reject") { root, element ->
                    renderUploadPanel(root, onUploadStarted = {}) {}
                    val input = element().first("input[type=file]") as HTMLInputElement
                    val transfer = js("new DataTransfer()")
                    transfer.items.add(js("new File(['x'], 'auszug.csv', { type: 'text/csv' })"))
                    input.asDynamic().files = transfer.files
                    input.dispatchEvent(Event("change"))
                    element().buttonNamed("Hochladen").click()
                    awaitUntil("the rejection shows", timeoutMs = 1500) {
                        calls.any { it.url.contains("/api/bank-statements/import") } &&
                            element().querySelector(".lapis-form-alert--shown") != null
                    }
                    val alert = element().first(".lapis-form-alert--shown").textContent.orEmpty()
                    assertFalse(alert.contains("DE89370400440532013000"), "the alert region holds no account data: $alert")
                    assertTrue(
                        element().textContent.orEmpty().contains("Betroffene Zeile: 2026-01-05;DE89370400440532013000;25,00"),
                        "the raw line stands in an ordinary detail area",
                    )
                }
            }
        }

    // ── KVision: two onEvent blocks on one widget are additive ──────────────────────────────────────────────────

    @Test
    fun twoOnEventBlocksOnOneInput_bothFire_soTheCrossRuleListenerDoesNotReplaceTheFieldsOwn(): Promise<Unit> =
        formTest {
            mountedForm("p3a-onevent") { root, element ->
                val input =
                    io.kvision.form.text
                        .Text(label = "x")
                root.add(input)
                var first = 0
                var second = 0
                input.onEvent { change = { first++ } }
                input.onEvent { change = { second++ } }
                delay(50)
                (element().first("input")).dispatchEvent(Event("change"))
                assertEquals(1, first, "the field's own listener (registered first) still fires")
                assertEquals(1, second, "and so does the listener a cross rule adds later")
            }
        }
}
