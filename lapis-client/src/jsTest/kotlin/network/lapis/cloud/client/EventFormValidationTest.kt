package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.3.x "Veranstaltungen: BOARD/ADMIN-Verwaltungsoberfläche" -- covers
 * `EventFormValidation.kt`'s pure `validateEventForm`, field for field against
 * `network.lapis.cloud.server.events.EventPolicy.validate`'s own checks (see that file's own
 * `EventPolicyTest` on the server side) plus the `existingStartsAt` edit-exception.
 * `now` is always passed explicitly (never the real-clock default) so every case is deterministic.
 */
class EventFormValidationTest {
    private val now = LocalDateTime(2026, 9, 17, 12, 0)

    private fun validRaw(
        title: String = "Mitgliederversammlung",
        description: String = "Jährliche Mitgliederversammlung mit Wahlen.",
        locationText: String = "Musterstr. 1, 38100 Braunschweig",
        onlineUrl: String = "",
        startsAtRaw: String = "2026-09-20T18:00",
        endsAtRaw: String = "2026-09-20T20:00",
        registrationClosesAtRaw: String = "",
        capacityRaw: String = "",
        feeAmountRaw: String = "0",
        visibility: EventVisibility = EventVisibility.MEMBERS_ONLY,
        roomId: String? = null,
    ) = EventFormRawInput(
        title = title,
        description = description,
        locationText = locationText,
        onlineUrl = onlineUrl,
        startsAtRaw = startsAtRaw,
        endsAtRaw = endsAtRaw,
        registrationClosesAtRaw = registrationClosesAtRaw,
        capacityRaw = capacityRaw,
        feeAmountRaw = feeAmountRaw,
        visibility = visibility,
        roomId = roomId,
    )

    private fun validate(
        raw: EventFormRawInput,
        existingStartsAt: LocalDateTime? = null,
    ) = validateEventForm(raw, existingStartsAt, now)

    @Test
    fun emptyTitle_isRejected() {
        val result = validate(validRaw(title = "  "))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun titleOver200Characters_isRejected() {
        val result = validate(validRaw(title = "x".repeat(201)))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun emptyDescription_isRejected() {
        val result = validate(validRaw(description = "  "))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun descriptionOver8000Characters_isRejected() {
        val result = validate(validRaw(description = "x".repeat(8001)))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun neitherLocationNorOnlineUrl_isRejected() {
        val result = validate(validRaw(locationText = "", onlineUrl = ""))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun onlyLocationSet_isOk() {
        val result = validate(validRaw(locationText = "Musterstr. 1", onlineUrl = ""))
        assertTrue(result is EventFormResult.Ok)
    }

    @Test
    fun onlyOnlineUrlSet_isOk() {
        val result = validate(validRaw(locationText = "", onlineUrl = "https://example.org/meeting"))
        assertTrue(result is EventFormResult.Ok)
    }

    @Test
    fun invalidStartDate_isRejected() {
        val result = validate(validRaw(startsAtRaw = "nicht ein datum"))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun invalidEndDate_isRejected() {
        val result = validate(validRaw(endsAtRaw = "nicht ein datum"))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun endBeforeStart_isRejected() {
        val result = validate(validRaw(startsAtRaw = "2026-09-20T20:00", endsAtRaw = "2026-09-20T18:00"))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun registrationClosesAfterStart_isRejected() {
        val result =
            validate(
                validRaw(
                    startsAtRaw = "2026-09-20T18:00",
                    endsAtRaw = "2026-09-20T20:00",
                    registrationClosesAtRaw = "2026-09-20T19:00",
                ),
            )
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun registrationClosesAtEmpty_isOkAndNull() {
        val result = validate(validRaw(registrationClosesAtRaw = ""))
        assertTrue(result is EventFormResult.Ok)
        assertNull(result.input.registrationClosesAt)
    }

    @Test
    fun capacityZeroOrNegative_isRejected() {
        assertTrue(validate(validRaw(capacityRaw = "0")) is EventFormResult.Error)
        assertTrue(validate(validRaw(capacityRaw = "-5")) is EventFormResult.Error)
    }

    @Test
    fun capacityNonNumeric_isRejected() {
        val result = validate(validRaw(capacityRaw = "abc"))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun capacityEmpty_isOkAndNull() {
        val result = validate(validRaw(capacityRaw = ""))
        assertTrue(result is EventFormResult.Ok)
        assertNull(result.input.capacity)
    }

    @Test
    fun feeNegative_isRejected() {
        val result = validate(validRaw(feeAmountRaw = "-1"))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun feeWithThreeDecimalPlaces_isRejected() {
        val result = validate(validRaw(feeAmountRaw = "12.345"))
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun feeZero_isOk() {
        val result = validate(validRaw(feeAmountRaw = "0"))
        assertTrue(result is EventFormResult.Ok)
    }

    @Test
    fun startInPast_newEvent_isRejected() {
        val result = validate(validRaw(startsAtRaw = "2020-01-01T00:00", endsAtRaw = "2020-01-01T01:00"), existingStartsAt = null)
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun startInPast_editWithUnchangedStart_isOk() {
        val pastStart = LocalDateTime(2020, 1, 1, 0, 0)
        val raw = validRaw(startsAtRaw = "2020-01-01T00:00", endsAtRaw = "2020-01-01T01:00")
        val result = validate(raw, existingStartsAt = pastStart)
        assertTrue(result is EventFormResult.Ok)
    }

    @Test
    fun startMovedIntoPast_editWithChangedStart_isRejected() {
        val existingStart = LocalDateTime(2026, 9, 20, 18, 0)
        val raw = validRaw(startsAtRaw = "2020-01-01T00:00", endsAtRaw = "2020-01-01T01:00")
        val result = validate(raw, existingStartsAt = existingStart)
        assertTrue(result is EventFormResult.Error)
    }

    @Test
    fun allFieldsValid_mapsCorrectlyWithEurCurrency() {
        val raw =
            validRaw(
                title = "Sommerfest",
                description = "Geselliges Beisammensein.",
                locationText = "Parkanlage",
                onlineUrl = "",
                startsAtRaw = "2026-10-01T14:00",
                endsAtRaw = "2026-10-01T18:00",
                registrationClosesAtRaw = "2026-09-30T23:59",
                capacityRaw = "50",
                feeAmountRaw = "12.50",
                visibility = EventVisibility.PUBLIC,
                roomId = "room-1",
            )
        val result = validate(raw)
        assertTrue(result is EventFormResult.Ok)
        val input = result.input
        assertEquals("Sommerfest", input.title)
        assertEquals("Geselliges Beisammensein.", input.description)
        assertEquals("Parkanlage", input.locationText)
        assertNull(input.onlineUrl)
        assertEquals(LocalDateTime(2026, 10, 1, 14, 0), input.startsAt)
        assertEquals(LocalDateTime(2026, 10, 1, 18, 0), input.endsAt)
        assertEquals(LocalDateTime(2026, 9, 30, 23, 59), input.registrationClosesAt)
        assertEquals(50, input.capacity)
        assertEquals("EUR", input.feeCurrency)
        assertEquals(EventVisibility.PUBLIC, input.visibility)
        assertEquals("room-1", input.roomId)
    }
}
