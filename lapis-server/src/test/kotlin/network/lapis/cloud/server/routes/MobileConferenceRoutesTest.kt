package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.module

private const val ADMIN_EMAIL = "amara.admin@example.org"

/**
 * [registerMobileConferenceRoutes] -- the thin, session-authenticated REST wrapper around
 * [network.lapis.cloud.server.rpc.ConferenceService] that the native mobile app's meeting list
 * uses instead of the Kilua-RPC channel the web SPA uses (see that function's own KDoc). Review-
 * Runde-2 MAJOR finding -- this route had NO test coverage at all before this file.
 *
 * Exercised through the REAL, fully-wired [module] (same idiom [AuthRoutesTest] establishes for
 * the `/api/auth` endpoints), authenticating via `Authorization: Bearer <sessionToken>` ONLY -- deliberately
 * NEVER a `lapis_session` cookie in these tests, because that is the one transport shape that
 * matters for this route: a native client with no cookie jar at all (see [LoginResponse
 * .sessionToken] KDoc). Every test environment's [network.lapis.cloud.server.conference
 * .ConferenceConfig] is `enabled=false` (no `LAPIS_LIVEKIT_*` env vars set) -- exactly the
 * production default before an operator configures LiveKit -- so LiveKit-dependent handlers
 * (list/get/join/leave) all take the [network.lapis.cloud.shared.rpc.ConflictException] branch.
 * That is still real, valuable coverage: it proves authentication/authorization and the exact
 * same [network.lapis.cloud.server.rpc.ConferenceService] business logic the web RPC path uses are
 * reached end to end, and that a business-rule rejection surfaces as a clean 409, never an
 * unhandled 500.
 */
class MobileConferenceRoutesTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        suspend fun ApplicationTestBuilder.loginAndGetSessionToken(): String {
            val response =
                client.post("/api/auth/login") {
                    setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                }
            val match = Regex(""""sessionToken"\s*:\s*"([^"]+)"""").find(response.bodyAsText())
            return requireNotNull(match) { "no sessionToken in: ${response.bodyAsText()}" }.groupValues[1]
        }

        test(
            "GET /api/mobile/v1/conference/availability without any credential (no cookie, no Authorization header) is rejected with 401",
        ) {
            testApplication {
                application { module() }

                val response = client.get("/api/mobile/v1/conference/availability")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "GET /api/mobile/v1/conference/availability authenticated ONLY via Authorization: Bearer <sessionToken> " +
                "(no cookie) succeeds and reports the real, unconfigured-by-default ConferenceConfig",
        ) {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val response =
                    client.get("/api/mobile/v1/conference/availability") { header(HttpHeaders.Authorization, "Bearer $token") }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"enabled\":false"
            }
        }

        test("GET /api/mobile/v1/conference/rooms without any credential is rejected with 401, never falls through to the DB") {
            testApplication {
                application { module() }

                client.get("/api/mobile/v1/conference/rooms").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "GET /api/mobile/v1/conference/rooms authenticated via Bearer token reaches the REAL ConferenceService " +
                "and surfaces the unconfigured-conference business rule as 409, not a raw 500",
        ) {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val response = client.get("/api/mobile/v1/conference/rooms") { header(HttpHeaders.Authorization, "Bearer $token") }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("GET /api/mobile/v1/conference/rooms/{roomId} without any credential is rejected with 401") {
            testApplication {
                application { module() }

                client.get("/api/mobile/v1/conference/rooms/some-room-id").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "GET /api/mobile/v1/conference/rooms/{roomId} authenticated via Bearer token also surfaces 409 for the unconfigured conference",
        ) {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val response =
                    client.get("/api/mobile/v1/conference/rooms/some-room-id") { header(HttpHeaders.Authorization, "Bearer $token") }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST /api/mobile/v1/conference/rooms/{roomId}/join without any credential is rejected with 401") {
            testApplication {
                application { module() }

                client.post("/api/mobile/v1/conference/rooms/some-room-id/join").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "POST /api/mobile/v1/conference/rooms/{roomId}/join authenticated via Bearer token surfaces 409 for the unconfigured conference",
        ) {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val response =
                    client.post("/api/mobile/v1/conference/rooms/some-room-id/join") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST /api/mobile/v1/conference/rooms/{roomId}/leave without any credential is rejected with 401") {
            testApplication {
                application { module() }

                client.post("/api/mobile/v1/conference/rooms/some-room-id/leave").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "POST /api/mobile/v1/conference/rooms/{roomId}/leave authenticated via Bearer token surfaces 409 for the unconfigured conference",
        ) {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val response =
                    client.post("/api/mobile/v1/conference/rooms/some-room-id/leave") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("a REVOKED Bearer token is rejected with 401 -- the mobile REST wrapper honors real session revocation, not just presence") {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val logoutResponse = client.post("/api/auth/logout") { header(HttpHeaders.Authorization, "Bearer $token") }
                logoutResponse.status shouldBe HttpStatusCode.NoContent

                val response =
                    client.get("/api/mobile/v1/conference/availability") { header(HttpHeaders.Authorization, "Bearer $token") }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a malformed Authorization header (missing Bearer prefix) is treated as absent, not as the raw token -- 401") {
            testApplication {
                application { module() }
                val token = loginAndGetSessionToken()

                val response = client.get("/api/mobile/v1/conference/availability") { header(HttpHeaders.Authorization, token) }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
