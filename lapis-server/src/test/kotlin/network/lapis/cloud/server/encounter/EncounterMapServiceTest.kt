package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapMove
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Welle V1.9.166 -- the five map RPCs end to end on H2 with a fake LiveKit: access, refusals, exclusivity, release points, tamper. */
class EncounterMapServiceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun mapWorld(rig: EncounterRig = EncounterRig()) = fx.tableWorld(rig = rig, mapEnabled = true)

        /** One bare refusal: the type AND the message are the same for every reason. */
        fun Result<*>.bareForbidden() {
            val e = failure<ForbiddenException>()
            e.message shouldBe ForbiddenException().message
        }

        test("happy path: enter, step, teleport, snapshot, leave") {
            val w = mapWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                val entered = w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                entered.onMap shouldBe true
                entered.facing shouldBe EncounterMapFacing.NORTH
                val stepped =
                    w.rig
                        .asMember(client = client, member = w.a) {
                            it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST)
                        }.getOrThrow()
                stepped.move shouldBe EncounterMapMove.MOVED
                stepped.x shouldBe entered.x - 1
                val teleported =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.mapTeleport(spaceId = w.spaceId, anchor = EncounterMapAnchor.CENTER) }
                        .getOrThrow()
                teleported.onMap shouldBe true
                val snapshot = w.rig.asMember(client = client, member = w.b) { it.mapSnapshot(w.spaceId) }.getOrThrow()
                snapshot.positions.map { it.memberId } shouldBe listOf(w.a.toString())
                snapshot.self.onMap shouldBe false
                snapshot.epoch shouldBe w.rig.mapState.epoch
                (
                    snapshot.positions
                        .single()
                        .initials.length <= 3
                ) shouldBe true
                w.rig.asMember(client = client, member = w.a) { it.mapLeave(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(client = client, member = w.b) { it.mapSnapshot(w.spaceId) }
                    .getOrThrow()
                    .positions
                    .shouldBeEmpty()
                // idempotent
                w.rig.asMember(client = client, member = w.a) { it.mapLeave(w.spaceId) }.getOrThrow()
            }
        }

        test("an office holder only looks: the snapshot works, entering is refused; the same bare answer as every other refusal") {
            val w = mapWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.pulpit, w.a)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                listOf(w.steward, w.pulpit).forEach { office ->
                    w.rig
                        .asMember(client = client, member = office) { it.mapSnapshot(w.spaceId) }
                        .getOrThrow()
                        .positions.size shouldBe 1
                    w.rig.asMember(client = client, member = office) { it.mapEnter(w.spaceId) }.bareForbidden()
                    w.rig
                        .asMember(
                            client = client,
                            member = office,
                        ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.NORTH) }
                        .bareForbidden()
                    w.rig
                        .asMember(
                            client = client,
                            member = office,
                        ) { it.mapTeleport(spaceId = w.spaceId, anchor = EncounterMapAnchor.CENTER) }
                        .bareForbidden()
                }
            }
        }

        test("every other refusal is the same bare ForbiddenException: map off, closed room, not present, removed, silenced") {
            val w = mapWorld()
            val off = fx.tableWorld(rig = w.rig, mapEnabled = false)
            encounterApp {
                // closed room (nobody opened it yet)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.bareForbidden()
                w.rig.asMember(client = client, member = w.a) { it.mapSnapshot(w.spaceId) }.bareForbidden()
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                // not present: a member who never entered
                val outsider = fx.createMember(name = "Draussen")
                w.rig.asMember(client = client, member = outsider) { it.mapEnter(w.spaceId) }.bareForbidden()
                w.rig.asMember(client = client, member = outsider) { it.mapSnapshot(w.spaceId) }.bareForbidden()
                // map off
                openAndEnter(w = off, off.steward, off.a)
                off.rig.asMember(client = client, member = off.a) { it.mapEnter(off.spaceId) }.bareForbidden()
                off.rig.asMember(client = client, member = off.a) { it.mapSnapshot(off.spaceId) }.bareForbidden()
                // silenced
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.silenceInSpace(spaceId = w.spaceId, memberId = w.b.toString()) }
                    .getOrThrow()
                w.rig.asMember(client = client, member = w.b) { it.mapEnter(w.spaceId) }.bareForbidden()
                // removed
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.removeFromSpace(spaceId = w.spaceId, memberId = w.c.toString()) }
                    .getOrThrow()
                w.rig.asMember(client = client, member = w.c) { it.mapEnter(w.spaceId) }.bareForbidden()
                w.rig.asMember(client = client, member = w.c) { it.mapSnapshot(w.spaceId) }.bareForbidden()
                // an unknown space
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(Uuid.random().toString()) }.bareForbidden()
            }
        }

        test("the throttles throw ServiceBusyException, not a Conflict") {
            val strict = EncounterRig(limiter = { FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes) })
            val w = mapWorld(strict)
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.failure<ServiceBusyException>()
                w.rig.asMember(client = client, member = w.a) { it.mapSnapshot(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.mapSnapshot(w.spaceId) }.failure<ServiceBusyException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .failure<ServiceBusyException>()
            }
        }

        test("the 26th person gets ServiceBusyException (map full)") {
            val bigRoom =
                ConferenceConfig.load { key ->
                    when (key) {
                        "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
                        "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
                        "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
                        "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "60"
                        else -> null
                    }
                }
            val w = mapWorld(EncounterRig(config = bigRoom))
            encounterApp {
                openAndEnter(w = w, w.steward)
                val people = (0 until 26).map { fx.createMember(name = "Teilnehmer") }
                people.forEach { p ->
                    w.rig.asMember(client = client, member = p) { it.enterSpace(spaceId = w.spaceId, consent = null) }.getOrThrow()
                }
                people.take(25).forEach { p -> w.rig.asMember(client = client, member = p) { it.mapEnter(w.spaceId) }.getOrThrow() }
                w.rig.asMember(client = client, member = people.last()) { it.mapEnter(w.spaceId) }.failure<ServiceBusyException>()
            }
        }

        test("tamper: stepping with the id of a space the caller is not on the map of, or after any release point, is Forbidden") {
            val w = mapWorld()
            val other = mapWorld(w.rig)
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                openAndEnter(w = other, other.steward)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = other.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                // leaveSpace
                w.rig.asMember(client = client, member = w.a) { it.leaveSpace(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                // removeFromSpace
                w.rig.asMember(client = client, member = w.b) { it.mapEnter(w.spaceId) }.getOrThrow()
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
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                // closeSpace
                w.rig.asMember(client = client, member = w.c) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.c,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                w.rig
                    .asMember(
                        client = client,
                        member = w.c,
                    ) { it.mapTeleport(spaceId = w.spaceId, anchor = EncounterMapAnchor.CENTER) }
                    .bareForbidden()
            }
        }

        test("tamper: after a status change and the next reconcile the person can no longer step") {
            val w = mapWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                fx.setStatus(memberId = w.a, status = MemberStatus.FRIEND)
                EncounterMapBroadcaster(liveKit = w.rig.liveKit, state = w.rig.mapState, liveKitEnabled = true).reconcileFromDb()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
            }
        }

        test(
            "one place per person: entering the map frees the bench seat; sitting, a table, re-entering and an office take the person off the map",
        ) {
            val w = mapWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                // bench seat -> map
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.spaceId, seat = 3) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(client = client, member = w.a) { it.listPresent(w.spaceId) }
                    .getOrThrow()
                    .single { it.memberId == w.a.toString() }
                    .seat shouldBe null
                // map -> bench seat
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.spaceId, seat = 4) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                // map -> table (tables are on in this world)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                // table -> map
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.listTables(w.spaceId) }
                    .getOrThrow()
                    .all { it.occupiedSeats.isEmpty() } shouldBe
                    true
                // a reload (enterSpace) starts without a position
                w.rig.asMember(client = client, member = w.a) { it.enterSpace(spaceId = w.spaceId, consent = null) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .bareForbidden()
                // an office takes the person off the map
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(client = client, member = w.board) {
                        it.setSpaceRoles(
                            spaceId = w.spaceId,
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(memberId = w.steward.toString(), role = EncounterSpaceRole.STEWARD),
                                    EncounterSpaceRoleAssignmentInput(memberId = w.pulpit.toString(), role = EncounterSpaceRole.PULPIT),
                                    EncounterSpaceRoleAssignmentInput(memberId = w.a.toString(), role = EncounterSpaceRole.STEWARD),
                                ),
                        )
                    }.getOrThrow()
                w.rig.mapState.walkerCount(fx.openSessionRoom(w.space)!!) shouldBe 0
            }
        }

        test("a throttled departure from a shared table changes nothing: the map entry is taken back") {
            val w = mapWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 0) }.getOrThrow()
                // a sits down at b's table and then moves on to another one: leaving a table others still sit at starts a's cooldown
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 0) }.getOrThrow()
                w.rig.asMember(client = client, member = w.c) { it.joinTable(spaceId = w.spaceId, table = 2, seat = 1) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.failure<ServiceBusyException>()
                w.rig
                    .asMember(client = client, member = w.a) { it.tableToken(w.spaceId) }
                    .getOrThrow()
                    .token
                    ?.table shouldBe 2
                w.rig.mapState.walkerCount(fx.openSessionRoom(w.space)!!) shouldBe 0
            }
        }

        test("the snapshot lists only people on the map and everybody sees the same positions") {
            val w = mapWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b, w.c)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.b) { it.mapEnter(w.spaceId) }.getOrThrow()
                val seenByC = w.rig.asMember(client = client, member = w.c) { it.mapSnapshot(w.spaceId) }.getOrThrow()
                val seenBySteward = w.rig.asMember(client = client, member = w.steward) { it.mapSnapshot(w.spaceId) }.getOrThrow()
                seenByC.positions.map { it.memberId } shouldContainExactlyInAnyOrder listOf(w.a.toString(), w.b.toString())
                seenBySteward.positions shouldBe seenByC.positions
                seenByC.self.onMap shouldBe false
            }
        }
    })
