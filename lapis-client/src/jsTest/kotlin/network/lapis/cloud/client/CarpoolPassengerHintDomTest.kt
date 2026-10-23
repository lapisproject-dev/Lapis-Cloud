package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolPassengerHintDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ICarpoolService
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.125 -- the carpool centre's suggestion for the "Mitfahrer" field of a mileage line: shown only to the subject of the report, never
 * filled in on its own (the field keeps "0" until "Übernehmen" is pressed), and silent when the centre has nothing to say.
 */
class CarpoolPassengerHintDomTest {
    private fun session(memberId: String) =
        SessionInfoDto(
            memberId = memberId,
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val rates =
        TravelExpenseRatesDto(
            mileageRatePerKm = 0.3.toDecimal(),
            perDiemRate = 28.0.toDecimal(),
            expenseAccountConfigured = true,
            bankAccountConfigured = true,
        )

    private fun report() =
        TravelExpenseReportDto(
            id = "report-1",
            subjectMemberId = "subject-1",
            subjectDisplayName = "Dana Keller",
            status = TravelExpenseReportStatus.DRAFT,
            purpose = "Konferenz",
            travelFrom = LocalDate(2099, 1, 1),
            travelTo = LocalDate(2099, 1, 2),
            totalAmount = 0.0.toDecimal(),
            lines = emptyList(),
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            requestedBy = "subject-1",
            requestedByDisplayName = "Dana Keller",
        )

    @Test
    fun theHintIsOnlyASuggestion_andOnlyForTheSubjectOfTheReport(): Promise<Unit> =
        formTest {
            val hintRoute = routeOf { rpcService<ICarpoolService>().getPassengerHint(LocalDate(2099, 1, 1), LocalDate(2099, 1, 2)) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == hintRoute) {
                        request.answerWith(jsonOf(CarpoolPassengerHintDto.serializer(), CarpoolPassengerHintDto(2, multipleTrips = true)))
                    } else {
                        request.answerWith("null")
                    }
                },
            ) { calls ->
                // Subject == caller: the suggestion appears, the field stays "0" until "Übernehmen".
                AppState.setSession(session("subject-1"))
                mountedForm("carpool-hint-subject") { root, element ->
                    renderLineForm(root, report(), TravelExpenseLineKind.MILEAGE, null, rates) {}
                    awaitUntil("the suggestion appears") { element().textContent.orEmpty().contains("2 bestätigte Mitfahrer") }
                    val screen = element()
                    assertTrue(screen.textContent.orEmpty().contains("mehrere Fahrten in diesem Zeitraum"))
                    assertEquals("0", (screen.controlOf("Mitfahrer") as HTMLInputElement).value, "never set on its own")
                    screen.buttonNamed("Übernehmen").click()
                    assertEquals("2", (screen.controlOf("Mitfahrer") as HTMLInputElement).value)
                }
                assertEquals(1, calls.count { it.isRpc && it.rpcRoute == hintRoute })
                // Somebody else looks at the report: no question at all.
                AppState.setSession(session("somebody-else"))
                val before = calls.count { it.isRpc && it.rpcRoute == hintRoute }
                mountedForm("carpool-hint-other") { root, element ->
                    renderLineForm(root, report(), TravelExpenseLineKind.MILEAGE, null, rates) {}
                    awaitUntil("the form is built") { element().allOf("label").any { it.textContent.orEmpty().contains("Mitfahrer") } }
                    awaitAppScopeIdle("settled")
                    assertFalse(element().textContent.orEmpty().contains("Mitfahrerzentrale"))
                    assertFalse(element().textContent.orEmpty().contains("Mitfahrzentrale"))
                }
                assertEquals(before, calls.count { it.isRpc && it.rpcRoute == hintRoute }, "no question for a foreign report")
            }
        }
}
