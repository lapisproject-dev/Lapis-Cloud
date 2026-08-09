package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Videokonferenzen domain (V1.0, Wave 1).
 *
 * Verifies that `lapis-server/src/main/kuml/27-conference.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`conference_room`/`conference_participation`), and (b)
 * the hand-written [ConferenceRoomTable]/[ConferenceParticipationTable] Exposed objects. Mirrors
 * [SessionSchemaDriftTest]'s shape (a Member-stub domain with real FKs), extended for the second FK
 * `conference_participation.room_id -> conference_room` — the first domain in this codebase's test
 * suite where two entities declared in the SAME file reference each other, not just both
 * referencing the shared Member stub.
 */
class ConferenceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "27-conference.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the two conference entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "conference_room", "conference_participation")
        }

        // ── conference_room ───────────────────────────────────────────────

        test("conference_room table shape matches the real migrated schema and ConferenceRoomTable 1:1") {
            val entity = model.entities.single { it.name == "conference_room" }
            val real = transaction { introspectConferenceTable("conference_room") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceRoomTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["created_by_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("created_by_member_id")?.nullable shouldBe false

            entity.attributeByName("ended_at")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("livekit_room_name"))
        }

        // ── conference_participation ──────────────────────────────────────

        test("conference_participation table shape matches the real migrated schema and ConferenceParticipationTable 1:1") {
            val entity = model.entities.single { it.name == "conference_participation" }
            val real = transaction { introspectConferenceTable("conference_participation") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ConferenceParticipationTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")

            real.foreignKeys["room_id"] shouldBe "conference_room"
            model.entityNameOf(entity.attributeByName("room_id")?.foreignKey?.targetEntityId ?: "") shouldBe "conference_room"
            entity.attributeByName("room_id")?.nullable shouldBe false

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            entity.attributeByName("left_at")?.nullable shouldBe true

            // Literal order is load-bearing -- see 27-conference.kuml.kts file header.
            entity.attributeByName("role")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConferenceRole",
                    values = listOf("MODERATOR", "PARTICIPANT"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConferenceRole",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [SessionSchemaDriftTest]'s own private helper. */
private data class IntrospectedConferenceTable(
    val columns: Map<String, IntrospectedConferenceColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedConferenceColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectConferenceTable(tableName: String): IntrospectedConferenceTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedConferenceColumn(nullable = nullable) }
    return IntrospectedConferenceTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors SchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
