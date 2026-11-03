package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.body
import kotlinx.html.stream.createHTML
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.LogoVariant
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.branding.ResolvedLogoFile
import network.lapis.cloud.server.theme.repoFile

/**
 * V1.9.267 -- the public header logo is 32 px (the app navbar size). CSS-text assertions and arithmetic only: nothing here
 * measures a real layout (there is no browser behind the server HTML).
 */
class PublicBrandLogoSizeTest :
    FunSpec({
        val baseUrl = "https://cloud.lapisproject.dev"
        val css = SocialPublicHtml.STYLESHEET

        fun rule(selector: String): String {
            val start = css.indexOf("$selector {")
            check(start >= 0) { "rule not found: $selector" }
            return css.substring(start, css.indexOf('}', start))
        }

        fun chrome(branding: ResolvedBranding): String =
            createHTML(prettyPrint = false).body {
                with(PublicChrome) {
                    renderChrome(
                        lang = PublicLanguage.DE,
                        active = null,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = "/",
                        nav = PublicNavAvailability.NONE,
                    )
                }
            }

        fun authChrome(branding: ResolvedBranding): String =
            createHTML(prettyPrint = false).body {
                with(PublicChrome) { renderAuthChrome(lang = PublicLanguage.DE, baseUrl = baseUrl, branding = branding) }
            }

        fun brand(html: String): String {
            val start = html.indexOf("<a href=\"$baseUrl\" class=\"chrome-brand\"")
            return html.substring(start, html.indexOf("</a>", start))
        }

        fun variants(vararg v: LogoVariant) =
            ResolvedBranding(
                title = "Mein Verein",
                logoAvailable = false,
                logoPath = null,
                variants = v.associateWith { ResolvedLogoFile(path = "/x/${it.slug}.png", extension = "png") },
            )

        test("rule shape: .chrome-logo follows the token, no display, no max-height") {
            val r = rule(".chrome-logo")
            r shouldContain "height: var(--lapis-brand-logo-size)"
            r shouldContain "width: auto"
            r shouldContain "max-width: 180px"
            r shouldContain "object-fit: contain"
            r shouldNotContain "display:"
            css shouldNotContain "max-height: 22px"
            css shouldContain "--lapis-brand-logo-size: 2rem"
        }

        test("rule shape: mark svg, phone cap and untouched neighbours") {
            val m = rule(".chrome-mark svg")
            m shouldContain "width: var(--lapis-brand-logo-size)"
            m shouldContain "height: var(--lapis-brand-logo-size)"
            css shouldContain "@media (max-width: 767.98px) { .chrome-logo { max-width: 120px; } }"
            css shouldContain "max-width: 30rem"
            css shouldContain ".chrome-logo--dark { display: none; }"
            css shouldContain "@media (prefers-color-scheme: dark) { .chrome-logo--light { display: none; } "
            css shouldContain ".chrome-logo--dark { display: inline; } }"
        }

        test("the stylesheet contains no stray interpolation") {
            css shouldNotContain "\${"
            css shouldNotContain "PublicBrandLogoSize"
        }

        listOf("chrome" to ::chrome, "auth chrome" to ::authChrome).forEach { (name, render) ->
            test("$name: mark variant is 32 x 32") {
                val b = brand(render(ResolvedBranding(title = "Mein Verein", logoAvailable = false, logoPath = null)))
                b shouldContain "chrome-brand"
                b shouldContain "width=\"32\" height=\"32\""
                b shouldContain "Mein Verein"
            }

            test("$name: logo images carry no width or height attribute; alt is the title") {
                val one = brand(render(ResolvedBranding(title = "Mein Verein", logoAvailable = true, logoPath = "/data/logo.png")))
                one shouldContain "alt=\"Mein Verein\""
                one shouldNotContain "width="
                one shouldNotContain "height="
                val pair = brand(render(variants(LogoVariant.WORDMARK_LIGHT, LogoVariant.WORDMARK_DARK)))
                pair shouldContain "chrome-logo--light"
                pair shouldContain "chrome-logo--dark"
                pair shouldNotContain "width="
                pair shouldNotContain "height="
            }
        }

        test("the brand link goes to the base URL") {
            val html = chrome(ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null))
            html shouldContain "<a href=\"$baseUrl\" class=\"chrome-brand\""
        }

        test("every renderChrome caller links the shared stylesheet through the one helper") {
            val dir = repoFile("lapis-server/src/main/kotlin/network/lapis/cloud/server/routes")
            val callers =
                dir
                    .listFiles { f -> f.name.endsWith(".kt") }!!
                    .filter { f ->
                        f.name != "PublicChrome.kt" &&
                            f.readText().let { "renderChrome(" in it || "renderAuthChrome(" in it }
                    }.map { it.name }
                    .sorted()
            callers shouldBe
                listOf(
                    "ArticlePublicHtml.kt",
                    "AuthPageHtml.kt",
                    "EventPublicHtml.kt",
                    "LegalHtml.kt",
                    "PublicLandingHtml.kt",
                    "PublicOverviewHtml.kt",
                    "PublicTransparencyHtml.kt",
                    "SocialPublicHtml.kt",
                )
            callers.forEach { n -> (dir.resolve(n).readText().contains("publicStylesheetLinks()")) shouldBe true }
        }

        test("budget arithmetic (not a measurement)") {
            (PublicBrandLogoSize.SIZE_PX * 3.7 <= PublicBrandLogoSize.PHONE_MAX_WIDTH_PX) shouldBe true
            (PublicBrandLogoSize.PHONE_MAX_WIDTH_PX <= PublicBrandLogoSize.MAX_WIDTH_PX) shouldBe true
            (PublicBrandLogoSize.SIZE_PX <= 44) shouldBe true
            (390 - 48 >= PublicBrandLogoSize.PHONE_MAX_WIDTH_PX) shouldBe true
        }
    })
