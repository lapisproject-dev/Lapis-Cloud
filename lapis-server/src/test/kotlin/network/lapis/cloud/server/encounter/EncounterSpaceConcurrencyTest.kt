package network.lapis.cloud.server.encounter

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val CONSENT =
    EncounterConsentInput(consentVersion = EncounterConsentDisclaimer.VERSION, consentSha256 = EncounterConsentDisclaimer.SHA256)

private suspend fun <T> parallel(
    n: Int,
    block: suspend (Int) -> T,
): List<T> = coroutineScope { (0 until n).map { i -> async(Dispatchers.IO) { block(i) } }.awaitAll() }

/**
 * Welle V1.9.61 -- the encounter service under real concurrency, as scenarios so the SAME assertions run on H2 and on PostgreSQL
 * (see CLAUDE.md "Postgres-Testspur"). The lock order (space row, then room row, audit last) is what these scenarios exercise; on
 * PostgreSQL the lane additionally asserts that the deadlock counter did not move.
 */
abstract class EncounterSpaceConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun participants(room: Uuid): Long = fx.participationCount(room)

        test("N parallel entries at the ceiling admit EXACTLY the ceiling (6 - 2 reserved = 4), every other caller gets a conflict") {
            val rig = EncounterRig(config = ENCOUNTER_CONFIG_SMALL)
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val members = List(12) { fx.createMember() }
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val results =
                    parallel(n = members.size) { i ->
                        rig.asMember(client = client, member = members[i]) {
                            it.enterSpace(spaceId = space.toString(), consent = null)
                        }
                    }
                results.count { it.isSuccess } shouldBe 4
                results.filter { it.isFailure }.forEach { (it.exceptionOrNull() is ConflictException) shouldBe true }
                participants(room) shouldBe 4L
            }
        }

        test(
            "V1.9.79: N people choosing the SAME seat at once: exactly one wins, every other caller gets a conflict, nobody holds two seats",
        ) {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val members = List(10) { fx.createMember() }
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                members.forEach { m ->
                    rig.asMember(client = client, member = m) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                }
                val results =
                    parallel(n = members.size) { i ->
                        rig.asMember(client = client, member = members[i]) { it.selectSeat(spaceId = space.toString(), seat = 7) }
                    }
                results.count { it.isSuccess } shouldBe 1
                results.filter { it.isFailure }.forEach { (it.exceptionOrNull() is ConflictException) shouldBe true }
                val seated =
                    rig
                        .asMember(client = client, member = members[0]) { it.listPresent(space.toString()) }
                        .getOrThrow()
                        .filter { it.seat != null }
                seated.size shouldBe 1
                seated.single().seat shouldBe 7
            }
        }

        test(
            "closeSpace racing a crowd of entries: afterwards the session is closed, NO presence row remains, nobody entered a closed session",
        ) {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val members = List(10) { fx.createMember() }
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val results =
                    parallel(n = members.size + 1) { i ->
                        if (i == 0) {
                            rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }
                        } else {
                            rig.asMember(
                                client = client,
                                member = members[i - 1],
                            ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        }
                    }
                results[0].isSuccess shouldBe true
                results.drop(1).filter { it.isFailure }.forEach { (it.exceptionOrNull() is ConflictException) shouldBe true }
                fx.openSessionRoom(space) shouldBe null
                participants(room) shouldBe 0L
                // and a late entry is refused
                (
                    rig
                        .asMember(client = client, member = members[0]) {
                            it.enterSpace(spaceId = space.toString(), consent = null)
                        }.exceptionOrNull() is ConflictException
                ) shouldBe
                    true
            }
        }

        test("two parallel openSpace calls leave exactly ONE open session; a LiveKit room created by the loser is deleted again") {
            val rig = EncounterRig()
            rig.liveKit.createDelayMillis = 400
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                val results = parallel(n = 2) { rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) } }
                results.all { it.isSuccess } shouldBe true
                results.all { it.getOrThrow().open } shouldBe true
                fx.sessionRoomIds(space).size shouldBe 1
                val survivor = fx.livekitName(fx.openSessionRoom(space)!!)
                // proves the two calls really overlapped: both created a room, one of them was thrown away
                rig.liveKit.createdRooms.size shouldBe 2
                rig.liveKit.deletedRooms.size shouldBe 1
                rig.liveKit.deletedRooms.contains(survivor) shouldBe false
                rig.liveKit.hasRoom(survivor) shouldBe true
            }
        }

        test("the same person entering 8 times in parallel (a reconnect storm) ends with exactly ONE presence row") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val results =
                    parallel(
                        n = 8,
                    ) { rig.asMember(client = client, member = member) { it.enterSpace(spaceId = space.toString(), consent = null) } }
                results.all { it.isSuccess } shouldBe true
                participants(room) shouldBe 1L
            }
        }

        test("the poller running while people enter never removes a fresh entry and never closes the live session") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val members = List(10) { fx.createMember() }
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val poller =
                    EncounterSpacePoller(
                        liveKitAdminClient = rig.liveKit,
                        moderationState = rig.moderationState,
                        blessingState = rig.blessingState,
                        bellState = rig.bellState,
                        seatState = rig.seatState,
                        tableState = rig.tableState,
                        entryNotifier = rig.entryNotifier,
                        liveKitEnabled = true,
                    )
                val results =
                    parallel(n = members.size + 3) { i ->
                        if (i < 3) {
                            poller.tick()
                            null
                        } else {
                            rig.asMember(
                                client = client,
                                member = members[i - 3],
                            ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        }
                    }.filterNotNull()
                results.all { it.isSuccess } shouldBe true
                participants(room) shouldBe members.size.toLong()
                fx.openSessionRoom(space) shouldBe room
            }
        }

        test("a non-member consenting in two spaces at once: BOTH entries succeed and exactly ONE consent proof exists") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val spaceA = fx.createSpace(createdBy = fx.createMember(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            val spaceB = fx.createSpace(createdBy = fx.createMember(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            listOf(spaceA, spaceB).forEach { fx.setRole(spaceId = it, memberId = steward, role = EncounterSpaceRole.STEWARD) }
            encounterApp {
                listOf(spaceA, spaceB).forEach { s ->
                    rig.asMember(client = client, member = steward) { it.openSpace(s.toString()) }.getOrThrow()
                }
                repeat(5) {
                    val guest = fx.createMember(status = if (it % 2 == 0) MemberStatus.GUEST else MemberStatus.FRIEND)
                    val results =
                        parallel(n = 2) { i ->
                            val s = if (i == 0) spaceA else spaceB
                            rig.asMember(
                                client = client,
                                member = guest,
                            ) { svc -> svc.enterSpace(spaceId = s.toString(), consent = CONSENT) }
                        }
                    results.all { r -> r.isSuccess } shouldBe true
                    transaction {
                        EncounterConsentAcknowledgmentTable
                            .selectAll()
                            .where { EncounterConsentAcknowledgmentTable.memberId eq guest }
                            .count()
                    } shouldBe 1L
                    transaction {
                        ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq guest }.count()
                    } shouldBe 2L
                }
            }
        }

        test("a removal racing the victim's own re-entries: the victim ends up with no presence row and cannot enter any more") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val victim = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val results =
                    parallel(n = 9) { i ->
                        if (i == 4) {
                            rig.asMember(
                                client = client,
                                member = steward,
                            ) { it.removeFromSpace(spaceId = space.toString(), memberId = victim.toString()) }
                        } else {
                            rig.asMember(client = client, member = victim) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        }
                    }
                results[4].isSuccess shouldBe true
                results.filterIndexed { i, _ -> i != 4 }.filter { it.isFailure }.forEach {
                    (it.exceptionOrNull() is ForbiddenException) shouldBe
                        true
                }
                // a re-entry that committed AFTER the removal's delete is impossible: the block is checked under the room lock
                participants(room) shouldBe 0L
                (
                    rig
                        .asMember(client = client, member = victim) {
                            it.enterSpace(spaceId = space.toString(), consent = null)
                        }.exceptionOrNull() is ForbiddenException
                ) shouldBe
                    true
            }
        }

        test(
            "opening and closing in a loop in parallel with entries leaves a consistent database (no orphan presence row of an ended session)",
        ) {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val members = List(6) { fx.createMember() }
            encounterApp {
                repeat(3) {
                    rig.asMember(client = client, member = steward) { svc -> svc.openSpace(space.toString()) }.getOrThrow()
                    parallel(n = members.size + 1) { i ->
                        if (i == 0) {
                            rig.asMember(client = client, member = steward) { svc -> svc.closeSpace(space.toString()) }
                        } else {
                            rig.asMember(
                                client = client,
                                member = members[i - 1],
                            ) { svc -> svc.enterSpace(spaceId = space.toString(), consent = null) }
                        }
                    }
                }
                // every session of this space has ended and holds no presence row
                fx.sessionRoomIds(space).forEach { participants(it) shouldBe 0L }
                fx.openSessionRoom(space) shouldBe null
                rig.liveKit.createdRooms.size shouldBe rig.liveKit.deletedRooms.size
                emptyList<String>().shouldBeEmpty()
            }
        }
    })

class EncounterSpaceConcurrencyTest : EncounterSpaceConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterSpaceConcurrencyPostgresTest : EncounterSpaceConcurrencyScenarios(TestDatabase.Postgres())
