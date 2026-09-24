package network.lapis.cloud.server.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.federation.OidcDiscoveryDocument
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.module

private const val MCP_ACCESS_ROUTE = "/rpc/routeMcpAccessServiceManager0"

/**
 * Kill-switch coverage for the MCP layer, same pattern as `ai.AiFeatureKillSwitchTest`. Default
 * configuration (`McpConfig.disabled()`, the parameter default of `Application.module`) MUST leave
 * every MCP surface unreachable: `POST /mcp` is a genuine Ktor 404 (the route is never even
 * registered -- see `registerMcpRoutes` call site KDoc in `Application.kt`), the well-known
 * Protected Resource Metadata document 404s, `/authorize` rejects the `mcp:member_read` scope, and
 * `IMcpAccessService` answers a typed `McpFeatureDisabledException` through the RPC protocol
 * instead of an unhandled 500.
 */
class McpDisabledEndpointTest :
    FunSpec({
        test("default configuration: POST /mcp is a real 404, not a handler-level refusal") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val response =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("default configuration: GET /.well-known/oauth-protected-resource/mcp is 404") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                client.get("/.well-known/oauth-protected-resource/mcp").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("default configuration: discovery document never advertises mcp:member_read") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val body = client.get("/.well-known/openid-configuration").bodyAsText()
                body shouldNotBe null
                body.contains("mcp:member_read") shouldBe false
            }
        }

        test("default configuration: discovery document never advertises the public-client auth method 'none'") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val body = client.get("/.well-known/openid-configuration").bodyAsText()
                body.contains("token_endpoint_auth_methods_supported") shouldBe true
                body.contains("\"none\"") shouldBe false
            }
        }

        test(
            "default configuration: POST /federation/oidc/register with token_endpoint_auth_method=none is " +
                "rejected -- that auth method exists only for MCP agents",
        ) {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val response =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"client_name":"Disabled Test Agent","redirect_uris":["http://127.0.0.1:19999/cb"],""" +
                                """"token_endpoint_auth_method":"none"}""",
                        )
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("default configuration: /authorize with the mcp scope is rejected") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val response =
                    client.get(
                        "/federation/oidc/authorize?response_type=code&client_id=x&redirect_uri=https://x.example/cb" +
                            "&scope=mcp:member_read&state=s&code_challenge=cc&code_challenge_method=S256&nonce=n",
                    )
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("default configuration: IMcpAccessService answers a typed 'disabled' error, never a 500") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val response =
                    client.post(MCP_ACCESS_ROUTE) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"id":1,"jsonrpc":"2.0","method":"getMcpAccessState","params":[]}""")
                    }
                response.status shouldNotBe HttpStatusCode.InternalServerError
                response.bodyAsText() shouldContain "McpFeatureDisabledException"
            }
        }

        test("enabled configuration: POST /mcp is reachable (401 without a token, never 404)") {
            testApplication {
                application {
                    module(
                        aiConfig = AiConfig.load { null },
                        mcpConfig = McpConfig.load { if (it == McpConfig.ENV_ENABLED) "true" else null },
                    )
                }
                val response =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "OidcDiscoveryDocument.build(mcpEnabled = true) advertises mcp:member_read and 'none'; " +
                "(false) never advertises either",
        ) {
            val enabled = OidcDiscoveryDocument.build(mcpEnabled = true)
            enabled.scopes_supported.contains("mcp:member_read") shouldBe true
            enabled.token_endpoint_auth_methods_supported.contains("none") shouldBe true

            val disabled = OidcDiscoveryDocument.build(mcpEnabled = false)
            disabled.scopes_supported.contains("mcp:member_read") shouldBe false
            disabled.token_endpoint_auth_methods_supported.contains("none") shouldBe false
        }
    })
