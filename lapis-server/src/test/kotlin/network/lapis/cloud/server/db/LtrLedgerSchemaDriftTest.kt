package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — LTR-Ledger domain (V0.6.1 Internes Crowdfunding).
 *
 * Verifies that `lapis-server/src/main/kuml/08-ltr-balance.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`ltr_ledger_entry`), and (b) the hand-written
 * [LtrLedgerEntryTable] Exposed object. Replaces `LtrBalanceSchemaDriftTest` (the pre-V0.6.1
 * `ltr_balance` snapshot table this domain re-modelled away, see that `.kuml.kts` file's own
 * header). Mirrors [AuditLogSchemaDriftTest]'s shape -- see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 *
 * The domain-specific structural point this test pins: UNLIKE the old `ltr_balance` (where
 * `member_id` doubled as the primary key), `ltr_ledger_entry` has its own synthetic `id` primary
 * key and `member_id` is a real UML association (many rows per member now) -- see the `.kuml.kts`
 * file header for the full before/after contrast.
 */
class LtrLedgerSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "08-ltr-balance.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the ltr_ledger_entry entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("ltr_ledger_entry", "member")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("ltr_ledger_entry table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "ltr_ledger_entry" }
            val real = transaction { introspectLtrLedgerTable("ltr_ledger_entry") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // id is the table's real, synthetic PRIMARY KEY -- unlike the pre-V0.6.1 ltr_balance,
            // member_id no longer doubles as the PK.
            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            entity.attributeByName("member_id")?.primaryKey shouldBe false

            // member_id is a real association-derived FK -> member (id).
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // created_by is a second, nullable, plain-«Column»-derived FK -> member (id) -- see
            // file header for why this could not also be a UML association (class-derived default
            // column name collision with member_id).
            real.foreignKeys["created_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("ltr_ledger_entry.reference_id has NO foreign key -- deliberately polymorphic, see file header") {
            val real = transaction { introspectLtrLedgerTable("ltr_ledger_entry") }
            real.foreignKeys.containsKey("reference_id") shouldBe false
        }

        test("ltr_ledger_entry.reference_type/reference_id/note/created_by are nullable, every other column is NOT NULL") {
            val entity = model.entities.single { it.name == "ltr_ledger_entry" }
            listOf("reference_type", "reference_id", "note", "created_by").forEach { name ->
                withClue(clue = "attribute '$name'") {
                    entity.attributeByName(name)?.nullable shouldBe true
                }
            }
            listOf("id", "entry_type", "amount_ltr", "created_at", "member_id").forEach { name ->
                withClue(clue = "attribute '$name'") {
                    entity.attributeByName(name)?.nullable shouldBe false
                }
            }
        }

        // ── (2) Model vs. hand-written Exposed Table object ─────────────────────

        test("ltr_ledger_entry entity column-name set matches the hand-written LtrLedgerEntryTable 1:1") {
            model.entities
                .single { it.name == "ltr_ledger_entry" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder LtrLedgerEntryTable.columns.map { it.name }
        }

        test("amount_ltr is modelled as DECIMAL(18,2), matching the real schema and LtrLedgerEntryTable (precision-override pin)") {
            val amountLtr = model.entities.single { it.name == "ltr_ledger_entry" }.attributeByName("amount_ltr")
            amountLtr?.type shouldBe ErmDataType.Decimal(18, 2)
        }

        test("entry_type/reference_type are modelled as real ErmDataType.Enum columns, literal order load-bearing") {
            val entity = model.entities.single { it.name == "ltr_ledger_entry" }

            entity.attributeByName("entry_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "LtrLedgerEntryType",
                    values =
                        listOf(
                            "MINT",
                            "PROJECT_STAKE",
                            "PROJECT_STAKE_RELEASE",
                            "VOTE_STAKE",
                            "PEER_TRANSFER_OUT",
                            "PEER_TRANSFER_IN",
                            "AUCTION_LISTING_FEE",
                            "AUCTION_HOLD",
                            "AUCTION_HOLD_RELEASE",
                            "AUCTION_SALE_OUT",
                            "AUCTION_SALE_IN",
                            "SOCIAL_POST_STAKE",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.LtrLedgerEntryType",
                )
            entity.attributeByName("reference_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "LtrLedgerReferenceType",
                    values = listOf("CROWDFUNDING_PROJECT", "VOTE", "PEER_TRANSFER", "AUCTION", "SOCIAL_POST"),
                    externalFqName = "network.lapis.cloud.shared.domain.LtrLedgerReferenceType",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedLtrLedgerTable(
    val columns: Map<String, IntrospectedLtrLedgerColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedLtrLedgerColumn(
    val nullable: Boolean,
)

/** ANSI `information_schema` walk for a single table's columns, nullability, FK targets and primary-key column set. Mirrors [LtrBalanceSchemaDriftTest]'s (pre-V0.6.1, since-removed) own `introspectLtrBalanceTable`. */
private fun JdbcTransaction.introspectLtrLedgerTable(tableName: String): IntrospectedLtrLedgerTable {
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

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedLtrLedgerColumn(nullable = nullable)
        }
    return IntrospectedLtrLedgerTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
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
