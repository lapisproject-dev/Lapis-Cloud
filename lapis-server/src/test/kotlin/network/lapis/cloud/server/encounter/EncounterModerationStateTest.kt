package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Welle V1.9.61 -- [EncounterModerationState]: in memory, bounded, refuses instead of evicting, cleared per session. */
class EncounterModerationStateTest :
    FunSpec({
        test("block and silence are separate lists per session") {
            val state = EncounterModerationState()
            val room = Uuid.random()
            val a = Uuid.random()
            state.block(sessionRoomId = room, memberId = a) shouldBe true
            state.isBlocked(sessionRoomId = room, memberId = a) shouldBe true
            state.isSilenced(sessionRoomId = room, memberId = a) shouldBe false
            state.silence(sessionRoomId = room, memberId = a) shouldBe true
            state.isSilenced(sessionRoomId = room, memberId = a) shouldBe true
            state.isBlocked(sessionRoomId = Uuid.random(), memberId = a) shouldBe false
            state.blockedIdentities(room) shouldBe setOf(a.toString())
        }

        test("clear drops everything of one session and nothing of another") {
            val state = EncounterModerationState()
            val r1 = Uuid.random()
            val r2 = Uuid.random()
            val a = Uuid.random()
            state.block(sessionRoomId = r1, memberId = a)
            state.silence(sessionRoomId = r1, memberId = a)
            state.block(sessionRoomId = r2, memberId = a)
            state.clear(r1)
            state.isBlocked(sessionRoomId = r1, memberId = a) shouldBe false
            state.isSilenced(sessionRoomId = r1, memberId = a) shouldBe false
            state.isBlocked(sessionRoomId = r2, memberId = a) shouldBe true
            state.trackedSessions() shouldBe 1
        }

        test("a full list refuses the new entry and never drops an existing one; the same entry again is accepted") {
            val state = EncounterModerationState()
            val room = Uuid.random()
            val first = Uuid.random()
            state.block(sessionRoomId = room, memberId = first) shouldBe true
            repeat(EncounterModerationState.MAX_ENTRIES_PER_SESSION - 1) {
                state.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe
                    true
            }
            state.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe false
            state.block(sessionRoomId = room, memberId = first) shouldBe true
            state.isBlocked(sessionRoomId = room, memberId = first) shouldBe true
        }

        test("at most 64 sessions are tracked; a 65th is refused, an already tracked one still accepts entries") {
            val state = EncounterModerationState()
            val rooms = List(EncounterModerationState.MAX_SESSIONS) { Uuid.random() }
            rooms.forEach { state.block(sessionRoomId = it, memberId = Uuid.random()) shouldBe true }
            state.block(sessionRoomId = Uuid.random(), memberId = Uuid.random()) shouldBe false
            state.silence(sessionRoomId = Uuid.random(), memberId = Uuid.random()) shouldBe false
            state.block(sessionRoomId = rooms.first(), memberId = Uuid.random()) shouldBe true
            state.trackedSessions() shouldBe EncounterModerationState.MAX_SESSIONS
        }

        test("thread-safe: 8 threads adding to one session never exceed the cap and never lose an accepted entry") {
            val state = EncounterModerationState()
            val room = Uuid.random()
            val pool = Executors.newFixedThreadPool(8)
            val accepted =
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet<Uuid>()
            try {
                repeat(8) {
                    pool.submit {
                        repeat(100) {
                            val m = Uuid.random()
                            if (state.block(sessionRoomId = room, memberId = m)) accepted += m
                        }
                    }
                }
            } finally {
                pool.shutdown()
                pool.awaitTermination(20, TimeUnit.SECONDS) shouldBe true
            }
            accepted.size shouldBe EncounterModerationState.MAX_ENTRIES_PER_SESSION
            accepted.all { state.isBlocked(sessionRoomId = room, memberId = it) } shouldBe true
        }
    })
