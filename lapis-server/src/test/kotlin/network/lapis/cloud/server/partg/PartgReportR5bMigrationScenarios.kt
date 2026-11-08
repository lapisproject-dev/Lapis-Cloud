package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.293 (R5b) -- the columns, CHECKs and the unique index of `V147__partg_report_r5b.sql` on H2 AND PostgreSQL with the same assertions. Every
 * refused statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the whole transaction). The file is purely additive and
 * repeatable: it runs a second time on the live schema.
 */
abstract class PartgReportR5bMigrationScenarios(
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

        fun donor(
            annex: Uuid,
            member: Uuid,
            key: String? = null,
            appliedAt: LocalDateTime? = null,
        ) = transaction {
            PartgReportLargeDonorTable.insert {
                it[id] = Uuid.random()
                it[annexId] = annex
                it[personKind] = PartgPersonKind.NATURAL
                it[name] = "Spender"
                it[country] = "DE"
                it[contributionKind] = PartgContributionKind.DONATION
                it[amountCents] = 1_200_000L
                it[createdAt] = now
                it[createdBy] = member
                it[updatedAt] = now
                it[updatedBy] = member
                it[sourceDonorKey] = key
                it[sourceAppliedAt] = appliedAt
            }
        }

        test("the two columns exist, are nullable, and an existing-style row keeps NULL in both") {
            val member = fx.member()
            val a = annex(member, 2061)
            donor(a, member)
            val columns =
                transaction {
                    val found = mutableMapOf<String, String>()
                    exec(
                        "SELECT lower(column_name), is_nullable FROM information_schema.columns WHERE lower(table_name) = 'partg_report_large_donor'",
                    ) { rs -> while (rs.next()) found[rs.getString(1)] = rs.getString(2) }
                    found
                }
            columns["source_donor_key"] shouldBe "YES"
            columns["source_applied_at"] shouldBe "YES"
            val row = transaction { PartgReportLargeDonorTable.selectAll().where { PartgReportLargeDonorTable.annexId eq a }.single() }
            row[PartgReportLargeDonorTable.sourceDonorKey] shouldBe null
            row[PartgReportLargeDonorTable.sourceAppliedAt] shouldBe null
        }

        test("the key CHECK: M: or X: plus a 36 character UUID; the take-over time needs a key") {
            val member = fx.member()
            val a = annex(member, 2062)
            val uuid = Uuid.random().toString()
            donor(a, member, key = "M:$uuid", appliedAt = now)
            donor(a, member, key = "X:${Uuid.random()}")
            refused { donor(a, member, key = "Z:${Uuid.random()}") } shouldBe true
            refused { donor(a, member, key = "M:abc") } shouldBe true
            refused { donor(a, member, key = "M:${Uuid.random()}0") } shouldBe true
            refused { donor(a, member, key = "m:${Uuid.random()}") } shouldBe true
            refused { donor(a, member, key = null, appliedAt = now) } shouldBe true
        }

        test(
            "the unique index is a normal one: several rows without a key are allowed, a doubled key in one annex is not, in another annex it is",
        ) {
            val member = fx.member()
            val a = annex(member, 2063)
            val b = annex(member, 2064)
            donor(a, member)
            donor(a, member)
            donor(a, member)
            val key = "M:${Uuid.random()}"
            donor(a, member, key = key)
            refused { donor(a, member, key = key) } shouldBe true
            donor(b, member, key = key)
        }

        test("the migration is repeatable, keeps the rules in force and its audit CHECK knows the new type and the old ones") {
            val sql =
                checkNotNull(
                    PartgReportR5bMigrationScenarios::class.java.classLoader
                        .getResourceAsStream("db/migration/V147__partg_report_r5b.sql"),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 8) shouldBe true
            transaction { statements.forEach { exec(it) } }
            val member = fx.member()
            val a = annex(member, 2065)
            donor(a, member, key = "M:${Uuid.random()}", appliedAt = now)
            refused { donor(a, member, key = "Q:${Uuid.random()}") } shouldBe true
            val types =
                listOf(
                    AuditEntityType.PARTG_LARGE_DONOR_PROPOSAL,
                    AuditEntityType.PARTG_REPORT_VERSION,
                    AuditEntityType.PARTG_REPORT_DEADLINE,
                    AuditEntityType.PARTG_DONOR_COMBINATION,
                    AuditEntityType.PARTG_REPORT_ANNEX,
                    AuditEntityType.MEMBER,
                )
            types.forEach { type ->
                transaction {
                    AuditLogRecorder.record(
                        actorMemberId = member,
                        actorRole = AccountRole.BOARD,
                        entityType = type,
                        entityId = Uuid.random(),
                        action = AuditAction.CREATE,
                        after = "{}",
                    )
                }
            }
            transaction {
                AuditLogEntryTable.selectAll().where { AuditLogEntryTable.actorMemberId eq member }.count()
            } shouldBe types.size.toLong()
        }
    })
