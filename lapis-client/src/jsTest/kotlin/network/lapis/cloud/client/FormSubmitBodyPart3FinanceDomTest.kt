package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AccountingExportConnectionDto
import network.lapis.cloud.shared.domain.AccountingExportItemDto
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import network.lapis.cloud.shared.domain.AccountingExportZeroVatDisclaimerDto
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.BankStatementLineDto
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementMatchCandidateDto
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.FinTsComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.FinTsSetupResultDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.NettingPreviewDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchInput
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewDto
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewItemDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemDto
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateDto
import network.lapis.cloud.shared.domain.SepaMandateInput
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaReturnInput
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.rpc.IAccountingExportService
import network.lapis.cloud.shared.rpc.IBankAccountService
import network.lapis.cloud.shared.rpc.IBankStatementService
import network.lapis.cloud.shared.rpc.IOpenItemService
import network.lapis.cloud.shared.rpc.ISepaService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.4.30 audit round (M4 + M8): the request bodies of the finance forms that had no test yet, FIELD BY FIELD with DISTINCT values in
 * neighbouring same-typed slots (an id is never a reason, a user id is never a PIN, a payable id is never the receivable id), and "no RPC
 * at all" for input the form must stop. Expected values come from the OLD code (`git show b776e93:<file>`) and the server limits, not from
 * the new code. **Not covered here (named, not hidden):** `SepaMandatesScreen`'s own row action for `revokeMandate` (same dialog, same
 * call as the section below), `AccountingExportScreen`'s run flow (`previewExport`, `startExport`, `mapAccount`), the SEPA creditor
 * settings, and the FinTS `cancelFinTsSetup` path.
 */
class FormSubmitBodyPart3FinanceDomTest {
    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    private fun HTMLElement.buttonStartingWith(text: String): HTMLElement =
        assertNotNull(
            allOf("button").firstOrNull {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith(text)
            },
            "no button starting with '$text'",
        )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────

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

