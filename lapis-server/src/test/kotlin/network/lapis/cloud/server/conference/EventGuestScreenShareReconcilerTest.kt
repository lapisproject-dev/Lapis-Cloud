package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.282 -- [EventGuestScreenShareReconciler]: an event guest shares the screen only while a moderator allowed exactly this session. Corrects a
 * token that lists (or implies) the screen share, mutes a share without a grant, removes a guest who switches it on again, drops a grant when the
 * sid changes, leaves the egress, members and non-event rooms alone, and never throws.
 */
class EventGuestScreenShareReconcilerTest :
    FunSpec({
        val fixtures = EventOnlineFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { fixtures.cleanUp() }

        fun eventRoom(bound: Boolean = true): Pair<Uuid, String> {
            val creator = fixtures.member(role = AccountRole.ADMIN)
            val id = Uuid.random()
            val name = "lc-$id"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Eventraum"
                    it[description] = ""
                    it[livekitRoomName] = name
                    it[createdByMemberId] = creator
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                    it[participantsMayUnmute] = true
                }
            }
            if (bound) {
                val eventId = fixtures.event(createdBy = creator)
                transaction {
                    EventConferenceTable.insert {
                        it[EventConferenceTable.eventId] = eventId
                        it[conferenceRoomId] = id
                        it[livekitRoomName] = name
                        it[status] = EventConferenceStatus.ACTIVE
                        it[claimToken] = Uuid.random()
                        it[claimedAt] = now
                        it[windowOpensAt] = LocalDateTime(2030, 6, 15, 17, 45)
                        it[windowClosesAt] = LocalDateTime(2030, 6, 15, 21, 0)
                        it[createdAt] = now
                        it[endedAt] = null
                    }
                }
            }
            return id to name
        }

        fun reconciler(
            lk: ModerationFakeLiveKit,
            shares: EventGuestScreenShareGrants = EventGuestScreenShareGrants(),
        ) = EventGuestScreenShareReconciler(
            liveKitAdminClient = lk,
            grants = ConferenceAudioGrantState(),
            screenShareGrants = shares,
            liveKitEnabled = true,
        )

        test("a guest with an old token (empty source list = every source) gets a camera-only permission, keeping the microphone it has") {
            val lk = ModerationFakeLiveKit()
            val (_, room) = eventRoom()
            val guest = "evg-${Uuid.random()}"
            lk.add(room = room, participant = guestParticipant(identity = guest, sid = "PA_1", permissionSources = emptyList()))
            reconciler(lk).tick()
            lk.permissionUpdates
                .single()
                .permission.canPublishSources shouldBe listOf("CAMERA", "MICROPHONE")
            // second tick: already correct, nothing to do
            reconciler(lk).tick()
            lk.permissionUpdates.size shouldBe 1
        }

        test("a guest with a screen share source and an open screen share track loses the source and the track is muted") {
            val lk = ModerationFakeLiveKit()
            val (_, room) = eventRoom()
            val guest = "evg-${Uuid.random()}"
            lk.add(
                room = room,
                participant =
                    guestParticipant(
                        identity = guest,
                        sid = "PA_1",
                        screenShareOpen = true,
                        permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                    ),
            )
            reconciler(lk).tick()
            lk.permissionUpdates
                .single()
                .permission.canPublishSources shouldBe listOf("CAMERA")
            lk.screenShareMutes.map { it.trackSid } shouldContainExactly listOf("TR_scr_PA_1")
        }

        test("the granted guest with exactly this sid is left alone; the same identity with a new sid (reconnect) loses the grant") {
            val lk = ModerationFakeLiveKit()
            val (roomId, room) = eventRoom()
            val guest = "evg-${Uuid.random()}"
            val shares = EventGuestScreenShareGrants()
            shares.tryGrant(roomId = roomId, identity = guest, participantSid = "PA_1")
            lk.add(
                room = room,
                participant =
                    guestParticipant(
                        identity = guest,
                        sid = "PA_1",
                        screenShareOpen = true,
                        permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                    ),
            )
            reconciler(lk, shares).tick()
            lk.permissionUpdates.shouldBeEmpty()
            lk.screenShareMutes.shouldBeEmpty()
            // the guest reconnects: new sid, a token that (by LiveKit's refresh) still names the screen share
            lk.participants[room]!!.clear()
            lk.add(
                room = room,
                participant =
                    guestParticipant(
                        identity = guest,
                        sid = "PA_2",
                        screenShareOpen = true,
                        permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                    ),
            )
            reconciler(lk, shares).tick()
            shares.current(roomId) shouldBe null
            lk.permissionUpdates
                .single()
                .permission.canPublishSources shouldBe listOf("CAMERA")
            lk.screenShareMutes.map { it.trackSid } shouldContainExactly listOf("TR_scr_PA_2")
        }

        test("a track the guest switches on again after our mute removes the guest from the room") {
            val lk = ModerationFakeLiveKit()
            lk.stubbornScreenShare = true
            val (_, room) = eventRoom()
            val guest = "evg-${Uuid.random()}"
            lk.add(
                room = room,
                participant =
                    guestParticipant(
                        identity = guest,
                        sid = "PA_1",
                        screenShareOpen = true,
                        permissionSources = listOf("CAMERA"),
                    ),
            )
            val reconciler = reconciler(lk)
            reconciler.tick()
            lk.screenShareMutes.size shouldBe 1
            lk.find(room = room, identity = guest) shouldBe lk.roster(room).single()
            reconciler.tick()
            lk.roster(room).shouldBeEmpty()
        }

        test("a grant whose guest left is dropped; an empty registry (restart) means every share is taken back") {
            val lk = ModerationFakeLiveKit()
            val (roomId, room) = eventRoom()
            val shares = EventGuestScreenShareGrants()
            shares.tryGrant(roomId = roomId, identity = "evg-gone", participantSid = "PA_gone")
            lk.add(room = room, participant = guestParticipant(identity = "evg-other", sid = "PA_x", permissionSources = listOf("CAMERA")))
            reconciler(lk, shares).tick()
            shares.current(roomId) shouldBe null
        }

        test("the egress, members and rooms that are no event rooms are never touched") {
            val lk = ModerationFakeLiveKit()
            val (_, event) = eventRoom()
            val (_, plain) = eventRoom(bound = false)
            lk.add(
                room = event,
                participant =
                    guestParticipant(
                        identity = "evg-egress",
                        sid = "PA_e",
                        screenShareOpen = true,
                        permissionSources = emptyList(),
                        kind = "EGRESS",
                    ),
            )
            lk.add(room = event, participant = liveParticipant(identity = Uuid.random().toString(), sid = "PA_m"))
            lk.add(
                room = plain,
                participant =
                    guestParticipant(
                        identity = "evg-${Uuid.random()}",
                        sid = "PA_p",
                        screenShareOpen = true,
                        permissionSources = emptyList(),
                    ),
            )
            reconciler(lk).tick()
            lk.permissionUpdates.shouldBeEmpty()
            lk.screenShareMutes.shouldBeEmpty()
        }

        test("a LiveKit outage is swallowed (a warning without ids), a failing guest does not keep the others from being corrected") {
            val lk = ModerationFakeLiveKit()
            val (_, room) = eventRoom()
            val bad = "evg-${Uuid.random()}"
            val good = "evg-${Uuid.random()}"
            lk.add(room = room, participant = guestParticipant(identity = bad, sid = "PA_b", permissionSources = emptyList()))
            lk.add(room = room, participant = guestParticipant(identity = good, sid = "PA_g", permissionSources = emptyList()))
            lk.failPermissionFor += bad
            reconciler(lk).tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(good)
            lk.failList = true
            reconciler(lk).tick()
        }

        test("a disabled reconciler does nothing") {
            val lk = ModerationFakeLiveKit()
            val (_, room) = eventRoom()
            lk.add(
                room = room,
                participant = guestParticipant(identity = "evg-${Uuid.random()}", sid = "PA_1", permissionSources = emptyList()),
            )
            EventGuestScreenShareReconciler(
                liveKitAdminClient = lk,
                grants = ConferenceAudioGrantState(),
                screenShareGrants = EventGuestScreenShareGrants(),
                liveKitEnabled = false,
            ).tick()
            lk.permissionUpdates.shouldBeEmpty()
        }
    })
