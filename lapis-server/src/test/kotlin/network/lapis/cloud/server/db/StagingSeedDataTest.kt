package network.lapis.cloud.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.MemberStatus
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * Exercises [StagingSeedData.seedWith] against its own, ISOLATED H2 instance -- see class KDoc
 * below for why an isolated instance is load-bearing here, not merely tidy. Precedent:
 * `MemberStatusMigrationTest` (raw JDBC); this test instead needs a real Exposed [Database] handle
 * (to call `seedWith(seedPassword = ..., database = db)`), so it wires Flyway + Exposed exactly
 * the way [DatabaseConfig.buildAndMigrate] does, just against a private, per-test H2 URL.
 */
class StagingSeedDataTest :
    FunSpec({
        val seedPassword = "ein-starkes-testpasswort"

        fun freshDatabase(): Database {
            val jdbcUrl = "jdbc:h2:mem:staging-seed-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            val dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        this.jdbcUrl = jdbcUrl
                        this.username = "sa"
                        this.password = ""
                        this.driverClassName = "org.h2.Driver"
                        this.maximumPoolSize = 4
                    },
                )
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            return Database.connect(dataSource)
        }

        test("seedWith populates a fresh instance: unique @staging.invalid emails, three seeded accounts") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                val emails = MemberTable.selectAll().map { it[MemberTable.email] }
                emails.size shouldBeGreaterThan 0
                emails.forEach { it.endsWith("@${StagingSeedData.EMAIL_DOMAIN}") shouldBe true }
                emails.toSet().size shouldBe emails.size

                AccountTable.selectAll().count() shouldBeGreaterThan 0L
            }
        }

        test("exactly one ADMIN/BOARD/TREASURER account, each with a working, non-DEMO_PASSWORD hash") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                val accounts = AccountTable.selectAll().toList()
                accounts.count { it[AccountTable.role].name == "ADMIN" } shouldBe 1
                accounts.count { it[AccountTable.role].name == "BOARD" } shouldBe 1
                accounts.count { it[AccountTable.role].name == "TREASURER" } shouldBe 1

                accounts.forEach { row ->
                    val hash = row[AccountTable.passwordHash]
                    hash.shouldNotBeNull()
                    PasswordHasher.verify(rawPassword = seedPassword, storedHash = hash) shouldBe true
                    PasswordHasher.verify(rawPassword = DevSeedData.DEMO_PASSWORD, storedHash = hash) shouldBe false
                }
            }
        }

        test("organization_settings sentinel row is updated in place, exactly one row remains") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                val rows = OrganizationSettingsTable.selectAll().toList()
                rows.size shouldBe 1
                rows.single()[OrganizationSettingsTable.name] shouldBe StagingSeedData.ORGANIZATION_NAME
            }
        }

        test("governance/event data has referential consistency and non-zero counts") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                ContributionTable.selectAll().count() shouldBeGreaterThan 0L
                CommitteeTable.selectAll().count() shouldBeGreaterThan 0L
                CommitteeMembershipTable.selectAll().count() shouldBeGreaterThan 0L
                MeetingTable.selectAll().count() shouldBeGreaterThan 0L
                AgendaItemTable.selectAll().count() shouldBeGreaterThan 0L
                MotionTable.selectAll().count() shouldBeGreaterThan 0L
                EventTable.selectAll().count() shouldBeGreaterThan 0L
                EventRegistrationTable.selectAll().count() shouldBeGreaterThan 0L

                val committeeIds = CommitteeTable.selectAll().map { it[CommitteeTable.id] }.toSet()
                MeetingTable.selectAll().forEach { (it[MeetingTable.committeeId] in committeeIds) shouldBe true }

                val memberIds = MemberTable.selectAll().map { it[MemberTable.id] }.toSet()
                MotionTable.selectAll().forEach { (it[MotionTable.submitterMemberId] in memberIds) shouldBe true }
            }
        }

        test("idempotent: a second seedWith call on the same database adds nothing") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)
            val countsBefore = transaction(db) { MemberTable.selectAll().count() }

            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)
            val countsAfter = transaction(db) { MemberTable.selectAll().count() }

            countsAfter shouldBe countsBefore
        }

        test("core protection: a database with one real member row is left completely untouched") {
            val db = freshDatabase()
            val realMemberId = Uuid.random()
            transaction(db) {
                MemberTable.insert {
                    it[id] = realMemberId
                    it[displayName] = "Echtes Mitglied"
                    it[email] = "echtes.mitglied@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = kotlinx.datetime.LocalDate(2020, 1, 1)
                }
            }

            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                MemberTable.selectAll().count() shouldBe 1L
                MemberTable.selectAll().single()[MemberTable.id] shouldBe realMemberId
                OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.name] shouldBe
                    "Verein/Partei (bitte in Organisationseinstellungen konfigurieren)"
                CommitteeTable.selectAll().count() shouldBe 0L
            }

            // cleanup, not load-bearing for the assertions above
            transaction(db) { MemberTable.deleteWhere { MemberTable.id eq realMemberId } }
        }

        test("DECEASED seed row carries dateOfDeath, every other row is null") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                val rows = MemberTable.selectAll().toList()
                val deceased = rows.filter { it[MemberTable.status] == MemberStatus.DECEASED }
                deceased.size shouldBe 1
                deceased.single()[MemberTable.dateOfDeath].shouldNotBeNull()
                rows.filterNot { it[MemberTable.status] == MemberStatus.DECEASED }.forEach {
                    it[MemberTable.dateOfDeath].shouldBeNull()
                }
            }
        }

        test("every MemberStatus literal is seeded at least once") {
            val db = freshDatabase()
            StagingSeedData.seedWith(seedPassword = seedPassword, database = db)

            transaction(db) {
                val seededStatuses = MemberTable.selectAll().map { it[MemberTable.status] }.toSet()
                seededStatuses shouldBe MemberStatus.entries.toSet()
            }
        }

        test("StagingSeedConfig.Refused never runs seedWith -- covered structurally, seedIfEmpty errors before any DB access") {
            val decision =
                StagingSeedConfig.decide { key ->
                    when (key) {
                        StagingSeedConfig.ENV_STAGING_MODE -> "true"
                        else -> null
                    }
                }
            (decision is StagingSeedDecision.Refused) shouldBe true
        }
    })
