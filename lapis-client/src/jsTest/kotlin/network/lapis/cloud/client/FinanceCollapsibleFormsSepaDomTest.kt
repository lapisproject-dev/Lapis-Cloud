package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ISepaService
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
 * V1.9.45 -- rule R36B for the finance group, part 2: the SEPA screens. Two equal-rank buttons in the SEPA title row (batch, return), the
 * mandate form behind "Neues Mandat", and the server-loaded preselections of the return form that must NOT make the form look changed
 * ([FormSnapshot.applyProgrammatic]) while typed input is never swallowed.
 */
class FinanceCollapsibleFormsSepaDomTest {
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

    private fun submittedBatch() =
        SepaDebitBatchDto(
            id = "batch-1",
            messageId = "MSG-1",
            paymentInfoId = "PMT-1",
            requestedCollectionDate = LocalDate(2026, 10, 20),
            sequenceType = SepaSequenceType.RCUR,
            status = SepaDebitBatchStatus.SUBMITTED,
            itemCount = 0,
            totalAmount = 0.0.toDecimal(),
            createdByDisplayName = "Dana Keller",
            createdAt = LocalDateTime(2026, 10, 1, 9, 0),
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

    // ---- SEPA batches + returns ------------------------------------------------------------------------------------------

    @Test
    fun sepa_bothButtonsSitInTheTitleRow_bothFormsAreCollapsed_andTheEmptyStatesNameTheButtons(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() },
            ) { _ ->
                mountedForm("r36b-finance-sepa") { root, element ->
                    renderSepaBatchesScreen(root)
                    awaitUntil("the empty states name the buttons") {
                        element().shows("Noch kein SEPA-Lauf. Mit \"Neuer Lastschriftlauf\" legen Sie einen an.") &&
                            element().shows("Noch keine Rücklastschriften. Mit \"Neue Rücklastschrift\" erfassen Sie eine.")
                    }
                    val screen = element()
                    assertEquals(
                        listOf("Neuer Lastschriftlauf", "Neue Rücklastschrift"),
                        screen.actionButtons().map { it.textContent?.trim() },
                    )
                    assertFalse(screen.hasForm("lapis-create-sepa-batch"))
                    assertFalse(screen.hasForm("lapis-create-sepa-return"))
                    assertFalse(screen.shows("Neuer Lauf"), "the old always-visible section title is gone")
                    assertFalse(screen.shows("Rücklastschrift erfassen"), "neither the old title nor the form is on the page")
                }
            }
        }

    @Test
    fun sepa_theBatchFormClosesUnchanged_butAskAfterTyping(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() },
            ) { _ ->
                mountedForm("r36b-finance-sepa-batch") { root, element ->
                    renderSepaBatchesScreen(root)
                    val screen = element()
                    awaitUntil("the buttons are there") { screen.actionButtons().size == 2 }
                    val host = openCreateForm(screen, "lapis-create-sepa-batch")
                    // the tier list arrives (and "all tiers" is preselected) -- that is not an edit
                    awaitUntil(
                        "the preview button is there",
                    ) { screen.allOf("button").any { it.textContent?.trim() == "Vorschau berechnen" } }
                    val previewIcon = screen.buttonNamed("Vorschau berechnen").querySelector("i.fa-eye")
                    assertEquals("true", previewIcon?.getAttribute("aria-hidden"), "the preview verb carries its decorative eye icon")
                    escape(host)
                    awaitUntil("closed without a question") { !screen.hasForm("lapis-create-sepa-batch") }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openCreateForm(screen, "lapis-create-sepa-batch")
                    reopened.typeInto("Einzugsdatum", "2030-01-15")
                    reopened.buttonNamed("Abbrechen").click()
                    awaitDiscardDialog().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
                    assertEquals("2030-01-15", (reopened.controlOf("Einzugsdatum") as HTMLInputElement).value)
                }
            }
        }

    @Test
    fun sepa_theReturnFormsServerPreselectionIsNotAnEdit_butTypedInputIsNeverSwallowed(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val listBatches = routeOf { rpcService<ISepaService>().listBatches() }
            val getBatch = routeOf { rpcService<ISepaService>().getBatch("batch-1") }
            val detail = SepaDebitBatchDetailDto(batch = submittedBatch(), items = emptyList())
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listBatches ->
                            // the return form asks once per eligible status; only the SUBMITTED call finds the batch
                            request.answerWith(
                                if (request.body.contains("SUBMITTED")) {
                                    jsonOf(ListSerializer(SepaDebitBatchDto.serializer()), listOf(submittedBatch()))
                                } else {
                                    "[]"
                                },
                            )
                        request.rpcRoute == getBatch -> request.answerWith(jsonOf(SepaDebitBatchDetailDto.serializer(), detail))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-finance-sepa-return") { root, element ->
                    renderSepaBatchesScreen(root)
                    val screen = element()
                    awaitUntil("the buttons are there") { screen.actionButtons().size == 2 }
                    val host = openCreateForm(screen, "lapis-create-sepa-return")
                    awaitUntil("the batch was preselected and its items were asked for") { calls.toRoute(getBatch).isNotEmpty() }
                    escape(host)
                    awaitUntil("closed without a question: the server preselection is not an edit") {
                        !screen.hasForm("lapis-create-sepa-return")
                    }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openCreateForm(screen, "lapis-create-sepa-return")
                    // typed BEFORE the preselection arrives: the person's input must still count as a change
                    reopened.typeInto("Rücklastschriftgebühr", "2,50")
                    escape(reopened)
                    awaitDiscardDialog().buttonNamed("Verwerfen").click()
                    awaitUntil("closed after discarding") { !screen.hasForm("lapis-create-sepa-return") }
                }
            }
        }

    @Test
    fun sepa_aBoardMemberGetsNoButtons(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() },
            ) { _ ->
                mountedForm("r36b-finance-sepa-board") { root, element ->
                    renderSepaBatchesScreen(root)
                    awaitUntil("the empty state is shown") { element().shows("Noch kein SEPA-Lauf angelegt.") }
                    assertTrue(element().actionButtons().isEmpty())
                    assertTrue(element().allOf(".lapis-page-action").isEmpty(), "no empty action area")
                }
            }
        }

    // ---- SEPA mandates ---------------------------------------------------------------------------------------------------

    @Test
    fun mandates_theOnBehalfFormIsCollapsedBehindNeuesMandat(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            withFetchStub(
                respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() },
            ) { _ ->
                mountedForm("r36b-finance-mandates") { root, element ->
                    renderSepaMandatesScreen(root)
                    val screen = element()
                    awaitUntil("the button appears once the members are loaded") { screen.actionButtons().isNotEmpty() }
                    assertEquals(listOf("Neues Mandat"), screen.actionButtons().map { it.textContent?.trim() })
                    awaitUntil("the empty state names the button") {
                        screen.shows("Noch keine SEPA-Mandate. Mit \"Neues Mandat\" erfassen Sie eines.")
                    }
                    assertFalse(screen.hasForm("lapis-create-sepa-mandate"))
                    assertFalse(screen.shows("Es können höchstens"), "the rate-limit sentence belongs to the form")
                    assertFalse(screen.shows("Mandat im Namen eines Mitglieds erfassen"), "the old section title is gone")

                    val host = openCreateForm(screen, "lapis-create-sepa-mandate")
                    assertTrue(host.shows("Es können höchstens"))
                    escape(host)
                    awaitUntil("closed without a question") { !screen.hasForm("lapis-create-sepa-mandate") }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openCreateForm(screen, "lapis-create-sepa-mandate")
                    reopened.typeInto("IBAN", "DE89370400440532013000")
                    escape(reopened)
                    awaitDiscardDialog().buttonNamed("Verwerfen").click()
                    awaitUntil("closed after discarding") { !screen.hasForm("lapis-create-sepa-mandate") }
                }
            }
        }

    @Test
    fun mandates_theEmptyTextOnlyNamesTheButtonWhenTheRoleMayGrant() {
        assertEquals("Noch keine SEPA-Mandate erfasst.", sepaMandatesEmptyText(null))
        assertEquals("Noch keine SEPA-Mandate erfasst.", sepaMandatesEmptyText(null, canGrant = false))
        assertEquals("Noch keine SEPA-Mandate. Mit \"Neues Mandat\" erfassen Sie eines.", sepaMandatesEmptyText(null, canGrant = true))
        // with a status filter the text names the filter, never the button
        assertFalse(sepaMandatesEmptyText(SepaMandateStatus.REVOKED, canGrant = true).contains("Neues Mandat"))
    }
}
