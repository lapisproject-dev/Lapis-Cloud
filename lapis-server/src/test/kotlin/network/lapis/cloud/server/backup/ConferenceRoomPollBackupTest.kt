package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.304 -- the four room-poll tables are found by the schema catalog on their own and travel in the whole-organization backup (the marker
 * table too: it is live data of a running poll, like `poll_participation`). Round trip of an open poll with an option, a marker and an answer.
 */
class ConferenceRoomPollBackupTest :
    FunSpec({
        test("an open room poll with its option, marker and anonymous answer round-trips through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("room-poll-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("room-poll-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("room-poll-source").toFile()
            val targetRoot = Files.createTempDirectory("room-poll-target").toFile()
            val adminId = Uuid.random()
            val roomId = Uuid.random()
            val pollId = Uuid.random()
            val optionId = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Raumumfrage Backup"
                    it[email] = "room-poll-backup@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it[role] = AccountRole.ADMIN
                }
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Backupraum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-$roomId"
                    it[createdByMemberId] = adminId
                    it[createdAt] = at
                    it[maxParticipants] = 25
                }
                ConferenceRoomPollTable.insert {
                    it[id] = pollId
                    it[ConferenceRoomPollTable.roomId] = roomId
                    it[openRoomId] = roomId
                    it[question] = "Backup-Frage"
                    it[countingMode] = RoomPollCountingMode.BOTH
                    it[status] = RoomPollStatus.OPEN
                    it[startedAt] = at
                    it[stateVersion] = 1L
                    it[snapshotAnswerCount] = 0
                }
                ConferenceRoomPollOptionTable.insert {
                    it[id] = optionId
                    it[ConferenceRoomPollOptionTable.pollId] = pollId
                    it[position] = 0
                    it[text] = "Ja"
                }
                ConferenceRoomPollVotedTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceRoomPollVotedTable.pollId] = pollId
                    it[memberId] = adminId
                }
                ConferenceRoomPollAnswerTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceRoomPollAnswerTable.pollId] = pollId
                    it[ConferenceRoomPollAnswerTable.optionId] = optionId
                    it[voterGroup] = RoomPollVoterGroup.MEMBERS
                    it[weightLtr] = BigDecimal("3.50")
                }
            }

            val bundle = File.createTempFile("room-poll-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val poll = ConferenceRoomPollTable.selectAll().where { ConferenceRoomPollTable.id eq pollId }.single()
                    poll[ConferenceRoomPollTable.question] shouldBe "Backup-Frage"
                    poll[ConferenceRoomPollTable.openRoomId] shouldBe roomId
                    ConferenceRoomPollOptionTable.selectAll().where { ConferenceRoomPollOptionTable.pollId eq pollId }.count() shouldBe 1L
                    ConferenceRoomPollVotedTable.selectAll().where { ConferenceRoomPollVotedTable.pollId eq pollId }.count() shouldBe 1L
                    ConferenceRoomPollAnswerTable
                        .selectAll()
                        .where { ConferenceRoomPollAnswerTable.pollId eq pollId }
                        .single()[ConferenceRoomPollAnswerTable.weightLtr]
                        .toPlainString() shouldBe "3.50"
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
