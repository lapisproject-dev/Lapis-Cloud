package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorInput
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.rpc.IAccountingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.4.30 (W4c): the finance forms on the form grammar, driven the way a person drives them (type, blur, choose, click) against a
 * stubbed `window.fetch`. The posting lines are the heart: the balance strip, the numbered labels (with their star), `detach()` /
 * `unregister()` (a removed row must never block the form), the balance check that stands in front of "Direkt buchen" but NOT in
 * front of "Als Entwurf speichern", the scale-2 rule, and the one-shot confirmation. The expected values are derived from the OLD
 * behaviour / the server limits, not from the new implementation.
 */
class FormGrammarPart3DomTest {
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

    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    /** Every RPC answers `null`, except the ones [answers] names. */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private val savedEntryJson =
        jsonOf(
            JournalEntryDto.serializer(),
            JournalEntryDto(
                id = "e1",
                entryDate = LocalDate(2026, 1, 1),
                description = "x",
                voucherReference = null,
                createdBy = "m1",
                createdByDisplayName = "M",
                status = JournalEntryStatus.DRAFT,
                postedAt = null,
                createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                postings = emptyList(),
            ),
        )

    private val dummyEntry = JournalEntryInput(entryDate = LocalDate(2026, 1, 1), description = "x")

    private fun HTMLElement.fillLine(
        nth: Int,
        account: String,
        side: String,
        amount: String,
    ) {
        chooseIn("Konto", account, nth)
        chooseIn("Soll/Haben", side, nth)
        typeInto("Betrag", amount, nth)
        chooseIn("Sphäre", "IDEELLER_BEREICH", nth)
    }

    private fun mountEntryForm(root: io.kvision.panel.Root) =
        renderNewEntryForm(
            root,
            accounts,
            emptyList(),
            listOf(MemberSummaryDto(id = "m1", displayName = "Amara Okafor")),
            emptyList(),
            isPoliticalParty = false,
            vatEnabled = false,
            isKleinunternehmer = false,
            onSaved = {},
        )

    private fun labelsStartingWith(
        root: HTMLElement,
        prefix: String,
    ): List<String> = root.allOf("label").map { it.textContent.orEmpty().trim() }.filter { it.startsWith(prefix) }

    // ── the posting lines ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun directPosting_sendsDebitAndCreditEachInItsSlot_theAmountUnchanged_andAnImpatientDoubleClickBooksOnce(): Promise<Unit> =
        formTest {
            val post = routeOf { rpcService<IAccountingService>().postJournalEntry(dummyEntry) }
            withFetchStub(respond = { r -> StubResponse(text = answering(post to savedEntryJson)(r).text, delayMs = 200) }) { calls ->
                mountedForm("p3-post") { root, element ->
                    mountEntryForm(root)
                    element().typeInto("Beschreibung", "  Spende  ")
                    element().typeInto("Belegnummer", "  B-7  ")
                    element().fillLine(0, "a1", "DEBIT", "  100,50  ")
                    element().fillLine(1, "a2", "CREDIT", "100.5")
                    element().buttonNamed("Direkt buchen").click()
                    val confirm = lastOpenModal().buttonNamed("Endgültig buchen")
                    confirm.click()
                    confirm.click()
                    delay(30)
                    confirm.click()
                    awaitUntil("postJournalEntry", timeoutMs = 1500) { calls.toRoute(post).size == 1 }
                    delay(400)
                    assertEquals(1, calls.toRoute(post).size, "one booking for three clicks on the one-shot confirmation")
                    val input = calls.singleCall(post).rpcParam(0)
                    assertEquals("Spende", input.description as String, "the description is trimmed")
                    assertEquals("B-7", input.voucherReference as String, "the voucher is trimmed and in its own slot")
                    val first = input.postings[0]
                    val second = input.postings[1]
                    assertEquals("DEBIT", first.side as String, "the first line is the debit line, not swapped")
                    assertEquals("a1", first.ledgerAccountId as String)
                    assertEquals("100.5", first.amount.toString(), "the amount is what was typed (comma or dot), never rounded or signed")
                    assertEquals("CREDIT", second.side as String)
                    assertEquals("a2", second.ledgerAccountId as String)
                    assertEquals("100.5", second.amount.toString())
                    assertEquals("IDEELLER_BEREICH", first.sphere as String)
                }
            }
        }

