package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.311 -- the DOM-free decisions of the consent box in the trip form. */
class CarpoolFederationUiLogicTest {
    private val day = LocalDate(2026, 12, 1)

    private fun dto(optIn: Boolean) =
        CarpoolPostingDto(
            id = "x",
            type = CarpoolPostingType.OFFER,
            fromPlace = "38100 A",
            toPlace = "30159 B",
            departureDate = day,
            departureTime = null,
            seatsOffered = 2,
            notes = null,
            authorDisplayName = "Ich",
            isOwn = true,
            isPast = false,
            createdAt = LocalDateTime(2026, 11, 1, 0, 0),
            federationOptIn = optIn,
        )

    @Test
    fun theBoxIsOfferedOnlyForAnOfferWhileTheExchangeIsOn() {
        assertTrue(CarpoolFederationUiLogic.showOptIn(CarpoolPostingType.OFFER, instanceEnabled = true, alreadyOptedIn = false))
        assertFalse(CarpoolFederationUiLogic.showOptIn(CarpoolPostingType.OFFER, instanceEnabled = false, alreadyOptedIn = false))
        assertFalse(CarpoolFederationUiLogic.showOptIn(CarpoolPostingType.REQUEST, instanceEnabled = true, alreadyOptedIn = false))
        assertFalse(CarpoolFederationUiLogic.showOptIn(null, instanceEnabled = true, alreadyOptedIn = true))
    }

    @Test
    fun anAlreadySharedOfferKeepsTheBoxEvenWithTheSwitchOffSoTheConsentCanBeWithdrawn() {
        assertTrue(CarpoolFederationUiLogic.showOptIn(CarpoolPostingType.OFFER, instanceEnabled = false, alreadyOptedIn = true))
        assertFalse(CarpoolFederationUiLogic.showOptIn(CarpoolPostingType.REQUEST, instanceEnabled = false, alreadyOptedIn = true))
    }

    @Test
    fun thePreviewShowsTheDateAndTheTwoAreasOfTheSharedProjection() {
        val teaser = CarpoolFederationUiLogic.preview("Hauptstr. 1, 38100 Braunschweig", "30159 Hannover", day)
        assertNotNull(teaser)
        assertEquals("38", teaser.fromPostcodeArea)
        assertEquals("30", teaser.toPostcodeArea)
        val text = CarpoolFederationUiLogic.previewText(teaser, day)
        assertTrue(text.contains("PLZ-Gebiet 38"))
        assertTrue(text.contains("PLZ-Gebiet 30"))
        assertFalse(text.contains("Hauptstr"), "no place text in the preview")
        assertFalse(text.contains("Braunschweig"), "no place text in the preview")
    }

    @Test
    fun withoutAPostcodeThePreviewSaysTheTripCannotBeShared() {
        val teaser = CarpoolFederationUiLogic.preview("Braunschweig", "30159 Hannover", day)
        assertNull(teaser)
        assertTrue(CarpoolFederationUiLogic.previewText(teaser, day).contains("keine Postleitzahl"))
        assertNull(CarpoolFederationUiLogic.preview("38100 A", "Hannover", null))
    }

    @Test
    fun theBoxCanOnlyBeUntickedWhenTheTripCannotBeShared() {
        val ok = CarpoolFederationUiLogic.preview("38100 A", "30159 B", day)
        assertTrue(CarpoolFederationUiLogic.checkboxEnabled(ok, currentlyChecked = false))
        assertFalse(CarpoolFederationUiLogic.checkboxEnabled(null, currentlyChecked = false))
        assertTrue(CarpoolFederationUiLogic.checkboxEnabled(null, currentlyChecked = true), "withdrawal must always be possible")
    }

    @Test
    fun aNewAndADuplicatedTripStartWithoutConsentAnEditedOneKeepsIt() {
        assertFalse(CarpoolFederationUiLogic.initialOptIn(null))
        assertFalse(CarpoolFederationUiLogic.initialOptIn(CarpoolFormPrefill(editing = null, duplicateFrom = dto(optIn = true))))
        assertTrue(CarpoolFederationUiLogic.initialOptIn(CarpoolFormPrefill(editing = dto(optIn = true), duplicateFrom = null)))
        assertFalse(CarpoolFederationUiLogic.initialOptIn(CarpoolFormPrefill(editing = dto(optIn = false), duplicateFrom = null)))
    }

    @Test
    fun theSecondCallIsMadeOnlyWhenTheConsentChanged() {
        assertFalse(CarpoolFederationUiLogic.mustCallSetOptIn(initial = false, wanted = false))
        assertTrue(CarpoolFederationUiLogic.mustCallSetOptIn(initial = false, wanted = true))
        assertTrue(CarpoolFederationUiLogic.mustCallSetOptIn(initial = true, wanted = false))
        assertFalse(CarpoolFederationUiLogic.mustCallSetOptIn(initial = true, wanted = true))
    }
}
