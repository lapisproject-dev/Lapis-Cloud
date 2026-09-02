package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- the two static `.js` assets: size budget, ETag/
 * 304, headers, and the source-text scans that back-stop the security guarantees in
 * `network.lapis.cloud.server.embed.EmbedCors`/`lapis-widgets.js`'s own KDoc/comments.
 */
class EmbedAssetTest :
    FunSpec({
        fun resourceDir(): File =
            File("src/main/resources/embed").let { if (it.exists()) it else File("lapis-server/src/main/resources/embed") }

        fun kotlinSourceDir(): File = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }

        val widgetsJs = File(resourceDir(), "lapis-widgets.js")
        val loginPopupJs = File(resourceDir(), "login-popup.js")

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist =
                    EmbedOriginAllowlist
                        .parse(
                            raw = "https://partei.example,https://www.partei.example",
                            allowInsecure = false,
                        ).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    routing {
                        registerEmbedRoutes(
                            config = enabledConfig,
                            assetRateLimiter = generousLimiter(),
                            loginPageRateLimiter = generousLimiter(),
                            sessionRateLimiter = generousLimiter(),
                            adminStatusRateLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        test("lapis-widgets.js resource file is at most 8192 bytes unminified") {
            widgetsJs.exists() shouldBe true
            widgetsJs.readBytes().size.toLong() shouldBeLessThanOrEqualTo 8192L
        }

        test("served bundle body is at most 8192 bytes INCLUDING the origin-allowlist prelude") {
            testApp {
                val body = client.get("/embed/v1/lapis-widgets.js").bodyAsText()
                body.toByteArray(Charsets.UTF_8).size.toLong() shouldBeLessThanOrEqualTo 8192L
            }
        }

        test("prelude contains exactly the canonical origins, as a JSON array") {
            testApp {
                val body = client.get("/embed/v1/lapis-widgets.js").bodyAsText()
                body shouldContain """window.__lapisEmbedAllowedOriginsV1=["https://partei.example","https://www.partei.example"];"""
            }
        }

        test("ETag stable over two calls, If-None-Match yields 304") {
            testApp {
                val first = client.get("/embed/v1/lapis-widgets.js")
                val etag = first.headers[HttpHeaders.ETag]
                etag shouldBe (client.get("/embed/v1/lapis-widgets.js").headers[HttpHeaders.ETag])
                val conditional = client.get("/embed/v1/lapis-widgets.js") { header(HttpHeaders.IfNoneMatch, etag!!) }
                conditional.status shouldBe HttpStatusCode.NotModified
            }
        }

        test("Content-Type and Cache-Control as specified, no CORS headers on the asset") {
            testApp {
                val response = client.get("/embed/v1/lapis-widgets.js") { header(HttpHeaders.Origin, "https://partei.example") }
                response.headers[HttpHeaders.ContentType].orEmpty() shouldContain "application/javascript"
                response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=300, must-revalidate"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("login.js: same Content-Type/Cache-Control/ETag treatment, no CORS headers") {
            testApp {
                val response = client.get("/embed/v1/login.js") { header(HttpHeaders.Origin, "https://partei.example") }
                response.headers[HttpHeaders.ContentType].orEmpty() shouldContain "application/javascript"
                response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=300, must-revalidate"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                response.headers[HttpHeaders.ETag] shouldNotBe null
            }
        }

        test(
            "source scan over both .js files: no innerHTML, localStorage, sessionStorage, document.cookie, eval, Math.random, or postMessage(..., '*'/\"*\")",
        ) {
            for (file in listOf(widgetsJs, loginPopupJs)) {
                val text = file.readText()
                text shouldNotContain "innerHTML"
                text shouldNotContain "localStorage"
                text shouldNotContain "sessionStorage"
                text shouldNotContain "document.cookie"
                text shouldNotContain Regex("[^.]eval\\(")
                text shouldNotContain "Math.random"
                text shouldNotContain Regex("postMessage\\([^)]*['\"]\\*['\"]")
            }
        }

        test("lapis-widgets.js: attachShadow present, __lapisEmbedV1 guard present, all four postMessage checks present as tokens") {
            val text = widgetsJs.readText()
            text shouldContain "attachShadow"
            text shouldContain "__lapisEmbedV1"
            text shouldContain "e.origin === LAPIS_ORIGIN"
            text shouldContain "e.source === popupHandle"
            text shouldContain "e.data.source === \"lapis-embed\""
            text shouldContain "e.data.state === currentNonce"
        }

        test(
            "lapis-widgets.js: scan() is gated behind a document.readyState/DOMContentLoaded guard, not called " +
                "unconditionally at the bottom of the IIFE (Review-Fund V1.4.1a MAJOR 1: an unguarded scan() call " +
                "misses the widget <div>s when the script executes async against an already-cached response)",
        ) {
            val text = widgetsJs.readText()
            text shouldContain "document.readyState"
            text shouldContain "DOMContentLoaded"
        }

        test(
            "login-popup.js: probes the session endpoint before deciding whether to show the login form " +
                "(Review-Fund V1.4.1a MAJOR 3: without this fetch every popup asks an already-signed-in member " +
                "to re-enter their password)",
        ) {
            val text = loginPopupJs.readText()
            // Anchored on the fetch() call itself, not the bare path -- the path also appears twice in
            // comments (file header + explainer block above the call), so a bare-path assertion stays
            // green even if the fetch is deleted (Review-Fund V1.4.1a Round 2 MEDIUM).
            text shouldContain "fetch(\"/api/embed/v1/session\""
        }

        test(
            "login-popup.js: session probe carries a status text and a bounded timeout that falls back to the " +
                "form (Review-Fund V1.4.1a MINOR: a hanging/very slow probe must not leave the popup blank " +
                "forever with no form and no feedback)",
        ) {
            val text = loginPopupJs.readText()
            text shouldContain "window.setTimeout(fallBackToForm"
            text shouldContain "statusEl.textContent = \"Anmeldung wird geprüft"
        }

        test(
            "the credentials-allow header is never SET anywhere in the embed Kotlin package or in " +
                "EmbedHtml.kt/EmbedRoutes.kt -- neither via the literal wire-format string NOR via Ktor's " +
                "AccessControlAllowCredentials constant (Review-Fund V1.4.1a: a scan for only the literal " +
                "wire-format string could not have caught a call setting the header through the typed " +
                "constant instead -- the form this codebase's other header(...) call sites all use)",
        ) {
            val embedPackageDir = File(kotlinSourceDir(), "network/lapis/cloud/server/embed")
            val extraFiles =
                listOf(
                    File(kotlinSourceDir(), "network/lapis/cloud/server/routes/EmbedHtml.kt"),
                    File(kotlinSourceDir(), "network/lapis/cloud/server/routes/EmbedRoutes.kt"),
                )
            val kotlinFiles = (embedPackageDir.listFiles { f -> f.extension == "kt" }?.toList().orEmpty()) + extraFiles
            kotlinFiles.isEmpty() shouldBe false
            // A call site that actually SETS the header -- not a bare mention of the identifier, which
            // legitimately appears in this file's own KDoc (a `[HttpHeaders.AccessControlAllowCredentials]`
            // cross-reference explaining exactly this guarantee). `header(` immediately followed
            // (whitespace/newlines allowed) by either spelling is the shape every real call site in this
            // codebase uses (see e.g. EmbedCors.kt's own `response.header(HttpHeaders.AccessControlAllowOrigin, ...)`).
            val settingCallPattern =
                Regex(
                    """header\(\s*(?:HttpHeaders\.AccessControlAllowCredentials|"Access-Control-Allow-Credentials")""",
                )
            kotlinFiles.forEach { file ->
                file.exists() shouldBe true
                settingCallPattern.containsMatchIn(file.readText()) shouldBe false
            }
        }
    })
