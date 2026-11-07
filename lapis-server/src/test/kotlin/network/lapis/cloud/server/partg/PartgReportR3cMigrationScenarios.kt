package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportEnterpriseTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.287 -- the CHECKs and FOREIGN KEY rules of `V143__partg_report_r3c.sql` on H2 AND PostgreSQL with the same assertions. Every refused
 * statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the whole transaction).
 */
abstract class PartgReportR3cMigrationScenarios(
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

        fun annex(
            member: Uuid,
            year: Int,
        ): Uuid =
            transaction {
                val id = Uuid.random()
                PartgReportAnnexTable.insert {
                    it[PartgReportAnnexTable.id] = id
                    it[fiscalYear] = year
                    it[unitLevel] = PartgUnitLevel.FEDERAL
                    it[unitKey] = "FEDERAL"
                    it[revision] = 0L
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
                id
            }

        fun enterprise(
            annex: Uuid,
            member: Uuid,
            name: String = "Betrieb",
            legalForm: String? = null,
            seat: String? = null,
            share: Int? = null,
            income: Long? = null,
            expenses: Long? = null,
        ) = transaction {
            PartgReportEnterpriseTable.insert {
                it[id] = Uuid.random()
                it[annexId] = annex
                it[PartgReportEnterpriseTable.name] = name
                it[PartgReportEnterpriseTable.legalForm] = legalForm
                it[PartgReportEnterpriseTable.seat] = seat
                it[shareBasisPoints] = share
                it[incomeCents] = income
                it[expensesCents] = expenses
                it[createdAt] = now
                it[createdBy] = member
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        test("the table, its columns and indexes exist") {
            val columns =
                transaction {
                    val found = mutableListOf<String>()
                    exec(
                        "SELECT lower(column_name) FROM information_schema.columns WHERE lower(table_name) = 'partg_report_enterprise'",
                    ) { rs -> while (rs.next()) found += rs.getString(1) }
                    found.toSet()
                }
            columns shouldBe
                setOf(
                    "id",
                    "annex_id",
                    "name",
                    "legal_form",
                    "seat",
                    "share_basis_points",
                    "income_cents",
                    "expenses_cents",
                    "created_at",
                    "created_by",
                    "updated_at",
                    "updated_by",
                )
            val indexQuery =
                if (db.isPostgres) {
                    "SELECT lower(indexname) FROM pg_indexes WHERE lower(tablename) = 'partg_report_enterprise'"
                } else {
                    "SELECT lower(index_name) FROM information_schema.indexes WHERE lower(table_name) = 'partg_report_enterprise'"
                }
            val indexes =
                transaction {
                    val found = mutableListOf<String>()
                    exec(indexQuery) { rs -> while (rs.next()) found += rs.getString(1) }
                    found.toSet()
                }
            listOf(
                "ix_partg_report_enterprise_annex",
                "ix_partg_report_enterprise_created_by",
                "ix_partg_report_enterprise_updated_by",
            ).forEach { (it in indexes) shouldBe true }
        }

        test("the text columns: name 1..200, legal form 1..60, seat 1..100 at the boundary and beyond") {
            val member = fx.member()
            val a = annex(member, 2041)
            enterprise(a, member, name = "x".repeat(200), legalForm = "x".repeat(60), seat = "x".repeat(100))
            enterprise(a, member, name = "x")
            refused { enterprise(a, member, name = "") } shouldBe true
            refused { enterprise(a, member, name = "x".repeat(201)) } shouldBe true
            refused { enterprise(a, member, legalForm = "") } shouldBe true
            refused { enterprise(a, member, legalForm = "x".repeat(61)) } shouldBe true
            refused { enterprise(a, member, seat = "") } shouldBe true
            refused { enterprise(a, member, seat = "x".repeat(101)) } shouldBe true
        }

        test("the share 0..10000, the amounts 0..99999999999999 or NULL (not entered); zero is a value") {
            val member = fx.member()
            val a = annex(member, 2042)
            enterprise(a, member)
            enterprise(a, member, share = 0, income = 0L, expenses = 0L)
            enterprise(a, member, share = 10_000, income = 99_999_999_999_999L, expenses = 99_999_999_999_999L)
            refused { enterprise(a, member, share = -1) } shouldBe true
            refused { enterprise(a, member, share = 10_001) } shouldBe true
            refused { enterprise(a, member, income = -1L) } shouldBe true
            refused { enterprise(a, member, income = 100_000_000_000_000L) } shouldBe true
            refused { enterprise(a, member, expenses = -1L) } shouldBe true
            refused { enterprise(a, member, expenses = 100_000_000_000_000L) } shouldBe true
        }

        test("foreign keys: known annex and known member only") {
            val member = fx.member()
            val a = annex(member, 2043)
            refused { enterprise(Uuid.random(), member) } shouldBe true
            refused { enterprise(a, Uuid.random()) } shouldBe true
        }

        test("no ON DELETE CASCADE: an annex with an enterprise cannot be deleted") {
            val member = fx.member()
            val a = annex(member, 2044)
            enterprise(a, member)
            refused { PartgReportAnnexTable.deleteWhere { PartgReportAnnexTable.id eq a } } shouldBe true
        }

        test("the migration is repeatable (the SQL file runs a second time on the live schema) and adds no audit entity type") {
            val sql =
                checkNotNull(
                    PartgReportR3cMigrationScenarios::class.java.classLoader
                        .getResourceAsStream("db/migration/V143__partg_report_r3c.sql"),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size > 10) shouldBe true
            transaction { statements.forEach { exec(it) } }
            val clause =
                transaction {
                    var text = ""
                    exec(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'chk_audit_log_entry_entity_type'",
                    ) { rs -> while (rs.next()) text += rs.getString(1) }
                    text
                }
            clause.contains("PARTG_REPORT_ANNEX") shouldBe true
            clause.contains("PARTG_REPORT_ENTERPRISE") shouldBe false
        }
    })
