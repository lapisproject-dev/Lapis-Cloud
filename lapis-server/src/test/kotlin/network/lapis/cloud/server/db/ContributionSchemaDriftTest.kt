package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — contribution domain.
 *
 * Verifies that `lapis-server/src/main/kuml/01-contribution.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`membership_tier`/`contribution`), and (b) the
 * hand-written `MembershipTierTable`/`ContributionTable` Exposed objects.
 *
 * Mirrors [SchemaDriftTest] (foundation domain) — see its KDoc for the full designModelStrategy
 * option B rationale (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class ContributionSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "01-contribution.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly membership_tier, contribution and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("membership_tier", "contribution", "member")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("membership_tier table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "membership_tier" }
            val real = transaction { introspectContributionTable("membership_tier") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
        }

        test("contribution table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "contribution" }
            val real = transaction { introspectContributionTable("contribution") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["membership_tier_id"] shouldBe "membership_tier"
        }

        test("contribution's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_contribution_member_tier_period UNIQUE (member_id, membership_tier_id,
            // period_start, period_end) — «Column».unique is single-column only, so this is pinned
            // via a class-level «Index» (composite, unique=true) instead, which renders as a named
            // CREATE UNIQUE INDEX rather than ErmAttribute.unique.
            val real = transaction { introspectContributionTable("contribution") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("member_id", "membership_tier_id", "period_start", "period_end"))

            val entity = model.entities.single { it.name == "contribution" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_contribution_member_tier_period" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("membership_tier_id")!!.id,
                        entity.attributeByName("period_start")!!.id,
                        entity.attributeByName("period_end")!!.id,
                    )
            }
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("membership_tier entity column-name set matches the hand-written MembershipTierTable 1:1") {
            model.entities
                .single { it.name == "membership_tier" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MembershipTierTable.columns.map { it.name }
        }

        test("contribution entity column-name set matches the hand-written ContributionTable 1:1") {
            model.entities
                .single { it.name == "contribution" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ContributionTable.columns.map { it.name }
        }

        test(
            "membership_tier.billing_interval and contribution.status are modelled as real ErmDataType.Enum columns",
        ) {
            // Same gap-closure as MemberStatus/AccountRole in the foundation domain (see
            // SchemaDriftTest's matching test) — with the «Column».sqlType overrides removed,
            // kUML's enum-to-Enum+CHECK fallback path applies, emitting a typed
            // ErmDataType.Enum(name, values) instead of an untyped VARCHAR override.
            val billingInterval = model.entities.single { it.name == "membership_tier" }.attributeByName("billing_interval")
            val status = model.entities.single { it.name == "contribution" }.attributeByName("status")
            billingInterval?.type shouldBe
                ErmDataType.Enum(
                    name = "BillingInterval",
                    values = listOf("MONTHLY", "QUARTERLY", "YEARLY"),
                    externalFqName = "network.lapis.cloud.shared.domain.BillingInterval",
                )
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "ContributionStatus",
                    // V1.2.1 "Zahlungs-Fundament" appended four literals -- see
                    // 01-contribution.kuml.kts file header "Welle V1.2.1". Order is load-bearing.
                    values =
                        listOf(
                            "OPEN",
                            "PAID",
                            "WAIVED",
                            "OVERDUE",
                            "DEBIT_SCHEDULED",
                            "DEBIT_SUBMITTED",
                            "RETURNED",
                            "IN_DUNNING",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.ContributionStatus",
                )
        }

        // V1.2.1 "Zahlungs-Fundament" addendum.
        test("contribution.payment_method is modelled as a real ErmDataType.Enum column") {
            val paymentMethod = model.entities.single { it.name == "contribution" }.attributeByName("payment_method")
            paymentMethod?.type shouldBe
                ErmDataType.Enum(
                    name = "ContributionPaymentMethod",
                    values = listOf("MANUAL", "SEPA_DEBIT", "GATEWAY"),
                    externalFqName = "network.lapis.cloud.shared.domain.ContributionPaymentMethod",
                )
        }

        // V1.2.1 "Zahlungs-Fundament" addendum -- contribution.due_date is NOT NULL,
        // membership_tier.payment_term_days defaults to 14 (not modelled via defaultValue here,
        // same "SchemaDriftTest does not introspect column defaults" gap the file header documents).
        test("contribution.due_date is NOT NULL -- V1.2.1 addendum") {
            val entity = model.entities.single { it.name == "contribution" }
            entity.attributeByName("due_date")?.nullable shouldBe false
            entity.attributeByName("payment_method")?.nullable shouldBe false
        }

        test("decimal columns are modelled with the real schema's DECIMAL(12,2) precision, not the default DECIMAL(19,2)") {
            // UmlErmTypeMapper's default "bigdecimal" mapping is ErmDataType.Decimal(19, 2) — the
            // real schema uses DECIMAL(12, 2) throughout (contribution_amount, amount_due,
            // paid_amount). An explicit «Column».sqlType="DECIMAL(12,2)" override is parsed by
            // UmlErmTypeMapper.mapOverride's DECIMAL(p,s) regex into ErmDataType.Decimal(12, 2),
            // pinning the correct precision/scale instead of silently drifting to the wider default.
            val tier = model.entities.single { it.name == "membership_tier" }
            val contribution = model.entities.single { it.name == "contribution" }
            tier.attributeByName("contribution_amount")?.type shouldBe ErmDataType.Decimal(12, 2)
            contribution.attributeByName("amount_due")?.type shouldBe ErmDataType.Decimal(12, 2)
            contribution.attributeByName("paid_amount")?.type shouldBe ErmDataType.Decimal(12, 2)
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedContributionTable(
    val columns: Map<String, IntrospectedContributionColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedContributionColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [network.lapis.cloud.server.db.SchemaDriftTest]'s
 * (private, foundation-domain-scoped) `introspectTable`, extended with composite-unique
 * detection for this domain's `uq_contribution_member_tier_period` constraint.
 */
private fun JdbcTransaction.introspectContributionTable(tableName: String): IntrospectedContributionTable {
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

    // Detects both inline CONSTRAINT ... UNIQUE and standalone CREATE UNIQUE INDEX (e.g.
    // uq_contribution_member_tier_period, generated via a class-level «Index») — H2's
    // information_schema.table_constraints only surfaces the former, never a plain named unique
    // index, so both sources are unioned (keyed by constraint/index name, no collision risk).
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

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedContributionColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedContributionTable(
        columns = columns,
        foreignKeys = fkByColumn,
        compositeUniqueConstraints = compositeUniques,
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
