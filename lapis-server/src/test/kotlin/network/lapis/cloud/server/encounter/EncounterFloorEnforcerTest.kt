package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.LiveKitTrackInfo
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_TOPIC
import network.lapis.cloud.shared.domain.EncounterFloorSignal
import network.lapis.cloud.shared.domain.encounterFloorPacket
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/** V1.9.138 -- the clock and the safety net of the floor: expiry, the revoke ladder, the 15 s verification, no path that leaves a right behind. */
class EncounterFloorEnforcerTest :
    FunSpec({
        class Scene {
            val time = TestTimeSource()
            val state = EncounterFloorState(time)
            val liveKit = FakeEncounterLiveKit()
            val enforcer = EncounterFloorEnforcer(liveKit = liveKit, floorState = state, liveKitEnabled = true, sleeper = { })
            val room = Uuid.random()
            val roomName = "lc-$room"
            val holder = Uuid.random()
            val mic = LiveKitTrackInfo(sid = "TR_mic", type = "AUDIO", source = "MICROPHONE", muted = false)

            /** The holder is connected and has the floor (permission raised like the service does). */
            suspend fun startFloor(tracks: List<LiveKitTrackInfo> = listOf(mic)) {
                liveKit.connect(room = roomName, identity = holder.toString(), canPublish = false, canPublishData = true)
                liveKit.tracks[roomName to holder.toString()] = tracks
                state.tryGrant(roomId = room, livekitRoomName = roomName, memberId = holder, displayName = "Redner")
                enforcer.applyGrant(livekitRoomName = roomName, memberId = holder)
            }

            fun floorSignals() =
                liveKit.sentData.filter { it.second == ENCOUNTER_FLOOR_TOPIC }.map {
                    encounterFloorPacket(
                        fromParticipant = false,
                        payload = it.third,
                    )
                }
        }

        test("an expired floor is taken away: can_publish off, sources stay MICROPHONE, the microphone muted, OFF sent, grace remembered") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.time += 119.seconds
                s.enforcer.tick()
                s.state.current(s.room).shouldNotBeNull()
                s.time += 2.seconds
                s.enforcer.tick()
                s.state.current(s.room).shouldBeNull()
                val last = s.liveKit.permissionUpdates.last()
                last.third.canPublish shouldBe false
                last.third.canSubscribe shouldBe true
                last.third.canPublishData shouldBe true
                last.third.canPublishSources shouldContainExactly listOf("MICROPHONE")
                s.liveKit.muted shouldContain Triple(s.roomName, s.holder.toString(), "TR_mic")
                s.floorSignals().last() shouldBe EncounterFloorSignal.Off
                s.state.recentlyEndedWithinGrace(roomId = s.room, memberId = s.holder) shouldBe true
                s.liveKit.removed.shouldBeEmpty()
            }
        }

        test("if the demotion fails three times the person is disconnected, and the floor is then gone") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.failUpdatePermission = true
                s.enforcer.revoke(roomId = s.room, memberId = s.holder, reason = RevokeReason.ENDED)
                s.liveKit.removed shouldContainExactly listOf(s.roomName to s.holder.toString())
                s.state.current(s.room).shouldBeNull()
            }
        }

        test(
            "if even the disconnect fails the floor stays as revoking (not valid), is retried only every 5 s, and ends when LiveKit recovers",
        ) {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.failUpdatePermission = true
                s.liveKit.failRemoveParticipant = true
                s.enforcer.revoke(roomId = s.room, memberId = s.holder, reason = RevokeReason.ENDED)
                val pending = s.state.current(s.room).shouldNotBeNull()
                pending.revoking shouldBe true
                s.state.isValid(roomId = s.room, memberId = s.holder) shouldBe false
                // LiveKit is back, but the retry interval has not passed
                s.liveKit.failUpdatePermission = false
                s.liveKit.failRemoveParticipant = false
                s.time += 3.seconds
                s.enforcer.tick()
                s.state.current(s.room).shouldNotBeNull()
                s.time += 3.seconds
                s.enforcer.tick()
                s.state.current(s.room).shouldBeNull()
                s.liveKit.permissionUpdates
                    .last()
                    .third.canPublish shouldBe false
            }
        }

        test("revoking somebody who is not connected any more succeeds without a LiveKit write") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.forgetParticipant(room = s.roomName, identity = s.holder.toString())
                s.enforcer.revoke(roomId = s.room, memberId = s.holder, reason = RevokeReason.LEFT)
                s.state.current(s.room).shouldBeNull()
                s.liveKit.removed.shouldBeEmpty()
            }
        }

        test("revoke of somebody who does not hold the floor does nothing") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.enforcer.revoke(roomId = s.room, memberId = Uuid.random(), reason = RevokeReason.ENDED)
                s.enforcer.endForMember(roomId = Uuid.random(), memberId = s.holder, reason = RevokeReason.ENDED)
                s.state
                    .current(s.room)
                    .shouldNotBeNull()
                    .revoking shouldBe false
            }
        }

        test("verification every 15 s: a holder who is gone twice in a row loses the floor, once is forgiven") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.forgetParticipant(room = s.roomName, identity = s.holder.toString())
                s.time += 16.seconds
                s.enforcer.tick()
                s.state
                    .current(s.room)
                    .shouldNotBeNull()
                    .absentChecks shouldBe 1
                // back again: the counter starts over
                s.liveKit.connect(room = s.roomName, identity = s.holder.toString(), canPublish = true, canPublishData = true)
                s.time += 16.seconds
                s.enforcer.tick()
                s.state
                    .current(s.room)
                    .shouldNotBeNull()
                    .absentChecks shouldBe 0
                s.liveKit.forgetParticipant(room = s.roomName, identity = s.holder.toString())
                s.time += 16.seconds
                s.enforcer.tick()
                s.time += 16.seconds
                s.enforcer.tick()
                s.state.current(s.room).shouldBeNull()
            }
        }

        test("a LiveKit reconnect that reset the permission is repaired within one verification") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.permissions[s.roomName to s.holder.toString()] =
                    network.lapis.cloud.server.conference.LiveKitParticipantPermission(
                        canPublish = false,
                        canPublishData = true,
                        canSubscribe = true,
                    )
                val before = s.liveKit.permissionUpdates.size
                s.time += 16.seconds
                s.enforcer.tick()
                s.liveKit.permissionUpdates.size shouldBe before + 1
                s.liveKit.permissionUpdates
                    .last()
                    .third.canPublish shouldBe true
                s.liveKit.permissionUpdates
                    .last()
                    .third.canPublishSources shouldContainExactly listOf("MICROPHONE")
                s.state.isValid(roomId = s.room, memberId = s.holder) shouldBe true
            }
        }

        test("a revoke that happens while a verification is in flight is not undone by the re-apply") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.permissions[s.roomName to s.holder.toString()] =
                    network.lapis.cloud.server.conference.LiveKitParticipantPermission(
                        canPublish = false,
                        canPublishData = true,
                        canSubscribe = true,
                    )
                // The snapshot of verify() shows the holder without can_publish; then the floor is ended before verify() goes on.
                s.liveKit.afterNextListSnapshot = {
                    s.enforcer.revoke(roomId = s.room, memberId = s.holder, reason = RevokeReason.ENDED)
                }
                val before = s.liveKit.permissionUpdates.size
                s.time += 16.seconds
                s.enforcer.tick()
                s.state.current(s.room).shouldBeNull()
                val updates = s.liveKit.permissionUpdates.drop(before)
                updates.none { it.third.canPublish } shouldBe true
                updates.isNotEmpty() shouldBe true
            }
        }

        test("a verification re-sends the state (YOU to the holder, ON to everybody) while the floor is valid") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.time += 20.seconds
                s.enforcer.tick()
                s.floorSignals().last() shouldBe EncounterFloorSignal.On(100)
                s.liveKit.sentDataTo
                    .last()
                    .destinations shouldContainExactly listOf(s.holder.toString())
                encounterFloorPacket(
                    fromParticipant = false,
                    payload =
                        s.liveKit.sentDataTo
                            .last()
                            .payload,
                ) shouldBe EncounterFloorSignal.You(100)
            }
        }

        test("a camera track or a second track during the floor ends it and disconnects the person") {
            listOf(
                listOf(LiveKitTrackInfo(sid = "TR_cam", type = "VIDEO", source = "CAMERA", muted = false)),
                listOf(
                    LiveKitTrackInfo(sid = "TR_a", type = "AUDIO", source = "MICROPHONE", muted = false),
                    LiveKitTrackInfo(sid = "TR_b", type = "AUDIO", source = "MICROPHONE", muted = false),
                ),
                listOf(LiveKitTrackInfo(sid = "TR_s", type = "AUDIO", source = "SCREEN_SHARE_AUDIO", muted = false)),
            ).forEach { tracks ->
                runBlocking {
                    val s = Scene()
                    s.startFloor(tracks = tracks)
                    s.time += 16.seconds
                    s.enforcer.tick()
                    s.state.current(s.room).shouldBeNull()
                    s.liveKit.removed shouldContainExactly listOf(s.roomName to s.holder.toString())
                }
            }
        }

        test("a tick with LiveKit unreachable never throws and keeps a valid floor; disabled LiveKit does nothing") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                s.liveKit.failAll = true
                s.time += 16.seconds
                s.enforcer.tick()
                s.state
                    .current(s.room)
                    .shouldNotBeNull()
                    .revoking shouldBe false
                val off = EncounterFloorEnforcer(liveKit = s.liveKit, floorState = s.state, liveKitEnabled = false, sleeper = { })
                s.time += 200.seconds
                off.tick()
                s.state.current(s.room).shouldNotBeNull()
            }
        }

        test("a restart forgets the floor (the state is memory only)") {
            runBlocking {
                val s = Scene()
                s.startFloor()
                EncounterFloorState(s.time).current(s.room).shouldBeNull()
            }
        }
    })
