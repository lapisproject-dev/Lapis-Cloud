package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseRatingTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.30 "Umfragen auf LTR-Basis". Verifies that
 * `lapis-server/src/main/kuml/61-poll.kuml.kts` is a faithful model of both (a) the real,
 * Flyway-migrated H2 schema (`V65__polls.sql`) and (b) the hand-written Exposed objects. Mirrors
 * [MemberPublicBioSchemaDriftTest], plus the ANONYMITY invariants of the model: `poll_response` has
 * no member column and no time column, `poll_participation` has no time column.
 */
abstract class PollSchemaDriftScenarios(
    db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        afterSpec { db.deactivate() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "61-poll.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        val expectedForeignKeys =
            mapOf(
                "poll" to mapOf("created_by" to "member", "closed_by" to "member"),
                "poll_option" to mapOf("poll_id" to "poll"),
                "poll_participation" to mapOf("poll_id" to "poll", "member_id" to "member"),
                "poll_response" to mapOf("poll_id" to "poll", "option_id" to "poll_option"),
                "poll_response_rating" to mapOf("response_id" to "poll_response", "option_id" to "poll_option"),
            )
        val exposedTables: Map<String, Table> =
            mapOf(
                "poll" to PollTable,
                "poll_option" to PollOptionTable,
                "poll_participation" to PollParticipationTable,
                "poll_response" to PollResponseTable,
                "poll_response_rating" to PollResponseRatingTable,
            )

        test("model declares exactly the five poll tables and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("poll", "poll_option", "poll_participation", "poll_response", "poll_response_rating", "member")
        }

        expectedForeignKeys.keys.forEach { tableName ->
            test("$tableName: shape (columns, nullability, foreign keys) matches the real migrated schema") {
                val entity = model.entities.single { it.name == tableName }
                val real = transaction { introspectPollTable(tableName) }
                entity.attributes.map { it.name }.toSet() shouldBe real.nullableByColumn.keys
                entity.attributes.forEach { attr ->
                    val nullable = real.nullableByColumn.getValue(attr.name!!)
                    if (nullable !=
                        attr.nullable
                    ) {
                        throw AssertionError("column '$tableName.${attr.name}': nullable $nullable vs model ${attr.nullable}")
                    }
                }
                real.foreignKeys shouldBe expectedForeignKeys.getValue(tableName)
            }

            test("$tableName: entity column-name set matches the hand-written Exposed table 1:1") {
                model.entities
                    .single { it.name == tableName }
                    .attributes
                    .map { it.name } shouldContainExactlyInAnyOrder exposedTables.getValue(tableName).columns.map { it.name }
            }
        }

        test("anonymity invariant: poll_response has NO member column and NO time column") {
            val columns = transaction { introspectPollTable("poll_response") }.nullableByColumn.keys
            columns shouldBe setOf("id", "poll_id", "option_id", "weight_ltr")
            columns.none { it.contains("member") || it.endsWith("_at") || it.contains("time") } shouldBe true
        }

        test("anonymity invariant: poll_response_rating has NO member column and NO time column") {
            val columns = transaction { introspectPollTable("poll_response_rating") }.nullableByColumn.keys
            columns shouldBe setOf("id", "response_id", "option_id", "resistance")
            columns.none { it.contains("member") || it.endsWith("_at") || it.contains("time") } shouldBe true
        }

        test("anonymity invariant: poll_participation has NO time column") {
            val columns = transaction { introspectPollTable("poll_participation") }.nullableByColumn.keys
            columns shouldBe setOf("id", "poll_id", "member_id")
        }
    })

/** The unchanged H2 run (normal `test` task). */
class PollSchemaDriftTest : PollSchemaDriftScenarios(TestDatabase.H2)

/** The same drift checks against a fresh, Flyway-migrated PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PollSchemaDriftPostgresTest : PollSchemaDriftScenarios(TestDatabase.Postgres())

private data class IntrospectedPollTable(
    val nullableByColumn: Map<String, Boolean>,
    val foreignKeys: Map<String, String>,
)

/** Same ANSI `information_schema` walk shape as [MemberPublicBioSchemaDriftTest]'s own introspection. */
private fun JdbcTransaction.introspectPollTable(tableName: String): IntrospectedPollTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName' AND table_schema = CURRENT_SCHEMA",
    ) { rs ->
        while (rs.next()) nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
            AND tc.table_name = kcu.table_name
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
    }
    return IntrospectedPollTable(nullableByColumn = nullableByColumn, foreignKeys = fkByColumn)
}
