package network.lapis.cloud.server.rpc

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
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.OnlineSessionCountsSupport
import network.lapis.cloud.shared.domain.AccountRole
import kotlin.uuid.Uuid

/** V1.9.215 -- the online-counts service on the RAW RPC wire (full `module()`). `/rpc/routeOnlineCountsServiceManager0` is `onlineCounts`. */
private const val ONLINE_COUNTS_ROUTE = "/rpc/routeOnlineCountsServiceManager0"
private const val ONLINE_COUNTS_BODY = """{"id":1,"jsonrpc":"2.0","method":"onlineCounts","params":[]}"""
private val UUID_PATTERN = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

class OnlineCountsRpcWireTest :
    FunSpec({
        val fixtures = MemberStatisticsFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterEach {
            OnlineSessionCountsSupport.cleanUp()
            fixtures.cleanUp()
        }

        test("ADMIN gets counts only: the declared fields, no id, no name, no e-mail, no UUID") {
            testApplication {
                application { module() }
                val admin = fixtures.member(role = AccountRole.ADMIN)
                OnlineSessionCountsSupport.session(memberId = admin)
                val response =
                    client.post(ONLINE_COUNTS_ROUTE) {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Application.Json)
                        setBody(ONLINE_COUNTS_BODY)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                listOf(
                    "active",
                    "signedIn",
                    "total",
                    "members",
                    "friends",
                    "guests",
                    "other",
                    "computedAt",
                ).forEach { body shouldContain it }
                listOf("memberId", "displayName", "email", "name", "\"id\":\"").forEach { body shouldNotContain it }
                UUID_PATTERN.containsMatchIn(body) shouldBe false
            }
        }

        test("BOARD, MEMBER and an anonymous caller get an error object, never a figure") {
            testApplication {
                application { module() }
                val callers: List<Uuid?> =
                    listOf(fixtures.member(role = AccountRole.BOARD), fixtures.member(role = AccountRole.MEMBER), null)
                callers.forEach { caller ->
                    val response =
                        client.post(ONLINE_COUNTS_ROUTE) {
                            caller?.let { header("X-Member-Id", it.toString()) }
                            contentType(ContentType.Application.Json)
                            setBody(ONLINE_COUNTS_BODY)
                        }
                    val body = response.bodyAsText()
                    body shouldNotContain "signedIn"
                    (response.status != HttpStatusCode.OK || body.contains("\"error\"")) shouldBe true
                }
            }
        }
    })
