package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.body
import kotlinx.html.stream.createHTML
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.shared.branding.LapisCloudMark

/**
 * V1.9.214 -- the brand rule of the public chrome: an operator logo wins; without one the Lapis Cloud mark shows next to
 * the title, whatever the title is. Pure rendering tests (no `testApplication`, no DB).
 */
class PublicChromeBrandTest :
    FunSpec({
        val baseUrl = "https://cloud.lapisproject.dev"

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

        val noLogo = { title: String -> ResolvedBranding(title = title, logoAvailable = false, logoPath = null) }
        val withLogo = ResolvedBranding(title = "Mein Verein", logoAvailable = true, logoPath = "/data/logo.png")

        fun brand(html: String): String {
            val start = html.indexOf("<a href=\"$baseUrl\" class=\"chrome-brand\"")
            return html.substring(start, html.indexOf("</a>", start))
        }

        listOf("chrome" to ::chrome, "auth chrome" to ::authChrome).forEach { (name, render) ->
            test("$name: custom title without a logo shows the mark and the wordmark") {
                val b = brand(render(noLogo("Mein Verein")))
                b shouldContain "<svg "
                b shouldContain "chrome-mark"
                b shouldContain "aria-hidden=\"true\""
                b shouldContain "width=\"22\""
                b shouldContain "Mein Verein"
            }

            test("$name: default title without a logo shows the mark and Lapis Cloud") {
                val b = brand(render(noLogo(BrandConfig.DEFAULT_TITLE)))
                b shouldContain "<svg "
                b shouldContain BrandConfig.DEFAULT_TITLE
            }

            test("$name: an operator logo wins -- image only, no mark") {
                val b = brand(render(withLogo))
                b shouldContain "<img "
                b shouldContain "chrome-logo"
                b shouldNotContain "<svg"
                b shouldNotContain "chrome-mark"
            }
        }

        test("the mark uses the shared geometry and dark palette, presentation attributes only") {
            val svg = createHTML(prettyPrint = false).body { with(LapisCloudMarkHtml) { lapisCloudMark() } }
            for (d in listOf(
                LapisCloudMark.CLOUD_D,
                LapisCloudMark.F1_D,
                LapisCloudMark.F2_D,
                LapisCloudMark.F3_D,
                LapisCloudMark.F4_D,
                LapisCloudMark.F5_D,
                LapisCloudMark.F6_D,
                LapisCloudMark.SPARK_D,
            )) {
                svg shouldContain "d=\"$d\""
            }
            svg shouldContain "viewBox=\"${LapisCloudMark.VIEW_BOX}\""
            svg shouldContain "fill=\"${LapisCloudMark.DarkPalette.CLOUD_FILL}\""
            svg shouldContain "fill=\"${LapisCloudMark.DarkPalette.SPARK}\""
            svg shouldNotContain "style="
            svg shouldNotContain "<img"
            svg shouldNotContain "<script"
            Regex("<svg ").findAll(svg).count() shouldBe 1
        }
    })
