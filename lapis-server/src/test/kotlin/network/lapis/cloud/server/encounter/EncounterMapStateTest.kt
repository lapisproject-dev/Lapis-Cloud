package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_MAX_WALKERS
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapMove
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterMapTemplates
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.encounterMapIndex
import network.lapis.cloud.shared.domain.encounterMapPayload
import kotlin.uuid.Uuid

/** Welle V1.9.166 -- the in-memory position state: entering, stepping, throttles, bounds, delivery batches, cleanup, concurrency. */
class EncounterMapStateTest :
    FunSpec({
        class Clock(
            var ms: Long = 0,
        ) {
            val read: () -> Long = { ms }
        }

        val hall = EncounterMapTemplates.of(EncounterRoomLayout.HALL)!!
        val entrance = hall.anchors.getValue(EncounterMapAnchor.ENTRANCE)

        class Rig(
            val clock: Clock = Clock(),
        ) {
            val state = EncounterMapState(monoMs = clock.read, epoch = "00ff00ff")
            val space: Uuid = Uuid.random()
            val room: Uuid = Uuid.random()

            fun enter(member: Uuid): EncounterMapState.EnterOutcome =
                state.enter(
                    spaceId = space,
                    sessionRoomId = room,
                    livekitRoom = "lk-room",
                    layout = EncounterRoomLayout.HALL,
                    memberId = member,
                    initials = "AB",
                )

            fun self(member: Uuid) = state.snapshot(sessionRoomId = room, memberId = member).self
        }

        fun member() = Uuid.random()

        test("enter puts a person on the nearest free tile to the entrance, facing north, and is idempotent") {
            val rig = Rig()
            val a = member()
            val ok = rig.enter(a).shouldBeInstanceOf<EncounterMapState.EnterOutcome.Ok>()
            ok.self.onMap shouldBe true
            encounterMapIndex(x = ok.self.x, y = ok.self.y) shouldBe entrance
            ok.self.facing shouldBe EncounterMapFacing.NORTH
            val again = rig.enter(a).shouldBeInstanceOf<EncounterMapState.EnterOutcome.Ok>()
            again.self.x shouldBe ok.self.x
            again.self.y shouldBe ok.self.y
            rig.state.walkerCount(rig.room) shouldBe 1
            val b = member()
            val second = rig.enter(b).shouldBeInstanceOf<EncounterMapState.EnterOutcome.Ok>()
            (encounterMapIndex(x = second.self.x, y = second.self.y) == entrance) shouldBe false
        }

        test("the 26th walker and the 65th session are refused") {
            val rig = Rig()
            repeat(ENCOUNTER_MAP_MAX_WALKERS) { rig.enter(member()).shouldBeInstanceOf<EncounterMapState.EnterOutcome.Ok>() }
            rig.enter(member()) shouldBe EncounterMapState.EnterOutcome.Full
            val sessions = Rig()
            repeat(EncounterMapState.MAX_SESSIONS) {
                sessions.state
                    .enter(
                        spaceId = Uuid.random(),
                        sessionRoomId = Uuid.random(),
                        livekitRoom = "r",
                        layout = EncounterRoomLayout.HALL,
                        memberId = member(),
                        initials = "A",
                    ).shouldBeInstanceOf<EncounterMapState.EnterOutcome.Ok>()
            }
            sessions.state.enter(
                spaceId = Uuid.random(),
                sessionRoomId = Uuid.random(),
                livekitRoom = "r",
                layout = EncounterRoomLayout.HALL,
                memberId = member(),
                initials = "A",
            ) shouldBe
                EncounterMapState.EnterOutcome.Full
        }

        test("a layout without a map (the nave) is refused") {
            val state = EncounterMapState()
            state.enter(
                spaceId = Uuid.random(),
                sessionRoomId = Uuid.random(),
                livekitRoom = "r",
                layout = EncounterRoomLayout.NAVE,
                memberId = member(),
                initials = "A",
            ) shouldBe
                EncounterMapState.EnterOutcome.Full
        }

        test("a step moves in every direction; the facing follows") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            rig.state.teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.CENTER)
            val start = rig.self(a)
            listOf(
                EncounterMapFacing.NORTH to (0 to -1),
                EncounterMapFacing.EAST to (1 to 0),
                EncounterMapFacing.SOUTH to (0 to 1),
                EncounterMapFacing.WEST to (-1 to 0),
            ).forEachIndexed { index, (dir, delta) ->
                rig.clock.ms += 1_000
                val before = rig.self(a)
                val done =
                    rig.state
                        .step(
                            spaceId = rig.space,
                            memberId = a,
                            dir = dir,
                        ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                done.self.move shouldBe EncounterMapMove.MOVED
                done.self.facing shouldBe dir
                (done.self.x to done.self.y) shouldBe (before.x + delta.first to before.y + delta.second)
                (index >= 0) shouldBe true
            }
            // north, east, south, west: back where it started
            (rig.self(a).x to rig.self(a).y) shouldBe (start.x to start.y)
        }

        test("a wall, an object, the edge and another person block the step but turn the person") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            // the entrance tile (5,7) is in the bottom gap: south is the edge of the map
            val edge =
                rig.state
                    .step(
                        spaceId = rig.space,
                        memberId = a,
                        dir = EncounterMapFacing.SOUTH,
                    ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
            edge.self.move shouldBe EncounterMapMove.BLOCKED
            edge.self.facing shouldBe EncounterMapFacing.SOUTH
            (encounterMapIndex(x = edge.self.x, y = edge.self.y)) shouldBe entrance
            // west x3: (4,7) then the wall at (3,7)
            rig.clock.ms += 1_000
            rig.state
                .step(
                    spaceId = rig.space,
                    memberId = a,
                    dir = EncounterMapFacing.WEST,
                ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe
                EncounterMapMove.MOVED
            rig.clock.ms += 1_000
            val wall =
                rig.state
                    .step(
                        spaceId = rig.space,
                        memberId = a,
                        dir = EncounterMapFacing.WEST,
                    ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
            wall.self.move shouldBe EncounterMapMove.BLOCKED
            // an object: the podium row (3..8, 1)
            val b = member()
            rig.enter(b)
            rig.state.teleport(spaceId = rig.space, memberId = b, anchor = EncounterMapAnchor.PULPIT) // (5,2)
            rig.clock.ms += 1_000
            val object1 =
                rig.state
                    .step(
                        spaceId = rig.space,
                        memberId = b,
                        dir = EncounterMapFacing.NORTH,
                    ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
            object1.self.move shouldBe EncounterMapMove.BLOCKED
            // another person: c stands next to b
            val c = member()
            rig.enter(c)
            rig.state.teleport(spaceId = rig.space, memberId = c, anchor = EncounterMapAnchor.PULPIT) // nearest free next to b
            val cAt = rig.self(c)
            val bAt = rig.self(b)
            val dir =
                when {
                    cAt.x == bAt.x + 1 -> EncounterMapFacing.WEST
                    cAt.x == bAt.x - 1 -> EncounterMapFacing.EAST
                    cAt.y == bAt.y + 1 -> EncounterMapFacing.NORTH
                    else -> EncounterMapFacing.SOUTH
                }
            rig.clock.ms += 1_000
            rig.state
                .step(spaceId = rig.space, memberId = c, dir = dir)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe
                EncounterMapMove.BLOCKED
            (rig.self(c).x to rig.self(c).y) shouldBe (cAt.x to cAt.y)
        }

        test("the token bucket: three steps at once, the fourth is throttled, after 120 ms there is one again") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            rig.state.teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.CENTER)
            rig.clock.ms += 10_000
            val moves =
                (0 until 4).map {
                    val dir = if (it % 2 == 0) EncounterMapFacing.EAST else EncounterMapFacing.WEST
                    rig.state
                        .step(spaceId = rig.space, memberId = a, dir = dir)
                        .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                        .self.move
                }
            moves shouldContainExactly
                listOf(EncounterMapMove.MOVED, EncounterMapMove.MOVED, EncounterMapMove.MOVED, EncounterMapMove.THROTTLED)
            val before = rig.self(a)
            rig.clock.ms += 119
            rig.state
                .step(spaceId = rig.space, memberId = a, dir = EncounterMapFacing.EAST)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe EncounterMapMove.THROTTLED
            rig.self(a).x shouldBe before.x
            rig.clock.ms += 1
            rig.state
                .step(spaceId = rig.space, memberId = a, dir = EncounterMapFacing.EAST)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe EncounterMapMove.MOVED
        }

        test("a throttled step changes neither position nor facing") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            rig.state.teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.CENTER)
            rig.clock.ms += 10_000
            repeat(3) { rig.state.step(spaceId = rig.space, memberId = a, dir = EncounterMapFacing.EAST) }
            val before = rig.self(a)
            val throttled =
                rig.state
                    .step(
                        spaceId = rig.space,
                        memberId = a,
                        dir = EncounterMapFacing.SOUTH,
                    ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
            throttled.self.move shouldBe EncounterMapMove.THROTTLED
            rig.self(a).facing shouldBe before.facing
            (rig.self(a).x to rig.self(a).y) shouldBe (before.x to before.y)
        }

        test("teleport goes to the nearest free tile of the anchor, at most once every second") {
            val rig = Rig()
            val a = member()
            val b = member()
            rig.enter(a)
            rig.enter(b)
            val center = hall.anchors.getValue(EncounterMapAnchor.CENTER)
            val first =
                rig.state
                    .teleport(
                        spaceId = rig.space,
                        memberId = a,
                        anchor = EncounterMapAnchor.CENTER,
                    ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
            encounterMapIndex(x = first.self.x, y = first.self.y) shouldBe center
            val second =
                rig.state
                    .teleport(
                        spaceId = rig.space,
                        memberId = b,
                        anchor = EncounterMapAnchor.CENTER,
                    ).shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
            (encounterMapIndex(x = second.self.x, y = second.self.y) == center) shouldBe false
            // a second teleport of the same person within 1 s is throttled
            rig.clock.ms += 999
            rig.state
                .teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.ENTRANCE)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe EncounterMapMove.THROTTLED
            rig.clock.ms += 1
            rig.state
                .teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.ENTRANCE)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe EncounterMapMove.MOVED
        }

        test("a jump onto the own tile costs no cooldown and marks nothing changed") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            rig.state.teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.ENTRANCE)
            rig.clock.ms += 10_000
            val here = rig.self(a)
            rig.state
                .teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.ENTRANCE)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe EncounterMapMove.MOVED
            (rig.self(a).x to rig.self(a).y) shouldBe (here.x to here.y)
            // no cooldown was set by the no-op jump: another anchor works at once
            rig.state
                .teleport(spaceId = rig.space, memberId = a, anchor = EncounterMapAnchor.CENTER)
                .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                .self.move shouldBe EncounterMapMove.MOVED
        }

        test("every anchor of every layout with a map is reachable, and an occupied anchor falls back to a free neighbour") {
            listOf(EncounterRoomLayout.HALL, EncounterRoomLayout.FOYER, EncounterRoomLayout.SMALL_GROUP).forEach { layout ->
                val template = EncounterMapTemplates.of(layout) ?: return@forEach
                EncounterMapAnchor.entries.forEach { anchor ->
                    val rig = Rig()
                    val a = member()
                    val b = member()
                    listOf(a, b).forEach {
                        rig.state.enter(
                            spaceId = rig.space,
                            sessionRoomId = rig.room,
                            livekitRoom = "lk-room",
                            layout = layout,
                            memberId = it,
                            initials = "AB",
                        )
                    }
                    val target = template.anchors.getValue(anchor)
                    val first =
                        rig.state
                            .teleport(spaceId = rig.space, memberId = a, anchor = anchor)
                            .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                    encounterMapIndex(x = first.self.x, y = first.self.y) shouldBe target
                    val second =
                        rig.state
                            .teleport(spaceId = rig.space, memberId = b, anchor = anchor)
                            .shouldBeInstanceOf<EncounterMapState.MoveOutcome.Done>()
                    second.self.move shouldBe EncounterMapMove.MOVED
                    (encounterMapIndex(x = second.self.x, y = second.self.y) == target) shouldBe false
                }
            }
        }

        test("somebody who is not on the map cannot step or teleport") {
            val rig = Rig()
            rig.enter(member())
            rig.state.step(spaceId = rig.space, memberId = member(), dir = EncounterMapFacing.NORTH) shouldBe
                EncounterMapState.MoveOutcome.NotOnMap
            rig.state.teleport(spaceId = rig.space, memberId = member(), anchor = EncounterMapAnchor.CENTER) shouldBe
                EncounterMapState.MoveOutcome.NotOnMap
            rig.state.step(spaceId = Uuid.random(), memberId = member(), dir = EncounterMapFacing.NORTH) shouldBe
                EncounterMapState.MoveOutcome.NotOnMap
        }

        test("leave, leaveAll, retain, clear and retainOnly remove walkers and viewers") {
            val rig = Rig()
            val a = member()
            val b = member()
            val c = member()
            listOf(a, b, c).forEach { rig.enter(it) }
            rig.state.leave(sessionRoomId = rig.room, memberId = a)
            rig.self(a).onMap shouldBe false
            rig.state.walkerCount(rig.room) shouldBe 2
            rig.state.leaveAll(sessionRoomId = rig.room, memberIds = listOf(b))
            rig.state.walkerCount(rig.room) shouldBe 1
            rig.enter(a)
            rig.enter(b)
            rig.state.retain(sessionRoomId = rig.room, walkers = setOf(a), viewers = setOf(a, b, c))
            rig.state.walkerCount(rig.room) shouldBe 1
            rig.state.leave(sessionRoomId = rig.room, memberId = a, dropViewer = true)
            rig.state.retain(sessionRoomId = rig.room, walkers = emptySet(), viewers = emptySet())
            rig.state.trackedSessions() shouldBe 0
            rig.enter(a)
            rig.state.clear(rig.room) shouldBe "lk-room"
            rig.state.trackedSessions() shouldBe 0
            rig.state.clear(rig.room) shouldBe null
            rig.enter(a)
            rig.state.retainOnly(emptySet())
            rig.state.trackedSessions() shouldBe 0
        }

        test("drain delivers a delta of the changed people to the viewers only, with a rising sequence") {
            val rig = Rig()
            val a = member()
            val viewer = member()
            rig.enter(a)
            rig.state.addViewer(
                spaceId = rig.space,
                sessionRoomId = rig.room,
                livekitRoom = "lk-room",
                layout = EncounterRoomLayout.HALL,
                memberId = viewer,
            ) shouldBe
                true
            val first = rig.state.drain().single()
            first.livekitRoom shouldBe "lk-room"
            first.recipients.toSet() shouldBe setOf(a.toString(), viewer.toString())
            val delta1 = first.packet.shouldBeInstanceOf<EncounterMapPacket.Delta>()
            delta1.changed.single().memberId shouldBe a.toString()
            delta1.removed.shouldBeEmpty()
            rig.state.drain().shouldBeEmpty()
            rig.clock.ms += 1_000
            rig.state.step(spaceId = rig.space, memberId = a, dir = EncounterMapFacing.WEST)
            val delta2 =
                rig.state
                    .drain()
                    .single()
                    .packet
                    .shouldBeInstanceOf<EncounterMapPacket.Delta>()
            (delta2.seq > delta1.seq) shouldBe true
            delta2.epoch shouldBe "00ff00ff"
            rig.state.leave(sessionRoomId = rig.room, memberId = a)
            val delta3 =
                rig.state
                    .drain()
                    .single()
                    .packet
                    .shouldBeInstanceOf<EncounterMapPacket.Delta>()
            delta3.removed shouldContainExactly listOf(a.toString())
            delta3.changed.shouldBeEmpty()
            (delta3.seq > delta2.seq) shouldBe true
        }

        test("a person who is not registered is never a recipient; a dropped viewer stops receiving") {
            val rig = Rig()
            val a = member()
            val stranger = member()
            rig.enter(a)
            rig.state
                .drain()
                .single()
                .recipients shouldBe listOf(a.toString())
            rig.state.leave(sessionRoomId = rig.room, memberId = a, dropViewer = true)
            rig.state.drain().shouldBeEmpty()
            stranger.toString().let { id ->
                rig.state
                    .drain()
                    .flatMap { it.recipients }
                    .contains(id) shouldBe false
            }
        }

        test("a delta over the list limit of the receiver becomes a reset (fail closed)") {
            val rig = Rig()
            val viewer = member()
            rig.state.addViewer(
                spaceId = rig.space,
                sessionRoomId = rig.room,
                livekitRoom = "lk-room",
                layout = EncounterRoomLayout.HALL,
                memberId = viewer,
            )
            rig.state.drain()
            // 70 different people come and go within one delivery interval: 70 removals do not fit the 64 the receiver accepts
            repeat(70) {
                val m = member()
                rig.enter(m)
                rig.state.leave(sessionRoomId = rig.room, memberId = m)
            }
            val pending = rig.state.drain().single()
            pending.packet.shouldBeInstanceOf<EncounterMapPacket.Reset>()
            viewer.toString().let { it in pending.recipients } shouldBe true
            // the largest delta that is still sent is far below the byte limit
            val biggest =
                encounterMapPayload(
                    EncounterMapPacket
                        .Delta(
                            epoch = "00ff00ff",
                            seq = 1,
                            changed = emptyList(),
                            removed =
                                (0 until 64).map {
                                    Uuid.random().toString()
                                },
                        ),
                ).toByteArray()
                    .size
            biggest shouldBeLessThanOrEqual ENCOUNTER_MAP_MAX_PAYLOAD_BYTES
        }

        test("clear queues one reset for the viewers, delivered by the next drain") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            rig.state.drain()
            rig.state.clear(rig.room)
            val pending = rig.state.drain().single()
            pending.recipients shouldBe listOf(a.toString())
            pending.packet.shouldBeInstanceOf<EncounterMapPacket.Reset>()
        }

        test("retain queues a reset for a viewer it drops, and none for the viewers it keeps") {
            val rig = Rig()
            val a = member()
            val b = member()
            rig.enter(a)
            rig.enter(b)
            rig.state.drain()
            rig.state.retain(sessionRoomId = rig.room, walkers = setOf(a, b), viewers = setOf(a))
            val pending = rig.state.drain().single()
            pending.recipients shouldBe listOf(b.toString())
            pending.livekitRoom shouldBe "lk-room"
            pending.packet.shouldBeInstanceOf<EncounterMapPacket.Reset>()
            rig.state.retain(sessionRoomId = rig.room, walkers = setOf(a, b), viewers = setOf(a))
            rig.state.drain().shouldBeEmpty()
        }

        test("a snapshot of an unknown session is an empty map; a new state has another epoch only if constructed so") {
            val state = EncounterMapState()
            val snap = state.snapshot(sessionRoomId = Uuid.random(), memberId = member())
            snap.positions.shouldBeEmpty()
            snap.self.onMap shouldBe false
            snap.seq shouldBe 0
            (EncounterMapState().epoch == EncounterMapState().epoch) shouldBe false
        }

        test("nothing of a left person stays behind: no history, only the current tile") {
            val rig = Rig()
            val a = member()
            rig.enter(a)
            rig.clock.ms += 1_000
            rig.state.step(spaceId = rig.space, memberId = a, dir = EncounterMapFacing.WEST)
            rig.state.leave(sessionRoomId = rig.room, memberId = a, dropViewer = true)
            rig.state
                .snapshot(sessionRoomId = rig.room, memberId = a)
                .positions
                .shouldBeEmpty()
            rig.state.trackedSessions() shouldBe 0
        }

        test("concurrency: 25 people with 200 steps each never share a tile") {
            val clock = Clock()
            val state = EncounterMapState(monoMs = { clock.ms.also { clock.ms += 1 } })
            val space = Uuid.random()
            val room = Uuid.random()
            val people = (0 until ENCOUNTER_MAP_MAX_WALKERS).map { member() }
            people.forEach {
                state.enter(
                    spaceId = space,
                    sessionRoomId = room,
                    livekitRoom = "lk",
                    layout = EncounterRoomLayout.HALL,
                    memberId = it,
                    initials = "AB",
                )
            }
            val dirs = EncounterMapFacing.entries
            runBlocking {
                people
                    .mapIndexed { index, p ->
                        async(Dispatchers.Default) {
                            repeat(200) { n -> state.step(spaceId = space, memberId = p, dir = dirs[(n + index) % dirs.size]) }
                        }
                    }.awaitAll()
            }
            val tiles = state.snapshot(sessionRoomId = room, memberId = people.first()).positions.map { it.x to it.y }
            tiles shouldHaveSize ENCOUNTER_MAP_MAX_WALKERS
            tiles.toSet() shouldHaveSize ENCOUNTER_MAP_MAX_WALKERS
        }
    })
