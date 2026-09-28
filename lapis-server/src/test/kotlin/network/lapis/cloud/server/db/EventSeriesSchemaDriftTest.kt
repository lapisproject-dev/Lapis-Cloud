package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- event-series domain (Welle V1.4.37 "Wiederkehrende
 * Veranstaltungen, Folgewelle (Rest)"). Verifies that `lapis-server/src/main/kuml/39-events.kuml.kts`
 * (the "Welle V1.4.37 addendum") is a faithful model of both (a) the real, Flyway-migrated H2
 * schema (`event_series`, plus `event`'s own three new columns, `V56__event_series.sql`) and (b)
 * the hand-written [EventSeriesTable] Exposed object. Mirrors [EventRoomSchemaDriftTest] exactly,
 * except `split_from_series_id` is genuinely self-referential (see that column's own KDoc in both
 * the kUML file header and [EventSeriesTable]) -- so unlike `event.room_id`, the model attribute
 * carries no `fkEntity` and the FK-presence assertion for it is checked directly against SQL
 * introspection, not against the model.
 */
class EventSeriesSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "39-events.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares event_series alongside the pre-existing event/event_registration and their stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("event", "event_registration", "event_series", "member", "event_room", "open_item")
        }

        test("event_series table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "event_series" }
            val real = transaction { introspectEventSeriesTable("event_series") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["created_by"] shouldBe "member"
        }

        test("event_series.split_from_series_id is a nullable self-referential FK, absent from the kUML fkEntity model") {
            val entity = model.entities.single { it.name == "event_series" }
            val attr = entity.attributes.single { it.name == "split_from_series_id" }
            attr.nullable shouldBe true

            val real = transaction { introspectEventSeriesTable("event_series") }
            real.columns.getValue("split_from_series_id").nullable shouldBe true
            real.foreignKeys["split_from_series_id"] shouldBe "event_series"
        }

        test("event_series entity column-name set matches the hand-written EventSeriesTable 1:1") {
            model.entities
                .single { it.name == "event_series" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder EventSeriesTable.columns.map { it.name }
        }

        test(
            "event.series_id is nullable and FK-references event_series (SQL-side; the hand-edited " +
                "EventTable column carries no typed .references())",
        ) {
            val real = transaction { introspectEventSeriesTable("event") }
            real.columns.getValue("series_id").nullable shouldBe true
            real.foreignKeys["series_id"] shouldBe "event_series"
            EventTable.seriesId.name shouldBe "series_id"
        }

        test("event.series_original_start and event.series_detached exist with the right nullability") {
            val real = transaction { introspectEventSeriesTable("event") }
            real.columns.getValue("series_original_start").nullable shouldBe true
            real.columns.getValue("series_detached").nullable shouldBe false
            EventTable.seriesOriginalStart.name shouldBe "series_original_start"
            EventTable.seriesDetached.name shouldBe "series_detached"
        }

        test("uq_event_series_occurrence is a unique index on (series_id, series_original_start)") {
            val real = transaction { introspectEventSeriesTable("event") }
            real.uniqueConstraints shouldContain setOf("series_id", "series_original_start")
        }
    })

private data class IntrospectedEventSeriesTable(
    val columns: Map<String, IntrospectedEventSeriesColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedEventSeriesColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [EventRoomSchemaDriftTest]'s own introspection helper. */
private fun JdbcTransaction.introspectEventSeriesTable(tableName: String): IntrospectedEventSeriesTable {
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

    return IntrospectedEventSeriesTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedEventSeriesColumn(nullable = nullable) },
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
