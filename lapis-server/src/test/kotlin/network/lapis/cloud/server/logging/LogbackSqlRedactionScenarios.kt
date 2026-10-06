package network.lapis.cloud.server.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.OutputStreamAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.shouldNotContainNumber
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

private const val KILUA_LOGGER = "dev.kilua.rpc.RpcServiceManager"

/**
 * V1.9.65 -- the REAL logging path: the production `logback.xml` (no logback-test.xml exists) is already loaded into the
 * default LoggerContext; a capture appender with exactly the production pattern is attached next to it, a real unique
 * violation is provoked on the database under test (H2 and a real PostgreSQL), and the rendered bytes are inspected.
 *
 * Kilua RPC cannot be driven wire-level from a JVM test in this codebase (its JVM client stub is a no-op, see
 * DbTimeoutScenarios), so the Kilua call is reproduced: `LOG.error(e.getMessage(), e)` on logger
 * `dev.kilua.rpc.RpcServiceManager`, byte-identical to the 0.0.45 bytecode (RpcServiceManager$createRequestHandler$1).
 */
abstract class LogbackSqlRedactionScenarios(
    private val db: TestDatabase,
) : FunSpec({
        var table = ""
        val context = LoggerFactory.getILoggerFactory() as LoggerContext

        beforeSpec {
            db.activate()
            table = createUniqueTable()
        }
        installLaneGuards(db = db)
        afterSpec {
            dropTable(table = table)
            db.deactivate()
        }

        fun productionPattern(): String {
            val console = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDOUT") as ConsoleAppender<*>
            return (console.encoder as PatternLayoutEncoder).pattern
        }

        /** Runs [block] with a capture appender (production pattern) attached to [loggerName]; returns the rendered text. */
        fun capture(
            loggerName: String,
            block: (org.slf4j.Logger) -> Unit,
        ): String {
            val out = ByteArrayOutputStream()
            val encoder =
                PatternLayoutEncoder().apply {
                    this.context = context
                    this.pattern = productionPattern()
                    start()
                }
            val appender =
                OutputStreamAppender<ILoggingEvent>().apply {
                    this.context = context
                    this.encoder = encoder
                    this.outputStream = out
                    start()
                }
            val logger = context.getLogger(loggerName)
            logger.addAppender(appender)
            try {
                block(logger)
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                encoder.stop()
            }
            return out.toString(Charsets.UTF_8)
        }

        test("the rendered pattern is the production pattern and uses the safe converters") {
            val pattern = productionPattern()
            pattern shouldContain "%safeMsg"
            pattern shouldContain "%safeEx"
        }

        test("a real unique violation logged the way Kilua does contains no personal data or SQL text") {
            val failure = provokeUniqueViolation(table = table)
            // Guard: the raw exception really does carry the data (otherwise the assertions below prove nothing).
            failure.toString().lowercase() shouldContain "mustermann"

            val text = capture(KILUA_LOGGER) { it.error(failure.message, failure) }
            val lower = text.lowercase()
            FORBIDDEN_IN_LOG.forEach { lower shouldNotContain it }
            text shouldNotContainNumber TEST_PHONE_DIGITS
            // Diagnosis stays: SQLSTATE, the exception class, at least one application frame.
            text shouldContain "23505"
            text shouldContain "ExposedSQLException"
            text shouldContain "at network.lapis"
            // The throwable block is rendered exactly once (no raw %ex appended behind %safeEx).
            text.lines().count { it.contains("ExposedSQLException:") } shouldBe 1
        }

        test("an application logger.warn(e) with the message interpolated is redacted as well") {
            val failure = provokeUniqueViolation(table = table)
            val text =
                capture("network.lapis.cloud.server.accounting.export.AccountingExportPoller") {
                    it.warn("export step failed: ${failure.message}", failure)
                }
            text.lowercase().let { lower -> FORBIDDEN_IN_LOG.forEach { lower shouldNotContain it } }
            text shouldContain "export step failed"
        }

        test("a wrapped exception (RuntimeException(cause)) is redacted") {
            val wrapper = RuntimeException(provokeUniqueViolation(table = table))
            val text = capture(KILUA_LOGGER) { it.error(wrapper.message, wrapper) }
            text.lowercase().let { lower -> FORBIDDEN_IN_LOG.forEach { lower shouldNotContain it } }
            text shouldContain "23505"
        }

        test("an ordinary exception keeps its message and its stack trace") {
            val text =
                capture("network.lapis.cloud.server.Probe") { it.error("boom happened", IllegalStateException("member 42 not found")) }
            text shouldContain "boom happened"
            text shouldContain "IllegalStateException: member 42 not found"
            text shouldContain "at network.lapis"
        }

        test("Tripwire: every configured appender pattern avoids the raw message/throwable tokens") {
            val forbidden =
                Regex(
                    "%(?:-?\\d+)?(?:\\.-?\\d+)?(?:msg|message|m|ex|exception|xEx|xException|xThrowable|rEx|rootException|throwable)(?![A-Za-z0-9])",
                )
            val appenders =
                context.loggerList.flatMap { logger -> logger.iteratorForAppenders().asSequence().toList() }.distinct()
            (appenders.isNotEmpty()) shouldBe true
            appenders.forEach { appender ->
                val encoder = (appender as? OutputStreamAppender<*>)?.encoder as? PatternLayoutEncoder ?: return@forEach
                // No precondition: an appender added later (e.g. a FILE appender with %msg%ex) must fail the build too.
                forbidden.containsMatchIn(encoder.pattern) shouldBe false
                encoder.pattern shouldContain "%safeMsg"
                encoder.pattern shouldContain "%safeEx"
            }
        }

        test("Tripwire: no logback-test.xml / logback.groovy can shadow the production configuration, Exposed is at least INFO") {
            val loader = LogbackSqlRedactionScenarios::class.java.classLoader
            loader.getResource("logback-test.xml") shouldBe null
            loader.getResource("logback.groovy") shouldBe null
            context.getLogger("Exposed").effectiveLevel.isGreaterOrEqual(Level.INFO) shouldBe true
        }
    })
