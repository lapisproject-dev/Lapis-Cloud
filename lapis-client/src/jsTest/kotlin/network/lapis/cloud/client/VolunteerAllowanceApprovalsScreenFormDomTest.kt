package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceYearStatusDto
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4d batch 6 (last file of the wave): `VolunteerAllowanceApprovalsScreen.kt`'s two migrated `lapisForm`s --
 * the combined decision panel (Entscheidungsnotiz, Pflicht; "Genehmigen und buchen"/"Buchung wiederholen" vs.
 * "Ablehnen", 1:1 nach `TravelExpenseApprovalsScreen.renderRequestedDecisionPanel`s Vorbild) and the
 * paper-declaration recording form (Unterschrieben am, single required date field). Driven the way a person
 * does (type, blur, click) against a stubbed `window.fetch`, same idiom `TravelExpenseApprovalsScreenFormDomTest.kt`
 * (W4d batch 4) already establishes.
 */
class VolunteerAllowanceApprovalsScreenFormDomTest {
    private val boardSession =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Board Member",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun requestedPayment() =
        VolunteerAllowancePaymentDto(
            id = "payment-1",
            subjectMemberId = "member-2",
            subjectDisplayName = "Amara Okafor",
            category = VolunteerAllowanceCategory.HONORARY,
            status = VolunteerAllowancePaymentStatus.REQUESTED,
            amount = 200.0.toDecimal(),
            activityDescription = "Jugendtrainer beim Vereinsfest",
            paymentDate = LocalDate(2026, 3, 10),
            createdAt = LocalDateTime(2026, 3, 1, 9, 0),
            submittedAt = LocalDateTime(2026, 3, 1, 9, 5),
            requestedBy = "member-2",
            requestedByDisplayName = "Amara Okafor",
        )

    private fun approvedPayment() =
        requestedPayment().copy(status = VolunteerAllowancePaymentStatus.APPROVED, executionError = "BANK_ACCOUNT_MISSING")

    private fun declaredYearStatus() =
        VolunteerAllowanceYearStatusDto(
            memberId = "member-2",
            category = VolunteerAllowanceCategory.HONORARY,
            calendarYear = 2026,
            annualCap = 840.0.toDecimal(),
            postedTotalInThisOrganization = 0.0.toDecimal(),
            remainingInThisOrganization = 840.0.toDecimal(),
            declaration =
                VolunteerAllowanceSelfDeclarationDto(
                    id = "decl-1",
                    memberId = "member-2",
                    category = VolunteerAllowanceCategory.HONORARY,
                    calendarYear = 2026,
                    source = VolunteerAllowanceDeclarationSource.IN_APP,
                    declaredAt = LocalDateTime(2026, 2, 1, 9, 0),
                    recordedByDisplayName = "Amara Okafor",
                ),
        )

