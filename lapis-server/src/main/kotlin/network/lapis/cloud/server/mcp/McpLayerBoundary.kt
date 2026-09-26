package network.lapis.cloud.server.mcp

/**
 * Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" -- optional MCP resource
 * server, **default OFF**. Exposes exactly five read-only tools over a bespoke JSON-RPC 2.0
 * transport (`POST /mcp`, protocol version hard-pinned to `2025-06-18`), authenticated by its OWN
 * bearer-token resource-server path -- see `auth.McpTokenAuth` KDoc -- never by the caller's
 * browser session and never by an `X-Member-Id`/API-key/AI-assistant credential.
 *
 * **No third-party MCP SDK dependency.** The official `kotlin-sdk` brings a transitive Ktor/
 * serialization/logging stack that would have to be pinned against this repo's own gepinnte
 * Versionen -- exactly the question this codebase already decided against Koog for
 * (`docs/architecture/ai-assistant.adoc`). Conformance is instead an ABNAHMEBEDINGUNG:
 * `McpConformanceTest` pins the wire shape against frozen fixtures, and connecting a real MCP
 * client (Claude Desktop) is a documented manual verification step for this wave.
 *
 * **Deliberately decoupled from the AI-assistance layer** (`network.lapis.cloud.server.ai`) -- MCP
 * access does not require `LAPIS_AI_ENABLED`, does not consume any `Ai*` quota, and never calls an
 * LLM provider itself (the five tools are all direct, authorization-scoped reads; the calling
 * agent is the "model", not this server). The one deliberate exception is `search_statute`, which
 * reuses `network.lapis.cloud.server.ai.retrieval.KnowledgeRetriever`/
 * `PostgresFullTextKnowledgeRetriever` directly (full-text retrieval only, no model call) --
 * everything else under `ai/qa`/`ai/llm` remains off-limits.
 *
 * **Source-scan-enforced boundary** (`McpStructureTest`, same pattern as `AiModuleBoundaryTest`):
 * - **R1** -- `mcp/` never imports `security.SessionStore`/`security.resolveCurrentMember`/
 *   `security.CurrentMember`/`security.ApiKeyStore`, and never imports a `server.rpc.*Service` --
 *   the MCP resource-server path resolves identity exclusively through `auth.McpTokenAuth`, and
 *   reads exclusively through the shared `rpc.*Reads` facade objects, never a `*Service` directly.
 * - **R2** -- `mcp/transport/`/`mcp/tools/` never read `call.request.cookies` -- MCP is a pure
 *   bearer-token resource server, cookies are a browser-session concept that must never leak in.
 * - **R3** -- no Exposed write happens anywhere under `mcp/` outside `optin/McpMemberBlockStore`,
 *   `audit/McpToolCallAuditRecorder`, and the two narrow, explicitly-scoped writes in
 *   `auth/McpTokenAuth` (`last_used_at` only) and `auth/McpTokenRevoker` (`revoked_at` only).
 * - **R4** -- `tools/McpToolCatalog.TOOLS` has exactly seven entries (five reading, two writing,
 *   Welle V1.8.2), and no tool signature accepts a caller-supplied `memberId` -- identity comes
 *   exclusively from the resolved `McpPrincipal`.
 * - **R5** -- `mcp/` never imports `server.ai.qa`/`server.ai.llm`; the one named exception is
 *   `ai.retrieval.KnowledgeRetriever`/`PostgresFullTextKnowledgeRetriever` for `search_statute`.
 * - **R6** (Welle V1.8.2) -- no source under `mcp/` imports/references
 *   `rpc.SocialNetworkService`/`rpc.SocialNetworkService.createPost`/`domain.SocialPostState`/
 *   `db.generated.SocialPostTable`: `create_post_draft`'s write path stops at `social
 *   .PostDraftStore` (a `mcp_post_draft` row), never a real `social_post`. Turning a draft into a
 *   published post is EXCLUSIVELY the member's own action via
 *   `rpc.SocialNetworkService.releaseMyPostDraft` -- see `docs/architecture/mcp-server.adoc` "Why
 *   there is no DRAFT state in SocialPostState". `domain.SocialPostVisibility` is a DELIBERATE,
 *   named exception (it is `create_post_draft`'s own input type, a shared-domain enum with no
 *   write capability of its own) -- without that exception R6 would be unsatisfiable.
 */
internal object McpLayerBoundary
