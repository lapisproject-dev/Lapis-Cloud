package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.server.db.generated.LupaBbSyncSuppressionTable
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- LupaBB sync (V1.9.226). Verifies that `77-lupabb-sync.kuml.kts` (and the two new columns of `member` in
 * `00-foundation.kuml.kts`) are a faithful model of the real, Flyway-migrated H2 schema and of the four Exposed objects, column for column.
 */
class LupaBbSyncSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "77-lupabb-sync.kuml.kts")) }
        val foundation: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "00-foundation.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares the four tables plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "member_lupabb_profile", "lupabb_sync_config", "lupabb_sync_run", "lupabb_sync_suppression")
        }

        fun check(
            table: String,
            exposedColumns: List<String>,
            expectedForeignKeys: Map<String, String>,
            pk: Set<String>,
        ) {
            val entity = model.entities.single { it.name == table }
            val real = transaction { introspectLupaBbTable(table) }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of $table") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
            real.primaryKeyColumns shouldBe pk
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe expectedForeignKeys
            real.foreignKeys shouldBe expectedForeignKeys
        }

        test("member_lupabb_profile matches the migrated schema and its Exposed object 1:1") {
            check(
                "member_lupabb_profile",
                MemberLupaBbProfileTable.columns.map { it.name },
                mapOf("member_id" to "member"),
                setOf("member_id"),
            )
        }

        test("lupabb_sync_config matches the migrated schema and its Exposed object 1:1") {
            check("lupabb_sync_config", LupaBbSyncConfigTable.columns.map { it.name }, mapOf("set_by" to "member"), setOf("id"))
        }

        test("lupabb_sync_run matches the migrated schema and its Exposed object 1:1") {
            check("lupabb_sync_run", LupaBbSyncRunTable.columns.map { it.name }, mapOf("started_by" to "member"), setOf("id"))
        }

        test("lupabb_sync_suppression matches the migrated schema and its Exposed object 1:1") {
            check("lupabb_sync_suppression", LupaBbSyncSuppressionTable.columns.map { it.name }, emptyMap(), setOf("person_number_hmac"))
        }

        test("the two LupaBB columns of member exist in the model, the table and the Exposed object, nullable") {
            val member = foundation.entities.single { it.name == "member" }
            val real = transaction { introspectLupaBbTable("member") }
            for (column in listOf("lupabb_person_number", "lupabb_missing_since")) {
                (member.attributes.firstOrNull { it.name == column }?.nullable) shouldBe true
                real.columns.getValue(column).nullable shouldBe true
            }
            MemberTable.columns.map { it.name }.containsAll(listOf("lupabb_person_number", "lupabb_missing_since")) shouldBe true
        }

        test("a person number is unique on member; NULL never collides") {
            transaction { introspectLupaBbTable("member") }.uniqueConstraints.contains(setOf("lupabb_person_number")) shouldBe true
        }

        test("the profile model keeps the attributes in the order of the table") {
            model.entities
                .single { it.name == "member_lupabb_profile" }
                .attributes
                .map { it.name } shouldContainExactly
                MemberLupaBbProfileTable.columns.map { it.name }
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedLupaBbTable(
    val columns: Map<String, IntrospectedLupaBbColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedLupaBbColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectLupaBbTable(tableName: String): IntrospectedLupaBbTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedLupaBbColumn(nullable = nullable) }
    return IntrospectedLupaBbTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
