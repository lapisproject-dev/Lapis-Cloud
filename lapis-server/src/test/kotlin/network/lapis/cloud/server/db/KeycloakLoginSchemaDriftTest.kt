package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.KeycloakLoginAttemptTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern". Verifies that the
 * hand-written `Keycloak*Table` Exposed objects match the real, Flyway-migrated H2 schema
 * (`V45__keycloak_login.sql`) 1:1 -- same `information_schema` introspection style
 * [AiAssistantSchemaDriftTest]/[OidcGuestFederationSchemaDriftTest] use, but WITHOUT a
 * kUML-model comparison: this sub-wave is server-only (no `.kuml.kts` model exists for it yet;
 * modelling it is left to a later wave/decision, out of this sub-wave's explicit scope).
 */
class KeycloakLoginSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("keycloak_account_link: column set matches KeycloakAccountLinkTable 1:1") {
            val real = transaction { introspectKeycloakTable("keycloak_account_link") }
            real.columns.keys shouldContainExactlyInAnyOrder KeycloakAccountLinkTable.columns.map { it.name }
        }

        test("keycloak_account_link: FKs point at member for both member_id and linked_by") {
            val real = transaction { introspectKeycloakTable("keycloak_account_link") }
            real.foreignKeys shouldBe mapOf("member_id" to "member", "linked_by" to "member")
        }

        test("keycloak_account_link: member_id is unique (a member has at most one link) and nullable columns match") {
            val real = transaction { introspectKeycloakTable("keycloak_account_link") }
            ("member_id" in real.uniqueColumns) shouldBe true
            real.columns.getValue("linked_by").nullable shouldBe true
            real.columns.getValue("last_login_at").nullable shouldBe true
            real.columns.getValue("member_id").nullable shouldBe false
            real.columns.getValue("keycloak_issuer").nullable shouldBe false
            real.columns.getValue("keycloak_subject").nullable shouldBe false
        }

        test("keycloak_account_link: composite unique index on (keycloak_issuer, keycloak_subject) exists") {
            val hasCompositeUniqueIndex =
                transaction {
                    var found = false
                    exec(
                        """
                        SELECT 1
                        FROM information_schema.indexes
                        WHERE table_name = 'keycloak_account_link'
                          AND index_name = 'uq_keycloak_account_link_issuer_subject'
                          AND index_type_name = 'UNIQUE INDEX'
                        """.trimIndent(),
                    ) { rs -> found = rs.next() }
                    found
                }
            hasCompositeUniqueIndex shouldBe true
        }

        test("keycloak_login_attempt: column set matches KeycloakLoginAttemptTable 1:1, NO member FK") {
            val real = transaction { introspectKeycloakTable("keycloak_login_attempt") }
            real.columns.keys shouldContainExactlyInAnyOrder KeycloakLoginAttemptTable.columns.map { it.name }
            real.foreignKeys.isEmpty() shouldBe true
            ("state_hash" in real.uniqueColumns) shouldBe true
            real.columns.getValue("consumed_at").nullable shouldBe true
        }
    })

private data class IntrospectedKeycloakTable(
    val columns: Map<String, IntrospectedKeycloakColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueColumns: Set<String>,
)

private data class IntrospectedKeycloakColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [AiAssistantSchemaDriftTest]'s own `introspectAiTable`. */
private fun JdbcTransaction.introspectKeycloakTable(tableName: String): IntrospectedKeycloakTable {
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

    val uniqueColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumns += rs.getString("column_name")
        }
    }

    return IntrospectedKeycloakTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedKeycloakColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueColumns = uniqueColumns,
    )
}
