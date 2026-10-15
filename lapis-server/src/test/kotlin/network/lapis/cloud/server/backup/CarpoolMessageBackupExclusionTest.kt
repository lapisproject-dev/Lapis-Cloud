package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CarpoolMessageTable
import network.lapis.cloud.server.db.generated.CarpoolMessageThreadTable
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolMessageSide
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * Welle V1.9.139 -- `carpool_message` and `carpool_message_thread` are deliberately NOT part of the whole-organization backup (see
 * `OrganizationSchemaCatalog.EXCLUDED_TABLES`): the bundle carries no message text, and a restore leaves both tables empty (the participation
 * rows themselves are organizational data and come back).
 */
class CarpoolMessageBackupExclusionTest :
    FunSpec({
        test("a bundle holds no ride message; the restore works, the participation comes back, the message tables stay empty") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-target-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-carpool-source-storage").toFile()
            val targetStorage = Files.createTempDirectory("backup-carpool-target-storage").toFile()
            val adminId = Uuid.random()
            val personId = Uuid.random()
            val postingId = Uuid.random()
            val participationId = Uuid.random()
            val secret = "RIDE-MESSAGE-SECRET-NEVER-IN-A-BACKUP"
            val at = LocalDateTime(2027, 1, 1, 10, 0)

            transaction(sourceDb) {
                listOf(adminId to MemberStatus.ACTIVE, personId to MemberStatus.GUEST).forEach { (memberId, memberStatus) ->
                    MemberTable.insert {
                        it[id] = memberId
                        it[displayName] = "Carpool Backup $memberStatus"
                        it[email] = "carpool-backup-$memberId@example.org"
                        it[status] = memberStatus
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[id] = Uuid.random()
                        it[AccountTable.memberId] = memberId
                        it[role] = if (memberId == adminId) AccountRole.ADMIN else AccountRole.MEMBER
                    }
                }
                CarpoolPostingTable.insert {
                    it[id] = postingId
                    it[authorMemberId] = adminId
                    it[type] = CarpoolPostingType.OFFER
                    it[fromPlace] = "Braunschweig"
                    it[toPlace] = "Hannover"
                    it[departureDate] = LocalDate(2027, 3, 1)
                    it[departureTime] = null
                    it[seatsOffered] = 2
                    it[notes] = null
                    it[createdAt] = at
                    it[updatedAt] = at
                }
                CarpoolParticipationTable.insert {
                    it[id] = participationId
                    it[CarpoolParticipationTable.postingId] = postingId
                    it[CarpoolParticipationTable.personId] = personId
                    it[initiator] = CarpoolParticipationInitiator.PASSENGER
                    it[status] = CarpoolParticipationStatus.CONFIRMED
                    it[createdAt] = at
                    it[updatedAt] = at
                    it[decidedAt] = at
                }
                CarpoolMessageThreadTable.insert { it[CarpoolMessageThreadTable.participationId] = participationId }
                CarpoolMessageTable.insert {
                    it[id] = Uuid.random()
                    it[CarpoolMessageTable.participationId] = participationId
                    it[senderSide] = CarpoolMessageSide.PASSENGER
                    it[body] = secret
                    it[sentAt] = at
                }
            }

            val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = File.createTempFile("carpool-backup-bundle-", ".zip")
            try {
                bundle.outputStream().use { out ->
                    OrganizationExportService(
                        database = sourceDb,
                        documentStorageRoot = sourceStorage,
                    ).streamExport(actor = actor, sink = out)
                }
                ZipFile(bundle).use { zip ->
                    val names =
                        zip
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .toList()
                    names.none { it.contains("carpool_message") } shouldBe true
                    names.forEach { name ->
                        val content = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                        String(content, Charsets.ISO_8859_1).contains(secret) shouldBe false
                    }
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorage)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                transaction(targetDb) { CarpoolParticipationTable.selectAll().count() } shouldBe 1L
                transaction(targetDb) { CarpoolMessageTable.selectAll().count() } shouldBe 0L
                transaction(targetDb) { CarpoolMessageThreadTable.selectAll().count() } shouldBe 0L
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                targetStorage.deleteRecursively()
            }
        }
    })
