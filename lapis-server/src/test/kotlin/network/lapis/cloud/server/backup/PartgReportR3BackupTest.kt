package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportHoldingTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PartgReportNoteTable
import network.lapis.cloud.server.db.generated.PartgReportNotesSheetTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgTriState
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.269 -- the five R3 tables are found by the schema catalog on their own, so they travel in the whole-organization backup (a deliberate
 * choice: it is the admin backup of the organisation's own books, like `external_donor`). Round trip with a NULL address, a legal person, Umlauts
 * and a Cyrillic name, a holding with a negative result and a note of 4,000 characters; NULL stays NULL. The restore order follows the foreign keys.
 */
class PartgReportR3BackupTest :
    FunSpec({
        test("annex, donors, notes and holdings round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r3-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r3-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r3-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r3-target").toFile()
            val adminId = Uuid.random()
            val annexId = Uuid.random()
            val plainDonor = Uuid.random()
            val legalDonor = Uuid.random()
            val cyrillicDonor = Uuid.random()
            val notesId = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)
            val longText = "ä".repeat(4000)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup"
                    it[email] = "partg-r3-backup@example.org"
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
                    it[deductionUnassignableCents] = 0L
                    it[deductionCashOver1000Cents] = null
                    it[deductionOver3300Cents] = 33_000L
                    it[deductionWronglyReportedCents] = null
                    it[memberCount] = 321
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }

                fun donor(
                    donorId: Uuid,
                    kind: PartgPersonKind,
                    donorName: String,
                    streetValue: String?,
                    cents: Long,
                ) = PartgReportLargeDonorTable.insert {
                    it[id] = donorId
                    it[PartgReportLargeDonorTable.annexId] = annexId
                    it[personKind] = kind
                    it[name] = donorName
                    it[street] = streetValue
                    it[postalCode] = null
                    it[city] = null
                    it[country] = "DE"
                    it[contributionKind] = PartgContributionKind.BOTH
                    it[amountCents] = cents
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                donor(plainDonor, PartgPersonKind.NATURAL, "Jörg Müßig", null, 1_000_001L)
                donor(legalDonor, PartgPersonKind.LEGAL, "Äpfel & Birnen GmbH", "Obstweg 3", 2_000_000L)
                donor(cyrillicDonor, PartgPersonKind.NATURAL, "Иван Петров", "ул. Ленина 1", 3_000_000L)
                PartgReportNotesSheetTable.insert {
                    it[id] = notesId
                    it[fiscalYear] = 2025
                    it[revision] = 2L
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgReportNoteTable.insert {
                    it[id] = Uuid.random()
                    it[notesSheetId] = notesId
                    it[blockCode] = PartgNoteBlock.RELIEF_28
                    it[included] = true
                    it[body] = longText
                    it[reliefAbs1] = PartgTriState.YES
                    it[reliefAbs3] = null
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgReportHoldingTable.insert {
                    it[id] = Uuid.random()
                    it[notesSheetId] = notesId
                    it[sortOrder] = 0
                    it[name] = "Verlag GmbH"
                    it[seat] = null
                    it[shareBasisPoints] = 2_550
                    it[capitalCents] = null
                    it[resultCents] = -1_500_00L
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
            }

            val bundle = File.createTempFile("partg-r3-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val annex = PartgReportAnnexTable.selectAll().where { PartgReportAnnexTable.id eq annexId }.single()
                    annex[PartgReportAnnexTable.revision] shouldBe 4L
                    annex[PartgReportAnnexTable.deductionUnassignableCents] shouldBe 0L
                    annex[PartgReportAnnexTable.deductionCashOver1000Cents] shouldBe null
                    annex[PartgReportAnnexTable.deductionOver3300Cents] shouldBe 33_000L
                    annex[PartgReportAnnexTable.memberCount] shouldBe 321

                    fun donorRow(id: Uuid) = PartgReportLargeDonorTable.selectAll().where { PartgReportLargeDonorTable.id eq id }.single()
                    donorRow(plainDonor)[PartgReportLargeDonorTable.street] shouldBe null
                    donorRow(plainDonor)[PartgReportLargeDonorTable.name] shouldBe "Jörg Müßig"
                    donorRow(plainDonor)[PartgReportLargeDonorTable.postalCode] shouldBe null
                    donorRow(legalDonor)[PartgReportLargeDonorTable.personKind] shouldBe PartgPersonKind.LEGAL
                    donorRow(legalDonor)[PartgReportLargeDonorTable.name] shouldBe "Äpfel & Birnen GmbH"
                    donorRow(cyrillicDonor)[PartgReportLargeDonorTable.name] shouldBe "Иван Петров"
                    donorRow(cyrillicDonor)[PartgReportLargeDonorTable.street] shouldBe "ул. Ленина 1"
                    donorRow(cyrillicDonor)[PartgReportLargeDonorTable.amountCents] shouldBe 3_000_000L
                    val note = PartgReportNoteTable.selectAll().where { PartgReportNoteTable.notesSheetId eq notesId }.single()
                    note[PartgReportNoteTable.body] shouldBe longText
                    note[PartgReportNoteTable.reliefAbs1] shouldBe PartgTriState.YES
                    note[PartgReportNoteTable.reliefAbs3] shouldBe null
                    val holding = PartgReportHoldingTable.selectAll().where { PartgReportHoldingTable.notesSheetId eq notesId }.single()
                    holding[PartgReportHoldingTable.resultCents] shouldBe -1_500_00L
                    holding[PartgReportHoldingTable.capitalCents] shouldBe null
                    holding[PartgReportHoldingTable.seat] shouldBe null
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
