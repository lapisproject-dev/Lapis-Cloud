package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — contribution-relief domain (Welle V1.4.10
 * "Beitragsvergünstigungen"). Mirrors [ContributionSchemaDriftTest]'s structure exactly.
 */
class ContributionReliefSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "44-contribution-relief.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly contribution_relief_request and the member/contribution/membership_tier stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("contribution_relief_request", "member", "contribution", "membership_tier")
        }

        test("contribution_relief_request table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "contribution_relief_request" }
            val real = transaction { introspectCrrTable("contribution_relief_request") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["subject_member_id"] shouldBe "member"
            real.foreignKeys["requested_by"] shouldBe "member"
            real.foreignKeys["decided_by"] shouldBe "member"
            real.foreignKeys["deferral_contribution_id"] shouldBe "contribution"
            real.foreignKeys["reduction_target_tier_id"] shouldBe "membership_tier"
        }

        test("uq_crr_active_request is a PLAIN (non-partial) unique index on active_request_key -- K-1") {
            val real = transaction { introspectCrrTable("contribution_relief_request") }
            real.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("active_request_key")
        }

        test("contribution_relief_request entity column-name set matches the hand-written ContributionReliefRequestTable 1:1") {
            model.entities
                .single { it.name == "contribution_relief_request" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ContributionReliefRequestTable.columns.map { it.name }
        }

        test("kind/status/reason_category are modelled as real ErmDataType.Enum columns, literal order pinned") {
            val entity = model.entities.single { it.name == "contribution_relief_request" }
            entity.attributeByName("kind")?.type shouldBe
                ErmDataType.Enum(
                    name = "ContributionReliefKind",
                    values = listOf("DEFERRAL", "EXEMPTION", "REDUCTION"),
                    externalFqName = "network.lapis.cloud.shared.domain.ContributionReliefKind",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ContributionReliefStatus",
                    values = listOf("REQUESTED", "APPROVED", "REJECTED", "EXECUTED", "WITHDRAWN"),
                    externalFqName = "network.lapis.cloud.shared.domain.ContributionReliefStatus",
                )
            entity.attributeByName("reason_category")?.type shouldBe
                ErmDataType.Enum(
                    name = "ContributionReliefReason",
                    values =
                        listOf(
                            "FINANCIAL_HARDSHIP",
                            "UNEMPLOYMENT",
                            "STUDENT_TRAINEE",
                            "ILLNESS_DISABILITY",
                            "PARENTAL_CARE",
                            "OTHER",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.ContributionReliefReason",
                )
        }

        test("subject_member_id/kind/status/reason_category/requested_at/requested_by are NOT NULL") {
            val entity = model.entities.single { it.name == "contribution_relief_request" }
            listOf("subject_member_id", "kind", "status", "reason_category", "requested_at", "requested_by").forEach { name ->
                withClue(clue = "column '$name'") {
                    entity.attributeByName(name)?.nullable shouldBe false
                }
            }
        }
    })

private data class IntrospectedCrrTable(
    val columns: Map<String, IntrospectedCrrColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueSingleColumns: List<String>,
)

private data class IntrospectedCrrColumn(
    val nullable: Boolean,
)

/** Mirrors [ContributionSchemaDriftTest]'s private `introspectContributionTable`, extended with
 * single-column-unique detection for `uq_crr_active_request` (K-1's plain-unique-index guarantee). */
private fun JdbcTransaction.introspectCrrTable(tableName: String): IntrospectedCrrTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedCrrColumn(nullable = nullable) }
    val uniqueSingleColumns = uniqueColumnsByConstraint.values.filter { it.size == 1 }.map { it.single() }
    return IntrospectedCrrTable(columns = columns, foreignKeys = fkByColumn, uniqueSingleColumns = uniqueSingleColumns)
}

/** Small local stand-in for Kotest's `withClue` — mirrors [ContributionSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
