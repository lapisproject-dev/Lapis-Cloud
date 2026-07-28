package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PoliticianWeightSnapshotTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Politiker-Profile und Politiker-Ranking domain (V0.6.4).
 *
 * Verifies that `lapis-server/src/main/kuml/20-politician.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`politician_profile`/`politician_reaction`/
 * `politician_weight_snapshot`), and (b) the hand-written [PoliticianProfileTable]/
 * [PoliticianReactionTable]/[PoliticianWeightSnapshotTable] Exposed objects. Mirrors
 * [CrowdfundingSchemaDriftTest]'s shape -- see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class PoliticianSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "20-politician.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the three politician entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "politician_profile",
                    "politician_reaction",
                    "politician_weight_snapshot",
                )
        }

        // ── politician_profile ────────────────────────────────────────────────

        test("politician_profile table shape matches the real migrated schema and PoliticianProfileTable 1:1") {
            val entity = model.entities.single { it.name == "politician_profile" }
            val real = transaction { introspectPoliticianTable("politician_profile") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PoliticianProfileTable.columns.map { it.name }

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            real.foreignKeys["granted_by_member_id"] shouldBe "member"
            real.foreignKeys["revoked_by_member_id"] shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false
            entity.attributeByName("granted_by_member_id")?.nullable shouldBe false
            entity.attributeByName("revoked_by_member_id")?.nullable shouldBe true
            entity.attributeByName("revoked_at")?.nullable shouldBe true
            entity.attributeByName("mandate_text")?.nullable shouldBe true

            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "PoliticianProfileStatus",
                    values = listOf("ACTIVE", "FORMER"),
                    externalFqName = "network.lapis.cloud.shared.domain.PoliticianProfileStatus",
                )

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("member_id"))
        }

        // ── politician_reaction ───────────────────────────────────────────────

        test("politician_reaction table shape matches the real migrated schema and PoliticianReactionTable 1:1") {
            val entity = model.entities.single { it.name == "politician_reaction" }
            val real = transaction { introspectPoliticianTable("politician_reaction") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PoliticianReactionTable.columns.map { it.name }

            // politician_profile_id: plain «Column».fkEntity -- see file header "FK-naming choice".
            real.foreignKeys["politician_profile_id"] shouldBe "politician_profile"
            model.entityNameOf(entity.attributeByName("politician_profile_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "politician_profile"
            // rater_member_id: real UML association, association-derived default matches.
            real.foreignKeys["rater_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("rater_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            entity.attributeByName("reaction_value")?.type shouldBe
                ErmDataType.Enum(
                    name = "PoliticianReactionValue",
                    values = listOf("LIKE", "DISLIKE"),
                    externalFqName = "network.lapis.cloud.shared.domain.PoliticianReactionValue",
                )

            // Guest-rating wave: rater_type distinguishes a MEMBER-cast from a GAST-cast reaction.
            entity.attributeByName("rater_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "PoliticianRaterType",
                    values = listOf("MEMBER", "GAST"),
                    externalFqName = "network.lapis.cloud.shared.domain.PoliticianRaterType",
                )
            entity.attributeByName("rater_type")?.nullable shouldBe false

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("politician_profile_id", "rater_member_id"))
        }

        // ── politician_weight_snapshot ────────────────────────────────────────

        test("politician_weight_snapshot table shape matches the real migrated schema and PoliticianWeightSnapshotTable 1:1") {
            val entity = model.entities.single { it.name == "politician_weight_snapshot" }
            val real = transaction { introspectPoliticianTable("politician_weight_snapshot") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PoliticianWeightSnapshotTable.columns.map { it.name }

            real.foreignKeys["politician_profile_id"] shouldBe "politician_profile"
            real.foreignKeys["computed_by_member_id"] shouldBe "member"
            entity.attributeByName("member_trust_weight")?.type shouldBe ErmDataType.Decimal(18, 2)

            // Guest-rating wave: guest-side and combined-side equivalents of the member_* columns.
            entity.attributeByName("guest_trust_weight")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("combined_trust_weight")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("guest_trust_weight")?.nullable shouldBe false
            entity.attributeByName("guest_like_count")?.nullable shouldBe false
            entity.attributeByName("guest_dislike_count")?.nullable shouldBe false
            entity.attributeByName("combined_trust_weight")?.nullable shouldBe false

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("politician_profile_id", "period_month"))
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [CrowdfundingSchemaDriftTest]'s own private helper. */
private data class IntrospectedPoliticianTable(
    val columns: Map<String, IntrospectedPoliticianColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedPoliticianColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectPoliticianTable(tableName: String): IntrospectedPoliticianTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedPoliticianColumn(nullable = nullable) }
    return IntrospectedPoliticianTable(
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
