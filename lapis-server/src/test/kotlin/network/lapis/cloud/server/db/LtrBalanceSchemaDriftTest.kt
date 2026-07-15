package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.LtrBalanceTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — ltr-balance domain.
 *
 * Verifies that `lapis-server/src/main/kuml/08-ltr-balance.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`ltr_balance`, created inside
 * `V8__meritokratische_voteen.sql`), and (b) the hand-written `LtrBalanceTable` Exposed
 * object.
 *
 * Mirrors [SchemaDriftTest] (foundation domain) — see its KDoc for the full designModelStrategy
 * option B rationale (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 *
 * The domain-specific structural point this test pins: `member_id` is simultaneously the PRIMARY
 * KEY and the FK to `member` — no separate synthetic `id` column exists in the real schema or the
 * hand-written table (`PrimaryKey(memberId)`). See the `.kuml.kts` file's header comment for why
 * this is modelled as a plain `«Column»`+`«Id»` attribute rather than a UML association
 * (`UmlToErmTransformer.addForeignKey` always produces `primaryKey = false` for association-derived
 * FK columns — verified by reading `kuml-transform-uml-to-erm`'s source directly).
 */
class LtrBalanceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "08-ltr-balance.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun entityNameOf(entityId: String?): String? = model.entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the ltr_balance entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("ltr_balance", "member")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("ltr_balance table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "ltr_balance" }
            val real = transaction { introspectLtrBalanceTable("ltr_balance") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // member_id is the table's real PRIMARY KEY (not a synthetic separate id column) —
            // pinned both ways: the model's own attribute carries primaryKey=true, and the real
            // schema's primary-key column set is exactly {member_id}.
            entity.attributeByName("member_id")?.primaryKey shouldBe true
            real.primaryKeyColumns shouldBe setOf("member_id")

            // member_id is also a real FK -> member (id) in the live schema. Modelled as a plain
            // «Column»+«Id» UUID attribute rather than a UML association (see file header
            // comment: an association-derived FK column can never be primaryKey=true) — pinned
            // instead via «Column».fkEntity, so the model-level ErmAttribute now carries a real
            // ErmForeignKey alongside primaryKey=true.
            real.foreignKeys["member_id"] shouldBe "member"
            entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId) shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table object ─────────────────────

        test("ltr_balance entity column-name set matches the hand-written LtrBalanceTable 1:1") {
            model.entities
                .single { it.name == "ltr_balance" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder LtrBalanceTable.columns.map { it.name }
        }

        test("balance_ltr is modelled as DECIMAL(18,2), matching the real schema and LtrBalanceTable (precision-override pin)") {
            // UmlErmTypeMapper's bare "decimal" keyword defaults to DECIMAL(19,2) (same default
            // contribution.amountDue needed to override) — the real V8 schema/hand-written table
            // use the wider DECIMAL(18,2), so an explicit «Column».sqlType="DECIMAL(18,2)" override
            // is required, parsed by mapOverride's DECIMAL(p,s) regex into ErmDataType.Decimal(18, 2).
            // Not the enum-fidelity gap (no enum columns in this domain at all) — a distinct,
            // separately-pinned precision gap, same mechanism/rationale as contribution's own
            // DECIMAL(12,2) pin.
            val balanceLtr = model.entities.single { it.name == "ltr_balance" }.attributeByName("balance_ltr")
            balanceLtr?.type shouldBe ErmDataType.Decimal(18, 2)
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedLtrBalanceTable(
    val columns: Map<String, IntrospectedLtrBalanceColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedLtrBalanceColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * primary-key column set. Mirrors [DsgvoSchemaDriftTest]'s (private, dsgvo-domain-scoped)
 * `introspectDsgvoTable`, extended with a primary-key-column-set query since this domain's own
 * structural point (member_id doubling as PK) needs it.
 */
private fun JdbcTransaction.introspectLtrBalanceTable(tableName: String): IntrospectedLtrBalanceTable {
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
            IntrospectedLtrBalanceColumn(nullable = nullable)
        }
    return IntrospectedLtrBalanceTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
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
