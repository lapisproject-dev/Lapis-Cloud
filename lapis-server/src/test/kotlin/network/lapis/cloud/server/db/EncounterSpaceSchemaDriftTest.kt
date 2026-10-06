package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.61 "Begegnungsraum". Verifies that
 * `lapis-server/src/main/kuml/65-encounter-space.kuml.kts` is a faithful model of both (a) the real, Flyway-migrated H2 schema
 * (`V73__encounter_space.sql`) and (b) the three hand-written Exposed objects. Mirrors [MemberStatusHistorySchemaDriftTest].
 */
class EncounterSpaceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "65-encounter-space.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the three encounter tables and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("encounter_space", "encounter_space_role", "encounter_consent_acknowledgment", "member")
        }

        listOf(
            Triple("encounter_space", EncounterSpaceTable.columns.map { it.name }, setOf("id")),
            Triple("encounter_space_role", EncounterSpaceRoleTable.columns.map { it.name }, setOf("space_id", "member_id")),
            Triple(
                "encounter_consent_acknowledgment",
                EncounterConsentAcknowledgmentTable.columns.map { it.name },
                setOf("member_id", "consent_version"),
            ),
        ).forEach { (table, exposedColumns, primaryKey) ->
            test("$table matches the real migrated schema and its Exposed object 1:1") {
                val entity = model.entities.single { it.name == table }
                val real = transaction { introspectEncounterTable(table) }
                entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
                entity.attributes.forEach { attr -> real.columns.getValue(attr.name!!) shouldBe attr.nullable }
                entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
                real.primaryKeyColumns shouldBe primaryKey
                entity.attributes
                    .filter { it.primaryKey }
                    .map { it.name }
                    .toSet() shouldBe primaryKey
            }
        }

        test(
            "foreign keys: space.created_by -> member, role.space_id -> encounter_space, role.member_id -> member, consent.member_id -> member",
        ) {
            transaction { introspectEncounterTable("encounter_space") }.foreignKeys["created_by_member_id"] shouldBe "member"
            val role = transaction { introspectEncounterTable("encounter_space_role") }
            role.foreignKeys["space_id"] shouldBe "encounter_space"
            role.foreignKeys["member_id"] shouldBe "member"
            transaction { introspectEncounterTable("encounter_consent_acknowledgment") }.foreignKeys["member_id"] shouldBe "member"
        }

        test("privacy by schema: the consent proof has NO room/space reference and NO timestamp, only a date") {
            val real = transaction { introspectEncounterTable("encounter_consent_acknowledgment") }
            real.columns.keys shouldBe setOf("member_id", "consent_version", "consent_sha256", "acknowledged_on")
            real.foreignKeys.values.toSet() shouldBe setOf("member")
            real.columnTypes.getValue("acknowledged_on").uppercase() shouldBe "DATE"
        }
    })

private data class IntrospectedEncounterTable(
    /** column name -> nullable */
    val columns: Map<String, Boolean>,
    val columnTypes: Map<String, String>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private fun JdbcTransaction.introspectEncounterTable(tableName: String): IntrospectedEncounterTable {
    val nullable = mutableMapOf<String, Boolean>()
    val types = mutableMapOf<String, String>()
    exec(
        "SELECT column_name, is_nullable, data_type FROM information_schema.columns WHERE table_name = '$tableName'",
    ) { rs ->
        while (rs.next()) {
            nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
            types[rs.getString("column_name")] = rs.getString("data_type")
        }
    }
    val fk = mutableMapOf<String, String>()
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
        while (rs.next()) fk[rs.getString("fk_column")] = rs.getString("ref_table")
    }
    val pk = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) pk += rs.getString("column_name")
    }
    return IntrospectedEncounterTable(columns = nullable, columnTypes = types, foreignKeys = fk, primaryKeyColumns = pk)
}
