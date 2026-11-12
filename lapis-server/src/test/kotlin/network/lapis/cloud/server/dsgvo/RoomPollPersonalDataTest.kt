package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.conference.roompoll.RoomPollFixtures
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.304 -- the marker table is owned by [RoomPollPersonalData]: exported while a poll runs, erased in every mode; the registry knows the tables. */
class RoomPollPersonalDataTest :
    FunSpec({
        val fx = RoomPollFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanUp() }

        fun markerFor(
            poll: Uuid,
            member: Uuid,
        ) = transaction {
            ConferenceRoomPollVotedTable.insert {
                it[id] = Uuid.random()
                it[pollId] = poll
                it[memberId] = member
            }
        }

        fun openPoll(roomId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomPollTable.insert {
                    it[ConferenceRoomPollTable.id] = id
                    it[ConferenceRoomPollTable.roomId] = roomId
                    it[openRoomId] = roomId
                    it[question] = "Datenschutzfrage?"
                    it[countingMode] = RoomPollCountingMode.HEAD_COUNT
                    it[status] = RoomPollStatus.OPEN
                    it[startedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[stateVersion] = 1L
                    it[snapshotAnswerCount] = 0
                }
            }
            return id
        }

        test("the export names the polls the member has a marker for; the erasure deletes the markers in every mode") {
            val member = fx.member()
            val room = fx.room(creator = member)
            val poll = openPoll(room.id)
            markerFor(poll, member)
            val json = transaction { RoomPollPersonalData.exportMember(member) }
            json.jsonObject["openPollsAnswered"]!!.jsonArray.size shouldBe 1
            ErasureMode.entries.forEach { mode ->
                if (fx.markers(poll).isEmpty()) markerFor(poll, member)
                val outcome = transaction { RoomPollPersonalData.eraseMember(memberId = member, mode = mode) }
                outcome.single().table shouldBe "conference_room_poll_voted"
                outcome.single().rowsDeleted shouldBe 1
                transaction {
                    ConferenceRoomPollVotedTable.selectAll().where { ConferenceRoomPollVotedTable.memberId eq member }.count()
                } shouldBe
                    0L
            }
        }

        test("the registry owns the marker table; the other three tables are on the written no-personal-data list") {
            PersonalDataRegistry.coveredTableNames().contains("conference_room_poll_voted") shouldBe true
            listOf("conference_room_poll", "conference_room_poll_option", "conference_room_poll_answer").forEach {
                PersonalDataRegistry.noPersonalDataAllowlist.containsKey(it) shouldBe true
            }
        }
    })
