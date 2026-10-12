package network.lapis.cloud.server.assets

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.routes.CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL
import network.lapis.cloud.server.routes.FLAG_ICONS_ASSET_DIR
import network.lapis.cloud.server.routes.FLAG_ICONS_CONTENT_SECURITY_POLICY
import network.lapis.cloud.server.routes.registerClientAssetRoutes
import network.lapis.cloud.shared.domain.CountryCodes
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * V1.9.121 -- guard for the 250 country flags under `lapis-client/src/jsMain/webAssets/flag-icons-v7` (flag-icons 7.5.0, 4x3 SVG). They are served
 * `immutable` for a year, so the set of files is pinned to exactly the selectable country codes, every file to its SHA-256 (`MANIFEST.sha256`, also
 * checked by the Gradle build on the staging and the webpack copy), the SVG content is scanned for active content, and provenance and licence are
 * documented. Lives in `lapis-server` because `jsTest` has no file system (same reason and two-path fallback as `RnnoiseAssetsTest`).
 */
class FlagAssetsTest :
    FunSpec({
        val clientRoot: File = File("../lapis-client").let { if (it.exists()) it else File("lapis-client") }
        val dir = File(clientRoot, "src/jsMain/webAssets/flag-icons-v7")
        val flags = File(dir, "4x3")
        val versionRule =
            "These files are served immutable for a year: if the bytes change, rename the directory to flag-icons-v8 " +
                "(CountryFlag.DIR, ClientAssetRoutes.kt, build.gradle.kts) and update this test."

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        test("4x3 holds exactly one SVG per selectable country code (lower case), and nothing else") {
            flags.isDirectory shouldBe true
            flags.listFiles().orEmpty().all { it.isFile } shouldBe true
            flags.listFiles().orEmpty().map { it.name } shouldContainExactlyInAnyOrder
                CountryCodes.SELECTABLE.map { it.lowercase() + ".svg" }
            CountryCodes.SELECTABLE.size shouldBe 250
            File(flags, "xk.svg").isFile shouldBe true
        }

        test("the directory holds exactly 4x3, the licence, the manifest and the provenance: no 1x1, no subdivisions, no CSS") {
            dir.listFiles().orEmpty().map { it.name } shouldContainExactlyInAnyOrder
                listOf("4x3", "LICENSES.txt", "MANIFEST.sha256", "PROVENANCE.adoc")
        }

        test("MANIFEST.sha256 lists every flag and every checksum matches the file (bytes unchanged). $versionRule") {
            val manifest =
                File(dir, "MANIFEST.sha256")
                    .readLines()
                    .filter { it.isNotBlank() }
                    .associate { it.substringAfter("  ") to it.substringBefore("  ") }
            manifest.keys shouldContainExactlyInAnyOrder CountryCodes.SELECTABLE.map { "4x3/${it.lowercase()}.svg" }
            manifest.forEach { (path, expected) ->
                val file = File(dir, path)
                (sha256(file.readBytes()) == expected) shouldBe true
            }
        }

        test("no flag contains active or external content, a source map or an embedded raster") {
            val forbidden =
                listOf(
                    Regex("<script", RegexOption.IGNORE_CASE),
                    Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE),
                    Regex("<foreignObject", RegexOption.IGNORE_CASE),
                    Regex("<!ENTITY", RegexOption.IGNORE_CASE),
                    Regex("<!DOCTYPE", RegexOption.IGNORE_CASE),
                    Regex("href\\s*=\\s*[\"']\\s*(https?:|//|javascript:|data:)", RegexOption.IGNORE_CASE),
                    Regex("url\\(\\s*[\"']?\\s*(https?:|//)", RegexOption.IGNORE_CASE),
                    Regex("sourceMappingURL", RegexOption.IGNORE_CASE),
                    Regex("<image\\b", RegexOption.IGNORE_CASE),
                    Regex("<iframe|<embed|<object", RegexOption.IGNORE_CASE),
                )
            val offenders =
                flags.listFiles().orEmpty().flatMap { file ->
                    val text = file.readText()
                    text.trimStart().startsWith("<svg") shouldBe true
                    forbidden.filter { it.containsMatchIn(text) }.map { "${file.name}: ${it.pattern}" }
                }
            offenders shouldBe emptyList()
        }

        test(
            "the licence is the MIT text with the copyright line, and the provenance names package, version, tarball hash and the rename rule",
        ) {
            val licence = File(dir, "LICENSES.txt").readText()
            licence shouldContain "The MIT License (MIT)"
            licence shouldContain "Copyright (c) 2013 Panayiotis Lipiridis"
            licence shouldContain "Permission is hereby granted, free of charge"
            val provenance = File(dir, "PROVENANCE.adoc").readText()
            listOf(
                "flag-icons",
                "7.5.0",
                "c0b80bf0e08006a60f56621d6bc49f8c7131f4d1fef6737a165a673431f4b518",
                "MIT",
                "flag-icons-v8",
                "2026-10-12",
                "MANIFEST.sha256",
            ).forEach { provenance shouldContain it }
        }

        test("the directory name stands in the three places and nowhere drifts: client constant, server route and Gradle") {
            FLAG_ICONS_ASSET_DIR shouldBe "flag-icons-v7"
            dir.name shouldBe FLAG_ICONS_ASSET_DIR
            val client = File(clientRoot, "src/jsMain/kotlin/network/lapis/cloud/client/CountryFlag.kt").readText()
            client shouldContain "const val DIR = \"$FLAG_ICONS_ASSET_DIR\""
            val gradle = File(clientRoot, "build.gradle.kts").readText()
            gradle shouldContain "into(\"$FLAG_ICONS_ASSET_DIR\")"
            gradle shouldContain "src/jsMain/webAssets/flag-icons-v7"
            gradle shouldContain "MANIFEST.sha256"
        }

        test(
            "served immutable as image/svg+xml with nosniff and a restrictive CSP; the licence is served too; unknown flags and traversal are 404",
        ) {
            val root = Files.createTempDirectory("lapis-flag-assets").toFile()
            try {
                File(root, "assets/$FLAG_ICONS_ASSET_DIR").apply { parentFile.mkdirs() }
                dir.copyRecursively(File(root, "assets/$FLAG_ICONS_ASSET_DIR"), overwrite = true)
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    val de = client.get("/assets/$FLAG_ICONS_ASSET_DIR/4x3/de.svg")
                    de.status shouldBe HttpStatusCode.OK
                    (de.headers[HttpHeaders.ContentType] ?: "") shouldContain "image/svg+xml"
                    de.headers.getAll(HttpHeaders.CacheControl)?.size shouldBe 1
                    de.headers[HttpHeaders.CacheControl] shouldBe CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL
                    de.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    de.headers["Content-Security-Policy"] shouldBe FLAG_ICONS_CONTENT_SECURITY_POLICY
                    sha256(de.bodyAsBytes()) shouldBe sha256(File(flags, "de.svg").readBytes())
                    client.get("/assets/$FLAG_ICONS_ASSET_DIR/LICENSES.txt").status shouldBe HttpStatusCode.OK
                    client.get("/assets/$FLAG_ICONS_ASSET_DIR/4x3/zz.svg").status shouldBe HttpStatusCode.NotFound
                    client.get("/assets/$FLAG_ICONS_ASSET_DIR/4x3/").status shouldBe HttpStatusCode.NotFound
                    client.get("/assets/$FLAG_ICONS_ASSET_DIR/../../../etc/passwd").status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                root.deleteRecursively()
            }
        }
    })
