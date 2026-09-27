package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- [EventDto.coverImageUrl] is additive and
 * defaulted, same "no pre-existing caller/test breaks" discipline every other addendum field on
 * this DTO documents (`roomId`/`roomName`, `ticketIssuedAt`, ...). This is the regression test for
 * that guarantee: a JSON payload from BEFORE this field existed must still decode.
 */
class EventCoverBackwardCompatTest {
    private fun sampleDto() =
        EventDto(
            id = "11111111-1111-1111-1111-111111111111",
            slug = "test-event",
            title = "Test",
            description = "Test description",
            locationText = null,
            onlineUrl = null,
            startsAt = LocalDateTime(2030, 1, 1, 18, 0),
            endsAt = LocalDateTime(2030, 1, 1, 22, 0),
            capacity = null,
            feeAmount = 0.0.toDecimal(),
            feeCurrency = "EUR",
            status = EventStatus.PUBLISHED,
            visibility = EventVisibility.PUBLIC,
            registrationClosesAt = null,
            occupiedSeats = 0,
            waitlistCount = 0,
            full = false,
            feeEditable = true,
            ownRegistrationStatus = null,
            publicUrl = "https://example.org/veranstaltung/test-event",
        )

    @Test
    fun eventDto_decodesFromJson_withoutCoverImageUrlField_defaultingToNull() {
        val encoded = Json.encodeToString(EventDto.serializer(), sampleDto())
        // Simulates a payload produced BEFORE this wave: strip the field entirely rather than
        // setting it to null, since a real pre-wave server would never have emitted the key at all.
        val withoutField = Json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }
        val stripped =
            kotlinx.serialization.json.JsonObject(withoutField.filterKeys { it != "coverImageUrl" })
        val decoded = Json.decodeFromJsonElement(EventDto.serializer(), stripped)
        assertNull(decoded.coverImageUrl)
    }

    @Test
    fun eventDto_withCoverImageUrl_roundTrips() {
        val dto = sampleDto().copy(coverImageUrl = "https://example.org/veranstaltung/test-event/bild?v=abcd1234")
        val encoded = Json.encodeToString(EventDto.serializer(), dto)
        val decoded = Json.decodeFromString(EventDto.serializer(), encoded)
        assertEquals(dto.coverImageUrl, decoded.coverImageUrl)
    }

    @Test
    fun eventCoverResultDto_roundTrips_withNullAndNonNullUrl() {
        val withUrl = EventCoverResultDto(coverImageUrl = "https://example.org/veranstaltung/test-event/bild?v=abcd1234")
        assertEquals(
            withUrl,
            Json.decodeFromString(EventCoverResultDto.serializer(), Json.encodeToString(EventCoverResultDto.serializer(), withUrl)),
        )

        val withoutUrl = EventCoverResultDto(coverImageUrl = null)
        assertEquals(
            withoutUrl,
            Json.decodeFromString(EventCoverResultDto.serializer(), Json.encodeToString(EventCoverResultDto.serializer(), withoutUrl)),
        )
    }
}
