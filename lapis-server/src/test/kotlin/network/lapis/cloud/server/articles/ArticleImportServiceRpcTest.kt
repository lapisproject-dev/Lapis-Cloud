package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ArticleImportService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.105 -- the RPC surface of the article import (H2 only: the trusted `X-Member-Id` header that drives these throwaway routes
 * exists exclusively on the in-memory test database). The database behaviour of the import itself is covered on both databases by
 * [ArticleImportScenarios].
 */
class ArticleImportServiceRpcTest :
    FunSpec({
        val slugPrefix = "artrpc-${Uuid.random().toString().take(8)}-"
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { transaction { ArticleTable.deleteWhere { slug like "$slugPrefix%" } } }

        fun seeded(role: AccountRole): Uuid = DevSeedData.demoMembers.first { it.role == role }.id

        fun json(n: Int) =
            """[{"slug":"$slugPrefix$n","title":"RPC Artikel $n","excerpt":"Teaser","body":"Text","publishedAt":"2024-05-05","byline":"PdV"}]"""

        fun articleCount() =
            transaction {
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.slug like "$slugPrefix%" }
                    .count()
                    .toInt()
            }

        fun Route.importRoutes(limiter: FederationInboxRateLimiter) {
            fun serviceFor(call: ApplicationCall) =
                ArticleImportService(call = call, previewRateLimiter = limiter, commitRateLimiter = limiter)
            post("/test/import/preview") {
                val labels =
                    call.request.queryParameters["labels"]
                        .orEmpty()
                        .split(',')
                        .filter { it.isNotEmpty() }
                val result = serviceFor(call).previewArticleImport(json = call.receiveText(), organizationLabels = labels)
                call.respondText("${result.createCount}/${result.skipCount}/${result.errorCount}")
            }
            post("/test/import/commit") {
                val result =
                    serviceFor(call).commitArticleImport(
                        json = call.receiveText(),
                        payloadSha256 = call.request.queryParameters["hash"].orEmpty(),
                    )
                call.respondText("${result.created}/${result.skipped}")
            }
        }

        test("MEMBER and TREASURER are forbidden on preview and commit and nothing is written; BOARD and ADMIN are allowed") {
            val member = seeded(AccountRole.MEMBER)
            val treasurer = seeded(AccountRole.TREASURER)
            val admin = seeded(AccountRole.ADMIN)
            val board = seeded(AccountRole.BOARD)
            val payload = json(1)
            val hash = ArticleImportPolicy.sha256Hex(payload)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)) }
                }
                for (who in listOf(member, treasurer)) {
                    client
                        .post("/test/import/preview") {
                            header("X-Member-Id", who.toString())
                            setBody(payload)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/test/import/commit?hash=$hash") {
                            header("X-Member-Id", who.toString())
                            setBody(payload)
                        }.status shouldBe HttpStatusCode.Forbidden
                }
                articleCount() shouldBe 0
                client
                    .post("/test/import/preview?labels=PdV") {
                        header("X-Member-Id", admin.toString())
                        setBody(payload)
                    }.bodyAsText() shouldBe "1/0/0"
                client
                    .post("/test/import/commit?hash=$hash") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.OK
                articleCount() shouldBe 1
            }
        }

        test("the authorization check comes before any parsing: a forbidden caller with garbage gets 403, not 400") {
            val member = seeded(AccountRole.MEMBER)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)) }
                }
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", member.toString())
                        setBody("{ not json")
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a BOARD caller with garbage gets 400 (the parse error is a message, never a stack trace)") {
            val board = seeded(AccountRole.BOARD)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)) }
                }
                val response =
                    client.post("/test/import/preview") {
                        header("X-Member-Id", board.toString())
                        setBody("{ not json")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe "Die Datei ist kein gültiges JSON."
            }
        }

        test("more than five organization names is a 400, the preview and the commit are rate limited per member") {
            val board = seeded(AccountRole.BOARD)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) }
                }
                val payload = json(2)
                client
                    .post("/test/import/preview?labels=a,b,c,d,e,f") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }
    })
