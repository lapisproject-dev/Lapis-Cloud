package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- room domain (Welle V1.4.3.4 "Raumverwaltung für
 * Veranstaltungen"). Verifies that `lapis-server/src/main/kuml/49-event-room.kuml.kts` is a
 * faithful model of both (a) the real, Flyway-migrated H2 schema (`event_room`, plus `event`'s own
 * new `room_id` column, `V36__event_rooms.sql`) and (b) the hand-written `EventRoomTable` Exposed
 * object. Mirrors [BankAccountSchemaDriftTest] exactly.
 */
class EventRoomSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "49-event-room.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly event_room and its member/event stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("event_room", "member", "event")
        }

        test("event_room table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "event_room" }
            val real = transaction { introspectEventRoomTable("event_room") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["created_by"] shouldBe "member"
        }

        test("event_room.name carries its own UNIQUE constraint") {
            val real = transaction { introspectEventRoomTable("event_room") }
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("name"))
        }

        test("event_room entity column-name set matches the hand-written EventRoomTable 1:1") {
            model.entities
                .single { it.name == "event_room" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder EventRoomTable.columns.map { it.name }
        }

        test("event.room_id is nullable and FK-references event_room") {
            val real = transaction { introspectEventRoomTable("event") }
            real.columns.getValue("room_id").nullable shouldBe true
            real.foreignKeys["room_id"] shouldBe "event_room"
            EventTable.roomId.name shouldBe "room_id"
        }
    })

private data class IntrospectedEventRoomTable(
    val columns: Map<String, IntrospectedEventRoomColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedEventRoomColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [BankAccountSchemaDriftTest]'s own `introspectBankAccountTable`. */
private fun JdbcTransaction.introspectEventRoomTable(tableName: String): IntrospectedEventRoomTable {
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

    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name AS name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint.getOrPut(rs.getString("name")) { mutableSetOf() }.add(rs.getString("column_name"))
        }
    }

    return IntrospectedEventRoomTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedEventRoomColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [BankAccountSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
