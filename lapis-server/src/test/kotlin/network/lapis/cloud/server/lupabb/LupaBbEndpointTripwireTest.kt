package network.lapis.cloud.server.lupabb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.226 -- the LupaBB host is ONE constant. No setting, no request value and no second URL can send the sync somewhere else (no SSRF), and the
 * only other shape the endpoint accepts is a loopback address for the tests.
 */
class LupaBbEndpointTripwireTest :
    FunSpec({
        test("the production endpoint is the one fixed https URL") {
            LupaBbEndpoint.PRODUCTION.baseUrl shouldBe "https://lupabb.de/verwaltung10/index.php/api/"
        }

        test("the endpoint refuses every other host, scheme, user info and the prefix tricks") {
            val bad =
                listOf(
                    "https://evil.example/api/",
                    "http://lupabb.de/api/",
                    "https://lupabb.de.evil.example/api/",
                    "https://lupabb.de@evil.example/api/",
                    "https://evil.example/lupabb.de/",
                    "http://10.0.0.1:8080/api/",
                    "http://169.254.169.254:80/latest/",
                    "http://127.0.0.1.evil.example:80/api/",
                    "http://localhost:80@evil.example/api/",
                    "http://127.0.0.1/api/",
                    "https://lupabb.de/api",
                    "https://lupabb.de/api/?x=1",
                    "file:///etc/passwd",
                    "not a url",
                    "",
                )
            for (url in bad) shouldThrow<IllegalArgumentException> { LupaBbEndpoint(url) }
            LupaBbEndpoint("http://127.0.0.1:8123/api/").baseUrl shouldBe "http://127.0.0.1:8123/api/"
        }

        test("src/main names the host only in LupaBbEndpoint.kt and creates exactly one endpoint") {
            val files = SourceScan.mainFiles()
            files
                .filter { it.readText().contains("lupabb.de") }
                .map { it.name }
                .toSet() shouldBe setOf("LupaBbEndpoint.kt")
            val creations =
                files
                    .filter { "/lupabb/" in it.path || it.name == "LupaBbSyncService.kt" || it.name == "Application.kt" }
                    .sumOf { Regex("""\bLupaBbEndpoint\(""").findAll(SourceScan.blank(it.readText())).count() }
            creations shouldBe 1
        }

        test("no file outside the lupabb package builds an HTTP client for it") {
            SourceScan
                .mainFiles()
                .filter { "/lupabb/" !in it.path }
                .filter { Regex("""\bLupaBbClient\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name } shouldBe listOf("LupaBbSyncService.kt")
        }
    })
