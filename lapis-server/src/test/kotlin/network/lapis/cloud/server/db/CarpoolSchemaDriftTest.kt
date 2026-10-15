package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.CarpoolMessageTable
import network.lapis.cloud.server.db.generated.CarpoolMessageThreadTable
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.125 "Mitfahrten". `57-carpool.kuml.kts` is a faithful model of (a) the real, Flyway-migrated
 * schema (`carpool_posting` incl. `meeting_note`, `carpool_participation`, `V58`/`V100`) and (b) the Exposed objects. Mirrors
 * [MemberPhotoSchemaDriftTest]; there was no drift test for the carpool domain before this wave.
 */
class CarpoolSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "57-carpool.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("the model declares exactly the carpool tables and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("carpool_posting", "carpool_participation", "carpool_message_thread", "carpool_message", "member")
        }

        listOf(
            "carpool_posting" to "member",
            "carpool_participation" to "carpool_posting",
            "carpool_message_thread" to "carpool_participation",
            "carpool_message" to "carpool_participation",
        ).forEach { (table, _) ->
            test("$table matches the real migrated schema (columns, nullability)") {
                val entity = model.entities.single { it.name == table }
                val real = transaction { introspect(table) }
                entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
                entity.attributes.forEach { attr ->
                    real.columns.getValue(attr.name!!) shouldBe attr.nullable
                }
            }
        }

        test("the foreign keys of carpool_participation point at carpool_posting and member") {
            val real = transaction { introspect("carpool_participation") }
            real.foreignKeys["posting_id"] shouldBe "carpool_posting"
            real.foreignKeys["person_id"] shouldBe "member"
        }

        test("the foreign keys of the message tables point at carpool_participation") {
            transaction { introspect("carpool_message").foreignKeys["participation_id"] } shouldBe "carpool_participation"
            transaction { introspect("carpool_message_thread").foreignKeys["participation_id"] } shouldBe "carpool_participation"
        }

        test("the entity column-name sets match the hand-written Exposed objects 1:1") {
            model.entities
                .single { it.name == "carpool_posting" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                CarpoolPostingTable.columns.map { it.name }
            model.entities
                .single { it.name == "carpool_participation" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                CarpoolParticipationTable.columns.map { it.name }
            model.entities
                .single { it.name == "carpool_message_thread" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                CarpoolMessageThreadTable.columns.map { it.name }
            model.entities
                .single { it.name == "carpool_message" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                CarpoolMessageTable.columns.map { it.name }
        }
    })

private data class IntrospectedCarpoolTable(
    val columns: Map<String, Boolean>,
    val foreignKeys: Map<String, String>,
)

private fun JdbcTransaction.introspect(tableName: String): IntrospectedCarpoolTable {
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
    return IntrospectedCarpoolTable(columns = nullableByColumn, foreignKeys = fkByColumn)
}
