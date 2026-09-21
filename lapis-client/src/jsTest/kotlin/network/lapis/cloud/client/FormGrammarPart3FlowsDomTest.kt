package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemInput
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import network.lapis.cloud.shared.domain.SepaDebitBatchInput
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IOpenItemService
import network.lapis.cloud.shared.rpc.ISepaService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.4.30 (W4c), second half: defaults that must NOT change, the request bodies of the open-item and SEPA forms field by field (values of
 * same-typed neighbours are DISTINCT), the double-click protection of the money paths, the upload slots (siblings, never children), and
 * that a counterparty name is never interpreted as markup. Expected values come from the old behaviour and the server limits.
 */
class FormGrammarPart3FlowsDomTest {
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

    private fun todayIso(): String = todayLocalDate().toString()

    private fun openItem(counterparty: String = "Muster GmbH") =
        OpenItemDto(
            id = "oi1",
            direction = OpenItemDirection.RECEIVABLE,
            counterpartyName = counterparty,
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

    // ── defaults that stay exactly as they were ─────────────────────────────────────────────────────────────────────

    @Test
    fun newEntryForm_keepsItsDefaults_todayAsDate_debitThenCredit_noPreselectedAccountSphereOrDonor(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("p3f-defaults") { root, element ->
                    renderNewEntryForm(root, accounts, emptyList(), emptyList(), emptyList(), false, false, false) {}
                    assertEquals(
                        todayIso(),
                        (element().controlOf("Buchungsdatum") as HTMLInputElement).value,
                        "the date is prefilled with today",
                    )
                    assertEquals("", (element().controlOf("Beschreibung") as HTMLInputElement).value)
                    assertEquals("DEBIT", (element().controlOf("Soll/Haben", 0) as HTMLSelectElement).value, "line 1 starts as debit")
                    assertEquals("CREDIT", (element().controlOf("Soll/Haben", 1) as HTMLSelectElement).value, "line 2 starts as credit")
                    assertEquals("", (element().controlOf("Konto", 0) as HTMLSelectElement).value, "no account is preselected")
                    assertEquals(
                        "",
                        (element().controlOf("Sphäre", 0) as HTMLSelectElement).value,
                        "no sphere is preselected (a legally loaded choice)",
                    )
                    assertEquals("", (element().controlOf("Spendertyp") as HTMLSelectElement).value, "no donor type is preselected")
                }
            }
        }

