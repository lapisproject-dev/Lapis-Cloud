package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberNumberSequenceTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle "Digitaler Mitgliedsausweis (PDF)". Verifies that
 * `lapis-server/src/main/kuml/52-member-card.kuml.kts` is a faithful model of both (a) the real,
 * Flyway-migrated H2 schema (`member_number_sequence`/`member_card_code`, `V43__member_card.sql`)
 * and (b) the hand-written `MemberNumberSequenceTable`/`MemberCardCodeTable` Exposed objects.
 * Mirrors [EventVolunteerSchemaDriftTest] exactly.
 */
class MemberCardSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "52-member-card.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly member_number_sequence/member_card_code and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member_number_sequence", "member_card_code", "member")
        }

        test("member_number_sequence table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "member_number_sequence" }
            val real = transaction { introspectMemberCardTable("member_number_sequence") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
        }

        test("member_card_code table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "member_card_code" }
            val real = transaction { introspectMemberCardTable("member_card_code") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["member_id"] shouldBe "member"
        }

        test("member_card_code.code_hash is globally unique") {
            val real = transaction { introspectMemberCardTable("member_card_code") }
            real.uniqueColumns shouldContainExactlyInAnyOrder listOf("code_hash")
        }

        test("member_number_sequence entity column-name set matches the hand-written MemberNumberSequenceTable 1:1") {
            model.entities
                .single { it.name == "member_number_sequence" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MemberNumberSequenceTable.columns.map { it.name }
        }

        test("member_card_code entity column-name set matches the hand-written MemberCardCodeTable 1:1") {
            model.entities
                .single { it.name == "member_card_code" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MemberCardCodeTable.columns.map { it.name }
        }
    })

private data class IntrospectedMemberCardTable(
    val columns: Map<String, IntrospectedMemberCardColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueColumns: Set<String>,
)

private data class IntrospectedMemberCardColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [EventVolunteerSchemaDriftTest]'s own `introspectEventVolunteerTable`. */
private fun JdbcTransaction.introspectMemberCardTable(tableName: String): IntrospectedMemberCardTable {
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

    val uniqueColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumns += rs.getString("column_name")
        }
    }

    return IntrospectedMemberCardTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedMemberCardColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueColumns = uniqueColumns,
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [EventVolunteerSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
