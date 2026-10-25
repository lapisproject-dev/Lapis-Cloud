package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.html.div
import kotlinx.html.p
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.legal.LegalConfig
import network.lapis.cloud.server.routes.LegalHtml
import network.lapis.cloud.server.routes.PublicLanguage
import network.lapis.cloud.server.routes.PublicNavAvailability
import network.lapis.cloud.server.routes.SocialPublicHtml
import network.lapis.cloud.server.routes.renderAuthPage

/**
 * V1.9.216 tripwires for the public pages: every `style-src 'self'` policy also needs `font-src 'self'` (theme fonts), the stylesheet
 * link of a public page goes through [publicStylesheetLinks] only, and the shared stylesheet uses the theme tokens for the parts a
 * theme is meant to restyle.
 */
class PublicThemeTripwireTest :
    FunSpec({
        val sources = repoFile("lapis-server/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("every file with a style-src 'self' policy also grants font-src 'self'") {
            val offenders =
                sources
                    .filter { f ->
                        val text = f.readText()
                        // policy strings only: they contain the directive followed by a semicolon
                        text.contains("style-src 'self';") && !text.contains("font-src 'self'")
                    }.map { it.name }
            offenders shouldBe emptyList()
        }
        test("no raw link to the shared stylesheet outside PublicThemeLinks") {
            val offenders =
                sources
                    .filter { it.name != "PublicThemeLinks.kt" }
                    .filter {
                        it.readText().contains(
                            "href = \"/s/assets/style.css\"",
                        )
                    }.map { it.name }
            offenders shouldBe emptyList()
        }
        test("a public page links the theme after the shared stylesheet and has font-src in its policy source") {
            val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
            val auth = renderAuthPage(title = "Anmelden", branding = branding, baseUrl = "https://example.org") { div { p { +"x" } } }
            val a = auth.indexOf("""href="/s/assets/style.css"""")
            val b = auth.indexOf("""href="/theme.css"""")
            (a in 0 until b) shouldBe true
            val legal =
                LegalHtml.imprintPage(
                    legal = LegalConfig.load { null },
                    baseUrl = "https://example.org",
                    branding = branding,
                    lang = PublicLanguage.DEFAULT,
                    nav = PublicNavAvailability.NONE,
                )
            legal shouldContain """<link href="/theme.css" rel="stylesheet">"""
        }
        test("the shared stylesheet restyles chrome, fonts and primary buttons through tokens, without literal fallbacks") {
            val css = SocialPublicHtml.STYLESHEET

            fun rule(selector: String): String = Regex("""(^|\n)\s*${Regex.escape(selector)}\s*\{([^}]*)}""").find(css)!!.groupValues[2]
            rule(".chrome") shouldContain "var(--lapis-nav)"
            rule(".chrome") shouldContain "var(--lapis-nav-text)"
            rule(".chrome-wordmark") shouldContain "var(--lapis-font-display)"
            rule("body") shouldContain "var(--lapis-font-body)"
            rule(".auth-primary") shouldContain "var(--lapis-accent)"
            rule(".auth-primary") shouldContain "var(--lapis-accent-contrast)"
            rule(".event-submit") shouldContain "var(--lapis-accent)"
            css.contains("var(--lapis-nav,") shouldBe false
            css.contains(Regex("""var\(--lapis-[a-z-]+\s*,""")) shouldBe false
        }
    })
