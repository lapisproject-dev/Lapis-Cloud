package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
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

        test("model declares exactly the five price-oracle entities plus the Member and PaymentTransaction stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "payment_transaction",
                    "donation",
                    "price_oracle_config",
                    "price_oracle_conversion",
                    "price_oracle_snapshot",
                    "price_oracle_snapshot_source",
                    "price_oracle_reference_rate",
                )
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
            real.foreignKeys["member_id"] shouldBe setOf("member", "donation")
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            // created_by: plain «Column».fkEntity -- see file header "Two separate member FKs".
            real.foreignKeys["created_by"] shouldBe setOf("member")
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

        // ── price_oracle_snapshot ─────────────────────────────────────────────

        test("price_oracle_snapshot table shape matches the real migrated schema and PriceOracleSnapshotTable 1:1, no FK to member") {
            val entity = model.entities.single { it.name == "price_oracle_snapshot" }
            val real = transaction { introspectPriceOracleTable("price_oracle_snapshot") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PriceOracleSnapshotTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            real.foreignKeys.isEmpty() shouldBe true

            entity.attributeByName("median_price")?.type shouldBe ErmDataType.Decimal(38, 18)
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

        // ── V171 (V1.9.324): reference-date rates ─────────────────────────────

        test("price_oracle_config has the conversion_rate_basis switch with the two literals CURRENT, REFERENCE_DATE") {
            val entity = model.entities.single { it.name == "price_oracle_config" }
            entity.attributeByName("conversion_rate_basis")?.type shouldBe
                ErmDataType.Enum(
                    name = "ConversionRateBasis",
                    values = listOf("CURRENT", "REFERENCE_DATE"),
                    externalFqName = "network.lapis.cloud.shared.domain.ConversionRateBasis",
                )
            entity.attributeByName("conversion_rate_basis")?.nullable shouldBe false
        }

        test("price_oracle_conversion carries the rate provenance columns; snapshot and rate are real FKs, the rest nullable") {
            val entity = model.entities.single { it.name == "price_oracle_conversion" }
            val real = transaction { introspectPriceOracleTable("price_oracle_conversion") }
            real.foreignKeys["snapshot_id"] shouldBe setOf("price_oracle_snapshot")
            real.foreignKeys["reference_rate_id"] shouldBe setOf("price_oracle_reference_rate")
            model.entityNameOf(entity.attributeByName("snapshot_id")?.foreignKey?.targetEntityId ?: "") shouldBe "price_oracle_snapshot"
            model.entityNameOf(entity.attributeByName("reference_rate_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "price_oracle_reference_rate"
            listOf("reference_date", "effective_date", "fallback_reason", "snapshot_id", "reference_rate_id", "quorum_required").forEach {
                withClue(clue = "column '$it'") { entity.attributeByName(it)?.nullable shouldBe true }
            }
            entity.attributeByName("rate_basis")?.nullable shouldBe false
            entity.attributeByName("rate_basis")?.type shouldBe
                ErmDataType.Enum(
                    name = "ReferenceRateBasis",
                    values = listOf("CURRENT", "SNAPSHOT", "MANUAL"),
                    externalFqName = "network.lapis.cloud.shared.domain.ReferenceRateBasis",
                )
        }

        test("price_oracle_conversion links the converted payment (real FK) and the server-derived date source (V173)") {
            val entity = model.entities.single { it.name == "price_oracle_conversion" }
            val real = transaction { introspectPriceOracleTable("price_oracle_conversion") }
            real.foreignKeys["payment_transaction_id"] shouldBe setOf("payment_transaction", "donation")
            model.entityNameOf(entity.attributeByName("payment_transaction_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "payment_transaction"
            entity.attributeByName("payment_transaction_id")?.nullable shouldBe true
            entity.attributeByName("reference_date_source")?.nullable shouldBe true
            entity.attributeByName("reference_date_source")?.type shouldBe
                ErmDataType.Enum(
                    name = "ReferenceDateSource",
                    values = listOf("BANK_BOOKING", "MANUAL"),
                    externalFqName = "network.lapis.cloud.shared.domain.ReferenceDateSource",
                )
        }

        test(
            "price_oracle_conversion links the donation (V174): simple FK on donation_id, composite FK over amount/currency/member/payment",
        ) {
            val entity = model.entities.single { it.name == "price_oracle_conversion" }
            val real = transaction { introspectPriceOracleTable("price_oracle_conversion") }
            real.foreignKeys["donation_id"] shouldBe setOf("donation")
            // the composite FK (donation_id, payment_transaction_id, member_id, donation_amount, donation_currency) shows up on all five columns
            real.foreignKeys["donation_amount"] shouldBe setOf("donation")
            real.foreignKeys["donation_currency"] shouldBe setOf("donation")
            model.entityNameOf(entity.attributeByName("donation_id")?.foreignKey?.targetEntityId ?: "") shouldBe "donation"
            entity.attributeByName("donation_id")?.nullable shouldBe true
            entity.attributeByName("reference_date_override_reason")?.nullable shouldBe true
            entity.attributeByName("reference_date_override_reason")?.type shouldBe ErmDataType.Varchar(500)
        }

        test("price_oracle_snapshot_source table shape matches the real migrated schema and PriceOracleSnapshotSourceTable 1:1") {
            val entity = model.entities.single { it.name == "price_oracle_snapshot_source" }
            val real = transaction { introspectPriceOracleTable("price_oracle_snapshot_source") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PriceOracleSnapshotSourceTable.columns.map { it.name }
            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys shouldBe mapOf("snapshot_id" to setOf("price_oracle_snapshot"))
            entity.attributeByName("raw_price")?.type shouldBe ErmDataType.Decimal(38, 18)
        }

        test("price_oracle_reference_rate table shape matches the real migrated schema and PriceOracleReferenceRateTable 1:1") {
            val entity = model.entities.single { it.name == "price_oracle_reference_rate" }
            val real = transaction { introspectPriceOracleTable("price_oracle_reference_rate") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PriceOracleReferenceRateTable.columns.map { it.name }
            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys shouldBe mapOf("confirmed_by" to setOf("member"))
            entity.attributeByName("price")?.type shouldBe ErmDataType.Decimal(38, 18)
            entity.attributeByName("reference_snapshot_median")?.nullable shouldBe true
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [PeerTransferSchemaDriftTest]'s own private helper. */
private data class IntrospectedPriceOracleTable(
    val columns: Map<String, IntrospectedPriceOracleColumn>,
    /** FK column name -> every table it references (a column can sit in a simple FK and in a composite FK). */
    val foreignKeys: Map<String, Set<String>>,
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

    val fkByColumn = mutableMapOf<String, MutableSet<String>>()
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
            fkByColumn.getOrPut(rs.getString("fk_column")) { mutableSetOf() }.add(rs.getString("ref_table"))
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
