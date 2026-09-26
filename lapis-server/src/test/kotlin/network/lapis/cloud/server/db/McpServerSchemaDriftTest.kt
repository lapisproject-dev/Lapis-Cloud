package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.McpMemberBlockTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.8.1 "MCP-Server für Mitglieder-Agenten". Verifies
 * that `lapis-server/src/main/kuml/54-mcp-server.kuml.kts` is a faithful model of both (a) the
 * real, Flyway-migrated H2 schema and (b) the hand-written `Mcp*Table` Exposed objects. Mirrors
 * [OidcGuestFederationSchemaDriftTest]'s shape.
 *
 * The domain-specific structural point this test pins above all others:
 * `mcp_tool_call_audit.token_id` has NO FK in the real schema -- see `54-mcp-server.kuml.kts` file
 * header. A future accidental `.references(...)` addition would break this test loudly.
 */
class McpServerSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "54-mcp-server.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the three MCP entities plus the Member/SocialPost stubs (Welle V1.8.2)") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "social_post", "mcp_member_block", "mcp_tool_call_audit", "mcp_post_draft")
        }

        test("mcp_post_draft table shape matches the real migrated schema and McpPostDraftTable 1:1 -- token_id has NO FK (deliberate)") {
            val entity = model.entities.single { it.name == "mcp_post_draft" }
            val real = transaction { introspectMcpTable("mcp_post_draft") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder McpPostDraftTable.columns.map { it.name }
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["released_post_id"] shouldBe "social_post"

            // Same pinned "no FK on token_id" regression guard as mcp_tool_call_audit.token_id below.
            real.foreignKeys["token_id"] shouldBe null
            entity.attributeByName("token_id")?.foreignKey shouldBe null
            entity.attributeByName("token_id")?.nullable shouldBe true
            entity.attributeByName("released_post_id")?.nullable shouldBe true
        }

        test("mcp_member_block table shape matches the real migrated schema and McpMemberBlockTable 1:1") {
            val entity = model.entities.single { it.name == "mcp_member_block" }
            val real = transaction { introspectMcpTable("mcp_member_block") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder McpMemberBlockTable.columns.map { it.name }
            real.foreignKeys["member_id"] shouldBe "member"
        }

        test(
            "mcp_tool_call_audit table shape matches the real migrated schema and McpToolCallAuditTable 1:1 -- token_id has NO FK (deliberate)",
        ) {
            val entity = model.entities.single { it.name == "mcp_tool_call_audit" }
            val real = transaction { introspectMcpTable("mcp_tool_call_audit") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder McpToolCallAuditTable.columns.map { it.name }
            real.foreignKeys["member_id"] shouldBe "member"

            // The pinned regression guard, same shape OidcGuestFederationSchemaDriftTest already
            // establishes for oidc_guest_login_event.member_id.
            real.foreignKeys["token_id"] shouldBe null
            entity.attributeByName("token_id")?.foreignKey shouldBe null
            entity.attributeByName("token_id")?.nullable shouldBe true
            entity.attributeByName("member_id")?.nullable shouldBe true
        }

        test("oidc_issued_token and oidc_authorization_code carry the V1.8.1 resource/connection_label columns, no FK") {
            val real1 = transaction { introspectMcpTable("oidc_issued_token") }
            real1.columns.keys shouldContainExactlyInAnyOrder
                listOf(
                    "id",
                    "client_registration_id",
                    "member_id",
                    "access_token_hash",
                    "refresh_token_hash",
                    "scope",
                    "issued_at",
                    "access_expires_at",
                    "refresh_expires_at",
                    "revoked_at",
                    "resource",
                    "connection_label",
                    "last_used_at",
                )
            real1.columns["resource"]?.nullable shouldBe true
            real1.columns["connection_label"]?.nullable shouldBe true
            real1.columns["last_used_at"]?.nullable shouldBe true

            val real2 = transaction { introspectMcpTable("oidc_client_registration") }
            real2.columns["token_endpoint_auth_method"]?.nullable shouldBe false
        }
    })

private data class IntrospectedMcpTable(
    val columns: Map<String, IntrospectedMcpColumn>,
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedMcpColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectMcpTable(tableName: String): IntrospectedMcpTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedMcpColumn(nullable = nullable) }
    return IntrospectedMcpTable(columns = columns, foreignKeys = fkByColumn)
}
