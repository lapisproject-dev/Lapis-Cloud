package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- bulletin board (V1.9.297). `81-bulletin-board.kuml.kts` is a faithful model of (a) the real,
 * Flyway-migrated schema (`bulletin_notice`, `bulletin_notice_boost`, `V149`) and (b) the Exposed objects, column for column, including every
 * foreign key. Mirrors [CarpoolSchemaDriftTest].
 */
class BulletinBoardSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "81-bulletin-board.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares the two bulletin tables and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("bulletin_notice", "bulletin_notice_boost", "member")
        }

        listOf("bulletin_notice", "bulletin_notice_boost").forEach { table ->
            test("$table matches the real migrated schema (columns, nullability)") {
                val entity = model.entities.single { it.name == table }
                val real = transaction { introspect(table) }
                entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
                entity.attributes.forEach { attr -> real.columns.getValue(attr.name!!) shouldBe attr.nullable }
            }
        }

        test("the foreign keys are exactly the ones the model and the Exposed objects declare") {
            val notice = transaction { introspect("bulletin_notice") }.foreignKeys
            notice shouldBe mapOf("author_member_id" to "member", "pinned_by" to "member", "removed_by" to "member")
            val boost = transaction { introspect("bulletin_notice_boost") }.foreignKeys
            boost shouldBe mapOf("notice_id" to "bulletin_notice", "member_id" to "member")
            listOf("bulletin_notice", "bulletin_notice_boost").forEach { table ->
                val entity = model.entities.single { it.name == table }
                entity.attributes
                    .filter { it.foreignKey != null }
                    .associate { it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId) } shouldBe
                    transaction { introspect(table) }.foreignKeys
            }
        }

        test("the entity column-name sets match the hand-written Exposed objects 1:1") {
            model.entities
                .single { it.name == "bulletin_notice" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                BulletinNoticeTable.columns.map { it.name }
            model.entities
                .single { it.name == "bulletin_notice_boost" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                BulletinNoticeBoostTable.columns.map { it.name }
        }

        test("the money column is DECIMAL(18,2) like the ledger and social_post_boost") {
            transaction {
                BulletinNoticeBoostTable.amountLtr.columnType.sqlType()
            } shouldBe "DECIMAL(18, 2)"
        }
    })

private data class IntrospectedBulletinTable(
    val columns: Map<String, Boolean>,
    val foreignKeys: Map<String, String>,
)

private fun JdbcTransaction.introspect(tableName: String): IntrospectedBulletinTable {
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
    return IntrospectedBulletinTable(columns = nullableByColumn, foreignKeys = fkByColumn)
}
