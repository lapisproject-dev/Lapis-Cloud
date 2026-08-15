package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.BackupOperationLogTable
import network.lapis.cloud.server.db.generated.CostCenterTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.BackupOperationType
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * The core round-trip proof: export a representative, multi-domain, multi-column-type dataset from
 * a freshly migrated "source" instance, restore it into a second, independently fresh "target"
 * instance, and assert every table's rows -- not just the ones this test happens to seed -- are
 * identical between source and target. Also proves the two documented escape hatches:
 * [organization_settings]/[audit_log_chain_state]'s Flyway-seeded singleton rows are correctly
 * upserted (overwritten, not duplicated), and re-running the identical restore a second time with
 * `allowNonEmptyTarget = true` is idempotent.
 */
class OrganizationBackupRoundTripTest :
    FunSpec({
        test("export from a seeded source instance and restore into a fresh target instance round-trips every table") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-roundtrip-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-roundtrip-target-${Uuid.random()}")
            val sourceStorageRoot = Files.createTempDirectory("roundtrip-source-storage").toFile()
            val targetStorageRoot = Files.createTempDirectory("roundtrip-target-storage").toFile()

            val adminId = Uuid.random()
            val memberTierId = Uuid.random()
            val documentId = Uuid.random()
            val versionId = Uuid.random()
            val blobBytes = "hello secession, this is a document blob".toByteArray(Charsets.UTF_8)
            val storageKey = "$documentId/$versionId.bin"

            transaction(sourceDb) {
                MembershipTierTable.insert {
                    it[id] = memberTierId
                    it[name] = "Roundtrip-Testbeitrag"
                    it[description] = "Nur fuer OrganizationBackupRoundTripTest."
                    it[contributionAmount] = BigDecimal("12.34")
                    it[billingInterval] = BillingInterval.MONTHLY
                    it[active] = true
                }
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Roundtrip Admin"
                    it[email] = "roundtrip-admin@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = memberTierId
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-round-trip-verification-only"
                    it[role] = AccountRole.ADMIN
                }

                val folderId = Uuid.random()
                DocumentFolderTable.insert {
                    it[id] = folderId
                    it[name] = "Roundtrip-Ordner"
                    it[parentFolderId] = null
                }
                DocumentTable.insert {
                    it[id] = documentId
                    it[DocumentTable.folderId] = folderId
                    it[title] = "Roundtrip-Dokument"
                    it[currentVersionId] = null
                    it[createdBy] = adminId
                    it[createdAt] = LocalDateTime(2027, 1, 2, 9, 0)
                    it[accessLevel] = DocumentAccessLevel.ADMIN_ONLY
                    it[isDeleted] = false
                }
                val targetFile = sourceStorageRoot.resolve(storageKey)
                targetFile.parentFile.mkdirs()
                targetFile.writeBytes(blobBytes)
                DocumentVersionTable.insert {
                    it[id] = versionId
                    it[DocumentVersionTable.documentId] = documentId
                    it[versionNumber] = 1
                    it[fileName] = "roundtrip.txt"
                    it[mimeType] = "text/plain"
                    it[fileSizeBytes] = blobBytes.size.toLong()
                    it[DocumentVersionTable.storageKey] = storageKey
                    it[checksumSha256] = MessageDigest.getInstance("SHA-256").digest(blobBytes).toHexLower()
                    it[uploadedBy] = adminId
                    it[uploadedAt] = LocalDateTime(2027, 1, 2, 9, 5)
                    it[changeNote] = "Erste Version"
                }
                DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }

                val ledgerAccountId = Uuid.random()
                LedgerAccountTable.insert {
                    it[id] = ledgerAccountId
                    it[accountNumber] = "40999"
                    it[name] = "Roundtrip-Konto"
                    it[accountClass] = 4
                    it[type] = LedgerAccountType.INCOME
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
                val costCenterId = Uuid.random()
                CostCenterTable.insert {
                    it[id] = costCenterId
                    it[code] = "RT-1"
                    it[name] = "Roundtrip-Kostenstelle"
                    it[description] = null
                    it[active] = true
                }
                val journalEntryId = Uuid.random()
                JournalEntryTable.insert {
                    it[id] = journalEntryId
                    it[entryDate] = LocalDate(2027, 1, 3)
                    it[description] = "Roundtrip-Buchung"
                    it[voucherReference] = "RT-BELEG-1"
                    it[createdBy] = adminId
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(2027, 1, 3, 10, 0)
                    it[createdAt] = LocalDateTime(2027, 1, 3, 10, 0)
                    it[donorMemberId] = null
                    it[donorCategory] = null
                    it[externalDonorId] = null
                }
                PostingTable.insert {
                    it[id] = Uuid.random()
                    it[side] = PostingSide.CREDIT
                    it[amount] = BigDecimal("99.99")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[PostingTable.journalEntryId] = journalEntryId
                    it[PostingTable.ledgerAccountId] = ledgerAccountId
                    it[PostingTable.costCenterId] = costCenterId
                }

                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = adminId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = BigDecimal("1000.50")
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Roundtrip-Testdaten"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2027, 1, 4, 8, 0)
                }

                // Mutates the Flyway-seeded organization_settings singleton row away from its
                // default -- proves restore's upsert path overwrites the seed row rather than
                // either duplicating it or leaving the target's default in place.
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq Uuid.parse("00000000-0000-0000-0000-0000000000f2") }) {
                    it[name] = "Roundtrip-Verein e.V."
                    it[city] = "Braunschweig"
                    it[isPoliticalParty] = true
                }

                AuditLogRecorder.record(
                    actorMemberId = adminId,
                    actorRole = AccountRole.ADMIN,
                    entityType = AuditEntityType.JOURNAL_ENTRY,
                    entityId = journalEntryId,
                    action = AuditAction.POST,
                    before = null,
                    after = """{"roundtrip":true}""",
                    occurredAt = LocalDateTime(2027, 1, 3, 10, 0),
                )
            }

            val bundleFile = File.createTempFile("roundtrip-bundle-", ".zip")
            try {
                bundleFile.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceStorageRoot)
                        .streamExport(actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN), sink = out)
                }

                val restoreResult =
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorageRoot)
                        .restore(
                            actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN),
                            bundleFile = bundleFile,
                            allowNonEmptyTarget = false,
                        )

                val liveTables = transaction(sourceDb) { OrganizationSchemaCatalog.exportableTables(this) }
                restoreResult.tablesRestored.map { it.tableName }.toSet() shouldBe liveTables.map { it.tableName }.toSet()
                restoreResult.warnings shouldBe emptyList()

                // Every table's row multiset matches exactly, table by table, including tables
                // this test never explicitly seeded (must be empty on both sides).
                // backup_operation_log is deliberately excluded from this generic comparison: it is
                // the one table that legitimately diverges between source and target by design (the
                // target additionally holds a fresh RESTORE row this very operation just wrote) --
                // checked with its own precise assertions below instead.
                liveTables.filter { it.tableName != "backup_operation_log" }.forEach { table ->
                    val sourceRows = dumpTableAsJsonLines(database = sourceDb, table = table)
                    val targetRows = dumpTableAsJsonLines(database = targetDb, table = table)
                    withClueLocal(clue = table.tableName) {
                        targetRows shouldBe sourceRows
                    }
                }

                // The blob file itself round-tripped byte-for-byte.
                val restoredBlob = targetStorageRoot.resolve(storageKey)
                (restoredBlob.exists()) shouldBe true
                restoredBlob.readBytes() shouldBe blobBytes

                // Exactly one SUCCEEDED EXPORT row in source. It was written AFTER the table-by-
                // table streaming already read backup_operation_log (see OrganizationExportService
                // KDoc's manifest-ordering note) -- by construction, an export's own logging row
                // can never appear inside its own bundle. So the bundle's backup_operation_log data
                // is empty, and the target -- fresh before this restore -- ends up with exactly one
                // row: this restore's own freshly written SUCCEEDED RESTORE row.
                val sourceOps = transaction(sourceDb) { BackupOperationLogTable.selectAll().toList() }
                sourceOps.size shouldBe 1
                sourceOps.single()[BackupOperationLogTable.operationType] shouldBe BackupOperationType.EXPORT
                sourceOps.single()[BackupOperationLogTable.status] shouldBe BackupOperationStatus.SUCCEEDED

                val targetOps = transaction(targetDb) { BackupOperationLogTable.selectAll().toList() }
                targetOps.size shouldBe 1
                val targetRestoreRows = targetOps.filter { it[BackupOperationLogTable.operationType] == BackupOperationType.RESTORE }
                targetRestoreRows.size shouldBe 1
                targetRestoreRows.single()[BackupOperationLogTable.status] shouldBe BackupOperationStatus.SUCCEEDED
                targetRestoreRows.single()[BackupOperationLogTable.tableCount] shouldBe liveTables.size
                targetRestoreRows.single()[BackupOperationLogTable.blobCount] shouldBe 1

                // Idempotency: re-running the identical restore against the now-populated target
                // requires allowNonEmptyTarget=true, succeeds, and does not duplicate rows.
                val secondRestore =
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorageRoot)
                        .restore(
                            actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN),
                            bundleFile = bundleFile,
                            allowNonEmptyTarget = true,
                        )
                secondRestore.tablesRestored.map { it.tableName }.toSet() shouldBe liveTables.map { it.tableName }.toSet()

                liveTables.forEach { table ->
                    if (table.tableName != "backup_operation_log") {
                        val sourceRows = dumpTableAsJsonLines(database = sourceDb, table = table)
                        val targetRows = dumpTableAsJsonLines(database = targetDb, table = table)
                        withClueLocal(clue = "second restore, ${table.tableName}") {
                            targetRows shouldBe sourceRows
                        }
                    }
                }
                // backup_operation_log itself is the one table that legitimately keeps growing: the
                // static bundleFile's backup_operation_log data is still empty (captured once, at
                // the original export time), so re-restoring it is a no-op for that table's data --
                // only this second run's own freshly appended RESTORE row adds a row, taking the
                // total from 1 (first restore's own row) to 2, never duplicated beyond that.
                val targetOpsAfterSecond = transaction(targetDb) { BackupOperationLogTable.selectAll().count() }
                targetOpsAfterSecond shouldBe 2L
            } finally {
                bundleFile.delete()
            }
        }

        test(
            "restoring without allowNonEmptyTarget into a target that already holds the source's EXACT data fails without allowNonEmptyTarget=true",
        ) {
            // Regression guard for the NonEmptyTargetException pre-flight check itself -- see
            // OrganizationBackupNonEmptyTargetGuardTest for the full dedicated coverage.
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-roundtrip-guard-source-${Uuid.random()}")
            val storageRoot = Files.createTempDirectory("roundtrip-guard-storage").toFile()
            val adminId = Uuid.random()
            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Guard Admin"
                    it[email] = "guard-admin@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[role] = AccountRole.ADMIN
                }
            }
            val bundleFile = File.createTempFile("roundtrip-guard-bundle-", ".zip")
            try {
                bundleFile.outputStream().use { out ->
                    OrganizationExportService(
                        database = sourceDb,
                        documentStorageRoot = storageRoot,
                    ).streamExport(actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN), sink = out)
                }
                // Restoring the bundle into the SAME source database it came from is, by definition,
                // a non-empty target -- must be rejected without allowNonEmptyTarget=true.
                try {
                    OrganizationRestoreService(
                        database = sourceDb,
                        documentStorageRoot = storageRoot,
                    ).restore(actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN), bundleFile = bundleFile)
                    throw AssertionError("Expected NonEmptyTargetException")
                } catch (e: NonEmptyTargetException) {
                    (e.message?.contains("member") == true) shouldBe true
                }
            } finally {
                bundleFile.delete()
            }
        }
    })

/** Dumps every row of [table] from [database] as a sorted list of stable JSON strings -- order-independent, value-exact row comparison. */
private fun dumpTableAsJsonLines(
    database: Database,
    table: OrganizationSchemaCatalog.TableMetadata,
): List<String> =
    transaction(database) {
        val connection = rawConnection()
        val columns = table.columns.joinToString(", ") { "\"${it.name}\"" }
        val orderBy = table.primaryKeyColumns.joinToString(", ") { "\"$it\"" }
        val rows = mutableListOf<String>()
        connection.prepareStatement("SELECT $columns FROM \"${table.tableName}\" ORDER BY $orderBy").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val json = JdbcRowCodec.rowToJson(rs = rs, columns = table.columns)
                    rows += Json.encodeToString(JsonObject.serializer(), json)
                }
            }
        }
        rows
    }

/** Small local stand-in for Kotest's `withClue`, mirrors `PostalMailSchemaDriftTest`'s own. */
private inline fun <T> withClueLocal(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
