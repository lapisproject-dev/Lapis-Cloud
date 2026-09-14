package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventCateringOrderTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- catering domain (Welle V1.4.3.5 "Catering-Management für
 * Veranstaltungen"). Verifies that `lapis-server/src/main/kuml/50-event-catering.kuml.kts` is a
 * faithful model of both (a) the real, Flyway-migrated H2 schema (`event_catering_order`,
 * `V37__event_catering.sql`) and (b) the hand-written `EventCateringOrderTable` Exposed object.
 * Mirrors [EventRoomSchemaDriftTest] exactly.
 */
class EventCateringSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "50-event-catering.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly event_catering_order and its member/event stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("event_catering_order", "member", "event")
        }

        test("event_catering_order table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "event_catering_order" }
            val real = transaction { introspectEventCateringOrderTable("event_catering_order") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["created_by"] shouldBe "member"
            real.foreignKeys["event_id"] shouldBe "event"
        }

        test("event_catering_order entity column-name set matches the hand-written EventCateringOrderTable 1:1") {
            model.entities
                .single { it.name == "event_catering_order" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder EventCateringOrderTable.columns.map { it.name }
        }
    })

private data class IntrospectedEventCateringOrderTable(
    val columns: Map<String, IntrospectedEventCateringOrderColumn>,
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedEventCateringOrderColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [EventRoomSchemaDriftTest]'s own
 * `introspectEventRoomTable`. */
private fun JdbcTransaction.introspectEventCateringOrderTable(tableName: String): IntrospectedEventCateringOrderTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    return IntrospectedEventCateringOrderTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedEventCateringOrderColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [EventRoomSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
