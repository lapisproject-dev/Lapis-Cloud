package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private val serverDir: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }

private fun src(relative: String): String = File(serverDir, relative).also { check(it.isFile) { "missing ${it.path}" } }.readText()

/**
 * V1.9.99 tripwire -- source-text guard that the RP never again asks a home server for
 * `OidcScopes.ALL` (which contains the MCP scopes the Issuer rejects in a mixed request).
 */
class OidcFederationScopeTripwireTest :
    FunSpec({
        val routes = src("routes/OidcRoutes.kt")
        val request = src("federation/OidcGuestAuthorizeRequest.kt")

        test("OidcRoutes never joins OidcScopes.ALL into a request scope") {
            Regex("""OidcScopes\.ALL\s*\.\s*joinToString""").containsMatchIn(routes) shouldBe false
        }

        test("OidcScopes.ALL appears in OidcRoutes exactly twice: the GET and POST /authorize known-scope validation") {
            // Allowlist: GET /authorize validation + POST /authorize/consent validation (V1.9.99). Anything else is a regression.
            Regex("""OidcScopes\.ALL\b""").findAll(routes).count() shouldBe 2
        }

        test("the outgoing authorize URL is built only in OidcGuestAuthorizeRequest") {
            routes.contains("&scope=") shouldBe false
            routes.contains("OidcGuestAuthorizeRequest.buildAuthorizeUrl(") shouldBe true
            routes.contains("OidcGuestAuthorizeRequest.grantedScope(") shouldBe true
        }

        test("OidcGuestAuthorizeRequest uses FEDERATION and never ALL") {
            request.contains("OidcScopes.FEDERATION") shouldBe true
            Regex("""OidcScopes\.ALL\b""").containsMatchIn(request) shouldBe false
        }

        test("the RP login start sets the browser-binding cookie") {
            routes.contains("appendRpBindingCookie(call = call") shouldBe true
        }
    })
