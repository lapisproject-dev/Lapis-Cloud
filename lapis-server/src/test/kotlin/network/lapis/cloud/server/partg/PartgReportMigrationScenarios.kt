package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueSource
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- the CHECKs, UNIQUE and FOREIGN KEY rules of `V121__partg_report.sql`, on H2 AND PostgreSQL with the same assertions. Every refused
 * statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the whole transaction).
 */
abstract class PartgReportMigrationScenarios(
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

        fun insertSheet(
            year: Int,
            level: PartgUnitLevel,
            chapter: Uuid?,
            key: String,
            member: Uuid,
        ) = transaction {
            val id = Uuid.random()
            PartgReportSheetTable.insert {
                it[PartgReportSheetTable.id] = id
                it[fiscalYear] = year
                it[unitLevel] = level
                it[regionalChapterId] = chapter
                it[unitKey] = key
                it[revision] = 0L
                it[updatedAt] = now
                it[updatedBy] = member
            }
            id
        }

        fun insertValue(
            sheet: Uuid,
            code: String,
            cents: Long,
            member: Uuid,
        ) = transaction {
            PartgReportValueTable.insert {
                it[id] = Uuid.random()
                it[sheetId] = sheet
                it[positionCode] = code
                it[amountCents] = cents
                it[valueSource] = PartgValueSource.MANUAL
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        test(
            "a valid federal, state and subordinate sheet is accepted; the UUID text in the unit key matches the database cast (lower case)",
        ) {
            val member = fx.member()
            val chapter = fx.chapter()
            insertSheet(2020, PartgUnitLevel.FEDERAL, null, "FEDERAL", member)
            insertSheet(2020, PartgUnitLevel.STATE, chapter, PartgUnit.State(chapter.toString()).key(), member)
            insertSheet(2020, PartgUnitLevel.SUBORDINATE, chapter, PartgUnit.Subordinate(chapter.toString()).key(), member)
        }

        test("the unit key must agree with level and chapter") {
            val member = fx.member()
            val chapter = fx.chapter()
            val other = fx.chapter()
            refused { insertSheet(2021, PartgUnitLevel.FEDERAL, chapter, "FEDERAL", member) } shouldBe true
            refused { insertSheet(2021, PartgUnitLevel.FEDERAL, null, "STATE:$chapter", member) } shouldBe true
            refused { insertSheet(2021, PartgUnitLevel.STATE, null, "STATE:$chapter", member) } shouldBe true
            refused { insertSheet(2021, PartgUnitLevel.STATE, chapter, "STATE:$other", member) } shouldBe true
            refused { insertSheet(2021, PartgUnitLevel.STATE, chapter, "SUB:$chapter", member) } shouldBe true
            refused { insertSheet(2021, PartgUnitLevel.STATE, chapter, "STATE:${chapter.toString().uppercase()}", member) } shouldBe true
            refused { insertSheet(2021, PartgUnitLevel.SUBORDINATE, chapter, "STATE:$chapter", member) } shouldBe true
        }

        test("year range, unique year and unit, and the foreign keys") {
            val member = fx.member()
            insertSheet(2022, PartgUnitLevel.FEDERAL, null, "FEDERAL", member)
            refused { insertSheet(1999, PartgUnitLevel.FEDERAL, null, "FEDERAL", member) } shouldBe true
            refused { insertSheet(2101, PartgUnitLevel.FEDERAL, null, "FEDERAL", member) } shouldBe true
            refused { insertSheet(2022, PartgUnitLevel.FEDERAL, null, "FEDERAL", member) } shouldBe true
            refused { insertSheet(2023, PartgUnitLevel.FEDERAL, null, "FEDERAL", Uuid.random()) } shouldBe true
            val ghostChapter = Uuid.random()
            refused { insertSheet(2023, PartgUnitLevel.STATE, ghostChapter, "STATE:$ghostChapter", member) } shouldBe true
        }

        test("value amount range, one value per sheet and position, known positions only, and RESTRICT on the sheet") {
            val member = fx.member()
            val sheet = insertSheet(2024, PartgUnitLevel.FEDERAL, null, "FEDERAL", member)
            insertValue(sheet, "E01", 0, member)
            insertValue(sheet, "E02", 99_999_999_999_999L, member)
            refused { insertValue(sheet, "E03", -1, member) } shouldBe true
            refused { insertValue(sheet, "E03", 100_000_000_000_000L, member) } shouldBe true
            refused { insertValue(sheet, "E01", 5, member) } shouldBe true
            refused { insertValue(sheet, "E99", 5, member) } shouldBe true
            refused { insertValue(Uuid.random(), "E03", 5, member) } shouldBe true
            refused { PartgReportSheetTable.deleteWhere { PartgReportSheetTable.id eq sheet } } shouldBe true
        }

        test("the audit CHECK names the two new entity types") {
            val clause =
                transaction {
                    var text = ""
                    exec(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'chk_audit_log_entry_entity_type'",
                    ) { rs -> while (rs.next()) text += rs.getString(1) }
                    text
                }
            clause.contains("PARTG_REPORT_SHEET") shouldBe true
            clause.contains("PARTG_REPORT_POSITION") shouldBe true
            clause.contains("ATTACHED_IMAGE") shouldBe true
        }

        test("the 42 seeded positions satisfy their own CHECKs and the parent chain") {
            val rows =
                transaction {
                    var n = 0
                    exec("SELECT COUNT(*) FROM partg_report_position WHERE parent_code IS NOT NULL OR parent_code IS NULL") { rs ->
                        if (rs.next()) n = rs.getInt(1)
                    }
                    n
                }
            rows shouldBe 42
        }
    })