    @Test
    fun directPosting_ofAnUnbalancedEntry_namesTheDifference_opensNoDialog_sendsNothing_butADraftMayBeUnbalanced(): Promise<Unit> =
        formTest {
            val post = routeOf { rpcService<IAccountingService>().postJournalEntry(dummyEntry) }
            val draft = routeOf { rpcService<IAccountingService>().saveDraftEntry(dummyEntry) }
            withFetchStub(respond = answering(draft to savedEntryJson)) { calls ->
                mountedForm("p3-unbalanced") { root, element ->
                    mountEntryForm(root)
                    element().typeInto("Beschreibung", "Ungleich")
                    element().fillLine(0, "a1", "DEBIT", "100,00")
                    element().fillLine(1, "a2", "CREDIT", "90,00")
                    assertEquals(
                        "Soll 100 € · Haben 90 € · Differenz 10 €",
                        element()
                            .first(".lapis-balance-strip")
                            .textContent
                            .orEmpty()
                            .trim(),
                    )

                    element().buttonNamed("Direkt buchen").click()
                    delay(80)
                    assertEquals(
                        0,
                        document.querySelectorAll(".modal.show").length,
                        "no confirmation dialog for an entry the server would reject",
                    )
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertTrue(
                        element()
                            .first(".lapis-form-alert")
                            .textContent
                            .orEmpty()
                            .contains("Differenz 10 €"),
                        "the alert names the difference",
                    )

                    // The very same lines as a DRAFT: the server allows an unbalanced draft, so the client must not forbid it.
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("saveDraftEntry", timeoutMs = 1500) { calls.toRoute(draft).size == 1 }
                    assertEquals(0, calls.toRoute(post).size, "the direct booking never went out")
                }
            }
        }

