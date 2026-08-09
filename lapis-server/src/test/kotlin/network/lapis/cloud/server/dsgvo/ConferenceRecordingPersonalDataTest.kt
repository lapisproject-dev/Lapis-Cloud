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
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
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
 * [ConferencePersonalData]'s V1.0 Wave 2 "Aufzeichnung" coverage of `conference_recording`/
 * `conference_recording_track` specifically -- the structural assertion that both tables are
 * covered by SOME contributor is [PersonalDataCoverageTest]'s job (already green per that generic
 * `information_schema` walk); this file exercises THIS contributor's own export/erase behavior for
 * the two new tables in detail, mirroring [AuditLogPersonalDataTest]'s house style.
 */
class ConferenceRecordingPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdRecordingIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdRecordingIds.isNotEmpty()) {
                    ConferenceRecordingTrackTable.deleteWhere { ConferenceRecordingTrackTable.recordingId inList createdRecordingIds }
                    ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id inList createdRecordingIds }
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
                    it[displayName] = "ConferenceRecordingPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
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
                    it[title] = "PD-Test-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-pd-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        fun createRecordingWithTracks(
            roomId: Uuid,
            startedByMemberId: Uuid,
            trackCount: Int,
        ): Uuid {
            val recordingId = Uuid.random()
            transaction {
                ConferenceRecordingTable.insert {
                    it[id] = recordingId
                    it[ConferenceRecordingTable.roomId] = roomId
                    it[ConferenceRecordingTable.startedByMemberId] = startedByMemberId
                    it[startedAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[stoppedAt] = null
                    it[readyAt] = null
                    it[status] = ConferenceRecordingStatus.RECORDING
                    it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                    it[documentId] = null
                    it[rawDir] = recordingId.toString()
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = null
                    it[composeAttempts] = 0
                }
                repeat(trackCount) { i ->
                    ConferenceRecordingTrackTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceRecordingTrackTable.recordingId] = recordingId
                        it[egressId] = "EG_pd-test-$recordingId-$i"
                        it[livekitTrackId] = "TR_pd-test-$i"
                        it[participantIdentity] = "identity-$i"
                        it[trackSource] = ConferenceRecordingTrackSource.CAMERA
                        it[status] = ConferenceRecordingTrackStatus.ACTIVE
                        it[startedAtEpochNanos] = null
                        it[endedAtEpochNanos] = null
                        it[fileName] = null
                        it[durationMs] = null
                        it[sizeBytes] = null
                    }
                }
            }
            createdRecordingIds += recordingId
            return recordingId
        }

        test("coveredTables includes exactly the two Wave 2 tables (plus Wave 1's own, plus Wave 3's three streaming tables)") {
            ConferencePersonalData.coveredTables shouldBe
                setOf(
                    ConferenceRoomTable,
                    ConferenceParticipationTable,
                    ConferenceRecordingTable,
                    ConferenceRecordingTrackTable,
                    ConferenceStreamDestinationTable,
                    ConferenceStreamTable,
                    ConferenceStreamTargetTable,
                )
        }

        test("export includes a recordingsStarted entry for every recording this member started, with id/roomId/status/startedAt") {
            val member = createTestMember("crpd-export@example.org")
            val roomId = createRoom(member)
            val recordingId = createRecordingWithTracks(roomId, member, trackCount = 2)

            val export = transaction { ConferencePersonalData.export(member) }
            val recordingsStarted = export.jsonObject.getValue("recordingsStarted").jsonArray
            recordingsStarted.size shouldBe 1
            val entry = recordingsStarted.single().jsonObject
            entry.getValue("id").jsonPrimitive.content shouldBe recordingId.toString()
            entry.getValue("roomId").jsonPrimitive.content shouldBe roomId.toString()
            entry.getValue("status").jsonPrimitive.content shouldBe "RECORDING"
        }

        test("export for a member who never started a recording has an empty recordingsStarted array") {
            val member = createTestMember("crpd-export-empty@example.org")
            val export = transaction { ConferencePersonalData.export(member) }
            export.jsonObject
                .getValue("recordingsStarted")
                .jsonArray.size shouldBe 0
        }

        test(
            "erase retains conference_recording AND conference_recording_track rows, with a non-blank retention reason, for both ErasureMode values",
        ) {
            val member = createTestMember("crpd-erase@example.org")
            val roomId = createRoom(member)
            createRecordingWithTracks(roomId, member, trackCount = 3)

            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcomes = transaction { ConferencePersonalData.erase(member, mode) }
                val recordingOutcome = outcomes.single { it.table == "conference_recording" }
                recordingOutcome.rowsRetained shouldBe 1
                recordingOutcome.retentionReason?.isNotBlank() shouldBe true

                val trackOutcome = outcomes.single { it.table == "conference_recording_track" }
                trackOutcome.rowsRetained shouldBe 3
                trackOutcome.retentionReason?.isNotBlank() shouldBe true
            }

            // Rows must still exist afterward -- erase() never actually mutates either table (retain-with-reason posture).
            val remainingRecordings =
                transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.startedByMemberId eq member }.count() }
            remainingRecordings shouldBe 1L
        }
    })
