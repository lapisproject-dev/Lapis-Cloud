package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
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
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- direct unit coverage of
 * [EventRoomCollisionGuard.assertNoOverlap], exercising the overlap predicate (`existing.startsAt
 * < newEnd AND existing.endsAt > newStart`) named in the plan's own testplan section: exact
 * overlap, partial overlap, edge-to-edge (no conflict), CANCELLED events never block, and a
 * self-update (same event excluded) never conflicts with itself.
 */
class EventRoomCollisionGuardTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdRoomIds.isNotEmpty()) EventRoomTable.deleteWhere { id inList createdRoomIds }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventRoomCollisionGuardTest Mitglied"
                    it[email] = "eventroomcollision-${Uuid.random()}@example.org"
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

        fun createRoom(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                EventRoomTable.insert {
                    it[EventRoomTable.id] = id
                    it[name] = "Room-$id"
                    it[capacity] = null
                    it[equipmentTags] = ""
                    it[status] = EventRoomStatus.ACTIVE
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[EventRoomTable.createdBy] = createdBy
                }
            }
            createdRoomIds += id
            return id
        }

        fun createEvent(
            createdBy: Uuid,
            roomId: Uuid?,
            startsAt: LocalDateTime,
            endsAt: LocalDateTime,
            status: EventStatus = EventStatus.PUBLISHED,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "room-collision-test-$id"
                    it[title] = "Room-Collision-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                    it[EventTable.roomId] = roomId
                }
            }
            createdEventIds += id
            return id
        }

        test("exact overlap on the same window is rejected") {
            val member = createMember()
            val room = createRoom(member)
            createEvent(member, room, LocalDateTime(2030, 6, 1, 18, 0), LocalDateTime(2030, 6, 1, 20, 0))
            transaction {
                shouldThrow<ConflictException> {
                    EventRoomCollisionGuard.assertNoOverlap(
                        roomId = room,
                        startsAt = LocalDateTime(2030, 6, 1, 18, 0),
                        endsAt = LocalDateTime(2030, 6, 1, 20, 0),
                        excludingEventId = null,
                    )
                }
            }
        }

        test("partial overlap is rejected") {
            val member = createMember()
            val room = createRoom(member)
            createEvent(member, room, LocalDateTime(2030, 6, 2, 18, 0), LocalDateTime(2030, 6, 2, 20, 0))
            transaction {
                shouldThrow<ConflictException> {
                    EventRoomCollisionGuard.assertNoOverlap(
                        roomId = room,
                        startsAt = LocalDateTime(2030, 6, 2, 19, 0),
                        endsAt = LocalDateTime(2030, 6, 2, 21, 0),
                        excludingEventId = null,
                    )
                }
            }
        }

        test("edge-to-edge (new starts exactly when existing ends) is NOT a conflict") {
            val member = createMember()
            val room = createRoom(member)
            createEvent(member, room, LocalDateTime(2030, 6, 3, 18, 0), LocalDateTime(2030, 6, 3, 20, 0))
            transaction {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = room,
                    startsAt = LocalDateTime(2030, 6, 3, 20, 0),
                    endsAt = LocalDateTime(2030, 6, 3, 22, 0),
                    excludingEventId = null,
                )
            }
        }

        test("edge-to-edge (new ends exactly when existing starts) is NOT a conflict") {
            val member = createMember()
            val room = createRoom(member)
            createEvent(member, room, LocalDateTime(2030, 6, 4, 18, 0), LocalDateTime(2030, 6, 4, 20, 0))
            transaction {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = room,
                    startsAt = LocalDateTime(2030, 6, 4, 16, 0),
                    endsAt = LocalDateTime(2030, 6, 4, 18, 0),
                    excludingEventId = null,
                )
            }
        }

        test("a CANCELLED event on the room never blocks a new booking in the same window") {
            val member = createMember()
            val room = createRoom(member)
            createEvent(
                member,
                room,
                LocalDateTime(2030, 6, 5, 18, 0),
                LocalDateTime(2030, 6, 5, 20, 0),
                status = EventStatus.CANCELLED,
            )
            transaction {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = room,
                    startsAt = LocalDateTime(2030, 6, 5, 18, 0),
                    endsAt = LocalDateTime(2030, 6, 5, 20, 0),
                    excludingEventId = null,
                )
            }
        }

        test("a self-update (excludingEventId = the event's own id) never conflicts with itself") {
            val member = createMember()
            val room = createRoom(member)
            val eventId = createEvent(member, room, LocalDateTime(2030, 6, 6, 18, 0), LocalDateTime(2030, 6, 6, 20, 0))
            transaction {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = room,
                    startsAt = LocalDateTime(2030, 6, 6, 18, 0),
                    endsAt = LocalDateTime(2030, 6, 6, 20, 0),
                    excludingEventId = eventId,
                )
            }
        }

        test("an INACTIVE room is rejected regardless of overlap") {
            val member = createMember()
            val room = createRoom(member)
            transaction { EventRoomStore.setStatus(id = room, status = EventRoomStatus.INACTIVE) }
            transaction {
                shouldThrow<ConflictException> {
                    EventRoomCollisionGuard.assertNoOverlap(
                        roomId = room,
                        startsAt = LocalDateTime(2030, 6, 7, 18, 0),
                        endsAt = LocalDateTime(2030, 6, 7, 20, 0),
                        excludingEventId = null,
                    )
                }
            }
        }

        // ── Race guard: the `FOR UPDATE` row lock on `event_room` (EventRoomCollisionGuard's own
        // KDoc "the invariant... is a property of the ROOM") must actually serialize two concurrent
        // booking attempts on the SAME room/window -- mirrors EventCapacityTest's own real-thread
        // race idiom ("the single most important test" of the sibling Welle V1.4.3.1). Without the
        // lock, both threads could pass assertNoOverlap's SELECT before either INSERTs its event,
        // double-booking the room.

        test("two concurrent bookings of the SAME room/window -- only one succeeds, the other sees a real conflict") {
            val member = createMember()
            val room = createRoom(member)
            val startsAt = LocalDateTime(2030, 6, 8, 18, 0)
            val endsAt = LocalDateTime(2030, 6, 8, 20, 0)

            val startLatch = CountDownLatch(2)
            val doneLatch = CountDownLatch(2)
            val results = Collections.synchronizedList(mutableListOf<Result<Uuid>>())

            fun bookingThread() =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        val outcome =
                            runCatching {
                                transaction {
                                    EventRoomCollisionGuard.assertNoOverlap(
                                        roomId = room,
                                        startsAt = startsAt,
                                        endsAt = endsAt,
                                        excludingEventId = null,
                                    )
                                    createEvent(member, room, startsAt, endsAt)
                                }
                            }
                        results += outcome
                    } finally {
                        doneLatch.countDown()
                    }
                }

            val t1 = bookingThread()
            val t2 = bookingThread()
            t1.start()
            t2.start()
            check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent booking attempts did not complete in time" }

            results.count { it.isSuccess } shouldBe 1
            results.count { it.isFailure } shouldBe 1
            (results.single { it.isFailure }.exceptionOrNull() is ConflictException) shouldBe true

            // DB-level confirmation: exactly one event actually got the room.
            val bookedCount =
                transaction {
                    EventTable.selectAll().where { (EventTable.roomId eq room) }.count()
                }
            bookedCount shouldBe 1L
        }
    })
