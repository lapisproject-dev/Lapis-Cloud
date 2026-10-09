package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.events.EventImportPolicy
import network.lapis.cloud.server.module

/** Welle V1.9.82 -- the size guard of the import route answers 413 BEFORE any handler (or Kilua) reads the body. */
class EventImportBodyLimitTest :
    FunSpec({
        val route = "${EVENT_IMPORT_ROUTE_PREFIX}0"

        suspend fun send(
            path: String,
            body: String,
        ): HttpStatusCode {
            var status: HttpStatusCode? = null
            testApplication {
                application {
                    installEventImportBodyLimit()
                    routing {
                        post(route) { call.respondText("reached") }
                        post("/rpc/otherServiceManager0") { call.respondText("reached") }
                    }
                }
                status = client.post(path) { setBody(body) }.status
            }
            return status!!
        }

        test("a request above the limit is refused with 413 and never reaches the handler") {
            val tooBig = "x".repeat((MAX_REQUEST_BYTES + 1).toInt())
            send(route, tooBig) shouldBe HttpStatusCode.PayloadTooLarge
        }

        test("a request up to the limit passes through") {
            send(route, "x".repeat(1024)) shouldBe HttpStatusCode.OK
            send(route, "x".repeat(EventImportPolicy.MAX_PAYLOAD_BYTES)) shouldBe HttpStatusCode.OK
        }

        test("a realistic double-encoded non-ASCII export close to 2 MiB (all chars as backslash-u escapes) is not refused") {
            // Python json.dumps default (ensure_ascii=True): every Cyrillic char is a 6-byte \\uXXXX escape in the file text.
            val fileText = "\\u0431".repeat(310_000) // about 1.77 MiB, below the 2 MiB payload limit
            (fileText.toByteArray(Charsets.UTF_8).size <= EventImportPolicy.MAX_PAYLOAD_BYTES) shouldBe true
            // Kilua: the parameter is a JSON string, and the envelope JSON-encodes that string again.
            val parameter = JsonPrimitive(fileText).toString()
            val wire = JsonPrimitive(parameter).toString()
            val wireBytes = wire.toByteArray(Charsets.UTF_8).size
            // it exceeded the former limit (payload + 512 KiB) ...
            (wireBytes > EventImportPolicy.MAX_PAYLOAD_BYTES + 512 * 1024) shouldBe true
            // ... and must now pass the guard
            (wireBytes <= MAX_REQUEST_BYTES) shouldBe true
            send(route, wire) shouldBe HttpStatusCode.OK
        }

        test("other RPC routes are not limited by this guard") {
            send("/rpc/otherServiceManager0", "x".repeat((MAX_REQUEST_BYTES + 1).toInt())) shouldBe HttpStatusCode.OK
        }

        test("full application: the real Kilua route of IEventImportService is guarded (413 above the limit, no 413 below it)") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val tooBig = "x".repeat((MAX_REQUEST_BYTES + 1).toInt())
                client
                    .post(route) {
                        contentType(ContentType.Application.Json)
                        setBody(tooBig)
                    }.status shouldBe HttpStatusCode.PayloadTooLarge
                val small =
                    client.post(route) {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                // not authenticated / malformed, but it got past the size guard and into Kilua (a 404 would mean the route name drifted)
                (small.status != HttpStatusCode.PayloadTooLarge) shouldBe true
                (small.status != HttpStatusCode.NotFound) shouldBe true
            }
        }

        test("full application: alternative path spellings of the real route are guarded too") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val tooBig = "x".repeat((MAX_REQUEST_BYTES + 1).toInt())
                val spellings =
                    listOf(
                        "http://localhost//rpc/routeEventImportServiceManager0",
                        "/rpc//routeEventImportServiceManager0",
                        "/rpc/routeEventImport%53erviceManager0",
                        "/%72pc/routeEventImportServiceManager0",
                    )
                for (path in spellings) {
                    client
                        .post(path) {
                            contentType(ContentType.Application.Json)
                            setBody(tooBig)
                        }.status shouldBe HttpStatusCode.PayloadTooLarge
                }
            }
        }

        test("path normalization mirrors routing: empty segments dropped, segments decoded, invalid encoding rejected") {
            normalizedPathSegments("//rpc//routeEventImport%53erviceManager0") shouldBe listOf("rpc", "routeEventImportServiceManager0")
            normalizedPathSegments("/rpc/routeEventImport%ZZ") shouldBe null
        }
    })
