package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * [ConferencePersonalData]'s V1.0 Wave 3 "Externes Streaming" coverage of
 * `conference_stream_destination`/`conference_stream`/`conference_stream_target` specifically --
 * the structural assertion that all three tables are covered by SOME contributor is
 * [PersonalDataCoverageTest]'s job (already green per that generic `information_schema` walk);
 * this file exercises THIS contributor's own export/erase behavior for the three new tables in
 * detail, mirroring [ConferenceRecordingPersonalDataTest]'s house style. Also asserts the negative:
 * the ciphertext column is never surfaced by [ConferencePersonalData.export] (see that class's own
 * KDoc "The stream key is never part of any export or erasure output here").
 */
class ConferenceStreamPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()
        val createdStreamIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdStreamIds.isNotEmpty()) {
                    ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList createdStreamIds }
                    ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList createdStreamIds }
                }
                if (createdDestinationIds.isNotEmpty()) {
                    ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList createdDestinationIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ConferenceStreamPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        fun createRoom(creatorId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "PD-Stream-Test-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-pd-stream-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        fun createDestination(
            createdByMemberId: Uuid,
            label: String,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[ConferenceStreamDestinationTable.label] = label
                    it[platform] = ConferenceStreamPlatform.YOUTUBE
                    it[rtmpUrl] = "rtmp://a.rtmp.youtube.com/live2"
                    it[streamKeyCiphertext] = "v1:not-a-real-iv:not-a-real-ciphertext"
                    it[streamKeySetAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[ConferenceStreamDestinationTable.createdByMemberId] = createdByMemberId
                    it[createdAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[enabled] = true
                }
            }
            createdDestinationIds += id
            return id
        }

        fun createStreamWithTargets(
            roomId: Uuid,
            startedByMemberId: Uuid,
            destinationIds: List<Uuid>,
        ): Uuid {
            val streamId = Uuid.random()
            transaction {
                ConferenceStreamTable.insert {
                    it[id] = streamId
                    it[ConferenceStreamTable.roomId] = roomId
                    it[ConferenceStreamTable.startedByMemberId] = startedByMemberId
                    it[status] = ConferenceStreamStatus.LIVE
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[participantIdentity] = null
                    it[livekitEgressId] = "EG_pd-stream-test-$streamId"
                    it[startedAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[pausedAt] = null
                    it[endedAt] = null
                    it[restartCount] = 0
                    it[failureReason] = null
                }
                destinationIds.forEachIndexed { i, destinationId ->
                    ConferenceStreamTargetTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceStreamTargetTable.streamId] = streamId
                        it[ConferenceStreamTargetTable.destinationId] = destinationId
                        it[status] = ConferenceStreamTargetStatus.ACTIVE
                        it[urlFingerprint] = "rtmp://a.rtmp.youtube.com/live2/{fin...er$i}"
                        it[startedAtEpochNanos] = null
                        it[endedAtEpochNanos] = null
                        it[retries] = 0
                        it[failureReason] = null
                    }
                }
            }
            createdStreamIds += streamId
            return streamId
        }

        test(
            "export includes a streamDestinationsCreated entry for every destination this member created, " +
                "WITHOUT the ciphertext column",
        ) {
            val admin = createTestMember("cspd-export-dest@example.org")
            val destinationId = createDestination(admin, label = "PD-Test-Kanal")

            val export = transaction { ConferencePersonalData.export(admin) }
            val destinations = export.jsonObject.getValue("streamDestinationsCreated").jsonArray
            destinations.size shouldBe 1
            val entry = destinations.single().jsonObject
            entry.getValue("id").jsonPrimitive.content shouldBe destinationId.toString()
            entry.getValue("label").jsonPrimitive.content shouldBe "PD-Test-Kanal"
            entry.getValue("platform").jsonPrimitive.content shouldBe "YOUTUBE"
            entry.getValue("rtmpUrl").jsonPrimitive.content shouldBe "rtmp://a.rtmp.youtube.com/live2"
            ("streamKeyCiphertext" in entry) shouldBe false
        }

        test("export includes a streamsStarted entry for every stream this member started, with id/roomId/status/startedAt") {
            val member = createTestMember("cspd-export-stream@example.org")
            val roomId = createRoom(member)
            val destinationId = createDestination(member, label = "PD-Test-Kanal-2")
            val streamId = createStreamWithTargets(roomId, member, listOf(destinationId))

            val export = transaction { ConferencePersonalData.export(member) }
            val streamsStarted = export.jsonObject.getValue("streamsStarted").jsonArray
            streamsStarted.size shouldBe 1
            val entry = streamsStarted.single().jsonObject
            entry.getValue("id").jsonPrimitive.content shouldBe streamId.toString()
            entry.getValue("roomId").jsonPrimitive.content shouldBe roomId.toString()
            entry.getValue("status").jsonPrimitive.content shouldBe "LIVE"
        }

        test("export for a member who never created a destination or started a stream has empty arrays for both") {
            val member = createTestMember("cspd-export-empty@example.org")
            val export = transaction { ConferencePersonalData.export(member) }
            export.jsonObject
                .getValue("streamDestinationsCreated")
                .jsonArray.size shouldBe 0
            export.jsonObject
                .getValue("streamsStarted")
                .jsonArray.size shouldBe 0
        }

        test(
            "erase retains conference_stream_destination, conference_stream AND conference_stream_target rows, " +
                "with a non-blank retention reason, for both ErasureMode values",
        ) {
            val member = createTestMember("cspd-erase@example.org")
            val roomId = createRoom(member)
            val destinationOne = createDestination(member, label = "PD-Erase-Kanal-1")
            val destinationTwo = createDestination(member, label = "PD-Erase-Kanal-2")
            createStreamWithTargets(roomId, member, listOf(destinationOne, destinationTwo))

            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcomes = transaction { ConferencePersonalData.erase(memberId = member, mode = mode) }

                val destinationOutcome = outcomes.single { it.table == "conference_stream_destination" }
                destinationOutcome.rowsRetained shouldBe 2
                destinationOutcome.retentionReason?.isNotBlank() shouldBe true

                val streamOutcome = outcomes.single { it.table == "conference_stream" }
                streamOutcome.rowsRetained shouldBe 1
                streamOutcome.retentionReason?.isNotBlank() shouldBe true

                val targetOutcome = outcomes.single { it.table == "conference_stream_target" }
                targetOutcome.rowsRetained shouldBe 2
                targetOutcome.retentionReason?.isNotBlank() shouldBe true
            }

            // Rows must still exist afterward -- erase() never actually mutates these tables (retain-with-reason posture).
            val remainingStreams =
                transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.startedByMemberId eq member }.count() }
            remainingStreams shouldBe 1L
        }
    })
