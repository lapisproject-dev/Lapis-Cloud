package network.lapis.cloud.server.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.AuthService

private const val AI_ROUTE = "/rpc/routeAiAssistantServiceManager0"
private const val CONTROL_ROUTE = "/rpc/routeDocumentServiceManager0"

private suspend fun ApplicationTestBuilder.rpc(route: String) =
    client.post(route) {
        contentType(ContentType.Application.Json)
        setBody("""{"id":1,"jsonrpc":"2.0","method":"x","params":[]}""")
    }

/**
 * Kill-switch coverage (pattern of `MobileWebviewBridgeKillSwitchTest`, but at application level):
 * `IAiAssistantService` is registered **only** when the AI layer is operational. Kilua RPC still
 * exposes the generated route for an unregistered service, but with no handler behind it the call
 * fails with a 500 and never reaches any service logic -- verified here against a registered control
 * service and against the enabled configuration.
 */
class AiFeatureKillSwitchTest :
    FunSpec({
        val full =
            mapOf(
                AiConfig.ENV_ENABLED to "true",
                AiConfig.ENV_PROVIDER to "anthropic",
                AiConfig.ENV_MODEL to "some-model",
                AiConfig.ENV_API_KEY to "sk-test",
            )

        test("default configuration: the AI service is not registered, nothing of it is reachable") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }) }
                val ai = rpc(AI_ROUTE)
                ai.status shouldBe HttpStatusCode.InternalServerError
                ai.bodyAsText() shouldNotContain "Unauthenticated"
                // Control: a registered service answers through the normal RPC protocol.
                rpc(CONTROL_ROUTE).bodyAsText() shouldContain "UnauthenticatedException"
            }
        }

        test("enabled but with an incomplete profile: the server still starts, the service stays unregistered") {
            testApplication {
                application { module(aiConfig = AiConfig.load { if (it == AiConfig.ENV_ENABLED) "true" else null }) }
                rpc(AI_ROUTE).status shouldBe HttpStatusCode.InternalServerError
                rpc(CONTROL_ROUTE).status shouldBe HttpStatusCode.OK
            }
        }

        test("an invalid profile (bad base URL) starts without exception and stays unregistered") {
            testApplication {
                application { module(aiConfig = AiConfig.load { (full + (AiConfig.ENV_BASE_URL to "http://insecure.example"))[it] }) }
                rpc(AI_ROUTE).status shouldBe HttpStatusCode.InternalServerError
            }
        }

        test("fully configured: the service is registered and answers through the RPC protocol") {
            testApplication {
                application { module(aiConfig = AiConfig.load { full[it] }) }
                val response = rpc(AI_ROUTE)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "UnauthenticatedException"
            }
        }

        test("SessionInfoDto.aiAssistantEnabled follows the flag handed to AuthService") {
            val fixtures = AiTestFixtures()
            network.lapis.cloud.server.db.DatabaseConfig
                .connect()
            try {
                val member = fixtures.member()
                listOf(true, false).forEach { flag ->
                    testApplication {
                        application {
                            routing {
                                post("/test/session") {
                                    call.respondText(
                                        AuthService(call = call, aiAssistantEnabled = flag).getSessionInfo().aiAssistantEnabled.toString(),
                                    )
                                }
                            }
                        }
                        client.post("/test/session") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe flag.toString()
                    }
                }
            } finally {
                fixtures.dispose()
            }
        }

        test("AuthService defaults to the feature being off") {
            val fixtures = AiTestFixtures()
            network.lapis.cloud.server.db.DatabaseConfig
                .connect()
            try {
                val member = fixtures.member()
                testApplication {
                    application {
                        routing {
                            post(
                                "/test/session",
                            ) { call.respondText(AuthService(call = call).getSessionInfo().aiAssistantEnabled.toString()) }
                        }
                    }
                    client.post("/test/session") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe "false"
                }
            } finally {
                fixtures.dispose()
            }
        }
    })
