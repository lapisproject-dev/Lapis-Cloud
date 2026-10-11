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
import network.lapis.cloud.server.articles.ArticleImportPolicy
import network.lapis.cloud.server.module

/** Welle V1.9.105 -- the size guard of the article-import route answers 413 BEFORE any handler (or Kilua) reads the body, and leaves the ordinary article service alone. */
class ArticleImportBodyLimitTest :
    FunSpec({
        val route = "${ARTICLE_IMPORT_ROUTE_PREFIX}0"

        suspend fun send(
            path: String,
            body: String,
        ): HttpStatusCode {
            var status: HttpStatusCode? = null
            testApplication {
                application {
                    installArticleImportBodyLimit()
                    routing {
                        post(route) { call.respondText("reached") }
                        post("/rpc/routeArticleServiceManager0") { call.respondText("reached") }
                        post("/rpc/otherServiceManager0") { call.respondText("reached") }
                    }
                }
                status = client.post(path) { setBody(body) }.status
            }
            return status!!
        }

        test("a request above the limit is refused with 413 and never reaches the handler") {
            send(route, "x".repeat((ARTICLE_IMPORT_MAX_REQUEST_BYTES + 1).toInt())) shouldBe HttpStatusCode.PayloadTooLarge
        }

        test("a request up to the limit passes through") {
            send(route, "x".repeat(1024)) shouldBe HttpStatusCode.OK
            send(route, "x".repeat(ArticleImportPolicy.MAX_PAYLOAD_BYTES)) shouldBe HttpStatusCode.OK
        }

        test("a realistic double-encoded non-ASCII export close to 2 MiB (all chars as backslash-u escapes) is not refused") {
            val fileText = "\\u0431".repeat(310_000)
            (fileText.toByteArray(Charsets.UTF_8).size <= ArticleImportPolicy.MAX_PAYLOAD_BYTES) shouldBe true
            val wire = JsonPrimitive(JsonPrimitive(fileText).toString()).toString()
            (wire.toByteArray(Charsets.UTF_8).size <= ARTICLE_IMPORT_MAX_REQUEST_BYTES) shouldBe true
            send(route, wire) shouldBe HttpStatusCode.OK
        }

        test("the ORDINARY article service with a large body is not limited by this guard (the prefixes do not overlap)") {
            send("/rpc/routeArticleServiceManager0", "x".repeat((ARTICLE_IMPORT_MAX_REQUEST_BYTES + 1).toInt())) shouldBe HttpStatusCode.OK
            send("/rpc/otherServiceManager0", "x".repeat((ARTICLE_IMPORT_MAX_REQUEST_BYTES + 1).toInt())) shouldBe HttpStatusCode.OK
            isArticleImportRoute(listOf("rpc", "routeArticleServiceManager0")) shouldBe false
            isArticleImportRoute(listOf("rpc", "routeArticleImportServiceManager1")) shouldBe true
        }

        test("full application: the real Kilua route of IArticleImportService is guarded (413 above the limit, no 413 below it, no 404)") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val tooBig = "x".repeat((ARTICLE_IMPORT_MAX_REQUEST_BYTES + 1).toInt())
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
                (small.status != HttpStatusCode.PayloadTooLarge) shouldBe true
                (small.status != HttpStatusCode.NotFound) shouldBe true
                // a normal ArticleService call with a large body must NOT be answered with 413 by the import guard
                val normal =
                    client.post("/rpc/routeArticleServiceManager0") {
                        contentType(ContentType.Application.Json)
                        setBody(tooBig)
                    }
                (normal.status != HttpStatusCode.PayloadTooLarge) shouldBe true
            }
        }

        test("full application: alternative path spellings of the real route are guarded too") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val tooBig = "x".repeat((ARTICLE_IMPORT_MAX_REQUEST_BYTES + 1).toInt())
                for (path in listOf(
                    "http://localhost//rpc/routeArticleImportServiceManager0",
                    "/rpc//routeArticleImportServiceManager0",
                    "/rpc/routeArticleImport%53erviceManager0",
                    "/%72pc/routeArticleImportServiceManager0",
                )) {
                    client
                        .post(path) {
                            contentType(ContentType.Application.Json)
                            setBody(tooBig)
                        }.status shouldBe HttpStatusCode.PayloadTooLarge
                }
            }
        }
    })
