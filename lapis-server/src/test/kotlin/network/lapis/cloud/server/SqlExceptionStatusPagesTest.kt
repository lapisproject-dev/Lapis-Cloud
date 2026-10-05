package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.sql.SQLException

class SqlExceptionStatusPagesTest :
    FunSpec({
        fun withRoute(
            sqlState: String,
            block: suspend (io.ktor.client.HttpClient) -> Unit,
        ) = testApplication {
            application {
                install(StatusPages) { installSqlExceptionHandler() }
                routing {
                    get("/boom") { throw SQLException("ERROR: secret detail relation \"members\"", sqlState) }
                }
            }
            block(client)
        }

        test("lock timeout (55P03) maps to 503 with Retry-After and no SQL text") {
            withRoute(sqlState = "55P03") { client ->
                val response = client.get("/boom")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.headers[HttpHeaders.RetryAfter] shouldBe "5"
                response.bodyAsText() shouldNotContain "secret"
                response.bodyAsText() shouldNotContain "members"
            }
        }

        test("an unclassified SQL error (42P01) maps to a generic 500 without SQL text") {
            withRoute(sqlState = "42P01") { client ->
                val response = client.get("/boom")
                response.status shouldBe HttpStatusCode.InternalServerError
                response.headers[HttpHeaders.RetryAfter] shouldBe null
                response.bodyAsText() shouldBe "Internal server error."
            }
        }
    })
