package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.federation.OidcScopes
import network.lapis.cloud.server.mcp.auth.McpScopes

class McpConsentPageTest :
    FunSpec({
        fun page(scope: String): String =
            mcpConsentPageHtml(
                clientName = "Test Agent",
                clientId = "client",
                redirectUri = "http://127.0.0.1:1/cb",
                scopeParam = scope,
                state = "s",
                codeChallenge = "c",
                nonce = null,
                resource = "http://localhost/mcp",
            )

        test("the reading block names the own-articles tool and its editorial restriction") {
            val html = page(McpScopes.MEMBER_READ)
            html shouldContain "Eigene Artikel (nur Titel und Status)"
            html shouldContain "Vorstand oder Administration"
            html shouldNotContain "Artikel-Entwurf"
        }

        test("with the write scope the article-draft line appears, and states that approval is by another board member") {
            val html = page("${McpScopes.MEMBER_READ} ${OidcScopes.MCP_MEMBER_WRITE}")
            html shouldContain "Einen unveröffentlichten Artikel-Entwurf anlegen"
            html shouldContain "Einreichen können nur Sie selbst"
            html shouldContain "durch ein anderes Vorstandsmitglied"
            html shouldNotContain "veröffentlichen können nur Sie"
        }
    })
