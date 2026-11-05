package network.lapis.cloud.server.branding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Mirrors the real `lapis-client/src/jsMain/resources/index.html` shell's relevant markup. */
private val FIXTURE_HTML =
    """
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="utf-8">
        <title>Lapis Cloud</title>
        <script type="application/json" id="lapis-brand">{"title":"Lapis Cloud","logoUrl":null}</script>
        <script type="text/javascript" src="main.bundle.js"></script>
    </head>
    <body>
    <div id="lapis-client"></div>
    </body>
    </html>
    """.trimIndent()

private val DEFAULT_BRANDING = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)

/**
 * Exercises [BrandingHtml.inject] as a pure string transformer -- no I/O, no server, no client
 * (see that object's own KDoc "Two separate escaping contexts").
 */
class BrandingHtmlTest :
    FunSpec({
        test("default branding on the real index.html shape -> title stays byte-identical") {
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = DEFAULT_BRANDING)
            result shouldContain "<title>Lapis Cloud</title>"
        }

        test("default branding -> payload has logoUrl null, keycloakMode false and emergencyAdminLoginEnabled false (the defaults)") {
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = DEFAULT_BRANDING)
            result shouldContain
                """id="lapis-brand">{"title":"Lapis Cloud","logoUrl":null,"keycloakMode":false,"emergencyAdminLoginEnabled":false}</script>"""
        }

        // V1.7.2 sub-wave 2a -- the pre-login carrier for keycloakConfig.enabled, see BrandingHtml.inject KDoc "keycloakMode".
        test("keycloakMode=true -> payload carries it, title/logoUrl injection stays unaffected") {
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = DEFAULT_BRANDING, keycloakMode = true)
            result shouldContain
                """id="lapis-brand">{"title":"Lapis Cloud","logoUrl":null,"keycloakMode":true,"emergencyAdminLoginEnabled":false}</script>"""
        }

        // Review fix (MINOR 3) -- the pre-login carrier for keycloakConfig.emergencyAdminLoginEnabled.
        test("emergencyAdminLoginEnabled=true -> payload carries it independently of keycloakMode") {
            val result =
                BrandingHtml.inject(html = FIXTURE_HTML, brand = DEFAULT_BRANDING, keycloakMode = true, emergencyAdminLoginEnabled = true)
            result shouldContain
                """id="lapis-brand">{"title":"Lapis Cloud","logoUrl":null,"keycloakMode":true,"emergencyAdminLoginEnabled":true}</script>"""
        }

        test("custom title -> injected into both <title> and the JSON payload") {
            val brand = ResolvedBranding(title = "Partei der Vernunft", logoAvailable = false, logoPath = null)
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
            result shouldContain "<title>Partei der Vernunft</title>"
            result shouldContain "\"title\":\"Partei der Vernunft\""
        }

        test("logoAvailable true -> payload logoUrl is the fixed logo route, never the raw filesystem path") {
            val brand = ResolvedBranding(title = "ELB", logoAvailable = true, logoPath = "/app/branding/logo.svg")
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
            result shouldContain "\"logoUrl\":\"${BrandingHtml.LOGO_ROUTE_PATH}\""
            result shouldNotContain "/app/branding/logo.svg"
        }

        test("script-injection title is HTML-escaped inside <title> AND JSON-escaped inside the script payload") {
            val brand = ResolvedBranding(title = "<script>alert(1)</script>", logoAvailable = false, logoPath = null)
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)

            // <title> context: standard HTML entity escaping, no raw '<'/'>' survives.
            result shouldContain "<title>&lt;script&gt;alert(1)&lt;/script&gt;</title>"
            result shouldNotContain "<title><script>"

            // JSON-in-<script> context: '<' (and ONLY '<' -- escaping '>' too is unnecessary,
            // since a browser cannot recognize "</script>" as a closing tag without the leading
            // '<') becomes the JSON unicode escape <, so the literal substring "</script>"
            // can never appear inside the payload and prematurely close the surrounding <script>
            // element.
            result shouldContain "\\u003Cscript>alert(1)\\u003C/script>"
            result shouldNotContain "</script>alert"
            result shouldNotContain "\"title\":\"<script>"
        }

        test("HTML with neither marker -> returned unchanged, never throws") {
            val html = "<html><head></head><body>no markers here</body></html>"
            val result = BrandingHtml.inject(html = html, brand = ResolvedBranding(title = "X", logoAvailable = true, logoPath = "/x.svg"))
            result shouldBe html
        }

        test("HTML with a <title> but no #lapis-brand script -> title still injected, rest unchanged") {
            val html = "<html><head><title>Old</title></head><body></body></html>"
            val brand = ResolvedBranding(title = "New Title", logoAvailable = false, logoPath = null)
            val result = BrandingHtml.inject(html = html, brand = brand)
            result shouldContain "<title>New Title</title>"
            result shouldBe "<html><head><title>New Title</title></head><body></body></html>"
        }

        test("empty title never throws, degrades gracefully") {
            val brand = ResolvedBranding(title = "", logoAvailable = false, logoPath = null)
            val result = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
            result shouldContain "<title></title>"
        }

        test("inject is idempotent-safe to call twice with the same input (pure function, no shared mutable render state)") {
            val brand = ResolvedBranding(title = "Partei der Vernunft", logoAvailable = false, logoPath = null)
            val first = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
            val second = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
            first shouldBe second
        }

        context("V1.9.265 logos block") {
            fun file(v: LogoVariant) = ResolvedLogoFile(path = "/x/${v.slug}.svg", extension = "svg")

            test("no variants -> the payload is byte-identical to the pre-variants string (legacy logo or none)") {
                BrandingHtml.inject(html = FIXTURE_HTML, brand = DEFAULT_BRANDING) shouldContain
                    """{"title":"Lapis Cloud","logoUrl":null,"keycloakMode":false,"emergencyAdminLoginEnabled":false}</script>"""
                val legacy = ResolvedBranding(title = "ELB", logoAvailable = true, logoPath = "/app/branding/logo.svg")
                val out = BrandingHtml.inject(html = FIXTURE_HTML, brand = legacy)
                out shouldContain
                    """{"title":"ELB","logoUrl":"/api/branding/logo","keycloakMode":false,"emergencyAdminLoginEnabled":false}</script>"""
                out shouldNotContain "\"logos\""
            }

            test("all four variants -> exact JSON block (the wordmark carries the name in navbar and lockup)") {
                val brand =
                    ResolvedBranding(
                        title = "T",
                        logoAvailable = false,
                        logoPath = null,
                        variants = LogoVariant.entries.associateWith(::file),
                    )
                BrandingHtml.inject(html = FIXTURE_HTML, brand = brand) shouldContain
                    """"emergencyAdminLoginEnabled":false,"logos":{"signet":{"light":"/api/branding/logo/signet-light",""" +
                    """"dark":"/api/branding/logo/signet-dark"},"wordmark":{"light":"/api/branding/logo/wordmark-light",""" +
                    """"dark":"/api/branding/logo/wordmark-dark"},"wordmarkCarriesName":{"navbar":true,"lockup":true}}}</script>"""
            }

            test("a single variant fills every slot via the chain and never yields a null slot") {
                val brand =
                    ResolvedBranding(
                        title = "T",
                        logoAvailable = false,
                        logoPath = null,
                        variants = mapOf(LogoVariant.WORDMARK_DARK to file(LogoVariant.WORDMARK_DARK)),
                    )
                val out = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
                out shouldContain
                    """"logos":{"signet":{"light":"/api/branding/logo/wordmark-dark","dark":"/api/branding/logo/wordmark-dark"}"""
                out shouldContain """"wordmarkCarriesName":{"navbar":true,"lockup":true}"""
                out.substringAfter("\"logos\"") shouldNotContain "null"
            }

            test("a title with </script> stays safe with variants present") {
                val brand =
                    ResolvedBranding(
                        title = "</script><script>alert(1)</script>",
                        logoAvailable = false,
                        logoPath = null,
                        variants = mapOf(LogoVariant.SIGNET_LIGHT to file(LogoVariant.SIGNET_LIGHT)),
                    )
                val out = BrandingHtml.inject(html = FIXTURE_HTML, brand = brand)
                out shouldNotContain "<script>alert(1)"
                out shouldContain "\\u003C/script>"
            }
        }
    })
