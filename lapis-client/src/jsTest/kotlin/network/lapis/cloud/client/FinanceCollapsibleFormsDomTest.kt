package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ExternalDonorDto
import network.lapis.cloud.shared.domain.ExternalDonorInput
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAccountingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.45 -- rule R36B for the finance group, part 1: cost centers, donors, ledger account and journal entry. After the load the create
 * form is collapsed, its button sits in the title row, Escape on a changed form asks before it discards, a save folds the form back and
 * reloads the list, a failed save keeps the form and its input, and a role without write rights gets no button at all.
 */
class FinanceCollapsibleFormsDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.actionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hasForm(formId: String): Boolean = querySelector("[id='$formId'] .lapis-form") != null

    private fun HTMLElement.host(formId: String): HTMLElement = querySelector("[id='$formId']") as HTMLElement

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun awaitDiscardDialog(): HTMLElement {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        return lastOpenModal()
    }

    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"

    // ---- Cost centers ---------------------------------------------------------------------------------------------------

    private fun costCenter(
        id: String,
        name: String,
    ) = CostCenterDto(id = id, code = "K-$id", name = name, description = null, active = true)

    @Test
    fun costCenters_collapsed_escapeAsksOnlyAfterTyping_andASaveFoldsBackAndReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val list = routeOf { rpcService<IAccountingService>().listCostCenters(true) }
            val create = routeOf { rpcService<IAccountingService>().createCostCenter(CostCenterInput("c", "n", null, true)) }
            val rows = mutableListOf(costCenter("1", "Altbestand"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(CostCenterDto.serializer()), rows))
                        request.rpcRoute == create -> {
                            val added = costCenter("2", "Sommerfest")
                            rows += added
                            request.answerWith(jsonOf(CostCenterDto.serializer(), added))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-finance-cost-centers") { root, element ->
                    renderCostCentersScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altbestand") }
                    val screen = element()
                    assertEquals(listOf("Neue Kostenstelle"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neue Kostenstelle anlegen"), "the old always-visible section title is gone")
                    assertFalse(screen.hasForm("lapis-create-cost-center"), "collapsed after the load")

                    val host = openCreateForm(screen, "lapis-create-cost-center")
                    // untouched: Escape closes without a question
                    escape(host)
                    awaitUntil("closed without a question") { !screen.hasForm("lapis-create-cost-center") }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openCreateForm(screen, "lapis-create-cost-center")
                    reopened.typeInto("Code", "SOMMER")
                    escape(reopened)
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertEquals("SOMMER", (reopened.controlOf("Code") as HTMLInputElement).value, "keep editing keeps the input")

                    reopened.typeInto("Name", "Sommerfest")
                    screen.buttonNamed("Kostenstelle anlegen").click()
                    awaitUntil("createCostCenter was called") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.hasForm("lapis-create-cost-center") }
                    awaitUntil("the new row is listed") { screen.shows("Sommerfest") }
                    assertEquals(2, calls.toRoute(list).size, "the list was reloaded once after the save")
                    val button = createFormButton(screen, "lapis-create-cost-center")
                    assertEquals("false", button.getAttribute("aria-expanded"))
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                }
            }
        }

    @Test
    fun costCenters_aConflictKeepsTheFormOpenWithItsInput(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val list = routeOf { rpcService<IAccountingService>().listCostCenters(true) }
            val create = routeOf { rpcService<IAccountingService>().createCostCenter(CostCenterInput("c", "n", null, true)) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith("[]")
                        request.rpcRoute == create -> serviceExceptionResult(request.json.id as Int, conflict)
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-finance-cost-centers-conflict") { root, element ->
                    renderCostCentersScreen(root)
                    awaitUntil("the empty state names the button") {
                        element().shows("Noch keine Kostenstellen. Mit \"Neue Kostenstelle\" legen Sie eine an.")
                    }
                    val screen = element()
                    val host = openCreateForm(screen, "lapis-create-cost-center")
                    host.typeInto("Code", "DOPPELT")
                    host.typeInto("Name", "Doppelt")
                    screen.buttonNamed("Kostenstelle anlegen").click()
                    awaitUntil("the write was attempted") { calls.toRoute(create).size == 1 }
                    awaitUntil("the failed save is over") { !screen.buttonNamed("Kostenstelle anlegen").hasAttribute("disabled") }
                    assertTrue(screen.hasForm("lapis-create-cost-center"), "a failed save never folds the form back")
                    assertEquals("DOPPELT", (host.controlOf("Code") as HTMLInputElement).value)
                }
            }
        }

    @Test
    fun costCenters_aBoardMemberGetsNoButton_andAHostileNameStaysText(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val list = routeOf { rpcService<IAccountingService>().listCostCenters(true) }
            val hostile = costCenter("9", "${KV_I18N_MARKER}Boese")
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(CostCenterDto.serializer()), listOf(hostile)))
                    } else if (request.isRpc) {
                        request.answerWith("[]")
                    } else {
                        StubResponse()
                    }
                },
            ) { _ ->
                mountedForm("r36b-finance-cost-centers-board") { root, element ->
                    renderCostCentersScreen(root)
                    awaitUntil("the list is shown") { element().shows("Boese") }
                    assertTrue(element().actionButtons().isEmpty())
                    assertTrue(element().allOf(".lapis-page-action").isEmpty(), "no empty action area")
                    assertFalse(element().shows(KV_I18N_MARKER), "an injected catalog marker never reaches the DOM")
                }
            }
        }

    // ---- Donors ---------------------------------------------------------------------------------------------------------

    private fun donor(
        id: String,
        name: String,
    ) = ExternalDonorDto(
        id = id,
        displayName = name,
        donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
        street = null,
        postalCode = null,
        city = null,
        country = null,
        active = true,
    )

    @Test
    fun donors_collapsed_andASaveFoldsBackAndReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val list = routeOf { rpcService<IAccountingService>().listExternalDonors(true) }
            val create =
                routeOf {
                    rpcService<IAccountingService>().createExternalDonor(
                        ExternalDonorInput("n", DonorCategory.GERMAN_NATURAL_PERSON, null, null, null, null, true),
                    )
                }
            val rows = mutableListOf<ExternalDonorDto>()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(ExternalDonorDto.serializer()), rows))
                        request.rpcRoute == create -> {
                            val added = donor("d1", "Erika Spenderin")
                            rows += added
                            request.answerWith(jsonOf(ExternalDonorDto.serializer(), added))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-finance-donors") { root, element ->
                    renderDonorsScreen(root)
                    awaitUntil("the empty state names the button") {
                        element().shows("Mit \"Neuer Spender\" legen Sie einen an.")
                    }
                    val screen = element()
                    assertEquals(listOf("Neuer Spender"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hasForm("lapis-create-donor"))
                    val host = openCreateForm(screen, "lapis-create-donor")
                    host.typeInto("Name", "Erika Spenderin")
                    host.chooseIn("Spenderkategorie", DonorCategory.GERMAN_NATURAL_PERSON.name)
                    screen.buttonNamed("Spender anlegen").click()
                    awaitUntil("createExternalDonor was called once") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.hasForm("lapis-create-donor") }
                    awaitUntil("the new donor is listed") { screen.shows("Erika Spenderin") }
                }
            }
        }

    // ---- Ledger: account + journal entry --------------------------------------------------------------------------------

    private fun account(
        id: String,
        number: String,
        type: LedgerAccountType,
    ) = LedgerAccountDto(
        id = id,
        accountNumber = number,
        name = "Konto $number",
        accountClass = number.take(1).toInt(),
        type = type,
        active = true,
    )

    private suspend fun withLedgerScreen(
        id: String,
        role: AccountRole,
        accounts: List<LedgerAccountDto>,
        block: suspend (screen: HTMLElement, calls: List<RecordedRequest>) -> Unit,
    ) {
        AppState.setSession(session(role))
        val listAccounts = routeOf { rpcService<IAccountingService>().listLedgerAccounts(true) }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listAccounts ->
                        request.answerWith(jsonOf(ListSerializer(LedgerAccountDto.serializer()), accounts))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls ->
            mountedForm(id) { root, element ->
                renderLedgerScreen(root)
                awaitUntil("the account list is shown") { accounts.isEmpty() || element().shows(accounts.first().name) }
                block(element(), calls)
            }
        }
    }

    @Test
    fun ledger_bothButtonsSitInTheTitleRowInAFixedOrder_andBothFormsAreCollapsed(): Promise<Unit> =
        formTest {
            val accounts = listOf(account("a1", "1000", LedgerAccountType.ASSET), account("a2", "4000", LedgerAccountType.INCOME))
            withLedgerScreen("r36b-finance-ledger", AccountRole.TREASURER, accounts) { screen, _ ->
                awaitUntil("both buttons are there") { screen.actionButtons().size == 2 }
                assertEquals(listOf("Neue Buchung", "Neues Konto"), screen.actionButtons().map { it.textContent?.trim() })
                assertFalse(screen.hasForm("lapis-create-journal-entry"))
                assertFalse(screen.hasForm("lapis-create-ledger-account"))
                assertFalse(screen.shows("Neues Konto anlegen"), "the old always-visible section title is gone")
            }
        }

    @Test
    fun ledger_addingAPostingLineAndTypingItsAmount_makesEscapeAsk(): Promise<Unit> =
        formTest {
            val accounts = listOf(account("a1", "1000", LedgerAccountType.ASSET), account("a2", "4000", LedgerAccountType.INCOME))
            withLedgerScreen("r36b-finance-ledger-snapshot", AccountRole.TREASURER, accounts) { screen, _ ->
                awaitUntil("the journal button is there") { screen.actionButtons().size == 2 }
                val host = openCreateForm(screen, "lapis-create-journal-entry")
                // untouched: no question
                escape(host)
                awaitUntil("closed without a question") { !screen.hasForm("lapis-create-journal-entry") }
                assertTrue(document.querySelector(".modal.show") == null)

                val reopened = openCreateForm(screen, "lapis-create-journal-entry")
                val addRow = reopened.buttonNamed("Buchungszeile hinzufügen")
                assertEquals("true", addRow.querySelector("i.fa-plus")?.getAttribute("aria-hidden"), "the add verb carries its plus icon")
                addRow.click()
                awaitUntil("the third posting line exists") { reopened.allOf("label").any { it.textContent.orEmpty().contains("Zeile 3") } }
                reopened.typeInto("Betrag", "12,50", nth = 2)
                escape(reopened)
                awaitDiscardDialog().buttonNamed("Verwerfen").click()
                awaitUntil("closed after discarding") { !screen.hasForm("lapis-create-journal-entry") }
            }
        }

    @Test
    fun ledger_withoutActiveAccounts_thereIsNoBookingButton_butTheHintAndTheAccountButton(): Promise<Unit> =
        formTest {
            withLedgerScreen("r36b-finance-ledger-empty", AccountRole.TREASURER, emptyList()) { screen, _ ->
                awaitUntil("the hint is shown") {
                    screen.shows("Noch keine aktiven Konten -- zuerst mit \"Neues Konto\" mindestens zwei Konten anlegen.")
                }
                assertEquals(listOf("Neues Konto"), screen.actionButtons().map { it.textContent?.trim() })
            }
        }

    @Test
    fun ledger_aBoardMemberGetsNoButtons(): Promise<Unit> =
        formTest {
            withLedgerScreen(
                "r36b-finance-ledger-board",
                AccountRole.BOARD,
                listOf(account("a1", "1000", LedgerAccountType.ASSET)),
            ) { screen, _ ->
                assertTrue(screen.actionButtons().isEmpty())
                assertTrue(screen.allOf(".lapis-page-action").isEmpty(), "no empty action area")
            }
        }

    // ---- Ledger: journal draft duplicate wiring and save fold-back --------------------------------------------------------

    private fun draftEntry() =
        JournalEntryDto(
            id = "j1",
            entryDate = LocalDate(2026, 3, 14),
            description = "Altentwurf Spende",
            voucherReference = null,
            createdBy = "member-1",
            createdByDisplayName = "Dana Keller",
            status = JournalEntryStatus.DRAFT,
            postedAt = null,
            createdAt = LocalDateTime(2026, 3, 14, 10, 0),
            postings =
                listOf(
                    PostingDto(
                        id = "p1",
                        ledgerAccountId = "a1",
                        ledgerAccountNumber = "1000",
                        ledgerAccountName = "Konto 1000",
                        side = PostingSide.DEBIT,
                        amount = 12.5.toDecimal(),
                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        vatAmount = 0.0.toDecimal(),
                    ),
                    PostingDto(
                        id = "p2",
                        ledgerAccountId = "a2",
                        ledgerAccountNumber = "4000",
                        ledgerAccountName = "Konto 4000",
                        side = PostingSide.CREDIT,
                        amount = 12.5.toDecimal(),
                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        vatAmount = 0.0.toDecimal(),
                    ),
                ),
        )

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

    /** A ledger screen with one draft in the journal; [block] gets the screen, the recorded calls and the saveDraftEntry / listJournal routes. */
    private suspend fun withJournalDraftScreen(
        id: String,
        block: suspend (screen: HTMLElement, calls: List<RecordedRequest>, saveDraft: String, listJournal: String) -> Unit,
    ) {
        AppState.setSession(session(AccountRole.TREASURER))
        val accounts = listOf(account("a1", "1000", LedgerAccountType.ASSET), account("a2", "4000", LedgerAccountType.INCOME))
        val listAccounts = routeOf { rpcService<IAccountingService>().listLedgerAccounts(true) }
        val listJournal = routeOf { rpcService<IAccountingService>().listJournal(null, null, null, null) }
        val getEntry = routeOf { rpcService<IAccountingService>().getJournalEntry("j1") }
        val saveDraft = routeOf { rpcService<IAccountingService>().saveDraftEntry(JournalEntryInput(LocalDate(2026, 1, 1), "x")) }
        val draft = draftEntry()
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listAccounts ->
                        request.answerWith(jsonOf(ListSerializer(LedgerAccountDto.serializer()), accounts))
                    request.rpcRoute == listJournal ->
                        request.answerWith(jsonOf(ListSerializer(JournalEntryDto.serializer()), listOf(draft)))
                    request.rpcRoute == getEntry -> request.answerWith(jsonOf(JournalEntryDto.serializer(), draft))
                    request.rpcRoute == saveDraft -> request.answerWith(jsonOf(JournalEntryDto.serializer(), draft))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls ->
            mountedForm(id) { root, element ->
                renderLedgerScreen(root)
                awaitUntil("the draft is listed") { element().shows("Altentwurf Spende") }
                awaitUntil("both buttons are there") { element().actionButtons().size == 2 }
                block(element(), calls, saveDraft, listJournal)
            }
        }
    }

    private suspend fun duplicateTheDraft(screen: HTMLElement) {
        // The account list has "Details anzeigen" buttons too; the journal row is the last one on the page.
        val details = screen.allOf("button").last { it.getAttribute("aria-label") == "Details anzeigen" }
        details.click()
        awaitUntil("the draft detail is shown") { screen.allOf("button").any { it.textContent?.trim() == "Als neuen Entwurf duplizieren" } }
        screen.buttonNamed("Als neuen Entwurf duplizieren").click()
    }

    @Test
    fun ledger_duplicatingADraftOpensThePrefilledForm_andAnUneditedDuplicateClosesWithoutAQuestion(): Promise<Unit> =
        formTest {
            withJournalDraftScreen("r36b-finance-ledger-duplicate") { screen, _, _, _ ->
                assertFalse(screen.hasForm("lapis-create-journal-entry"), "collapsed before the duplicate")
                duplicateTheDraft(screen)
                awaitUntil("the form opens prefilled") { screen.hasForm("lapis-create-journal-entry") }
                val host = screen.host("lapis-create-journal-entry")
                assertEquals("Altentwurf Spende", (host.controlOf("Beschreibung") as HTMLInputElement).value)
                assertTrue((host.controlOf("Betrag", 0) as HTMLInputElement).value.contains("12"), "the first amount is prefilled")
                assertTrue((host.controlOf("Betrag", 1) as HTMLInputElement).value.contains("12"), "the second amount is prefilled")
                // The prefill runs before the controller takes its baseline: an unedited duplicate is not dirty.
                escape(host)
                awaitUntil("closed without a question") { !screen.hasForm("lapis-create-journal-entry") }
                assertTrue(document.querySelector(".modal.show") == null, "no discard dialog for an unedited duplicate")
            }
        }

    @Test
    fun ledger_duplicatingAgainOverAnEditedForm_asksBeforeOverwriting(): Promise<Unit> =
        formTest {
            withJournalDraftScreen("r36b-finance-ledger-duplicate-dirty") { screen, _, _, _ ->
                duplicateTheDraft(screen)
                awaitUntil("the form opens prefilled") { screen.hasForm("lapis-create-journal-entry") }
                val host = screen.host("lapis-create-journal-entry")
                host.typeInto("Beschreibung", "Handgetippt")
                screen.buttonNamed("Als neuen Entwurf duplizieren").click()
                val dialog = awaitDiscardDialog()
                assertTrue(dialog.shows("Eingaben verwerfen?"), "the dialog asks before the typed input is overwritten")
                dialog.buttonNamed("Weiter bearbeiten").click()
                awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                assertEquals("Handgetippt", (host.controlOf("Beschreibung") as HTMLInputElement).value, "keep editing keeps the input")
            }
        }

    @Test
    fun ledger_savingADraft_foldsTheFormBackAndReloadsTheJournal(): Promise<Unit> =
        formTest {
            withJournalDraftScreen("r36b-finance-ledger-save-draft") { screen, calls, saveDraft, listJournal ->
                val host = openCreateForm(screen, "lapis-create-journal-entry")
                host.typeInto("Beschreibung", "Neuer Entwurf")
                host.fillLine(0, "a1", "DEBIT", "10,00")
                host.fillLine(1, "a2", "CREDIT", "10,00")
                host.buttonNamed("Als Entwurf speichern").click()
                awaitUntil("saveDraftEntry was called once") { calls.toRoute(saveDraft).size == 1 }
                awaitUntil("the form folded back") { !screen.hasForm("lapis-create-journal-entry") }
                awaitUntil("the journal was reloaded after the save") { calls.toRoute(listJournal).size >= 2 }
                val button = createFormButton(screen, "lapis-create-journal-entry")
                assertEquals("false", button.getAttribute("aria-expanded"))
            }
        }
}
