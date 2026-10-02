package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal val EVENT_NOW = LocalDateTime(2026, 10, 2, 12, 0)

internal fun testEvent(
    id: String = "e1",
    title: String = "Sommerfest",
    startsAt: LocalDateTime = LocalDateTime(2026, 11, 1, 18, 0),
    endsAt: LocalDateTime = LocalDateTime(2026, 11, 1, 22, 0),
    status: EventStatus = EventStatus.PUBLISHED,
    fee: Double = 0.0,
    full: Boolean = false,
    own: EventRegistrationStatus? = null,
    registrationClosesAt: LocalDateTime? = null,
    locationText: String? = "Gemeindehaus",
    onlineUrl: String? = null,
) = EventDto(
    id = id,
    slug = "slug-$id",
    title = title,
    description = "Beschreibung",
    locationText = locationText,
    onlineUrl = onlineUrl,
    startsAt = startsAt,
    endsAt = endsAt,
    capacity = null,
    feeAmount = fee.toDecimal(),
    feeCurrency = "EUR",
    status = status,
    visibility = EventVisibility.MEMBERS_ONLY,
    registrationClosesAt = registrationClosesAt,
    occupiedSeats = 0,
    waitlistCount = 0,
    full = full,
    feeEditable = true,
    ownRegistrationStatus = own,
    publicUrl = null,
)

/** Welle V1.9.33 -- the pure decisions behind the member event screen. */
class MemberEventActionTest {
    @Test
    fun aFreeOpenEvent_offersRegister() {
        assertEquals(MemberEventAction.Register, memberEventAction(testEvent(), EVENT_NOW))
    }

    @Test
    fun aFeeBearingEvent_offersRegisterAndPay_withTheFormattedAmount() {
        val action = memberEventAction(testEvent(fee = 12.0), EVENT_NOW)
        assertEquals(MemberEventAction.RegisterAndPay(formatMoney(12.0)), action)
    }

    @Test
    fun aFullEvent_offersTheWaitlist_evenWithAFee() {
        assertEquals(MemberEventAction.Waitlist, memberEventAction(testEvent(full = true, fee = 12.0), EVENT_NOW))
    }

    @Test
    fun anActiveOwnRegistration_leavesNothingToRegister() {
        listOf(EventRegistrationStatus.CONFIRMED, EventRegistrationStatus.WAITLISTED, EventRegistrationStatus.PENDING_PAYMENT).forEach {
            assertEquals(MemberEventAction.None, memberEventAction(testEvent(own = it), EVENT_NOW), it.name)
            assertTrue(canCancelOwn(testEvent(own = it)), it.name)
        }
    }

    @Test
    fun aCancelledOrExpiredRegistration_mayRegisterAgain() {
        listOf(EventRegistrationStatus.CANCELLED, EventRegistrationStatus.EXPIRED).forEach {
            assertEquals(MemberEventAction.Register, memberEventAction(testEvent(own = it), EVENT_NOW), it.name)
            assertFalse(canCancelOwn(testEvent(own = it)), it.name)
        }
        assertFalse(canCancelOwn(testEvent()))
    }

    @Test
    fun registrationCloses_withTheStart_andWithRegistrationClosesAt() {
        val running = testEvent(startsAt = LocalDateTime(2026, 10, 2, 11, 0), endsAt = LocalDateTime(2026, 10, 2, 14, 0))
        assertEquals(MemberEventAction.None, memberEventAction(running, EVENT_NOW))
        assertEquals(MemberEventAction.None, memberEventAction(testEvent(startsAt = EVENT_NOW), EVENT_NOW))
        assertEquals(
            MemberEventAction.None,
            memberEventAction(testEvent(registrationClosesAt = LocalDateTime(2026, 10, 2, 11, 59)), EVENT_NOW),
        )
        assertEquals(MemberEventAction.Register, memberEventAction(testEvent(registrationClosesAt = EVENT_NOW), EVENT_NOW))
    }

    @Test
    fun onlyAbsoluteHttpsUrlsWithoutCredentialsAreFollowed() {
        assertTrue(isSafeHttpsRedirect("https://pay.example.org/checkout?x=1"))
        listOf(
            "http://pay.example.org/",
            "javascript:alert(1)",
            "data:text/html,x",
            "//evil.example",
            "/relative/path",
            "https://user:pw@pay.example.org/",
            "https://user@pay.example.org/",
            "",
            "not a url",
            "HTTP://pay.example.org",
        ).forEach { assertFalse(isSafeHttpsRedirect(it), it) }
    }
}
