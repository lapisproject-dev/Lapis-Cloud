package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterTablesConfig
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.uuid.Uuid

/** Welle V1.9.80 -- the table configuration of a room: who may set it, its ranges, its tie to the assembly profile, the freeze while open. */
class EncounterSpaceTablesConfigTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun assemblyInput(tables: EncounterTablesConfig?) =
            EncounterSpaceInput(title = "Versammlung", profile = EncounterProfile.ASSEMBLY, tables = tables)

        test("createSpace stores and returns the table configuration; without it tables are off with the defaults") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val on =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(assemblyInput(EncounterTablesConfig(enabled = true, count = 5, seats = 3)))
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(on.id)
                on.tables shouldBe EncounterTablesConfig(enabled = true, count = 5, seats = 3)
                val off = rig.asMember(client = client, member = boardId) { it.createSpace(assemblyInput(null)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(off.id)
                off.tables shouldBe EncounterTablesConfig(enabled = false, count = 4, seats = 6)
            }
        }

        test("only BOARD/ADMIN configure tables") {
            val rig = EncounterRig()
            val member = fx.createMember()
            encounterApp {
                rig
                    .asMember(client = client, member = member) {
                        it.createSpace(assemblyInput(EncounterTablesConfig(enabled = true)))
                    }.failure<ForbiddenException>()
            }
        }

        test("ranges: count 1..12, seats 2..8, otherwise BadRequest -- even for a disabled configuration") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                listOf(0 to 4, 13 to 4, 4 to 1, 4 to 9, -1 to 4).forEach { (count, seats) ->
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(assemblyInput(EncounterTablesConfig(enabled = false, count = count, seats = seats)))
                        }.failure<BadRequestException>()
                }
                listOf(1 to 2, 12 to 8).forEach { (count, seats) ->
                    val dto =
                        rig
                            .asMember(client = client, member = boardId) {
                                it.createSpace(assemblyInput(EncounterTablesConfig(enabled = true, count = count, seats = seats)))
                            }.getOrThrow()
                    fx.spaceIds += Uuid.parse(dto.id)
                }
            }
        }

        test(
            "tables with the church-service profile are refused, on create and on update; switching the profile away needs tables off first",
        ) {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                rig
                    .asMember(client = client, member = boardId) {
                        it.createSpace(
                            EncounterSpaceInput(
                                title = "Gottesdienst",
                                profile = EncounterProfile.CHURCH_SERVICE,
                                tables = EncounterTablesConfig(enabled = true),
                            ),
                        )
                    }.failure<BadRequestException>()
                // default profile on create is the church service
                rig
                    .asMember(client = client, member = boardId) {
                        it.createSpace(EncounterSpaceInput(title = "X", tables = EncounterTablesConfig(enabled = true)))
                    }.failure<BadRequestException>()
                val dto =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(assemblyInput(EncounterTablesConfig(enabled = true)))
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                // profile -> church while tables are on and the input carries no tables: BadRequest, not a SQL exception
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = dto.id,
                            input = EncounterSpaceInput(title = "Versammlung", profile = EncounterProfile.CHURCH_SERVICE),
                        )
                    }.failure<BadRequestException>()
                // tables off AND profile church in one call is fine
                val church =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.updateSpace(
                                spaceId = dto.id,
                                input =
                                    EncounterSpaceInput(
                                        title = "Versammlung",
                                        profile = EncounterProfile.CHURCH_SERVICE,
                                        tables = EncounterTablesConfig(enabled = false),
                                    ),
                            )
                        }.getOrThrow()
                church.tables.enabled shouldBe false
            }
        }

        test("update: tables = null keeps the configuration (an old cached admin client sends none)") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val dto =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(assemblyInput(EncounterTablesConfig(enabled = true, count = 7, seats = 4)))
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                val renamed =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.updateSpace(spaceId = dto.id, input = EncounterSpaceInput(title = "Umbenannt"))
                        }.getOrThrow()
                renamed.tables shouldBe EncounterTablesConfig(enabled = true, count = 7, seats = 4)
                renamed.profile shouldBe EncounterProfile.ASSEMBLY
            }
        }

        test("a real change while the room is open is a ConflictException; the same values are not a change") {
            val rig = EncounterRig()
            val w = fx.tableWorld(rig = rig)
            encounterApp {
                openAndEnter(w = w, w.steward)
                val same =
                    EncounterSpaceInput(
                        title = "Gottesdienst",
                        profile = EncounterProfile.ASSEMBLY,
                        tables = EncounterTablesConfig(enabled = true, count = 3, seats = 2),
                    )
                rig.asMember(client = client, member = w.board) { it.updateSpace(spaceId = w.spaceId, input = same) }.getOrThrow()
                rig
                    .asMember(client = client, member = w.board) {
                        it.updateSpace(
                            spaceId = w.spaceId,
                            input = same.copy(tables = EncounterTablesConfig(enabled = true, count = 4, seats = 2)),
                        )
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = w.board) {
                        it.updateSpace(
                            spaceId = w.spaceId,
                            input = same.copy(tables = EncounterTablesConfig(enabled = false, count = 3, seats = 2)),
                        )
                    }.failure<ConflictException>()
                rig.asMember(client = client, member = w.steward) { it.closeSpace(w.spaceId) }.getOrThrow()
                rig
                    .asMember(client = client, member = w.board) {
                        it.updateSpace(
                            spaceId = w.spaceId,
                            input = same.copy(tables = EncounterTablesConfig(enabled = true, count = 4, seats = 2)),
                        )
                    }.getOrThrow()
                    .tables shouldBe EncounterTablesConfig(enabled = true, count = 4, seats = 2)
            }
        }

        test("the audit snapshot carries the three table fields and no person") {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            encounterApp {
                val dto =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(assemblyInput(EncounterTablesConfig(enabled = true, count = 6, seats = 5)))
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                val snapshot = auditEntriesOf(Uuid.parse(dto.id)).single().second!!
                snapshot shouldContain "\"tablesEnabled\":true"
                snapshot shouldContain "\"tableCount\":6"
                snapshot shouldContain "\"tableSeats\":5"
                snapshot shouldNotContain boardId.toString()
            }
        }
    })
