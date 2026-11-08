package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.PartgAccountMappingTable
import network.lapis.cloud.server.db.generated.PartgReportUnitModeTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.RegionalChapterTreasurerTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.289 -- the CHECKs, UNIQUE rules and FOREIGN KEYs of `V144__partg_report_r4.sql` on H2 AND PostgreSQL with the same assertions: no cascade, every
 * CHECK at its boundary, the unique indexes, the new value source `LEDGER`, and the audit CHECK list = the enum. Every refused statement runs in its
 * OWN transaction (on PostgreSQL a failed statement aborts the whole transaction).
 */
abstract class PartgReportR4MigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val now = LocalDateTime(2026, 1, 1, 0, 0)

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun refused(block: () -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun columnsOf(table: String): Set<String> =
            transaction {
                val found = mutableListOf<String>()
                exec("SELECT lower(column_name) FROM information_schema.columns WHERE lower(table_name) = '$table'") { rs ->
                    while (rs.next()) found += rs.getString(1)
                }
                found.toSet()
            }

        test("the new columns and tables exist; existing journal entries stay federal (NULL), no backfill") {
            ("regional_chapter_id" in columnsOf("journal_entry")) shouldBe true
            columnsOf("regional_chapter_treasurer") shouldBe
                setOf("id", "member_id", "regional_chapter_id", "granted_at", "granted_by_member_id", "revoked_at", "active_key")
            columnsOf("partg_account_mapping") shouldBe
                setOf("id", "ledger_account_id", "position_code", "excluded", "updated_at", "updated_by")
            columnsOf("partg_report_unit_mode") shouldBe
                setOf("id", "fiscal_year", "unit_key", "regional_chapter_id", "bookkeeping_mode", "updated_at", "updated_by")
            val member = fx.member()
            transaction {
                JournalEntryTable.insert {
                    it[id] = Uuid.random()
                    it[entryDate] = LocalDate(2026, 1, 2)
                    it[description] = "legacy"
                    it[createdBy] = member
                    it[status] = JournalEntryStatus.DRAFT
                    it[postedAt] = null
                    it[createdAt] = now
                }
            }
            transaction {
                JournalEntryTable.selectAll().where { JournalEntryTable.createdBy eq member }.single()[JournalEntryTable.regionalChapterId]
            } shouldBe null
        }

        test("journal_entry.regional_chapter_id is a foreign key without cascade") {
            val member = fx.member()
            val chapter = fx.chapter()
            transaction {
                JournalEntryTable.insert {
                    it[id] = Uuid.random()
                    it[entryDate] = LocalDate(2026, 1, 2)
                    it[description] = "chapter"
                    it[createdBy] = member
                    it[status] = JournalEntryStatus.DRAFT
                    it[postedAt] = null
                    it[createdAt] = now
                    it[regionalChapterId] = chapter
                }
            }
            refused {
                JournalEntryTable.insert {
                    it[id] = Uuid.random()
                    it[entryDate] = LocalDate(2026, 1, 2)
                    it[description] = "ghost"
                    it[createdBy] = member
                    it[status] = JournalEntryStatus.DRAFT
                    it[postedAt] = null
                    it[createdAt] = now
                    it[regionalChapterId] = Uuid.random()
                }
            } shouldBe true
            // the referenced chapter cannot be deleted (no ON DELETE CASCADE)
            refused { RegionalChapterTable.deleteWhere { RegionalChapterTable.id eq chapter } } shouldBe true
        }

        fun grant(
            member: Uuid,
            chapter: Uuid,
            revoked: Boolean,
            key: String?,
        ) = transaction {
            RegionalChapterTreasurerTable.insert {
                it[id] = Uuid.random()
                it[memberId] = member
                it[regionalChapterId] = chapter
                it[grantedAt] = now
                it[grantedByMemberId] = null
                it[revokedAt] = if (revoked) now else null
                it[activeKey] = key
            }
        }

        test(
            "the treasurer grant: the active key must equal member:chapter while active and NULL once revoked; one active grant per pair",
        ) {
            val member = fx.member()
            val chapter = fx.chapter()
            val other = fx.chapter()
            grant(member, chapter, revoked = false, key = "$member:$chapter")
            refused { grant(member, chapter, revoked = false, key = "$member:$chapter") } shouldBe true // the same pair twice
            refused { grant(member, other, revoked = false, key = null) } shouldBe true
            refused { grant(member, other, revoked = false, key = "$member:$chapter") } shouldBe true // a wrong key
            refused { grant(member, other, revoked = true, key = "$member:$other") } shouldBe true
            grant(member, other, revoked = false, key = "$member:$other") // several grants for one member
            grant(member, chapter, revoked = true, key = null) // history rows are unlimited
            grant(member, chapter, revoked = true, key = null)
        }

        test("the mapping: a position XOR excluded, one mapping per account, a real position code") {
            val member = fx.member()
            val a = fx.ledgerAccount(type = LedgerAccountType.INCOME)
            val b = fx.ledgerAccount(type = LedgerAccountType.INCOME)
            val c = fx.ledgerAccount(type = LedgerAccountType.INCOME)

            fun mapping(
                account: Uuid,
                code: String?,
                excluded: Boolean,
            ) = transaction {
                PartgAccountMappingTable.insert {
                    it[id] = Uuid.random()
                    it[ledgerAccountId] = account
                    it[positionCode] = code
                    it[PartgAccountMappingTable.excluded] = excluded
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
            }
            mapping(a, "E01", false)
            refused { mapping(a, "E02", false) } shouldBe true // one mapping per account
            refused { mapping(b, null, false) } shouldBe true
            refused { mapping(b, "E01", true) } shouldBe true
            refused { mapping(b, "NOPE", false) } shouldBe true // no such position
            mapping(b, null, true)
            mapping(c, "E02", false)
        }

        test(
            "the unit mode: year 2000..2100, mode A/B/C, unit key consistent with the chapter, federal or state only, one row per year and unit",
        ) {
            val member = fx.member()
            val chapter = fx.chapter()

            fun mode(
                year: Int,
                unit: String,
                chapterId: Uuid?,
                value: String = "A",
            ) = transaction {
                // raw SQL for the literal 'D' (the Kotlin enum has no such value)
                val chapterSql = chapterId?.let { "'$it'" } ?: "NULL"
                exec(
                    "INSERT INTO partg_report_unit_mode " +
                        "(id, fiscal_year, unit_key, regional_chapter_id, bookkeeping_mode, updated_at, updated_by) " +
                        "VALUES ('${Uuid.random()}', $year, '$unit', $chapterSql, '$value', TIMESTAMP '2026-01-01 00:00:00', '$member')",
                )
            }
            mode(2040, "FEDERAL", null)
            mode(2041, "STATE:$chapter", chapter, "C")
            refused { mode(2040, "FEDERAL", null) } shouldBe true // unique
            refused { mode(1999, "FEDERAL", null) } shouldBe true
            refused { mode(2101, "FEDERAL", null) } shouldBe true
            mode(2000, "FEDERAL", null, "B")
            mode(2100, "FEDERAL", null, "B")
            refused { mode(2042, "FEDERAL", null, "D") } shouldBe true
            refused { mode(2042, "FEDERAL", chapter) } shouldBe true // federal with a chapter
            refused { mode(2042, "STATE:$chapter", null) } shouldBe true // state without a chapter
            refused { mode(2042, "STATE:${Uuid.random()}", chapter) } shouldBe true // key and chapter disagree
            refused { mode(2042, "SUB:$chapter", chapter) } shouldBe true // a summary of sub-units is never stored
            transaction { PartgReportUnitModeTable.selectAll().where { PartgReportUnitModeTable.updatedBy eq member }.count() } shouldBe 4
            PartgBookkeepingMode.entries.map { it.name } shouldBe listOf("A", "B", "C")
        }

        test("the value source accepts LEDGER and refuses anything else") {
            val member = fx.member()
            val sheet = Uuid.random()
            transaction {
                exec(
                    "INSERT INTO partg_report_sheet " +
                        "(id, fiscal_year, unit_level, regional_chapter_id, unit_key, revision, updated_at, updated_by) " +
                        "VALUES ('$sheet', 2043, 'FEDERAL', NULL, 'FEDERAL', 0, TIMESTAMP '2026-01-01 00:00:00', '$member')",
                )
            }

            fun value(
                code: String,
                source: String,
            ) = transaction {
                exec(
                    "INSERT INTO partg_report_value (id, sheet_id, position_code, amount_cents, value_source, updated_at, updated_by) " +
                        "VALUES ('${Uuid.random()}', '$sheet', '$code', 1, '$source', TIMESTAMP '2026-01-01 00:00:00', '$member')",
                )
            }
            value("E01", "LEDGER")
            value("E02", "MANUAL")
            value("E03", "IMPORT")
            refused { value("E04", "XLEDGE") } shouldBe true
        }

        test("the audit CHECK list is exactly the enum, and the three new entity types can be written") {
            val clause =
                transaction {
                    var text = ""
                    exec(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE lower(constraint_name) = 'chk_audit_log_entry_entity_type'",
                    ) { rs ->
                        while (rs.next()) text += rs.getString(1)
                    }
                    text
                }
            val inCheck = Regex("""'([A-Z_]+)'""").findAll(clause).map { it.groupValues[1] }.toSet()
            inCheck shouldBe AuditEntityType.entries.map { it.name }.toSet()
            val member = fx.member(label = "auditor", role = AccountRole.ADMIN)
            listOf(
                AuditEntityType.PARTG_ACCOUNT_MAPPING,
                AuditEntityType.PARTG_UNIT_MODE,
                AuditEntityType.REGIONAL_CHAPTER_TREASURER,
            ).forEach { type ->
                transaction {
                    AuditLogRecorder.record(
                        actorMemberId = member,
                        actorRole = AccountRole.ADMIN,
                        entityType = type,
                        entityId = Uuid.random(),
                        action = AuditAction.CREATE,
                        before = null,
                        after = "{}",
                        occurredAt = now,
                    )
                }
            }
        }
    })
