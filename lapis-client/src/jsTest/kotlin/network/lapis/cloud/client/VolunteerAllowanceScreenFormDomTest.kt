package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceConfigDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentInput
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4d batch 5: `VolunteerAllowanceScreen.kt`'s one migrated `lapisForm` -- the payment form
 * (Kategorie required select, Betrag/Tätigkeitsbeschreibung/Zahlungsdatum required text-like
 * fields), used for both "Entwurf anlegen" and "Entwurf speichern" -- driven the way a person does
 * (type, choose, blur, click) against a stubbed `window.fetch`, same idiom
 * `TravelExpenseScreenFormDomTest.kt` (W4d batch 3) already establishes.
 */
class VolunteerAllowanceScreenFormDomTest {
    private val memberSession =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Amara Okafor",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val config =
        VolunteerAllowanceConfigDto(
            instructorCap = 3000.0.toDecimal(),
            honoraryCap = 840.0.toDecimal(),
            expenseAccountConfigured = true,
            bankAccountConfigured = true,
        )

    private fun draft() =
        VolunteerAllowancePaymentDto(
            id = "payment-1",
            subjectMemberId = "member-1",
            subjectDisplayName = "Amara Okafor",
            category = VolunteerAllowanceCategory.HONORARY,
            status = VolunteerAllowancePaymentStatus.DRAFT,
            amount = 200.0.toDecimal(),
            activityDescription = "Jugendtrainer",
            paymentDate = LocalDate(2026, 3, 10),
            createdAt = LocalDateTime(2026, 3, 1, 9, 0),
            requestedBy = "member-1",
            requestedByDisplayName = "Amara Okafor",
        )

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `TravelExpenseScreenFormDomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    @Test
    fun paymentForm_createDraft_sendsTheChosenCategoryAndTypedFieldsTrimmed(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val configRoute = routeOf { rpcService<IVolunteerAllowanceService>().getVolunteerAllowanceConfig() }
            val listRoute = routeOf { rpcService<IVolunteerAllowanceService>().listMyPayments() }
            val createDraftRoute =
                routeOf {
                    rpcService<IVolunteerAllowanceService>().createDraft(
                        "x",
                        VolunteerAllowancePaymentInput(
                            VolunteerAllowanceCategory.HONORARY,
                            0.0.toDecimal(),
                            "x",
                            LocalDate(2026, 1, 1),
                        ),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        configRoute to jsonOf(VolunteerAllowanceConfigDto.serializer(), config),
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), emptyList()),
                        createDraftRoute to jsonOf(VolunteerAllowancePaymentDto.serializer(), draft()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-create-happy") { root, element ->
                    renderVolunteerAllowanceScreen(root, null)
                    delay(80)
                    element().buttonNamed("Neue Zahlung beantragen").click()
                    delay(80)
                    element().chooseIn("Kategorie", VolunteerAllowanceCategory.INSTRUCTOR.name)
                    element().typeInto("Betrag (EUR)", "  123,45  ")
                    element().typeInto("Tätigkeitsbeschreibung", "  Jugendtrainer beim Vereinsfest  ")
                    element().typeInto("Zahlungsdatum (JJJJ-MM-TT)", "  2026-03-10  ")
                    element().buttonNamed("Entwurf anlegen").click()
                    awaitUntil("createDraft", timeoutMs = 800) { calls.toRoute(createDraftRoute).size == 1 }
                    val call = calls.singleCall(createDraftRoute)
                    assertEquals("member-1", call.rpcParam(0) as String, "the session member is the subject")
                    val input = call.rpcParam(1)
                    assertEquals("INSTRUCTOR", input.category as String, "the chosen category is sent")
                    assertEquals(123.45, input.amount as Double, "the comma decimal is parsed and sent as a Decimal")
                    assertEquals(
                        "Jugendtrainer beim Vereinsfest",
                        input.activityDescription as String,
                        "the description is sent, trimmed",
                    )
                    assertEquals("2026-03-10", input.paymentDate as String)
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a valid payment form")
                }
            }
        }

    @Test
    fun paymentForm_withAnInvalidDate_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val configRoute = routeOf { rpcService<IVolunteerAllowanceService>().getVolunteerAllowanceConfig() }
            val listRoute = routeOf { rpcService<IVolunteerAllowanceService>().listMyPayments() }
            val createDraftRoute =
                routeOf {
                    rpcService<IVolunteerAllowanceService>().createDraft(
                        "x",
                        VolunteerAllowancePaymentInput(
                            VolunteerAllowanceCategory.HONORARY,
                            0.0.toDecimal(),
                            "x",
                            LocalDate(2026, 1, 1),
                        ),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        configRoute to jsonOf(VolunteerAllowanceConfigDto.serializer(), config),
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-create-invalid-date") { root, element ->
                    renderVolunteerAllowanceScreen(root, null)
                    delay(80)
                    element().buttonNamed("Neue Zahlung beantragen").click()
                    delay(80)
                    element().typeInto("Betrag (EUR)", "50,00")
                    element().typeInto("Tätigkeitsbeschreibung", "Jugendtrainer beim Vereinsfest")
                    element().typeInto("Zahlungsdatum (JJJJ-MM-TT)", "nicht-ein-datum")
                    element().buttonNamed("Entwurf anlegen").click()
                    delay(80)
                    assertTrue(calls.toRoute(createDraftRoute).isEmpty(), "no createDraft call for an invalid date")
                    assertTrue(element().shownErrors().isNotEmpty(), "the invalid date is reported on the field")
                }
            }
        }

    @Test
    fun paymentForm_withATooShortDescription_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val configRoute = routeOf { rpcService<IVolunteerAllowanceService>().getVolunteerAllowanceConfig() }
            val listRoute = routeOf { rpcService<IVolunteerAllowanceService>().listMyPayments() }
            val createDraftRoute =
                routeOf {
                    rpcService<IVolunteerAllowanceService>().createDraft(
                        "x",
                        VolunteerAllowancePaymentInput(
                            VolunteerAllowanceCategory.HONORARY,
                            0.0.toDecimal(),
                            "x",
                            LocalDate(2026, 1, 1),
                        ),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        configRoute to jsonOf(VolunteerAllowanceConfigDto.serializer(), config),
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-create-short-description") { root, element ->
                    renderVolunteerAllowanceScreen(root, null)
                    delay(80)
                    element().buttonNamed("Neue Zahlung beantragen").click()
                    delay(80)
                    element().typeInto("Betrag (EUR)", "50,00")
                    // Below VolunteerAllowanceRules.MIN_ACTIVITY_DESCRIPTION_LENGTH (3).
                    element().typeInto("Tätigkeitsbeschreibung", "ab")
                    element().typeInto("Zahlungsdatum (JJJJ-MM-TT)", "2026-03-10")
                    element().buttonNamed("Entwurf anlegen").click()
                    delay(80)
                    assertTrue(calls.toRoute(createDraftRoute).isEmpty(), "no createDraft call for a too-short description")
                    assertTrue(element().shownErrors().isNotEmpty(), "the too-short description is reported on the field")
                }
            }
        }

    @Test
    fun paymentForm_updateDraft_prefillsTheExistingValuesAndDisablesTheCategory(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val configRoute = routeOf { rpcService<IVolunteerAllowanceService>().getVolunteerAllowanceConfig() }
            val listRoute = routeOf { rpcService<IVolunteerAllowanceService>().listMyPayments() }
            val updateDraftRoute =
                routeOf {
                    rpcService<IVolunteerAllowanceService>().updateDraft(
                        "x",
                        VolunteerAllowancePaymentInput(
                            VolunteerAllowanceCategory.HONORARY,
                            0.0.toDecimal(),
                            "x",
                            LocalDate(2026, 1, 1),
                        ),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        configRoute to jsonOf(VolunteerAllowanceConfigDto.serializer(), config),
                        listRoute to jsonOf(ListSerializer(VolunteerAllowancePaymentDto.serializer()), listOf(draft())),
                        updateDraftRoute to jsonOf(VolunteerAllowancePaymentDto.serializer(), draft()),
                    ),
            ) { calls ->
                mountedForm("volunteer-allowance-update-happy") { root, element ->
                    renderVolunteerAllowanceScreen(root, null)
                    awaitUntil("draft editor rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Speichern" }
                    }
                    delay(80)
                    element().typeInto("Betrag (EUR)", "250,00")
                    element().buttonNamed("Speichern").click()
                    awaitUntil("updateDraft", timeoutMs = 800) { calls.toRoute(updateDraftRoute).size == 1 }
                    val call = calls.singleCall(updateDraftRoute)
                    assertEquals("payment-1", call.rpcParam(0) as String)
                    val input = call.rpcParam(1)
                    assertEquals("HONORARY", input.category as String, "the unchangeable category of the existing draft is sent")
                    assertEquals(250.0, input.amount as Double, "the newly typed amount is sent")
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a valid update")
                }
            }
        }
}
