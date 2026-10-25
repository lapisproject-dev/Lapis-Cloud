package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files

class ThemeRoutesTest :
    FunSpec({
        val fontBytes = ByteArray(4096) { (it % 251).toByte() }.also { ThemeTokens.WOFF2_MAGIC.copyInto(it) }
        val json = themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal")) }
        val theme = ThemeLoader.load(themeDir(json = json, fonts = mapOf("A.woff2" to fontBytes))).theme
        val fontHash = theme.fonts.getValue("A.woff2").hash12
        val distRoot = Files.createTempDirectory("theme-dist").toFile().also { it.resolve("theme.css").writeText("RAW CLIENT CSS") }

        fun io.ktor.server.application.Application.setup(t: ResolvedTheme = theme) {
            install(io.ktor.server.plugins.autohead.AutoHeadResponse)
            install(Compression) { excludeContentType(WOFF2_CONTENT_TYPE) }
            routing {
                registerThemeRoutes(t)
                staticFiles("/", distRoot)
            }
        }

        test("/theme.css serves the generated CSS, not the raw client file") {
            testApplication {
                application { setup() }
                val r = client.get("/theme.css")
                r.status shouldBe HttpStatusCode.OK
                r.headers[HttpHeaders.ContentType]!! shouldContain "text/css"
                r.headers["X-Content-Type-Options"] shouldBe "nosniff"
                r.headers[HttpHeaders.ETag] shouldBe theme.etag
                r.headers[HttpHeaders.Vary]!! shouldContain "Accept-Encoding"
                r.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                r.bodyAsText() shouldBe theme.css
            }
        }
        test("/theme.css answers 304 for a matching ETag and is immutable only for the current hash") {
            testApplication {
                application { setup() }
                client.get("/theme.css") { header(HttpHeaders.IfNoneMatch, theme.etag) }.status shouldBe HttpStatusCode.NotModified
                client.get("/theme.css") { header(HttpHeaders.IfNoneMatch, "W/\"other\"") }.status shouldBe HttpStatusCode.OK
                client.get("/theme.css?v=${theme.hash12}").headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
                client.get("/theme.css?v=deadbeef0000").headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                client.head("/theme.css").status shouldBe HttpStatusCode.OK
            }
        }
        test("a font is served uncompressed with a strong ETag and 304 support") {
            testApplication {
                application { setup() }
                val r = client.get("/theme-fonts/A.woff2?v=$fontHash") { header(HttpHeaders.AcceptEncoding, "gzip") }
                r.status shouldBe HttpStatusCode.OK
                r.headers[HttpHeaders.ContentType] shouldBe "font/woff2"
                r.headers[HttpHeaders.ContentEncoding] shouldBe null
                r.headers["X-Content-Type-Options"] shouldBe "nosniff"
                r.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
                val etag = r.headers[HttpHeaders.ETag]!!
                (etag.startsWith("\"") && !etag.startsWith("W/")) shouldBe true
                r.readRawBytes().contentEquals(fontBytes) shouldBe true
                client.get("/theme-fonts/A.woff2") { header(HttpHeaders.IfNoneMatch, etag) }.status shouldBe HttpStatusCode.NotModified
                client.get("/theme-fonts/A.woff2").headers[HttpHeaders.CacheControl] shouldBe "no-cache"
            }
        }
        test("unknown names, traversal attempts and malformed paths are 404 with no content") {
            testApplication {
                application { setup() }
                listOf(
                    "/theme-fonts/B.woff2",
                    "/theme-fonts/theme.json",
                    "/theme-fonts/OFL.txt",
                    "/theme-fonts/..%2Ftheme.json",
                    "/theme-fonts/%2e%2e%2ftheme.json",
                    "/theme-fonts/",
                    "/theme-fonts/a/b",
                    "/theme-fonts",
                ).forEach { path ->
                    val r = client.get(path)
                    r.status shouldBe HttpStatusCode.NotFound
                    r.bodyAsText().contains("RAW") shouldBe false
                }
            }
        }
        test("the default theme is served when nothing is configured") {
            testApplication {
                application { setup(ResolvedTheme.DEFAULT) }
                client.get("/theme.css").bodyAsText() shouldBe ResolvedTheme.DEFAULT.css
                client.get("/theme-fonts/A.woff2").status shouldBe HttpStatusCode.NotFound
            }
        }
        test("content type constant") {
            ContentType("font", "woff2").toString() shouldBe "font/woff2"
        }
    })
