// Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" + Welle V1.8.2 "MCP-Server:
// Schreibwerkzeuge" -- see network.lapis.cloud.server.mcp for the (optional, default-OFF) MCP
// resource-server layer these tables back, and docs/architecture/mcp-server.adoc for the
// architecture. The RFC 8707/connection-label/token_endpoint_auth_method additions to the existing
// OIDC tables (oidc_authorization_code, oidc_issued_token, oidc_client_registration) are modelled
// in 25-oidc-guest-federation.kuml.kts, not here -- this file owns only the genuinely new tables.
//
// **This file models three new tables (plus two cross-domain stubs):**
//   * `mcp_member_block`      -- per-member MCP kill-switch. NO ROW MEANS "NOT BLOCKED" -- the
//                                OPPOSITE polarity of `ai_member_opt_in` (no row there means "not
//                                opted in"). See network.lapis.cloud.server.mcp.optin
//                                .McpMemberBlockStore KDoc for why the name/polarity is deliberate.
//   * `mcp_tool_call_audit`   -- one row per `tools/call` JSON-RPC request: metadata only, NEVER
//                                arguments, NEVER results.
//   * `mcp_post_draft`        -- (Welle V1.8.2) an agent's UNPUBLISHED draft, created by the
//                                `create_post_draft` write tool. NOT a `social_post` row -- see
//                                docs/architecture/mcp-server.adoc "Why there is no DRAFT state in
//                                SocialPostState". `token_id` has no FK (same reasoning as
//                                `mcp_tool_call_audit.token_id` below); `agent_label` is a frozen
//                                SNAPSHOT of the connection's label at draft-creation time, not a
//                                live lookup, because a token revocation/deletion must never make a
//                                draft's own provenance unreadable -- see
//                                network.lapis.cloud.server.db.generated.McpPostDraftTable KDoc.
//
// **Why `mcp_tool_call_audit.token_id` has NO `fkEntity` tag.** An audit row must outlive the
// token it was recorded against (a deleted/purged token must not cascade-delete or orphan-block
// its own audit trail) -- same "outlives its parent" posture `oidc_guest_login_event.member_id`
// already establishes in 25-oidc-guest-federation.kuml.kts. Pinned by McpServerSchemaDriftTest
// asserting the real schema has no such FK; a future accidental `fkEntity` addition here would
// break that test loudly.
//
// **DSGVO**: both tables carry a member FK -- `mcp_member_block.member_id` (hard-deleted on
// erasure, a blocking decision for a person no longer in the system has no retention interest of
// its own) and `mcp_tool_call_audit.member_id` (retained but nulled, retain-and-redact, same
// posture `ai_call_audit.member_id` already gets in 53-ai-assistant.kuml.kts -- see
// network.lapis.cloud.server.dsgvo.McpPersonalData). `mcp_tool_call_audit.token_id` itself is
// never redacted/exported -- it carries no personal data of its own.
//
// Cross-domain stub: minimal id-only Member (owned by 00-foundation), same single-file-evaluation
// pattern every later domain file uses, purely so UmlToErmTransformer can resolve this file's FK
// columns.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "McpServer") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Cross-domain stub (Welle V1.8.2) -- id-only, owned by 32-social-network.kuml.kts, purely so
    // this file's own `released_post_id` FK column resolves. Same pattern as the `member` stub
    // above.
    val socialPost = classOf(name = "SocialPost") {
        stereotype("Entity") { "tableName" to "social_post"; "kotlinObjectName" to "SocialPostTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val mcpMemberBlock = classOf(name = "McpMemberBlock") {
        stereotype("Entity") { "tableName" to "mcp_member_block"; "kotlinObjectName" to "McpMemberBlockTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "uq_mcp_member_block_member"; "unique" to true }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "blocked", type = "Boolean") {
            stereotype("Column") { "columnName" to "blocked" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }

    val mcpToolCallAudit = classOf(name = "McpToolCallAudit") {
        stereotype("Entity") { "tableName" to "mcp_tool_call_audit"; "kotlinObjectName" to "McpToolCallAuditTable" }
        stereotype("Index") { "columns" to listOf("member_id", "called_at"); "name" to "ix_mcp_tool_call_audit_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Nullable: nulled on Art. 17 erasure (retain-and-redact), see file header "DSGVO".
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Deliberately NO fkEntity -- see file header.
        attribute(name = "tokenId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "token_id" }
        }
        attribute(name = "toolName", type = "String") {
            stereotype("Column") { "columnName" to "tool_name"; "sqlType" to "VARCHAR(60)" }
        }
        // OK|TIMEOUT|FORBIDDEN|ERROR|UNKNOWN_TOOL -- rate-limited calls are never audited (see
        // McpToolCallAuditRecorder KDoc). Longest literal UNKNOWN_TOOL (12) -> VARCHAR(30).
        attribute(name = "outcome", type = "String") {
            stereotype("Column") { "columnName" to "outcome"; "sqlType" to "VARCHAR(30)" }
        }
        attribute(name = "durationMs", type = "Int") {
            stereotype("Column") { "columnName" to "duration_ms" }
        }
        attribute(name = "calledAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "called_at" }
        }
    }

    // Literal order is load-bearing, matches network.lapis.cloud.shared.domain
    // .SocialPostVisibility (redeclared here, not shared with 32-social-network.kuml.kts --
    // this file's own single-file-evaluation pattern, see file header).
    val mcpDraftVisibility = enumOf(name = "SocialPostVisibility") {
        literal(name = "PUBLIC")
        literal(name = "MEMBERS_ONLY")
        literal(name = "MEMBERS_AND_EXTERNAL")
    }

    // Literal order is load-bearing, matches network.lapis.cloud.shared.domain.McpPostDraftStatus.
    val mcpPostDraftStatus = enumOf(name = "McpPostDraftStatus") {
        literal(name = "OPEN")
        literal(name = "RELEASED")
        literal(name = "DISCARDED")
    }

    val mcpPostDraft = classOf(name = "McpPostDraft") {
        stereotype("Entity") { "tableName" to "mcp_post_draft"; "kotlinObjectName" to "McpPostDraftTable" }
        stereotype("Index") { "columns" to listOf("member_id", "status"); "name" to "ix_mcp_post_draft_member_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Deliberately NO fkEntity -- same reasoning as mcp_tool_call_audit.token_id above: a
        // draft must outlive the token that created it.
        attribute(name = "tokenId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "token_id" }
        }
        // Frozen snapshot of the connection's label at draft-creation time -- see file header.
        attribute(name = "agentLabel", type = "String") {
            stereotype("Column") { "columnName" to "agent_label"; "sqlType" to "VARCHAR(60)" }
        }
        attribute(name = "content", type = "String") {
            stereotype("Column") { "columnName" to "content"; "sqlType" to "TEXT" }
        }
        attribute(name = "visibility", type = mcpDraftVisibility) {
            stereotype("Column") {
                "columnName" to "visibility"
                "enumType" to "network.lapis.cloud.shared.domain.SocialPostVisibility"
            }
        }
        attribute(name = "status", type = mcpPostDraftStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.McpPostDraftStatus"
            }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "statusChangedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "status_changed_at" }
        }
        // Real FK -> social_post (id), nullable (null until released, stays null forever if
        // DISCARDED). See SocialPost stub above.
        attribute(name = "releasedPostId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "released_post_id"; "fkEntity" to "SocialPost" }
        }
    }
}
