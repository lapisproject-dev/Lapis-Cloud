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
 * Welle V1.9.57 -- `V71__privileged_action_request.sql`: every CHECK / UNIQUE / FK rule of `privileged_action_request`
 * actually bites. "At most one open request per (target, action)" is the H2-capable substitute for a partial unique
 * index (`open_target_member_id` + `chk_privileged_action_request_open`); this proves it on H2 and on PostgreSQL.
 */
abstract class PrivilegedActionMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        installLaneGuards(db = db)
        lateinit var actor: UUID
        lateinit var target: UUID
        lateinit var other: UUID

        beforeSpec {
            db.activate()
            actor = UUID.randomUUID()
            target = UUID.randomUUID()
            other = UUID.randomUUID()
            transaction {
                listOf(actor, target, other).forEach { id ->
                    exec(
                        "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                            "('$id', 'M', 'par-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
                    )
                }
            }
        }
        afterSpec {
            transaction {
                exec("DELETE FROM privileged_action_request WHERE target_member_id IN ('$target', '$other')")
                exec("DELETE FROM member WHERE id IN ('$actor', '$target', '$other')")
            }
            db.deactivate()
        }

        fun rejection(sql: String): String? =
            runCatching { transaction { exec(sql) } }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        @Suppress("LongParameterList")
        fun insert(
            targetId: UUID = target,
            openTarget: String? = "'$targetId'",
            status: String = "PENDING",
            action: String = "TEMP_PASSWORD",
            requestedRole: String? = if (action == "DEMOTE") "MEMBER" else null,
            requestedStatus: String? = if (action == "SUSPEND") "WITHDRAWN" else null,
            vetoHash: String? = null,
            approver: UUID? = null,
            actorId: UUID = actor,
            targetRole: String = "ADMIN",
        ) = "INSERT INTO privileged_action_request (id, action, actor_member_id, target_member_id, open_target_member_id, " +
            "target_role_at_request, requested_role, requested_status, reason, status, approver_member_id, veto_token_hash, " +
            "created_at, expires_at) VALUES ('${UUID.randomUUID()}', '$action', '$actorId', '$targetId', ${openTarget ?: "NULL"}, " +
            "'$targetRole', ${requestedRole?.let { "'$it'" } ?: "NULL"}, ${requestedStatus?.let { "'$it'" } ?: "NULL"}, " +
            "'a sufficiently long reason', '$status', ${approver?.let { "'$it'" } ?: "NULL"}, ${vetoHash?.let { "'$it'" } ?: "NULL"}, " +
            "TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-04 10:00:00')"

        test("at most one open request per (target, action): the second violates uq_privileged_action_request_open") {
            rejection(insert()) shouldBe null
            val second = rejection(insert())
            (second != null) shouldBe true
            second!!.lowercase() shouldContain "uq_privileged_action_request_open"
            // another action against the same target, and the same action against another target, are unaffected
            rejection(insert(action = "DEMOTE")) shouldBe null
            rejection(insert(targetId = other)) shouldBe null
        }

        test("APPROVED_WAITING is open as well, resolved rows are unlimited (open_target_member_id NULL)") {
            val t = UUID.randomUUID()
            transaction {
                exec(
                    "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                        "('$t', 'M', 'par-$t@example.org', 'ACTIVE', DATE '2026-01-01')",
                )
            }
            rejection(insert(targetId = t, status = "APPROVED_WAITING")) shouldBe null
            (rejection(insert(targetId = t, status = "PENDING")) != null) shouldBe true
            listOf("EXECUTED", "REJECTED", "WITHDRAWN", "VETOED", "EXPIRED", "INVALIDATED", "EXECUTED").forEach {
                rejection(insert(targetId = t, openTarget = null, status = it)) shouldBe null
            }
            transaction { exec("DELETE FROM privileged_action_request WHERE target_member_id = '$t'") }
            transaction { exec("DELETE FROM member WHERE id = '$t'") }
        }

        test("chk_privileged_action_request_open ties open_target_member_id to the status") {
            (rejection(insert(targetId = other, action = "SUSPEND", openTarget = null, status = "PENDING")) != null) shouldBe true
            (rejection(insert(targetId = other, action = "SUSPEND", openTarget = "'$target'", status = "PENDING")) != null) shouldBe true
            (rejection(insert(targetId = other, action = "SUSPEND", openTarget = "'$other'", status = "EXECUTED")) != null) shouldBe true
        }

        test("action, status, roles and the DEMOTE/SUSPEND column coupling are enforced") {
            (
                rejection(insert(openTarget = null, status = "EXECUTED", action = "BOGUS", requestedRole = null, requestedStatus = null)) !=
                    null
            ) shouldBe
                true
            (rejection(insert(openTarget = null, status = "BOGUS")) != null) shouldBe true
            (rejection(insert(openTarget = null, status = "EXECUTED", targetRole = "ROOT")) != null) shouldBe true
            // DEMOTE needs a requested role, the others must not carry one
            (rejection(insert(openTarget = null, status = "EXECUTED", action = "DEMOTE", requestedRole = null)) != null) shouldBe true
            (rejection(insert(openTarget = null, status = "EXECUTED", action = "TEMP_PASSWORD", requestedRole = "MEMBER")) != null) shouldBe
                true
            (rejection(insert(openTarget = null, status = "EXECUTED", action = "DEMOTE", requestedRole = "ROOT")) != null) shouldBe true
            // SUSPEND needs a requested status, the others must not carry one
            (rejection(insert(openTarget = null, status = "EXECUTED", action = "SUSPEND", requestedStatus = null)) != null) shouldBe true
            (rejection(insert(openTarget = null, status = "EXECUTED", action = "DEMOTE", requestedStatus = "WITHDRAWN")) != null) shouldBe
                true
            (rejection(insert(openTarget = null, status = "EXECUTED", action = "SUSPEND", requestedStatus = "NOPE")) != null) shouldBe true
        }

        test("veto token hashes are unique where set, several NULLs are allowed") {
            val h = "c".repeat(64)
            rejection(insert(openTarget = null, status = "VETOED", vetoHash = h)) shouldBe null
            (rejection(insert(openTarget = null, status = "VETOED", vetoHash = h)) != null) shouldBe true
            repeat(2) { rejection(insert(openTarget = null, status = "EXECUTED")) shouldBe null }
        }

        test("foreign keys: unknown actor, target and approver are rejected") {
            val ghost = UUID.randomUUID()
            (rejection(insert(targetId = ghost, openTarget = null, status = "EXECUTED")) != null) shouldBe true
            (rejection(insert(openTarget = null, status = "EXECUTED", actorId = ghost)) != null) shouldBe true
            (rejection(insert(openTarget = null, status = "EXECUTED", approver = ghost)) != null) shouldBe true
            rejection(insert(openTarget = null, status = "EXECUTED", approver = other)) shouldBe null
        }

        test("account.role_changed_at exists and is nullable") {
            val accountId = UUID.randomUUID()
            rejection(
                "INSERT INTO account (id, role, member_id) VALUES ('$accountId', 'MEMBER', '$other')",
            ) shouldBe null
            transaction {
                exec("SELECT role_changed_at FROM account WHERE id = '$accountId'") { rs ->
                    rs.next() shouldBe true
                    rs.getTimestamp(1) shouldBe null
                }
                exec("DELETE FROM account WHERE id = '$accountId'")
            }
        }
    })

