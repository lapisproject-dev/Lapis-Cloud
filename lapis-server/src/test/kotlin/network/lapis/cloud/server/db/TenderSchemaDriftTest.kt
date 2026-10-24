package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- tender domain (V1.9.201). Verifies that `74-tender.kuml.kts` is a faithful model of the real,
 * Flyway-migrated H2 schema (`tender`, `tender_bid`, `tender_award`, `tender_acceptance`) and of the Exposed objects, column for column.
 */
class TenderSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "74-tender.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        fun check(
            table: String,
            exposed: List<String>,
        ) {
            val entity = model.entities.single { it.name == table }
            val real = transaction { introspectTenderTable(table) }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of $table") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposed
            real.primaryKeyColumns shouldBe setOf("id")
            // every foreign key of the model is a real one in the database and the other way round
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to
                    model.entityNameOf(
                        it.foreignKey!!.targetEntityId,
                    )
            } shouldBe
                real.foreignKeys
        }

        test("the model declares the four tender entities plus the Member, Motion and Resolution stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "motion", "resolution", "tender", "tender_bid", "tender_award", "tender_acceptance")
        }

        test("tender matches the migrated schema and TenderTable 1:1") {
            check("tender", TenderTable.columns.map { it.name })
            val entity = model.entities.single { it.name == "tender" }
            entity.attributeByName("deadline")?.nullable shouldBe false
            entity.attributeByName("award_motion_id")?.nullable shouldBe true
            entity.attributeByName("payout_ledger_entry_id")?.nullable shouldBe true
            entity.attributeByName("payout_ledger_entry_id")?.foreignKey shouldBe null // reserved for wave 3, deliberately no foreign key
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "TenderStatus",
                    values = listOf("DRAFT", "PUBLISHED", "CLOSED", "AWARDED", "COMPLETED", "CANCELLED"),
                    externalFqName = "network.lapis.cloud.shared.domain.TenderStatus",
                )
            // sized by an explicit sqlType (VARCHAR(16), room for later formats), so the model type is a Varchar; the Kotlin type is the enum
            entity.attributeByName("description_format")?.type shouldBe ErmDataType.Varchar(length = 16)
            entity.attributeByName("description_format")?.nullable shouldBe false
            entity.attributeByName("bid_visibility")?.type shouldBe
                ErmDataType.Enum(
                    name = "TenderBidVisibility",
                    values = listOf("HIDDEN_UNTIL_DEADLINE", "OPEN", "OWN_ONLY"),
                    externalFqName = "network.lapis.cloud.shared.domain.TenderBidVisibility",
                )
            transaction { introspectTenderTable("tender") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("award_motion_id"))
        }

        test("tender_bid matches the migrated schema and TenderBidTable 1:1; one bid per tender and bidder") {
            check("tender_bid", TenderBidTable.columns.map { it.name })
            model.entities
                .single { it.name == "tender_bid" }
                .attributeByName("price")
                ?.type shouldBe ErmDataType.Decimal(18, 2)
            transaction { introspectTenderTable("tender_bid") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("tender_id", "bidder_member_id"))
        }

        test("tender_award matches the migrated schema and TenderAwardTable 1:1; one award per tender and per bid") {
            check("tender_award", TenderAwardTable.columns.map { it.name })
            transaction { introspectTenderTable("tender_award") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("tender_id"), setOf("bid_id"))
        }

        test("tender_acceptance matches the migrated schema and TenderAcceptanceTable 1:1") {
            check("tender_acceptance", TenderAcceptanceTable.columns.map { it.name })
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedTenderTable(
    val columns: Map<String, IntrospectedTenderColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedTenderColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectTenderTable(tableName: String): IntrospectedTenderTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedTenderColumn(nullable = nullable) }
    return IntrospectedTenderTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