    private fun openItem(
        id: String,
        direction: OpenItemDirection = OpenItemDirection.RECEIVABLE,
        open: Double = 100.0,
        reference: String? = null,
        note: String? = null,
    ) = OpenItemDto(
        id = id,
        direction = direction,
        counterpartyName = "Muster GmbH",
        counterpartyKey = "muster",
        reference = reference,
        note = note,
        itemDate = LocalDate(2026, 1, 1),
        dueDate = LocalDate(2026, 2, 1),
        amount = open.toDecimal(),
        openAmount = open.toDecimal(),
        contraAccountId = "a2",
        contraAccountNumber = "4200",
        contraAccountName = "Spenden",
        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        status = OpenItemStatus.OPEN,
        daysOverdue = 0,
        asOf = LocalDate(2026, 1, 1),
        createdByMemberId = "m1",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    private fun batch(
        id: String,
        status: SepaDebitBatchStatus = SepaDebitBatchStatus.SUBMITTED,
    ) = SepaDebitBatchDto(
        id = id,
        messageId = "m-$id",
        paymentInfoId = "p-$id",
        requestedCollectionDate = LocalDate(2026, 1, 15),
        sequenceType = SepaSequenceType.RCUR,
        status = status,
        itemCount = 2,
        totalAmount = 20.0.toDecimal(),
        createdByDisplayName = "Kassenwart",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        notifiedAt = null,
        requiredNoticeDays = null,
        fileGenerationAllowedFrom = LocalDate(2026, 1, 1),
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

    private fun sepaItem(
        id: String,
        name: String,
        status: SepaDebitItemStatus = SepaDebitItemStatus.PENDING,
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
        status = status,
        settleableAt = null,
        journalEntryId = null,
        returnReason = null,
    )

    private fun mandate(id: String) =
        SepaMandateDto(
            id = id,
            memberId = "m-own",
            memberDisplayName = "Lena Berg",
            mandateReference = "MREF-1",
            debtorName = "Lena Berg",
            debtorIbanLast4 = "3000",
            debtorBic = null,
            signatureDate = LocalDate(2026, 1, 1),
            sequenceType = SepaSequenceType.FRST,
            status = SepaMandateStatus.ACTIVE,
            grantedAt = LocalDateTime(2026, 1, 1, 10, 0),
            revokedAt = null,
            revocationReason = null,
            lastUsedAt = null,
            lastDebitedAmount = null,
            expiresAt = LocalDate(2029, 1, 1),
            createdByMemberId = "m-own",
            createdByDisplayName = "Lena Berg",
            createdBySelf = true,
        )

    private fun line(id: String = "l1") =
        BankStatementLineDto(
            id = id,
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

    private fun bankAccount(id: String = "ba1") =
        BankAccountDto(
            id = id,
            label = "Hauptkonto",
            iban = "DE89370400440532013000",
            ibanMasked = "DE89••3000",
            bic = "COBADEFF",
            bankName = "Commerzbank",
            isDefault = true,
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            updatedAt = LocalDateTime(2026, 1, 1, 10, 0),
        )

    // ── settleOpenItem ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun settlement_prefillsTheOpenAmount_andSendsItemAmountDateAndBankAccountEachInItsOwnSlot(): Promise<Unit> =
        formTest {
            val settle = routeOf { rpcService<IOpenItemService>().settleOpenItem("i", 1.0.toDecimal(), LocalDate(2026, 1, 1), null) }
            withFetchStub { calls ->
                mountedForm("p3b-settle") { _, _ ->
                    openItemSettlementDialog(
                        item = openItem("oi-77", open = 100.0),
                        accounts = { accounts },
                        paymentMapping = { PaymentAccountMapping(defaultBankAccountId = "a1") },
                        knownSettlementIds = emptySet(),
                        onDone = {},
                    )
                    val modal = lastOpenModal()
                    assertEquals(
                        "100",
                        (modal.controlOf("Betrag in EUR") as HTMLInputElement).value,
                        "the amount is prefilled with the open amount",
                    )
                    modal.typeInto("Betrag in EUR", "  40,50  ")
                    // A date in the past on purpose: the server does not validate `settledOn`, the client must pass it through unchanged.
                    modal.typeInto("Zahlungsdatum", "2026-03-10")
                    modal.chooseIn("Zahlungskonto", "a1")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(settle).size, "nothing goes out before the confirmation")
                    modal.buttonNamed("Jetzt ausgleichen").click()
                    awaitUntil("settleOpenItem", timeoutMs = 1500) { calls.toRoute(settle).size == 1 }
                    val request = calls.singleCall(settle)
                    assertEquals("oi-77", request.rpcParam(0) as String, "the open item id is the first slot")
                    assertEquals(
                        40.5,
                        request.rpcParam(1).unsafeCast<Double>(),
                        "the amount is what was typed, trimmed, not the open amount",
                    )
                    assertEquals("2026-03-10", request.rpcParam(2) as String, "the date is unchanged")
                    assertEquals("a1", request.rpcParam(3) as String, "the bank account id is the LAST slot, never the item id")
                }
            }
        }

    @Test
    fun settlement_withTheDefaultBankAccount_sendsNullForTheAccount_andAnAmountOverTheOpenAmountSendsNothing(): Promise<Unit> =
        formTest {
            val settle = routeOf { rpcService<IOpenItemService>().settleOpenItem("i", 1.0.toDecimal(), LocalDate(2026, 1, 1), null) }
            withFetchStub { calls ->
                mountedForm("p3b-settle2") { _, _ ->
                    openItemSettlementDialog(
                        item = openItem("oi-78", open = 50.0),
                        accounts = { accounts },
                        paymentMapping = { PaymentAccountMapping(defaultBankAccountId = "a1") },
                        knownSettlementIds = emptySet(),
                        onDone = {},
                    )
                    val modal = lastOpenModal()
                    modal.typeInto("Betrag in EUR", "50,01")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(settle).size, "an amount above the open amount: nothing is sent")
                    modal.typeInto("Betrag in EUR", "50")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    modal.buttonNamed("Jetzt ausgleichen").click()
                    awaitUntil("settleOpenItem", timeoutMs = 1500) { calls.toRoute(settle).size == 1 }
                    assertNull(calls.singleCall(settle).rpcParam(3), "the default account: no id, the server decides")
                }
            }
        }

    // ── executeNetting ───────────────────────────────────────────────────────────────────────────────────────────

    private fun candidate(
        index: Int,
        max: Double,
    ) = NettingCandidateDto(
        counterpartyKey = "cp$index",
        counterpartyDisplayName = "Gegenpartei $index",
        matchedByNameOnly = false,
        payable = openItem("pay-$index", OpenItemDirection.PAYABLE, open = max),
        receivable = openItem("rec-$index", OpenItemDirection.RECEIVABLE, open = max),
        maxNettableAmount = max.toDecimal(),
    )

    @Test
    fun netting_switchingTheCandidateClearsTheOverLimitError_andTheExecutedCallCarriesPayableReceivableAndAmountInOrder(): Promise<Unit> =
        formTest {
            val list = routeOf { rpcService<IOpenItemService>().listNettingCandidates(200) }
            val preview = routeOf { rpcService<IOpenItemService>().previewNetting("a", "b", 1.0.toDecimal()) }
            val execute = routeOf { rpcService<IOpenItemService>().executeNetting("a", "b", 1.0.toDecimal()) }
            val candidates = jsonOf(ListSerializer(NettingCandidateDto.serializer()), listOf(candidate(0, 100.0), candidate(1, 250.0)))
            val previewJson =
                jsonOf(
                    ListSerializer(NettingPreviewDto.serializer()),
                    listOf(
                        NettingPreviewDto(
                            debitAccountNumber = "1600",
                            debitAccountName = "Verbindlichkeiten",
                            creditAccountNumber = "1400",
                            creditAccountName = "Forderungen",
                            amount = 250.0.toDecimal(),
                            payableOpenAmountAfter = 0.0.toDecimal(),
                            receivableOpenAmountAfter = 0.0.toDecimal(),
                            payableStatusAfter = OpenItemStatus.SETTLED,
                            receivableStatusAfter = OpenItemStatus.SETTLED,
                        ),
                    ),
                )
            withFetchStub(respond = answering(list to candidates, preview to previewJson)) { calls ->
                mountedForm("p3b-netting") { _, _ ->
                    openItemNettingDialog {}
                    awaitUntil("the candidates load", timeoutMs = 1500) { lastOpenModal().querySelector("select") != null }
                    val modal = lastOpenModal()
                    assertEquals(
                        "100",
                        (modal.controlOf("Betrag in EUR") as HTMLInputElement).value,
                        "prefilled with the first candidate's maximum",
                    )

                    modal.typeInto("Betrag in EUR", "300")
                    assertTrue(
                        modal.shownErrors().any { it.contains("verrechenbaren Betrag") },
                        "300 is above the first candidate's 100: the error stands at the field: ${modal.shownErrors()}",
                    )
                    // Choosing the second candidate writes ITS maximum (250) into the amount programmatically: the standing error was about
                    // the old candidate and must be gone -- only `validate(force = false)` after the write clears it.
                    modal.chooseIn("Gegenpartei", "1")
                    assertEquals("250", (modal.controlOf("Betrag in EUR") as HTMLInputElement).value)
                    assertTrue(
                        modal.shownErrors().isEmpty(),
                        "the over-limit error is gone after the candidate switch: ${modal.shownErrors()}",
                    )

                    awaitUntil("the preview arrives and the button is released", timeoutMs = 1500) {
                        !modal.buttonNamed("Verrechnen …").hasAttribute("disabled")
                    }
                    modal.buttonNamed("Verrechnen …").click()
                    delay(80)
                    modal.buttonNamed("Endgültig verrechnen").click()
                    awaitUntil("executeNetting", timeoutMs = 1500) { calls.toRoute(execute).size == 1 }
                    val request = calls.singleCall(execute)
                    assertEquals("pay-1", request.rpcParam(0) as String, "the payable id comes first")
                    assertEquals("rec-1", request.rpcParam(1) as String, "the receivable id second -- never swapped")
                    assertEquals(250.0, request.rpcParam(2).unsafeCast<Double>())
                }
            }
        }

    @Test
    fun netting_theOnlyFieldTheUserMustFill_theAmount_carriesTheStar_notTheNeverEmptyCandidateSelect(): Promise<Unit> =
        formTest {
            val list = routeOf { rpcService<IOpenItemService>().listNettingCandidates(200) }
            val candidates = jsonOf(ListSerializer(NettingCandidateDto.serializer()), listOf(candidate(0, 100.0)))
            withFetchStub(respond = answering(list to candidates)) { _ ->
                mountedForm("p3b-netting-star") { _, _ ->
                    openItemNettingDialog {}
                    awaitUntil("the candidates load", timeoutMs = 1500) { lastOpenModal().querySelector("select") != null }
                    val modal = lastOpenModal()
                    val labels = modal.allOf("label")
                    val amountLabel =
                        labels.first {
                            it.textContent
                                .orEmpty()
                                .trim()
                                .startsWith("Betrag in EUR")
                        }
                    val candidateLabel =
                        labels.first {
                            it.textContent
                                .orEmpty()
                                .trim()
                                .startsWith("Gegenpartei")
                        }
                    assertNotNull(amountLabel.querySelector(".lapis-required-mark"), "the amount carries the star")
                    assertNull(
                        candidateLabel.querySelector(".lapis-required-mark"),
                        "a select that can never be empty is not marked as required",
                    )
                    assertTrue(modal.textContent.orEmpty().contains("* Pflichtfeld"), "and the legend explains the star")
                }
            }
        }

    // ── updateOpenItemMetadata ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun metadataDialog_sendsIdReferenceAndNoteEachInItsSlot_trimmed_andABlankNoteIsNull(): Promise<Unit> =
        formTest {
            val update = routeOf { rpcService<IOpenItemService>().updateOpenItemMetadata("i", null, null) }
            withFetchStub { calls ->
                mountedForm("p3b-meta") { _, _ ->
                    openItemMetadataDialog(openItem("oi-9", reference = "R-alt", note = "Notiz alt")) {}
                    val modal = lastOpenModal()
                    assertEquals(
                        "R-alt",
                        (modal.controlOf("Belegnummer") as HTMLInputElement).value,
                        "the fields start with the stored values",
                    )
                    modal.typeInto("Belegnummer", "  R-2026-7  ")
                    modal.typeInto("Notiz", "")
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("updateOpenItemMetadata", timeoutMs = 1500) { calls.toRoute(update).size == 1 }
                    val request = calls.singleCall(update)
                    assertEquals("oi-9", request.rpcParam(0) as String)
                    assertEquals("R-2026-7", request.rpcParam(1) as String, "the reference is trimmed and in its own slot")
                    assertNull(request.rpcParam(2), "a blank note is null, never \"\"")
                }
            }
        }

    // ── recordReturn ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun recordReturn_sendsTheItemNotTheBatch_theDateTheReasonTheTrimmedFreeTextAndTheFeeEachInItsSlot(): Promise<Unit> =
        formTest {
            val list = routeOf { rpcService<ISepaService>().listBatches(status = SepaDebitBatchStatus.SUBMITTED, limit = 100) }
            val get = routeOf { rpcService<ISepaService>().getBatch("x") }
            val record =
                routeOf { rpcService<ISepaService>().recordReturn(SepaReturnInput("i", LocalDate(2026, 1, 1), SepaReturnReason.AC01)) }
            val batches = jsonOf(ListSerializer(SepaDebitBatchDto.serializer()), listOf(batch("b-77")))
            val detail =
                jsonOf(
                    SepaDebitBatchDetailDto.serializer(),
                    SepaDebitBatchDetailDto(batch = batch("b-77"), items = listOf(sepaItem("i-a", "Anna"), sepaItem("i-b", "Bert"))),
                )
            withFetchStub(respond = { r ->
                when {
                    !r.isRpc -> StubResponse()
                    r.rpcRoute == list -> if (r.rpcParam(0) == "SUBMITTED") r.answerWith(batches) else r.answerWith("[]")
                    r.rpcRoute == get -> r.answerWith(detail)
                    else -> r.answerWith("null")
                }
            }) { calls ->
                mountedForm("p3b-return") { root, element ->
                    renderRecordReturnForm(root) {}
                    awaitUntil("the item options load", timeoutMs = 1500) {
                        (element().controlOf("Position") as HTMLSelectElement).value ==
                            "i-a"
                    }
                    element().chooseIn("Position", "i-b")
                    element().typeInto("Rücklastschrift-Datum", "2026-02-11")
                    element().chooseIn("Grund", SepaReturnReason.OTHER.name)
                    element().typeInto("Freitext", "  Konto aufgelöst  ")
                    element().typeInto("Rücklastschriftgebühr in EUR", "3,50")
                    element().buttonNamed("Rücklastschrift erfassen").click()
                    awaitUntil("recordReturn", timeoutMs = 1500) { calls.toRoute(record).size == 1 }
                    val input = calls.singleCall(record).rpcParam(0)
                    assertEquals("i-b", input.debitItemId as String, "the chosen ITEM id, not the batch id 'b-77'")
                    assertEquals("2026-02-11", input.returnedAt as String)
                    assertEquals("OTHER", input.reasonCode as String)
                    assertEquals("Konto aufgelöst", input.reasonText as String, "the free text is trimmed")
                    assertEquals(3.5, input.returnFee.unsafeCast<Double>())
                }
            }
        }

    // ── grantMandate ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun grantMandate_theBicIsUppercasedTheIbanIsSentVerbatimTrimmed_andEveryFieldIsInItsOwnSlot(): Promise<Unit> =
        formTest {
            val grant =
                routeOf { rpcService<ISepaService>().grantMandate(SepaMandateInput("m", "n", "iban", null, LocalDate(2026, 1, 1), true)) }
            withFetchStub { calls ->
                mountedForm("p3b-mandate") { root, element ->
                    renderSepaMandateForm(
                        root,
                        onBehalf = true,
                        defaultDebtorName = "",
                        memberOptions = listOf("m-1" to "Anna", "m-2" to "Bert"),
                    ) {}
                    element().chooseIn("Mitglied", "m-2")
                    element().typeInto("Name des Kontoinhabers", "  Bert Beispiel  ")
                    element().typeInto("IBAN", "  DE89 3704 0044 0532 0130 00  ")
                    element().typeInto("BIC", "cobadeff")
                    element().typeInto("Datum der Unterschrift", "2026-03-01")

                    // Without the acknowledgement: no RPC, the message stands at the checkbox.
                    element().buttonNamed("Mandat erteilen").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(grant).size, "the unticked authorisation sends nothing at all")
                    assertTrue(
                        element().shownErrors().any { it.contains("SEPA-Lastschriftmandat bestätigen") },
                        "shown: ${element().shownErrors()}",
                    )

                    element().tick("Ich ermächtige")
                    element().buttonNamed("Mandat erteilen").click()
                    awaitUntil("grantMandate", timeoutMs = 1500) { calls.toRoute(grant).size == 1 }
                    val input = calls.singleCall(grant).rpcParam(0)
                    assertEquals("m-2", input.memberId as String, "the chosen member")
                    assertEquals("Bert Beispiel", input.debtorName as String, "the holder name is trimmed")
                    assertEquals("DE89 3704 0044 0532 0130 00", input.debtorIban as String, "the IBAN is only trimmed, never rewritten")
                    assertEquals(
                        "COBADEFF",
                        input.debtorBic as String,
                        "the BIC is upper-cased for the server (its regex sees the raw string)",
                    )
                    assertEquals("2026-03-01", input.signatureDate as String)
                    assertEquals(true, input.mandateTextAcknowledged as Boolean)
                }
            }
        }

