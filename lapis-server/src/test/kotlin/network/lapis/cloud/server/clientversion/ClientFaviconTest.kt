package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.BrandingHtml
import network.lapis.cloud.server.branding.ResolvedBranding
import java.io.File

/**
 * V1.9.195 -- the favicon set of the SPA: the five files exist in the client resources and are served from the site root by the same
 * `staticFiles("/")` catch-all as every other client file (no new route), `index.html` links them in the agreed order with absolute
 * paths, the branding / client-version injection still works on that file, the SVGs carry no active content, and the theme rules for
 * the brand mark sit before the last `prefers-reduced-motion: reduce` block without adding a transition (R54).
 */
class ClientFaviconTest :
    FunSpec({
        fun resource(name: String): File =
            File("../lapis-client/src/jsMain/resources/$name")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/$name") }

        val indexHtml = resource("index.html").readText()
        val files = listOf("favicon.ico", "favicon.svg", "favicon-dark.svg", "favicon-32.png", "apple-touch-icon.png")

        test("index.html links the five icons in the agreed order, with absolute paths") {
            val links = Regex("""<link rel="(?:icon|apple-touch-icon)"[^>]*>""").findAll(indexHtml).map { it.value }.toList()
            links.shouldContainExactly(
                """<link rel="icon" href="/favicon.ico" sizes="32x32">""",
                """<link rel="icon" href="/favicon.svg" type="image/svg+xml" media="(prefers-color-scheme: light)">""",
                """<link rel="icon" href="/favicon-dark.svg" type="image/svg+xml" media="(prefers-color-scheme: dark)">""",
                """<link rel="icon" href="/favicon-32.png" type="image/png" sizes="32x32">""",
                """<link rel="apple-touch-icon" href="/apple-touch-icon.png" sizes="180x180">""",
            )
        }

        test("branding and client-version injection still work on the real index.html with the icon links") {
            val brand = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
            val branded = BrandingHtml.inject(html = indexHtml, brand = brand)
            val result = ClientVersionHtml.inject(html = branded, buildId = "0123456789abcdef")
            result shouldContain """<meta name="lapis-client-build" content="0123456789abcdef">"""
            result shouldContain """src="main.bundle.js?v=0123456789abcdef""""
            result shouldContain """<link rel="icon" href="/favicon.ico" sizes="32x32">"""
            result shouldContain """<link rel="apple-touch-icon" href="/apple-touch-icon.png" sizes="180x180">"""
        }

        test("the five icon files are served from the site root with sensible content types, and no CSP header is added") {
            val dir = resource("favicon.svg").parentFile
            testApplication {
                application { routing { staticFiles("/", dir) } }
                files.forEach { name ->
                    val response = client.get("/$name")
                    response.status shouldBe HttpStatusCode.OK
                    val type = response.headers[HttpHeaders.ContentType] ?: ""
                    when {
                        name.endsWith(".svg") -> type shouldContain "image/svg+xml"
                        name.endsWith(".png") -> type shouldContain "image/png"
                        else -> (type.contains("image/vnd.microsoft.icon") || type.contains("image/x-icon")) shouldBe true
                    }
                    response.headers["Content-Security-Policy"] shouldBe null
                    response.readRawBytes().size shouldBe resource(name).length().toInt()
                }
            }
        }

        test("the SVGs carry no active content, ids or external references, and the whole icon set stays small") {
            listOf("favicon.svg", "favicon-dark.svg").forEach { name ->
                val svg = resource(name).readText()
                svg shouldNotContain "<script"
                svg shouldNotContain "foreignObject"
                svg shouldNotContain "href"
                svg shouldNotContain "http://www.w3.org/1999/xlink"
                svg shouldNotContain " id="
            }
            (files.sumOf { resource(it).length() } < 64 * 1024) shouldBe true
        }

        test("theme.css: the brand-mark rules exist, sit before the last reduced-motion block, and add no transition") {
            val css = resource("theme.css").readText()
            val rules =
                listOf(
                    """html[data-theme="dark"] .lapis-brand-mark--light,""",
                    """html:not([data-theme="dark"]) .lapis-brand-mark--dark {""",
                )
            val lastReducedMotion = css.lastIndexOf("@media (prefers-reduced-motion: reduce)")
            (lastReducedMotion > 0) shouldBe true
            rules.forEach {
                val at = css.indexOf(it)
                (at in 1 until lastReducedMotion) shouldBe true
            }
            val markStart = css.indexOf(".lapis-brand-mark {")
            val markEnd = css.indexOf(".lapis-brand-text", markStart)
            (markEnd > markStart) shouldBe true
            css.substring(markStart, markEnd) shouldNotContain "transition"
            // the palette of the two variants (the sparkle colours stand for the whole set)
            css shouldContain ".lapis-brand-mark--light .bm-spark { fill: #c9a227; }"
            css shouldContain ".lapis-brand-mark--dark .bm-spark { fill: #e6bc3a; }"
            css shouldNotContain "color: var(--lapis-fleck);\n}\n\n.lapis-brand-text"
        }
    })
