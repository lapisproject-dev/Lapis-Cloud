package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_TOPIC
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.encounterMapPacket
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Welle V1.9.166 -- delivery (members only, through sendDataTo, chunks, failures) and the reconcile against the database. */
class EncounterMapBroadcasterTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun broadcaster(
            liveKit: FakeEncounterLiveKit,
            state: EncounterMapState,
        ) = EncounterMapBroadcaster(liveKit = liveKit, state = state, liveKitEnabled = true, reconcileEvery = 1_000_000)

        test("a tick sends one data packet per room to the registered members only, never to everybody") {
            val liveKit = FakeEncounterLiveKit()
            val state = EncounterMapState(epoch = "00ff00ff")
            val space = Uuid.random()
            val room = Uuid.random()
            val a = Uuid.random()
            val b = Uuid.random()
            state.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk-room",
                layout = EncounterRoomLayout.HALL,
                memberId = a,
                initials = "AB",
            )
            state.addViewer(spaceId = space, sessionRoomId = room, livekitRoom = "lk-room", layout = EncounterRoomLayout.HALL, memberId = b)
            broadcaster(liveKit, state).tick()
            liveKit.sentData.shouldBeEmpty()
            val sent = liveKit.sentDataTo.single()
            sent.topic shouldBe ENCOUNTER_MAP_TOPIC
            sent.room shouldBe "lk-room"
            sent.destinations.toSet() shouldBe setOf(a.toString(), b.toString())
            encounterMapPacket(fromParticipant = false, payload = sent.payload).shouldBeInstanceOf<EncounterMapPacket.Delta>()
            // nothing changed: nothing is sent
            broadcaster(liveKit, state).tick()
            liveKit.sentDataTo shouldHaveSize 1
        }

        test("more than 50 recipients are sent in chunks of at most 50") {
            val liveKit = FakeEncounterLiveKit()
            val state = EncounterMapState(epoch = "00ff00ff")
            val space = Uuid.random()
            val room = Uuid.random()
            state.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk-room",
                layout = EncounterRoomLayout.HALL,
                memberId = Uuid.random(),
                initials = "AB",
            )
            repeat(120) {
                state.addViewer(
                    spaceId = space,
                    sessionRoomId = room,
                    livekitRoom = "lk-room",
                    layout = EncounterRoomLayout.HALL,
                    memberId = Uuid.random(),
                )
            }
            broadcaster(liveKit, state).tick()
            liveKit.sentDataTo.map { it.destinations.size }.sum() shouldBe 121
            liveKit.sentDataTo.all { it.destinations.size in 1..50 } shouldBe true
        }

        test("a LiveKit failure does not throw and logs no identity") {
            val liveKit = FakeEncounterLiveKit()
            liveKit.failSendData = true
            val state = EncounterMapState(epoch = "00ff00ff")
            val member = Uuid.random()
            state.enter(
                spaceId = Uuid.random(),
                sessionRoomId = Uuid.random(),
                livekitRoom = "lk-secret-room",
                layout = EncounterRoomLayout.HALL,
                memberId = member,
                initials = "AB",
            )
            val logs = captureLogs { broadcaster(liveKit, state).tick() }
            logs.none { member.toString() in it || "lk-secret-room" in it } shouldBe true
        }

        test("a disabled LiveKit does nothing") {
            val liveKit = FakeEncounterLiveKit()
            val state = EncounterMapState()
            state.enter(
                spaceId = Uuid.random(),
                sessionRoomId = Uuid.random(),
                livekitRoom = "lk",
                layout = EncounterRoomLayout.HALL,
                memberId = Uuid.random(),
                initials = "AB",
            )
            EncounterMapBroadcaster(liveKit = liveKit, state = state, liveKitEnabled = false).tick()
            liveKit.sentDataTo.shouldBeEmpty()
        }

        test("the reconcile removes a person whose status changed from ACTIVE during the session") {
            val board = fx.createMember(role = AccountRole.BOARD)
            val space = fx.createSpace(createdBy = board, profile = EncounterProfile.ASSEMBLY, mapEnabled = true)
            val walker = fx.createMember(name = "Walker")
            val room = fx.insertSession(spaceId = space, openedBy = board)
            fx.insertParticipation(roomId = room, memberId = walker)
            val state = EncounterMapState(epoch = "00ff00ff")
            state.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk",
                layout = EncounterRoomLayout.HALL,
                memberId = walker,
                initials = "AB",
            )
            val broadcaster =
                EncounterMapBroadcaster(liveKit = FakeEncounterLiveKit(), state = state, liveKitEnabled = true, reconcileEvery = 1)
            broadcaster.reconcileFromDb()
            state.walkerCount(room) shouldBe 1
            fx.setStatus(memberId = walker, status = MemberStatus.FRIEND)
            broadcaster.reconcileFromDb()
            state.walkerCount(room) shouldBe 0
            state.snapshot(sessionRoomId = room, memberId = walker).self.onMap shouldBe false
        }

        test("the reconcile keeps an office holder as a viewer but never as a walker, and drops a person who is not present") {
            val board = fx.createMember(role = AccountRole.BOARD)
            val space = fx.createSpace(createdBy = board, profile = EncounterProfile.ASSEMBLY, mapEnabled = true)
            val steward = fx.createMember(name = "Steward")
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val gone = fx.createMember(name = "Gone")
            val room = fx.insertSession(spaceId = space, openedBy = board)
            fx.insertParticipation(roomId = room, memberId = steward)
            val state = EncounterMapState(epoch = "00ff00ff")
            state.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk",
                layout = EncounterRoomLayout.HALL,
                memberId = steward,
                initials = "ST",
            )
            state.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk",
                layout = EncounterRoomLayout.HALL,
                memberId = gone,
                initials = "GO",
            )
            EncounterMapBroadcaster(liveKit = FakeEncounterLiveKit(), state = state, liveKitEnabled = true).reconcileFromDb()
            state.walkerCount(room) shouldBe 0
            state.snapshot(sessionRoomId = room, memberId = steward).positions.shouldBeEmpty()
        }

        test("the reconcile clears a session whose map was switched off or which ended, and queues a reset") {
            val board = fx.createMember(role = AccountRole.BOARD)
            val space = fx.createSpace(createdBy = board, profile = EncounterProfile.ASSEMBLY, mapEnabled = true)
            val walker = fx.createMember(name = "Walker")
            val room = fx.insertSession(spaceId = space, openedBy = board)
            fx.insertParticipation(roomId = room, memberId = walker)
            val state = EncounterMapState(epoch = "00ff00ff")
            state.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk",
                layout = EncounterRoomLayout.HALL,
                memberId = walker,
                initials = "AB",
            )
            state.drain()
            transaction { EncounterSpaceTable.update({ EncounterSpaceTable.id eq space }) { it[mapEnabled] = false } }
            EncounterMapBroadcaster(liveKit = FakeEncounterLiveKit(), state = state, liveKitEnabled = true).reconcileFromDb()
            state.trackedSessions() shouldBe 0
            state
                .drain()
                .single()
                .packet
                .shouldBeInstanceOf<EncounterMapPacket.Reset>()
        }
    })
