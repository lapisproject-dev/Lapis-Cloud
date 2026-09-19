package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import java.io.File
import java.nio.file.Files

private val BRANDING = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)

private fun tempRoot(): File = Files.createTempDirectory("lapis-client-shell").toFile().also { it.deleteOnExit() }

/**
 * The REAL `lapis-client` `index.html` (read from the sibling module's source tree, Gradle runs
 * tests with `lapis-server` as working directory) must keep carrying both injection markers: if
 * someone edits the file and drops one, the version hint silently stops working in production --
 * this test is the tripwire.
 */
private val REAL_INDEX_HTML =
    File("../lapis-client/src/jsMain/resources/index.html")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/index.html") }

class ClientShellTest :
    FunSpec({
        test("no client build at all -> no shell, no build id") {
            val shell = ClientShell.load(clientDistRoot = tempRoot(), branding = BRANDING)
            shell.indexHtml shouldBe null
            shell.buildId shouldBe null
        }

        test("real index.html + bundle -> the stamped meta and the served id are the SAME value") {
            REAL_INDEX_HTML.isFile shouldBe true
            val root = tempRoot()
            File(root, "index.html").writeText(REAL_INDEX_HTML.readText())
            File(root, ClientBuildId.BUNDLE_FILE_NAME).writeText("console.log('bundle');")

            val shell = ClientShell.load(clientDistRoot = root, branding = BRANDING)

            val id = shell.buildId
            id shouldNotBe null
            ClientBuildId.isWellFormed(id) shouldBe true
            val html = shell.indexHtml!!
            html shouldContain """<meta name="lapis-client-build" content="$id">"""
            html shouldContain """<script type="text/javascript" src="main.bundle.js?v=$id"></script>"""
            // exactly ONE stamped occurrence: a comment repeating the marker literal would steal the
            // (first-match) injection from the real tag.
            html.split("?v=$id").size shouldBe 2
            html.split("content=\"$id\"").size shouldBe 2
            html shouldNotContain """content="dev""""
            html shouldContain "<title>Lapis Cloud</title>"
        }

        test("real index.html without a bundle -> shell served un-versioned (dev sentinel stays)") {
            val root = tempRoot()
            File(root, "index.html").writeText(REAL_INDEX_HTML.readText())

            val shell = ClientShell.load(clientDistRoot = root, branding = BRANDING)

            shell.buildId shouldBe null
            shell.indexHtml!! shouldContain """content="dev""""
            shell.indexHtml!! shouldContain """src="main.bundle.js""""
        }

        test("bundle without index.html -> id is still available (route answers although /app 404s)") {
            val root = tempRoot()
            File(root, ClientBuildId.BUNDLE_FILE_NAME).writeText("x")
            val shell = ClientShell.load(clientDistRoot = root, branding = BRANDING)
            shell.indexHtml shouldBe null
            shell.buildId shouldNotBe null
        }
    })
