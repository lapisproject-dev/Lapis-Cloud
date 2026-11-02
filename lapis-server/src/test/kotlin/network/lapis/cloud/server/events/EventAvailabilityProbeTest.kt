package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.routes.CountingStripe
import network.lapis.cloud.server.routes.EventPublicMemberFixtures
import network.lapis.cloud.server.routes.RecordingMail
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import java.math.BigDecimal
import kotlin.uuid.Uuid

class EventAvailabilityProbeTest :
    FunSpec({
        val fixtures = EventPublicMemberFixtures()
        val mail = RecordingMail()

        beforeSpec { DatabaseConfig.connect() }
        afterTest { fixtures.disableGateway() }
        afterSpec { fixtures.cleanup() }

        fun submission(withGateway: Boolean = false): EventRegistrationSubmission =
            EventRegistrationSubmission(
                checkoutGateways = if (withGateway) mapOf(PaymentProvider.STRIPE to CountingStripe().client) else emptyMap(),
                baseUrl = "https://example.org",
                mailDispatcher = mail.dispatcher,
            )

        test("an open event is Open") {
            val (id, _) = fixtures.createEvent()
            submission().probeAvailability(eventId = id) shouldBe EventRegistrationSubmission.Availability.Open
        }

        test("an unknown event, an unpublished one and one that is over are NotAvailable") {
            submission().probeAvailability(eventId = Uuid.random()) shouldBe EventRegistrationSubmission.Availability.NotAvailable
            val (draft, _) = fixtures.createEvent(status = EventStatus.DRAFT)
            submission().probeAvailability(eventId = draft) shouldBe EventRegistrationSubmission.Availability.NotAvailable
            val (over, _) =
                fixtures.createEvent(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 20, 0))
            submission().probeAvailability(eventId = over) shouldBe EventRegistrationSubmission.Availability.NotAvailable
        }

        test("a paid event without a usable gateway is GatewayUnavailable, with one it is Open") {
            val (id, _) = fixtures.createEvent(fee = BigDecimal("10.00"))
            submission().probeAvailability(eventId = id) shouldBe EventRegistrationSubmission.Availability.GatewayUnavailable
            fixtures.enableGateway()
            submission(withGateway = true).probeAvailability(eventId = id) shouldBe EventRegistrationSubmission.Availability.Open
        }

        test("a full event is Open while the waitlist has room, WaitlistFull once it is full") {
            val (id, _) = fixtures.createEvent(capacity = 1)
            fixtures.insertRegistration(eventId = id, guestEmail = "holder-${Uuid.random()}@example.org")
            submission().probeAvailability(eventId = id) shouldBe EventRegistrationSubmission.Availability.Open
            for (position in 1..EventPolicy.MAX_WAITLIST) {
                fixtures.insertRegistration(
                    eventId = id,
                    guestEmail = "wait-$position-${Uuid.random()}@example.org",
                    status = EventRegistrationStatus.WAITLISTED,
                    waitlistPosition = position,
                )
            }
            submission().probeAvailability(eventId = id) shouldBe EventRegistrationSubmission.Availability.WaitlistFull
        }

        test("the probe is read-only: it places nothing and sends nothing") {
            val (id, _) = fixtures.createEvent()
            submission().probeAvailability(eventId = id)
            fixtures.registrationCount(id) shouldBe 0
            mail.settle().size shouldBe 0
        }
    })
