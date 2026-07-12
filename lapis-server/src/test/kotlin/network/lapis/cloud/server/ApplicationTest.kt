package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class ApplicationTest :
    FunSpec({
        test("root route responds with greeting") {
            testApplication {
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Hello from Lapis Cloud"
            }
        }
    })
