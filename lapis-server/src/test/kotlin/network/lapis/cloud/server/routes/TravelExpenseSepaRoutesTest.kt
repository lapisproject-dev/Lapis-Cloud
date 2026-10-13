package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.DEBTOR_IBAN
import network.lapis.cloud.server.rpc.MEMBER_IBAN_1
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.rpc.SEPA_TEST_KEY
import network.lapis.cloud.server.rpc.TravelExpenseSepaExport
import network.lapis.cloud.server.rpc.TravelExpenseSepaTestData
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker
import network.lapis.cloud.shared.domain.TravelExpenseSepaErrorDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaExportRequest
import network.lapis.cloud.shared.domain.TravelExpenseSepaReexportRequest
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.time.Duration.Companion.minutes

private const val BASE_URL = "https://lapis.example"
private const val NOW = "2026-10-13T10:00:00Z"

/** Welle V1.9.123 -- the HTTP face of the SEPA export: guards, headers, error bodies. */
class TravelExpenseSepaRoutesTest :
    FunSpec({
        val data = PollTestData()
        val sepa = TravelExpenseSepaTestData(data)
        lateinit var bank: kotlin.uuid.Uuid

        beforeSpec {
            DatabaseConfig.connect()
            sepa.configureOrganization()
            // ONE debtor account for the whole spec: the IBAN is unique in bank_account.
            bank = sepa.bankAccount(createdBy = data.member(label = "route-bank-owner", role = AccountRole.ADMIN))
        }
        afterSpec {
            sepa.cleanUp()
            data.cleanUp()
        }

        fun exportBody(
            reports: List<kotlin.uuid.Uuid>,
            account: kotlin.uuid.Uuid?,
        ) = Json.encodeToString(
            TravelExpenseSepaExportRequest.serializer(),
            TravelExpenseSepaExportRequest(
                reportIds =
                    reports.map {
                        it.toString()
                    },
                debtorBankAccountId = account?.toString(),
                executionDate = LocalDate(2026, 10, 14),
            ),
        )

        fun withApp(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) = TimeTestSupport.withServerClock(instant = NOW) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                    }
                    routing {
                        registerTravelExpenseSepaRoutes(
                            export = TravelExpenseSepaExport(SEPA_TEST_KEY),
                            rateLimiter = limiter,
                            baseUrl = BASE_URL,
                        )
                    }
                }
                block()
            }
        }

        test("a successful export delivers an attachment that is never cached, with batch metadata in headers") {
            val admin = data.member(label = "route-admin", role = AccountRole.ADMIN)
            val report = sepa.executedReport(subject = data.member(label = "route-subject", role = AccountRole.MEMBER))
            withApp {
                val response =
                    client.post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", admin.toString())
                        header(HttpHeaders.Origin, BASE_URL)
                        contentType(ContentType.Application.Json)
                        setBody(exportBody(listOf(report), bank))
                    }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers[HttpHeaders.Pragma] shouldBe "no-cache"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.ContentType] shouldContain "application/xml"
                response.headers[HttpHeaders.ContentDisposition] shouldMatch
                    Regex("attachment; filename=\"?sepa-reisekosten-20261014-[0-9a-f]{8}\\.xml\"?")
                response.headers["X-Lapis-Sepa-Count"] shouldBe "1"
                response.headers["X-Lapis-Sepa-Sum-Cents"] shouldBe "8000"
                val xml = response.bodyAsBytes().decodeToString()
                xml shouldContain MEMBER_IBAN_1
                xml shouldContain DEBTOR_IBAN
                xml shouldContain "<NbOfTxs>1</NbOfTxs>"
            }
        }

        test("a cross-origin request is refused before anything happens (CSRF)") {
            val admin = data.member(label = "route-csrf", role = AccountRole.ADMIN)
            val report = sepa.executedReport(subject = data.member(label = "route-csrf-s", role = AccountRole.MEMBER))
            withApp {
                listOf("Origin" to "https://evil.example", "Sec-Fetch-Site" to "cross-site").forEach { (name, value) ->
                    val response =
                        client.post("/api/travel-expenses/sepa-batches") {
                            header("X-Member-Id", admin.toString())
                            header(name, value)
                            contentType(ContentType.Application.Json)
                            setBody(exportBody(listOf(report), null))
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                    Json.decodeFromString(TravelExpenseSepaErrorDto.serializer(), response.bodyAsText()).code shouldBe "invalid_origin"
                }
                sepa.report(report)[network.lapis.cloud.server.db.generated.TravelExpenseReportTable.sepaExportedAt] shouldBe null
            }
        }

        test("anonymous callers get 401, wrong content types 415, oversized bodies 413, plain members 403") {
            val member = data.member(label = "route-guards", role = AccountRole.MEMBER)
            val admin = data.member(label = "route-guards-a", role = AccountRole.ADMIN)
            withApp {
                client
                    .post("/api/travel-expenses/sepa-batches") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status shouldBe HttpStatusCode.Unauthorized
                client
                    .post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Text.Plain)
                        setBody("{}")
                    }.status shouldBe HttpStatusCode.UnsupportedMediaType
                client
                    .post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Application.Json)
                        setBody("x".repeat(70 * 1024))
                    }.status shouldBe HttpStatusCode.PayloadTooLarge
                client
                    .post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Application.Json)
                        setBody("not json")
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", member.toString())
                        contentType(ContentType.Application.Json)
                        setBody(exportBody(listOf(kotlin.uuid.Uuid.random()), null))
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("the eleventh call within a minute is rate limited (both routes share the limit)") {
            val admin = data.member(label = "route-rate", role = AccountRole.ADMIN)
            withApp {
                val statuses =
                    (1..11).map { i ->
                        val response =
                            if (i % 2 == 0) {
                                client.post("/api/travel-expenses/reports/${kotlin.uuid.Uuid.random()}/sepa-reexport") {
                                    header("X-Member-Id", admin.toString())
                                    contentType(ContentType.Application.Json)
                                    setBody("not json")
                                }
                            } else {
                                client.post("/api/travel-expenses/sepa-batches") {
                                    header("X-Member-Id", admin.toString())
                                    contentType(ContentType.Application.Json)
                                    setBody("not json")
                                }
                            }
                        response.status to response.headers[HttpHeaders.RetryAfter]
                    }
                statuses.take(10).all { it.first == HttpStatusCode.BadRequest } shouldBe true
                statuses[10].first shouldBe HttpStatusCode.TooManyRequests
                (statuses[10].second != null) shouldBe true
            }
        }

        test("an error body names the blockers per report and never an IBAN, a holder or a name") {
            val admin = data.member(label = "route-err", role = AccountRole.ADMIN)
            val report =
                sepa.executedReport(
                    subject = data.member(label = "route-err-s", role = AccountRole.MEMBER),
                    holder = "Erika Mustermann",
                    iban = null,
                )
            withApp {
                val response =
                    client.post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Application.Json)
                        setBody(exportBody(listOf(report), bank))
                    }
                response.status shouldBe HttpStatusCode.Conflict
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val text = response.bodyAsText()
                val error = Json.decodeFromString(TravelExpenseSepaErrorDto.serializer(), text)
                error.code shouldBe "reports_not_exportable"
                error.blockers shouldBe mapOf(report.toString() to TravelExpenseSepaBlocker.NO_REFUND_IBAN)
                listOf(DEBTOR_IBAN, MEMBER_IBAN_1, "Mustermann", "route-err").forEach { text shouldNotContain it }
            }
        }

        test("the re-export route delivers a new file for an exported report") {
            val admin = data.member(label = "route-re", role = AccountRole.ADMIN)
            val report = sepa.executedReport(subject = data.member(label = "route-re-s", role = AccountRole.MEMBER))
            withApp {
                client
                    .post("/api/travel-expenses/sepa-batches") {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Application.Json)
                        setBody(exportBody(listOf(report), bank))
                    }.status shouldBe HttpStatusCode.OK
                val reexport =
                    client.post("/api/travel-expenses/reports/$report/sepa-reexport") {
                        header("X-Member-Id", admin.toString())
                        contentType(ContentType.Application.Json)
                        setBody(
                            Json.encodeToString(
                                TravelExpenseSepaReexportRequest.serializer(),
                                TravelExpenseSepaReexportRequest(
                                    reason = "Datei wurde von der Bank abgelehnt",
                                    debtorBankAccountId = bank.toString(),
                                    executionDate = LocalDate(2026, 10, 14),
                                ),
                            ),
                        )
                    }
                reexport.status shouldBe HttpStatusCode.OK
                reexport.bodyAsBytes().decodeToString() shouldContain "-R1</EndToEndId>"
            }
        }
    })
