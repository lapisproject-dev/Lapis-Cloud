package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.166 -- the per-room option "grid map": who may set it, the profile rule, ON only while the room is closed, OFF always (and it clears the state), audit. */
class EncounterSpaceMapOptionTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun board() = fx.createMember(role = AccountRole.BOARD)

        fun input(
            map: Boolean?,
            profile: EncounterProfile? = EncounterProfile.ASSEMBLY,
        ) = EncounterSpaceInput(title = "Versammlung", profile = profile, mapEnabled = map)

        test("a created room defaults to no map; BOARD sets it for an assembly; a MEMBER or an office holder cannot") {
            val rig = EncounterRig()
            val b = board()
            val member = fx.createMember()
            encounterApp {
                val off = rig.asMember(client = client, member = b) { it.createSpace(input(null)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(off.id)
                off.mapEnabled shouldBe false
                val on = rig.asMember(client = client, member = b) { it.createSpace(input(true)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(on.id)
                on.mapEnabled shouldBe true
                rig.asMember(client = client, member = member) { it.createSpace(input(true)) }.failure<ForbiddenException>()
                val steward =
                    fx.createMember().also {
                        fx.setRole(
                            spaceId = Uuid.parse(on.id),
                            memberId = it,
                            role = EncounterSpaceRole.STEWARD,
                        )
                    }
                rig
                    .asMember(
                        client = client,
                        member = steward,
                    ) { it.updateSpace(spaceId = on.id, input = input(false)) }
                    .failure<ForbiddenException>()
            }
        }

        test("the church profile has no map: a sent flag is BadRequest; a stored flag is reset by a profile change that sends none") {
            val rig = EncounterRig()
            val b = board()
            encounterApp {
                rig
                    .asMember(
                        client = client,
                        member = b,
                    ) { it.createSpace(input(true, EncounterProfile.CHURCH_SERVICE)) }
                    .failure<BadRequestException>()
                val room = rig.asMember(client = client, member = b) { it.createSpace(input(true)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(room.id)
                // a flag that was SENT for a profile without a map
                rig
                    .asMember(
                        client = client,
                        member = b,
                    ) { it.updateSpace(spaceId = room.id, input = input(true, EncounterProfile.CHURCH_SERVICE)) }
                    .failure<BadRequestException>()
                // an old cached admin client switches the profile and sends no flag: the stored one is reset instead of failing
                val switched =
                    rig
                        .asMember(client = client, member = b) {
                            it.updateSpace(spaceId = room.id, input = input(null, EncounterProfile.CHURCH_SERVICE).copy(roomLayout = null))
                        }.getOrThrow()
                switched.profile shouldBe EncounterProfile.CHURCH_SERVICE
                switched.mapEnabled shouldBe false
            }
        }

        test("a null flag leaves the option alone") {
            val rig = EncounterRig()
            val b = board()
            encounterApp {
                val room = rig.asMember(client = client, member = b) { it.createSpace(input(true)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(room.id)
                rig
                    .asMember(
                        client = client,
                        member = b,
                    ) { it.updateSpace(spaceId = room.id, input = input(null)) }
                    .getOrThrow()
                    .mapEnabled shouldBe
                    true
                rig
                    .asMember(
                        client = client,
                        member = b,
                    ) { it.updateSpace(spaceId = room.id, input = input(false)) }
                    .getOrThrow()
                    .mapEnabled shouldBe
                    false
            }
        }

        test("switching it on while the room is open is refused; switching it off is allowed and drops every position at once") {
            val rig = EncounterRig()
            val world = fx.tableWorld(rig = rig, mapEnabled = false)
            encounterApp {
                openAndEnter(w = world, world.a)
                rig
                    .asMember(
                        client = client,
                        member = world.board,
                    ) { it.updateSpace(spaceId = world.spaceId, input = input(true).copy(profile = null)) }
                    .failure<ConflictException>()
                rig.asMember(client = client, member = world.board) { it.closeSpace(world.spaceId) }.getOrThrow()
                rig
                    .asMember(
                        client = client,
                        member = world.board,
                    ) { it.updateSpace(spaceId = world.spaceId, input = input(true).copy(profile = null)) }
                    .getOrThrow()
                openAndEnter(w = world, world.a)
                rig.asMember(client = client, member = world.a) { it.mapEnter(world.spaceId) }.getOrThrow()
                rig.mapState.walkerCount(fx.openSessionRoom(world.space)!!) shouldBe 1
                rig.mapState.drain() // the entry delta
                val sessionRoom = fx.openSessionRoom(world.space)!!
                rig
                    .asMember(
                        client = client,
                        member = world.board,
                    ) { it.updateSpace(spaceId = world.spaceId, input = input(false).copy(profile = null)) }
                    .getOrThrow()
                    .mapEnabled shouldBe false
                rig.mapState.walkerCount(sessionRoom) shouldBe 0
                rig.mapState
                    .drain()
                    .single()
                    .packet
                    .shouldBeInstanceOf<EncounterMapPacket.Reset>()
                rig
                    .asMember(
                        client = client,
                        member = world.a,
                    ) { it.mapStep(spaceId = world.spaceId, direction = EncounterMapFacing.WEST) }
                    .failure<ForbiddenException>()
            }
        }

        test("the option is part of the audited room configuration -- the switch only, never a person or a position") {
            val rig = EncounterRig()
            val b = board()
            encounterApp {
                val room = rig.asMember(client = client, member = b) { it.createSpace(input(true)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(room.id)
                rig.asMember(client = client, member = b) { it.updateSpace(spaceId = room.id, input = input(false)) }.getOrThrow()
                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ENCOUNTER_SPACE) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(room.id))
                            }.map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    }
                val joined = entries.joinToString("\n")
                joined shouldContain "\"mapEnabled\":true"
                joined shouldContain "\"mapEnabled\":false"
                joined shouldNotContain b.toString()
            }
        }

        test("the database refuses a map in a room that is not an assembly (CHECK constraint)") {
            val b = board()
            val failure =
                runCatching {
                    fx.createSpace(
                        createdBy = b,
                        profile = EncounterProfile.CHURCH_SERVICE,
                        mapEnabled = true,
                    )
                }.exceptionOrNull()
            (failure != null) shouldBe true
            fx.createSpace(createdBy = b, profile = EncounterProfile.ASSEMBLY, mapEnabled = true)
        }
    })
