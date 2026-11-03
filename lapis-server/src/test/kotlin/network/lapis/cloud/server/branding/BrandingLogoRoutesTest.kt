package network.lapis.cloud.server.branding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.registerBrandingLogoRoutes
import java.io.File
import java.nio.file.Files

/**
 * V1.9.265 -- the logo routes against REAL files. A `/{...}` catch-all registered AFTER the logo routes stands in for `staticFiles`: a
 * 404 from the logo handler must never turn into the catch-all's 200.
 */
class BrandingLogoRoutesTest :
    FunSpec({
        val dir: File = Files.createTempDirectory("brand-logo-routes").toFile().also { it.deleteOnExit() }

        fun write(
            name: String,
            bytes: ByteArray,
        ): File = File(dir, name).also { it.writeBytes(bytes) }

        val bytes = LogoVariant.entries.associateWith { "bytes-of-${it.slug}".toByteArray() }
        val extensions =
            mapOf(
                LogoVariant.SIGNET_LIGHT to "svg",
                LogoVariant.SIGNET_DARK to "png",
                LogoVariant.WORDMARK_LIGHT to "webp",
                LogoVariant.WORDMARK_DARK to "svg",
            )
        val contentTypes = mapOf("svg" to "image/svg+xml", "png" to "image/png", "webp" to "image/webp")

        fun branding(
            variants: Set<LogoVariant> = LogoVariant.entries.toSet(),
            legacy: File? = null,
        ): ResolvedBranding {
            val files =
                variants.associateWith {
                    val ext = extensions.getValue(it)
                    ResolvedLogoFile(path = write("${it.slug}.$ext", bytes.getValue(it)).path, extension = ext)
                }
            return ResolvedBranding(
                title = "T",
                logoAvailable = legacy != null,
                logoPath = legacy?.path,
                variants = files,
            )
        }

        suspend fun app(
            branding: ResolvedBranding,
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        registerBrandingLogoRoutes(branding = branding)
                        get("/{path...}") { call.respondText("CATCHALL") }
                    }
                }
                block()
            }
        }

        test("every variant slug -> 200, right content type, cache + nosniff headers, exact file bytes") {
            app(branding()) {
                for (variant in LogoVariant.entries) {
                    val response = client.get("/api/branding/logo/${variant.slug}")
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType]!!.substringBefore(';') shouldBe
                        contentTypes.getValue(extensions.getValue(variant))
                    response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=300"
                    response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    response.bodyAsBytes().toList() shouldBe bytes.getValue(variant).toList()
                }
            }
        }

        test("unknown, wrong-case, encoded-traversal and unconfigured slugs -> 404 with an empty body, never the catch-all") {
            val only = branding(variants = setOf(LogoVariant.SIGNET_LIGHT))
            app(only) {
                for (
                path in
                listOf(
                    "nope",
                    "SIGNET-LIGHT",
                    "Signet-Light",
                    "%2e%2e",
                    "..%2f",
                    "..%2fsignet-light",
                    "signet-light%2f..%2f..",
                    "signet-dark", // valid slug, not configured
                    "wordmark-light",
                    "%20signet-light",
                    "signet-light%00",
                )
                ) {
                    val response = client.get("/api/branding/logo/$path")
                    response.status shouldBe HttpStatusCode.NotFound
                    val body = response.bodyAsText()
                    body shouldBe ""
                    body shouldNotContain dir.path
                    body shouldNotContain "CATCHALL"
                }
            }
        }

        test("legacy route is unchanged: the legacy file wins") {
            val legacy = write("legacy.png", "legacy-bytes".toByteArray())
            app(branding(legacy = legacy)) {
                val response = client.get("/api/branding/logo")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType]!!.substringBefore(';') shouldBe "image/png"
                response.bodyAsText() shouldBe "legacy-bytes"
            }
        }

        test("without a legacy logo the legacy route serves the light-signet chain; with nothing at all it is 404") {
            app(branding(variants = setOf(LogoVariant.WORDMARK_DARK))) {
                val response = client.get("/api/branding/logo")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsBytes().toList() shouldBe bytes.getValue(LogoVariant.WORDMARK_DARK).toList()
            }
            app(ResolvedBranding(title = "T", logoAvailable = false, logoPath = null)) {
                val response = client.get("/api/branding/logo")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldBe ""
            }
        }
    })
