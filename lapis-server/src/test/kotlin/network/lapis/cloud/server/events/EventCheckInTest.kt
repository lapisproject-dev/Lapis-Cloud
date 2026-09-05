package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCheckInOutcome
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [EventCheckIn]'s classification, one test
 * per [EventCheckInOutcome], plus the concurrency guarantee (`byCode`'s atomic UPDATE, see that
 * function's own KDoc step 7) and the negative proof that a check-in touches neither
 * `event.capacity`-derived occupancy nor the waitlist (see `39-events.kuml.kts` file header "Why
 * check-in does NOT go through EventCapacityGuard").
 */
class EventCheckInTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createActor(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventCheckInTest Tuersteher"
                    it[MemberTable.email] = "checkin-actor-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createEvent(
            capacity: Int? = null,
            createdBy: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "checkin-test-$id"
                    it[title] = "CheckIn-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        /** Directly crafts a registration row in an arbitrary state -- bypassing all business flow, so every [EventCheckInOutcome] branch is independently reachable regardless of which combinations the normal RPC/webhook flows actually produce today. */
        fun craftRegistration(
            eventId: Uuid,
            status: EventRegistrationStatus,
            rawTicketCode: String? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val activeKey =
                if (status in
                    setOf(EventRegistrationStatus.CANCELLED, EventRegistrationStatus.EXPIRED)
                ) {
                    null
                } else {
                    "g:checkin-$id@example.org"
                }
            transaction {
                EventStore.insertRegistration(
                    id = id,
                    eventId = eventId,
                    memberId = null,
                    guestName = "Checkin-Gast",
                    guestEmail = "checkin-$id@example.org",
                    activeParticipantKey = activeKey,
                    status = status,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = if (status == EventRegistrationStatus.PENDING_PAYMENT) now else null,
                    waitlistPosition = if (status == EventRegistrationStatus.WAITLISTED) 1 else null,
                    cancelTokenSha256 = "cancel-$id",
                    registeredAt = now,
                    confirmedAt = if (status == EventRegistrationStatus.CONFIRMED) now else null,
                    ticketCodeSha256 = rawTicketCode?.let { sha256Hex(it.toByteArray(Charsets.US_ASCII)) },
                    ticketIssuedAt = rawTicketCode?.let { now },
                )
            }
            return id
        }

        test("OK -- a CONFIRMED, ticketed registration checks in") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED, rawTicketCode = code)
            val result =
                transaction {
                    EventCheckIn.byCode(
                        eventId = eventId,
                        rawInput = code,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.OK
        }

        test("ALREADY_CHECKED_IN -- a second scan of the same ticket") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED, rawTicketCode = code)
            val now = DbClock.nowLocalDateTime()
            val first = transaction { EventCheckIn.byCode(eventId = eventId, rawInput = code, actorMemberId = actor, now = now) }
            first.outcome shouldBe EventCheckInOutcome.OK
            val second = transaction { EventCheckIn.byCode(eventId = eventId, rawInput = code, actorMemberId = actor, now = now) }
            second.outcome shouldBe EventCheckInOutcome.ALREADY_CHECKED_IN
            second.checkedInAt shouldBe now
        }

        test("WRONG_EVENT -- a ticket for a DIFFERENT event") {
            val actor = createActor()
            val eventA = createEvent(createdBy = actor)
            val eventB = createEvent(createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventA, status = EventRegistrationStatus.CONFIRMED, rawTicketCode = code)
            val result =
                transaction {
                    EventCheckIn.byCode(
                        eventId = eventB,
                        rawInput = code,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.WRONG_EVENT
        }

        test("NOT_CONFIRMED -- a non-CONFIRMED, non-CANCELLED status") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.WAITLISTED, rawTicketCode = code)
            val result =
                transaction {
                    EventCheckIn.byCode(
                        eventId = eventId,
                        rawInput = code,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.NOT_CONFIRMED
        }

        test("CANCELLED_REGISTRATION -- a ticket that was issued and later cancelled") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.CANCELLED, rawTicketCode = code)
            val result =
                transaction {
                    EventCheckIn.byCode(
                        eventId = eventId,
                        rawInput = code,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.CANCELLED_REGISTRATION
        }

        test("UNKNOWN_CODE -- a well-formed but non-existent code, without any DB write") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val fantasyCode = EventTicketPolicy.newRawCode()
            val result =
                transaction {
                    EventCheckIn.byCode(
                        eventId = eventId,
                        rawInput = fantasyCode,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.UNKNOWN_CODE
        }

        test("UNKNOWN_CODE -- a too-short/malformed code never reaches the database") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val result =
                transaction {
                    EventCheckIn.byCode(
                        eventId = eventId,
                        rawInput = "TOO-SHORT",
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.UNKNOWN_CODE
        }

        test("byRegistration checks in a CONFIRMED, already-ticketed row without the caller ever supplying a code") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            // chk_event_registration_checkin_ticket requires a ticket to already exist before any
            // check-in -- in production this is always true by the time `byRegistration` runs
            // (`EventService.openCheckIn`'s nachausstellung sweep issues one to every CONFIRMED row
            // first); the raw code itself is irrelevant here, `byRegistration` never sees it.
            val registrationId =
                craftRegistration(
                    eventId = eventId,
                    status = EventRegistrationStatus.CONFIRMED,
                    rawTicketCode = EventTicketPolicy.newRawCode(),
                )
            val result =
                transaction {
                    EventCheckIn.byRegistration(
                        eventId = eventId,
                        registrationId = registrationId,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.OK
        }

        test(
            "byRegistration mints a missing ticket on the fly instead of violating chk_event_registration_checkin_ticket (Security-Review LOW fix)",
        ) {
            // Before this fix, `EventStore.checkInIfNotCheckedIn`'s guard did not require a ticket,
            // so checking in a CONFIRMED row with NO ticket (e.g. reached via `byRegistration`
            // directly, without `IEventService.openCheckIn`'s own nachausstellung sweep having run
            // first) threw an unhandled ExposedSQLException straight out of the UPDATE --
            // `chk_event_registration_checkin_ticket` (`V19__event_tickets.sql`) forbids
            // `checked_in_at IS NOT NULL AND ticket_code_sha256 IS NULL`. `EventCheckIn
            // .classifyAndCheckIn` now calls `EventTicketIssuer.issueIfMissing` before attempting
            // the check-in, so this must succeed cleanly AND leave the row with a ticket.
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val registrationId = craftRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED, rawTicketCode = null)
            val before = transaction { EventStore.getRegistrationOrThrow(registrationId)[EventRegistrationTable.ticketCodeSha256] }
            before shouldBe null
            val result =
                transaction {
                    EventCheckIn.byRegistration(
                        eventId = eventId,
                        registrationId = registrationId,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.OK
            val after = transaction { EventStore.getRegistrationOrThrow(registrationId) }
            after[EventRegistrationTable.ticketCodeSha256] shouldNotBe null
            after[EventRegistrationTable.checkedInAt] shouldNotBe null
        }

        test("byRegistration rejects a registrationId belonging to a DIFFERENT event as WRONG_EVENT") {
            // Regression test for a review finding (MINOR): before eventId became a required
            // parameter here, this function derived eventId from the looked-up row itself, making
            // step 4's WRONG_EVENT check (see class KDoc "verbindliche Reihenfolge") a tautology on
            // this path -- it could never fire, since the row's own eventId trivially always equals
            // itself. Passing a caller-supplied eventId that genuinely differs from the row's own
            // must be rejected, exactly like byCode's own cross-event guard already is.
            val actor = createActor()
            val ownEventId = createEvent(createdBy = actor)
            val otherEventId = createEvent(createdBy = actor)
            val registrationId =
                craftRegistration(
                    eventId = otherEventId,
                    status = EventRegistrationStatus.CONFIRMED,
                    rawTicketCode = EventTicketPolicy.newRawCode(),
                )
            val result =
                transaction {
                    EventCheckIn.byRegistration(
                        eventId = ownEventId,
                        registrationId = registrationId,
                        actorMemberId = actor,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            result.outcome shouldBe EventCheckInOutcome.WRONG_EVENT
            val checkedInAt = transaction { EventStore.getRegistrationOrThrow(registrationId)[EventRegistrationTable.checkedInAt] }
            checkedInAt shouldBe null
        }

        test("a check-in never changes occupiedSeats or the waitlist") {
            val actor = createActor()
            val eventId = createEvent(capacity = 1, createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED, rawTicketCode = code)
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.WAITLISTED)
            val now = DbClock.nowLocalDateTime()
            val occupiedBefore = transaction { EventStore.countOccupied(eventId = eventId, now = now) }
            val waitlistedBefore = transaction { EventStore.countWaitlisted(eventId) }
            val result = transaction { EventCheckIn.byCode(eventId = eventId, rawInput = code, actorMemberId = actor, now = now) }
            result.outcome shouldBe EventCheckInOutcome.OK
            val occupiedAfter = transaction { EventStore.countOccupied(eventId = eventId, now = now) }
            val waitlistedAfter = transaction { EventStore.countWaitlisted(eventId) }
            occupiedAfter shouldBe occupiedBefore
            waitlistedAfter shouldBe waitlistedBefore
        }

        test("two concurrent check-ins on the SAME ticket: exactly one OK, exactly one ALREADY_CHECKED_IN") {
            val actor = createActor()
            val eventId = createEvent(createdBy = actor)
            val code = EventTicketPolicy.newRawCode()
            craftRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED, rawTicketCode = code)

            val startLatch = CountDownLatch(2)
            val doneLatch = CountDownLatch(2)
            val outcomes = Collections.synchronizedList(mutableListOf<EventCheckInOutcome>())

            fun checkInThread() =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        val outcome =
                            transaction {
                                EventCheckIn
                                    .byCode(
                                        eventId = eventId,
                                        rawInput = code,
                                        actorMemberId = actor,
                                        now = DbClock.nowLocalDateTime(),
                                    ).outcome
                            }
                        outcomes += outcome
                    } finally {
                        doneLatch.countDown()
                    }
                }

            val t1 = checkInThread()
            val t2 = checkInThread()
            t1.start()
            t2.start()
            check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent check-in attempts did not complete in time" }

            outcomes.count { it == EventCheckInOutcome.OK } shouldBe 1
            outcomes.count { it == EventCheckInOutcome.ALREADY_CHECKED_IN } shouldBe 1
        }
    })
