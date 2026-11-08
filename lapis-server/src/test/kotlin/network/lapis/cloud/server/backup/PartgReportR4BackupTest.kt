package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgAccountMappingTable
import network.lapis.cloud.server.db.generated.PartgReportUnitModeTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.RegionalChapterTreasurerTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- the new tables and the new journal column are found by the schema catalog on their own and travel in the whole-organization
 * backup. Round trip of a booking in a chapter, a mapping, an exclusion, a mode and a treasurer grant; the restore order follows the foreign keys
 * (chapter -> journal entry, ledger account and report position -> mapping).
 */
class PartgReportR4BackupTest :
    FunSpec({
        test("a booking with a chapter, the mapping, the mode and the treasurer grant round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("partg-r4-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("partg-r4-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("partg-r4-source").toFile()
            val targetRoot = Files.createTempDirectory("partg-r4-target").toFile()
            val adminId = Uuid.random()
            val chapterId = Uuid.random()
            val incomeAccount = Uuid.random()
            val equityAccount = Uuid.random()
            val entryId = Uuid.random()
            val mappingId = Uuid.random()
            val excludedId = Uuid.random()
            val modeId = Uuid.random()
            val grantId = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Rechenschaft Backup R4"
                    it[email] = "partg-r4-backup@example.org"
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
                RegionalChapterTable.insert {
                    it[id] = chapterId
                    it[name] = "Backup Land"
                    it[nameKey] = "backup land"
                    it[createdAt] = at
                }

                fun account(
                    id: Uuid,
                    number: String,
                    kind: LedgerAccountType,
                ) = LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Konto $number"
                    it[accountClass] = number.first().digitToInt()
                    it[type] = kind
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
                account(incomeAccount, "49001", LedgerAccountType.INCOME)
                account(equityAccount, "29001", LedgerAccountType.EQUITY)
                JournalEntryTable.insert {
                    it[id] = entryId
                    it[entryDate] = LocalDate(2027, 1, 3)
                    it[description] = "Buchung in der Gliederung"
                    it[createdBy] = adminId
                    it[status] = JournalEntryStatus.DRAFT
                    it[postedAt] = null
                    it[createdAt] = at
                    it[regionalChapterId] = chapterId
                }
                PartgAccountMappingTable.insert {
                    it[id] = mappingId
                    it[ledgerAccountId] = incomeAccount
                    it[positionCode] = "E01"
                    it[excluded] = false
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgAccountMappingTable.insert {
                    it[id] = excludedId
                    it[ledgerAccountId] = equityAccount
                    it[positionCode] = null
                    it[excluded] = true
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                PartgReportUnitModeTable.insert {
                    it[id] = modeId
                    it[fiscalYear] = 2026
                    it[unitKey] = "STATE:$chapterId"
                    it[regionalChapterId] = chapterId
                    it[bookkeepingMode] = PartgBookkeepingMode.B
                    it[updatedAt] = at
                    it[updatedBy] = adminId
                }
                RegionalChapterTreasurerTable.insert {
                    it[id] = grantId
                    it[memberId] = adminId
                    it[regionalChapterId] = chapterId
                    it[grantedAt] = at
                    it[grantedByMemberId] = adminId
                    it[revokedAt] = null
                    it[activeKey] = "$adminId:$chapterId"
                }
            }

            val bundle = File.createTempFile("partg-r4-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    JournalEntryTable
                        .selectAll()
                        .where {
                            JournalEntryTable.id eq entryId
                        }.single()[JournalEntryTable.regionalChapterId] shouldBe
                        chapterId
                    val mapping = PartgAccountMappingTable.selectAll().where { PartgAccountMappingTable.id eq mappingId }.single()
                    mapping[PartgAccountMappingTable.positionCode] shouldBe "E01"
                    mapping[PartgAccountMappingTable.ledgerAccountId] shouldBe incomeAccount
                    val excluded = PartgAccountMappingTable.selectAll().where { PartgAccountMappingTable.id eq excludedId }.single()
                    excluded[PartgAccountMappingTable.excluded] shouldBe true
                    excluded[PartgAccountMappingTable.positionCode] shouldBe null
                    val mode = PartgReportUnitModeTable.selectAll().where { PartgReportUnitModeTable.id eq modeId }.single()
                    mode[PartgReportUnitModeTable.bookkeepingMode] shouldBe PartgBookkeepingMode.B
                    mode[PartgReportUnitModeTable.unitKey] shouldBe "STATE:$chapterId"
                    val grant = RegionalChapterTreasurerTable.selectAll().where { RegionalChapterTreasurerTable.id eq grantId }.single()
                    grant[RegionalChapterTreasurerTable.activeKey] shouldBe "$adminId:$chapterId"
                    grant[RegionalChapterTreasurerTable.revokedAt] shouldBe null
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