    // ── createDebitBatch ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun createBatch_sendsTheSameInputAsThePreview_bothDatesApart_andAFailedCreateWithdrawsTheStaleButton(): Promise<Unit> =
        formTest {
            val preview =
                routeOf { rpcService<ISepaService>().previewDebitBatch(SepaDebitBatchInput(LocalDate(2026, 1, 1), LocalDate(2026, 1, 1))) }
            val create =
                routeOf { rpcService<ISepaService>().createDebitBatch(SepaDebitBatchInput(LocalDate(2026, 1, 1), LocalDate(2026, 1, 1))) }
            val previewJson =
                jsonOf(
                    SepaDebitBatchPreviewDto.serializer(),
                    SepaDebitBatchPreviewDto(
                        itemCount = 1,
                        totalAmount = 10.0.toDecimal(),
                        items = listOf(SepaDebitBatchPreviewItemDto("c1", "Anna", 10.0.toDecimal(), "REF", "1234", false)),
                        excluded = emptyList(),
                    ),
                )
            withFetchStub(respond = { r ->
                when {
                    !r.isRpc -> StubResponse()
                    r.rpcRoute == preview -> r.answerWith(previewJson)
                    // The create call FAILS (a conflict / a dropped connection): the preview button must not stand there with the old numbers.
                    r.rpcRoute == create -> StubResponse(networkError = true)
                    else -> r.answerWith("null")
                }
            }) { calls ->
                mountedForm("p3b-batch") { root, element ->
                    renderNewBatchSection(root) {}
                    element().typeInto("Einzugsdatum", "2099-01-15")
                    element().typeInto("Fällig bis", "2098-12-31")
                    element().buttonNamed("Vorschau berechnen").click()
                    awaitUntil("the create button appears", timeoutMs = 1500) {
                        element().allOf("button").any { it.textContent.orEmpty().startsWith("Lauf anlegen (1 Positionen") }
                    }
                    element().buttonStartingWith("Lauf anlegen").click()
                    awaitUntil("createDebitBatch", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("2099-01-15", input.requestedCollectionDate as String)
                    assertEquals("2098-12-31", input.dueOnOrBefore as String, "the two dates are not swapped")
                    assertNull(input.membershipTierId, "no tier chosen: null")
                    awaitUntil("the failed create withdraws the stale button", timeoutMs = 1500) {
                        element().allOf("button").none { it.textContent.orEmpty().startsWith("Lauf anlegen") }
                    }
                }
            }
        }

    // ── revokeMandate / markBatchSubmitted / cancelBatch / settleBatch ───────────────────────────────────────────

    @Test
    fun revokeMandate_sendsTheMandateIdAndTheTrimmedReasonInTheirOwnSlots_andABlankReasonIsNull(): Promise<Unit> =
        formTest {
            val mine = routeOf { rpcService<ISepaService>().getMyMandate() }
            val revoke = routeOf { rpcService<ISepaService>().revokeMandate("m", null) }
            val mandates = jsonOf(ListSerializer(SepaMandateDto.serializer()), listOf(mandate("md-77")))
            withFetchStub(respond = answering(mine to mandates)) { calls ->
                mountedForm("p3b-revoke") { root, element ->
                    renderSepaMandateSection(root)
                    awaitUntil("the mandate shows", timeoutMs = 1500) {
                        element().allOf("button").any {
                            it.textContent?.trim() ==
                                "Mandat widerrufen"
                        }
                    }
                    element().buttonNamed("Mandat widerrufen").click()
                    val modal = lastOpenModal()
                    modal.typeInto("Grund", "  Umzug ins Ausland  ")
                    modal.buttonNamed("Widerrufen").click()
                    awaitUntil("revokeMandate", timeoutMs = 1500) { calls.toRoute(revoke).size == 1 }
                    val request = calls.singleCall(revoke)
                    assertEquals("md-77", request.rpcParam(0) as String, "the mandate id first")
                    assertEquals("Umzug ins Ausland", request.rpcParam(1) as String, "the reason second, trimmed")
                }
            }
        }

    private fun batchDetail(
        id: String,
        status: SepaDebitBatchStatus,
        items: List<SepaDebitItemDto> = emptyList(),
    ) = SepaDebitBatchDetailDto(batch = batch(id, status), items = items)

    @Test
    fun batchActions_markSubmittedCarriesTheNote_cancelCarriesTheReason_settleCarriesOnlyTheId(): Promise<Unit> =
        formTest {
            val submitted = routeOf { rpcService<ISepaService>().markBatchSubmitted("b", null) }
            val cancel = routeOf { rpcService<ISepaService>().cancelBatch("b", "r") }
            val settle = routeOf { rpcService<ISepaService>().settleBatch("b") }
            withFetchStub { calls ->
                mountedForm("p3b-batch-actions") { root, element ->
                    // GENERATED -> "Als eingereicht markieren" (note optional) and "Stornieren" (reason required)
                    renderSepaBatchDetail(
                        root,
                        batchDetail("b-gen", SepaDebitBatchStatus.GENERATED),
                        AccountRole.TREASURER,
                        onChanged = {},
                        onSettled = {},
                    )
                    element().buttonNamed("Als eingereicht markieren").click()
                    val submitModal = lastOpenModal()
                    submitModal.typeInto("Notiz", "  Bei der Bank eingereicht  ")
                    submitModal.buttonNamed("Als eingereicht markieren").click()
                    awaitUntil("markBatchSubmitted", timeoutMs = 1500) { calls.toRoute(submitted).size == 1 }
                    val submittedRequest = calls.singleCall(submitted)
                    assertEquals("b-gen", submittedRequest.rpcParam(0) as String)
                    assertEquals("Bei der Bank eingereicht", submittedRequest.rpcParam(1) as String, "the note is trimmed")
                    closeOpenModals(timeoutMs = 600)

                    element().buttonNamed("Stornieren").click()
                    val cancelModal = lastOpenModal()
                    cancelModal.buttonNamed("Stornieren").click()
                    delay(80)
                    assertTrue(calls.toRoute(cancel).isEmpty(), "the reason is required: nothing is sent without it")
                    cancelModal.typeInto("Grund", "  Falsches Einzugsdatum  ")
                    cancelModal.buttonNamed("Stornieren").click()
                    awaitUntil("cancelBatch", timeoutMs = 1500) { calls.toRoute(cancel).size == 1 }
                    val cancelRequest = calls.singleCall(cancel)
                    assertEquals("b-gen", cancelRequest.rpcParam(0) as String, "the batch id first")
                    assertEquals("Falsches Einzugsdatum", cancelRequest.rpcParam(1) as String, "the reason second, trimmed")
                }
            }
            withFetchStub { calls ->
                mountedForm("p3b-batch-settle") { root, element ->
                    renderSepaBatchDetail(
                        root,
                        batchDetail(
                            "b-sub",
                            SepaDebitBatchStatus.SUBMITTED,
                            listOf(sepaItem("i1", "Anna", SepaDebitItemStatus.SETTLEABLE)),
                        ),
                        AccountRole.TREASURER,
                        onChanged = {},
                        onSettled = {},
                    )
                    element().buttonNamed("Abrechnen").click()
                    awaitUntil("settleBatch", timeoutMs = 1500) { calls.toRoute(settle).size == 1 }
                    assertEquals("b-sub", calls.singleCall(settle).rpcParam(0) as String)
                }
            }
        }

    // ── FinTS: the user id and the PIN are two neighbouring secrets ──────────────────────────────────────────────

    @Test
    fun finTsSetup_sendsTheUserIdAndThePinInTheirOwnSlots_neverPutsThePinIntoTheDom_andTheTanGoesWithItsHandle(): Promise<Unit> =
        formTest {
            val begin =
                routeOf {
                    rpcService<IBankAccountService>().beginFinTsSetup(
                        network.lapis.cloud.shared.domain
                            .FinTsSetupInput("a", "b", "c", "u", "p", "v", "s"),
                    )
                }
            val tan = routeOf { rpcService<IBankAccountService>().submitFinTsTan("h", "t") }
            val tanRequested =
                jsonOf(
                    FinTsSetupResultDto.serializer(),
                    FinTsSetupResultDto.TanRequested("handle-42", "Bitte TAN eingeben", LocalDateTime(2099, 1, 1, 10, 0)),
                )
            withFetchStub(respond = answering(begin to tanRequested)) { calls ->
                mountedForm("p3b-fints") { _, _ ->
                    showFinTsSetupModal(
                        account = bankAccount("ba-5"),
                        disclaimer = FinTsComplianceDisclaimerDto(version = "v-3", text = "Hinweis", sha256 = "sha-xyz"),
                        onChanged = {},
                    )
                    val modal = lastOpenModal()
                    modal.buttonNamed("Aktivieren").click()
                    delay(80)
                    assertEquals(
                        0,
                        calls.toRoute(begin).size,
                        "the unticked acknowledgement sends nothing at all (no grey button, a message at the box)",
                    )
                    assertTrue(modal.shownErrors().isNotEmpty(), "and says why: ${modal.shownErrors()}")

                    modal.tick("Ich habe den Hinweistext")
                    modal.typeInto("Bankleitzahl", "10010010")
                    modal.typeInto("FinTS/HBCI-URL", "https://bank.example/fints")
                    modal.typeInto("Benutzerkennung", "user-4711")
                    modal.typeInto("PIN", "pin-9999")
                    assertFalse(document.body!!.outerHTML.contains("pin-9999"), "the PIN never appears in an attribute of the document")
                    assertFalse(document.body!!.outerHTML.contains("user-4711"), "neither does the user id")
                    assertEquals("password", modal.controlOf("PIN").getAttribute("type"), "the PIN field is a password field")
                    modal.buttonNamed("Aktivieren").click()
                    awaitUntil("beginFinTsSetup", timeoutMs = 1500) { calls.toRoute(begin).size == 1 }
                    val input = calls.singleCall(begin).rpcParam(0)
                    assertEquals("ba-5", input.bankAccountId as String)
                    assertEquals("10010010", input.blz as String)
                    assertEquals("https://bank.example/fints", input.url as String)
                    assertEquals("user-4711", input.userId as String, "the user id is the user id ...")
                    assertEquals("pin-9999", input.pin as String, "... and the PIN is the PIN, never the other way round")
                    assertEquals("v-3", input.disclaimerVersion as String)
                    assertEquals("sha-xyz", input.disclaimerSha256 as String)

                    awaitUntil("the TAN step shows", timeoutMs = 1500) {
                        modal.allOf("button").any {
                            it.textContent?.trim() ==
                                "TAN bestätigen" &&
                                it.offsetParent != null
                        }
                    }
                    modal.typeInto("TAN", "123456")
                    modal.buttonNamed("TAN bestätigen").click()
                    awaitUntil("submitFinTsTan", timeoutMs = 1500) { calls.toRoute(tan).size == 1 }
                    val tanRequest = calls.singleCall(tan)
                    assertEquals("handle-42", tanRequest.rpcParam(0) as String, "the handle from the bank's answer first")
                    assertEquals("123456", tanRequest.rpcParam(1) as String, "the TAN second")
                }
            }
        }

    // ── the bank statement workbench ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun assignToDonation_sendsTheLineIdAndEachDonorFieldInItsSlot_andAContributionAssignmentClearsAStaleFormError(): Promise<Unit> =
        formTest {
            val donation =
                routeOf {
                    rpcService<IBankStatementService>().assignLineToDonation(
                        "l",
                        network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput(
                            donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                        ),
                    )
                }
            val contribution = routeOf { rpcService<IBankStatementService>().assignLineToContribution("l", "c", null) }
            val suggest = routeOf { rpcService<IBankStatementService>().suggestMatches("l") }
            val suggestions =
                jsonOf(
                    ListSerializer(BankStatementMatchCandidateDto.serializer()),
                    listOf(
                        BankStatementMatchCandidateDto(
                            contributionId = "c-9",
                            memberDisplayName = "Anna",
                            membershipTierName = "Regulär",
                            periodStart = LocalDate(2026, 1, 1),
                            periodEnd = LocalDate(2026, 12, 31),
                            amountDue = 25.0.toDecimal(),
                            explanation = "x",
                        ),
                    ),
                )
            withFetchStub(respond = answering(suggest to suggestions)) { calls ->
                mountedForm("p3b-workbench") { root, element ->
                    renderAssignmentWorkbench(
                        host = root,
                        line = line("l-3"),
                        members = listOf(MemberSummaryDto("m-1", "Anna Okafor"), MemberSummaryDto("m-2", "Bert Beispiel")),
                        externalDonors =
                            listOf(
                                ExternalDonorDto(
                                    "x-1",
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
                    awaitUntil("the suggestion shows", timeoutMs = 1500) {
                        element().allOf("button").any { it.textContent?.trim() == "Diesem Beitrag zuordnen" }
                    }
                    // A MEMBER donation without a member/category: the cross rule stops it and shows the collective message.
                    element().buttonNamed("Als Spende zuordnen").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(donation).size, "no donation without a category")
                    assertNotNull(element().querySelector(".lapis-form-alert--shown"), "the collective message stands")

                    // Assigning to a contribution is a different action: the stale message of the failed attempt must not stay above it.
                    element().typeInto("Notiz", "  Vereinsbeitrag  ")
                    element().buttonNamed("Diesem Beitrag zuordnen").click()
                    awaitUntil("assignLineToContribution", timeoutMs = 1500) { calls.toRoute(contribution).size == 1 }
                    val contributionRequest = calls.singleCall(contribution)
                    assertEquals("l-3", contributionRequest.rpcParam(0) as String, "the line id first")
                    assertEquals("c-9", contributionRequest.rpcParam(1) as String, "the contribution id second")
                    assertEquals("Vereinsbeitrag", contributionRequest.rpcParam(2) as String, "the note third, trimmed")
                    assertNull(element().querySelector(".lapis-form-alert--shown"), "the stale collective message is gone")

                    // Now a member donation with everything chosen.
                    element().chooseIn("Mitglied", "m-2", nth = 1) // nth 0 is the search field "Mitgliedsname oder Beitragssatz"
                    element().chooseIn("Spenderkategorie", DonorCategory.EU_NATURAL_PERSON.name)
                    element().buttonNamed("Als Spende zuordnen").click()
                    awaitUntil("assignLineToDonation (member)", timeoutMs = 1500) { calls.toRoute(donation).size == 1 }
                    val memberRequest = calls.toRoute(donation).single()
                    assertEquals("l-3", memberRequest.rpcParam(0) as String, "the line id first")
                    val memberInput = memberRequest.rpcParam(1)
                    assertEquals("m-2", memberInput.donorMemberId as String, "the chosen member, not the first")
                    assertEquals("EU_NATURAL_PERSON", memberInput.donorCategory as String)
                    assertEquals("Vereinsbeitrag", memberInput.note as String)
                    assertTrue(memberInput.externalDonorId == null, "no external donor on a member donation")

                    // The external donor: its own id and ITS category (from the donor record), no member id.
                    element().chooseIn("Spendertyp", "EXTERNAL")
                    awaitUntil("the external donor panel shows", timeoutMs = 1500) {
                        element().querySelector("select option[value='x-1']") !=
                            null
                    }
                    element().chooseIn("Externer Spender", "x-1")
                    element().buttonNamed("Als Spende zuordnen").click()
                    awaitUntil("assignLineToDonation (external)", timeoutMs = 1500) { calls.toRoute(donation).size == 2 }
                    val externalInput = calls.toRoute(donation)[1].rpcParam(1)
                    assertEquals("x-1", externalInput.externalDonorId as String)
                    assertEquals(
                        "GERMAN_COMPANY_OR_ORGANIZATION",
                        externalInput.donorCategory as String,
                        "the category of the donor record",
                    )
                    assertTrue(externalInput.donorMemberId == null, "no member id on an external donation")
                }
            }
        }

    // ── the accounting export ───────────────────────────────────────────────────────────────────────────────────

    private val connection =
        AccountingExportConnectionDto(
            provider = AccountingExportProvider.LEXOFFICE,
            connected = false,
            tokenLast4 = null,
            connectedCompanyName = null,
            lastTestedAt = null,
            zeroVatAcknowledged = false,
            zeroVatAcknowledgedAt = null,
        )

    @Test
    fun exportToken_isRequiredAtTheField_sentWithItsProvider_neverInTheDom_andTheSecondaryActionStandsLeftOfThePrimary(): Promise<Unit> =
        formTest {
            val set = routeOf { rpcService<IAccountingExportService>().setToken(AccountingExportProvider.SEVDESK, "t") }
            withFetchStub { calls ->
                mountedForm("p3b-token") { root, element ->
                    renderConnectionSection(root, AccountingExportProvider.LEXOFFICE, connection) {}
                    element().buttonNamed("Token speichern").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(set).size, "no token: no RPC, the message stands at the field")
                    assertTrue(element().shownErrors().contains("Bitte ein Token eingeben."), "shown: ${element().shownErrors()}")

                    element().typeInto("Token", "  geheimes-token-123  ")
                    assertFalse(
                        document.body!!.outerHTML.contains("geheimes-token-123"),
                        "the token is never in an attribute of the document",
                    )
                    assertEquals("password", element().controlOf("Token").getAttribute("type"))
                    element().buttonNamed("Token speichern").click()
                    awaitUntil("setToken", timeoutMs = 1500) { calls.toRoute(set).size == 1 }
                    val request = calls.singleCall(set)
                    assertEquals("LEXOFFICE", request.rpcParam(0) as String, "the provider first")
                    assertEquals("geheimes-token-123", request.rpcParam(1) as String, "the token second, trimmed")

                    val labels = element().allOf("button").map { it.textContent.orEmpty().trim() }
                    assertTrue(
                        labels.indexOf("Verbindung prüfen") in 0 until labels.indexOf("Token speichern"),
                        "the secondary action stands BEFORE (left of) the primary one: $labels",
                    )
                }
            }
        }

    private fun unknownItem() =
        AccountingExportItemDto(
            id = "it-5",
            runId = "run-1",
            journalEntryId = "je-1",
            entryDate = LocalDate(2026, 1, 1),
            voucherNumber = "V-1",
            grossAmount = 10.0.toDecimal(),
            status = AccountingExportItemStatus.UNKNOWN,
            externalVoucherId = null,
            errorCode = null,
            errorMessage = null,
        )

    @Test
    fun resolveUnknown_confirmedSentCarriesTheTrimmedVoucherId_notFoundCarriesNull_andBothCarryTheItemIdFirst(): Promise<Unit> =
        formTest {
            val resolve =
                routeOf {
                    rpcService<IAccountingExportService>().resolveUnknownItem(
                        "i",
                        AccountingExportUnknownItemResolution.CONFIRMED_SENT,
                        null,
                    )
                }
            withFetchStub { calls ->
                mountedForm("p3b-resolve") { root, element ->
                    root.renderResolveUnknownActions(AccountingExportProvider.LEXOFFICE, unknownItem()) {}
                    element().typeInto("Lexware Office-Belegnummer", "  EXT-9  ")
                    element().buttonStartingWith("In ").click()
                    awaitUntil("resolveUnknownItem (sent)", timeoutMs = 1500) { calls.toRoute(resolve).size == 1 }
                    val sent = calls.toRoute(resolve).single()
                    assertEquals("it-5", sent.rpcParam(0) as String)
                    assertEquals("CONFIRMED_SENT", sent.rpcParam(1) as String)
                    assertEquals("EXT-9", sent.rpcParam(2) as String, "the voucher id is trimmed")
                }
            }
            withFetchStub { calls ->
                mountedForm("p3b-resolve2") { root, element ->
                    root.renderResolveUnknownActions(AccountingExportProvider.LEXOFFICE, unknownItem()) {}
                    element().typeInto("Lexware Office-Belegnummer", "EXT-10")
                    element().buttonNamed("Nicht gefunden").click()
                    awaitUntil("resolveUnknownItem (not sent)", timeoutMs = 1500) { calls.toRoute(resolve).size == 1 }
                    val notSent = calls.toRoute(resolve).single()
                    assertEquals("it-5", notSent.rpcParam(0) as String)
                    assertEquals("CONFIRMED_NOT_SENT", notSent.rpcParam(1) as String)
                    assertNull(notSent.rpcParam(2), "a typed voucher id is NOT sent when the voucher was not found")
                }
            }
        }

    @Test
    fun zeroVatAcknowledgement_isARequiredCheckbox_sendsNothingUnticked_andSendsTheProviderAndTheChecksumWhenTicked(): Promise<Unit> =
        formTest {
            val disclaimer = routeOf { rpcService<IAccountingExportService>().getZeroVatDisclaimer(AccountingExportProvider.LEXOFFICE) }
            val acknowledge = routeOf { rpcService<IAccountingExportService>().acknowledgeZeroVat(AccountingExportProvider.LEXOFFICE, "s") }
            val disclaimerJson =
                jsonOf(
                    AccountingExportZeroVatDisclaimerDto.serializer(),
                    AccountingExportZeroVatDisclaimerDto(version = "v1", text = "Hinweistext", sha256 = "sha-abc"),
                )
            withFetchStub(respond = answering(disclaimer to disclaimerJson)) { calls ->
                mountedForm("p3b-zerovat") { root, element ->
                    renderZeroVatSection(root, AccountingExportProvider.LEXOFFICE, connection) {}
                    awaitUntil("the notice text loads", timeoutMs = 1500) { element().textContent.orEmpty().contains("Hinweistext") }
                    delay(50)
                    element().buttonNamed("Bestätigen").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(acknowledge).size, "the unticked box: no acknowledgement is sent")
                    assertTrue(element().shownErrors().isNotEmpty(), "and the box says why: ${element().shownErrors()}")

                    element().tick("Zur Kenntnis genommen")
                    element().buttonNamed("Bestätigen").click()
                    awaitUntil("acknowledgeZeroVat", timeoutMs = 1500) { calls.toRoute(acknowledge).size == 1 }
                    val request = calls.singleCall(acknowledge)
                    assertEquals("LEXOFFICE", request.rpcParam(0) as String, "the provider first")
                    assertEquals("sha-abc", request.rpcParam(1) as String, "the checksum of the text that was shown, second")
                }
            }
        }

    // ── bank accounts (M4) ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun bankAccountForm_rejectsAWrongCheckDigitAtTheField_andSendsLabelIbanBicAndBankNameEachInItsSlot(): Promise<Unit> =
        formTest {
            val create = routeOf { rpcService<IBankAccountService>().createBankAccount(BankAccountInput("l", "i")) }
            val update = routeOf { rpcService<IBankAccountService>().updateBankAccount("i", BankAccountInput("l", "i")) }
            withFetchStub { calls ->
                mountedForm("p3b-bank-create") { _, _ ->
                    bankAccountEditModal(existing = null) {}
                    val modal = lastOpenModal()
                    modal.typeInto("Bezeichnung", "  Spendenkonto  ")
                    modal.typeInto("IBAN", "DE89370400440532013001")
                    modal.typeInto("BIC", "cobadeff")
                    modal.typeInto("Bankname", "  Commerzbank  ")
                    modal.buttonNamed("Speichern").click()
                    delay(80)
                    assertEquals(
                        0,
                        calls.toRoute(create).size,
                        "a wrong check digit never leaves the form (the server would answer with a conflict)",
                    )
                    assertTrue(modal.shownErrors().contains("Die IBAN ist ungültig."), "the field says so: ${modal.shownErrors()}")

                    modal.typeInto("IBAN", "DE89 3704 0044 0532 0130 00")
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("createBankAccount", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("Spendenkonto", input.label as String, "the label is its own slot, trimmed")
                    assertEquals(
                        "DE89 3704 0044 0532 0130 00",
                        input.iban as String,
                        "the IBAN as typed, only trimmed (the server normalises)",
                    )
                    assertEquals("cobadeff", input.bic as String, "the BIC as typed: the server upper-cases its own copy")
                    assertEquals("Commerzbank", input.bankName as String, "the bank name is trimmed")
                }
            }
            withFetchStub { calls ->
                mountedForm("p3b-bank-update") { _, _ ->
                    bankAccountEditModal(existing = bankAccount("ba-9")) {}
                    val modal = lastOpenModal()
                    assertEquals(
                        "DE89370400440532013000",
                        (modal.controlOf("IBAN") as HTMLInputElement).value,
                        "the stored IBAN is prefilled",
                    )
                    modal.typeInto("Bankname", "")
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("updateBankAccount", timeoutMs = 1500) { calls.toRoute(update).size == 1 }
                    val request = calls.singleCall(update)
                    assertEquals("ba-9", request.rpcParam(0) as String, "the account id first")
                    val input = request.rpcParam(1)
                    assertEquals("Hauptkonto", input.label as String)
                    assertEquals("DE89370400440532013000", input.iban as String)
                    assertEquals("COBADEFF", input.bic as String)
                    assertNull(input.bankName, "a blank bank name is null, never \"\"")
                }
            }
        }

    @Test
    fun bankAccountForm_aWrongBicIsRejectedAtTheField_theServersRegex_andAnEmptyBicIsFine(): Promise<Unit> =
        formTest {
            val create = routeOf { rpcService<IBankAccountService>().createBankAccount(BankAccountInput("l", "i")) }
            withFetchStub { calls ->
                mountedForm("p3b-bank-bic") { _, _ ->
                    bankAccountEditModal(existing = null) {}
                    val modal = lastOpenModal()
                    modal.typeInto("Bezeichnung", "Konto")
                    modal.typeInto("IBAN", "DE89370400440532013000")
                    modal.typeInto("BIC", "ABC")
                    modal.buttonNamed("Speichern").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(create).size)
                    assertTrue(modal.shownErrors().contains("Die BIC ist ungültig."), "shown: ${modal.shownErrors()}")
                    modal.typeInto("BIC", "")
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("createBankAccount", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    assertNull(calls.singleCall(create).rpcParam(0).bic, "an empty BIC is null")
                }
            }
        }
}
