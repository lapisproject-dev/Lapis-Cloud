package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Price-Oracle fuer die Anker-Bindung domain (V0.6.5).
 *
 * Verifies that `lapis-server/src/main/kuml/19-price-oracle.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`price_oracle_config`/`price_oracle_conversion`), and
 * (b) the hand-written [PriceOracleConfigTable]/[PriceOracleConversionTable] Exposed objects.
 * Mirrors [PeerTransferSchemaDriftTest]'s shape -- see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 *
 * The domain-specific structural points this test pins: `price_oracle_config` has NO FK to member
 * at all (pure scalar policy row); `price_oracle_conversion` has exactly TWO FKs to member
 * (`member_id` via a real UML association, `created_by` via a plain «Column» attribute) plus one
 * deliberately non-FK `ltr_ledger_entry_id` column -- see the `.kuml.kts` file header for why.
 */
class PriceOracleSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "19-price-oracle.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the two price-oracle entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "price_oracle_config", "price_oracle_conversion")
        }

        // ── price_oracle_config ───────────────────────────────────────────────

        test("price_oracle_config table shape matches the real migrated schema and PriceOracleConfigTable 1:1, no FK to member") {
            val entity = model.entities.single { it.name == "price_oracle_config" }
            val real = transaction { introspectPriceOracleTable("price_oracle_config") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PriceOracleConfigTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            real.foreignKeys.isEmpty() shouldBe true

            entity.attributeByName("anchor_units_per_ltr")?.type shouldBe ErmDataType.Decimal(38, 18)
            entity.attributeByName("anchor_asset")?.type shouldBe
                ErmDataType.Enum(
                    name = "AnchorAsset",
                    values = listOf("BITCOIN_BTC", "GOLD_XAU", "FIAT"),
                    externalFqName = "network.lapis.cloud.shared.domain.AnchorAsset",
                )
        }

        // ── price_oracle_conversion ───────────────────────────────────────────

        test("price_oracle_conversion table shape matches the real migrated schema and PriceOracleConversionTable 1:1") {
            val entity = model.entities.single { it.name == "price_oracle_conversion" }
            val real = transaction { introspectPriceOracleTable("price_oracle_conversion") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PriceOracleConversionTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            // member_id: real UML association (association-derived default matches).
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            // created_by: plain «Column».fkEntity -- see file header "Two separate member FKs".
            real.foreignKeys["created_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("created_by")?.nullable shouldBe true

            // ltr_ledger_entry_id: deliberately NOT a FK -- see file header.
            (real.foreignKeys.containsKey("ltr_ledger_entry_id")) shouldBe false
            entity.attributeByName("ltr_ledger_entry_id")?.foreignKey shouldBe null
            entity.attributeByName("ltr_ledger_entry_id")?.nullable shouldBe false

            entity.attributeByName("donation_amount")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("ltr_minted")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("anchor_price")?.type shouldBe ErmDataType.Decimal(38, 18)
            entity.attributeByName("anchor_units_per_ltr")?.type shouldBe ErmDataType.Decimal(38, 18)

            entity.attributeByName("anchor_asset")?.type shouldBe
                ErmDataType.Enum(
                    name = "AnchorAsset",
                    values = listOf("BITCOIN_BTC", "GOLD_XAU", "FIAT"),
                    externalFqName = "network.lapis.cloud.shared.domain.AnchorAsset",
                )
            entity.attributeByName("price_status")?.type shouldBe
                ErmDataType.Enum(
                    name = "PriceStatus",
                    values = listOf("LIVE", "DEGRADED", "CACHED", "DEFERRED"),
                    externalFqName = "network.lapis.cloud.shared.domain.PriceStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [PeerTransferSchemaDriftTest]'s own private helper. */
private data class IntrospectedPriceOracleTable(
    val columns: Map<String, IntrospectedPriceOracleColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedPriceOracleColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectPriceOracleTable(tableName: String): IntrospectedPriceOracleTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedPriceOracleColumn(nullable = nullable) }
    return IntrospectedPriceOracleTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
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
