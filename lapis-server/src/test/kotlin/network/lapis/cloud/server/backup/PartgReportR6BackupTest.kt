package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgReportDeadlineTable
import network.lapis.cloud.server.db.generated.PartgReportDeclarationTable
import network.lapis.cloud.server.db.generated.PartgReportVersionCounterTable
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
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
 * V1.9.291 (R6) -- the four version tables are found by the schema catalog on their own and travel in the whole-organization backup. Round trip of a
 * counter, a finalised version with its declaration and a deadline; the restore order follows the foreign keys (member, counter, version, declaration).
 */
class PartgReportR6BackupTest :
    FunSpec({
        test("a counter, a finalised version with its declaration and a deadline round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r6-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r6-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r6-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r6-target").toFile()
            val adminId = Uuid.random()
            val counterId = Uuid.random()
            val versionId = Uuid.random()
            val declarationId = Uuid.random()
            val deadlineId = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)
            val hash = "ab".repeat(32)
            val json = "{\"backup\":\"Rechenschaft \\u00fcber alles\",\"n\":1}"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup R6"
                    it[email] = "partg-r6-backup@example.org"
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
                PartgReportVersionCounterTable.insert {
                    it[id] = counterId
                    it[fiscalYear] = 2026
                    it[scopeKey] = "ALL"
                    it[lastNumber] = 3
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgReportVersionTable.insert {
                    it[id] = versionId
                    it[PartgReportVersionTable.counterId] = counterId
                    it[fiscalYear] = 2026
                    it[scopeKey] = "ALL"
                    it[versionNumber] = 3
                    it[formatVersion] = 1
                    it[contentJson] = json
                    it[contentHash] = hash
                    it[byteSize] = json.length
                    it[hasThirdPartyData] = true
                    it[comment] = "Backup-Kommentar"
                    it[frozenAt] = at
                    it[frozenBy] = adminId
                    it[finalizedAt] = at
                    it[finalizedBy] = adminId
                }
                PartgReportDeclarationTable.insert {
                    it[id] = declarationId
                    it[PartgReportDeclarationTable.versionId] = versionId
                    it[templateCode] = "ENTWURF-V1"
                    it[contentHash] = hash
                    it[declaredAt] = at
                    it[declaredBy] = adminId
                }
                PartgReportDeadlineTable.insert {
                    it[id] = deadlineId
                    it[fiscalYear] = 2026
                    it[deadline] = LocalDate(2027, 12, 15)
                    it[confirmedAt] = at
                    it[confirmedBy] = adminId
                }
            }

            val bundle = File.createTempFile("partg-r6-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val counter =
                        PartgReportVersionCounterTable
                            .selectAll()
                            .where { PartgReportVersionCounterTable.id eq counterId }
                            .single()
                    counter[PartgReportVersionCounterTable.lastNumber] shouldBe 3
                    val version = PartgReportVersionTable.selectAll().where { PartgReportVersionTable.id eq versionId }.single()
                    version[PartgReportVersionTable.contentJson] shouldBe json
                    version[PartgReportVersionTable.contentHash] shouldBe hash
                    version[PartgReportVersionTable.comment] shouldBe "Backup-Kommentar"
                    version[PartgReportVersionTable.hasThirdPartyData] shouldBe true
                    version[PartgReportVersionTable.finalizedBy] shouldBe adminId
                    PartgReportDeclarationTable
                        .selectAll()
                        .where { PartgReportDeclarationTable.id eq declarationId }
                        .single()[PartgReportDeclarationTable.versionId] shouldBe versionId
                    PartgReportDeadlineTable
                        .selectAll()
                        .where { PartgReportDeadlineTable.id eq deadlineId }
                        .single()[PartgReportDeadlineTable.deadline] shouldBe LocalDate(2027, 12, 15)
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
