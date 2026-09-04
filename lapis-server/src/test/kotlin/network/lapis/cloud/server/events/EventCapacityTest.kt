package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- **the single most important test of this wave**
 * (design-team plan §10, `EventCapacityTest`). Proves `EventCapacityGuard.withEventLock`'s `event`
 * row lock actually serializes two concurrent registration attempts for a `capacity = 1` event:
 * exactly one seat is granted, the other registrant is waitlisted -- never two seats, never zero.
 * Same real-thread-race idiom `SepaServiceTest`'s "concurrent-grant guard" already establishes.
 */
class EventCapacityTest :
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

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventCapacityTest Organisator"
                    it[MemberTable.email] = email
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

        // Far in the future -- EventPolicy.isRegistrationOpen requires `now < startsAt`; using the
        // real DbClock.nowLocalDateTime() for BOTH the event's own startsAt AND every submit()
        // call's `now` would make the event count as "already started" the instant a few
        // milliseconds pass between fixture creation and the actual registration attempt.
        val farFutureStartsAt = kotlinx.datetime.LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = kotlinx.datetime.LocalDateTime(2030, 1, 1, 22, 0)

        fun createCapacityOneFreeEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "capacity-test-$id"
                    it[title] = "Capacity-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[capacity] = 1
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = now
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        test(
            "two concurrent guest registrations for a capacity=1 free event: exactly one CONFIRMED, one WAITLISTED, never both CONFIRMED",
        ) {
            val organizer = createTestMember("event-capacity-organizer-${Uuid.random()}@example.org")
            val eventId = createCapacityOneFreeEvent(organizer)

            val submission =
                EventRegistrationSubmission(
                    pspConfigState = PspConfigState.NotConfigured,
                    checkoutClient = null,
                    baseUrl = "https://example.org",
                    mailDispatcher =
                        MailDispatcher(
                            transport = NoOpMailTransport(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        ),
                )

            val startLatch = CountDownLatch(2)
            val doneLatch = CountDownLatch(2)
            val results = Collections.synchronizedList(mutableListOf<EventRegistrationResult>())

            fun registerThread(guestEmail: String) =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        val result =
                            runBlocking {
                                submission.submit(
                                    eventId = eventId,
                                    participant = EventParticipant.Guest(name = "Racer", normalizedEmail = guestEmail),
                                )
                            }
                        results += result
                    } finally {
                        doneLatch.countDown()
                    }
                }

            val t1 = registerThread("racer-1-${Uuid.random()}@example.org")
            val t2 = registerThread("racer-2-${Uuid.random()}@example.org")
            t1.start()
            t2.start()
            check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent registration attempts did not complete in time" }

            results.count { it is EventRegistrationResult.Confirmed } shouldBe 1
            results.count { it is EventRegistrationResult.Waitlisted } shouldBe 1

            // DB-level confirmation: exactly one occupied seat, exactly one waitlisted row.
            val confirmedCount =
                transaction {
                    EventRegistrationTable
                        .selectAll()
                        .where {
                            (EventRegistrationTable.eventId eq eventId) and
                                (EventRegistrationTable.status eq EventRegistrationStatus.CONFIRMED)
                        }.count()
                }
            val waitlistedCount =
                transaction {
                    EventRegistrationTable
                        .selectAll()
                        .where {
                            (EventRegistrationTable.eventId eq eventId) and
                                (EventRegistrationTable.status eq EventRegistrationStatus.WAITLISTED)
                        }.count()
                }
            confirmedCount shouldBe 1L
            waitlistedCount shouldBe 1L
        }

        test("a duplicate guest registration for the same event is rejected as AlreadyRegistered, not a second row") {
            val organizer = createTestMember("event-capacity-organizer-dup-${Uuid.random()}@example.org")
            val eventId = createCapacityOneFreeEvent(organizer)
            transaction { EventTable.update({ EventTable.id eq eventId }) { it[capacity] = null } }

            val submission =
                EventRegistrationSubmission(
                    pspConfigState = PspConfigState.NotConfigured,
                    checkoutClient = null,
                    baseUrl = "https://example.org",
                    mailDispatcher =
                        MailDispatcher(
                            transport = NoOpMailTransport(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        ),
                )
            val email = "dup-${Uuid.random()}@example.org"

            val first =
                runBlocking {
                    submission.submit(
                        eventId = eventId,
                        participant = EventParticipant.Guest(name = "Dup", normalizedEmail = email),
                    )
                }
            val second =
                runBlocking {
                    submission.submit(
                        eventId = eventId,
                        participant = EventParticipant.Guest(name = "Dup", normalizedEmail = email),
                    )
                }

            (first is EventRegistrationResult.Confirmed) shouldBe true
            second shouldBe EventRegistrationResult.AlreadyRegistered

            val rowCount =
                transaction {
                    EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq eventId }.count()
                }
            rowCount shouldBe 1L
        }
    })
