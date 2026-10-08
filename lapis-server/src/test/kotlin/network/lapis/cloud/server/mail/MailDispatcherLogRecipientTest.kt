package network.lapis.cloud.server.mail

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Welle V1.9.76 -- `enqueue(logRecipient = false)`: no log line of the dispatcher carries the recipient, not even masked. Default
 * callers keep the masked address.
 */
class MailDispatcherLogRecipientTest :
    FunSpec({
        suspend fun logLinesFor(logRecipient: Boolean): List<String> {
            val logger = LoggerFactory.getLogger(MailDispatcher::class.java.name) as LogbackLogger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            val previousLevel = logger.level
            logger.level = ch.qos.logback.classic.Level.INFO
            try {
                val sent = CompletableDeferred<Unit>()
                val transport =
                    object : MailTransport {
                        override suspend fun send(
                            to: String,
                            subject: String,
                            plainTextBody: String,
                            htmlBody: String,
                        ): MailSendOutcome {
                            sent.complete(Unit)
                            return MailSendOutcome.Sent
                        }
                    }
                val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                dispatcher.enqueue(
                    to = "pastor.beispiel@example.org",
                    subject = "s",
                    plainTextBody = "p",
                    htmlBody = "h",
                    purpose = "test",
                    logRecipient = logRecipient,
                )
                withTimeout(5.seconds) { sent.await() }
                // the log line is written right after send() returns
                repeat(50) {
                    if (appender.list.isNotEmpty()) return@repeat
                    kotlinx.coroutines.delay(20)
                }
                dispatcher.shutdown()
                return appender.list.map { it.formattedMessage }
            } finally {
                logger.level = previousLevel
                logger.detachAppender(appender)
            }
        }

        test("logRecipient = false: the delivery log line says (withheld) and contains no part of the address") {
            val lines = runBlocking { logLinesFor(logRecipient = false) }
            lines.isNotEmpty() shouldBe true
            lines.forEach {
                it.contains("example.org") shouldBe false
                it.contains("pastor") shouldBe false
                it.contains("p***") shouldBe false
                it.contains("to=(withheld)") shouldBe true
            }
        }

        test("the default keeps the masked address in the log (existing behaviour)") {
            val lines = runBlocking { logLinesFor(logRecipient = true) }
            lines.isNotEmpty() shouldBe true
            lines.all { it.contains("to=p") && !it.contains("pastor.beispiel") } shouldBe true
        }
    })
