package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.CostCenterTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.PostingTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — accounting domain (SKR42 + double-entry, V0.3.1, chart
 * swapped from SKR49 in V0.3.1.1).
 *
 * Verifies that `lapis-server/src/main/kuml/10-accounting.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`ledger_account`/`journal_entry`/`posting`), and (b)
 * the generated `LedgerAccountTable`/`JournalEntryTable`/`PostingTable` Exposed objects.
 *
 * Mirrors [ContributionSchemaDriftTest]/[ElectionSchemaDriftTest] — see [SchemaDriftTest]'s KDoc
 * for the full designModelStrategy rationale. Since 4756e69, the generated Kotlin files under
 * `db/generated` are the compiled/imported-by-N-files source; this model is the versioned source
 * of truth for schema shape.
 */
class AccountingSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "10-accounting.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly ledger_account, journal_entry, posting, cost_center and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "ledger_account", "journal_entry", "posting", "cost_center")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("ledger_account table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "ledger_account" }
            val real = transaction { introspectAccountingTable("ledger_account") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
        }

        test("ledger_account.account_number's UNIQUE constraint is pinned via a class-level «Index»") {
            val entity = model.entities.single { it.name == "ledger_account" }
            val real = transaction { introspectAccountingTable("ledger_account") }

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("account_number"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_ledger_account_number" }.let {
                it.unique shouldBe true
                it.attributeIds shouldBe listOf(entity.attributeByName("account_number")!!.id)
            }
        }

        test("journal_entry table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "journal_entry" }
            val real = transaction { introspectAccountingTable("journal_entry") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // created_by -> member, NOT NULL: default would be "member_id" -- pinned instead via
            // «Column».fkEntity (same naming-gap class as election.opened_by/
            // systemic_consensus.opened_by).
            real.foreignKeys["created_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // Reserved-SQL-keyword avoidance, pinned explicitly: entry_date (not "date"),
            // voucher_reference (not "reference") -- both column names are already asserted
            // present above via the full column-name-set comparison; this additionally pins that
            // neither "date" nor "reference" sneaked in instead.
            ("date" in entity.attributes.map { it.name }) shouldBe false
            ("reference" in entity.attributes.map { it.name }) shouldBe false
            entity.attributeByName("voucher_reference")?.nullable shouldBe true
        }

        test("journal_entry.donor_member_id is a nullable FK -> member (V0.4.1 Spendenbescheinigung)") {
            val entity = model.entities.single { it.name == "journal_entry" }
            val real = transaction { introspectAccountingTable("journal_entry") }

            real.foreignKeys["donor_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("donor_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("donor_member_id")?.nullable shouldBe true
            real.columns.getValue("donor_member_id").nullable shouldBe true
        }

        test("posting table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "posting" }
            val real = transaction { introspectAccountingTable("posting") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // journal_entry_id -> journal_entry, ledger_account_id -> ledger_account: both match
            // the association-derived default and are modelled as real UML associations.
            real.foreignKeys["journal_entry_id"] shouldBe "journal_entry"
            real.foreignKeys["ledger_account_id"] shouldBe "ledger_account"
            model.entityNameOf(entity.attributeByName("journal_entry_id")?.foreignKey?.targetEntityId ?: "") shouldBe "journal_entry"
            model.entityNameOf(entity.attributeByName("ledger_account_id")?.foreignKey?.targetEntityId ?: "") shouldBe "ledger_account"
        }

        test("cost_center table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "cost_center" }
            val real = transaction { introspectAccountingTable("cost_center") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
        }

        test("cost_center.code's UNIQUE constraint is pinned via a class-level «Index»") {
            val entity = model.entities.single { it.name == "cost_center" }
            val real = transaction { introspectAccountingTable("cost_center") }

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("code"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_cost_center_code" }.let {
                it.unique shouldBe true
                it.attributeIds shouldBe listOf(entity.attributeByName("code")!!.id)
            }
        }

        test("cost_center entity column-name set matches the generated CostCenterTable 1:1") {
            model.entities
                .single { it.name == "cost_center" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder CostCenterTable.columns.map { it.name }
        }

        test("posting.cost_center_id is a nullable FK -> cost_center (V0.3.6, most postings carry none)") {
            val entity = model.entities.single { it.name == "posting" }
            val real = transaction { introspectAccountingTable("posting") }

            real.foreignKeys["cost_center_id"] shouldBe "cost_center"
            model.entityNameOf(entity.attributeByName("cost_center_id")?.foreignKey?.targetEntityId ?: "") shouldBe "cost_center"
            entity.attributeByName("cost_center_id")?.nullable shouldBe true
            real.columns.getValue("cost_center_id").nullable shouldBe true
        }

        test("posting.amount is modelled with DECIMAL(15,2) precision, not the default DECIMAL(19,2)") {
            val entity = model.entities.single { it.name == "posting" }
            entity.attributeByName("amount")?.type shouldBe ErmDataType.Decimal(15, 2)
        }

        test("posting.sphere is NOT NULL -- no posting may be silently unassigned to a Gemeinnuetzigkeit sphere") {
            val entity = model.entities.single { it.name == "posting" }
            entity.attributeByName("sphere")?.nullable shouldBe false

            val real = transaction { introspectAccountingTable("posting") }
            real.columns.getValue("sphere").nullable shouldBe false
        }

        test("ledger_account.reserve_type is nullable -- most accounts are not a designated §62 AO reserve") {
            val entity = model.entities.single { it.name == "ledger_account" }
            entity.attributeByName("reserve_type")?.nullable shouldBe true

            val real = transaction { introspectAccountingTable("ledger_account") }
            real.columns.getValue("reserve_type").nullable shouldBe true
        }

        test("ledger_account.is_cash_register is NOT NULL, defaulting to false (V0.3.5 Kassenbuch)") {
            val entity = model.entities.single { it.name == "ledger_account" }
            entity.attributeByName("is_cash_register")?.nullable shouldBe false

            val real = transaction { introspectAccountingTable("ledger_account") }
            real.columns.getValue("is_cash_register").nullable shouldBe false
        }

        // ── (2) Model vs. generated Exposed Table objects ────────────────────

        test("ledger_account entity column-name set matches the generated LedgerAccountTable 1:1") {
            model.entities
                .single { it.name == "ledger_account" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder LedgerAccountTable.columns.map { it.name }
        }

        test("journal_entry entity column-name set matches the generated JournalEntryTable 1:1") {
            model.entities
                .single { it.name == "journal_entry" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder JournalEntryTable.columns.map { it.name }
        }

        test("posting entity column-name set matches the generated PostingTable 1:1") {
            model.entities
                .single { it.name == "posting" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder PostingTable.columns.map { it.name }
        }

        test("ledger_account.type/journal_entry.status/posting.side/posting.sphere are modelled as real ErmDataType.Enum columns") {
            val type = model.entities.single { it.name == "ledger_account" }.attributeByName("type")
            val status = model.entities.single { it.name == "journal_entry" }.attributeByName("status")
            val side = model.entities.single { it.name == "posting" }.attributeByName("side")
            val sphere = model.entities.single { it.name == "posting" }.attributeByName("sphere")

            type?.type shouldBe
                ErmDataType.Enum(
                    name = "LedgerAccountType",
                    values = listOf("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"),
                    externalFqName = "network.lapis.cloud.shared.domain.LedgerAccountType",
                )
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "JournalEntryStatus",
                    values = listOf("DRAFT", "POSTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.JournalEntryStatus",
                )
            side?.type shouldBe
                ErmDataType.Enum(
                    name = "PostingSide",
                    values = listOf("DEBIT", "CREDIT"),
                    externalFqName = "network.lapis.cloud.shared.domain.PostingSide",
                )
            sphere?.type shouldBe
                ErmDataType.Enum(
                    name = "GemeinnuetzigkeitSphere",
                    values =
                        listOf(
                            "IDEELLER_BEREICH",
                            "VERMOEGENSVERWALTUNG",
                            "ZWECKBETRIEB",
                            "WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere",
                )
        }

        test("ledger_account.reserve_type is modelled as a real ErmDataType.Enum column") {
            val reserveType = model.entities.single { it.name == "ledger_account" }.attributeByName("reserve_type")

            reserveType?.type shouldBe
                ErmDataType.Enum(
                    name = "ReserveType",
                    values =
                        listOf(
                            "PROJEKTRUECKLAGE",
                            "FREIE_RUECKLAGE",
                            "WIEDERBESCHAFFUNGSRUECKLAGE",
                            "BETRIEBSMITTELRUECKLAGE",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.ReserveType",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including single-column uniques. */
private data class IntrospectedAccountingTable(
    val columns: Map<String, IntrospectedAccountingColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one UNIQUE constraint (1+ columns). */
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedAccountingColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * UNIQUE constraints. Mirrors [ContributionSchemaDriftTest]'s (private, contribution-domain-
 * scoped) `introspectContributionTable`.
 */
private fun JdbcTransaction.introspectAccountingTable(tableName: String): IntrospectedAccountingTable {
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

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedAccountingColumn(nullable = nullable)
        }
    return IntrospectedAccountingTable(
        columns = columns,
        foreignKeys = fkByColumn,
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
