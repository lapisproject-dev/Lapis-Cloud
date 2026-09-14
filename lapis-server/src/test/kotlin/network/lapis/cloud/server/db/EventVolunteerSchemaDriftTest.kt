package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.server.db.generated.EventVolunteerSignupTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Helfer-/Schichtplanungs-Domäne (Welle V1.4.3.7). Verifies
 * that `lapis-server/src/main/kuml/51-event-volunteer.kuml.kts` is a faithful model of both (a) the
 * real, Flyway-migrated H2 schema (`event_volunteer_shift`/`event_volunteer_signup`,
 * `V39__event_volunteer.sql`) and (b) the hand-written `EventVolunteerShiftTable`/
 * `EventVolunteerSignupTable` Exposed objects. Mirrors [EventRoomSchemaDriftTest] exactly.
 */
class EventVolunteerSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "51-event-volunteer.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly event_volunteer_shift/event_volunteer_signup and their member/event stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("event_volunteer_shift", "event_volunteer_signup", "member", "event")
        }

        test("event_volunteer_shift table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "event_volunteer_shift" }
            val real = transaction { introspectEventVolunteerTable("event_volunteer_shift") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["created_by"] shouldBe "member"
            real.foreignKeys["event_id"] shouldBe "event"
        }

        test("event_volunteer_signup table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "event_volunteer_signup" }
            val real = transaction { introspectEventVolunteerTable("event_volunteer_signup") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["shift_id"] shouldBe "event_volunteer_shift"
        }

        test("event_volunteer_signup carries a UNIQUE (shift_id, active_member_key) index") {
            val real = transaction { introspectEventVolunteerTable("event_volunteer_signup") }
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("shift_id", "active_member_key"))
        }

        test("event_volunteer_shift entity column-name set matches the hand-written EventVolunteerShiftTable 1:1") {
            model.entities
                .single { it.name == "event_volunteer_shift" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder EventVolunteerShiftTable.columns.map { it.name }
        }

        test("event_volunteer_signup entity column-name set matches the hand-written EventVolunteerSignupTable 1:1") {
            model.entities
                .single { it.name == "event_volunteer_signup" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder EventVolunteerSignupTable.columns.map { it.name }
        }
    })

private data class IntrospectedEventVolunteerTable(
    val columns: Map<String, IntrospectedEventVolunteerColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedEventVolunteerColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [EventRoomSchemaDriftTest]'s own `introspectEventRoomTable`. */
private fun JdbcTransaction.introspectEventVolunteerTable(tableName: String): IntrospectedEventVolunteerTable {
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

    return IntrospectedEventVolunteerTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedEventVolunteerColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
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
