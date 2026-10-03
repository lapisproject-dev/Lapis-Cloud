package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportPageDto
import network.lapis.cloud.shared.domain.BankStatementImportResultDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IBankStatementService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.45 -- rule R36B for the finance group, part 3: open items, bank statement upload and the bank accounts button. The open-items
 * "Abbrechen" used to throw the typed input away silently; it asks now. The upload form is collapsed behind a button with the upload
 * verb icon, and picking a file counts as a change.
 */
class FinanceCollapsibleFormsImportDomTest {
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

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun awaitDiscardDialog(): HTMLElement {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        return lastOpenModal()
    }

    // ---- Open items ------------------------------------------------------------------------------------------------------

    @Test
    fun openItems_theButtonIsInTheTitleRow_andCancelAfterTypingAsksInsteadOfLosingTheInput(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            withFetchStub { _ ->
                mountedForm("r36b-finance-open-items") { root, element ->
                    renderOpenItemsScreen(root)
                    val screen = element()
                    awaitUntil("the header button is there") { screen.actionButtons().isNotEmpty() }
                    assertEquals(listOf("Neuer offener Posten"), screen.actionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hasForm("lapis-create-open-item"), "collapsed after the load")
                    assertTrue(
                        screen.allOf("button").none { it.textContent?.trim() == "Posten anlegen" },
                        "the old action-row button is gone",
                    )
                    assertTrue(screen.allOf("button").any { it.textContent?.trim() == "Verrechnen …" }, "netting stays in the action row")

                    val host = openCreateForm(screen, "lapis-create-open-item")
                    host.typeInto("Gegenpartei", "Lieferant GmbH")
                    host.buttonNamed("Abbrechen").click()
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertEquals(
                        "Lieferant GmbH",
                        (host.controlOf("Gegenpartei") as HTMLInputElement).value,
                        "keep editing keeps the input",
                    )

                    host.buttonNamed("Abbrechen").click()
                    awaitDiscardDialog().buttonNamed("Verwerfen").click()
                    awaitUntil("closed after discarding") { !screen.hasForm("lapis-create-open-item") }
                }
            }
        }

