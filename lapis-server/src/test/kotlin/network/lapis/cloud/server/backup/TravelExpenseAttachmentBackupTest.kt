package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.198 -- `travel_expense_attachment` is a blob table of the organization backup: the metadata row AND the file bytes travel in the
 * bundle (`blobs/<storage key>`) and come back byte for byte; a table that is not a blob table would restore a row pointing at nothing.
 */
class TravelExpenseAttachmentBackupTest :
    FunSpec({
        test("an attachment row and its file round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("te-att-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("te-att-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("te-att-source").toFile()
            val targetRoot = Files.createTempDirectory("te-att-target").toFile()
            val adminId = Uuid.random()
            val reportId = Uuid.random()
            val attachmentId = Uuid.random()
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x01, 0x02, 0x03)
            val storageKey = "travel-expenses/$reportId/attachments/$attachmentId.bin"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Attachment Backup Admin"
                    it[email] = "te-att-backup@example.org"
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
                TravelExpenseReportTable.insert {
                    it[id] = reportId
                    it[subjectMemberId] = adminId
                    it[status] = TravelExpenseReportStatus.DRAFT
                    it[purpose] = "Backup-Test"
                    it[travelFrom] = LocalDate(2027, 1, 5)
                    it[travelTo] = LocalDate(2027, 1, 5)
                    it[totalAmount] = BigDecimal("0.00")
                    it[createdAt] = LocalDateTime(2027, 1, 1, 9, 0)
                    it[requestedBy] = adminId
                    it[advanceAmount] = BigDecimal("0.00")
                    it[refundAccountChangedAfterSubmit] = false
                    it[paidOutsideLapis] = false
                    it[sepaReexportCount] = 0
                }
                TravelExpenseAttachmentTable.insert {
                    it[id] = attachmentId
                    it[TravelExpenseAttachmentTable.reportId] = reportId
                    it[TravelExpenseAttachmentTable.storageKey] = storageKey
                    it[originalFilename] = "Einladung.pdf"
                    it[mimeType] = "application/pdf"
                    it[sizeBytes] = bytes.size.toLong()
                    it[sha256] = "0".repeat(64)
                    it[uploadedBy] = adminId
                    it[uploadedAt] = LocalDateTime(2027, 1, 2, 9, 0)
                }
            }
            sourceRoot.resolve(storageKey).apply { parentFile.mkdirs() }.writeBytes(bytes)

            val bundle = File.createTempFile("te-att-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                val restored =
                    transaction(targetDb) {
                        TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq attachmentId }.single()
                    }
                restored[TravelExpenseAttachmentTable.storageKey] shouldBe storageKey
                restored[TravelExpenseAttachmentTable.originalFilename] shouldBe "Einladung.pdf"
                val restoredFile = targetRoot.resolve(storageKey)
                restoredFile.exists() shouldBe true
                restoredFile.readBytes() shouldBe bytes
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
