package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportEnterpriseTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.287 -- `partg_report_enterprise` is found by the schema catalog on its own and travels in the whole-organization backup. Round trip with
 * Umlauts and Cyrillic, all optional fields NULL and at their boundary values, share 0 and 10000. The restore order follows the foreign keys.
 */
class PartgReportR3cBackupTest :
    FunSpec({
        test("enterprises round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r3c-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r3c-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r3c-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r3c-target").toFile()
            val adminId = Uuid.random()
            val annexId = Uuid.random()
            val allNull = Uuid.random()
            val boundary = Uuid.random()
            val zeroShare = Uuid.random()
            val cyrillic = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup R3c"
                    it[email] = "partg-r3c-backup@example.org"
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
                    it[fiscalYear] = 2025
                    it[unitLevel] = PartgUnitLevel.FEDERAL
                    it[regionalChapterId] = null
                    it[unitKey] = "FEDERAL"
                    it[revision] = 4L
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }

                fun enterprise(
                    enterpriseId: Uuid,
                    enterpriseName: String,
                    form: String?,
                    place: String?,
                    share: Int?,
                    income: Long?,
                    expenses: Long?,
                ) = PartgReportEnterpriseTable.insert {
                    it[id] = enterpriseId
                    it[PartgReportEnterpriseTable.annexId] = annexId
                    it[name] = enterpriseName
                    it[legalForm] = form
                    it[seat] = place
                    it[shareBasisPoints] = share
                    it[incomeCents] = income
                    it[expensesCents] = expenses
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                enterprise(allNull, "Nur Name", null, null, null, null, null)
                enterprise(
                    boundary,
                    "Ä".repeat(200),
                    "ö".repeat(60),
                    "ü".repeat(100),
                    10_000,
                    99_999_999_999_999L,
                    99_999_999_999_999L,
                )
                enterprise(zeroShare, "Null-Anteil GmbH", "GmbH", "Köln", 0, 0L, 0L)
                enterprise(cyrillic, "Иван Петров Верлаг", "ООО", "Тбилиси", 2_550, 5_000L, 7_000L)
            }

            val bundle = File.createTempFile("partg-r3c-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    fun row(id: Uuid) = PartgReportEnterpriseTable.selectAll().where { PartgReportEnterpriseTable.id eq id }.single()
                    row(allNull)[PartgReportEnterpriseTable.name] shouldBe "Nur Name"
                    row(allNull)[PartgReportEnterpriseTable.legalForm] shouldBe null
                    row(allNull)[PartgReportEnterpriseTable.seat] shouldBe null
                    row(allNull)[PartgReportEnterpriseTable.shareBasisPoints] shouldBe null
                    row(allNull)[PartgReportEnterpriseTable.incomeCents] shouldBe null
                    row(allNull)[PartgReportEnterpriseTable.expensesCents] shouldBe null
                    row(allNull)[PartgReportEnterpriseTable.annexId] shouldBe annexId
                    row(boundary)[PartgReportEnterpriseTable.name] shouldBe "Ä".repeat(200)
                    row(boundary)[PartgReportEnterpriseTable.legalForm] shouldBe "ö".repeat(60)
                    row(boundary)[PartgReportEnterpriseTable.seat] shouldBe "ü".repeat(100)
                    row(boundary)[PartgReportEnterpriseTable.shareBasisPoints] shouldBe 10_000
                    row(boundary)[PartgReportEnterpriseTable.incomeCents] shouldBe 99_999_999_999_999L
                    row(boundary)[PartgReportEnterpriseTable.expensesCents] shouldBe 99_999_999_999_999L
                    row(zeroShare)[PartgReportEnterpriseTable.shareBasisPoints] shouldBe 0
                    row(zeroShare)[PartgReportEnterpriseTable.incomeCents] shouldBe 0L
                    row(zeroShare)[PartgReportEnterpriseTable.expensesCents] shouldBe 0L
                    row(cyrillic)[PartgReportEnterpriseTable.name] shouldBe "Иван Петров Верлаг"
                    row(cyrillic)[PartgReportEnterpriseTable.legalForm] shouldBe "ООО"
                    row(cyrillic)[PartgReportEnterpriseTable.seat] shouldBe "Тбилиси"
                    row(cyrillic)[PartgReportEnterpriseTable.createdAt] shouldBe at
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
