package network.lapis.cloud.server.mcp.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.federation.OidcScopes
import network.lapis.cloud.server.security.ApiKeyStore

/**
 * Pure, no-DB cross-checks for the two duplicated-by-design literals [McpTokenAuth] and [McpScopes]
 * KDoc both promise are covered here -- see their own KDocs ("MUST stay byte-identical", "MUST stay
 * in sync"). Neither literal can be replaced by an import (`McpStructureTest` R1 forbids `mcp/`
 * importing `security/`; `McpScopes`' own KDoc explains why it exists instead of re-exporting
 * `OidcScopes`), so nothing in the compiler catches a drift between the two copies -- this test is
 * the only thing that does. A red run here means someone changed one side of a pair that is
 * supposed to move together.
 */
class McpTokenAuthTest :
    FunSpec({
        test("McpTokenAuth.API_KEY_TOKEN_PREFIX stays byte-identical to ApiKeyStore.API_KEY_TOKEN_PREFIX") {
            McpTokenAuth.API_KEY_TOKEN_PREFIX shouldBe ApiKeyStore.API_KEY_TOKEN_PREFIX
        }

        test("McpScopes.MEMBER_READ stays byte-identical to OidcScopes.MCP_MEMBER_READ") {
            McpScopes.MEMBER_READ shouldBe OidcScopes.MCP_MEMBER_READ
        }
    })
