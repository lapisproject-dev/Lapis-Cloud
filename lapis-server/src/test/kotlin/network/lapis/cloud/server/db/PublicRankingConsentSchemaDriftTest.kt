package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- V1.3.0 "Öffentliche Transparenz-Startseite".
 *
 * Verifies that `lapis-server/src/main/kuml/35-public-ranking-consent.kuml.kts` is a faithful
 * model of both (a) the real, Flyway-migrated H2 schema (`public_ranking_consent_event`), and (b)
 * the hand-written [PublicRankingConsentEventTable] Exposed object. Mirrors
 * [ConferenceGuestAccessSchemaDriftTest]'s shape, with ONE cross-domain stub (Member).
 */
class PublicRankingConsentSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "35-public-ranking-consent.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the one consent-event entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "public_ranking_consent_event")
        }

        test("public_ranking_consent_event table shape matches the real migrated schema and PublicRankingConsentEventTable 1:1") {
            val entity = model.entities.single { it.name == "public_ranking_consent_event" }
            val real = transaction { introspectConsentEventTable("public_ranking_consent_event") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                PublicRankingConsentEventTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["member_id"] shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            entity.attributeByName("ranking_kind")?.nullable shouldBe false
            entity.attributeByName("event_type")?.nullable shouldBe false
            entity.attributeByName("occurred_at")?.nullable shouldBe false
            entity.attributeByName("superseded_at")?.nullable shouldBe true
            entity.attributeByName("consent_version")?.nullable shouldBe false
            entity.attributeByName("consent_sha256")?.nullable shouldBe false
        }
    })

private data class IntrospectedConsentEventTable(
    val columns: Map<String, IntrospectedConsentEventColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedConsentEventColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectConsentEventTable(tableName: String): IntrospectedConsentEventTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedConsentEventColumn(nullable = nullable) }
    return IntrospectedConsentEventTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [ConferenceGuestAccessSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
