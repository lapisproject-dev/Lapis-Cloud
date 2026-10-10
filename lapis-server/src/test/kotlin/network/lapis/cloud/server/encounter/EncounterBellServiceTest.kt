package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ENCOUNTER_BELL_TOPIC
import network.lapis.cloud.shared.domain.ENCOUNTER_BLESSING_TOPIC
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.96 -- the bell: the server (never the client) decides. Only an ACTIVE PULPIT holder who is present, in an open CHURCH_SERVICE
 * space, causes one `sendData`; every other caller gets the SAME bare [ForbiddenException] and no packet goes out. Nothing is stored.
 */
class EncounterBellServiceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        /** A space with an open session; [present] members get a presence row. */
        class Scene(
            val rig: EncounterRig,
            val space: Uuid,
            val room: Uuid,
            val livekitName: String,
        )

        fun scene(
            rig: EncounterRig = EncounterRig(),
            profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
            open: Boolean = true,
        ): Scene {
            val creator = fx.createMember(role = AccountRole.BOARD)
            val space = fx.createSpace(createdBy = creator, profile = profile)
            val room = fx.insertSession(spaceId = space, openedBy = creator, endedAt = if (open) null else DbClock.nowLocalDateTime())
            return Scene(rig = rig, space = space, room = room, livekitName = fx.livekitName(room))
        }

        fun Scene.pulpit(
            present: Boolean = true,
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val m = fx.createMember(role = role)
            fx.setRole(spaceId = space, memberId = m, role = EncounterSpaceRole.PULPIT)
            if (present) fx.insertParticipation(roomId = room, memberId = m)
            if (status != MemberStatus.ACTIVE) fx.setStatus(memberId = m, status = status)
            return m
        }

        test("a present, active PULPIT holder sends exactly one packet: right room, topic and fixed payload") {
            val s = scene()
            val p = s.pulpit()
            encounterApp {
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                s.rig.liveKit.sentData shouldHaveSize 1
                val (room, topic, payload) =
                    s.rig.liveKit.sentData
                        .single()
                room shouldBe s.livekitName
                topic shouldBe ENCOUNTER_BELL_TOPIC
                payload.decodeToString() shouldBe """{"v":1}"""
            }
        }

        test("every other caller gets the same bare ForbiddenException and nothing is sent") {
            val s = scene()
            val steward =
                fx.createMember().also {
                    fx.setRole(spaceId = s.space, memberId = it, role = EncounterSpaceRole.STEWARD)
                    fx.insertParticipation(roomId = s.room, memberId = it)
                }
            val congregation = fx.createMember().also { fx.insertParticipation(roomId = s.room, memberId = it) }
            val board = fx.createMember(role = AccountRole.BOARD).also { fx.insertParticipation(roomId = s.room, memberId = it) }
            val admin = fx.createMember(role = AccountRole.ADMIN).also { fx.insertParticipation(roomId = s.room, memberId = it) }
            val withdrawn = s.pulpit(status = MemberStatus.WITHDRAWN)
            val absent = s.pulpit(present = false)
            encounterApp {
                val messages =
                    listOf(steward, congregation, board, admin, withdrawn, absent).map { who ->
                        s.rig
                            .asMember(
                                client = client,
                                member = who,
                            ) { it.ringBell(s.space.toString()) }
                            .failure<ForbiddenException>()
                            .message
                    }
                messages.toSet().size shouldBe 1
                s.rig.liveKit.sentData shouldHaveSize 0
            }
        }

        test("the assembly profile has no bell, even for a PULPIT holder") {
            val s = scene(profile = EncounterProfile.ASSEMBLY)
            val p = s.pulpit()
            encounterApp {
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.failure<ForbiddenException>()
                s.rig.liveKit.sentData shouldHaveSize 0
            }
        }

        test("a closed space refuses, an unknown space is not found") {
            val s = scene(open = false)
            val p = s.pulpit(present = false)
            encounterApp {
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.failure<ForbiddenException>()
                s.rig.asMember(client = client, member = p) { it.ringBell(Uuid.random().toString()) }.failure<NotFoundException>()
                s.rig.liveKit.sentData shouldHaveSize 0
            }
        }

        test("a second bell inside ten seconds is swallowed without an exception; after the interval it is sent again") {
            var t = Instant.fromEpochMilliseconds(5_000_000)
            val rig = EncounterRig(bellState = EncounterBellState(now = { t }))
            val s = scene(rig = rig)
            val p = s.pulpit()
            encounterApp {
                rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                rig.liveKit.sentData shouldHaveSize 1
                t += 9_999.milliseconds
                rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                rig.liveKit.sentData shouldHaveSize 1
                t += 1.milliseconds
                rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                rig.liveKit.sentData shouldHaveSize 2
            }
        }

        test("the bell and the blessing do not throttle each other and use separate topics") {
            val s = scene()
            val p = s.pulpit()
            encounterApp {
                s.rig.asMember(client = client, member = p) { it.blessSpace(s.space.toString()) }.getOrThrow()
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                s.rig.liveKit.sentData shouldHaveSize 2
                s.rig.liveKit.sentData
                    .map { it.second }
                    .toSet() shouldBe setOf(ENCOUNTER_BLESSING_TOPIC, ENCOUNTER_BELL_TOPIC)
            }
        }

        test("the payload names nobody: no member id, no digit but the fixed 1") {
            val s = scene()
            val p = s.pulpit()
            encounterApp {
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                val payload =
                    s.rig.liveKit.sentData
                        .single()
                        .third
                        .decodeToString()
                payload.contains(p.toString()) shouldBe false
                payload.filter { it.isDigit() } shouldBe "1"
            }
        }

        test("two spaces do not throttle each other") {
            val rig = EncounterRig()
            val a = scene(rig = rig)
            val b = scene(rig = rig)
            val pa = a.pulpit()
            val pb = b.pulpit()
            encounterApp {
                rig.asMember(client = client, member = pa) { it.ringBell(a.space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = pb) { it.ringBell(b.space.toString()) }.getOrThrow()
                rig.liveKit.sentData shouldHaveSize 2
            }
        }

        test("closing the space forgets the throttle entry") {
            val rig = EncounterRig()
            val s = scene(rig = rig)
            val p = s.pulpit()
            val board = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                rig.bellState.size() shouldBe 1
                rig.asMember(client = client, member = board) { it.closeSpace(s.space.toString()) }.getOrThrow()
                rig.bellState.size() shouldBe 0
            }
        }

        test("nothing is stored: audit, presence and encounter tables are unchanged by a bell") {
            val s = scene()
            val p = s.pulpit()

            fun counts() =
                transaction {
                    listOf(
                        AuditLogEntryTable.selectAll().count(),
                        ConferenceParticipationTable.selectAll().count(),
                        ConferenceRoomTable.selectAll().count(),
                        EncounterSpaceTable.selectAll().count(),
                        EncounterSpaceRoleTable.selectAll().count(),
                    )
                }
            encounterApp {
                val before = counts()
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                counts() shouldBe before
            }
        }

        test("a failed delivery is not reported to the caller") {
            val s = scene()
            val p = s.pulpit()
            s.rig.liveKit.failSendData = true
            encounterApp {
                s.rig.asMember(client = client, member = p) { it.ringBell(s.space.toString()) }.getOrThrow()
                s.rig.liveKit.sentData shouldHaveSize 0
            }
        }
    })
