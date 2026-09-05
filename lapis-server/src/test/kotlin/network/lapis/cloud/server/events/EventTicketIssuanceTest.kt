package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val ADMIN_UUID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [EventTicketIssuer]/[EventStore]'s
 * ticket-writing functions, exercised directly against manually-inserted fixture rows (rather than
 * through the full [EventRegistrationSubmission] flow, which would additionally require mocking
 * Stripe for the paid path -- out of scope for what this file tests). The three CONFIRMED-transition
 * call sites themselves (free registration, free waitlist promotion, paid webhook confirmation)
 * each mint a ticket the SAME way [EventTicketIssuer.mint]/`EventStore.issueTicketIfMissing`/
 * `.confirmRegistrationIfPendingAndIssueTicket`/`.promoteToConfirmedDirectly` do here -- this file's
 * job is proving those primitives themselves are correct in isolation.
 */
class EventTicketIssuanceTest :
    FunSpec({
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
            }
        }

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createEvent(): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "ticket-issuance-test-$id"
                    it[title] = "Ticket-Issuance-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = ADMIN_UUID
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun insertRegistration(
            eventId: Uuid,
            status: EventRegistrationStatus,
            ticketCodeSha256: String? = null,
            ticketIssuedAt: LocalDateTime? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            // chk_event_registration_active_key (V18__events.sql): the key is NULL iff status is
            // CANCELLED/EXPIRED, non-NULL otherwise -- see EventMigrationTest's own probes.
            val activeKey =
                if (status in setOf(EventRegistrationStatus.CANCELLED, EventRegistrationStatus.EXPIRED)) null else "g:gast-$id@example.org"
            transaction {
                EventStore.insertRegistration(
                    id = id,
                    eventId = eventId,
                    memberId = null,
                    guestName = "Gast",
                    guestEmail = "gast-$id@example.org",
                    activeParticipantKey = activeKey,
                    status = status,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = if (status == EventRegistrationStatus.PENDING_PAYMENT) now else null,
                    waitlistPosition = if (status == EventRegistrationStatus.WAITLISTED) 1 else null,
                    cancelTokenSha256 = "cancel-$id",
                    registeredAt = now,
                    confirmedAt = if (status == EventRegistrationStatus.CONFIRMED) now else null,
                    ticketCodeSha256 = ticketCodeSha256,
                    ticketIssuedAt = ticketIssuedAt,
                )
            }
            return id
        }

        test("issueIfMissing mints a ticket for a CONFIRMED registration without one") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED)
            val issued =
                transaction { EventTicketIssuer.issueIfMissing(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            issued.shouldNotBeNull()
            val row = transaction { EventStore.getRegistrationOrThrow(registrationId) }
            row[EventRegistrationTable.ticketCodeSha256] shouldBe issued.sha256
            row[EventRegistrationTable.ticketIssuedAt] shouldNotBe null
        }

        test("issueIfMissing is idempotent -- a second call on an already-ticketed row is a no-op") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED)
            val first = transaction { EventTicketIssuer.issueIfMissing(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            first.shouldNotBeNull()
            val second = transaction { EventTicketIssuer.issueIfMissing(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            second.shouldBeNull()
            val row = transaction { EventStore.getRegistrationOrThrow(registrationId) }
            row[EventRegistrationTable.ticketCodeSha256] shouldBe first.sha256
        }

        test("issueIfMissing does nothing for a PENDING_PAYMENT registration") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.PENDING_PAYMENT)
            val issued =
                transaction { EventTicketIssuer.issueIfMissing(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            issued.shouldBeNull()
            val row = transaction { EventStore.getRegistrationOrThrow(registrationId) }
            row[EventRegistrationTable.ticketCodeSha256] shouldBe null
        }

        test("reissue rotates the code -- the old hash no longer resolves") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED)
            val original =
                transaction { EventTicketIssuer.issueIfMissing(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            original.shouldNotBeNull()
            val rotated = transaction { EventTicketIssuer.reissue(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            rotated.shouldNotBeNull()
            rotated.sha256 shouldNotBe original.sha256
            transaction { EventStore.findByTicketCodeHash(original.sha256) }.shouldBeNull()
            transaction { EventStore.findByTicketCodeHash(rotated.sha256) }.shouldNotBeNull()
        }

        test("reissue does nothing for a non-CONFIRMED registration") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.WAITLISTED)
            val rotated = transaction { EventTicketIssuer.reissue(registrationId = registrationId, now = DbClock.nowLocalDateTime()) }
            rotated.shouldBeNull()
        }

        test(
            "issueTicketIfMissing/rotateTicket throw ExposedSQLException (SQLSTATE 23505) on an " +
                "actual uq_event_registration_ticket_code collision, they do not just return 0",
        ) {
            // Regression test for a review finding (MINOR): EventTicketIssuer.mintWithRetry's own
            // KDoc/log message used to claim it "retries on a unique-index collision", but the only
            // branch that existed (affected == 0) can NEVER be reached by an actual collision on this
            // plain guarded UPDATE -- it throws instead, straight out of mintWithRetry, aborting the
            // whole caller transaction rather than retrying. This test proves the premise the fix
            // relies on: a real collision on this unique index is a thrown ExposedSQLException with
            // SQLSTATE 23505 (H2, this codebase's test dialect, same SQLSTATE Postgres also uses),
            // not a return value of 0.
            val eventId = createEvent()
            val existingHash = "collision-hash-${Uuid.random()}"
            insertRegistration(
                eventId = eventId,
                status = EventRegistrationStatus.CONFIRMED,
                ticketCodeSha256 = existingHash,
                ticketIssuedAt = DbClock.nowLocalDateTime(),
            )
            val targetId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED)

            val exception =
                shouldThrow<ExposedSQLException> {
                    transaction {
                        EventStore.issueTicketIfMissing(id = targetId, ticketCodeSha256 = existingHash, now = DbClock.nowLocalDateTime())
                    }
                }
            exception.sqlState shouldBe "23505"

            // rotateTicket's own guarded UPDATE hits the exact same unique index.
            val rotateTargetId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED)
            val rotateException =
                shouldThrow<ExposedSQLException> {
                    transaction {
                        EventStore.rotateTicket(id = rotateTargetId, ticketCodeSha256 = existingHash, now = DbClock.nowLocalDateTime())
                    }
                }
            rotateException.sqlState shouldBe "23505"
        }

        test("listConfirmedWithoutTicket returns only CONFIRMED rows without a ticket") {
            val eventId = createEvent()
            val withoutTicket = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CONFIRMED)
            val withTicket =
                insertRegistration(
                    eventId = eventId,
                    status = EventRegistrationStatus.CONFIRMED,
                    ticketCodeSha256 = "already-ticketed",
                    ticketIssuedAt = DbClock.nowLocalDateTime(),
                )
            insertRegistration(eventId = eventId, status = EventRegistrationStatus.PENDING_PAYMENT)
            val ids = transaction { EventTicketIssuer.listConfirmedWithoutTicket(eventId).map { it[EventRegistrationTable.id] } }
            ids shouldBe listOf(withoutTicket)
            ids.contains(withTicket) shouldBe false
        }

        test("confirmRegistrationIfPendingAndIssueTicket flips status and writes the ticket atomically") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.PENDING_PAYMENT)
            val ticket = EventTicketIssuer.mint()
            val affected =
                transaction {
                    EventStore.confirmRegistrationIfPendingAndIssueTicket(
                        id = registrationId,
                        ticketCodeSha256 = ticket.sha256,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            affected shouldBe 1
            val row = transaction { EventStore.getRegistrationOrThrow(registrationId) }
            row[EventRegistrationTable.status] shouldBe EventRegistrationStatus.CONFIRMED
            row[EventRegistrationTable.ticketCodeSha256] shouldBe ticket.sha256
        }

        test("confirmRegistrationIfPendingAndIssueTicket is a guarded no-op for an already-CANCELLED row") {
            val eventId = createEvent()
            val registrationId = insertRegistration(eventId = eventId, status = EventRegistrationStatus.CANCELLED)
            val ticket = EventTicketIssuer.mint()
            val affected =
                transaction {
                    EventStore.confirmRegistrationIfPendingAndIssueTicket(
                        id = registrationId,
                        ticketCodeSha256 = ticket.sha256,
                        now = DbClock.nowLocalDateTime(),
                    )
                }
            affected shouldBe 0
        }
    })
