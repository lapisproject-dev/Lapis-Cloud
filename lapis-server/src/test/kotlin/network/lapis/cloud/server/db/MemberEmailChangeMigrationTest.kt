package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PgSpecDatabase
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Welle V1.9.56 -- `V70__member_email_change.sql`: every CHECK / UNIQUE / FK rule of `member_email_change` actually bites.
 * The "at most one open change per member" rule is the H2-capable substitute for a partial unique index (`open_member_id`
 * UNIQUE plus `chk_member_email_change_open`); this proves the substitute holds on H2 and on PostgreSQL.
 */
abstract class MemberEmailChangeMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        installLaneGuards(db = db)
        lateinit var member: UUID
        lateinit var other: UUID

        beforeSpec {
            db.activate()
            member = UUID.randomUUID()
            other = UUID.randomUUID()
            transaction {
                listOf(member, other).forEach { id ->
                    exec(
                        "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                            "('$id', 'M', 'mig-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
                    )
                }
            }
        }
        afterSpec {
            transaction {
                exec("DELETE FROM member_email_change WHERE member_id IN ('$member', '$other')")
                exec("DELETE FROM member WHERE id IN ('$member', '$other')")
            }
            db.deactivate()
        }

        /** The failure message of [sql], or null when it was accepted. */
        fun rejection(sql: String): String? =
            runCatching {
                transaction {
                    exec(sql)
                }
            }.exceptionOrNull()?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun insert(
            memberId: UUID = member,
            openMemberId: String? = "'$memberId'",
            status: String = "PENDING",
            kind: String = "PROPOSAL",
            confirmHash: String? = null,
            revokeHash: String? = null,
            requestedBy: UUID? = null,
        ) = "INSERT INTO member_email_change (id, member_id, open_member_id, pending_email, kind, requested_by, confirm_token_hash, " +
            "revoke_token_hash, status, created_at, expires_at) VALUES ('${UUID.randomUUID()}', '$memberId', ${openMemberId ?: "NULL"}, " +
            "'new-${UUID.randomUUID()}@example.org', '$kind', ${requestedBy?.let { "'$it'" } ?: "NULL"}, " +
            "${confirmHash?.let { "'$it'" } ?: "NULL"}, ${revokeHash?.let { "'$it'" } ?: "NULL"}, '$status', " +
            "TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-08 10:00:00')"

        test("at most one PENDING change per member: the second one violates uq_member_email_change_open") {
            rejection(insert()) shouldBe null
            val second = rejection(insert())
            (second != null) shouldBe true
            second!!.lowercase() shouldContain "uq_member_email_change_open"
            // another member is unaffected
            rejection(insert(memberId = other)) shouldBe null
        }

        test("any number of resolved changes per member are fine (open_member_id NULL, several NULLs allowed)") {
            repeat(3) { rejection(insert(openMemberId = null, status = "APPLIED")) shouldBe null }
            listOf("REVOKED", "WITHDRAWN", "EXPIRED", "SUPERSEDED", "CONFLICT").forEach {
                rejection(insert(openMemberId = null, status = it)) shouldBe null
            }
        }

        test("chk_member_email_change_open ties open_member_id to the status") {
            val unrelated = UUID.randomUUID()
            (rejection(insert(memberId = other, openMemberId = null, status = "PENDING")) != null) shouldBe true // PENDING needs open id
            (rejection(insert(memberId = other, openMemberId = "'$member'", status = "PENDING")) != null) shouldBe true // wrong id
            // resolved must be NULL
            (rejection(insert(memberId = other, openMemberId = "'$other'", status = "APPLIED")) != null) shouldBe true
            unrelated.toString().isNotEmpty() shouldBe true
        }

        test("kind and status accept only the known values") {
            (rejection(insert(openMemberId = null, status = "BOGUS")) != null) shouldBe true
            (rejection(insert(openMemberId = null, status = "APPLIED", kind = "BOGUS")) != null) shouldBe true
            listOf("SELF", "PROPOSAL", "PROPOSAL_NO_ACCOUNT", "ADMIN_OVERRIDE").forEach {
                rejection(insert(openMemberId = null, status = "APPLIED", kind = it)) shouldBe null
            }
        }

        test("token hashes are unique where set, several NULLs are allowed") {
            val h = "a".repeat(64)
            rejection(insert(openMemberId = null, status = "APPLIED", confirmHash = h)) shouldBe null
            (rejection(insert(openMemberId = null, status = "APPLIED", confirmHash = h)) != null) shouldBe true
            val r = "b".repeat(64)
            rejection(insert(openMemberId = null, status = "APPLIED", revokeHash = r)) shouldBe null
            (rejection(insert(openMemberId = null, status = "APPLIED", revokeHash = r)) != null) shouldBe true
            // confirm and revoke hashes live in separate columns: the same value in the other column is a different slot
            rejection(insert(openMemberId = null, status = "APPLIED", confirmHash = r)) shouldBe null
        }

        test("foreign keys: unknown member and unknown requester are rejected") {
            val ghost = UUID.randomUUID()
            (rejection(insert(memberId = ghost, openMemberId = null, status = "APPLIED")) != null) shouldBe true
            (rejection(insert(openMemberId = null, status = "APPLIED", requestedBy = ghost)) != null) shouldBe true
            rejection(insert(openMemberId = null, status = "APPLIED", requestedBy = other)) shouldBe null
        }
    })

