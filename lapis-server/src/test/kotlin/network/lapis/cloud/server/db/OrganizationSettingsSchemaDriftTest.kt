package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — organization-settings domain (V0.4.1 Serienbrief/PDF
 * engine).
 *
 * Verifies that `lapis-server/src/main/kuml/11-organization-settings.kuml.kts` is a faithful model
 * of both (a) the real, Flyway-migrated H2 schema (`organization_settings`), and (b) the generated
 * `OrganizationSettingsTable` Exposed object. Mirrors [LtrBalanceSchemaDriftTest] -- see
 * [SchemaDriftTest]'s KDoc for the full designModelStrategy rationale.
 *
 * The domain-specific structural point this test pins: exactly one row exists, at the fixed
 * sentinel id [ORGANIZATION_SETTINGS_ID], from first migration onward in EVERY environment (not
 * just `LAPIS_SEED_DEMO_DATA=true` demo deployments) -- see `V1__baseline.sql`'s unconditional
 * seed `INSERT` and the `.kuml.kts` file header for the full "why not DevSeedData" rationale.
 */
class OrganizationSettingsSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "11-organization-settings.kuml.kts")
        val model by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the organization_settings entity, no cross-domain stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("organization_settings")
        }

        test("organization_settings table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "organization_settings" }
            val real = transaction { introspectOrganizationSettingsTable() }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // Only `name` (besides `id`) is NOT NULL -- see file header.
            entity.attributeByName("name")?.nullable shouldBe false
        }

        test("organization_settings entity column-name set matches the generated OrganizationSettingsTable 1:1") {
            model.entities
                .single { it.name == "organization_settings" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder OrganizationSettingsTable.columns.map { it.name }
        }

        test("exactly one organization_settings row exists at the fixed sentinel id, seeded from V1__baseline.sql") {
            val rows =
                transaction {
                    OrganizationSettingsTable.selectAll().toList()
                }
            rows.size shouldBe 1
            rows.single()[OrganizationSettingsTable.id] shouldBe ORGANIZATION_SETTINGS_ID
            rows.single()[OrganizationSettingsTable.name].isNotBlank() shouldBe true
        }

        test("the seeded row is reachable by id (defensive -- proves the sentinel id itself, not just row count)") {
            val row =
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                }
            (row != null) shouldBe true
        }
    })

private data class IntrospectedOrganizationSettingsColumn(
    val nullable: Boolean,
)

private data class IntrospectedOrganizationSettingsTable(
    val columns: Map<String, IntrospectedOrganizationSettingsColumn>,
)

private fun JdbcTransaction.introspectOrganizationSettingsTable(): IntrospectedOrganizationSettingsTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = 'organization_settings'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }
    return IntrospectedOrganizationSettingsTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedOrganizationSettingsColumn(nullable) },
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
