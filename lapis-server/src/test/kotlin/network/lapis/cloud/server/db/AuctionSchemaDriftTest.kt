package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — LTR-Auktion domain (V0.6.2).
 *
 * Verifies that `lapis-server/src/main/kuml/21-auction.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`auction`/`auction_bid`/
 * `auction_compliance_acknowledgment`), and (b) the hand-written [AuctionTable]/[AuctionBidTable]/
 * [AuctionComplianceAcknowledgmentTable] Exposed objects. Mirrors [PoliticianSchemaDriftTest]'s
 * shape -- see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B rationale.
 */
class AuctionSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "21-auction.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the three auction entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "auction",
                    "auction_bid",
                    "auction_compliance_acknowledgment",
                )
        }

        // ── auction ────────────────────────────────────────────────────────────

        test("auction table shape matches the real migrated schema and AuctionTable 1:1") {
            val entity = model.entities.single { it.name == "auction" }
            val real = transaction { introspectAuctionTable("auction") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AuctionTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["seller_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("seller_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            real.foreignKeys["winner_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("winner_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            entity.attributeByName("seller_member_id")?.nullable shouldBe false
            entity.attributeByName("winner_member_id")?.nullable shouldBe true
            entity.attributeByName("final_price_ltr")?.nullable shouldBe true
            entity.attributeByName("buy_now_price_ltr")?.nullable shouldBe true
            entity.attributeByName("settled_at")?.nullable shouldBe true

            entity.attributeByName("starting_bid_ltr")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "AuctionStatus",
                    values = listOf("OPEN", "SETTLED", "CLOSED_NO_SALE"),
                    externalFqName = "network.lapis.cloud.shared.domain.AuctionStatus",
                )
        }

        // ── auction_bid ───────────────────────────────────────────────────────

        test("auction_bid table shape matches the real migrated schema and AuctionBidTable 1:1") {
            val entity = model.entities.single { it.name == "auction_bid" }
            val real = transaction { introspectAuctionTable("auction_bid") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AuctionBidTable.columns.map { it.name }

            // auction_id: plain «Column».fkEntity -- see file header "FK-naming choice"
            // (deliberately plain even though the association default would already match).
            real.foreignKeys["auction_id"] shouldBe "auction"
            model.entityNameOf(entity.attributeByName("auction_id")?.foreignKey?.targetEntityId ?: "") shouldBe "auction"
            real.foreignKeys["bidder_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("bidder_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("auction_id", "bidder_member_id"))
        }

        // ── auction_compliance_acknowledgment ────────────────────────────────────

        test(
            "auction_compliance_acknowledgment table shape matches the real migrated schema and AuctionComplianceAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "auction_compliance_acknowledgment" }
            val real = transaction { introspectAuctionTable("auction_compliance_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AuctionComplianceAcknowledgmentTable.columns.map { it.name }

            real.foreignKeys["acknowledged_by_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("acknowledged_by_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            entity.attributeByName("disclaimer_sha256")?.nullable shouldBe false
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [PoliticianSchemaDriftTest]'s own private helper. */
private data class IntrospectedAuctionTable(
    val columns: Map<String, IntrospectedAuctionColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedAuctionColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectAuctionTable(tableName: String): IntrospectedAuctionTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedAuctionColumn(nullable = nullable) }
    return IntrospectedAuctionTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
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
