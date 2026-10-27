package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.232 -- `mailing_message_attachment` is a blob table of the organization backup: row AND file travel in the bundle and come back byte
 * for byte; a row whose file the retention job removed (`file_deleted_at`) exports and restores as a plain metadata row without breaking the bundle.
 */
class MailingAttachmentBackupTest :
    FunSpec({
        test("an attachment row and its file round-trip; a retention-removed row travels without a file") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("mail-att-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("mail-att-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("mail-att-source").toFile()
            val targetRoot = Files.createTempDirectory("mail-att-target").toFile()
            val adminId = Uuid.random()
            val listId = Uuid.random()
            val messageId = Uuid.random()
            val liveId = Uuid.random()
            val goneId = Uuid.random()
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x01, 0x02, 0x03)
            val liveKey = "mailing/$messageId/$liveId.bin"
            val goneKey = "mailing/$messageId/$goneId.bin"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Mailing Attachment Backup Admin"
                    it[email] = "mail-att-backup@example.org"
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
                MailingListTable.insert {
                    it[id] = listId
                    it[name] = "Backup-Liste"
                    it[description] = null
                    it[createdBy] = adminId
                }
                MailingMessageTable.insert {
                    it[id] = messageId
                    it[mailingListId] = listId
                    it[subject] = "Backup"
                    it[bodyText] = "Text"
                    it[bodyHtml] = null
                    it[sentBy] = adminId
                    it[status] = MailingMessageStatus.SENT
                    it[sentAt] = LocalDateTime(2027, 1, 2, 9, 0)
                }
                listOf(liveId to liveKey, goneId to goneKey).forEach { (attachmentId, key) ->
                    MailingMessageAttachmentTable.insert {
                        it[id] = attachmentId
                        it[mailingMessageId] = messageId
                        it[storageKey] = key
                        it[originalFilename] = "Datei-$attachmentId.pdf"
                        it[nameKey] = "datei-$attachmentId.pdf"
                        it[mimeType] = "application/pdf"
                        it[textCharset] = null
                        it[sizeBytes] = bytes.size.toLong()
                        it[sha256] = "0".repeat(64)
                        it[uploadedBy] = adminId
                        it[uploadedAt] = LocalDateTime(2027, 1, 2, 8, 0)
                        it[fileDeletedAt] = if (attachmentId == goneId) LocalDateTime(2027, 8, 1, 3, 0) else null
                    }
                }
            }
            sourceRoot.resolve(liveKey).apply { parentFile.mkdirs() }.writeBytes(bytes)
            // goneKey has NO file: exactly what the retention job leaves behind.

            val bundle = File.createTempFile("mail-att-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                val rows =
                    transaction(targetDb) {
                        MailingMessageAttachmentTable.selectAll().associateBy { it[MailingMessageAttachmentTable.id] }
                    }
                rows.keys shouldBe setOf(liveId, goneId)
                rows.getValue(liveId)[MailingMessageAttachmentTable.storageKey] shouldBe liveKey
                rows.getValue(liveId)[MailingMessageAttachmentTable.fileDeletedAt] shouldBe null
                rows.getValue(goneId)[MailingMessageAttachmentTable.fileDeletedAt] shouldBe LocalDateTime(2027, 8, 1, 3, 0)
                targetRoot.resolve(liveKey).readBytes() shouldBe bytes
                targetRoot.resolve(goneKey).exists() shouldBe false
                transaction(targetDb) {
                    MailingMessageAttachmentTable.selectAll().where { MailingMessageAttachmentTable.id eq liveId }.count()
                } shouldBe 1L
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
