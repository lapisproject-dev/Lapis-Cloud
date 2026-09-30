package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.4 "private Hintergrundbild-Uploads für
 * Videokonferenzen". Verifies that `lapis-server/src/main/kuml/59-member-photo.kuml.kts`
 * is a faithful model of both (a) the real, Flyway-migrated H2 schema
 * (`member_photo`, `V61__member_photo.sql`) and (b) the
 * hand-written `MemberPhotoTable` Exposed object. Mirrors [MemberCardSchemaDriftTest]
 * exactly.
 */
class MemberPhotoSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "59-member-photo.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly member_photo and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member_photo", "member")
        }

        test("member_photo table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "member_photo" }
            val real = transaction { introspectMemberPhotoTable("member_photo") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["member_id"] shouldBe "member"
        }

        test("member_photo entity column-name set matches the hand-written MemberPhotoTable 1:1") {
            model.entities
                .single { it.name == "member_photo" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MemberPhotoTable.columns.map { it.name }
        }
    })

private data class IntrospectedMemberPhotoTable(
    val columns: Map<String, IntrospectedMemberPhotoColumn>,
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedMemberPhotoColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [MemberCardSchemaDriftTest]'s own `introspectMemberCardTable`. */
private fun JdbcTransaction.introspectMemberPhotoTable(tableName: String): IntrospectedMemberPhotoTable {
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

    return IntrospectedMemberPhotoTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedMemberPhotoColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [MemberCardSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
