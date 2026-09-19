package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.branding.BrandingHtml
import network.lapis.cloud.server.branding.ResolvedBranding

private const val ID = "0123456789abcdef"

/** Mirrors the real `lapis-client/src/jsMain/resources/index.html` shell's relevant markup. */
private val FIXTURE =
    """
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="utf-8">
        <title>Lapis Cloud</title>
        <script type="application/json" id="lapis-brand">{"title":"Lapis Cloud","logoUrl":null}</script>
        <meta name="lapis-client-build" content="dev">
        <script type="text/javascript" src="main.bundle.js"></script>
    </head>
    <body>
    <div id="lapis-client"></div>
    </body>
    </html>
    """.trimIndent()

class ClientVersionHtmlTest :
    FunSpec({
        test("sets the meta content and the ?v= cache buster") {
            val result = ClientVersionHtml.inject(html = FIXTURE, buildId = ID)
            result shouldContain """<meta name="lapis-client-build" content="$ID">"""
            result shouldContain """src="main.bundle.js?v=$ID""""
            result shouldNotContain """content="dev""""
        }

        test("null build id -> input unchanged") {
            ClientVersionHtml.inject(html = FIXTURE, buildId = null) shouldBe FIXTURE
        }

        test("malformed build id -> input unchanged (fail closed, no escaping)") {
            listOf("", "XYZ", "0123456789ABCDEF", "\"><script>alert(1)</script>", "0123456789abcde").forEach {
                ClientVersionHtml.inject(html = FIXTURE, buildId = it) shouldBe FIXTURE
            }
        }

        test("meta marker missing -> bundle still versioned, no throw") {
            val html = FIXTURE.replace("""<meta name="lapis-client-build" content="dev">""", "")
            val result = ClientVersionHtml.inject(html = html, buildId = ID)
            result shouldContain """src="main.bundle.js?v=$ID""""
            result shouldNotContain "lapis-client-build"
        }

        test("bundle marker missing -> meta still set, no throw") {
            val html = FIXTURE.replace("""src="main.bundle.js"""", """src="other.js"""")
            val result = ClientVersionHtml.inject(html = html, buildId = ID)
            result shouldContain """content="$ID""""
            result shouldContain """src="other.js""""
        }

        test("meta without a content attribute -> unchanged meta, no throw") {
            val html = FIXTURE.replace("""<meta name="lapis-client-build" content="dev">""", """<meta name="lapis-client-build">""")
            val result = ClientVersionHtml.inject(html = html, buildId = ID)
            result shouldContain """<meta name="lapis-client-build">"""
            result shouldContain """src="main.bundle.js?v=$ID""""
        }

        test("content attribute of a LATER tag is never touched") {
            val html =
                FIXTURE.replace(
                    """<meta name="lapis-client-build" content="dev">""",
                    """<meta name="lapis-client-build"><meta content="keep">""",
                )
            ClientVersionHtml.inject(html = html, buildId = ID) shouldContain """<meta content="keep">"""
        }

        test("applying it twice does not stack ?v= parameters") {
            val once = ClientVersionHtml.inject(html = FIXTURE, buildId = ID)
            val twice = ClientVersionHtml.inject(html = once, buildId = ID)
            twice shouldBe once
            twice shouldNotContain "?v=$ID?v="
        }

        test("composes with BrandingHtml in either order") {
            val brand = ResolvedBranding(title = "Partei der Vernunft", logoAvailable = false, logoPath = null)
            val brandedFirst = ClientVersionHtml.inject(html = BrandingHtml.inject(html = FIXTURE, brand = brand), buildId = ID)
            val versionedFirst = BrandingHtml.inject(html = ClientVersionHtml.inject(html = FIXTURE, buildId = ID), brand = brand)
            brandedFirst shouldBe versionedFirst
            brandedFirst shouldContain "<title>Partei der Vernunft</title>"
            brandedFirst shouldContain "\"title\":\"Partei der Vernunft\""
            brandedFirst shouldContain """content="$ID""""
        }
    })
