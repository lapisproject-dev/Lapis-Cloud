package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.199 -- `travel_expense_document` is a blob table of the organization backup: the metadata row (with its nullable uploader link) AND the
 * file bytes travel in the bundle (`blobs/<storage key>`) and come back byte for byte.
 */
class TravelExpenseDocumentBackupTest :
    FunSpec({
        test("a document row (also one without an uploader) and its file round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("te-doc-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("te-doc-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("te-doc-source").toFile()
            val targetRoot = Files.createTempDirectory("te-doc-target").toFile()
            val adminId = Uuid.random()
            val withUploader = Uuid.random()
            val withoutUploader = Uuid.random()
            val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x01, 0x02, 0x03)

            fun key(id: Uuid) = "travel-expenses/documents/$id.bin"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Dokument Backup Admin"
                    it[email] = "te-doc-backup@example.org"
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
                listOf(withUploader to adminId, withoutUploader to null).forEach { (documentId, uploader) ->
                    TravelExpenseDocumentTable.insert {
                        it[id] = documentId
                        it[title] = "Reisekostenordnung"
                        it[storageKey] = key(documentId)
                        it[originalFilename] = "ordnung.pdf"
                        it[mimeType] = "application/pdf"
                        it[sizeBytes] = bytes.size.toLong()
                        it[sha256] = "0".repeat(64)
                        it[uploadedBy] = uploader
                        it[uploadedAt] = LocalDateTime(2027, 1, 2, 9, 0)
                    }
                    sourceRoot.resolve(key(documentId)).apply { parentFile.mkdirs() }.writeBytes(bytes)
                }
            }

            val bundle = File.createTempFile("te-doc-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                listOf(withUploader to adminId, withoutUploader to null).forEach { (documentId, uploader) ->
                    val restored =
                        transaction(targetDb) {
                            TravelExpenseDocumentTable.selectAll().where { TravelExpenseDocumentTable.id eq documentId }.single()
                        }
                    restored[TravelExpenseDocumentTable.storageKey] shouldBe key(documentId)
                    restored[TravelExpenseDocumentTable.uploadedBy] shouldBe uploader
                    restored[TravelExpenseDocumentTable.title] shouldBe "Reisekostenordnung"
                    targetRoot.resolve(key(documentId)).readBytes() shouldBe bytes
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