    @Test
    fun openItems_aBoardMemberGetsNoButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub { _ ->
                mountedForm("r36b-finance-open-items-board") { root, element ->
                    renderOpenItemsScreen(root)
                    val screen = element()
                    awaitUntil("the screen is built") { screen.shows("Offene Posten") }
                    assertTrue(screen.actionButtons().isEmpty())
                    assertTrue(screen.allOf(".lapis-page-action").isEmpty(), "no empty action area")
                }
            }
        }

    // ---- Bank statement import -------------------------------------------------------------------------------------------

    private fun emptyImportsJson() =
        jsonOf(
            BankStatementImportPageDto.serializer(),
            BankStatementImportPageDto(rows = emptyList(), totalCount = 0, limit = 20, offset = 0),
        )

    @Test
    fun bankImport_theUploadFormIsCollapsedBehindAButtonWithTheUploadIcon_andPickingAFileCountsAsAChange(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val listImports = routeOf { rpcService<IBankStatementService>().listImports() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listImports -> request.answerWith(emptyImportsJson())
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-finance-bank-import") { root, element ->
                    renderBankStatementImportScreen(root, null)
                    val screen = element()
                    awaitUntil("the empty state names the button") {
                        screen.shows("Noch keine Kontoauszüge importiert. Mit \"Kontoauszug hochladen\" laden Sie den ersten hoch.")
                    }
                    assertEquals(listOf("Kontoauszug hochladen"), screen.actionButtons().map { it.textContent?.trim() })
                    assertNotNull(screen.actionButtons().single().querySelector(".fa-upload"), "the upload verb icon, not the plus")
                    assertFalse(screen.hasForm("lapis-create-bank-import"), "collapsed: no upload box on the page")
                    assertTrue(screen.allOf(".border.rounded.p-3").isEmpty(), "no empty frame while collapsed")
                    assertFalse(screen.shows("Auszug hochladen"), "the old toggle is gone")

                    val host = openCreateForm(screen, "lapis-create-bank-import")
                    escape(host)
                    awaitUntil("an untouched upload form closes without a question") { !screen.hasForm("lapis-create-bank-import") }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openCreateForm(screen, "lapis-create-bank-import")
                    val input = assertNotNull(reopened.querySelector("input[type=file]") as? HTMLInputElement, "no file input")
                    val transfer = js("new DataTransfer()")
                    transfer.items.add(js("new File(['a;b'], 'auszug.csv', { type: 'text/csv' })"))
                    input.asDynamic().files = transfer.files
                    input.dispatchEvent(Event("change"))
                    delay(200) // the upload control registers its selection asynchronously
                    escape(reopened)
                    awaitDiscardDialog()
                    lastOpenModal().buttonNamed("Verwerfen").click()
                    awaitUntil("closed after discarding") { !screen.hasForm("lapis-create-bank-import") }
                }
            }
        }

    @Test
    fun bankImport_aSuccessfulUploadFoldsTheFormBackAndReloadsTheImportList(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val listImports = routeOf { rpcService<IBankStatementService>().listImports() }
            val result =
                Json.encodeToString(
                    BankStatementImportResultDto.serializer(),
                    BankStatementImportResultDto(
                        importId = "11111111-1111-1111-1111-111111111111",
                        format = BankStatementFormat.CSV,
                        dialect = BankCsvDialect.SPARKASSE_CAMT,
                        accountIbanMasked = "DE...4711",
                        statementFrom = null,
                        statementTo = null,
                        balanceChecked = true,
                        lineCount = 3,
                        duplicateCount = 0,
                        autoPostedCount = 1,
                        ambiguousCount = 0,
                        unmatchedCount = 1,
                        ignoredCount = 0,
                        suggestedCount = 1,
                        warnings = emptyList(),
                    ),
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc ->
                            if (request.url.contains(
                                    "/api/bank-statements/import",
                                )
                            ) {
                                StubResponse(status = 200, text = result)
                            } else {
                                StubResponse()
                            }
                        request.rpcRoute == listImports -> request.answerWith(emptyImportsJson())
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-finance-bank-import-success") { root, element ->
                    renderBankStatementImportScreen(root, null)
                    val screen = element()
                    awaitUntil("the empty state is shown") { screen.shows("Noch keine Kontoauszüge importiert.") }
                    val host = openCreateForm(screen, "lapis-create-bank-import")
                    val input = assertNotNull(host.querySelector("input[type=file]") as? HTMLInputElement, "no file input")
                    val transfer = js("new DataTransfer()")
                    transfer.items.add(js("new File(['a;b'], 'auszug.csv', { type: 'text/csv' })"))
                    input.asDynamic().files = transfer.files
                    input.dispatchEvent(Event("change"))
                    delay(200) // the upload control registers its selection asynchronously
                    host.buttonNamed("Hochladen").click()
                    awaitUntil("the upload was sent") { calls.any { it.url.contains("/api/bank-statements/import") } }
                    awaitUntil("the form folded back") { !screen.hasForm("lapis-create-bank-import") }
                    assertTrue(calls.toRoute(listImports).size >= 2, "the import list was reloaded after the upload")
                    assertTrue(document.querySelector(".modal.show") == null, "no discard dialog after a successful upload")
                }
            }
        }

    @Test
    fun bankImport_aBoardMemberSeesTheListButNoUploadButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val listImports = routeOf { rpcService<IBankStatementService>().listImports() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listImports -> request.answerWith(emptyImportsJson())
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-finance-bank-import-board") { root, element ->
                    renderBankStatementImportScreen(root, null)
                    awaitUntil("the plain empty state is shown") { element().shows("Noch keine Kontoauszüge importiert.") }
                    assertFalse(element().shows("Kontoauszug hochladen"), "the text does not name a button the role does not have")
                    assertTrue(element().actionButtons().isEmpty())
                    assertTrue(element().allOf(".lapis-page-action").isEmpty(), "no empty action area")
                }
            }
        }

    // ---- Bank accounts -----------------------------------------------------------------------------------------------------

    @Test
    fun bankAccounts_theCreateButtonMovedIntoTheTitleRow_andStillOpensTheModal(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() },
            ) { _ ->
                mountedForm("r36b-finance-bank-accounts") { root, element ->
                    renderBankAccountsScreen(root)
                    val screen = element()
                    awaitUntil("the button is there") { screen.actionButtons().isNotEmpty() }
                    val button = screen.actionButtons().single()
                    assertEquals("Neues Bankkonto", button.textContent?.trim())
                    assertEquals("true", button.querySelector("i.fa-plus")?.getAttribute("aria-hidden"))
                    assertTrue(screen.allOf("button").none { it.textContent?.trim() == "Bankkonto anlegen" }, "the old button is gone")
                    button.click()
                    awaitUntil("the modal is open") { document.querySelector(".modal.show") != null }
                    assertTrue(lastOpenModal().shows("Bankkonto anlegen"), "the modal keeps its own caption")
                }
            }
        }

    @Test
    fun bankAccounts_aBoardMemberGetsNoButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() },
            ) { _ ->
                mountedForm("r36b-finance-bank-accounts-board") { root, element ->
                    renderBankAccountsScreen(root)
                    awaitUntil("the empty state is shown") { element().shows("Noch kein Bankkonto angelegt.") }
                    assertTrue(element().actionButtons().isEmpty())
                    assertTrue(element().allOf(".lapis-page-action").isEmpty(), "no empty action area")
                }
            }
        }
}
