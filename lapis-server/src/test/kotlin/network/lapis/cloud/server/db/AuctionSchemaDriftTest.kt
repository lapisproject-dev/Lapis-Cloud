package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
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

        test("model declares exactly the auction entities plus the Member and PartnerServer stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "auction",
                    "auction_bid",
                    "auction_compliance_acknowledgment",
                    "auction_payment_details",
                    "auction_fiat_acknowledgment",
                    "auction_offer",
                    "partner_server",
                    "auction_teaser_snapshot",
                    "auction_teaser_tombstone",
                    "auction_received_hint",
                    "auction_received_tombstone",
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
            // V1.9.320 (V166): the LTR amount is nullable (a Fiat row carries the minor-unit columns), the Fiat columns exist.
            entity.attributeByName("starting_bid_ltr")?.nullable shouldBe true
            entity.attributeByName("currency")?.nullable shouldBe false
            listOf(
                "starting_price_minor",
                "buy_now_price_minor",
                "final_price_minor",
                "seller_disclaimer_version",
                "payment_confirmed_at",
                "delivery_confirmed_at",
            ).forEach { entity.attributeByName(it)?.nullable shouldBe true }
            entity.attributeByName("offers_enabled")?.nullable shouldBe false
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "AuctionStatus",
                    values = listOf("OPEN", "SETTLED", "CLOSED_NO_SALE", "AWAITING_PAYMENT", "PAID", "COMPLETED"),
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
            // V1.9.320 (V166): exactly one of the two amount columns is set per row (CHECK), so both are nullable.
            entity.attributeByName("max_bid_ltr")?.nullable shouldBe true
            entity.attributeByName("max_bid_minor")?.nullable shouldBe true
        }

        // ── auction_payment_details / auction_fiat_acknowledgment (V1.9.320) ───────────────

        test("auction_payment_details table shape matches the real migrated schema and AuctionPaymentDetailsTable 1:1") {
            val entity = model.entities.single { it.name == "auction_payment_details" }
            val real = transaction { introspectAuctionTable("auction_payment_details") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AuctionPaymentDetailsTable.columns.map { it.name }
            real.primaryKeyColumns shouldBe setOf("auction_id")
            real.foreignKeys["auction_id"] shouldBe "auction"
            entity.attributes.forEach { attr -> real.columns.getValue(attr.name!!).nullable shouldBe false }
        }

        test("auction_fiat_acknowledgment table shape matches the real migrated schema and AuctionFiatAcknowledgmentTable 1:1") {
            val entity = model.entities.single { it.name == "auction_fiat_acknowledgment" }
            val real = transaction { introspectAuctionTable("auction_fiat_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AuctionFiatAcknowledgmentTable.columns.map { it.name }
            real.foreignKeys["acknowledged_by_member_id"] shouldBe "member"
            entity.attributes.forEach { attr -> real.columns.getValue(attr.name!!).nullable shouldBe false }
        }

        // ── auction_offer (V1.9.321) ─────────────────────────────────────────────

        test("auction_offer table shape matches the real migrated schema and AuctionOfferTable 1:1") {
            val entity = model.entities.single { it.name == "auction_offer" }
            val real = transaction { introspectAuctionTable("auction_offer") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AuctionOfferTable.columns.map { it.name }
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["auction_id"] shouldBe "auction"
            real.foreignKeys["buyer_member_id"] shouldBe "member"
            // parent_offer_id is a real self-referencing FK in V167 (the model keeps a plain nullable UUID, like document_folder.parent_folder_id).
            real.foreignKeys["parent_offer_id"] shouldBe "auction_offer"
            entity.attributeByName("parent_offer_id")?.nullable shouldBe true
            entity.attributeByName("decided_at")?.nullable shouldBe true
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "AuctionOfferStatus",
                    values = listOf("OFFERED", "ACCEPTED", "DECLINED", "COUNTERED", "EXPIRED", "WITHDRAWN"),
                    externalFqName = "network.lapis.cloud.shared.domain.AuctionOfferStatus",
                )
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

        // ── V169 federation visibility (wave c) ──────────────────────────────────

        listOf(
            "auction_teaser_snapshot" to AuctionTeaserSnapshotTable.columns.map { it.name },
            "auction_teaser_tombstone" to AuctionTeaserTombstoneTable.columns.map { it.name },
            "auction_received_hint" to AuctionReceivedHintTable.columns.map { it.name },
            "auction_received_tombstone" to AuctionReceivedTombstoneTable.columns.map { it.name },
        ).forEach { (table, exposedColumns) ->
            test("$table matches the real migrated schema (columns, nullability) and its Exposed object 1:1") {
                val entity = model.entities.single { it.name == table }
                val real = transaction { introspectAuctionTable(table) }
                entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
                entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
                entity.attributes.forEach { attr -> real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
        }

        test("the federation foreign keys point at auction and partner_server") {
            transaction { introspectAuctionTable("auction_teaser_snapshot").foreignKeys["auction_id"] } shouldBe "auction"
            transaction { introspectAuctionTable("auction_teaser_tombstone").foreignKeys["partner_id"] } shouldBe "partner_server"
            transaction { introspectAuctionTable("auction_received_hint").foreignKeys["source_partner_id"] } shouldBe "partner_server"
            transaction {
                introspectAuctionTable("auction_received_tombstone").foreignKeys["reported_by_partner_id"]
            } shouldBe "partner_server"
        }

        test("the new columns of auction match the model and its Exposed object") {
            val auction =
                model.entities
                    .single { it.name == "auction" }
                    .attributes
                    .map { it.name }
            auction shouldContainExactlyInAnyOrder AuctionTable.columns.map { it.name }
            (auction.containsAll(listOf("federation_opt_in", "federation_opt_in_at"))) shouldBe true
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
