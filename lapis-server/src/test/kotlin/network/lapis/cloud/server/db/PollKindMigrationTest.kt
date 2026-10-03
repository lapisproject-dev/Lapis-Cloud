package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.util.UUID

/**
 * V1.9.41 -- `V69__poll_kinds.sql`: every CHECK / UNIQUE rule of the consensus kinds actually bites (H2 and PostgreSQL).
 */
abstract class PollKindMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        installLaneGuards(db = db)
        val data = PollTestData()
        lateinit var member: UUID

        beforeSpec {
            db.activate()
            member = UUID.fromString(data.member(label = "kind-migration").toString())
        }
        afterSpec {
            transaction {
                exec("DELETE FROM poll_response_rating")
                exec("DELETE FROM poll_response")
                exec("DELETE FROM poll_option")
                exec("DELETE FROM poll")
            }
            data.cleanUp()
            db.deactivate()
        }

        fun rejected(sql: String): Boolean = runCatching { transaction { exec(sql) } }.isFailure

        fun newPoll(kind: String = "SK_DECISION"): UUID {
            val id = UUID.randomUUID()
            transaction {
                exec(
                    "INSERT INTO poll (id, question, status, created_by, created_at, kind) VALUES " +
                        "('$id', 'Q?', 'OPEN', '$member', TIMESTAMP '2026-01-01 10:00:00', '$kind')",
                )
            }
            return id
        }

        fun newOption(
            poll: UUID,
            position: Int,
            passive: Boolean = false,
            explanation: String? = null,
        ): UUID {
            val id = UUID.randomUUID()
            transaction {
                exec(
                    "INSERT INTO poll_option (id, poll_id, position, text, is_passive, explanation) VALUES " +
                        "('$id', '$poll', $position, 'o$position', $passive, ${explanation?.let { "'$it'" } ?: "NULL"})",
                )
            }
            return id
        }

        test("poll.kind accepts the three kinds and rejects anything else; the default is SINGLE_CHOICE") {
            listOf("SINGLE_CHOICE", "SK_DECISION", "SK_PRIORITY").forEach { newPoll(it) }
            rejected(
                "INSERT INTO poll (id, question, status, created_by, created_at, kind) VALUES " +
                    "('${UUID.randomUUID()}', 'Q?', 'OPEN', '$member', TIMESTAMP '2026-01-01 10:00:00', 'BOGUS')",
            ) shouldBe true
            val id = UUID.randomUUID()
            transaction {
                exec(
                    "INSERT INTO poll (id, question, status, created_by, created_at) VALUES " +
                        "('$id', 'Q?', 'OPEN', '$member', TIMESTAMP '2026-01-01 10:00:00')",
                )
                exec("SELECT kind FROM poll WHERE id = '$id'") { rs ->
                    rs.next()
                    rs.getString(1) shouldBe "SINGLE_CHOICE"
                }
            }
        }

        test("poll_option: positions 0..9 for normal options, 10 only for the passive one, at most one passive, no explanation for it") {
            val poll = newPoll()
            newOption(poll, 0)
            newOption(poll, 9)
            rejected(
                "INSERT INTO poll_option (id, poll_id, position, text, is_passive) VALUES ('${UUID.randomUUID()}', '$poll', 3, 'x', TRUE)",
            ) shouldBe
                true
            rejected(
                "INSERT INTO poll_option (id, poll_id, position, text, is_passive) VALUES ('${UUID.randomUUID()}', '$poll', 10, 'x', FALSE)",
            ) shouldBe
                true
            newOption(poll, 10, passive = true)
            rejected(
                "INSERT INTO poll_option (id, poll_id, position, text, is_passive) VALUES ('${UUID.randomUUID()}', '$poll', 10, 'y', TRUE)",
            ) shouldBe
                true
            val other = newPoll()
            rejected(
                "INSERT INTO poll_option (id, poll_id, position, text, is_passive, explanation) VALUES " +
                    "('${UUID.randomUUID()}', '$other', 10, 'x', TRUE, 'not allowed')",
            ) shouldBe true
            newOption(other, 1, explanation = "allowed")
        }

        test("poll_response: option_id may be NULL only with weight 0") {
            val poll = newPoll()
            val option = newOption(poll, 0)

            fun insert(
                optionSql: String,
                weight: String,
            ) =
                "INSERT INTO poll_response (id, poll_id, option_id, weight_ltr) VALUES ('${UUID.randomUUID()}', '$poll', $optionSql, $weight)"
            rejected(insert("NULL", "5.00")) shouldBe true
            rejected(insert("NULL", "0.00")) shouldBe false
            rejected(insert("'$option'", "5.00")) shouldBe false
        }

        test("poll_response_rating: resistance 0..10, unique per (response, option), foreign keys enforced") {
            val poll = newPoll()
            val a = newOption(poll, 0)
            val b = newOption(poll, 1)
            val response = UUID.randomUUID()
            transaction {
                exec("INSERT INTO poll_response (id, poll_id, option_id, weight_ltr) VALUES ('$response', '$poll', NULL, 0.00)")
            }

            fun rating(
                option: UUID,
                resistance: Int,
                responseId: UUID = response,
            ) = "INSERT INTO poll_response_rating (id, response_id, option_id, resistance) VALUES " +
                "('${UUID.randomUUID()}', '$responseId', '$option', $resistance)"
            rejected(rating(a, 11)) shouldBe true
            rejected(rating(a, -1)) shouldBe true
            rejected(rating(a, 0)) shouldBe false
            rejected(rating(a, 5)) shouldBe true // duplicate (response, option)
            rejected(rating(b, 10)) shouldBe false
            rejected(rating(b, 1, responseId = UUID.randomUUID())) shouldBe true // unknown response
        }
    })

class PollKindMigrationTest : PollKindMigrationScenarios(TestDatabase.H2) {
    init {
        test(
            "upgrading a V68 database keeps classic polls: kind SINGLE_CHOICE, is_passive false, explanation NULL, answers intact; a re-run is a no-op",
        ) {
            val jdbcUrl = "jdbc:h2:mem:poll-kind-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

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
            flyway("68").migrate()
            val member = UUID.randomUUID()
            val poll = UUID.randomUUID()
            val option = UUID.randomUUID()
            execute(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$member', 'M', 'm-$member@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            execute(
                "INSERT INTO poll (id, question, status, created_by, created_at) VALUES ('$poll', 'Q?', 'OPEN', '$member', TIMESTAMP '2026-01-01 10:00:00')",
            )
            execute("INSERT INTO poll_option (id, poll_id, position, text) VALUES ('$option', '$poll', 0, 'Ja')")
            execute(
                "INSERT INTO poll_response (id, poll_id, option_id, weight_ltr) VALUES ('${UUID.randomUUID()}', '$poll', '$option', 3.00)",
            )

            (flyway(null).migrate().migrationsExecuted >= 1) shouldBe true
            query("SELECT kind FROM poll WHERE id = '$poll'") {
                it.next()
                it.getString(1) shouldBe "SINGLE_CHOICE"
            }
            query("SELECT is_passive, explanation FROM poll_option WHERE id = '$option'") {
                it.next()
                it.getBoolean(1) shouldBe false
                it.getString(2) shouldBe null
            }
            query("SELECT COUNT(*) FROM poll_response WHERE option_id = '$option' AND weight_ltr = 3.00") {
                it.next()
                it.getInt(1) shouldBe 1
            }
            flyway(null).migrate().migrationsExecuted shouldBe 0
            flyway(null).validate()
        }
    }
}

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PollKindMigrationPostgresTest : PollKindMigrationScenarios(TestDatabase.Postgres())
