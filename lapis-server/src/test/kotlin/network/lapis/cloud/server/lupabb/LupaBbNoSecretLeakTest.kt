package network.lapis.cloud.server.lupabb

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * V1.9.226 -- nothing secret or personal reaches a log, an exception message, a cause chain, a `toString` or an audit line, also with the query
 * transport (the API key sits in the URL) and with the log level of the whole application raised to TRACE (Ktor logs the URL at TRACE).
 */
class LupaBbNoSecretLeakTest :
    FunSpec({
        val fx = LupaBbFixtures()
        beforeSpec { DatabaseConfig.connect() }
        beforeTest { LupaBbSuppression.configure(TEST_BOX) }
        afterTest { fx.cleanUp() }

        fun render(event: ILoggingEvent): String {
            val sb = StringBuilder(event.formattedMessage)
            var proxy: IThrowableProxy? = event.throwableProxy
            while (proxy != null) {
                sb
                    .append(' ')
                    .append(proxy.className)
                    .append(' ')
                    .append(proxy.message)
                proxy.stackTraceElementProxyArray?.forEach { sb.append(' ').append(it.stackTraceElement) }
                proxy = proxy.cause
            }
            return sb.toString()
        }

        test("a full run with the query transport, wrong credentials, timeouts and broken answers logs no secret and no personal value") {
            val family = "Geheim${System.nanoTime()}"
            val email = "${TEST_EMAIL_PREFIX}geheim@example.org"
            FakeLupaBbServer(password = "pw-top-secret", apiKey = "key-top-secret").use { fake ->
                fake.acceptHeader = false
                fake.members = listOf(FakeLupaBbServer.member(personNumber = 9_000_000_070, familyName = family, email = email))
                val appender = ListAppender<ILoggingEvent>().also { it.start() }
                val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
                val oldLevel = root.level
                root.level = Level.TRACE
                root.addAppender(appender)
                val rt = fx.runtime(server = fake, limits = LupaBbLimits(requestTimeoutMillis = 500, socketTimeoutMillis = 500))
                try {
                    lupaBbTestApplication(fixtures = fx) {
                        val admin = fx.admin()
                        val input = LupaBbCredentialsInput(username = fake.username, password = fake.password, apiKey = fake.apiKey)
                        call(member = admin) { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input) }
                        runCatching {
                            attempt(member = admin) { c ->
                                fx
                                    .service(
                                        call = c,
                                        runtime = rt,
                                    ).verifyAndSaveCredentials(
                                        LupaBbCredentialsInput(username = fake.username, password = "pw-falsch", apiKey = fake.apiKey),
                                    )
                            }
                        }
                        fx.allowNextFetch()
                        val summary = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                        call(
                            member = admin,
                        ) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.ALL, offset = 0, limit = 10)
                        }
                        call(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview(summary.token) }
                        for (mode in listOf(FakeMode.SERVER_ERROR, FakeMode.BROKEN_JSON, FakeMode.STATUS_FALSE, FakeMode.REDIRECT)) {
                            fake.mode = mode
                            fx.allowNextFetch()
                            attempt(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                        }
                        fake.mode = FakeMode.NORMAL
                        fake.delayMillis = 2_000
                        fx.allowNextFetch()
                        attempt(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    }
                } finally {
                    root.detachAppender(appender)
                    root.level = oldLevel
                }
                // only the loggers of the system under test (the fake server's own authentication logger quotes its request URLs on purpose)
                val ours =
                    listOf(
                        "network.lapis",
                        "io.ktor.client",
                        "io.ktor.network",
                        "io.ktor.util",
                        "org.jetbrains.exposed",
                        "kotlinx",
                        "dev.kilua",
                    )
                val everything = appender.list.filter { e -> ours.any { e.loggerName.startsWith(it) } }.joinToString("\n") { render(it) }
                val port = fake.baseUrl.substringAfter("127.0.0.1:").substringBefore('/')
                val secrets =
                    listOf("pw-top-secret", "key-top-secret", "pw-falsch", fake.username, family, email, "example.org", "127.0.0.1:$port")
                for (secret in secrets) everything shouldNotContain secret
                // the stored row is sealed and the audit of the config names no value
                val cipher = transaction { LupaBbSyncConfigTable.selectAll().single() }[LupaBbSyncConfigTable.credentialsCiphertext]
                for (secret in listOf("pw-top-secret", "key-top-secret", fake.username)) cipher shouldNotContain secret
                transaction { LupaBbSyncRunTable.selectAll().count() } shouldBe 1
            }
        }

        test("credentials and their input print as masked text") {
            LupaBbCredentials(username = "u-geheim", password = "p-geheim", apiKey = "k-geheim").toString() shouldBe
                "LupaBbCredentials(***)"
            LupaBbCredentialsInput(username = "u-geheim", password = "p-geheim", apiKey = "k-geheim").toString() shouldBe
                "LupaBbCredentialsInput(***)"
        }

        test("a failure of the client carries no cause and a fixed message") {
            val server = FakeLupaBbServer()
            val endpoint = server.endpoint
            server.close()
            val client =
                LupaBbClient(
                    endpoint = endpoint,
                    credentials = LupaBbCredentials(username = "u", password = "p", apiKey = "key-top-secret"),
                    transport = ApiKeyTransport.QUERY,
                )
            client.use {
                val e = runCatching { it.probe() }.exceptionOrNull() as LupaBbFetchException
                e.cause shouldBe null
                e.message shouldBe "LupaBB request failed: ${e.code}"
            }
        }
    })
