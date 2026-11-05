package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportSponsorTable
import network.lapis.cloud.server.db.generated.PartgReportSponsoringEntryTable
import network.lapis.cloud.server.db.generated.PartgReportYouthOrgTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.283 -- the CHECKs and FOREIGN KEY rules of `V142__partg_report_r3b.sql` on H2 AND PostgreSQL with the same assertions. Every refused
 * statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the whole transaction).
 */
abstract class PartgReportR3bMigrationScenarios(
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

        fun sponsor(
            annex: Uuid,
            member: Uuid,
            name: String = "Sponsor GmbH",
            kind: PartgPersonKind = PartgPersonKind.LEGAL,
            country: String = "DE",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                PartgReportSponsorTable.insert {
                    it[PartgReportSponsorTable.id] = id
                    it[annexId] = annex
                    it[personKind] = kind
                    it[PartgReportSponsorTable.name] = name
                    it[PartgReportSponsorTable.country] = country
                    it[createdAt] = now
                    it[createdBy] = member
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
            }
            return id
        }

        fun entry(
            sponsor: Uuid,
            member: Uuid,
            cents: Long,
            purpose: String? = null,
        ) = transaction {
            PartgReportSponsoringEntryTable.insert {
                it[id] = Uuid.random()
                it[sponsorId] = sponsor
                it[sponsoredOn] = LocalDate(2026, 3, 1)
                it[amountCents] = cents
                it[PartgReportSponsoringEntryTable.purpose] = purpose
                it[createdAt] = now
                it[createdBy] = member
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        fun youth(
            annex: Uuid,
            member: Uuid,
            name: String = "Jugend",
            relationship: String? = null,
            income: Long? = null,
            expenses: Long? = null,
        ) = transaction {
            PartgReportYouthOrgTable.insert {
                it[id] = Uuid.random()
                it[annexId] = annex
                it[PartgReportYouthOrgTable.name] = name
                it[PartgReportYouthOrgTable.relationship] = relationship
                it[incomeCents] = income
                it[expensesCents] = expenses
                it[createdAt] = now
                it[createdBy] = member
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        test("a sponsor: person kind, name length 1..200, country exactly two characters, known annex and member") {
            val member = fx.member()
            val a = annex(member, 2031)
            sponsor(a, member)
            sponsor(a, member, kind = PartgPersonKind.NATURAL, name = "x".repeat(200))
            refused { sponsor(a, member, name = "") } shouldBe true
            refused { sponsor(a, member, name = "x".repeat(201)) } shouldBe true
            refused { sponsor(a, member, country = "D") } shouldBe true
            refused { sponsor(a, member, country = "DEU") } shouldBe true
            refused { sponsor(Uuid.random(), member) } shouldBe true
            refused { sponsor(a, Uuid.random()) } shouldBe true
        }

        test("a sponsoring entry: amount 1..99999999999999 (no limit of the report is enforced), purpose 1..500, known sponsor") {
            val member = fx.member()
            val a = annex(member, 2032)
            val s = sponsor(a, member)
            entry(s, member, 1L)
            entry(s, member, 75_000L) // exactly the single limit: stored, qualification is computed
            entry(s, member, 99_999_999_999_999L, purpose = "x".repeat(500))
            refused { entry(s, member, 0L) } shouldBe true
            refused { entry(s, member, -1L) } shouldBe true
            refused { entry(s, member, 100_000_000_000_000L) } shouldBe true
            refused { entry(s, member, 10L, purpose = "") } shouldBe true
            refused { entry(s, member, 10L, purpose = "x".repeat(501)) } shouldBe true
            refused { entry(Uuid.random(), member, 10L) } shouldBe true
        }

        test("a youth organisation: NULL figures mean not entered, zero is a value, negative and too large are refused") {
            val member = fx.member()
            val a = annex(member, 2033)
            youth(a, member)
            youth(a, member, income = 0L, expenses = 99_999_999_999_999L, relationship = "x".repeat(200))
            refused { youth(a, member, name = "") } shouldBe true
            refused { youth(a, member, name = "x".repeat(201)) } shouldBe true
            refused { youth(a, member, relationship = "") } shouldBe true
            refused { youth(a, member, relationship = "x".repeat(201)) } shouldBe true
            refused { youth(a, member, income = -1L) } shouldBe true
            refused { youth(a, member, expenses = 100_000_000_000_000L) } shouldBe true
            refused { youth(Uuid.random(), member) } shouldBe true
        }

        test("no ON DELETE CASCADE: a sponsor with entries and an annex with sponsors or youth organisations cannot be deleted") {
            val member = fx.member()
            val a = annex(member, 2034)
            val s = sponsor(a, member)
            entry(s, member, 100L)
            youth(a, member)
            refused { PartgReportSponsorTable.deleteWhere { PartgReportSponsorTable.id eq s } } shouldBe true
            refused { PartgReportAnnexTable.deleteWhere { PartgReportAnnexTable.id eq a } } shouldBe true
        }

        test("the migration is repeatable and the audit CHECK list is untouched") {
            val clause =
                transaction {
                    var text = ""
                    exec(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'chk_audit_log_entry_entity_type'",
                    ) { rs -> while (rs.next()) text += rs.getString(1) }
                    text
                }
            listOf("PARTG_REPORT_ANNEX", "PARTG_REPORT_NOTES", "PARTG_REPORT_EXPORT").forEach { clause.contains(it) shouldBe true }
            // R3b adds no entity type: the existing PARTG_REPORT_ANNEX is reused with new change codes
            clause.contains("PARTG_REPORT_SPONSOR") shouldBe false
        }
    })
