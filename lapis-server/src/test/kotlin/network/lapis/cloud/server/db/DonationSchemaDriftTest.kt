package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.DonationTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- the donation entity (V1.9.328, V174). Verifies that `84-donation.kuml.kts` is a faithful model of the real,
 * Flyway-migrated H2 schema and of [DonationTable], column for column. The composite UNIQUE uq_donation_link and the CHECKs are not expressible in the
 * ERM profile; they are pinned by DonationEntityMigrationTest.
 */
class DonationSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "84-donation.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares the donation entity plus the Member and PaymentTransaction stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "payment_transaction", "donation")
        }

        test("donation matches the migrated schema and DonationTable 1:1") {
            val entity = model.entities.single { it.name == "donation" }
            val real = transaction { introspectDonationTable("donation") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder DonationTable.columns.map { it.name }
            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe real.foreignKeys
            real.foreignKeys shouldBe
                mapOf("payment_transaction_id" to "payment_transaction", "member_id" to "member", "created_by" to "member")
            entity.attributeByName("created_by")?.nullable shouldBe true
            entity.attributeByName("amount")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("currency")?.type shouldBe ErmDataType.Varchar(length = 3)
            real.uniqueConstraints.any { it == setOf("payment_transaction_id") } shouldBe true
        }
    })

private data class IntrospectedDonationTable(
    val columns: Map<String, IntrospectedDonationColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedDonationColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectDonationTable(tableName: String): IntrospectedDonationTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedDonationColumn(nullable = nullable) }
    return IntrospectedDonationTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
