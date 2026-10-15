package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/** V1.9.138 -- the in-memory floor state: monotonic time, one person per session, bounded, revoke-aware, stale-serial-safe. */
class EncounterFloorStateTest :
    FunSpec({
        fun grant(
            state: EncounterFloorState,
            room: Uuid = Uuid.random(),
            member: Uuid = Uuid.random(),
        ) = state.tryGrant(roomId = room, livekitRoomName = "lc-$room", memberId = member, displayName = "Name")

        test("a grant lasts 120 seconds and counts down from the monotonic clock only, rounded up") {
            val time = TestTimeSource()
            val state = EncounterFloorState(time)
            val room = Uuid.random()
            val member = Uuid.random()
            val g = grant(state, room, member).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>().grant
            state.remainingSeconds(g) shouldBe 120
            time += 500.milliseconds
            state.remainingSeconds(g) shouldBe 120
            time += 600.milliseconds
            state.remainingSeconds(g) shouldBe 119
            time += 118.seconds
            state.remainingSeconds(g) shouldBe 1
            state.isValid(roomId = room, memberId = member) shouldBe true
            time += 1.seconds
            state.remainingSeconds(g) shouldBe 0
            state.isValid(roomId = room, memberId = member) shouldBe false
            time += 1000.seconds
            state.remainingSeconds(g) shouldBe 0
        }

        test("one person per session: a second grant is Busy, also while the first is being revoked") {
            val state = EncounterFloorState()
            val room = Uuid.random()
            grant(state, room).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>()
            grant(state, room) shouldBe EncounterFloorState.GrantResult.Busy
            state.markRevoking(room).shouldNotBeNull().revoking shouldBe true
            grant(state, room) shouldBe EncounterFloorState.GrantResult.Busy
        }

        test("a revoking grant is no longer valid and cannot be extended") {
            val state = EncounterFloorState()
            val room = Uuid.random()
            val member = Uuid.random()
            grant(state, room, member)
            state.isValid(roomId = room, memberId = member) shouldBe true
            state.markRevoking(room)
            state.isValid(roomId = room, memberId = member) shouldBe false
            state.extend(roomId = room, seconds = 60) shouldBe EncounterFloorState.ExtendResult.NoGrant
        }

        test("the total never exceeds 600 seconds: 120 + 4 x 120 is the last allowed step") {
            val state = EncounterFloorState()
            val room = Uuid.random()
            grant(state, room)
            repeat(4) { state.extend(roomId = room, seconds = 120).shouldBeInstanceOf<EncounterFloorState.ExtendResult.Extended>() }
            state.current(room)!!.totalSeconds shouldBe 600
            state.extend(roomId = room, seconds = 60) shouldBe EncounterFloorState.ExtendResult.LimitReached
            state.extend(roomId = Uuid.random(), seconds = 60) shouldBe EncounterFloorState.ExtendResult.NoGrant
        }

        test("a late revoke of an old grant never removes a newer grant of the same person") {
            val state = EncounterFloorState()
            val room = Uuid.random()
            val member = Uuid.random()
            val first = grant(state, room, member).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>().grant
            state.remove(roomId = room, serial = first.serial) shouldBe true
            val second = grant(state, room, member).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>().grant
            (second.serial != first.serial) shouldBe true
            state.remove(roomId = room, serial = first.serial) shouldBe false
            state.current(room).shouldNotBeNull().serial shouldBe second.serial
        }

        test("at most 64 sessions are tracked; a full table refuses a new grant and never evicts one") {
            val state = EncounterFloorState()
            val rooms = List(EncounterFloorState.MAX_SESSIONS) { Uuid.random() }
            rooms.forEach { grant(state, it).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>() }
            grant(state, Uuid.random()) shouldBe EncounterFloorState.GrantResult.Full
            state.size() shouldBe EncounterFloorState.MAX_SESSIONS
            rooms.forEach { state.current(it).shouldNotBeNull() }
        }

        test("retainOnly and clear forget sessions and their grace entries") {
            val state = EncounterFloorState()
            val keep = Uuid.random()
            val drop = Uuid.random()
            val keeper = Uuid.random()
            val dropper = Uuid.random()
            grant(state, keep, keeper)
            val g = grant(state, drop, dropper).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>().grant
            state.remove(roomId = drop, serial = g.serial)
            state.recentlyEndedWithinGrace(roomId = drop, memberId = dropper) shouldBe true
            state.retainOnly(setOf(keep))
            state.recentlyEndedWithinGrace(roomId = drop, memberId = dropper) shouldBe false
            state.current(keep).shouldNotBeNull()
            state.clear(keep)
            state.current(keep).shouldBeNull()
        }

        test("the grace after an end lasts 90 seconds") {
            val time = TestTimeSource()
            val state = EncounterFloorState(time)
            val room = Uuid.random()
            val member = Uuid.random()
            val g = grant(state, room, member).shouldBeInstanceOf<EncounterFloorState.GrantResult.Granted>().grant
            state.recentlyEndedWithinGrace(roomId = room, memberId = member) shouldBe false
            state.remove(roomId = room, serial = g.serial)
            time += 89.seconds
            state.recentlyEndedWithinGrace(roomId = room, memberId = member) shouldBe true
            time += 2.seconds
            state.recentlyEndedWithinGrace(roomId = room, memberId = member) shouldBe false
        }

        test("a new state (a restart) is empty") {
            val old = EncounterFloorState()
            grant(old)
            EncounterFloorState().sessionsWithGrant().isEmpty() shouldBe true
        }

        test("the session lock admits one block at a time per session") {
            val state = EncounterFloorState()
            val room = Uuid.random()
            val inside = AtomicInteger(0)
            val maxInside = AtomicInteger(0)
            runBlocking {
                coroutineScope {
                    (1..20)
                        .map {
                            async(Dispatchers.Default) {
                                state.withSessionLock(roomId = room) {
                                    val now = inside.incrementAndGet()
                                    maxInside.updateAndGet { maxOf(it, now) }
                                    kotlinx.coroutines.delay(1)
                                    inside.decrementAndGet()
                                }
                            }
                        }.awaitAll()
                }
            }
            maxInside.get() shouldBe 1
        }
    })
