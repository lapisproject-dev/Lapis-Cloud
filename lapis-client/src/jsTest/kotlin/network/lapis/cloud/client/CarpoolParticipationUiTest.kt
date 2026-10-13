package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CarpoolMyParticipationRef
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** V1.9.125 -- the pure decisions of the carpool participation UI: badge wording and colour, the request button rule, the sidebar label. */
class CarpoolParticipationUiTest {
    private fun posting(
        own: Boolean = false,
        past: Boolean = false,
        free: Int? = 2,
        mine: CarpoolMyParticipationRef? = null,
        type: CarpoolPostingType = CarpoolPostingType.OFFER,
    ) = CarpoolPostingDto(
        id = "p",
        type = type,
        fromPlace = "A",
        toPlace = "B",
        departureDate = LocalDate(2099, 1, 1),
        departureTime = null,
        seatsOffered = if (type == CarpoolPostingType.OFFER) 3 else null,
        notes = null,
        authorDisplayName = "X",
        isOwn = own,
        isPast = past,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        seatsFree = free,
        myParticipation = mine,
    )

    private fun ref(
        status: CarpoolParticipationStatus,
        initiator: CarpoolParticipationInitiator,
    ) = CarpoolMyParticipationRef("r", status, initiator)

    @Test
    fun badge_waitingForWhom_dependsOnWhoStartedItAndWhoLooks() {
        val passengerAsked =
            CarpoolParticipationStatus.OPEN to CarpoolParticipationInitiator.PASSENGER
        assertEquals("warning", carpoolBadgeSpec(passengerAsked.first, passengerAsked.second, viewerIsDriver = true).color)
        assertEquals("secondary", carpoolBadgeSpec(passengerAsked.first, passengerAsked.second, viewerIsDriver = false).color)
        assertTrue(carpoolBadgeSpec(passengerAsked.first, passengerAsked.second, viewerIsDriver = false).label.contains("Fahrer"))
        val driverInvited = CarpoolParticipationStatus.OPEN to CarpoolParticipationInitiator.DRIVER
        assertEquals("warning", carpoolBadgeSpec(driverInvited.first, driverInvited.second, viewerIsDriver = false).color)
        assertTrue(carpoolBadgeSpec(driverInvited.first, driverInvited.second, viewerIsDriver = true).label.contains("Mitfahrer"))
    }

    @Test
    fun badge_neverUsesTheDangerColour_andEveryStateHasATextAndAnIcon() {
        for (status in CarpoolParticipationStatus.entries) {
            for (initiator in CarpoolParticipationInitiator.entries) {
                for (driver in listOf(true, false)) {
                    val spec = carpoolBadgeSpec(status, initiator, driver)
                    assertNotEquals("danger", spec.color)
                    assertTrue(spec.label.isNotBlank() && spec.icon.startsWith("fas fa-"), "$status/$initiator/$driver")
                }
            }
        }
    }

    @Test
    fun requestButton_onlyOnAForeignCurrentBookableOffer_andNotAfterTheDriversDecline() {
        assertTrue(carpoolMayRequestRide(posting()))
        assertFalse(carpoolMayRequestRide(posting(own = true)))
        assertFalse(carpoolMayRequestRide(posting(past = true)))
        assertFalse(carpoolMayRequestRide(posting(free = 0)))
        assertFalse(carpoolMayRequestRide(posting(type = CarpoolPostingType.REQUEST)))
        assertFalse(carpoolMayRequestRide(posting(mine = ref(CarpoolParticipationStatus.OPEN, CarpoolParticipationInitiator.PASSENGER))))
        assertFalse(
            carpoolMayRequestRide(posting(mine = ref(CarpoolParticipationStatus.CONFIRMED, CarpoolParticipationInitiator.PASSENGER))),
        )
        // Declined by the driver (the person had asked): final in the UI. Declined by the person themselves: may ask. Withdrawn: may ask again.
        assertFalse(
            carpoolMayRequestRide(posting(mine = ref(CarpoolParticipationStatus.DECLINED, CarpoolParticipationInitiator.PASSENGER))),
        )
        assertTrue(carpoolMayRequestRide(posting(mine = ref(CarpoolParticipationStatus.DECLINED, CarpoolParticipationInitiator.DRIVER))))
        assertTrue(
            carpoolMayRequestRide(posting(mine = ref(CarpoolParticipationStatus.WITHDRAWN, CarpoolParticipationInitiator.PASSENGER))),
        )
    }

    @Test
    fun seatsText_followsTheFreeSeats_andKeepsTheOldWordingForAnOldServer() {
        assertEquals("2 von 3 Plätzen frei", carpoolSeatsText(posting(free = 2)))
        assertEquals("Ausgebucht", carpoolSeatsText(posting(free = 0)))
        assertEquals("3 freie Plätze", carpoolSeatsText(posting(free = null)))
        assertEquals(null, carpoolSeatsText(posting(type = CarpoolPostingType.REQUEST, free = null)))
    }

    @Test
    fun sidebarLabel_isThePlainLabelWithoutAnythingWaiting() {
        assertEquals("${KV_I18N_MARKER}Mitfahrerzentrale", carpoolSidebarLabel(null))
        assertEquals("${KV_I18N_MARKER}Mitfahrerzentrale", carpoolSidebarLabel(0))
        assertEquals("Mitfahrerzentrale (3)", carpoolSidebarLabel(3))
        assertEquals("Mitfahrerzentrale (99+)", carpoolSidebarLabel(250))
    }
}
