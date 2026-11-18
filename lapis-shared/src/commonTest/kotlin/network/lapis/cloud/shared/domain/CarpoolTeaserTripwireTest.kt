package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** V1.9.311 -- the wire type of the hint carries exactly four fields; nothing else can be added without failing this test. */
class CarpoolTeaserTripwireTest {
    private val day = LocalDate(2026, 12, 1)
    private val id = "0123456789abcdef"

    @Test
    fun theWireTypeHasExactlyFourFields() {
        assertEquals(
            setOf("teaserId", "departureDate", "fromPostcodeArea", "toPostcodeArea"),
            CarpoolTeaser
                .serializer()
                .descriptor.elementNames
                .toSet(),
        )
    }

    private fun make(
        id: String,
        from: String,
        to: String,
    ) = CarpoolTeaser(teaserId = id, departureDate = day, fromPostcodeArea = from, toPostcodeArea = to)

    private fun project(
        from: String,
        to: String,
    ) = projectCarpoolTeaser(fromPlace = from, toPlace = to, departureDate = day, teaserId = CarpoolTeaser.PREVIEW_ID)

    @Test
    fun initRejectsMalformedAreas() {
        assertFailsWith<IllegalArgumentException> { make(id = id, from = "381", to = "38") }
        assertFailsWith<IllegalArgumentException> { make(id = id, from = "38", to = "3a") }
        assertFailsWith<IllegalArgumentException> { make(id = id, from = "", to = "38") }
    }

    @Test
    fun initRejectsMalformedIds() {
        assertFailsWith<IllegalArgumentException> { make(id = "a".repeat(15), from = "38", to = "10") }
        assertFailsWith<IllegalArgumentException> { make(id = "a".repeat(65), from = "38", to = "10") }
        assertFailsWith<IllegalArgumentException> { make(id = "a/b" + "c".repeat(20), from = "38", to = "10") }
        assertNotNull(make(id = "a".repeat(16), from = "38", to = "10"))
        assertNotNull(make(id = "a".repeat(64), from = "38", to = "10"))
    }

    @Test
    fun thePreviewIdIsValidAndProjectionWorks() {
        val teaser = project(from = "38100 Braunschweig", to = "10115 Berlin")
        assertNotNull(teaser)
        assertEquals("38", teaser.fromPostcodeArea)
        assertEquals("10", teaser.toPostcodeArea)
        assertNull(project(from = "Braunschweig", to = "10115 Berlin"))
        assertNull(project(from = "38100 Braunschweig", to = "Berlin"))
    }

    @Test
    fun hopLimitIsClamped() {
        assertEquals(1, clampHopLimit(-5))
        assertEquals(1, clampHopLimit(0))
        assertEquals(1, clampHopLimit(1))
        assertEquals(3, clampHopLimit(3))
        assertEquals(3, clampHopLimit(4))
        assertEquals(3, clampHopLimit(Int.MAX_VALUE))
    }
}
