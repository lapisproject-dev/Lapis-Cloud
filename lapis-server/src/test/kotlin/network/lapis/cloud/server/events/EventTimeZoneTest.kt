package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.routes.loadPublicNavAvailability
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.38 -- the two clocks around events. `starts_at`/`ends_at` are class-B wall-clocks of the organization zone and are compared
 * with the organization-zone wall-clock; `hold_expires_at` is a class-A UTC stamp (now + hold) and is compared with the UTC
 * system clock. One caller (`submit`) uses BOTH in one call, which is the trap the plan names (S1).
 */
class EventTimeZoneTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        beforeTest { TimeTestSupport.resetOrganizationZone() }
        afterSpec {
            TimeTestSupport.resetOrganizationZone()
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun member(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventTimeZoneTest"
                    it[email] = "event-tz-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun event(
            startsAt: LocalDateTime,
            endsAt: LocalDateTime,
            capacity: Int? = null,
        ): Uuid {
            val organizer = member()
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "event-tz-test-$id"
                    it[title] = "Zeitzonen-Test"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun submission() =
            EventRegistrationSubmission(
                checkoutGateways = emptyMap(),
                baseUrl = "https://example.org",
                mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            )

        fun guest() = EventParticipant.Guest(name = "Gast", normalizedEmail = "gast-${Uuid.random()}@example.org")

        // Event "2026-07-01 20:00-22:00" as typed in Berlin = 18:00Z-20:00Z.
        val startsAt = LocalDateTime(2026, 7, 1, 20, 0)
        val endsAt = LocalDateTime(2026, 7, 1, 22, 0)

        test("registration is open until the event's own Berlin start, not until 20:00 UTC") {
            val id = event(startsAt, endsAt)
            // 17:59:00Z = 19:59 Berlin: open
            submission()
                .submit(
                    eventId = id,
                    participant = guest(),
                    now = LocalDateTime(2026, 7, 1, 17, 59),
                ).shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
            // 18:01:00Z = 20:01 Berlin: the event has started. (Before V1.9.38 this compared 18:01 with 20:00 and stayed open for two more hours.)
            submission().submit(eventId = id, participant = guest(), now = LocalDateTime(2026, 7, 1, 18, 1)) shouldBe
                EventRegistrationResult.EventNotAvailable
        }

        test("EventPolicy.isRegistrationOpen compares wall-clock with wall-clock") {
            EventPolicy.isRegistrationOpen(
                status = EventStatus.PUBLISHED,
                registrationClosesAt = null,
                startsAt = startsAt,
                wallNow = LocalDateTime(2026, 7, 1, 19, 59),
            ) shouldBe
                true
            EventPolicy.isRegistrationOpen(
                status = EventStatus.PUBLISHED,
                registrationClosesAt = null,
                startsAt = startsAt,
                wallNow = LocalDateTime(2026, 7, 1, 20, 0),
            ) shouldBe
                false
        }

        fun eventWithHold(holdExpiresAt: LocalDateTime): Uuid {
            val id = event(startsAt, endsAt, capacity = 1)
            val holdOwner = Uuid.random()
            transaction {
                EventStore.insertRegistration(
                    id = holdOwner,
                    eventId = id,
                    memberId = null,
                    guestName = "Halter",
                    guestEmail = "halter-$holdOwner@example.org",
                    activeParticipantKey = "g:halter-$holdOwner@example.org",
                    status = EventRegistrationStatus.PENDING_PAYMENT,
                    feeAmount = BigDecimal("10.00"),
                    holdExpiresAt = holdExpiresAt, // class A: a UTC stamp
                    waitlistPosition = null,
                    cancelTokenSha256 = null,
                    registeredAt = LocalDateTime(2026, 7, 1, 17, 0),
                    confirmedAt = null,
                )
            }
            return id
        }

        test("an unexpired payment hold (class A, UTC) occupies the only seat while the event (class B, Berlin) is open") {
            val id = eventWithHold(holdExpiresAt = LocalDateTime(2026, 7, 1, 17, 30))
            // 17:29Z: hold still valid -> the seat is taken -> waitlisted (event open: 19:29 Berlin)
            submission()
                .submit(
                    eventId = id,
                    participant = guest(),
                    now = LocalDateTime(2026, 7, 1, 17, 29),
                ).shouldBeInstanceOf<EventRegistrationResult.Waitlisted>()
        }

        test("an expired payment hold frees the seat, again while the event is open -- both clocks in ONE call") {
            val id = eventWithHold(holdExpiresAt = LocalDateTime(2026, 7, 1, 17, 30))
            // 17:31Z: hold expired (UTC) -> seat free -> confirmed; the event is open at the same instant (19:31 Berlin)
            submission()
                .submit(
                    eventId = id,
                    participant = guest(),
                    now = LocalDateTime(2026, 7, 1, 17, 31),
                ).shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
        }

        test("EventStore.list hides an event once its Berlin end has passed") {
            val id = event(startsAt, endsAt)

            fun listed(wallNow: LocalDateTime) =
                transaction {
                    EventStore.list(status = EventStatus.PUBLISHED, includePast = false, wallNow = wallNow, limit = 200, offset = 0).first
                }.any { it[EventTable.id] == id }
            listed(LocalDateTime(2026, 7, 1, 21, 59, 59)) shouldBe true
            listed(LocalDateTime(2026, 7, 1, 22, 0, 1)) shouldBe false
        }

        test(
            "the public navigation flag follows the real clock through the organization zone: an event ending 22:00 Berlin is upcoming at 19:59Z, over at 20:01Z",
        ) {
            val id = event(startsAt, endsAt)
            TimeTestSupport.withServerClock(instant = "2026-07-01T19:59:00Z") { loadPublicNavAvailability().events shouldBe true }
            TimeTestSupport.withServerClock(instant = "2026-07-01T20:01:00Z") {
                // other tests' events may still be upcoming; this event is the one under test, so look at it directly
                transaction {
                    EventStore
                        .list(
                            status = EventStatus.PUBLISHED,
                            includePast = false,
                            wallNow =
                                network.lapis.cloud.server.time.OrganizationTimeZone
                                    .wallNow(),
                            limit = 200,
                            offset = 0,
                        ).first
                }.none { it[EventTable.id] == id } shouldBe true
            }
        }
    })
