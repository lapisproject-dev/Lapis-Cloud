package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.conference.LiveKitTrackInfo
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val T0 = Instant.parse("2026-10-08T10:00:00Z")

/** Welle V1.9.80 -- the reconciler: orphan rooms, unexpected listeners, non-microphone tracks, quiet violations, expiry. */
class EncounterTableReconcilerTest :
    FunSpec({
        val session = Uuid.random()
        val a = Uuid.random()
        val b = Uuid.random()

        class Rig {
            val liveKit = FakeEncounterLiveKit()
            val state = EncounterTableState()
            var now: Instant = T0
            val reconciler =
                EncounterTableReconciler(liveKitAdminClient = liveKit, tableState = state, liveKitEnabled = true, clock = { now })

            /** Seats [who] and creates the room at the fake LiveKit like the service would. */
            suspend fun sit(
                who: Uuid,
                table: Int,
                seat: Int,
            ): String {
                val out =
                    state.join(
                        sessionRoomId = session,
                        memberId = who,
                        table = table,
                        seat = seat,
                        present = setOf(a, b),
                        seatsPerTable = 4,
                        now = now,
                    ) as EncounterTableState.JoinOutcome.Ok
                EncounterTableRooms.apply(liveKit = liveKit, rotations = out.rotations)
                return out.assignment.room
            }
        }

        test("an lc-et room that is not a current table room is deleted; the session room and other rooms are left alone") {
            val rig = Rig()
            val current = rig.sit(who = a, table = 0, seat = 0)
            rig.liveKit.createRoom(name = "${ENCOUNTER_TABLE_ROOM_PREFIX}phantom", maxParticipants = 4, emptyTimeoutSeconds = 60)
            rig.liveKit.createRoom(name = "lc-${Uuid.random()}", maxParticipants = 25, emptyTimeoutSeconds = 60)
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldContain "${ENCOUNTER_TABLE_ROOM_PREFIX}phantom"
            rig.liveKit.deletedRooms shouldNotContain current
            rig.liveKit.deletedRooms.size shouldBe 1
            rig.liveKit.hasRoom(current) shouldBe true
        }

        test("a connected identity that is not assigned rotates the table; the intruder's room is gone") {
            val rig = Rig()
            val room = rig.sit(who = a, table = 0, seat = 0)
            rig.liveKit.connect(room = room, identity = a.toString())
            rig.liveKit.connect(room = room, identity = Uuid.random().toString())
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldContain room
            rig.state.assignmentOf(sessionRoomId = session, memberId = a, now = T0)!!.room shouldBe rig.liveKit.createdRooms.last()
            (rig.state.assignmentOf(sessionRoomId = session, memberId = a, now = T0)!!.room != room) shouldBe true
        }

        test("a camera or screen-share track rotates the table; a microphone track does not") {
            val rig = Rig()
            val room = rig.sit(who = a, table = 0, seat = 0)
            rig.liveKit.connect(room = room, identity = a.toString())
            rig.liveKit.tracks[room to a.toString()] = listOf(LiveKitTrackInfo(sid = "T1", type = "AUDIO", source = "MICROPHONE"))
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldNotContain room
            rig.liveKit.tracks[room to a.toString()] = listOf(LiveKitTrackInfo(sid = "T2", type = "VIDEO", source = "CAMERA"))
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldContain room
        }

        test("in a quieted table a participant with the publish grant or any track rotates the table") {
            val rig = Rig()
            rig.sit(who = a, table = 0, seat = 0)
            val quiet = rig.state.quiet(sessionRoomId = session, table = 0, until = T0 + 5.minutes, now = T0)!!
            EncounterTableRooms.apply(liveKit = rig.liveKit, rotations = listOf(quiet))
            val room = quiet.newRoom!!
            rig.liveKit.connect(room = room, identity = a.toString(), canPublish = false, canPublishData = false)
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldNotContain room // a correctly muted participant is fine
            rig.liveKit.connect(room = room, identity = a.toString(), canPublish = true, canPublishData = false)
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldContain room
        }

        test("expired quiets are lifted and the table rotated so the members get a publishing token") {
            val rig = Rig()
            rig.sit(who = a, table = 0, seat = 0)
            val quiet = rig.state.quiet(sessionRoomId = session, table = 0, until = T0 + 5.minutes, now = T0)!!
            EncounterTableRooms.apply(liveKit = rig.liveKit, rotations = listOf(quiet))
            rig.now = T0 + 6.minutes
            rig.reconciler.tick()
            rig.liveKit.deletedRooms shouldContain quiet.newRoom!!
            rig.state.assignmentOf(sessionRoomId = session, memberId = a, now = rig.now)!!.quieted shouldBe false
        }

        /** A LiveKit that lets a test act while the reconciler is in the middle of its Twirp calls (the real ones take hundreds of ms). */
        class RacingLiveKit(
            private val inner: FakeEncounterLiveKit,
            var beforeListRooms: suspend () -> Unit = {},
            var beforeListParticipants: suspend (String) -> Unit = {},
        ) : LiveKitAdminClient by inner {
            override suspend fun listRooms(): List<LiveKitRoomInfo> {
                beforeListRooms()
                return inner.listRooms()
            }

            override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> {
                beforeListParticipants(room)
                return inner.listParticipants(room)
            }
        }

        test("a table room that is created while the tick runs is not deleted as an orphan") {
            val rig = Rig()
            val racing = RacingLiveKit(rig.liveKit)
            val reconciler =
                EncounterTableReconciler(liveKitAdminClient = racing, tableState = rig.state, liveKitEnabled = true, clock = { rig.now })
            var created: String? = null
            // somebody sits down at a NEW table (state first, then the LiveKit room) right before the room list is read
            racing.beforeListRooms = {
                if (created == null) created = rig.sit(who = b, table = 1, seat = 0)
            }
            reconciler.tick()
            val room = created!!
            rig.liveKit.deletedRooms shouldNotContain room
            rig.liveKit.hasRoom(room) shouldBe true
        }

        test("somebody who sits down and connects while the participants are being listed does not get the whole table rotated") {
            val rig = Rig()
            val room = rig.sit(who = a, table = 0, seat = 0)
            rig.liveKit.connect(room = room, identity = a.toString())
            val racing = RacingLiveKit(rig.liveKit)
            val reconciler =
                EncounterTableReconciler(liveKitAdminClient = racing, tableState = rig.state, liveKitEnabled = true, clock = { rig.now })
            var joined = false
            racing.beforeListParticipants = {
                if (!joined) {
                    joined = true
                    rig.sit(who = b, table = 0, seat = 1) // same table: no rotation, same room
                    rig.liveKit.connect(room = room, identity = b.toString())
                }
            }
            reconciler.tick()
            joined shouldBe true
            rig.liveKit.deletedRooms shouldNotContain room
            rig.state.assignmentOf(sessionRoomId = session, memberId = b, now = T0)!!.room shouldBe room
        }

        test("LiveKit not reachable: the tick does not throw and changes nothing") {
            val rig = Rig()
            val room = rig.sit(who = a, table = 0, seat = 0)
            rig.liveKit.failAll = true
            rig.reconciler.tick()
            rig.state.assignmentOf(sessionRoomId = session, memberId = a, now = T0)!!.room shouldBe room
        }

        test("a disabled reconciler does nothing") {
            val rig = Rig()
            rig.liveKit.createRoom(name = "${ENCOUNTER_TABLE_ROOM_PREFIX}phantom", maxParticipants = 4, emptyTimeoutSeconds = 60)
            EncounterTableReconciler(liveKitAdminClient = rig.liveKit, tableState = rig.state, liveKitEnabled = false).tick()
            rig.liveKit.deletedRooms.size shouldBe 0
        }

        test("a silenced identity that holds the publish grant or a track in a table room rotates the table") {
            val liveKit = FakeEncounterLiveKit()
            val state = EncounterTableState()
            val moderation = EncounterModerationState()
            val reconciler =
                EncounterTableReconciler(
                    liveKitAdminClient = liveKit,
                    tableState = state,
                    liveKitEnabled = true,
                    moderationState = moderation,
                    clock = { T0 },
                )
            val out =
                state.join(
                    sessionRoomId = session,
                    memberId = a,
                    table = 0,
                    seat = 0,
                    present = setOf(a),
                    seatsPerTable = 4,
                    now = T0,
                ) as EncounterTableState.JoinOutcome.Ok
            EncounterTableRooms.apply(liveKit = liveKit, rotations = out.rotations)
            val room = out.assignment.room
            liveKit.connect(room = room, identity = a.toString(), canPublish = true, canPublishData = false)
            reconciler.tick()
            liveKit.deletedRooms shouldNotContain room // not silenced: fine
            moderation.silence(sessionRoomId = session, memberId = a)
            reconciler.tick()
            liveKit.deletedRooms shouldContain room
        }
    })