    @Test
    fun openItemCreation_hasNoPreselectedDirection_and_sendsEveryFieldInItsOwnSlot(): Promise<Unit> =
        formTest {
            val create =
                routeOf {
                    rpcService<IOpenItemService>().createOpenItem(
                        OpenItemInput(
                            OpenItemDirection.PAYABLE,
                            "n",
                            null,
                            null,
                            LocalDate(2026, 1, 1),
                            LocalDate(2026, 1, 2),
                            1.0.toDecimal(),
                            "a",
                            GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            null,
                        ),
                    )
                }
            withFetchStub { calls ->
                mountedForm("p3f-openitem") { root, element ->
                    renderOpenItemCreateForm(
                        root,
                        AccountRole.TREASURER,
                        { accounts },
                        defaultDirection = null,
                        onCreated = {},
                        onCancel = {},
                    )
                    assertEquals(
                        "",
                        (element().controlOf("Richtung") as HTMLSelectElement).value,
                        "the direction is a deliberate choice: no preselection",
                    )
                    assertEquals(todayIso(), (element().controlOf("Belegdatum") as HTMLInputElement).value)
                    assertEquals(
                        todayLocalDate().plus(DatePeriod(days = 14)).toString(),
                        (element().controlOf("Fällig am") as HTMLInputElement).value,
                    )

                    // The due date before the item date: the error stands at the due-date field, and nothing is sent.
                    element().chooseIn("Richtung", "RECEIVABLE")
                    element().typeInto("Gegenpartei", "Muster")
                    element().typeInto("Belegdatum", "2026-03-10")
                    element().typeInto("Fällig am", "2026-03-01")
                    element().typeInto("Betrag in EUR", "10,00")
                    element().chooseIn("Gegenkonto", "a2")
                    element().buttonNamed("Posten anlegen").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "due before item date: no RPC at all")
                    assertTrue(
                        element().shownErrors().any { it.contains("Fälligkeitsdatum") },
                        "the field says why: ${element().shownErrors()}",
                    )

                    element().typeInto("Fällig am", "2026-04-15")
                    element().typeInto("Betrag in EUR", "1234,56")
                    element().typeInto("Belegnummer", "  R-1  ")
                    element().buttonNamed("Posten anlegen").click()
                    awaitUntil("createOpenItem", timeoutMs = 1500) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("RECEIVABLE", input.direction as String)
                    assertEquals("Muster", input.counterpartyName as String)
                    assertEquals("2026-03-10", input.itemDate as String, "the item date is not swapped with the due date")
                    assertEquals("2026-04-15", input.dueDate as String)
                    assertEquals("1234.56", input.amount.toString())
                    assertEquals("a2", input.contraAccountId as String)
                    assertEquals("R-1", input.reference as String)
                    assertTrue(input.note == null, "the empty note is null, never \"\"")
                }
            }
        }

    @Test
    fun newBatch_theCollectionDateMustLieInTheFuture_atTheField_andThePreviewBodyKeepsBothDatesApart(): Promise<Unit> =
        formTest {
            val preview =
                routeOf { rpcService<ISepaService>().previewDebitBatch(SepaDebitBatchInput(LocalDate(2026, 1, 1), LocalDate(2026, 1, 1))) }
            withFetchStub { calls ->
                mountedForm("p3f-batch") { root, element ->
                    renderNewBatchSection(root) {}
                    element().typeInto("Einzugsdatum", todayIso())
                    element().buttonNamed("Vorschau berechnen").click()
                    delay(80)
                    assertTrue(calls.toRoute(preview).isEmpty(), "a collection date of today: no preview")
                    assertTrue(element().shownErrors().any { it.contains("Zukunft") }, "the field says why: ${element().shownErrors()}")

                    element().typeInto("Einzugsdatum", "2099-01-15")
                    element().typeInto("Fällig bis", "2098-12-31")
                    element().buttonNamed("Vorschau berechnen").click()
                    awaitUntil("previewDebitBatch", timeoutMs = 1500) { calls.toRoute(preview).size == 1 }
                    val input = calls.singleCall(preview).rpcParam(0)
                    assertEquals("2099-01-15", input.requestedCollectionDate as String)
                    assertEquals("2098-12-31", input.dueOnOrBefore as String, "the two dates are not swapped")
                    assertTrue(input.membershipTierId == null, "no tier chosen: null")
                }
            }
        }

    // ── the upload: hint and error slot are SIBLINGS of the control ───────────────────────────────────────────

    @Test
    fun statementUpload_withoutAFile_showsTheErrorAtTheUpload_asASibling_andSendsNothing(): Promise<Unit> =
        formTest {
            withFetchStub { calls ->
                mountedForm("p3f-upload") { root, element ->
                    renderUploadPanel(root, onUploadStarted = {}) {}
                    element().buttonNamed("Hochladen").click()
                    delay(80)
                    assertEquals(0, calls.size, "no request at all")
                    assertTrue(element().shownErrors().contains("Bitte eine Datei auswählen."), "shown: ${element().shownErrors()}")
                    val upload = element().first("input[type=file]")
                    assertTrue(upload.querySelector(".invalid-feedback") == null, "the error slot is not inside the control")
                    assertEquals(1, element().allOf(".invalid-feedback").size, "but it exists, next to it")
                }
            }
        }

    // ── the double click on the money paths ────────────────────────────────────────────────────────────────────────

    @Test
    fun aDoubleClick_onDraftSave_accountCreation_andCostCenterCreation_sendsOneRequestEach(): Promise<Unit> =
        formTest {
            val draft = routeOf { rpcService<IAccountingService>().saveDraftEntry(JournalEntryInput(LocalDate(2026, 1, 1), "x")) }
            val account =
                routeOf { rpcService<IAccountingService>().createLedgerAccount(LedgerAccountInput("1", "n", 1, LedgerAccountType.ASSET)) }
            val costCenter = routeOf { rpcService<IAccountingService>().createCostCenter(CostCenterInput("c", "n")) }
            withFetchStub(respond = { r ->
                if (r.isRpc) StubResponse(text = r.answerWith("null").text, delayMs = 250) else StubResponse()
            }) { calls ->
                mountedForm("p3f-double-draft") { root, element ->
                    renderNewEntryForm(root, accounts, emptyList(), emptyList(), emptyList(), false, false, false) {}
                    element().typeInto("Beschreibung", "Doppelt")
                    for (line in 0..1) {
                        element().chooseIn("Konto", if (line == 0) "a1" else "a2", line)
                        element().typeInto("Betrag", "5,00", line)
                        element().chooseIn("Sphäre", "IDEELLER_BEREICH", line)
                    }
                    val save = element().buttonNamed("Als Entwurf speichern")
                    save.click()
                    save.click()
                    delay(40)
                    save.click()
                    delay(600)
                    assertEquals(1, calls.toRoute(draft).size, "one draft for three clicks")
                }
                mountedForm("p3f-double-account") { root, element ->
                    renderAccountCreationForm(root) {}
                    element().typeInto("Kontonummer", "4711")
                    element().typeInto("Name", "Sonder")
                    element().typeInto("Kontenklasse", "4")
                    val create = element().buttonNamed("Konto anlegen")
                    create.click()
                    create.click()
                    delay(600)
                    assertEquals(1, calls.toRoute(account).size, "one account for two clicks")
                }
                mountedForm("p3f-double-costcenter") { root, element ->
                    renderCostCenterCreationForm(root) {}
                    element().typeInto("Code", "K-1")
                    element().typeInto("Name", "Kostenstelle Eins")
                    val cc = element().buttonNamed("Kostenstelle anlegen")
                    cc.click()
                    cc.click()
                    delay(600)
                    assertEquals(1, calls.toRoute(costCenter).size, "one cost center for two clicks")
                }
            }
        }

    // ── a name is a name, never markup ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aCounterpartyNameWithMarkup_isShownAsText_inTheSettlementDialogAndItsConfirmation(): Promise<Unit> =
        formTest {
            val hostile = "<img src=x onerror=\"window.__lapisXss=1\">Muster"
            window.asDynamic().__lapisXss = null
            withFetchStub { _ ->
                mountedForm("p3f-xss") { _, _ ->
                    openItemSettlementDialog(
                        item = openItem(hostile),
                        accounts = { accounts },
                        paymentMapping = { PaymentAccountMapping(defaultBankAccountId = "a1") },
                        knownSettlementIds = null,
                        onDone = {},
                    )
                    val modal = lastOpenModal()
                    modal.buttonNamed("Ausgleichen …").click()
                    delay(150)
                    assertEquals(0, modal.allOf("img").size, "no element was created from the name")
                    assertTrue(modal.textContent.orEmpty().contains("<img src=x"), "the name is visible as text")
                    assertTrue(window.asDynamic().__lapisXss == null, "no script ran")
                }
            }
        }
}
