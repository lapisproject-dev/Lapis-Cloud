package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.TrustAnchorEventTable
import network.lapis.cloud.server.db.generated.TrustAnchorPoolMemberTable
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.server.db.generated.TrustedExternalAnchorTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Trust-Anchor-Governance domain (V0.8.3).
 *
 * Verifies that `lapis-server/src/main/kuml/26-trust-anchor.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`trust_anchor_signing_key`/`trust_anchor_pool_member`/
 * `trusted_external_anchor`/`trust_anchor_event`), and (b) the hand-written
 * `TrustAnchorSigningKeyTable`/`TrustAnchorPoolMemberTable`/`TrustedExternalAnchorTable`/
 * `TrustAnchorEventTable` Exposed objects. Mirrors [FederationSchemaDriftTest]'s shape (same "no
 * Member stub, no FK anywhere in this file" domain shape).
 */
class TrustAnchorSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "26-trust-anchor.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the four Trust-Anchor entities") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "trust_anchor_signing_key",
                    "trust_anchor_pool_member",
                    "trusted_external_anchor",
                    "trust_anchor_event",
                )
        }

        // ── trust_anchor_signing_key ──────────────────────────────────────

        test("trust_anchor_signing_key table shape matches the real migrated schema and TrustAnchorSigningKeyTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "trust_anchor_signing_key" }
            val real = transaction { introspectTrustAnchorTable("trust_anchor_signing_key") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder TrustAnchorSigningKeyTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            real.foreignKeys.isEmpty() shouldBe true

            entity.attributeByName("retired_at")?.nullable shouldBe true
            entity.attributeByName("revoked_at")?.nullable shouldBe true

            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "TrustAnchorSigningKeyStatus",
                    values = listOf("ACTIVE", "RETIRED", "REVOKED"),
                    externalFqName = "network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus",
                )
        }

        // ── trust_anchor_pool_member ──────────────────────────────────────

        test("trust_anchor_pool_member table shape matches the real migrated schema and TrustAnchorPoolMemberTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "trust_anchor_pool_member" }
            val real = transaction { introspectTrustAnchorTable("trust_anchor_pool_member") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder TrustAnchorPoolMemberTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys.isEmpty() shouldBe true
        }

        // ── trusted_external_anchor ────────────────────────────────────────

        test("trusted_external_anchor table shape matches the real migrated schema and TrustedExternalAnchorTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "trusted_external_anchor" }
            val real = transaction { introspectTrustAnchorTable("trusted_external_anchor") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder TrustedExternalAnchorTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys.isEmpty() shouldBe true
        }

        // ── trust_anchor_event ─────────────────────────────────────────────

        test("trust_anchor_event table shape matches the real migrated schema and TrustAnchorEventTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "trust_anchor_event" }
            val real = transaction { introspectTrustAnchorTable("trust_anchor_event") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder TrustAnchorEventTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys.isEmpty() shouldBe true

            entity.attributeByName("event_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "TrustAnchorEventType",
                    values =
                        listOf(
                            "KEY_PROVISIONED",
                            "KEY_ROTATED",
                            "KEY_REVOKED",
                            "POOL_MEMBER_ADDED",
                            "POOL_MEMBER_REMOVED",
                            "TRUSTED_ANCHOR_ADDED",
                            "TRUSTED_ANCHOR_REMOVED",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.TrustAnchorEventType",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [FederationSchemaDriftTest]'s own private helper. */
private data class IntrospectedTrustAnchorTable(
    val columns: Map<String, IntrospectedTrustAnchorColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedTrustAnchorColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectTrustAnchorTable(tableName: String): IntrospectedTrustAnchorTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedTrustAnchorColumn(nullable = nullable) }
    return IntrospectedTrustAnchorTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors [FederationSchemaDriftTest]'s). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
