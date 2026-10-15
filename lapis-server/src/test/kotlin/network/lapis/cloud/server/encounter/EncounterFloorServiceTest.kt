package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import network.lapis.cloud.server.conference.LiveKitTrackInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_TOPIC
import network.lapis.cloud.shared.domain.EncounterFloorDto
import network.lapis.cloud.shared.domain.EncounterFloorSignal
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.encounterFloorPacket
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * V1.9.138 -- the temporary floor ("Rederecht") through the real service: the role matrix (one bare ForbiddenException for every refusal),
 * who may receive the floor, the single permission that is raised, one person at a time, the extension limits, the throttles and every
 * hook that must end a floor (leave, remove, silence, table, close, office).
 */
class EncounterFloorServiceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun TableWorld.roomId(): Uuid = fx.openSessionRoom(space)!!

        fun TableWorld.livekitRoom(): String = fx.livekitName(roomId())

        /** Marks [who] as connected at the fake LiveKit; office holders connect with the right to publish, like their token. */
        fun TableWorld.connect(vararg who: Uuid) {
            who.forEach { id ->
                val office = id == steward || id == pulpit
                rig.liveKit.connect(room = livekitRoom(), identity = id.toString(), canPublish = office, canPublishData = true)
            }
        }

        /** Opens the room, lets steward, pulpit, a, b and c enter and marks them connected. */
        suspend fun ApplicationTestBuilder.openWorld(w: TableWorld) {
            openAndEnter(w = w, w.steward, w.pulpit, w.a, w.b, w.c)
            w.connect(w.steward, w.pulpit, w.a, w.b, w.c)
        }

        suspend fun ApplicationTestBuilder.grant(
            w: TableWorld,
            by: Uuid,
            target: Uuid,
        ): Result<EncounterFloorDto> =
            w.rig.asMember(client = client, member = by) {
                it.grantFloor(spaceId = w.spaceId, memberId = target.toString())
            }

        suspend fun ApplicationTestBuilder.end(
            w: TableWorld,
            by: Uuid,
        ): Result<Unit> = w.rig.asMember(client = client, member = by) { it.endFloor(w.spaceId) }

        fun TableWorld.floorPackets() = rig.liveKit.sentData.filter { it.second == ENCOUNTER_FLOOR_TOPIC }

        fun TableWorld.floorPacketsTo() = rig.liveKit.sentDataTo.filter { it.topic == ENCOUNTER_FLOOR_TOPIC }

        test(
            "a grant raises exactly one permission (microphone only), sends ON to everybody and YOU to the holder, and no packet names anybody",
        ) {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                val dto = grant(w, w.steward, w.a).getOrThrow()
                dto.active shouldBe true
                dto.remainingSeconds shouldBe 120
                dto.totalSeconds shouldBe 120
                dto.holderMemberId shouldBe w.a.toString()
                dto.self shouldBe false
                w.rig.liveKit.permissionUpdates shouldHaveSize 1
                val (room, identity, permission) =
                    w.rig.liveKit.permissionUpdates
                        .single()
                room shouldBe w.livekitRoom()
                identity shouldBe w.a.toString()
                permission.canSubscribe shouldBe true
                permission.canPublish shouldBe true
                permission.canPublishData shouldBe true
                permission.canPublishSources shouldBe listOf("MICROPHONE")
                w.floorPackets() shouldHaveSize 1
                encounterFloorPacket(fromParticipant = false, payload = w.floorPackets().single().third) shouldBe
                    EncounterFloorSignal.On(120)
                w.floorPacketsTo() shouldHaveSize 1
                w.floorPacketsTo().single().destinations shouldBe listOf(w.a.toString())
                encounterFloorPacket(fromParticipant = false, payload = w.floorPacketsTo().single().payload) shouldBe
                    EncounterFloorSignal.You(120)
                (w.floorPackets().map { it.third } + w.floorPacketsTo().map { it.payload }).forEach {
                    it.decodeToString() shouldNotContain w.a.toString()
                }
                w.rig.floorState.isValid(roomId = w.roomId(), memberId = w.a) shouldBe true
            }
        }

        test("every refusal of a grant is the same bare ForbiddenException and nothing is raised") {
            val w = fx.tableWorld()
            val otherWorld = fx.tableWorld()
            val absentSteward = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.STEWARD) }
            val withdrawn = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            encounterApp {
                openWorld(w)
                openAndEnter(w = w, w.board, withdrawn)
                w.connect(w.board)
                fx.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
                val messages =
                    listOf(w.b, w.board, otherWorld.pulpit, absentSteward, withdrawn).map { who ->
                        grant(w, by = who, target = w.a).failure<ForbiddenException>().message
                    }
                messages.toSet().size shouldBe 1
                w.rig.liveKit.permissionUpdates
                    .shouldBeEmpty()
                w.floorPackets().shouldBeEmpty()
                w.rig.floorState
                    .current(w.roomId())
                    .shouldBeNull()
            }
        }

        test("a closed session and an archived space refuse an office holder with the same bare ForbiddenException") {
            val closed = fx.tableWorld()
            val archivedBoard = fx.createMember(role = AccountRole.BOARD)
            val archivedSteward = fx.createMember()
            val archivedTarget = fx.createMember()
            val archivedSpace = fx.createSpace(createdBy = archivedBoard, archived = true)
            fx.setRole(spaceId = archivedSpace, memberId = archivedSteward, role = EncounterSpaceRole.STEWARD)
            val archivedRoom = fx.insertSession(spaceId = archivedSpace, openedBy = archivedBoard)
            fx.insertParticipation(roomId = archivedRoom, memberId = archivedSteward)
            fx.insertParticipation(roomId = archivedRoom, memberId = archivedTarget)
            val rig = EncounterRig()
            encounterApp {
                val a =
                    closed.rig
                        .asMember(
                            client = client,
                            member = closed.steward,
                        ) { it.grantFloor(spaceId = closed.spaceId, memberId = closed.a.toString()) }
                        .failure<ForbiddenException>()
                        .message
                val b =
                    rig
                        .asMember(
                            client = client,
                            member = archivedSteward,
                        ) { it.grantFloor(spaceId = archivedSpace.toString(), memberId = archivedTarget.toString()) }
                        .failure<ForbiddenException>()
                        .message
                a shouldBe b
                rig.liveKit.permissionUpdates.shouldBeEmpty()
            }
        }

        test(
            "who may NOT receive the floor: yourself, an office holder, a silenced person, somebody at a table, somebody absent or not connected, somebody who already publishes",
        ) {
            val w = fx.tableWorld()
            val absent = fx.createMember()
            encounterApp {
                openWorld(w)
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.moderationState.silence(sessionRoomId = w.roomId(), memberId = w.a)
                // c is present but not connected at LiveKit
                w.rig.liveKit.forgetParticipant(room = w.livekitRoom(), identity = w.c.toString())
                grant(w, w.steward, w.steward).failure<ConflictException>()
                grant(w, w.steward, w.pulpit).failure<ConflictException>()
                grant(w, w.steward, w.a).failure<ConflictException>() // silenced
                grant(w, w.steward, w.b).failure<ConflictException>() // at a table
                grant(w, w.steward, absent).failure<ConflictException>() // not present
                grant(w, w.steward, w.c).failure<ConflictException>() // not connected
                grant(w, w.steward, Uuid.random()).failure<ConflictException>() // unknown person
                w.rig.liveKit.permissionUpdates
                    .shouldBeEmpty()
                w.rig.floorState
                    .current(w.roomId())
                    .shouldBeNull()
            }
        }

        test("somebody who already has a track is refused; an invalid member id is a bad request") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                w.rig.liveKit.tracks[w.livekitRoom() to w.a.toString()] =
                    listOf(LiveKitTrackInfo(sid = "TR_1", type = "AUDIO", source = "MICROPHONE", muted = false))
                grant(w, w.steward, w.a).failure<ConflictException>()
                w.rig
                    .asMember(client = client, member = w.steward) { it.grantFloor(spaceId = w.spaceId, memberId = "not-a-uuid") }
                    .failure<BadRequestException>()
                w.rig.liveKit.permissionUpdates
                    .shouldBeEmpty()
            }
        }

        test("BOARD/ADMIN without an office and a guest may receive the floor") {
            val w = fx.tableWorld()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            encounterApp {
                openWorld(w)
                openAndEnter(w = w, w.board)
                w.connect(w.board)
                grant(w, w.steward, w.board).getOrThrow().holderMemberId shouldBe w.board.toString()
                end(w, w.steward).getOrThrow()
                fx.insertParticipation(roomId = w.roomId(), memberId = guest)
                w.rig.liveKit.connect(room = w.livekitRoom(), identity = guest.toString(), canPublish = false, canPublishData = true)
                grant(w, w.pulpit, guest).getOrThrow().holderMemberId shouldBe guest.toString()
            }
        }

        test("only one person at a time: the second grant is a conflict and the first holder stays; parallel grants yield exactly one") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                grant(w, w.steward, w.a).getOrThrow()
                grant(w, w.pulpit, w.b).failure<ConflictException>()
                w.rig.floorState
                    .current(w.roomId())!!
                    .memberId shouldBe w.a
                end(w, w.steward).getOrThrow()
                w.rig.liveKit.permissionUpdates
                    .clear()
                val results =
                    coroutineScope {
                        listOf(w.steward to w.b, w.pulpit to w.c)
                            .map { (by, target) -> async(Dispatchers.IO) { grant(w, by, target) } }
                            .awaitAll()
                    }
                results.count { it.isSuccess } shouldBe 1
                results.single { it.isFailure }.failure<ConflictException>()
                w.rig.liveKit.permissionUpdates
                    .count { it.third.canPublish } shouldBe 1
            }
        }

        test("a LiveKit failure while raising the permission rolls the grant back and is a conflict") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                w.rig.liveKit.failUpdatePermission = true
                grant(w, w.steward, w.a).failure<ConflictException>()
                w.rig.floorState
                    .current(w.roomId())
                    .shouldBeNull()
                w.floorPackets().shouldBeEmpty()
                w.rig.liveKit.failUpdatePermission = false
                grant(w, w.steward, w.a).getOrThrow().active shouldBe true
            }
        }

        test("extension: 60 and 120 only, the total stops at ten minutes, nobody can extend without a floor") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                    .failure<ConflictException>()
                grant(w, w.steward, w.a).getOrThrow()
                listOf(30, 0, -60, 600).forEach { bad ->
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.extendFloor(spaceId = w.spaceId, seconds = bad) }
                        .failure<BadRequestException>()
                }
                val first =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.pulpit,
                        ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                        .getOrThrow()
                first.totalSeconds shouldBe 180
                first.remainingSeconds shouldBe 180
                w.rig.floorTime += 100.seconds
                w.rig
                    .asMember(client = client, member = w.steward) { it.extendFloor(spaceId = w.spaceId, seconds = 120) }
                    .getOrThrow()
                    .totalSeconds shouldBe
                    300
                w.rig.asMember(client = client, member = w.steward) { it.extendFloor(spaceId = w.spaceId, seconds = 120) }.getOrThrow()
                val atLimit =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.extendFloor(spaceId = w.spaceId, seconds = 120) }
                        .getOrThrow()
                atLimit.totalSeconds shouldBe 540
                val full =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                        .getOrThrow()
                full.totalSeconds shouldBe 600
                full.maxReached shouldBe true
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                    .failure<BadRequestException>()
                // the congregation cannot extend
                w.rig
                    .asMember(
                        client = client,
                        member = w.b,
                    ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                    .failure<ForbiddenException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                    .failure<ForbiddenException>()
            }
        }

        test("an expired floor cannot be extended any more") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                grant(w, w.steward, w.a).getOrThrow()
                w.rig.floorTime += 121.seconds
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.extendFloor(spaceId = w.spaceId, seconds = 60) }
                    .failure<ConflictException>()
            }
        }

        test("throttles: at most one call per second, and 30 per minute per caller") {
            val burst = EncounterRig(floorBurstLimiterOverride = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
            val burstWorld = fx.tableWorld(rig = burst)
            val budget = EncounterRig(floorLimiterOverride = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes))
            val budgetWorld = fx.tableWorld(rig = budget)
            encounterApp {
                openWorld(burstWorld)
                grant(burstWorld, burstWorld.steward, burstWorld.a).getOrThrow()
                burst
                    .asMember(client = client, member = burstWorld.steward) { it.extendFloor(spaceId = burstWorld.spaceId, seconds = 60) }
                    .failure<ConflictException>()
                openWorld(budgetWorld)
                repeat(30) { end(budgetWorld, budgetWorld.steward).getOrThrow() }
                end(budgetWorld, budgetWorld.steward).failure<ConflictException>()
            }
        }

        test("the holder may hand the floor back, a bystander may not; the demotion keeps the microphone source and mutes the track") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                // the end of a floor says nothing to somebody without a right (not even that nobody speaks)
                end(w, w.b).failure<ForbiddenException>()
                grant(w, w.steward, w.a).getOrThrow()
                w.rig.liveKit.tracks[w.livekitRoom() to w.a.toString()] =
                    listOf(LiveKitTrackInfo(sid = "TR_9", type = "AUDIO", source = "MICROPHONE", muted = false))
                end(w, w.b).failure<ForbiddenException>()
                w.rig.floorState
                    .current(w.roomId())
                    .shouldNotBeNull()
                end(w, w.a).getOrThrow()
                w.rig.floorState
                    .current(w.roomId())
                    .shouldBeNull()
                val last =
                    w.rig.liveKit.permissionUpdates
                        .last()
                last.second shouldBe w.a.toString()
                last.third.canPublish shouldBe false
                last.third.canSubscribe shouldBe true
                last.third.canPublishSources shouldBe listOf("MICROPHONE")
                w.rig.liveKit.muted shouldContain Triple(w.livekitRoom(), w.a.toString(), "TR_9")
                encounterFloorPacket(fromParticipant = false, payload = w.floorPackets().last().third) shouldBe EncounterFloorSignal.Off
                // idempotent for somebody with the right
                end(w, w.steward).getOrThrow()
            }
        }

        test("floorState: only for people who are present; holder and totals only for the office and the holder") {
            val w = fx.tableWorld()
            val outsider = fx.createMember()
            encounterApp {
                openWorld(w)
                w.rig
                    .asMember(client = client, member = w.b) { it.floorState(w.spaceId) }
                    .getOrThrow()
                    .active shouldBe false
                w.rig.asMember(client = client, member = outsider) { it.floorState(w.spaceId) }.failure<ForbiddenException>()
                grant(w, w.steward, w.a).getOrThrow()
                val bystander = w.rig.asMember(client = client, member = w.b) { it.floorState(w.spaceId) }.getOrThrow()
                bystander.active shouldBe true
                bystander.remainingSeconds shouldBe 120
                bystander.holderMemberId.shouldBeNull()
                bystander.holderDisplayName.shouldBeNull()
                bystander.totalSeconds shouldBe 0
                bystander.maxReached shouldBe false
                bystander.self shouldBe false
                val office = w.rig.asMember(client = client, member = w.pulpit) { it.floorState(w.spaceId) }.getOrThrow()
                office.holderMemberId shouldBe w.a.toString()
                office.totalSeconds shouldBe 120
                val holder = w.rig.asMember(client = client, member = w.a) { it.floorState(w.spaceId) }.getOrThrow()
                holder.self shouldBe true
                holder.holderMemberId shouldBe w.a.toString()
                w.rig.floorTime += 130.seconds
                w.rig
                    .asMember(client = client, member = w.b) { it.floorState(w.spaceId) }
                    .getOrThrow()
                    .active shouldBe false
            }
        }

        test("leaving, being removed, being silenced, sitting down at a table and a new office all end the floor") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                val room = w.roomId()

                grant(w, w.steward, w.a).getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.leaveSpace(w.spaceId) }.getOrThrow()
                w.rig.floorState
                    .current(room)
                    .shouldBeNull()

                grant(w, w.steward, w.b).getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.removeFromSpace(spaceId = w.spaceId, memberId = w.b.toString()) }
                    .getOrThrow()
                w.rig.floorState
                    .current(room)
                    .shouldBeNull()
                w.rig.liveKit.permissionUpdates
                    .any { it.second == w.b.toString() && !it.third.canPublish } shouldBe true

                grant(w, w.steward, w.c).getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.silenceInSpace(spaceId = w.spaceId, memberId = w.c.toString()) }
                    .getOrThrow()
                w.rig.floorState
                    .current(room)
                    .shouldBeNull()
            }
        }

        test("sitting down at a table ends the floor; giving the holder an office ends it; closing clears it") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                val room = w.roomId()
                grant(w, w.steward, w.a).getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.floorState
                    .current(room)
                    .shouldBeNull()

                grant(w, w.steward, w.b).getOrThrow()
                w.rig
                    .asMember(client = client, member = w.board) {
                        it.setSpaceRoles(
                            spaceId = w.spaceId,
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(memberId = w.steward.toString(), role = EncounterSpaceRole.STEWARD),
                                    EncounterSpaceRoleAssignmentInput(memberId = w.pulpit.toString(), role = EncounterSpaceRole.PULPIT),
                                    EncounterSpaceRoleAssignmentInput(memberId = w.b.toString(), role = EncounterSpaceRole.STEWARD),
                                ),
                        )
                    }.getOrThrow()
                w.rig.floorState
                    .current(room)
                    .shouldBeNull()

                grant(w, w.steward, w.c).getOrThrow()
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.spaceId) }.getOrThrow()
                w.rig.floorState
                    .current(room)
                    .shouldBeNull()
                w.rig.floorState.size() shouldBe 0
            }
        }

        test("a grant racing a silence never leaves a silenced person with the floor") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                val room = w.roomId()
                listOf(w.a, w.b, w.c).forEach { target ->
                    coroutineScope {
                        listOf(
                            async(Dispatchers.IO) { grant(w, w.pulpit, target).map { } },
                            async(Dispatchers.IO) {
                                w.rig.asMember(client = client, member = w.steward) {
                                    it.silenceInSpace(spaceId = w.spaceId, memberId = target.toString())
                                }
                            },
                        ).awaitAll()
                    }
                    w.rig.moderationState.isSilenced(sessionRoomId = room, memberId = target) shouldBe true
                    w.rig.floorState
                        .current(room)
                        ?.memberId shouldNotBe target
                    // the burst limiter allows one grant per second and caller
                    delay(1100)
                }
            }
        }

        test("a grant racing a table join never leaves a person with a table seat AND the floor") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                val room = w.roomId()
                listOf(w.a, w.b, w.c).forEachIndexed { index, target ->
                    val outcomes =
                        coroutineScope {
                            listOf(
                                async(Dispatchers.IO) { grant(w, w.pulpit, target).map { } },
                                async(Dispatchers.IO) {
                                    w.rig
                                        .asMember(client = client, member = target) {
                                            it.joinTable(spaceId = w.spaceId, table = index, seat = 0)
                                        }.map { }
                                },
                            ).awaitAll()
                        }
                    outcomes[1].exceptionOrNull()?.let { throw AssertionError("join failed: $it", it) }
                    val seated = w.rig.tableState.assignmentOf(sessionRoomId = room, memberId = target, now = Clock.System.now()) != null
                    seated shouldBe true
                    w.rig.floorState
                        .current(room)
                        ?.memberId shouldNotBe target
                    // the burst limiter allows one grant per second and caller
                    delay(1100)
                }
            }
        }

        test("an id that is no encounter space (random, or a session room) is refused neutrally and raises nothing") {
            val w = fx.tableWorld()
            encounterApp {
                openWorld(w)
                val sessionRoomId = w.roomId().toString()
                listOf(Uuid.random().toString(), sessionRoomId).forEach { id ->
                    w.rig
                        .asMember(client = client, member = w.steward) { it.grantFloor(spaceId = id, memberId = w.a.toString()) }
                        .failure<NotFoundException>()
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.extendFloor(spaceId = id, seconds = 60) }
                        .failure<NotFoundException>()
                    w.rig.asMember(client = client, member = w.steward) { it.endFloor(id) }.failure<NotFoundException>()
                }
                w.rig.liveKit.permissionUpdates
                    .shouldBeEmpty()
            }
        }
    })
