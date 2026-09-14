package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.server.db.generated.EventVolunteerSignupTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.EventVolunteerSignupStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- direct unit coverage of
 * [EventVolunteerCapacityGuard.assertCapacityAvailable], mirroring
 * [EventRoomCollisionGuardTest]'s own house style: capacity-free/full/cancelled checks, and the
 * "single most important test" real-thread race idiom proving the `FOR UPDATE` lock on
 * `event_volunteer_shift` actually serializes two concurrent signup attempts on a
 * `neededCount = 1` shift.
 */
class EventVolunteerCapacityGuardTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdShiftIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdShiftIds.isNotEmpty()) {
                    EventVolunteerSignupTable.deleteWhere { shiftId inList createdShiftIds }
                    EventVolunteerShiftTable.deleteWhere { id inList createdShiftIds }
                }
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(role: AccountRole = AccountRole.MEMBER): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventVolunteerCapacityGuardTest Mitglied"
                    it[email] = "eventvolunteercapacity-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "volunteer-capacity-test-$id"
                    it[title] = "Volunteer-Capacity-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = now
                    it[endsAt] = now
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = EventStatus.PUBLISHED
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

        fun createShift(
            eventId: Uuid,
            createdBy: Uuid,
            neededCount: Int,
            status: EventVolunteerShiftStatus = EventVolunteerShiftStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                EventVolunteerShiftTable.insert {
                    it[EventVolunteerShiftTable.id] = id
                    it[EventVolunteerShiftTable.eventId] = eventId
                    it[description] = "Shift-$id"
                    it[startsAt] = now
                    it[endsAt] = LocalDateTime(2026, 1, 1, 23, 59)
                    it[EventVolunteerShiftTable.neededCount] = neededCount
                    it[EventVolunteerShiftTable.status] = status
                    it[EventVolunteerShiftTable.createdAt] = now
                    it[EventVolunteerShiftTable.createdBy] = createdBy
                }
            }
            createdShiftIds += id
            return id
        }

        fun confirmSignup(
            shiftId: Uuid,
            memberId: Uuid,
        ) {
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                EventVolunteerSignupTable.insert {
                    it[id] = Uuid.random()
                    it[EventVolunteerSignupTable.shiftId] = shiftId
                    it[EventVolunteerSignupTable.memberId] = memberId
                    it[status] = EventVolunteerSignupStatus.CONFIRMED
                    it[signedUpAt] = now
                    it[cancelledAt] = null
                    it[activeMemberKey] = memberId.toString()
                }
            }
        }

        test("capacity available on a fresh shift with no signups") {
            val organizer = createMember(AccountRole.BOARD)
            val eventId = createEvent(organizer)
            val shiftId = createShift(eventId, organizer, neededCount = 2)
            transaction { EventVolunteerCapacityGuard.assertCapacityAvailable(shiftId) }
        }

        test("a full shift (confirmedCount >= neededCount) is rejected") {
            val organizer = createMember(AccountRole.BOARD)
            val eventId = createEvent(organizer)
            val shiftId = createShift(eventId, organizer, neededCount = 1)
            val volunteer = createMember()
            confirmSignup(shiftId, volunteer)
            transaction {
                shouldThrow<ConflictException> { EventVolunteerCapacityGuard.assertCapacityAvailable(shiftId) }
            }
        }

        test("a CANCELLED shift is rejected regardless of capacity") {
            val organizer = createMember(AccountRole.BOARD)
            val eventId = createEvent(organizer)
            val shiftId = createShift(eventId, organizer, neededCount = 5, status = EventVolunteerShiftStatus.CANCELLED)
            transaction {
                shouldThrow<ConflictException> { EventVolunteerCapacityGuard.assertCapacityAvailable(shiftId) }
            }
        }

        // ── Race guard: the `FOR UPDATE` row lock on `event_volunteer_shift` must actually
        // serialize two concurrent signup attempts on the SAME `neededCount = 1` shift -- mirrors
        // EventRoomCollisionGuardTest's/EventCapacityTest's own real-thread race idiom.

        test("two concurrent signups on a neededCount=1 shift -- only one succeeds, the other sees a real conflict") {
            val organizer = createMember(AccountRole.BOARD)
            val eventId = createEvent(organizer)
            val shiftId = createShift(eventId, organizer, neededCount = 1)
            val volunteer1 = createMember()
            val volunteer2 = createMember()

            val startLatch = CountDownLatch(2)
            val doneLatch = CountDownLatch(2)
            val results = Collections.synchronizedList(mutableListOf<Result<Unit>>())

            fun signupThread(memberId: Uuid) =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        val outcome =
                            runCatching {
                                transaction {
                                    EventVolunteerCapacityGuard.assertCapacityAvailable(shiftId)
                                    confirmSignup(shiftId, memberId)
                                }
                            }
                        results += outcome
                    } finally {
                        doneLatch.countDown()
                    }
                }

            val t1 = signupThread(volunteer1)
            val t2 = signupThread(volunteer2)
            t1.start()
            t2.start()
            check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent signup attempts did not complete in time" }

            results.count { it.isSuccess } shouldBe 1
            results.count { it.isFailure } shouldBe 1
            (results.single { it.isFailure }.exceptionOrNull() is ConflictException) shouldBe true

            // DB-level confirmation: exactly one signup actually landed.
            val confirmedCount =
                transaction {
                    EventVolunteerSignupTable
                        .selectAll()
                        .where {
                            (EventVolunteerSignupTable.shiftId eq shiftId) and
                                (EventVolunteerSignupTable.status eq EventVolunteerSignupStatus.CONFIRMED)
                        }.count()
                }
            confirmedCount shouldBe 1L
        }
    })
