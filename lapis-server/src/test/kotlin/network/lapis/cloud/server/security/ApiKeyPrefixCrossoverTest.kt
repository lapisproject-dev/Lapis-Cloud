package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.rpc.UnauthenticatedException

private val ADMIN_ID = kotlin.uuid.Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * V1.3.1 "API-Fundament, lesend", Design-Team decision #3 ("harte Trennung", now Code+Test instead
 * of mere convention) -- both directions of the guarantee that a session token and an API key can
 * NEVER be resolved through the wrong function, regardless of what either underlying store would
 * otherwise do with a matching hash.
 */
class ApiKeyPrefixCrossoverTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    routing {
                        get("/test/resolve-session") {
                            val result =
                                runCatching { resolveCurrentMember(call) }
                                    .fold(onSuccess = { "OK:${it.memberId}" }, onFailure = { "FAIL:${it::class.simpleName}" })
                            call.respondText(result)
                        }
                        get("/test/resolve-api-key") {
                            val result =
                                when (val r = resolveApiKey(call)) {
                                    is ApiKeyStore.Resolution.Valid -> "VALID:${r.principal.apiKeyId}"
                                    is ApiKeyStore.Resolution.Unknown -> "UNKNOWN"
                                    is ApiKeyStore.Resolution.Revoked -> "REVOKED"
                                    is ApiKeyStore.Resolution.Expired -> "EXPIRED"
                                }
                            call.respondText(result)
                        }
                    }
                }
                block()
            }
        }

        test("a valid, non-revoked session token used as a Bearer token is NOT mistakenly resolved as an API key") {
            testApp {
                val session = SessionStore.createSession(ADMIN_ID)
                val response = client.get("/test/resolve-api-key") { header("Authorization", "Bearer ${session.rawToken}") }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "UNKNOWN"
            }
        }

        test(
            "a valid, non-revoked API key used as a Bearer token is NEVER resolved by resolveCurrentMember (throws UnauthenticatedException, not resolved as a member)",
        ) {
            testApp {
                val issued = ApiKeyStore.issue(label = "Crossover", createdByMemberId = ADMIN_ID)
                val response = client.get("/test/resolve-session") { header("Authorization", "Bearer ${issued.rawKey}") }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "FAIL:${UnauthenticatedException::class.simpleName}"
            }
        }

        test("a genuine session token still resolves correctly via resolveCurrentMember (no false-positive rejection)") {
            testApp {
                val session = SessionStore.createSession(ADMIN_ID)
                val response = client.get("/test/resolve-session") { header("Authorization", "Bearer ${session.rawToken}") }
                response.bodyAsText() shouldBe "OK:$ADMIN_ID"
            }
        }

        test("a genuine API key still resolves correctly via resolveApiKey (no false-positive rejection)") {
            testApp {
                val issued = ApiKeyStore.issue(label = "Genuine", createdByMemberId = ADMIN_ID)
                val response = client.get("/test/resolve-api-key") { header("Authorization", "Bearer ${issued.rawKey}") }
                response.bodyAsText() shouldBe "VALID:${issued.id}"
            }
        }

        test("Authorization: BEARER (uppercase schema) still works for both a session token and an API key") {
            testApp {
                val session = SessionStore.createSession(ADMIN_ID)
                val sessionResponse = client.get("/test/resolve-session") { header("Authorization", "BEARER ${session.rawToken}") }
                sessionResponse.bodyAsText() shouldBe "OK:$ADMIN_ID"

                val issued = ApiKeyStore.issue(label = "Uppercase Schema", createdByMemberId = ADMIN_ID)
                val apiKeyResponse = client.get("/test/resolve-api-key") { header("Authorization", "BEARER ${issued.rawKey}") }
                apiKeyResponse.bodyAsText() shouldBe "VALID:${issued.id}"
            }
        }
    })
