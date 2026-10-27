package network.lapis.cloud.server.lupabb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.seconds

/** V1.9.226 -- the hardened client against a fake LupaBB: digest, both key transports, and every failure mode it must survive. */
class LupaBbClientTest :
    FunSpec({
        fun client(
            server: FakeLupaBbServer,
            transport: ApiKeyTransport = ApiKeyTransport.HEADER,
            limits: LupaBbLimits = LupaBbLimits.DEFAULT,
            password: String = server.password,
            apiKey: String = server.apiKey,
        ) = LupaBbClient(
            endpoint = server.endpoint,
            credentials = LupaBbCredentials(username = server.username, password = password, apiKey = apiKey),
            transport = transport,
            limits = limits,
        )

        suspend fun failureOf(block: suspend () -> Unit): LupaBbFetchException =
            shouldThrow<LupaBbFetchException> { block() }.also {
                it.cause shouldBe null
                it.message shouldNotContain "test-api-key"
                it.message shouldNotContain "127.0.0.1"
            }

        test("happy path: lookups and three pages, digest answered, numbers as numbers or as quoted strings") {
            FakeLupaBbServer().use { server ->
                server.members =
                    listOf(
                        FakeLupaBbServer.member(personNumber = 1),
                        FakeLupaBbServer.member(personNumber = 2, numbersAsStrings = true),
                        FakeLupaBbServer.member(personNumber = 3),
                        FakeLupaBbServer.member(personNumber = 4),
                        FakeLupaBbServer.member(personNumber = 5),
                    )
                client(server).use { c ->
                    val dataset = LupaBbFetcher.fetchAll(client = c)
                    dataset.validRecords.map { it.personNumber } shouldBe listOf(1L, 2L, 3L, 4L, 5L)
                    dataset.dataSha256.length shouldBe 64
                }
                (server.unauthorizedCount.get() == 0) shouldBe true
                server.authorizedPaths.count { it == "mitglieder" } shouldBe 3
            }
        }

        test("a wrong password and a wrong API key are AUTH_REJECTED") {
            FakeLupaBbServer().use { server ->
                client(server, password = "wrong").use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.AUTH_REJECTED }
                client(server, apiKey = "wrong").use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.AUTH_REJECTED }
            }
        }

        test("the API key travels as header or as query parameter, whichever the server accepts") {
            FakeLupaBbServer().use { server ->
                server.acceptQuery = false
                client(server, ApiKeyTransport.HEADER).use { it.probe() }
                client(server, ApiKeyTransport.QUERY).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.AUTH_REJECTED }
                server.acceptQuery = true
                server.acceptHeader = false
                client(server, ApiKeyTransport.QUERY).use { it.probe() }
                client(server, ApiKeyTransport.HEADER).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.AUTH_REJECTED }
                server.transports
                    .toList()
                    .distinct()
                    .sorted() shouldBe listOf("HEADER", "QUERY")
            }
        }

        test("a redirect to another host is UNEXPECTED_RESPONSE and is never followed") {
            FakeLupaBbServer().use { target ->
                FakeLupaBbServer().use { server ->
                    server.mode = FakeMode.REDIRECT
                    server.redirectTarget = target.baseUrl + "mitgliederstatus"
                    client(server).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE }
                    target.requestCount.get() shouldBe 0
                }
            }
        }

        test("a 5xx is UNAVAILABLE, a 404 and a status:false and broken JSON are UNEXPECTED_RESPONSE") {
            FakeLupaBbServer().use { server ->
                server.mode = FakeMode.SERVER_ERROR
                client(server).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.UNAVAILABLE }
                server.mode = FakeMode.NOT_FOUND
                client(server).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE }
                server.mode = FakeMode.STATUS_FALSE
                client(server).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE }
                server.mode = FakeMode.BROKEN_JSON
                client(server).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE }
            }
        }

        test("a slow answer is TIMEOUT") {
            FakeLupaBbServer().use { server ->
                server.delayMillis = 3_000
                val limits = LupaBbLimits(requestTimeoutMillis = 400, socketTimeoutMillis = 400)
                client(server, limits = limits).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.TIMEOUT }
            }
        }

        test("an unreachable host is UNAVAILABLE and the exception carries neither the URL nor the key (query transport)") {
            val server = FakeLupaBbServer()
            val endpoint = server.endpoint
            server.close()
            LupaBbClient(
                endpoint = endpoint,
                credentials = LupaBbCredentials(username = "test-user", password = "test-password", apiKey = "test-api-key"),
                transport = ApiKeyTransport.QUERY,
            ).use { c ->
                val e = failureOf { c.probe() }
                e.code shouldBe LupaBbErrorCode.UNAVAILABLE
                e.toString() shouldNotContain "test-api-key"
                e.stackTraceToString() shouldNotContain "test-api-key"
            }
        }

        test("a body over the limit is LIMIT_EXCEEDED, with and without Content-Length") {
            FakeLupaBbServer().use { server ->
                val limits = LupaBbLimits(maxResponseBytes = 64 * 1024)
                server.mode = FakeMode.HUGE_WITH_LENGTH
                client(server, limits = limits).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.LIMIT_EXCEEDED }
                server.mode = FakeMode.HUGE_CHUNKED
                client(server, limits = limits).use { c -> failureOf { c.probe() }.code shouldBe LupaBbErrorCode.LIMIT_EXCEEDED }
            }
        }

        test("more than 200 pages, more records than allowed, a wrong current page and an empty page before the end are rejected") {
            FakeLupaBbServer().use { server ->
                server.members = (1L..4L).map { FakeLupaBbServer.member(personNumber = it) }
                server.forcedMax = 201
                client(server).use { c -> failureOf { LupaBbFetcher.fetchAll(client = c) }.code shouldBe LupaBbErrorCode.LIMIT_EXCEEDED }
                server.forcedMax = null
                client(server).use { c ->
                    failureOf { LupaBbFetcher.fetchAll(client = c, limits = LupaBbLimits(maxRecords = 3)) }.code shouldBe
                        LupaBbErrorCode.LIMIT_EXCEEDED
                }
                server.forcedCurrent = 7
                client(server).use { c ->
                    failureOf { LupaBbFetcher.fetchAll(client = c) }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE
                }
                server.forcedCurrent = null
                server.forcedMax = 5
                client(server).use { c ->
                    failureOf { LupaBbFetcher.fetchAll(client = c) }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE
                }
            }
        }

        test("a missing pagination object with records on the page is UNEXPECTED_RESPONSE, never a dataset cut off after page one") {
            FakeLupaBbServer().use { server ->
                server.members = (1L..2L).map { FakeLupaBbServer.member(personNumber = it) }
                server.omitPagination = true
                client(server).use { c ->
                    failureOf { LupaBbFetcher.fetchAll(client = c) }.code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE
                }
            }
        }

        test("the total timeout of the fetch is TIMEOUT") {
            FakeLupaBbServer().use { server ->
                server.members = (1L..2L).map { FakeLupaBbServer.member(personNumber = it) }
                server.delayMillis = 300
                client(server).use { c ->
                    failureOf { LupaBbFetcher.fetchAll(client = c, limits = LupaBbLimits(totalTimeout = 0.5.seconds)) }.code shouldBe
                        LupaBbErrorCode.TIMEOUT
                }
            }
        }

        test("a duplicated person number turns BOTH records into errors") {
            FakeLupaBbServer().use { server ->
                server.members =
                    listOf(
                        FakeLupaBbServer.member(personNumber = 7),
                        FakeLupaBbServer.member(personNumber = 7),
                        FakeLupaBbServer.member(personNumber = 8),
                    )
                client(server).use { c ->
                    val dataset = LupaBbFetcher.fetchAll(client = c)
                    dataset.validRecords.map { it.personNumber } shouldBe listOf(8L)
                    dataset.parsed.count { it is ParsedRecord.Rejected && it.personNumber == 7L } shouldBe 2
                }
            }
        }

        test("the same data gives the same hash, other data another one") {
            FakeLupaBbServer().use { server ->
                server.members = listOf(FakeLupaBbServer.member(personNumber = 1), FakeLupaBbServer.member(personNumber = 2))
                val first = client(server).use { LupaBbFetcher.fetchAll(client = it) }
                val second = client(server).use { LupaBbFetcher.fetchAll(client = it) }
                first.dataSha256 shouldBe second.dataSha256
                server.members =
                    listOf(FakeLupaBbServer.member(personNumber = 1), FakeLupaBbServer.member(personNumber = 2, givenName = "Anna"))
                val third = client(server).use { LupaBbFetcher.fetchAll(client = it) }
                third.dataSha256 shouldNotBe first.dataSha256
            }
        }
    })