class PrivilegedActionMigrationTest : PrivilegedActionMigrationScenarios(TestDatabase.H2) {
    init {
        test(
            "upgrading a V70 database keeps member and account rows byte-identical, adds an empty table and a NULL role_changed_at; a re-run is a no-op",
        ) {
            val jdbcUrl =
                "jdbc:h2:mem:privileged-action-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

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

            flyway("70").migrate()
            val member = UUID.randomUUID()
            val account = UUID.randomUUID()
            execute(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$member', 'M', 'Foo@Example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            execute("INSERT INTO account (id, role, member_id) VALUES ('$account', 'ADMIN', '$member')")
            (flyway(null).migrate().migrationsExecuted >= 1) shouldBe true
            query("SELECT email FROM member WHERE id = '$member'") {
                it.next()
                it.getString(1) shouldBe "Foo@Example.org"
            }
            query("SELECT role, role_changed_at FROM account WHERE id = '$account'") {
                it.next()
                it.getString(1) shouldBe "ADMIN"
                it.getTimestamp(2) shouldBe null
            }
            query("SELECT count(*) FROM privileged_action_request") {
                it.next()
                it.getLong(1) shouldBe 0L
            }
            flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    }
}

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PrivilegedActionMigrationPostgresTest : PrivilegedActionMigrationScenarios(TestDatabase.Postgres())

/** The V70 -> V71 upgrade against a real, EMPTY PostgreSQL database (not the lane's template: it must run the migrations itself). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PrivilegedActionUpgradePostgresTest :
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

        test("upgrading a V70 PostgreSQL database keeps an account byte-identical, the new table is empty, a re-run is a no-op") {
            flyway("70").migrate()
            val member = UUID.randomUUID()
            val account = UUID.randomUUID()
            inConnection { c ->
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                            "('$member', 'M', 'Foo@Example.org', 'ACTIVE', DATE '2026-01-01')",
                    )
                    it.execute("INSERT INTO account (id, role, member_id) VALUES ('$account', 'ADMIN', '$member')")
                }
            }
            (flyway(null).migrate().migrationsExecuted >= 1) shouldBe true
            inConnection { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT role, role_changed_at FROM account WHERE id = '$account'").use {
                        it.next()
                        it.getString(1) shouldBe "ADMIN"
                        it.getTimestamp(2) shouldBe null
                    }
                    st.executeQuery("SELECT count(*) FROM privileged_action_request").use {
                        it.next()
                        it.getLong(1) shouldBe 0L
                    }
                }
            }
            flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("the partial-unique substitute works on PostgreSQL: a second open request is a 23505 on uq_privileged_action_request_open") {
            val actor = UUID.randomUUID()
            val target = UUID.randomUUID()
            inConnection { c ->
                c.createStatement().use { st ->
                    listOf(actor, target).forEach {
                        st.execute(
                            "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                                "('$it', 'M', 'one-open-$it@example.org', 'ACTIVE', DATE '2026-01-01')",
                        )
                    }

                    fun row() =
                        "INSERT INTO privileged_action_request (id, action, actor_member_id, target_member_id, open_target_member_id, " +
                            "target_role_at_request, reason, status, created_at, expires_at) VALUES " +
                            "('${UUID.randomUUID()}', 'TEMP_PASSWORD', " +
                            "'$actor', '$target', '$target', 'ADMIN', 'a sufficiently long reason', 'PENDING', " +
                            "TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-04 10:00:00')"
                    st.execute(row())
                    val failure = runCatching { st.execute(row()) }.exceptionOrNull()
                    (failure as java.sql.SQLException).sqlState shouldBe "23505"
                    failure.message!!.lowercase() shouldContain "uq_privileged_action_request_open"
                }
            }
        }
    })