    @Test
    fun anAmountWithMoreThanTwoDecimals_isRejectedAtTheField_notSilentlyRoundedIntoAnotherBooking(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p3-scale") { root, element ->
                    mountEntryForm(root)
                    element().typeInto("Beschreibung", "Rundung")
                    element().fillLine(0, "a1", "DEBIT", "10,005") // the old client posted this as 10,01
                    element().fillLine(1, "a2", "CREDIT", "10,01")
                    element().buttonNamed("Als Entwurf speichern").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertTrue(
                        element().shownErrors().any { it.contains("Nachkommastellen") },
                        "the field says why: ${element().shownErrors()}",
                    )
                    assertTrue(
                        element()
                            .first(".lapis-balance-strip")
                            .textContent
                            .orEmpty()
                            .contains("—"),
                        "the strip shows a dash, never an old sum",
                    )
                }
            }
        }

    @Test
    fun aRemovedLine_neverBlocksTheFormAgain_andTheRemainingLinesAreRenumbered_starIncluded(): Promise<Unit> =
        formTest {
            val draft = routeOf { rpcService<IAccountingService>().saveDraftEntry(dummyEntry) }
            withFetchStub(respond = answering(draft to savedEntryJson)) { calls ->
                mountedForm("p3-remove") { root, element ->
                    mountEntryForm(root)
                    element().typeInto("Beschreibung", "Zeilen")
                    element().buttonNamed("Buchungszeile hinzufügen").click()
                    val third = labelsStartingWith(element(), "Betrag")
                    assertEquals(3, third.size)
                    assertEquals("Betrag · Zeile 3 *", third[2], "a line added later carries its star, too")
                    element().typeInto("Betrag", "abc", nth = 2) // the third line is INVALID and shows its error
                    assertTrue(element().shownErrors().isNotEmpty())

                    element().allOf("button").filter { it.textContent?.trim() == "Entfernen" }[1].click()
                    // The lines were Zeile 1, 2, 3; the second is gone: the old third is Zeile 2 now, with its text once and its star.
                    assertEquals(listOf("Betrag · Zeile 1 *", "Betrag · Zeile 2 *"), labelsStartingWith(element(), "Betrag"))

                    element().fillLine(0, "a1", "DEBIT", "10,00")
                    element().fillLine(1, "a2", "CREDIT", "10,00")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("saveDraftEntry", timeoutMs = 1500) { calls.toRoute(draft).size == 1 }
                }
            }
        }

    @Test
    fun theBalanceStrip_addsInWholeCents_showsDashesForABadAmount_andFollowsARemovedLine(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3-strip") { root, element ->
                    mountEntryForm(root)
                    element().fillLine(0, "a1", "DEBIT", "0,1")
                    element().fillLine(1, "a2", "DEBIT", "0,2")
                    assertEquals(
                        "Soll 0.3 € · Haben 0 € · Differenz 0.3 €",
                        element()
                            .first(".lapis-balance-strip")
                            .textContent
                            .orEmpty()
                            .trim(),
                        "0.1 + 0.2 is 0.3, not 0.30000000000000004",
                    )
                    element().typeInto("Betrag", "x", nth = 1)
                    assertEquals(
                        "Soll — · Haben — · Differenz —",
                        element()
                            .first(".lapis-balance-strip")
                            .textContent
                            .orEmpty()
                            .trim(),
                        "one bad amount: no sum at all",
                    )
                    element().allOf("button").filter { it.textContent?.trim() == "Entfernen" }[1].click()
                    assertEquals(
                        "Soll 0.1 € · Haben 0 € · Differenz 0.1 €",
                        element()
                            .first(".lapis-balance-strip")
                            .textContent
                            .orEmpty()
                            .trim(),
                        "the removed line no longer counts",
                    )
                }
            }
        }

    @Test
    fun afterASuccessfulSave_theFormIsResetAndCanBeSubmittedAgain_noDeadFieldsPileUp(): Promise<Unit> =
        formTest {
            val draft = routeOf { rpcService<IAccountingService>().saveDraftEntry(dummyEntry) }
            withFetchStub(respond = answering(draft to savedEntryJson)) { calls ->
                mountedForm("p3-reset") { root, element ->
                    mountEntryForm(root)
                    for (round in 1..3) {
                        element().typeInto("Beschreibung", "Runde $round")
                        element().fillLine(0, "a1", "DEBIT", "5,00")
                        element().fillLine(1, "a2", "CREDIT", "5,00")
                        element().buttonNamed("Als Entwurf speichern").click()
                        awaitUntil("save #$round", timeoutMs = 1500) { calls.toRoute(draft).size == round }
                        awaitUntil(
                            "form reset #$round",
                            timeoutMs = 1500,
                        ) { (element().controlOf("Beschreibung") as HTMLInputElement).value.isEmpty() }
                        assertEquals(
                            listOf("Betrag · Zeile 1 *", "Betrag · Zeile 2 *"),
                            labelsStartingWith(element(), "Betrag"),
                            "two fresh lines after every save",
                        )
                    }
                }
            }
        }

    @Test
    fun aMemberDonorWithoutACategory_isStoppedByTheCrossRule_evenThoughThePanelIsHiddenForOtherTypes(): Promise<Unit> =
        formTest {
            val draft = routeOf { rpcService<IAccountingService>().saveDraftEntry(dummyEntry) }
            withFetchStub(respond = answering(draft to savedEntryJson)) { calls ->
                mountedForm("p3-donor") { root, element ->
                    mountEntryForm(root)
                    element().typeInto("Beschreibung", "Spende")
                    element().fillLine(0, "a1", "DEBIT", "5,00")
                    element().fillLine(1, "a2", "CREDIT", "5,00")
                    // Hidden donor selects are optional: no donor type, no obstacle.
                    element().chooseIn("Spendertyp", "MEMBER")
                    element().buttonNamed("Als Entwurf speichern").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "member donor without a category: no RPC")
                    assertTrue(
                        element()
                            .first(".lapis-form-alert")
                            .textContent
                            .orEmpty()
                            .contains("Spenderkategorie"),
                    )
                    assertTrue(
                        element()
                            .first(".lapis-form-alert")
                            .textContent
                            .orEmpty()
                            .contains("Mitglied"),
                        "and without a member",
                    )
                    element().chooseIn("Spenderkategorie", DonorCategory.GERMAN_NATURAL_PERSON.name)
                    element().chooseIn("Mitglied", "m1")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("saveDraftEntry", timeoutMs = 1500) { calls.toRoute(draft).size == 1 }
                    val input = calls.singleCall(draft).rpcParam(0)
                    assertEquals("m1", input.donorMemberId as String)
                    assertEquals("GERMAN_NATURAL_PERSON", input.donorCategory as String)
                }
            }
        }

    // ── the grammar addition: unregister / detach ─────────────────────────────────────────────────────────────

    @Test
    fun unregister_removesAFieldFromValidation_clearsItsError_andIsIdempotent(): Promise<Unit> =
        formTest {
            mountedForm("p3-unregister") { root, element ->
                val form = root.lapisForm()
                val keep = form.textField(label = tr("Feld A"), value = "ok")
                val gone = form.textField(label = tr("Feld B"), required = true)
                form.buttons(primary = Button("Absenden", style = ButtonStyle.PRIMARY))
                assertEquals(false, form.validateAndReport(), "the required, empty field B blocks")
                assertEquals(1, element().shownErrors().size)
                gone.detach()
                gone.detach() // idempotent
                assertEquals(true, form.validateAndReport(), "a detached field no longer blocks")
                assertEquals(0, element().shownErrors().size, "and its error is gone")
                assertEquals(listOf(keep), form.fields)
            }
        }

    // ── the other finance forms: what goes over the wire ───────────────────────────────────────────────────────────

    @Test
    fun accountCreation_sendsEachFieldInItsSlot_theClassCheckedAtTheField_andAnInvalidClassSendsNothing(): Promise<Unit> =
        formTest {
            val create =
                routeOf { rpcService<IAccountingService>().createLedgerAccount(LedgerAccountInput("1", "n", 1, LedgerAccountType.ASSET)) }
            withFetchStub { calls ->
                mountedForm("p3-account") { root, element ->
                    renderAccountCreationForm(root) {}
                    element().typeInto("Kontonummer", "  4711  ")
                    element().typeInto("Name", "  Sonderkonto  ")
                    element().typeInto("Kontenklasse", "12")
                    element().buttonNamed("Konto anlegen").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "class 12 is outside 0-9: no RPC at all")
                    assertTrue(element().shownErrors().isNotEmpty())
                    element().typeInto("Kontenklasse", "4")
                    element().chooseIn("Kontotyp", LedgerAccountType.EQUITY.name)
                    element().buttonNamed("Konto anlegen").click()
                    awaitUntil("createLedgerAccount", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("4711", input.accountNumber as String)
                    assertEquals("Sonderkonto", input.name as String)
                    assertEquals(4, input.accountClass as Int)
                    assertEquals("EQUITY", input.type as String)
                    // `false` is the default and is not encoded: absent or false, never true.
                    assertTrue(input.isCashRegister != true, "the cash-register flag is off for an equity account")
                }
            }
        }

    @Test
    fun costCenterCreation_sendsCodeAndNameEachInItsSlot_trimmed_andABlankDescriptionIsNull(): Promise<Unit> =
        formTest {
            val create = routeOf { rpcService<IAccountingService>().createCostCenter(CostCenterInput("c", "n")) }
            withFetchStub { calls ->
                mountedForm("p3-costcenter") { root, element ->
                    renderCostCenterCreationForm(root) {}
                    element().buttonNamed("Kostenstelle anlegen").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "two required fields empty: no RPC at all")
                    element().typeInto("Code", "  SOMMER-27  ")
                    element().typeInto("Name", "  Sommerfest  ")
                    element().typeInto("Beschreibung", "   ")
                    element().buttonNamed("Kostenstelle anlegen").click()
                    awaitUntil("createCostCenter", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("SOMMER-27", input.code as String)
                    assertEquals("Sommerfest", input.name as String)
                    assertTrue(input.description == null, "a blank optional field is null, never \"\"")
                }
            }
        }

    @Test
    fun donorCreation_needsNameAndCategory_sendsTheAddressFieldsInTheirSlots(): Promise<Unit> =
        formTest {
            val create =
                routeOf {
                    rpcService<IAccountingService>().createExternalDonor(
                        ExternalDonorInput("n", DonorCategory.GERMAN_NATURAL_PERSON),
                    )
                }
            withFetchStub { calls ->
                mountedForm("p3-donor-create") { root, element ->
                    renderDonorCreationForm(root) {}
                    element().typeInto("Name", "  Lena Berg  ")
                    element().buttonNamed("Spender anlegen").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "the category is missing: no RPC at all")
                    element().chooseIn("Spenderkategorie", DonorCategory.GERMAN_NATURAL_PERSON.name)
                    element().typeInto("Straße", "  Hauptstr. 1  ")
                    element().typeInto("PLZ", "38100")
                    element().typeInto("Ort", "Braunschweig")
                    element().buttonNamed("Spender anlegen").click()
                    awaitUntil("createExternalDonor", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("Lena Berg", input.displayName as String)
                    assertEquals("Hauptstr. 1", input.street as String)
                    assertEquals("38100", input.postalCode as String)
                    assertEquals("Braunschweig", input.city as String)
                    assertTrue(input.country == null, "the empty country is null, never \"\"")
                }
            }
        }

    @Test
    fun mandateForm_theIbanEchoIsAReadingAid_alsoForAWrongCheckDigit_andTheBicIsCheckedButNeverRewritten(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3-mandate") { root, element ->
                    renderSepaMandateForm(root, onBehalf = false, defaultDebtorName = "Lena Berg", memberOptions = emptyList()) {}
                    val echo = element().first(".font-monospace")
                    element().typeInto("IBAN", "DE89370400440532013000")
                    assertEquals("DE89 3704 0044 0532 0130 00", echo.textContent.orEmpty().trim(), "a valid IBAN is echoed in groups")
                    element().typeInto("IBAN", "DE89370400440532013001")
                    assertEquals(
                        "DE89 3704 0044 0532 0130 01",
                        echo.textContent.orEmpty().trim(),
                        "the echo is a reading aid: a wrong check digit is still shown grouped, so the typo is visible",
                    )
                    assertTrue(
                        element().shownErrors().contains("Die IBAN ist ungültig."),
                        "and the validity is reported separately, at the field: ${element().shownErrors()}",
                    )
                    element().typeInto("IBAN", "")
                    assertEquals("", echo.textContent.orEmpty().trim(), "an empty entry has no echo")
                    assertEquals(
                        "off",
                        element().controlOf("IBAN").getAttribute("autocomplete"),
                        "no browser suggestion of an earlier IBAN",
                    )
                    element().typeInto("BIC", "cobadeff")
                    assertEquals(
                        "cobadeff",
                        (element().controlOf("BIC") as HTMLInputElement).value,
                        "the BIC is uppercased for the check only, never written back",
                    )
                }
            }
        }

    @Test
    fun settlementDialog_theBackButtonStandsBeforeTheConfirmingButton_andTheFieldsCarryTheErrors(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p3-settle") { _, _ ->
                    val item =
                        OpenItemDto(
                            id = "oi1",
                            direction = OpenItemDirection.RECEIVABLE,
                            counterpartyName = "Muster GmbH",
                            counterpartyKey = "muster",
                            itemDate = LocalDate(2026, 1, 1),
                            dueDate = LocalDate(2026, 2, 1),
                            amount = 100.0.toDecimal(),
                            openAmount = 100.0.toDecimal(),
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
                    openItemSettlementDialog(
                        item = item,
                        accounts = { accounts },
                        paymentMapping = { PaymentAccountMapping(defaultBankAccountId = "a1") },
                        knownSettlementIds = null,
                        onDone = {},
                    )
                    val modal = lastOpenModal()
                    modal.typeInto("Betrag in EUR", "150,00")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    assertTrue(
                        modal.shownErrors().any {
                            it.contains("offenen Betrag")
                        },
                        "over the open amount: the error stands at the field: ${modal.shownErrors()}",
                    )
                    assertEquals(0, calls.rpcCount)
                    modal.typeInto("Betrag in EUR", "40,00")
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(80)
                    val buttons = modal.allOf("button").map { it.textContent.orEmpty().trim() }
                    assertTrue(
                        buttons.indexOf("Zurück") in 0 until buttons.indexOf("Jetzt ausgleichen"),
                        "Zurück stands BEFORE the confirming button: $buttons",
                    )
                }
            }
        }
}
