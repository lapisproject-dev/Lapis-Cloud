package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.clientversion.ClientShell
import java.io.File
import java.nio.file.Files

class ThemeHtmlTest :
    FunSpec({
        val index = repoFile("lapis-client/src/jsMain/resources/index.html").readText()
        val theme = ResolvedTheme.DEFAULT

        test("the real index.html carries the marker exactly once") {
            Regex("""id="lapis-theme-css"""").findAll(index).count() shouldBe 1
        }
        test("the link gets the content hash and nothing else changes") {
            val out = ThemeHtml.inject(html = index, theme = theme)
            out shouldContain """<link rel="stylesheet" href="/theme.css?v=${theme.hash12}" id="lapis-theme-css">"""
            out.replace("/theme.css?v=${theme.hash12}", "/theme.css") shouldBe index
        }
        test("a missing marker leaves the HTML unchanged and does not throw") {
            val html = "<html><head></head></html>"
            ThemeHtml.inject(html = html, theme = theme) shouldBe html
            ThemeHtml.inject(html = html, theme = theme) shouldBe html
        }
        test("a tag without href is left alone") {
            val html = """<head><link id="lapis-theme-css"></head><script src="x"></script>"""
            ThemeHtml.inject(html = html, theme = theme) shouldBe html
        }
        test("the shell stamps the theme link after the icons and before the bundle, next to branding and version") {
            val dist = Files.createTempDirectory("theme-shell").toFile()
            File(dist, "index.html").writeText(index)
            File(dist, "main.bundle.js").writeText("console.log(1)")
            val shell =
                ClientShell.load(
                    clientDistRoot = dist,
                    branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                    theme = theme,
                )
            val html = shell.indexHtml!!
            val icon = html.indexOf("apple-touch-icon.png")
            val link = html.indexOf("/theme.css?v=${theme.hash12}")
            val bundle = html.indexOf("main.bundle.js?v=")
            (icon in 0 until link) shouldBe true
            (link < bundle) shouldBe true
            html shouldContain "name=\"lapis-client-build\" content=\"${shell.buildId}\""
            html shouldNotContain "href=\"/theme.css\""
        }
    })
