package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.CrowdfundingSubmissionGateTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Internes Crowdfunding domain (V0.6.1).
 *
 * Verifies that `lapis-server/src/main/kuml/17-crowdfunding.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`crowdfunding_project`/`crowdfunding_reaction`/
 * `crowdfunding_distribution`/`crowdfunding_submission_gate`), and (b) the hand-written
 * [CrowdfundingProjectTable]/[CrowdfundingReactionTable]/[CrowdfundingDistributionTable]/
 * [CrowdfundingSubmissionGateTable] Exposed objects. Mirrors [LtrLedgerSchemaDriftTest]'s shape --
 * see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B rationale.
 */
class CrowdfundingSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "17-crowdfunding.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the four crowdfunding entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "crowdfunding_project",
                    "crowdfunding_reaction",
                    "crowdfunding_distribution",
                    "crowdfunding_submission_gate",
                )
        }

        // ── crowdfunding_project ──────────────────────────────────────────────

        test("crowdfunding_project table shape matches the real migrated schema and CrowdfundingProjectTable 1:1") {
            val entity = model.entities.single { it.name == "crowdfunding_project" }
            val real = transaction { introspectCrowdfundingTable("crowdfunding_project") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder CrowdfundingProjectTable.columns.map { it.name }

            real.foreignKeys["submitter_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("submitter_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            real.foreignKeys["reviewed_by"] shouldBe "member"
            entity.attributeByName("submitter_member_id")?.nullable shouldBe false
            entity.attributeByName("reviewed_by")?.nullable shouldBe true

            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "CrowdfundingProjectStatus",
                    values = listOf("PENDING", "APPROVED", "REJECTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.CrowdfundingProjectStatus",
                )
            entity.attributeByName("initial_weight_ltr")?.type shouldBe ErmDataType.Decimal(18, 2)
        }

        // ── crowdfunding_reaction ─────────────────────────────────────────────

        test("crowdfunding_reaction table shape matches the real migrated schema and CrowdfundingReactionTable 1:1") {
            val entity = model.entities.single { it.name == "crowdfunding_reaction" }
            val real = transaction { introspectCrowdfundingTable("crowdfunding_reaction") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder CrowdfundingReactionTable.columns.map { it.name }

            // project_id: plain «Column».fkEntity -- see file header "FK-naming choice".
            real.foreignKeys["project_id"] shouldBe "crowdfunding_project"
            model.entityNameOf(entity.attributeByName("project_id")?.foreignKey?.targetEntityId ?: "") shouldBe "crowdfunding_project"
            // member_id: real UML association, association-derived default matches.
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            entity.attributeByName("reaction_value")?.type shouldBe
                ErmDataType.Enum(
                    name = "CrowdfundingReactionValue",
                    values = listOf("LIKE", "DISLIKE"),
                    externalFqName = "network.lapis.cloud.shared.domain.CrowdfundingReactionValue",
                )

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("project_id", "member_id"))
        }

        // ── crowdfunding_distribution ─────────────────────────────────────────

        test("crowdfunding_distribution table shape matches the real migrated schema and CrowdfundingDistributionTable 1:1") {
            val entity = model.entities.single { it.name == "crowdfunding_distribution" }
            val real = transaction { introspectCrowdfundingTable("crowdfunding_distribution") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder CrowdfundingDistributionTable.columns.map { it.name }

            real.foreignKeys["project_id"] shouldBe "crowdfunding_project"
            real.foreignKeys["triggered_by"] shouldBe "member"
            entity.attributeByName("amount_eur")?.type shouldBe ErmDataType.Decimal(12, 2)

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("project_id", "period_start", "period_end"))
        }

        // ── crowdfunding_submission_gate ──────────────────────────────────────

        test("crowdfunding_submission_gate is a bare, FK-less singleton-lock table matching CrowdfundingSubmissionGateTable 1:1") {
            val entity = model.entities.single { it.name == "crowdfunding_submission_gate" }
            val real = transaction { introspectCrowdfundingTable("crowdfunding_submission_gate") }

            entity.attributes.map { it.name } shouldBe listOf("id")
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder CrowdfundingSubmissionGateTable.columns.map { it.name }
            real.foreignKeys.isEmpty() shouldBe true
            real.primaryKeyColumns shouldBe setOf("id")
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [AuditLogSchemaDriftTest]'s own private helper. */
private data class IntrospectedCrowdfundingTable(
    val columns: Map<String, IntrospectedCrowdfundingColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedCrowdfundingColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectCrowdfundingTable(tableName: String): IntrospectedCrowdfundingTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedCrowdfundingColumn(nullable = nullable) }
    return IntrospectedCrowdfundingTable(
        columns = columns,
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
        primaryKeyColumns = pkColumns,
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
