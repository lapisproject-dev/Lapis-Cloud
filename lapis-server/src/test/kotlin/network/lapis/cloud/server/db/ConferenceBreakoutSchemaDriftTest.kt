package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Videokonferenzen domain (V1.0, Wave 6 "Breakout-Räume").
 *
 * Verifies that `lapis-server/src/main/kuml/31-conference-breakout.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`conference_breakout_room`/
 * `conference_breakout_assignment`), and (b) the hand-written [ConferenceBreakoutRoomTable]/
 * [ConferenceBreakoutAssignmentTable] Exposed objects. Mirrors [ConferenceSchemaDriftTest]'s shape
 * (two entities in one file, one referencing the other plus both referencing the shared Member
 * stub), with TWO cross-domain stubs (Member, ConferenceRoom) like
 * [ConferenceGuestAccessSchemaDriftTest] establishes.
 */
class ConferenceBreakoutSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "31-conference-breakout.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the two breakout entities plus the Member/ConferenceRoom stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "conference_room", "conference_breakout_room", "conference_breakout_assignment")
        }

        // ── conference_breakout_room ──────────────────────────────────────

        test("conference_breakout_room table shape matches the real migrated schema and ConferenceBreakoutRoomTable 1:1") {
            val entity = model.entities.single { it.name == "conference_breakout_room" }
            val real = transaction { introspectBreakoutTable("conference_breakout_room") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceBreakoutRoomTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["parent_room_id"] shouldBe "conference_room"
            model.entityNameOf(entity.attributeByName("parent_room_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_room"
            entity.attributeByName("parent_room_id")?.nullable shouldBe false

            real.foreignKeys["created_by_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("created_by_member_id")?.nullable shouldBe false

            entity.attributeByName("label")?.nullable shouldBe false
            entity.attributeByName("livekit_room_name")?.nullable shouldBe false
            entity.attributeByName("created_at")?.nullable shouldBe false
            entity.attributeByName("closed_at")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("livekit_room_name"))
        }

        // ── conference_breakout_assignment ────────────────────────────────

        test(
            "conference_breakout_assignment table shape matches the real migrated schema and ConferenceBreakoutAssignmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "conference_breakout_assignment" }
            val real = transaction { introspectBreakoutTable("conference_breakout_assignment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceBreakoutAssignmentTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")

            real.foreignKeys["breakout_room_id"] shouldBe "conference_breakout_room"
            model.entityNameOf(entity.attributeByName("breakout_room_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "conference_breakout_room"
            entity.attributeByName("breakout_room_id")?.nullable shouldBe false

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            entity.attributeByName("assigned_at")?.nullable shouldBe false
            entity.attributeByName("recalled_at")?.nullable shouldBe true
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [ConferenceSchemaDriftTest]'s own private helper. */
private data class IntrospectedBreakoutTable(
    val columns: Map<String, IntrospectedBreakoutColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedBreakoutColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectBreakoutTable(tableName: String): IntrospectedBreakoutTable {
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

    val pkColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            pkColumns += rs.getString("column_name")
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
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedBreakoutColumn(nullable = nullable) }
    return IntrospectedBreakoutTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors ConferenceSchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
