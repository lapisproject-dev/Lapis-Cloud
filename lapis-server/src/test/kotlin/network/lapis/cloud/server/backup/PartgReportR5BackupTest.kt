package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgConsolidationSheetTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationRowTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationTable
import network.lapis.cloud.server.db.generated.PartgIntraTransferTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.290 (R5) -- the four consolidation tables are found by the schema catalog on their own and travel in the whole-organization backup. Round trip
 * of a header, a transfer pair and a donor combination with its row; the restore order follows the foreign keys (chapter, donor row -> pair, row).
 */
class PartgReportR5BackupTest :
    FunSpec({
        test("a header, a pair and a donor combination round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r5-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r5-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r5-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r5-target").toFile()
            val adminId = Uuid.random()
            val chapterA = Uuid.random()
            val chapterB = Uuid.random()
            val sheetId = Uuid.random()
            val transferId = Uuid.random()
            val combinationId = Uuid.random()
            val rowId = Uuid.random()
            val annexA = Uuid.random()
            val annexB = Uuid.random()
            val donorA = Uuid.random()
            val donorB = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup R5"
                    it[email] = "partg-r5-backup@example.org"
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
                listOf(chapterA to "Backup Nord", chapterB to "Backup Sued").forEach { (chapter, label) ->
                    RegionalChapterTable.insert {
                        it[id] = chapter
                        it[name] = label
                        it[nameKey] = label.lowercase()
                        it[createdAt] = at
                    }
                }
                listOf(Triple(annexA, chapterA, donorA), Triple(annexB, chapterB, donorB)).forEach { (annex, chapter, donor) ->
                    PartgReportAnnexTable.insert {
                        it[id] = annex
                        it[fiscalYear] = 2026
                        it[unitLevel] = PartgUnitLevel.STATE
                        it[regionalChapterId] = chapter
                        it[unitKey] = "STATE:$chapter"
                        it[revision] = 1L
                        it[updatedAt] = at
                        it[updatedBy] = adminId
                    }
                    PartgReportLargeDonorTable.insert {
                        it[id] = donor
                        it[annexId] = annex
                        it[personKind] = PartgPersonKind.NATURAL
                        it[name] = "Backup Spender"
                        it[country] = "DE"
                        it[contributionKind] = PartgContributionKind.DONATION
                        it[amountCents] = 1_200_000L
                        it[createdAt] = at
                        it[createdBy] = adminId
                        it[updatedAt] = at
                        it[updatedBy] = adminId
                    }
                }
                PartgConsolidationSheetTable.insert {
                    it[id] = sheetId
                    it[fiscalYear] = 2026
                    it[revision] = 4L
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgIntraTransferTable.insert {
                    it[id] = transferId
                    it[consolidationId] = sheetId
                    it[transferKind] = PartgTransferKind.SUBSIDY
                    it[payerLevel] = PartgUnitLevel.STATE
                    it[payerChapterId] = chapterA
                    it[payerUnitKey] = "STATE:$chapterA"
                    it[payeeLevel] = PartgUnitLevel.STATE
                    it[payeeChapterId] = chapterB
                    it[payeeUnitKey] = "STATE:$chapterB"
                    it[payerAmountCents] = 100_00L
                    it[payeeAmountCents] = null
                    it[note] = "Backup-Notiz"
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgDonorCombinationTable.insert {
                    it[id] = combinationId
                    it[consolidationId] = sheetId
                    it[createdAt] = at
                    it[createdBy] = adminId
                }
                listOf(donorA, donorB).forEach { donor ->
                    PartgDonorCombinationRowTable.insert {
                        it[id] = if (donor == donorA) rowId else Uuid.random()
                        it[PartgDonorCombinationRowTable.combinationId] = combinationId
                        it[largeDonorId] = donor
                    }
                }
            }

            val bundle = File.createTempFile("partg-r5-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val sheet = PartgConsolidationSheetTable.selectAll().where { PartgConsolidationSheetTable.id eq sheetId }.single()
                    sheet[PartgConsolidationSheetTable.revision] shouldBe 4L
                    val pair = PartgIntraTransferTable.selectAll().where { PartgIntraTransferTable.id eq transferId }.single()
                    pair[PartgIntraTransferTable.payerAmountCents] shouldBe 100_00L
                    pair[PartgIntraTransferTable.payeeAmountCents] shouldBe null
                    pair[PartgIntraTransferTable.payeeUnitKey] shouldBe "STATE:$chapterB"
                    pair[PartgIntraTransferTable.note] shouldBe "Backup-Notiz"
                    PartgDonorCombinationTable.selectAll().where { PartgDonorCombinationTable.id eq combinationId }.count() shouldBe 1L
                    PartgDonorCombinationRowTable
                        .selectAll()
                        .where { PartgDonorCombinationRowTable.combinationId eq combinationId }
                        .map { it[PartgDonorCombinationRowTable.largeDonorId] }
                        .toSet() shouldBe setOf(donorA, donorB)
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
