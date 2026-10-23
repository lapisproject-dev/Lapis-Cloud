package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.191 -- the wire object of the shared line fields (`buildLineFields` / `LineFormFields.toInput`), which every add and edit form of the
 * "Weitere Kosten" section sends unchanged to `ITravelExpenseService.addLine/updateLine`. Pins, per kind, exactly the fields that existed before the
 * refactoring, and that the raw text of a kind survives a snapshot (the per-kind draft).
 */
class TravelExpenseLineFieldsTest {
    private val report =
        TravelExpenseReportDto(
            id = "report-1",
            subjectMemberId = "member-1",
            subjectDisplayName = "Amara Okafor",
            status = TravelExpenseReportStatus.DRAFT,
            purpose = "Delegiertenversammlung",
            travelFrom = LocalDate(2026, 3, 10),
            travelTo = LocalDate(2026, 3, 12),
            totalAmount = 0.0.toDecimal(),
            lines = emptyList(),
            createdAt = LocalDateTime(2026, 3, 1, 9, 0),
            requestedBy = "member-1",
            requestedByDisplayName = "Amara Okafor",
        )

    private suspend fun fieldsOf(
        id: String,
        kind: TravelExpenseLineKind,
        initial: LineDraftValues? = null,
        block: suspend (LineFormFields, () -> org.w3c.dom.HTMLElement) -> Unit,
    ) {
        useTextTemporalInputs()
        withMountedRoot(id) { root, element ->
            val fields = buildLineFields(root.lapisForm(), report, kind, line = null, initial = initial)
            delay(60)
            block(fields, element)
        }
    }

    @Test
    fun mileage_sendsWholeKilometersAndPassengers_andNothingElse(): Promise<Unit> =
        formTest {
            fieldsOf("te-fields-mileage", TravelExpenseLineKind.MILEAGE) { fields, element ->
                element().typeInto("Beschreibung", "  Zugfahrt  ")
                element().typeInto("Kilometer", " 123 ")
                element().typeInto("Mitfahrer", "2")
                val input = fields.toInput()
                assertEquals(TravelExpenseLineKind.MILEAGE, input.kind)
                assertEquals("Zugfahrt", input.description)
                assertEquals(123.0, input.kilometers?.toDouble())
                assertEquals(2, input.passengerCount)
                assertNull(input.amountCents)
                assertNull(input.dayDate)
                assertNull(input.nights)
                assertNull(input.breakfastIncluded)
            }
        }

    @Test
    fun accommodation_sendsFirstNightNightsBreakfastAndAmount(): Promise<Unit> =
        formTest {
            fieldsOf("te-fields-accommodation", TravelExpenseLineKind.ACCOMMODATION) { fields, element ->
                element().typeInto("Unterkunft", "Hotel Alpha")
                element().typeInto("Erste Nacht", "11.03.2026")
                element().typeInto("Nächte", "2")
                element().typeInto("Betrag", "160,00")
                (element().controlOf("mit Frühstück") as HTMLInputElement).click()
                val input = fields.toInput()
                assertEquals(TravelExpenseLineKind.ACCOMMODATION, input.kind)
                assertEquals("Hotel Alpha", input.description)
                assertEquals(LocalDate(2026, 3, 11), input.dayDate)
                assertEquals(2, input.nights)
                assertEquals(true, input.breakfastIncluded)
                assertEquals(16000L, input.amountCents)
                assertNull(input.kilometers)
                assertNull(input.passengerCount)
            }
        }

    @Test
    fun theThreeAmountKinds_sendOnlyDescriptionAndAmount(): Promise<Unit> =
        formTest {
            for (kind in listOf(TravelExpenseLineKind.PUBLIC_TRANSPORT, TravelExpenseLineKind.HOSPITALITY, TravelExpenseLineKind.OTHER)) {
                fieldsOf("te-fields-amount-$kind", kind) { fields, element ->
                    element().typeInto("Beschreibung", "Beleg")
                    element().typeInto("Betrag", "12,50")
                    val expected = TravelExpenseLineInput(kind = kind, description = "Beleg", amountCents = 1250)
                    assertEquals(expected, fields.toInput(), "wire object of $kind")
                }
            }
        }

    @Test
    fun snapshot_carriesTheRawText_andARebuiltFormShowsIt(): Promise<Unit> =
        formTest {
            var snapshot: LineDraftValues? = null
            fieldsOf("te-fields-snapshot-1", TravelExpenseLineKind.MILEAGE) { fields, element ->
                element().typeInto("Beschreibung", "Zug")
                element().typeInto("Kilometer", "77")
                element().typeInto("Mitfahrer", "3")
                snapshot = fields.snapshot()
            }
            val taken = snapshot
            assertTrue(taken != null)
            assertEquals("Zug", taken.description)
            assertEquals("77", taken.kilometers)
            assertEquals("3", taken.passengers)
            assertNull(taken.amount, "a mileage form has no amount field")
            fieldsOf("te-fields-snapshot-2", TravelExpenseLineKind.MILEAGE, initial = taken) { _, element ->
                assertEquals("Zug", (element().controlOf("Beschreibung") as HTMLInputElement).value)
                assertEquals("77", (element().controlOf("Kilometer") as HTMLInputElement).value)
                assertEquals("3", (element().controlOf("Mitfahrer") as HTMLInputElement).value)
            }
        }
}
