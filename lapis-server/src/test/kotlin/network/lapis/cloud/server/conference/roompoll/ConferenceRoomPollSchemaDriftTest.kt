package network.lapis.cloud.server.conference.roompoll

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.KumlModelLoader
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/** ADR-0016 -- room polls (V1.9.304): `82-conference-room-poll.kuml.kts` is a faithful model of the migrated schema and of the Exposed objects. */
class ConferenceRoomPollSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy {
            KumlModelLoader.loadErmModel(
                File(KumlModelLoader.kumlSourceDir, "82-conference-room-poll.kuml.kts"),
            )
        }
        val tables =
            listOf("conference_room_poll", "conference_room_poll_option", "conference_room_poll_voted", "conference_room_poll_answer")

        test("the model declares the four tables and the two id-only stubs") {
            model.entities.map { it.name }.toSet() shouldBe tables.toSet() + setOf("member", "conference_room")
        }

        tables.forEach { table ->
            test("$table matches the real migrated schema (columns, nullability)") {
                val entity = model.entities.single { it.name == table }
                val real = transaction { introspect(table) }
                entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
                entity.attributes.forEach { attr -> real.columns.getValue(attr.name!!) shouldBe attr.nullable }
            }
        }

        test("the foreign keys are exactly the ones the model and the Exposed objects declare") {
            transaction { introspect("conference_room_poll") }.foreignKeys shouldBe mapOf("room_id" to "conference_room")
            transaction { introspect("conference_room_poll_option") }.foreignKeys shouldBe mapOf("poll_id" to "conference_room_poll")
            transaction { introspect("conference_room_poll_voted") }.foreignKeys shouldBe
                mapOf("poll_id" to "conference_room_poll", "member_id" to "member")
            transaction { introspect("conference_room_poll_answer") }.foreignKeys shouldBe
                mapOf("poll_id" to "conference_room_poll", "option_id" to "conference_room_poll_option")
            tables.forEach { table ->
                val entity = model.entities.single { it.name == table }
                entity.attributes
                    .filter { it.foreignKey != null }
                    .associate { attr -> attr.name!! to model.entities.first { it.id == attr.foreignKey!!.targetEntityId }.name } shouldBe
                    transaction { introspect(table) }.foreignKeys
            }
        }

        test("the entity column-name sets match the generated Exposed objects 1:1") {
            mapOf(
                "conference_room_poll" to ConferenceRoomPollTable.columns,
                "conference_room_poll_option" to ConferenceRoomPollOptionTable.columns,
                "conference_room_poll_voted" to ConferenceRoomPollVotedTable.columns,
                "conference_room_poll_answer" to ConferenceRoomPollAnswerTable.columns,
            ).forEach { (name, columns) ->
                model.entities
                    .single { it.name == name }
                    .attributes
                    .map { it.name } shouldContainExactlyInAnyOrder columns.map { it.name }
            }
        }

        test("the weight column is DECIMAL(18,2) like the ledger") {
            transaction { ConferenceRoomPollAnswerTable.weightLtr.columnType.sqlType() } shouldBe "DECIMAL(18, 2)"
        }
    })

private data class IntrospectedPollTable(
    val columns: Map<String, Boolean>,
    val foreignKeys: Map<String, String>,
)

private fun JdbcTransaction.introspect(tableName: String): IntrospectedPollTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
    }
    return IntrospectedPollTable(columns = nullableByColumn, foreignKeys = fkByColumn)
}
