package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.routes.SocialPublicHtml
import network.lapis.cloud.server.routes.renderAuthPage

/**
 * V1.9.247 -- the public pages follow the theme tokens. The shared stylesheet may contain NO colour literal except the few
 * allow-listed ones below (each with its reason), and no `rgba(`. Dead allow-list entries fail the test too.
 */
class PublicCssColourAllowListTest :
    FunSpec({
        val allowed =
            mapOf(
                "#fff" to "QR code ground: scannability / quiet zone, independent of the theme",
                "#2e7d32" to "status edge of the success callout (light): non-text contrast only, never a text or fill colour",
                "#4caf50" to "same edge in dark mode: #2e7d32 reaches only 2.85:1 on the dark sunken surface, this one more than 3:1",
            )

        // comments and data: URIs (mask images carry %23000 strokes) are not rendered colours
        fun stripped(): String =
            SocialPublicHtml.STYLESHEET
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""url\("data:[^"]*"\)"""), "")

        fun rule(
            css: String,
            selector: String,
        ): String = Regex("""(^|\n)\s*${Regex.escape(selector)}\s*\{([^}]*)}""").find(css)!!.groupValues[2]

        test("only allow-listed hex literals occur, and rgba( is forbidden") {
            val css = stripped()
            css.contains(Regex("""rgba?\(""")) shouldBe false
            val found = Regex("""#[0-9a-fA-F]{3,8}\b""").findAll(css).map { it.value.lowercase() }.toSet()
            (found - allowed.keys) shouldBe emptySet()
            (allowed.keys - found) shouldBe emptySet() // dead entries are deleted, not kept
        }
        test("the literals sit in their one permitted place") {
            val css = stripped()
            rule(css, ".event-qr img") shouldContain "#fff"
            rule(css, ".auth-callout--success") shouldContain "border-left-color: #2e7d32"
            Regex("""#2e7d32""").findAll(css).count() shouldBe 1
            Regex("""#4caf50""").findAll(css).count() shouldBe 1
            css.substringAfter("prefers-color-scheme: dark").substringAfter(".auth-callout--success").substringBefore("}") shouldContain
                "#4caf50"
            Regex("""#fff\b""").findAll(css).count() shouldBe 1
        }
        test("no inline style: no <style block in the stylesheet, none in the rendered pages") {
            stripped() shouldNotContain "<style"
            val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
            val auth = renderAuthPage(title = "Anmelden", branding = branding, baseUrl = "https://example.org") { div { p { +"x" } } }
            auth shouldNotContain "<style"
            auth shouldNotContain " style=\""
        }
        test("publicStylesheetLinks emits the page stylesheet before the theme, both present") {
            val head = createHTML().html { head { publicStylesheetLinks() } }
            val a = head.indexOf("""href="/s/assets/style.css"""")
            val b = head.indexOf("""href="/theme.css"""")
            (a >= 0 && b > a) shouldBe true
        }
        test("every public renderer builds its head through publicStylesheetLinks") {
            val routes = repoFile("lapis-server/src/main/kotlin/network/lapis/cloud/server/routes")
            listOf(
                "PublicLandingHtml.kt",
                "SocialPublicHtml.kt",
                "PublicTransparencyHtml.kt",
                "ArticlePublicHtml.kt",
                "EventPublicHtml.kt",
                "PublicOverviewHtml.kt",
                "EmbedHtml.kt",
                "LegalHtml.kt",
                "AuthPageHtml.kt",
            ).forEach { name ->
                (name to routes.resolve(name).readText().contains("publicStylesheetLinks()")) shouldBe (name to true)
            }
        }
        test("the default colours reach 4.5:1 for every pair the public pages rely on, in both modes") {
            val pairs =
                listOf(
                    "text" to "bg",
                    "text" to "surface",
                    "text" to "surface-sunken",
                    "muted" to "bg",
                    "muted" to "surface",
                    "muted" to "surface-sunken",
                    "accent" to "bg",
                    "accent" to "surface",
                    "accent" to "surface-sunken",
                    "accent-strong" to "bg",
                    "accent-strong" to "accent-soft",
                    "text" to "accent-soft",
                    "accent-contrast" to "accent",
                    "accent-contrast" to "accent-strong",
                    "bg" to "invalid",
                    "invalid" to "surface-sunken",
                    "invalid" to "bg",
                )
            listOf("light" to ThemeDefaults.LIGHT, "dark" to ThemeDefaults.DARK).forEach { (mode, map) ->
                pairs.forEach { (fg, bg) ->
                    val r = ThemeContrast.ratio(a = HexColor.parse(map.getValue(fg))!!, b = HexColor.parse(map.getValue(bg))!!)
                    println("CONTRAST $mode $fg on $bg = ${"%.2f".format(r)}")
                    (mode to fg to bg to (r >= 4.5)) shouldBe (mode to fg to bg to true)
                }
            }
        }
        test("the success edge colours reach 3:1 as non-text edges on the default grounds of their mode") {
            fun ratio(
                edge: String,
                ground: String,
            ) = ThemeContrast.ratio(a = HexColor.parse(edge)!!, b = HexColor.parse(ground)!!).also {
                println("CONTRAST edge $edge on $ground = ${"%.2f".format(it)}")
            }
            listOf("surface-sunken", "bg", "surface").forEach { (ratio("#2e7d32", ThemeDefaults.LIGHT.getValue(it)) >= 3.0) shouldBe true }
            listOf("surface-sunken", "bg", "surface").forEach { (ratio("#4caf50", ThemeDefaults.DARK.getValue(it)) >= 3.0) shouldBe true }
        }
    })
