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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
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

        fun noOpMailDispatcher() = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

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
                            pspConfigState = network.lapis.cloud.server.payment.psp.PspConfigState.NotConfigured,
                            checkoutClient = null,
                            donationCheckoutRateLimiter = generousLimiter(),
                            donationCheckoutAttemptRateLimiter = generousLimiter(),
                            donationPageRateLimiter = generousLimiter(),
                            mailDispatcher = noOpMailDispatcher(),
                            eventRegistrationAttemptRateLimiter = generousLimiter(),
                            eventRegistrationRateLimiter = generousLimiter(),
                            eventPageRateLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        /**
         * Test-only [PspConfig] (Review MINOR, Round 3: donationRange had NULL test coverage --
         * [testApp] above hardcodes `PspConfigState.NotConfigured`, so the whole
         * `window.__lapisEmbedDonationRangeV1` prelude branch in [EmbedAssets.widgetJs] never ran in
         * any prior test). Same construction shape as `EmbedDonationRoutesTest.testPspConfig` --
         * `PspConfig`'s constructor is private, so a real [PspConfig] can only come from
         * `PspConfig.load` with a fake env lambda.
         */
        fun testPspConfig(maxCheckoutAmountEur: String): PspConfig =
            (
                PspConfig.load {
                    when (it) {
                        PspConfig.ENV_SECRET_KEY -> "sk_test_asset"
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_asset"
                        PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR -> maxCheckoutAmountEur
                        else -> null
                    }
                } as PspConfigState.Configured
            ).config

        suspend fun testAppWithPsp(
            pspConfigState: PspConfigState,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerEmbedRoutes(
                            config = enabledConfig,
                            assetRateLimiter = generousLimiter(),
                            loginPageRateLimiter = generousLimiter(),
                            sessionRateLimiter = generousLimiter(),
                            adminStatusRateLimiter = generousLimiter(),
                            pspConfigState = pspConfigState,
                            checkoutClient = null,
                            donationCheckoutRateLimiter = generousLimiter(),
                            donationCheckoutAttemptRateLimiter = generousLimiter(),
                            donationPageRateLimiter = generousLimiter(),
                            mailDispatcher = noOpMailDispatcher(),
                            eventRegistrationAttemptRateLimiter = generousLimiter(),
                            eventRegistrationRateLimiter = generousLimiter(),
                            eventPageRateLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        test(
            "donationRange prelude: present with the correct min/max JSON when the PSP is Configured " +
                "and the range is usable, and the served bundle stays within the 20480-byte budget " +
                "INCLUDING this line (Review MINOR, Round 3 -- the pre-existing budget tests below never " +
                "exercised this branch, because testApp hardcodes PspConfigState.NotConfigured; see their " +
                "own comment for why the budget itself had to move to accommodate this)",
        ) {
            testAppWithPsp(PspConfigState.Configured(testPspConfig(maxCheckoutAmountEur = "10000.00"))) {
                val body = client.get("/embed/v1/lapis-widgets.js").bodyAsText()
                // 10000.00 clamps to EmbedDonationLimits.MAX_AMOUNT_EUR (500.00) -- see
                // EmbedDonationLimits.effectiveMaxAmountEur -- and both bounds are stripped of their
                // trailing ".00" (Review TRIVIAL fix, EmbedAssets.widgetJs).
                body shouldContain """window.__lapisEmbedDonationRangeV1={"min":"5","max":"500"};"""
                body.toByteArray(Charsets.UTF_8).size.toLong() shouldBeLessThanOrEqualTo 20480L
            }
        }

        test("donationRange prelude: absent when the PSP is NotConfigured") {
            testApp {
                val body = client.get("/embed/v1/lapis-widgets.js").bodyAsText()
                // The bare token also legitimately appears inside widgetJsTemplate itself
                // (hydrateDonate() reads `window.__lapisEmbedDonationRangeV1 || null`), so the
                // assertion is anchored on the PRELUDE's assignment shape specifically, not the token
                // alone -- that assignment is the only thing EmbedAssets.widgetJs ever omits.
                body shouldNotContain "window.__lapisEmbedDonationRangeV1={"
            }
        }

        test(
            "donationRange prelude: absent when the operator's own maximum sits below " +
                "EmbedDonationLimits.MIN_AMOUNT_EUR (rangeIsUsable == false) -- the donation form is " +
                "unusable either way, see EmbedAssets.widgetJs KDoc",
        ) {
            testAppWithPsp(PspConfigState.Configured(testPspConfig(maxCheckoutAmountEur = "1.00"))) {
                val body = client.get("/embed/v1/lapis-widgets.js").bodyAsText()
                // The bare token also legitimately appears inside widgetJsTemplate itself
                // (hydrateDonate() reads `window.__lapisEmbedDonationRangeV1 || null`), so the
                // assertion is anchored on the PRELUDE's assignment shape specifically, not the token
                // alone -- that assignment is the only thing EmbedAssets.widgetJs ever omits.
                body shouldNotContain "window.__lapisEmbedDonationRangeV1={"
            }
        }

        // Budget raised from 8192 to 12288 bytes in Welle V1.4.1b (Falle 7, plan §12.5) --
        // hydrateDonate() (the anonymous embed-widget donation form) grew the bundle beyond the
        // V1.4.1a ceiling. Raised again, 12288 -> 13312 bytes, by the Review MAJOR #3 fix (the
        // donate widget's 400-response handler now echoes the server's own, possibly-lowered
        // AMOUNT_OUT_OF_RANGE min/maxAmount instead of a hardcoded "5-500 EUR" string). Raised a
        // third time, 13312 -> 13824 bytes, by the Review MINOR Round 3 fix: the "served bundle"
        // assertion below only ever ran against `testApp`'s PspConfigState.NotConfigured, so it
        // never included the `window.__lapisEmbedDonationRangeV1` prelude line a Configured PSP
        // actually ships in production -- with that line (and the data-lapis-amounts fallback fix
        // right above), the 13312 ceiling left only single-digit bytes of headroom for a REAL
        // deployment (an operator with three allowed origins was already over it -- see the
        // donationRange test above). 13824 restored a realistic margin for both the raw file and
        // the served bundle with every prelude line a real installation can carry at once.
        // Raised a fourth time, 13824 -> 20480 bytes, in Welle V1.4.3.3 -- the fourth widget
        // (`hydrateEvent()`, the embeddable event-registration form: two labelled fields, a
        // honeypot, and a full set of German status/error strings for every outcome the
        // `POST /api/embed/v1/event/{slug}/registration` contract defines) added roughly 4.7 KB,
        // more than the original per-widget estimate -- German UI copy carries real weight in
        // UTF-8, and this widget's contract (unlike donate's single amount field) needed a
        // full name+email form with its own validation/error/success copy. Deliberately and
        // bounded, not stealth: the value is a named constant right here, not silently widened,
        // and the file stays unminified/readable (no minification used to "cheat" the budget
        // down) -- see `hydrateEvent()`'s own comments in `lapis-widgets.js` for the actual code.
        test("lapis-widgets.js resource file is at most 20480 bytes unminified") {
            widgetsJs.exists() shouldBe true
            widgetsJs.readBytes().size.toLong() shouldBeLessThanOrEqualTo 20480L
        }

        test("served bundle body is at most 20480 bytes INCLUDING the origin-allowlist prelude") {
            testApp {
                val body = client.get("/embed/v1/lapis-widgets.js").bodyAsText()
                body.toByteArray(Charsets.UTF_8).size.toLong() shouldBeLessThanOrEqualTo 20480L
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

        test(
            "lapis-widgets.js: an unparsable/all-invalid data-lapis-amounts falls back to the documented " +
                "default presets (filtered by the effective ceiling) BEFORE collapsing to a single minA " +
                "button (Review MINOR, Round 3: the two-step fallback regressed to a single step, see " +
                "docs/api/embed-widgets.adoc \"data-lapis-amounts\")",
        ) {
            val text = widgetsJs.readText()
            text shouldContain "if (amounts.length === 0) amounts = [10, 25, 50, 100].filter(function (n) { return n <= maxA; });"
            text shouldContain "if (amounts.length === 0) amounts = [minA];"
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

        // Review-Fund MEDIUM (Multi-Agent-Pipeline "Öffentliche Root-Seite für Lapis Cloud", fehlende
        // Testabdeckung): before this test, NO test anywhere asserted on the LINK FORM this file
        // generates -- only on its size budget, headers, and unrelated security tokens (the tests
        // above). That is exactly how a stale "/#/..." link generator (missing the "/app" prefix the
        // SPA has required since Welle V1.4.6, see PublicLandingRoutes KDoc) went unnoticed here even
        // though the Kotlin-side twin generator (EmbedIntegrationHttp.buildEmbedSnippet) DOES have a
        // test (EmbedIntegrationScreenTest) that was updated -- this served JS asset was not. Mirrors
        // the source-scan-for-a-forbidden-token pattern SocialPublicHtmlTest's "T6" test already
        // establishes for the Kotlin-rendered public HTML.
        test(
            "lapis-widgets.js: every internal same-origin hash-route link it generates carries the " +
                "mandatory \"/app\" prefix -- no bare \"/#/...\" link generator (a stale pre-Welle-V1.4.6 " +
                "shape that would strand the browser on the anonymous landing page instead of the SPA)",
        ) {
            val text = widgetsJs.readText()
            // Every occurrence of "#/" in the file must be immediately preceded by "/app" -- i.e. no
            // "#/" is reachable that is NOT part of an "/app#/..." literal. A naive `shouldNotContain
            // "/#/"` would miss this if a future edit introduced e.g. string concatenation instead of a
            // literal; scanning every "#/" occurrence's preceding chars is stricter.
            val hashRouteOccurrences = Regex("""#/""").findAll(text).toList()
            hashRouteOccurrences.isEmpty() shouldBe false
            hashRouteOccurrences.forEach { match ->
                val precedingStart = (match.range.first - 4).coerceAtLeast(0)
                text.substring(precedingStart, match.range.first) shouldBe "/app"
            }
        }

        test(
            "lapis-widgets.js: hydrateEvent exists, scan() dispatches data-lapis-widget=\"event\" to it, " +
                "the No-JS fallback is derived as /veranstaltung/<slug> (never a data-lapis-fallback-url " +
                "attribute -- that stays donate-widget-only, Falle F-6), the fetch omits credentials, and a " +
                "missing/placeholder slug logs a console.error instead of mounting (Falle F-5)",
        ) {
            val text = widgetsJs.readText()
            text shouldContain "function hydrateEvent(host)"
            text shouldContain "else if (kind === \"event\") hydrateEvent(host);"
            text shouldContain "\"/veranstaltung/\" + encodeURIComponent(slug)"
            text shouldContain "console.error(\"[lapis-widgets] data-lapis-event-slug fehlt"
            // hydrateEvent's OWN body never reads data-lapis-fallback-url -- scoped to that
            // function's text (not the whole file), since hydrateDonate legitimately reads it.
            val startIdx = text.indexOf("function hydrateEvent(host)")
            val endIdx = text.indexOf("\n  function scan()", startIdx)
            val hydrateEventBody = text.substring(startIdx, endIdx)
            hydrateEventBody shouldNotContain "data-lapis-fallback-url"
            hydrateEventBody shouldContain "credentials: \"omit\""
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
                    // Welle V1.4.1b -- die zwei neuen Spenden-Route-Dateien leben ebenfalls im
                    // `routes`-Paket, nicht im `embed`-Paket, und müssen daher explizit ergänzt
                    // werden (Security-Loop Gate 2: Scan über das GESAMTE embed-Paket UND die
                    // neuen routes-Dateien).
                    File(kotlinSourceDir(), "network/lapis/cloud/server/routes/EmbedDonationRoutes.kt"),
                    File(kotlinSourceDir(), "network/lapis/cloud/server/routes/EmbedDonationHtml.kt"),
                    // Welle V1.4.3.3 -- die vierte neue Routen-Datei lebt ebenfalls im `routes`-
                    // Paket, nicht im `embed`-Paket (see the two entries directly above).
                    File(kotlinSourceDir(), "network/lapis/cloud/server/routes/EmbedEventRoutes.kt"),
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
