package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterTablesConfig
import network.lapis.cloud.shared.domain.allowedFor
import network.lapis.cloud.shared.domain.defaultRoomLayout
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.152 (stage 2c) -- the fixed room template: who may set it, the default per profile, the combination rule, the freeze while
 * open, the audit snapshot (an enum name, no person) and the fact that the template never changes the entry notice.
 */
class EncounterRoomLayoutServiceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun input(
            profile: EncounterProfile?,
            layout: EncounterRoomLayout?,
            tables: EncounterTablesConfig? = null,
        ) = EncounterSpaceInput(title = "Raum", profile = profile, roomLayout = layout, tables = tables)

        test("the combination rule: NAVE only church, FOYER/SMALL_GROUP only assembly, HALL both") {
            EncounterRoomLayout.NAVE.allowedFor(EncounterProfile.CHURCH_SERVICE) shouldBe true
            EncounterRoomLayout.NAVE.allowedFor(EncounterProfile.ASSEMBLY) shouldBe false
            EncounterRoomLayout.FOYER.allowedFor(EncounterProfile.CHURCH_SERVICE) shouldBe false
            EncounterRoomLayout.FOYER.allowedFor(EncounterProfile.ASSEMBLY) shouldBe true
            EncounterRoomLayout.SMALL_GROUP.allowedFor(EncounterProfile.CHURCH_SERVICE) shouldBe false
            EncounterRoomLayout.SMALL_GROUP.allowedFor(EncounterProfile.ASSEMBLY) shouldBe true
            EncounterRoomLayout.HALL.allowedFor(EncounterProfile.CHURCH_SERVICE) shouldBe true
            EncounterRoomLayout.HALL.allowedFor(EncounterProfile.ASSEMBLY) shouldBe true
            defaultRoomLayout(EncounterProfile.CHURCH_SERVICE) shouldBe EncounterRoomLayout.NAVE
            defaultRoomLayout(EncounterProfile.ASSEMBLY) shouldBe EncounterRoomLayout.HALL
        }

        test("create: null gets the default of the profile, an explicit fitting template is stored and returned") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val church = rig.asMember(client = client, member = boardId) { it.createSpace(input(null, null)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(church.id)
                church.roomLayout shouldBe EncounterRoomLayout.NAVE
                val assembly =
                    rig.asMember(client = client, member = boardId) { it.createSpace(input(EncounterProfile.ASSEMBLY, null)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(assembly.id)
                assembly.roomLayout shouldBe EncounterRoomLayout.HALL
                listOf(
                    EncounterProfile.CHURCH_SERVICE to EncounterRoomLayout.HALL,
                    EncounterProfile.ASSEMBLY to EncounterRoomLayout.FOYER,
                    EncounterProfile.ASSEMBLY to EncounterRoomLayout.SMALL_GROUP,
                ).forEach { (profile, layout) ->
                    val dto = rig.asMember(client = client, member = boardId) { it.createSpace(input(profile, layout)) }.getOrThrow()
                    fx.spaceIds += Uuid.parse(dto.id)
                    dto.roomLayout shouldBe layout
                }
            }
        }

        test("create: a template that does not fit the profile is a BadRequest, never a SQL exception") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                listOf(
                    EncounterProfile.CHURCH_SERVICE to EncounterRoomLayout.FOYER,
                    EncounterProfile.CHURCH_SERVICE to EncounterRoomLayout.SMALL_GROUP,
                    EncounterProfile.ASSEMBLY to EncounterRoomLayout.NAVE,
                    null to EncounterRoomLayout.FOYER,
                ).forEach { (profile, layout) ->
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.createSpace(input(profile, layout)) }
                        .failure<BadRequestException>()
                }
            }
        }

        test("only BOARD/ADMIN set the template") {
            val rig = EncounterRig()
            val member = fx.createMember()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.createSpace(input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.FOYER)) }
                    .failure<ForbiddenException>()
                val dto =
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.createSpace(input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.FOYER)) }
                        .getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.updateSpace(spaceId = dto.id, input = input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.HALL)) }
                    .failure<ForbiddenException>()
            }
        }

        test("update: null keeps the template; a profile switch with an explicit unfitting template is a BadRequest") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val dto =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(
                                input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.FOYER, EncounterTablesConfig(enabled = true)),
                            )
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                val renamed =
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.updateSpace(spaceId = dto.id, input = EncounterSpaceInput(title = "Umbenannt")) }
                        .getOrThrow()
                renamed.roomLayout shouldBe EncounterRoomLayout.FOYER
                // explicit FOYER with the church profile (tables off, so the tables rule is not the cause)
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = dto.id,
                            input =
                                input(
                                    EncounterProfile.CHURCH_SERVICE,
                                    EncounterRoomLayout.FOYER,
                                    EncounterTablesConfig(enabled = false),
                                ),
                        )
                    }.failure<BadRequestException>()
                // an old cached client switches the profile and sends no template: the stored FOYER is reset to the default of the new profile
                val church =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.updateSpace(
                                spaceId = dto.id,
                                input =
                                    EncounterSpaceInput(
                                        title = "Umbenannt",
                                        profile = EncounterProfile.CHURCH_SERVICE,
                                        tables = EncounterTablesConfig(enabled = false),
                                    ),
                            )
                        }.getOrThrow()
                church.roomLayout shouldBe EncounterRoomLayout.NAVE
            }
        }

        test("an open room: a real template change is a ConflictException, the same value is accepted") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val dto =
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.createSpace(input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.FOYER)) }
                        .getOrThrow()
                val spaceId = Uuid.parse(dto.id)
                fx.spaceIds += spaceId
                fx.insertSession(spaceId = spaceId, openedBy = boardId)
                rig
                    .asMember(
                        client = client,
                        member = boardId,
                    ) { it.updateSpace(spaceId = dto.id, input = input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.FOYER)) }
                    .getOrThrow()
                    .roomLayout shouldBe EncounterRoomLayout.FOYER
                rig
                    .asMember(
                        client = client,
                        member = boardId,
                    ) { it.updateSpace(spaceId = dto.id, input = input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.HALL)) }
                    .failure<ConflictException>()
                transaction {
                    EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq spaceId }.single()
                }[EncounterSpaceTable.roomLayout] shouldBe
                    "FOYER"
            }
        }

        test("the audit snapshot carries the template name and no person") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val dto =
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.createSpace(input(EncounterProfile.ASSEMBLY, EncounterRoomLayout.SMALL_GROUP)) }
                        .getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                val snapshot = auditEntriesOf(Uuid.parse(dto.id)).single().second!!
                snapshot shouldContain "\"roomLayout\":\"SMALL_GROUP\""
                snapshot shouldNotContain boardId.toString()
            }
        }

        test("the entry notice is the same for every template of a profile (the template never enters the consent text)") {
            val board = fx.createMember(role = AccountRole.BOARD)

            fun consentHash(
                profile: EncounterProfile,
                layout: EncounterRoomLayout,
            ): String {
                val id = fx.createSpace(createdBy = board, profile = profile, roomLayout = layout)
                val row = transaction { EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq id }.single() }
                return encounterConsentFor(
                    profile = profileOf(row),
                    pulpitRecordingAllowed = row[EncounterSpaceTable.pulpitRecordingAllowed],
                ).sha256
            }
            EncounterProfile.entries.forEach { profile ->
                val fitting = EncounterRoomLayout.entries.filter { it.allowedFor(profile) }
                fitting.map { consentHash(profile, it) }.toSet().size shouldBe 1
            }
        }
    })
