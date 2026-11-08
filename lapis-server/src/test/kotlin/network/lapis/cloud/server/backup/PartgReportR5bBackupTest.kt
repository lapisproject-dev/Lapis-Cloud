package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.293 (R5b) -- the two new columns of `partg_report_large_donor` (`source_donor_key`, `source_applied_at`) travel in the whole-organization
 * backup: one row with a key and a take-over time and one without a key round-trip with every column unchanged.
 */
class PartgReportR5bBackupTest :
    FunSpec({
        test("a row linked to a booking donor and a row entered by hand round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r5b-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r5b-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r5b-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r5b-target").toFile()
            val adminId = Uuid.random()
            val annexId = Uuid.random()
            val linked = Uuid.random()
            val byHand = Uuid.random()
            val key = "M:${Uuid.random()}"
            val at = LocalDateTime(2027, 1, 2, 9, 0)
            val appliedAt = LocalDateTime(2027, 1, 3, 10, 30)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup R5b"
                    it[email] = "partg-r5b-backup@example.org"
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
                PartgReportAnnexTable.insert {
                    it[id] = annexId
                    it[fiscalYear] = 2026
                    it[unitLevel] = PartgUnitLevel.FEDERAL
                    it[regionalChapterId] = null
                    it[unitKey] = "FEDERAL"
                    it[revision] = 3L
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                listOf(linked to key, byHand to null).forEach { (donor, donorKey) ->
                    PartgReportLargeDonorTable.insert {
                        it[id] = donor
                        it[PartgReportLargeDonorTable.annexId] = annexId
                        it[personKind] = PartgPersonKind.NATURAL
                        it[name] = "Backup Spender"
                        it[country] = "DE"
                        it[contributionKind] = PartgContributionKind.DONATION
                        it[amountCents] = 1_200_000L
                        it[createdAt] = at
                        it[createdBy] = adminId
                        it[updatedAt] = if (donorKey != null) appliedAt else at
                        it[updatedBy] = adminId
                        it[sourceDonorKey] = donorKey
                        it[sourceAppliedAt] = if (donorKey != null) appliedAt else null
                    }
                }
            }

            val bundle = File.createTempFile("partg-r5b-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val a = PartgReportLargeDonorTable.selectAll().where { PartgReportLargeDonorTable.id eq linked }.single()
                    a[PartgReportLargeDonorTable.sourceDonorKey] shouldBe key
                    a[PartgReportLargeDonorTable.sourceAppliedAt] shouldBe appliedAt
                    a[PartgReportLargeDonorTable.updatedAt] shouldBe appliedAt
                    a[PartgReportLargeDonorTable.amountCents] shouldBe 1_200_000L
                    val b = PartgReportLargeDonorTable.selectAll().where { PartgReportLargeDonorTable.id eq byHand }.single()
                    b[PartgReportLargeDonorTable.sourceDonorKey] shouldBe null
                    b[PartgReportLargeDonorTable.sourceAppliedAt] shouldBe null
                    b[PartgReportLargeDonorTable.updatedAt] shouldBe at
                    PartgReportLargeDonorTable.selectAll().where { PartgReportLargeDonorTable.annexId eq annexId }.count() shouldBe 2L
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
