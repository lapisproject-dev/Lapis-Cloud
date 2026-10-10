package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.net.URI
import java.net.URLDecoder

/** V1.9.99 -- pure coverage of the RP's outgoing authorize URL and the persisted guest scope. */
class OidcGuestAuthorizeRequestTest :
    FunSpec({
        fun query(url: String): Map<String, String> =
            URI(url)
                .rawQuery
                .split("&")
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to URLDecoder.decode(v, "UTF-8")
                }

        test("buildAuthorizeUrl asks for FEDERATION scopes only -- never an mcp: scope") {
            val url =
                OidcGuestAuthorizeRequest.buildAuthorizeUrl(
                    authorizationEndpoint = "https://home.example/federation/oidc/authorize",
                    clientId = "client id/with+odd&chars",
                    redirectUri = "https://rp.example/federation/oidc/rp/callback?x=1&y=2",
                    state = "st-ate_1",
                    codeChallenge = "chal-lenge_1",
                    nonce = "no-nce_1",
                )
            url.startsWith("https://home.example/federation/oidc/authorize?response_type=code") shouldBe true
            val q = query(url)
            q["scope"]!!.split(" ").toSet() shouldBe OidcScopes.FEDERATION.toSet()
            url shouldNotContain "mcp"
            q["response_type"] shouldBe "code"
            q["code_challenge_method"] shouldBe "S256"
            q["client_id"] shouldBe "client id/with+odd&chars"
            q["redirect_uri"] shouldBe "https://rp.example/federation/oidc/rp/callback?x=1&y=2"
            q["state"] shouldBe "st-ate_1"
            q["code_challenge"] shouldBe "chal-lenge_1"
            q["nonce"] shouldBe "no-nce_1"
        }

        test("grantedScope drops MCP and unknown scopes") {
            OidcGuestAuthorizeRequest.grantedScope("openid mcp:member_read foo:bar profile_basic mcp:member_write") shouldBe
                "openid profile_basic"
        }

        test("grantedScope de-duplicates -- 1000x openid stays within the varchar(500) column") {
            val result = OidcGuestAuthorizeRequest.grantedScope(List(1000) { "openid" }.joinToString(" "))
            result shouldBe "openid"
            (result.length <= 500) shouldBe true
        }

        test("grantedScope falls back to ALWAYS_GRANTED for empty or MCP-only input") {
            val fallback = OidcScopes.ALWAYS_GRANTED.joinToString(" ")
            OidcGuestAuthorizeRequest.grantedScope("") shouldBe fallback
            OidcGuestAuthorizeRequest.grantedScope("mcp:member_read mcp:member_write") shouldBe fallback
        }

        test("grantedScope order is stable (FEDERATION order) regardless of input order") {
            val reversed = OidcScopes.FEDERATION.reversed().joinToString(" ")
            OidcGuestAuthorizeRequest.grantedScope(reversed) shouldBe OidcScopes.FEDERATION.joinToString(" ")
        }
    })
