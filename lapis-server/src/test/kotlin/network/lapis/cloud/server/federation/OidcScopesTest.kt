package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure unit coverage for [OidcScopes.isMcpScopeSet] -- the predicate EVERY MCP gate in this
 * codebase now uses to decide whether a stored/requested scope set is "an MCP grant"
 * ([network.lapis.cloud.server.mcp.auth.McpTokenAuth.resolve],
 * [network.lapis.cloud.server.mcp.auth.McpTokenRevoker], [network.lapis.cloud.server.rpc
 * .McpAccessService.stateFor]) rather than the pre-V1.8.2 `scope eq MCP_MEMBER_READ` exact-string
 * comparison that silently stopped matching the moment a grant could ALSO carry
 * [OidcScopes.MCP_MEMBER_WRITE]. Closes the gap a Welle-V1.8.2 review named explicitly: "no test
 * for the rejection of {mcp:member_write} alone or {openid, mcp:member_read}" -- both cases below
 * are exactly the ones the two CRITICAL findings on `McpTokenRevoker`/`McpAccessService` would have
 * been caught by, had this test existed before that wave shipped.
 */
class OidcScopesTest :
    FunSpec({
        test("a lone mcp:member_read is a valid MCP scope set") {
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.MCP_MEMBER_READ)) shouldBe true
        }

        test("mcp:member_read + mcp:member_write together is a valid MCP scope set, regardless of set iteration order") {
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.MCP_MEMBER_READ, OidcScopes.MCP_MEMBER_WRITE)) shouldBe true
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.MCP_MEMBER_WRITE, OidcScopes.MCP_MEMBER_READ)) shouldBe true
        }

        test("a lone mcp:member_write is REJECTED -- writing without reading has no meaning, see MCP_MEMBER_WRITE KDoc") {
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.MCP_MEMBER_WRITE)) shouldBe false
        }

        test("an empty scope set is REJECTED") {
            OidcScopes.isMcpScopeSet(emptySet()) shouldBe false
        }

        test(
            "mcp:member_read mixed with a guest-federation scope is REJECTED -- an MCP grant is never mixed with openid/profile_basic/etc.",
        ) {
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.OPENID, OidcScopes.MCP_MEMBER_READ)) shouldBe false
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.MCP_MEMBER_READ, OidcScopes.PROFILE_BASIC)) shouldBe false
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.PZB_READ)) shouldBe false
        }

        test("mcp:member_read + mcp:member_write mixed with a guest-federation scope is REJECTED too") {
            OidcScopes.isMcpScopeSet(setOf(OidcScopes.OPENID, OidcScopes.MCP_MEMBER_READ, OidcScopes.MCP_MEMBER_WRITE)) shouldBe false
        }
    })
