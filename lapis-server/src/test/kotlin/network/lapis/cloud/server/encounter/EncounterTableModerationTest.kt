package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/** Welle V1.9.80 -- quieting a table and sending a person back to the plenum: the role matrix, the protection rules, no trace. */
class EncounterTableModerationTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        test("quietTable: steward and board may, the congregation may not; range and closed room are refused") {
            val w = fx.tableWorld()
            encounterApp {
                w.rig
                    .asMember(client = client, member = w.steward) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .failure<ConflictException>() // closed
                openAndEnter(w = w, w.steward, w.a, w.b)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.b,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .failure<ForbiddenException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .failure<ForbiddenException>()
                w.rig
                    .asMember(client = client, member = w.steward) { it.quietTable(spaceId = w.spaceId, table = 3, quiet = true) }
                    .failure<BadRequestException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.board,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = false) }
                    .getOrThrow()
                // quieting a table nobody sits at is a harmless no-op
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.quietTable(spaceId = w.spaceId, table = 2, quiet = true) }
                    .getOrThrow()
            }
        }

        test("quietTable is refused in a room without tables") {
            val w = fx.tableWorld(tablesEnabled = false)
            encounterApp {
                openAndEnter(w = w, w.steward)
                w.rig
                    .asMember(client = client, member = w.steward) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .failure<ForbiddenException>()
            }
        }

        test("sendToPlenum: a steward may send the congregation back, never a pulpit person, another steward or board/admin") {
            val w = fx.tableWorld()
            val steward2 = fx.createMember(name = "Zweiter Ordner")
            fx.setRole(spaceId = w.space, memberId = steward2, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                openAndEnter(w = w, w.steward, steward2, w.pulpit, w.board, w.a)
                val sat =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }
                        .getOrThrow()
                listOf(w.pulpit, steward2, w.board).forEach { protectedPerson ->
                    w.rig
                        .asMember(client = client, member = w.steward) {
                            it.sendToPlenum(spaceId = w.spaceId, memberId = protectedPerson.toString())
                        }.failure<ForbiddenException>()
                }
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.steward.toString()) }
                    .failure<ConflictException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.sendToPlenum(spaceId = w.spaceId, memberId = Uuid.random().toString()) }
                    .failure<NotFoundException>()
                w.rig
                    .asMember(client = client, member = w.a) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.a.toString()) }
                    .failure<ConflictException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.board,
                    ) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.steward.toString()) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.a.toString()) }
                    .getOrThrow()
                // sent back: no table, the table room is gone, the person is still present and NOT removed
                w.rig.liveKit.deletedRooms shouldContain sat.join.livekitRoomName
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                // ... but stays away from the tables for a while (each return would rotate a table)
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }
                    .failure<ForbiddenException>()
            }
        }

        test("a congregation member cannot send anybody to the plenum") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                w.rig
                    .asMember(client = client, member = w.a) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.b.toString()) }
                    .failure<ForbiddenException>()
            }
        }

        test("moderating tables writes no audit entry; a removed person loses the table and cannot come back to one") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }.getOrThrow()
                val before = fx.auditCount()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.a.toString()) }
                    .getOrThrow()
                fx.auditCount() shouldBe before
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.removeFromSpace(spaceId = w.spaceId, memberId = w.b.toString()) }
                    .getOrThrow()
                w.rig.asMember(client = client, member = w.b) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
            }
        }

        test("silencing a person at a table takes them off it at once; they cannot sit down again or fetch a token") {
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
                w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.silenceInSpace(spaceId = w.spaceId, memberId = w.a.toString()) }
                    .getOrThrow()
                // the table was rotated: the old room is deleted, the silenced person has no seat and no token
                w.rig.liveKit.deletedRooms shouldContain first.join.livekitRoomName
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 0) }
                    .failure<ForbiddenException>()
                // the other person keeps the table with a new room
                w.rig
                    .asMember(client = client, member = w.b) { it.tableToken(w.spaceId).token }
                    .getOrThrow()!!
                    .table shouldBe 0
            }
        }

        test("joinTable after silencing is refused even when the person never sat at a table") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.silenceInSpace(spaceId = w.spaceId, memberId = w.a.toString()) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                    .failure<ForbiddenException>()
            }
        }

        test("a person sent back to the plenum cannot sit down again right away") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.a.toString()) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                    .failure<ForbiddenException>()
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
            }
        }
    })