class MemberEmailChangeMigrationTest : MemberEmailChangeMigrationScenarios(TestDatabase.H2) {
    init {
        test(
            "upgrading a V69 database keeps every address byte-identical and adds an empty member_email_change; a re-run is a no-op",
        ) {
            val jdbcUrl =
                "jdbc:h2:mem:member-email-change-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

            fun flyway(target: String?): Flyway {
                val configuration = Flyway.configure().dataSource(jdbcUrl, "sa", "").locations("classpath:db/migration")
                if (target != null) configuration.target(target)
                return configuration.load()
            }

            fun <T> query(
                sql: String,
                read: (java.sql.ResultSet) -> T,
            ): T =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                    c.createStatement().use { st -> st.executeQuery(sql).use(read) }
                }

            fun execute(sql: String) =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { c -> c.createStatement().use { it.execute(sql) } }

            flyway("69").migrate()
            val member = UUID.randomUUID()
            execute(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$member', 'M', 'Foo@Example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            (flyway(null).migrate().migrationsExecuted >= 1) shouldBe true
            query("SELECT email FROM member WHERE id = '$member'") {
                it.next()
                it.getString(1) shouldBe "Foo@Example.org"
            }
            query("SELECT count(*) FROM member_email_change") {
                it.next()
                it.getLong(1) shouldBe 0L
            }
            flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    }
}

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberEmailChangeMigrationPostgresTest : MemberEmailChangeMigrationScenarios(TestDatabase.Postgres())

/** The V69 -> V70 upgrade against a real, EMPTY PostgreSQL database (not the lane's template: it must run the migrations itself). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberEmailChangeUpgradePostgresTest :
    FunSpec({
        lateinit var pg: PgSpecDatabase

        beforeSpec { pg = PostgresTestSupport.createDatabase(migrated = false, timeouts = DbSessionTimeouts.DISABLED) }
        afterSpec { pg.close() }

        fun flyway(target: String?): Flyway {
            val configuration = Flyway.configure().dataSource(pg.dataSource).locations("classpath:db/migration")
            if (target != null) configuration.target(target)
            return configuration.load()
        }

        fun <T> inConnection(block: (Connection) -> T): T = pg.rawConnection().use(block)

        test(
            "upgrading a V69 PostgreSQL database keeps an address byte-identical (mixed case), the new table is empty, a re-run is a no-op",
        ) {
            flyway("69").migrate()
            val member = UUID.randomUUID()
            inConnection { c ->
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                            "('$member', 'M', 'Foo@Example.org', 'ACTIVE', DATE '2026-01-01')",
                    )
                }
            }
            (flyway(null).migrate().migrationsExecuted >= 1) shouldBe true
            inConnection { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT email FROM member WHERE id = '$member'").use {
                        it.next()
                        it.getString(1) shouldBe "Foo@Example.org"
                    }
                    st.executeQuery("SELECT count(*) FROM member_email_change").use {
                        it.next()
                        it.getLong(1) shouldBe 0L
                    }
                }
            }
            flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test(
            "the partial-unique substitute works on PostgreSQL: a second PENDING row is a 23505 on uq_member_email_change_open",
        ) {
            val member = UUID.randomUUID()
            inConnection { c ->
                c.createStatement().use { st ->
                    st.execute(
                        "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                            "('$member', 'M', 'one-open-${UUID.randomUUID()}@example.org', 'ACTIVE', DATE '2026-01-01')",
                    )

                    fun row() =
                        "INSERT INTO member_email_change " +
                            "(id, member_id, open_member_id, pending_email, kind, status, created_at, expires_at) VALUES " +
                            "('${UUID.randomUUID()}', '$member', '$member', 'x-${UUID.randomUUID()}@example.org', 'PROPOSAL', 'PENDING', " +
                            "TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-08 10:00:00')"
                    st.execute(row())
                    val failure = runCatching { st.execute(row()) }.exceptionOrNull()
                    (failure as java.sql.SQLException).sqlState shouldBe "23505"
                    failure.message!!.lowercase() shouldContain "uq_member_email_change_open"
                }
            }
        }
    })
