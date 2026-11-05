package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportSponsorTable
import network.lapis.cloud.server.db.generated.PartgReportSponsoringEntryTable
import network.lapis.cloud.server.db.generated.PartgReportYouthOrgTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
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
 * V1.9.283 -- the three R3b tables are found by the schema catalog on their own and travel in the whole-organization backup. Round trip with
 * Umlauts and Cyrillic, a NULL address, a purpose of 500 characters, a date column, and a youth organisation with NULL figures (NULL stays NULL).
 * The restore order follows the foreign keys: annex, sponsor, entry.
 */
class PartgReportR3bBackupTest :
    FunSpec({
        test("sponsors, entries and youth organisations round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r3b-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r3b-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r3b-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r3b-target").toFile()
            val adminId = Uuid.random()
            val annexId = Uuid.random()
            val plainSponsor = Uuid.random()
            val cyrillicSponsor = Uuid.random()
            val longPurposeEntry = Uuid.random()
            val nullPurposeEntry = Uuid.random()
            val cyrillicEntry = Uuid.random()
            val youthWithFigures = Uuid.random()
            val youthWithoutFigures = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)
            val longPurpose = "ö".repeat(500)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup R3b"
                    it[email] = "partg-r3b-backup@example.org"
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

                fun sponsor(
                    sponsorId: Uuid,
                    kind: PartgPersonKind,
                    sponsorName: String,
                    streetValue: String?,
                ) = PartgReportSponsorTable.insert {
                    it[id] = sponsorId
                    it[PartgReportSponsorTable.annexId] = annexId
                    it[personKind] = kind
                    it[name] = sponsorName
                    it[street] = streetValue
                    it[postalCode] = null
                    it[city] = null
                    it[country] = "DE"
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                sponsor(plainSponsor, PartgPersonKind.LEGAL, "Äpfel & Söhne GmbH", null)
                sponsor(cyrillicSponsor, PartgPersonKind.NATURAL, "Иван Петров", "ул. Ленина 1")

                fun entry(
                    entryId: Uuid,
                    sponsorId: Uuid,
                    day: LocalDate,
                    cents: Long,
                    text: String?,
                ) = PartgReportSponsoringEntryTable.insert {
                    it[id] = entryId
                    it[PartgReportSponsoringEntryTable.sponsorId] = sponsorId
                    it[sponsoredOn] = day
                    it[amountCents] = cents
                    it[purpose] = text
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                entry(longPurposeEntry, plainSponsor, LocalDate(2025, 12, 31), 75_001L, longPurpose)
                entry(nullPurposeEntry, plainSponsor, LocalDate(2025, 1, 1), 1L, null)
                entry(cyrillicEntry, cyrillicSponsor, LocalDate(2025, 6, 15), 99_999_999_999_999L, "Праздник")

                fun youth(
                    youthId: Uuid,
                    youthName: String,
                    relation: String?,
                    income: Long?,
                    expenses: Long?,
                ) = PartgReportYouthOrgTable.insert {
                    it[id] = youthId
                    it[PartgReportYouthOrgTable.annexId] = annexId
                    it[name] = youthName
                    it[relationship] = relation
                    it[incomeCents] = income
                    it[expensesCents] = expenses
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                youth(youthWithFigures, "Junge Üniversität", "Jugendorganisation", 0L, 500L)
                youth(youthWithoutFigures, "Jugend ohne Zahlen", null, null, null)
            }

            val bundle = File.createTempFile("partg-r3b-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    fun sponsorRow(id: Uuid) = PartgReportSponsorTable.selectAll().where { PartgReportSponsorTable.id eq id }.single()
                    sponsorRow(plainSponsor)[PartgReportSponsorTable.name] shouldBe "Äpfel & Söhne GmbH"
                    sponsorRow(plainSponsor)[PartgReportSponsorTable.street] shouldBe null
                    sponsorRow(plainSponsor)[PartgReportSponsorTable.personKind] shouldBe PartgPersonKind.LEGAL
                    sponsorRow(cyrillicSponsor)[PartgReportSponsorTable.name] shouldBe "Иван Петров"
                    sponsorRow(cyrillicSponsor)[PartgReportSponsorTable.street] shouldBe "ул. Ленина 1"

                    fun entryRow(id: Uuid) =
                        PartgReportSponsoringEntryTable.selectAll().where { PartgReportSponsoringEntryTable.id eq id }.single()
                    entryRow(longPurposeEntry)[PartgReportSponsoringEntryTable.purpose] shouldBe longPurpose
                    entryRow(longPurposeEntry)[PartgReportSponsoringEntryTable.sponsoredOn] shouldBe LocalDate(2025, 12, 31)
                    entryRow(longPurposeEntry)[PartgReportSponsoringEntryTable.sponsorId] shouldBe plainSponsor
                    entryRow(nullPurposeEntry)[PartgReportSponsoringEntryTable.purpose] shouldBe null
                    entryRow(nullPurposeEntry)[PartgReportSponsoringEntryTable.sponsoredOn] shouldBe LocalDate(2025, 1, 1)
                    entryRow(cyrillicEntry)[PartgReportSponsoringEntryTable.amountCents] shouldBe 99_999_999_999_999L
                    entryRow(cyrillicEntry)[PartgReportSponsoringEntryTable.purpose] shouldBe "Праздник"

                    fun youthRow(id: Uuid) = PartgReportYouthOrgTable.selectAll().where { PartgReportYouthOrgTable.id eq id }.single()
                    youthRow(youthWithFigures)[PartgReportYouthOrgTable.incomeCents] shouldBe 0L
                    youthRow(youthWithFigures)[PartgReportYouthOrgTable.expensesCents] shouldBe 500L
                    youthRow(youthWithFigures)[PartgReportYouthOrgTable.name] shouldBe "Junge Üniversität"
                    youthRow(youthWithoutFigures)[PartgReportYouthOrgTable.incomeCents] shouldBe null
                    youthRow(youthWithoutFigures)[PartgReportYouthOrgTable.expensesCents] shouldBe null
                    youthRow(youthWithoutFigures)[PartgReportYouthOrgTable.relationship] shouldBe null
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
