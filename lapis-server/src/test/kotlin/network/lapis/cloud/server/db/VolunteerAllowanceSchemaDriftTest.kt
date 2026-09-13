package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG) -- mirrors
 * [TravelExpenseSchemaDriftTest]'s structure exactly for the two new entities.
 */
class VolunteerAllowanceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "46-volunteer-allowance.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the two volunteer-allowance entities and the member/journal_entry stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("volunteer_allowance_payment", "volunteer_allowance_self_declaration", "member", "journal_entry")
        }

        test("volunteer_allowance_payment table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "volunteer_allowance_payment" }
            val real = transaction { introspectVaTable("volunteer_allowance_payment") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["subject_member_id"] shouldBe "member"
            real.foreignKeys["requested_by"] shouldBe "member"
            real.foreignKeys["decided_by"] shouldBe "member"
            real.foreignKeys["posted_journal_entry_id"] shouldBe "journal_entry"
            real.foreignKeys["cap_acknowledged_by"] shouldBe "member"
        }

        test("volunteer_allowance_self_declaration table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "volunteer_allowance_self_declaration" }
            val real = transaction { introspectVaTable("volunteer_allowance_self_declaration") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["recorded_by"] shouldBe "member"
        }

        test("uq_vap_posted_journal_entry is a PLAIN (non-partial) unique index, uq_vasd_member_category_year is a 3-column unique") {
            val paymentReal = transaction { introspectVaTable("volunteer_allowance_payment") }
            paymentReal.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("posted_journal_entry_id")
            val declarationReal = transaction { introspectVaTable("volunteer_allowance_self_declaration") }
            declarationReal.uniqueColumnSets.any { it == setOf("member_id", "category", "calendar_year") } shouldBe true
        }

        test("generated *Table objects match the modelled column-name sets 1:1") {
            model.entities
                .single { it.name == "volunteer_allowance_payment" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                VolunteerAllowancePaymentTable.columns.map { it.name }
            model.entities
                .single { it.name == "volunteer_allowance_self_declaration" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                VolunteerAllowanceSelfDeclarationTable.columns.map { it.name }
        }

        test("category/status/source are modelled as real ErmDataType.Enum columns, literal order pinned") {
            val paymentEntity = model.entities.single { it.name == "volunteer_allowance_payment" }
            paymentEntity.attributeByName("category")?.type shouldBe
                ErmDataType.Enum(
                    name = "VolunteerAllowanceCategory",
                    values = listOf("INSTRUCTOR", "HONORARY"),
                    externalFqName = "network.lapis.cloud.shared.domain.VolunteerAllowanceCategory",
                )
            paymentEntity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "VolunteerAllowancePaymentStatus",
                    values = listOf("DRAFT", "REQUESTED", "APPROVED", "REJECTED", "EXECUTED", "WITHDRAWN"),
                    externalFqName = "network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus",
                )
            val declarationEntity = model.entities.single { it.name == "volunteer_allowance_self_declaration" }
            declarationEntity.attributeByName("category")?.type shouldBe
                ErmDataType.Enum(
                    name = "VolunteerAllowanceCategory",
                    values = listOf("INSTRUCTOR", "HONORARY"),
                    externalFqName = "network.lapis.cloud.shared.domain.VolunteerAllowanceCategory",
                )
            declarationEntity.attributeByName("source")?.type shouldBe
                ErmDataType.Enum(
                    name = "VolunteerAllowanceDeclarationSource",
                    values = listOf("IN_APP", "ON_PAPER"),
                    externalFqName = "network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource",
                )
        }

        test("subject_member_id/category/status/amount/activity_description/payment_date/created_at/requested_by are NOT NULL") {
            val entity = model.entities.single { it.name == "volunteer_allowance_payment" }
            listOf(
                "subject_member_id",
                "category",
                "status",
                "amount",
                "activity_description",
                "payment_date",
                "created_at",
                "requested_by",
            ).forEach { name ->
                withClue(clue = "column '$name'") { entity.attributeByName(name)?.nullable shouldBe false }
            }
        }
    })

private data class IntrospectedVaTable(
    val columns: Map<String, IntrospectedVaColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueSingleColumns: List<String>,
    val uniqueColumnSets: List<Set<String>>,
)

private data class IntrospectedVaColumn(
    val nullable: Boolean,
)

/** Mirrors [TravelExpenseSchemaDriftTest]'s private `introspectTeTable`, generalized to any table name. */
private fun JdbcTransaction.introspectVaTable(tableName: String): IntrospectedVaTable {
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
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedVaColumn(nullable = nullable) }
    val uniqueSingleColumns = uniqueColumnsByConstraint.values.filter { it.size == 1 }.map { it.single() }
    val uniqueColumnSets = uniqueColumnsByConstraint.values.map { it.toSet() }
    return IntrospectedVaTable(
        columns = columns,
        foreignKeys = fkByColumn,
        uniqueSingleColumns = uniqueSingleColumns,
        uniqueColumnSets = uniqueColumnSets,
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [TravelExpenseSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