    private fun undeclaredYearStatus() = declaredYearStatus().copy(declaration = null)

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `TravelExpenseScreenFormDomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    @Test
    fun requestedDecision_theNoteIsMarkedAsRequired_approveSendsTheTrimmedNote(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().listPayments(status = null, afterSubmittedAt = null, afterId = null) }
            val yearStatusRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().getYearStatus("x", VolunteerAllowanceCategory.HONORARY, 2026) }
            val decideRoute = routeOf { rpcService<IVolunteerAllowanceService>().decidePayment("i", true, "n", null) }
            withFetchStub(
                respond =
                    answering(
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), listOf(requestedPayment())),
                        yearStatusRoute to jsonOf(VolunteerAllowanceYearStatusDto.serializer(), declaredYearStatus()),
                        decideRoute to jsonOf(VolunteerAllowancePaymentDto.serializer(), requestedPayment()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-approvals-decide") { root, element ->
                    renderVolunteerAllowanceApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Genehmigen und buchen" }
                    }
                    delay(80)
                    // M-7: a single required field is recognisable as required (star + legend), not silently "case (c)".
                    assertEquals(1, element().querySelectorAll(".lapis-required-mark").length, "a star on the note")
                    assertTrue(element().textContent.orEmpty().contains("* Pflichtfeld"), "with the legend")

                    // empty note: nothing goes out, at all
                    element().buttonNamed("Genehmigen und buchen").click()
                    delay(60)
                    assertEquals(0, calls.toRoute(decideRoute).size, "no decidePayment for an empty note")
                    assertEquals("Bitte eine Entscheidungsnotiz eingeben.", element().shownErrors().single())

                    element().typeInto("Entscheidungsnotiz", "  Ordnungsgemäß geprüft  ")
                    element().buttonNamed("Genehmigen und buchen").click()
                    awaitUntil("approve", timeoutMs = 800) { calls.toRoute(decideRoute).size == 1 }
                    val approve = calls.singleCall(decideRoute)
                    assertEquals("payment-1", approve.rpcParam(0) as String)
                    assertEquals(true, approve.rpcParam(1) as Boolean, "Genehmigen sends approve = true")
                    assertEquals("Ordnungsgemäß geprüft", approve.rpcParam(2) as String, "the note arrives trimmed")
                }
            }
        }

    @Test
    fun requestedDecision_reject_withABlankNote_blocksAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().listPayments(status = null, afterSubmittedAt = null, afterId = null) }
            val yearStatusRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().getYearStatus("x", VolunteerAllowanceCategory.HONORARY, 2026) }
            val decideRoute = routeOf { rpcService<IVolunteerAllowanceService>().decidePayment("i", false, "n", null) }
            withFetchStub(
                respond =
                    answering(
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), listOf(requestedPayment())),
                        yearStatusRoute to jsonOf(VolunteerAllowanceYearStatusDto.serializer(), declaredYearStatus()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-approvals-decide-blank") { root, element ->
                    renderVolunteerAllowanceApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Ablehnen" }
                    }
                    delay(80)
                    element().typeInto("Entscheidungsnotiz", "   ")
                    element().buttonNamed("Ablehnen").click()
                    delay(60)
                    assertEquals(0, calls.toRoute(decideRoute).size, "a blank note blocks Ablehnen as well")
                    assertTrue(element().shownErrors().isNotEmpty())
                }
            }
        }

    @Test
    fun approvedRetry_retryNeedsNoNote_onlyRejectingDoes_andTheHintSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().listPayments(status = null, afterSubmittedAt = null, afterId = null) }
            val yearStatusRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().getYearStatus("x", VolunteerAllowanceCategory.HONORARY, 2026) }
            val retryRoute = routeOf { rpcService<IVolunteerAllowanceService>().retryPosting("i") }
            withFetchStub(
                respond =
                    answering(
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), listOf(approvedPayment())),
                        yearStatusRoute to jsonOf(VolunteerAllowanceYearStatusDto.serializer(), declaredYearStatus()),
                        retryRoute to jsonOf(VolunteerAllowancePaymentDto.serializer(), approvedPayment()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-approvals-retry") { root, element ->
                    renderVolunteerAllowanceApprovalsScreen(root)
                    awaitUntil("card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Buchung wiederholen" }
                    }
                    delay(80)
                    assertTrue(
                        element().textContent.orEmpty().contains("Nur für \"Ablehnen\" erforderlich."),
                        "the hint tells that the star is for Ablehnen only",
                    )
                    element().buttonNamed("Buchung wiederholen").click()
                    awaitUntil("retry", timeoutMs = 800) { calls.toRoute(retryRoute).size == 1 }
                    assertEquals("payment-1", calls.singleCall(retryRoute).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun paperDeclarationForm_recordsTheTypedSignedOnDate(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().listPayments(status = null, afterSubmittedAt = null, afterId = null) }
            val yearStatusRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().getYearStatus("x", VolunteerAllowanceCategory.HONORARY, 2026) }
            val recordRoute =
                routeOf {
                    rpcService<IVolunteerAllowanceService>().recordPaperDeclaration(
                        VolunteerAllowanceSelfDeclarationInput(
                            memberId = "x",
                            category = VolunteerAllowanceCategory.HONORARY,
                            calendarYear = 2026,
                            source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                            signedOn = LocalDate(2026, 1, 1),
                        ),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), listOf(requestedPayment())),
                        yearStatusRoute to jsonOf(VolunteerAllowanceYearStatusDto.serializer(), undeclaredYearStatus()),
                        recordRoute to jsonOf(VolunteerAllowanceSelfDeclarationDto.serializer(), declaredYearStatus().declaration!!),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-approvals-paper-declaration") { root, element ->
                    renderVolunteerAllowanceApprovalsScreen(root)
                    awaitUntil("paper declaration form rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Papiererklärung erfassen" }
                    }
                    delay(80)
                    element().typeInto("Unterschrieben am (JJJJ-MM-TT)", "  2026-03-14  ")
                    element().buttonNamed("Papiererklärung erfassen").click()
                    awaitUntil("record", timeoutMs = 800) { calls.toRoute(recordRoute).size == 1 }
                    val call = calls.singleCall(recordRoute)
                    val input = call.rpcParam(0)
                    assertEquals("2026-03-14", input.signedOn as String, "the typed date arrives trimmed and parsed")
                    assertEquals("ON_PAPER", input.source as String)
                }
            }
        }

    @Test
    fun paperDeclarationForm_withAnInvalidDate_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().listPayments(status = null, afterSubmittedAt = null, afterId = null) }
            val yearStatusRoute =
                routeOf { rpcService<IVolunteerAllowanceService>().getYearStatus("x", VolunteerAllowanceCategory.HONORARY, 2026) }
            val recordRoute =
                routeOf {
                    rpcService<IVolunteerAllowanceService>().recordPaperDeclaration(
                        VolunteerAllowanceSelfDeclarationInput(
                            memberId = "x",
                            category = VolunteerAllowanceCategory.HONORARY,
                            calendarYear = 2026,
                            source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                            signedOn = LocalDate(2026, 1, 1),
                        ),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), listOf(requestedPayment())),
                        yearStatusRoute to jsonOf(VolunteerAllowanceYearStatusDto.serializer(), undeclaredYearStatus()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-approvals-paper-declaration-invalid") { root, element ->
                    renderVolunteerAllowanceApprovalsScreen(root)
                    awaitUntil("paper declaration form rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Papiererklärung erfassen" }
                    }
                    delay(80)
                    element().typeInto("Unterschrieben am (JJJJ-MM-TT)", "nicht-ein-datum")
                    element().buttonNamed("Papiererklärung erfassen").click()
                    delay(60)
                    assertEquals(0, calls.toRoute(recordRoute).size, "no recordPaperDeclaration call for an invalid date")
                    assertTrue(element().shownErrors().isNotEmpty(), "the invalid date is reported on the field")
                }
            }
        }
}
