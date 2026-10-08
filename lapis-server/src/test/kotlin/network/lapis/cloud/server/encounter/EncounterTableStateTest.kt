package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-10-08T10:00:00Z")

/** Welle V1.9.80 -- the in-memory table plan: atomic choice, one place per person, rotation on every departure, bounded state. */
class EncounterTableStateTest :
    FunSpec({
        val session = Uuid.random()

        fun EncounterTableState.sit(
            who: Uuid,
            table: Int,
            seat: Int,
            present: Set<Uuid>,
            sessionId: Uuid = session,
        ) = join(
            sessionRoomId = sessionId,
            memberId = who,
            table = table,
            seat = seat,
            present = present,
            seatsPerTable = 4,
            now = NOW,
        )

        fun EncounterTableState.JoinOutcome.ok() = this as EncounterTableState.JoinOutcome.Ok

        test("the first sitter creates an opaque table room; nobody is rotated out") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val ok = state.sit(who = a, table = 2, seat = 1, present = setOf(a)).ok()
            ok.assignment.table shouldBe 2
            ok.assignment.seat shouldBe 1
            ok.assignment.room shouldStartWith ENCOUNTER_TABLE_ROOM_PREFIX
            ok.assignment.room.contains(session.toString()) shouldBe false
            ok.rotations.single().oldRoom shouldBe null
            ok.rotations.single().newRoom shouldBe ok.assignment.room
            ok.rotations.single().maxParticipants shouldBe 4
        }

        test("a second sitter joins the existing room without a rotation") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val first = state.sit(who = a, table = 0, seat = 0, present = setOf(a, b)).ok()
            val second = state.sit(who = b, table = 0, seat = 1, present = setOf(a, b)).ok()
            second.assignment.room shouldBe first.assignment.room
            second.rotations.shouldBeEmpty()
        }

        test("a taken seat is refused; a seat of somebody who is gone counts as free and rotates the table") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val c = Uuid.random()
            val room =
                state
                    .sit(who = a, table = 1, seat = 0, present = setOf(a, b, c))
                    .ok()
                    .assignment.room
            state.sit(who = b, table = 1, seat = 0, present = setOf(a, b, c)) shouldBe EncounterTableState.JoinOutcome.Taken
            state.sit(who = c, table = 1, seat = 3, present = setOf(a, b, c)).ok()
            // a leaves the plenum without telling anybody; b takes the seat -> the table rotates (a may still be connected)
            val takeover = state.sit(who = b, table = 1, seat = 0, present = setOf(b, c)).ok()
            takeover.assignment.room shouldBe takeover.rotations.single().newRoom
            (takeover.assignment.room != room) shouldBe true
            takeover.rotations.single().oldRoom shouldBe room
            state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW) shouldBe null
        }

        test("one place per person: moving to another table rotates the table that was left; within a table nothing rotates") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val present = setOf(a, b)
            val roomA =
                state
                    .sit(who = a, table = 0, seat = 0, present = present)
                    .ok()
                    .assignment.room
            state.sit(who = b, table = 0, seat = 1, present = present).ok()
            // same table, other seat: no rotation
            state
                .sit(who = a, table = 0, seat = 2, present = present)
                .ok()
                .rotations
                .shouldBeEmpty()
            state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW)!!.seat shouldBe 2
            // other table: table 0 keeps b but gets a new room, table 1 is created
            val moved = state.sit(who = a, table = 1, seat = 0, present = present).ok()
            moved.rotations shouldHaveSize 2
            val left = moved.rotations.first { it.oldRoom == roomA }
            (left.newRoom != null && left.newRoom != roomA) shouldBe true
            state.assignmentOf(sessionRoomId = session, memberId = b, now = NOW)!!.room shouldBe left.newRoom
        }

        test("every departure rotates; the last one deletes the room and forgets the table") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val present = setOf(a, b)
            val room =
                state
                    .sit(who = a, table = 0, seat = 0, present = present)
                    .ok()
                    .assignment.room
            state.sit(who = b, table = 0, seat = 1, present = present).ok()
            val first = state.leave(sessionRoomId = session, memberId = a)!!
            first.oldRoom shouldBe room
            (first.newRoom != null && first.newRoom != room) shouldBe true
            val last = state.leave(sessionRoomId = session, memberId = b)!!
            last.oldRoom shouldBe first.newRoom
            last.newRoom shouldBe null
            state.activeRooms(NOW).isEmpty() shouldBe true
            state.leave(sessionRoomId = session, memberId = b) shouldBe null // idempotent
            state.trackedSessions() shouldBe 0
        }

        test("a room name is never reused") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val present = setOf(a, b)
            val seen = mutableSetOf<String>()
            repeat(20) {
                seen +=
                    state
                        .sit(who = a, table = 0, seat = 0, present = present)
                        .ok()
                        .assignment.room
                seen +=
                    state
                        .sit(who = b, table = 0, seat = 1, present = present)
                        .ok()
                        .assignment.room
                state.leave(sessionRoomId = session, memberId = a)?.newRoom?.let { seen += it }
                state.leave(sessionRoomId = session, memberId = b)
            }
            (seen.size >= 20) shouldBe true
        }

        test("quieting rotates once, an extension does not, releasing rotates again, an expired quiet is lifted by expireQuiet") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val room =
                state
                    .sit(who = a, table = 0, seat = 0, present = setOf(a))
                    .ok()
                    .assignment.room
            val until = NOW + 5.minutes
            val quieted = state.quiet(sessionRoomId = session, table = 0, until = until, now = NOW)!!
            quieted.oldRoom shouldBe room
            state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW)!!.quieted shouldBe true
            state.activeRooms(NOW).getValue(quieted.newRoom!!).publishAllowed shouldBe false
            state.quiet(sessionRoomId = session, table = 0, until = until + 1.minutes, now = NOW) shouldBe null
            state.expireQuiet(NOW + 1.minutes).shouldBeEmpty()
            val lifted = state.expireQuiet(NOW + 7.minutes).single()
            lifted.oldRoom shouldBe quieted.newRoom
            state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW + 7.minutes)!!.quieted shouldBe false
            // releasing an unquieted table is a no-op, quieting an empty table too
            state.quiet(sessionRoomId = session, table = 0, until = null, now = NOW) shouldBe null
            state.quiet(sessionRoomId = session, table = 5, until = until, now = NOW) shouldBe null
            val again = state.quiet(sessionRoomId = session, table = 0, until = until, now = NOW)!!
            val released = state.quiet(sessionRoomId = session, table = 0, until = null, now = NOW)!!
            released.oldRoom shouldBe again.newRoom
        }

        test("a new quiet on a table whose old quiet ran out but was not lifted yet rotates, so the members get a muted token") {
            val state = EncounterTableState()
            val a = Uuid.random()
            state.sit(who = a, table = 0, seat = 0, present = setOf(a)).ok()
            val firstUntil = NOW + 5.minutes
            state.quiet(sessionRoomId = session, table = 0, until = firstUntil, now = NOW)!!
            val later = firstUntil + 1.minutes // expired, expireQuiet has not run
            state.assignmentOf(sessionRoomId = session, memberId = a, now = later)!!.quieted shouldBe false
            val again = state.quiet(sessionRoomId = session, table = 0, until = later + 5.minutes, now = later)
            (again != null) shouldBe true
            state.assignmentOf(sessionRoomId = session, memberId = a, now = later)!!.quieted shouldBe true
        }

        test("releasing a table whose quiet ran out but was not lifted yet still rotates, so the members get a publishing token") {
            val state = EncounterTableState()
            val a = Uuid.random()
            state.sit(who = a, table = 0, seat = 0, present = setOf(a)).ok()
            val until = NOW + 5.minutes
            state.quiet(sessionRoomId = session, table = 0, until = until, now = NOW)!!
            val later = until + 1.minutes // expired, expireQuiet has not run yet
            val released = state.quiet(sessionRoomId = session, table = 0, until = null, now = later)
            (released != null) shouldBe true
            state.expireQuiet(later) shouldHaveSize 0 // nothing left to lift: the rotation happened in quiet()
            state.assignmentOf(sessionRoomId = session, memberId = a, now = later)!!.quieted shouldBe false
        }

        test("snapshot prunes people who are gone (or hold an office now) and rotates each affected table once") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val c = Uuid.random()
            val present = setOf(a, b, c)
            state.sit(who = a, table = 0, seat = 0, present = present).ok()
            state.sit(who = b, table = 0, seat = 1, present = present).ok()
            state.sit(who = c, table = 3, seat = 0, present = present).ok()
            val snap = state.snapshot(sessionRoomId = session, present = setOf(c), now = NOW)
            snap.rotations shouldHaveSize 1 // two people left table 0 -> ONE rotation (the table is empty -> deletion)
            snap.rotations.single().newRoom shouldBe null
            snap.occupied shouldBe mapOf(3 to setOf(0))
            snap.positions shouldBe mapOf(c to (3 to 0))
        }

        test("activeRooms lists exactly the assigned identities of the current rooms") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val room =
                state
                    .sit(who = a, table = 0, seat = 0, present = setOf(a, b))
                    .ok()
                    .assignment.room
            state.sit(who = b, table = 0, seat = 1, present = setOf(a, b)).ok()
            val active = state.activeRooms(NOW)
            active.keys shouldBe setOf(room)
            active.getValue(room).identities shouldBe setOf(a.toString(), b.toString())
            active.getValue(room).publishAllowed shouldBe true
        }

        test("rotateRoom rotates only a current table room") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val room =
                state
                    .sit(who = a, table = 0, seat = 0, present = setOf(a))
                    .ok()
                    .assignment.room
            state.rotateRoom("lc-et-unknown") shouldBe null
            val rotated = state.rotateRoom(room)!!
            rotated.oldRoom shouldBe room
            state.rotateRoom(room) shouldBe null // the old name is not current any more
        }

        test("clear and retainOnly hand back the rooms to delete and drop the state") {
            val state = EncounterTableState()
            val other = Uuid.random()
            val a = Uuid.random()
            val b = Uuid.random()
            val roomA =
                state
                    .sit(who = a, table = 0, seat = 0, present = setOf(a))
                    .ok()
                    .assignment.room
            val roomB =
                state
                    .sit(who = b, table = 1, seat = 0, present = setOf(b), sessionId = other)
                    .ok()
                    .assignment.room
            state.retainOnly(setOf(session)) shouldBe listOf(roomB)
            state.trackedSessions() shouldBe 1
            state.clear(session) shouldBe listOf(roomA)
            state.trackedSessions() shouldBe 0
            state.clear(session).shouldBeEmpty()
        }

        test("the number of tracked sessions is bounded") {
            val state = EncounterTableState()
            repeat(EncounterTableState.MAX_SESSIONS) {
                val who = Uuid.random()
                state.sit(who = who, table = 0, seat = 0, present = setOf(who), sessionId = Uuid.random()).ok()
            }
            val late = Uuid.random()
            state.sit(who = late, table = 0, seat = 0, present = setOf(late), sessionId = Uuid.random()) shouldBe
                EncounterTableState.JoinOutcome.Full
            // an existing session still works
            state.trackedSessions() shouldBe EncounterTableState.MAX_SESSIONS
        }

        test("concurrent choice of the same seat has exactly one winner") {
            repeat(30) {
                val state = EncounterTableState()
                val people = List(8) { Uuid.random() }
                val present = people.toSet()
                val pool = Executors.newFixedThreadPool(8)
                val start = CountDownLatch(1)
                val winners = AtomicInteger()
                val losers = AtomicInteger()
                val futures =
                    people.map { who ->
                        pool.submit {
                            start.await()
                            when (state.sit(who = who, table = 0, seat = 0, present = present)) {
                                is EncounterTableState.JoinOutcome.Ok -> winners.incrementAndGet()
                                EncounterTableState.JoinOutcome.Taken -> losers.incrementAndGet()
                                EncounterTableState.JoinOutcome.Full, EncounterTableState.JoinOutcome.TooFast -> error("unexpected")
                            }
                        }
                    }
                start.countDown()
                futures.forEach { it.get() }
                pool.shutdown()
                winners.get() shouldBe 1
                losers.get() shouldBe 7
            }
        }

        test("leaving a shared table by choice is limited to one rotation per cooldown; moderation and a lone table are not") {
            val state = EncounterTableState()
            val a = Uuid.random()
            val b = Uuid.random()
            val present = setOf(a, b)
            state.sit(who = b, table = 0, seat = 0, present = present).ok()
            state.sit(who = a, table = 0, seat = 1, present = present).ok()
            // a switches away from the shared table 0: allowed once, the table is rotated
            state.sit(who = a, table = 1, seat = 0, present = present).ok().rotations shouldHaveSize 2
            state.sit(who = b, table = 1, seat = 1, present = present).ok()
            // a leaves the shared table 1 again right away: refused, nothing changed
            val before = state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW)!!
            state.sit(who = a, table = 2, seat = 0, present = present) shouldBe EncounterTableState.JoinOutcome.TooFast
            state.leaveSelf(sessionRoomId = session, memberId = a, now = NOW + 5.seconds) shouldBe
                EncounterTableState.SelfLeave(rotation = null, throttled = true)
            state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW) shouldBe before
            // after the cooldown the person may leave again; the table is rotated
            state.leaveSelf(sessionRoomId = session, memberId = a, now = NOW + 11.seconds).throttled shouldBe false
            state.assignmentOf(sessionRoomId = session, memberId = a, now = NOW) shouldBe null
            // moderation (leave) is never throttled, a repeated call is a no-op
            state.leave(sessionRoomId = session, memberId = b)!!.newRoom shouldBe null
            state.leave(sessionRoomId = session, memberId = b) shouldBe null
        }

        test("leaving a table nobody else sits at does not rotate anybody and is never throttled") {
            val state = EncounterTableState()
            val a = Uuid.random()
            state.sit(who = a, table = 0, seat = 0, present = setOf(a)).ok()
            state.sit(who = a, table = 1, seat = 0, present = setOf(a)).ok()
            state.sit(who = a, table = 2, seat = 0, present = setOf(a)).ok()
            state.leaveSelf(sessionRoomId = session, memberId = a, now = NOW).throttled shouldBe false
        }
    })
