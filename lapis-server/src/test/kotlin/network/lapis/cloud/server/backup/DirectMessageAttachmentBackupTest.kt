package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DirectMessageAttachmentTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.233 -- `direct_message_attachment` is a blob table of the organization backup: row AND file travel in the bundle and come back byte
 * for byte, for a SENT attachment (message FK) as well as for a still PENDING one (message_id NULL). The restore order follows the FK, so
 * the message exists before its attachment.
 */
class DirectMessageAttachmentBackupTest :
    FunSpec({
        test("a sent and a pending direct-message attachment round-trip with their files") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("dm-att-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("dm-att-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("dm-att-source").toFile()
            val targetRoot = Files.createTempDirectory("dm-att-target").toFile()
            val adminId = Uuid.random()
            val otherId = Uuid.random()
            val messageId = Uuid.random()
            val sentId = Uuid.random()
            val pendingId = Uuid.random()
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x01, 0x02, 0x03)
            val sentKey = "dm/$adminId/$sentId.bin"
            val pendingKey = "dm/$adminId/$pendingId.bin"

            transaction(sourceDb) {
                listOf(adminId to "dm-att-backup-a@example.org", otherId to "dm-att-backup-b@example.org").forEach { (memberId, mail) ->
                    MemberTable.insert {
                        it[id] = memberId
                        it[displayName] = "DM Attachment Backup"
                        it[email] = mail
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[id] = Uuid.random()
                        it[AccountTable.memberId] = memberId
                        it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                        it[role] = AccountRole.ADMIN
                    }
                }
                DirectMessageTable.insert {
                    it[id] = messageId
                    it[senderId] = adminId
                    it[recipientId] = otherId
                    it[body] = "Mit Anhang"
                    it[sentAt] = LocalDateTime(2027, 1, 2, 9, 0)
                }
                listOf(sentId to sentKey, pendingId to pendingKey).forEach { (attachmentId, key) ->
                    DirectMessageAttachmentTable.insert {
                        it[id] = attachmentId
                        it[DirectMessageAttachmentTable.messageId] = if (attachmentId == sentId) messageId else null
                        it[uploaderId] = adminId
                        it[recipientId] = otherId
                        it[storageKey] = key
                        it[originalFilename] = "Datei-$attachmentId.pdf"
                        it[nameKey] = "datei-$attachmentId.pdf"
                        it[mimeType] = "application/pdf"
                        it[textCharset] = null
                        it[sizeBytes] = bytes.size.toLong()
                        it[sha256] = "0".repeat(64)
                        it[createdAt] = LocalDateTime(2027, 1, 2, 8, 0)
                    }
                }
            }
            listOf(sentKey, pendingKey).forEach { sourceRoot.resolve(it).apply { parentFile.mkdirs() }.writeBytes(bytes) }

            val bundle = File.createTempFile("dm-att-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                val rows =
                    transaction(targetDb) { DirectMessageAttachmentTable.selectAll().associateBy { it[DirectMessageAttachmentTable.id] } }
                rows.keys shouldBe setOf(sentId, pendingId)
                rows.getValue(sentId)[DirectMessageAttachmentTable.messageId] shouldBe messageId
                rows.getValue(pendingId)[DirectMessageAttachmentTable.messageId] shouldBe null
                rows.getValue(sentId)[DirectMessageAttachmentTable.storageKey] shouldBe sentKey
                targetRoot.resolve(sentKey).readBytes() shouldBe bytes
                targetRoot.resolve(pendingKey).readBytes() shouldBe bytes
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
