package network.lapis.cloud.shared.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.311 -- truth table of the eligibility rule: the base case passes, and every single condition turns it off. */
class CarpoolEligibilityTest {
    private val today = LocalDate(2026, 11, 18)

    private fun base() =
        CarpoolTeaserEligibility(
            type = CarpoolPostingType.OFFER,
            optIn = true,
            instanceEnabled = true,
            partnerSharing = true,
            partnerActive = true,
            partnerBlocked = false,
            authorActive = true,
            departureDate = today.plus(5, DateTimeUnit.DAY),
            seatsOffered = 3,
            confirmedCount = 1,
            fromPlace = "38100 Braunschweig",
            toPlace = "10115 Berlin",
        )

    private fun eligible(e: CarpoolTeaserEligibility) = isCarpoolTeaserEligible(e = e, today = today)

    @Test
    fun baseCaseIsEligible() = assertTrue(eligible(base()))

    @Test
    fun everyConditionAloneTurnsItOff() {
        assertFalse(eligible(base().copy(type = CarpoolPostingType.REQUEST)))
        assertFalse(eligible(base().copy(optIn = false)))
        assertFalse(eligible(base().copy(instanceEnabled = false)))
        assertFalse(eligible(base().copy(partnerSharing = false)))
        assertFalse(eligible(base().copy(partnerActive = false)))
        assertFalse(eligible(base().copy(partnerBlocked = true)))
        assertFalse(eligible(base().copy(authorActive = false)))
        assertFalse(eligible(base().copy(seatsOffered = null)))
        assertFalse(eligible(base().copy(fromPlace = "Braunschweig")))
        assertFalse(eligible(base().copy(toPlace = "Berlin")))
    }

    @Test
    fun dateBoundaries() {
        assertTrue(eligible(base().copy(departureDate = today)))
        assertFalse(eligible(base().copy(departureDate = today.plus(-1, DateTimeUnit.DAY))))
        assertTrue(eligible(base().copy(departureDate = today.plus(90, DateTimeUnit.DAY))))
        assertFalse(eligible(base().copy(departureDate = today.plus(91, DateTimeUnit.DAY))))
    }

    @Test
    fun fullyBookedIsNotEligible() {
        assertFalse(eligible(base().copy(seatsOffered = 2, confirmedCount = 2)))
        assertTrue(eligible(base().copy(seatsOffered = 2, confirmedCount = 1)))
        assertEquals(false, eligible(base().copy(seatsOffered = 1, confirmedCount = 3)))
    }
}
