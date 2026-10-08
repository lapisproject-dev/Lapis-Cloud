package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Welle V1.9.80 -- table choice end to end on H2 with a fake LiveKit: access, conflicts, throttle, release points, rotation, no trace. */
class EncounterTableSelectionTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        test("sitting down returns the token of an opaque table room and is visible to everybody present, without the plenum seat") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.spaceId, seat = 3) }.getOrThrow()
                val dto =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 0) }
                        .getOrThrow()
                dto.table shouldBe 1
                dto.tableSeat shouldBe 0
                dto.canPublish shouldBe true
                dto.join.livekitRoomName shouldStartWith ENCOUNTER_TABLE_ROOM_PREFIX
                dto.join.livekitRoomName shouldNotContain w.spaceId
                dto.join.identity shouldBe w.a.toString()
                // the room was created with the table's seat count as its ceiling
                w.rig.liveKit.createRoomArgs
                    .single { it.first == dto.join.livekitRoomName }
                    .second shouldBe 2
                // the name at the table is the initials only
                (dto.join.displayName.length <= 2) shouldBe true
                // the bench seat is freed, the table position is public
                val seen = w.rig.asMember(client = client, member = w.steward) { it.listPresent(w.spaceId) }.getOrThrow()
                seen.single { it.memberId == w.a.toString() }.seat shouldBe null
                seen.single { it.memberId == w.a.toString() }.table shouldBe 1
                seen.single { it.memberId == w.a.toString() }.tableSeat shouldBe 0
                seen.single { it.memberId == w.b.toString() }.table shouldBe null
                // the table list names seat numbers, never persons
                val tables = w.rig.asMember(client = client, member = w.b) { it.listTables(w.spaceId) }.getOrThrow()
                tables.map { it.table } shouldBe listOf(0, 1, 2)
                tables.single { it.table == 1 }.occupiedSeats shouldBe listOf(0)
                tables.single { it.table == 0 }.occupiedSeats.shouldBeEmpty()
                tables.all { it.seats == 2 && !it.quieted } shouldBe true
            }
        }

        test("a bench seat frees the table seat and vice versa: one place per person") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }.getOrThrow()
                val list = w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.spaceId, seat = 5) }.getOrThrow()
                list.single { it.memberId == w.a.toString() }.table shouldBe null
                list.single { it.memberId == w.a.toString() }.seat shouldBe 5
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }.getOrThrow()
                w.rig
                    .asMember(client = client, member = w.a) { it.listPresent(w.spaceId) }
                    .getOrThrow()
                    .single { it.memberId == w.a.toString() }
                    .seat shouldBe null
            }
        }

        test("a taken seat is a ConflictException, a full table room is not exceeded, the seat range is BadRequest") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.b,
                    ) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 0) }
                    .failure<ConflictException>()
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 1) }.getOrThrow()
                // 2 seats per table: seat 2 and table 3 are out of range, as are negatives
                w.rig
                    .asMember(
                        client = client,
                        member = w.c,
                    ) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 2) }
                    .failure<BadRequestException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.c,
                    ) { it.joinTable(spaceId = w.spaceId, table = 3, seat = 0) }
                    .failure<BadRequestException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.c,
                    ) { it.joinTable(spaceId = w.spaceId, table = -1, seat = 0) }
                    .failure<BadRequestException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.c,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = -1) }
                    .failure<BadRequestException>()
            }
        }

        test("access: not present, closed room, office holders, blocked people and rooms without tables are Forbidden") {
            val w = fx.tableWorld()
            val churchWorld = fx.tableWorld(tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE)
            val outsider = fx.createMember()
            encounterApp {
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                    .failure<ForbiddenException>()
                openAndEnter(w = w, w.steward, w.pulpit, w.a, w.b)
                w.rig
                    .asMember(client = client, member = outsider) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                    .failure<ForbiddenException>()
                listOf(w.steward, w.pulpit).forEach { officer ->
                    w.rig
                        .asMember(client = client, member = officer) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                        .failure<ForbiddenException>()
                    // an office holder never gets a table token
                    w.rig.asMember(client = client, member = officer) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                }
                // removed (blocked) people are refused
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.removeFromSpace(spaceId = w.spaceId, memberId = w.b.toString()) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.b,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                    .failure<ForbiddenException>()
                // a church-service room has no tables at all
                churchWorld.rig.asMember(client = client, member = churchWorld.steward) { it.openSpace(churchWorld.spaceId) }.getOrThrow()
                churchWorld.rig
                    .asMember(
                        client = client,
                        member = churchWorld.a,
                    ) { it.enterSpace(spaceId = churchWorld.spaceId, consent = null) }
                    .getOrThrow()
                churchWorld.rig
                    .asMember(client = client, member = churchWorld.a) {
                        it.joinTable(spaceId = churchWorld.spaceId, table = 0, seat = 0)
                    }.failure<ForbiddenException>()
                churchWorld.rig
                    .asMember(client = client, member = churchWorld.a) { it.listTables(churchWorld.spaceId) }
                    .getOrThrow()
                    .shouldBeEmpty()
                // after closing, nobody can sit
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                    .failure<ForbiddenException>()
            }
        }

        test("throttle: a second change within the window is ServiceBusyException (not Conflict); the token has its own limiter") {
            val w =
                fx.tableWorld(
                    rig =
                        EncounterRig(
                            tableLimiterOverride = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                            tableTokenLimiterOverride = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        ),
                )
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }
                    .failure<ServiceBusyException>()
                w.rig.asMember(client = client, member = w.a) { it.leaveTable(w.spaceId) }.failure<ServiceBusyException>()
                w.rig
                    .asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }
                    .getOrThrow()!!
                    .table shouldBe 0
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.failure<ServiceBusyException>()
            }
        }

        test("leaving the table deletes its room (everybody is disconnected at once); the remaining person gets a NEW room") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                val first =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                        .getOrThrow()
                val second =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.b,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }
                        .getOrThrow()
                second.join.livekitRoomName shouldBe first.join.livekitRoomName
                w.rig.asMember(client = client, member = w.a) { it.leaveTable(w.spaceId) }.getOrThrow()
                w.rig.liveKit.deletedRooms shouldContain first.join.livekitRoomName
                // b follows to table 1 (leaving a table nobody else sits at is not a disturbance)
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }.getOrThrow()
                val renewed = w.rig.asMember(client = client, member = w.b) { it.tableToken(w.spaceId).token }.getOrThrow()!!
                (renewed.join.livekitRoomName != first.join.livekitRoomName) shouldBe true
                w.rig.liveKit.createdRooms shouldContain renewed.join.livekitRoomName
                // the one who left has no token any more, and leaving again is a no-op
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                w.rig.asMember(client = client, member = w.a) { it.leaveTable(w.spaceId) }.getOrThrow()
                // the last one leaving deletes the room and creates none
                val created = w.rig.liveKit.createdRooms.size
                w.rig.asMember(client = client, member = w.b) { it.leaveTable(w.spaceId) }.getOrThrow()
                w.rig.liveKit.deletedRooms shouldContain renewed.join.livekitRoomName
                w.rig.liveKit.createdRooms.size shouldBe created
            }
        }

        test(
            "the table seat is dropped, and the table rotated, on re-entry, leaving the room, removal, closing and a vanished presence row",
        ) {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                val roomId = fx.openSessionRoom(w.space)!!
                // re-entry
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.enterSpace(spaceId = w.spaceId, consent = null) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                // leaving the room
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.leaveSpace(w.spaceId) }.getOrThrow()
                w.rig.tableState.assignmentOf(sessionRoomId = roomId, memberId = w.a, now = Clock.System.now()) shouldBe null
                // removal
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.removeFromSpace(spaceId = w.spaceId, memberId = w.b.toString()) }
                    .getOrThrow()
                w.rig.tableState.assignmentOf(sessionRoomId = roomId, memberId = w.b, now = Clock.System.now()) shouldBe null
                // a vanished presence row: the next list prunes the seat and rotates the table
                val c =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.c,
                        ) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 0) }
                        .getOrThrow()
                fx.deleteParticipation(roomId = roomId, memberId = w.c)
                w.rig.asMember(client = client, member = w.steward) { it.listPresent(w.spaceId) }.getOrThrow()
                w.rig.tableState.assignmentOf(sessionRoomId = roomId, memberId = w.c, now = Clock.System.now()) shouldBe null
                w.rig.liveKit.deletedRooms shouldContain c.join.livekitRoomName
                // closing clears everything and deletes the table rooms
                w.rig.asMember(client = client, member = w.a) { it.enterSpace(spaceId = w.spaceId, consent = null) }.getOrThrow()
                val last =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }
                        .getOrThrow()
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.spaceId) }.getOrThrow()
                w.rig.tableState.trackedSessions() shouldBe 0
                w.rig.liveKit.deletedRooms shouldContain last.join.livekitRoomName
            }
        }

        test("a person who is given an office leaves the table at once") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                val sat =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                        .getOrThrow()
                w.rig
                    .asMember(client = client, member = w.board) {
                        it.setSpaceRoles(
                            spaceId = w.spaceId,
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(
                                        memberId = w.a.toString(),
                                        role = EncounterSpaceRole.STEWARD,
                                    ),
                                ),
                        )
                    }.getOrThrow()
                w.rig.liveKit.deletedRooms shouldContain sat.join.livekitRoomName
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
            }
        }

        test(
            "a rotation between seating and the token re-read mints again for the new room; a person dropped meanwhile gets the conflict",
        ) {
            class RotatingState : EncounterTableState() {
                @Volatile var hook: ((EncounterTableState, kotlin.uuid.Uuid, kotlin.uuid.Uuid) -> Unit)? = null

                override fun assignmentOf(
                    sessionRoomId: kotlin.uuid.Uuid,
                    memberId: kotlin.uuid.Uuid,
                    now: kotlin.time.Instant,
                ): Assignment? {
                    hook?.let {
                        hook = null
                        it(this, sessionRoomId, memberId)
                    }
                    return super.assignmentOf(sessionRoomId = sessionRoomId, memberId = memberId, now = now)
                }
            }
            val state = RotatingState()
            val w = fx.tableWorld(rig = EncounterRig(tableState = state))
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                // (1) the table rotates between the seating and the re-read: the answer points at the NEW room, not at a conflict
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }.getOrThrow()
                val roomBefore = state.activeRooms(Clock.System.now()).keys.single()
                state.hook = { s, _, _ -> s.rotateRoom(roomBefore) }
                val dto =
                    w.rig
                        .asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 0) }
                        .getOrThrow()
                val current = state.activeRooms(Clock.System.now()).keys.single()
                (current != roomBefore) shouldBe true
                dto.join.livekitRoomName shouldBe current
                // (2) the person is not seated any more when the re-read happens: that stays a conflict
                state.hook = { s, session, _ -> s.leave(sessionRoomId = session, memberId = w.c) }
                w.rig
                    .asMember(client = client, member = w.c) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 0) }
                    .failure<ConflictException>()
            }
        }

        test("sitting down, leaving and listing write no audit entry") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                val before = fx.auditCount()
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.listTables(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.leaveTable(w.spaceId) }.getOrThrow()
                fx.auditCount() shouldBe before
            }
        }

        test("switching tables again right away is ServiceBusyException; the left table is not rotated a second time") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 0) }.getOrThrow()
                // b follows to table 1 (leaving a table nobody else sits at is not a disturbance)
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }.getOrThrow()
                val renewed = w.rig.asMember(client = client, member = w.b) { it.tableToken(w.spaceId).token }.getOrThrow()!!
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 0) }
                    .failure<ServiceBusyException>()
                w.rig
                    .asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }
                    .getOrThrow()!!
                    .table shouldBe 1
                // b's table was not touched by the refused attempt
                w.rig
                    .asMember(
                        client = client,
                        member = w.b,
                    ) { it.tableToken(w.spaceId).token }
                    .getOrThrow()!!
                    .join.livekitRoomName shouldBe
                    renewed.join.livekitRoomName
            }
        }
    })
