package network.lapis.cloud.server.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.bootstrap.flywayRepairSettings
import network.lapis.cloud.server.bootstrap.resetPasswordInputs
import network.lapis.cloud.server.db.dbConnectionSettings
import org.slf4j.LoggerFactory
import java.nio.file.Files

/** V1.9.266 -- neither a path nor a value ever reaches an exception or a log line. */
class SecretEnvNoLeakTest :
    FunSpec({
        val pathMark = "LEAKPATHMARK"
        val valueMark = "LEAKVALUEMARK"
        val root = Files.createTempDirectory("secret-leak-$pathMark")
        afterSpec { root.toFile().deleteRecursively() }

        fun assertClean(e: Throwable) {
            val all = listOf(e.message.orEmpty(), e.toString(), e.stackTraceToString())
            all.forEach {
                it shouldNotContain pathMark
                it shouldNotContain valueMark
                it shouldNotContain root.toString()
            }
            e.cause shouldBe null
        }

        test("every failure mode is free of path and value") {
            val name = "LAPIS_SMTP_PASSWORD"
            val good = root.resolve("good").also { Files.write(it, "$valueMark\n".toByteArray()) }
            val cases =
                listOf(
                    mapOf(name to valueMark, "${name}_FILE" to good.toString()),
                    mapOf("${name}_FILE" to root.resolve("missing").toString()),
                    mapOf("${name}_FILE" to root.toString()),
                    mapOf("${name}_FILE" to "relative/$pathMark"),
                    mapOf("${name}_FILE" to root.resolve("empty").also { Files.write(it, ByteArray(0)) }.toString()),
                    mapOf(
                        "${name}_FILE" to
                            root.resolve("bad").also { Files.write(it, byteArrayOf(0xC3.toByte(), 0x28)) }.toString(),
                    ),
                    mapOf(
                        "${name}_FILE" to
                            root.resolve("nul").also { Files.write(it, "$valueMark\u0000".toByteArray()) }.toString(),
                    ),
                    mapOf(
                        "${name}_FILE" to
                            root.resolve("big").also { Files.write(it, ByteArray(70_000) { 'v'.code.toByte() }) }.toString(),
                    ),
                )
            for (env in cases) {
                assertClean(shouldThrow<SecretFileException> { SecretEnv.resolve(name = name, raw = env::get) })
            }
        }

        test("the success log line carries only the variable name") {
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val oldLevel = rootLogger.level
            rootLogger.level = Level.ALL
            rootLogger.addAppender(appender)
            try {
                val f = root.resolve("logged").also { Files.write(it, "$valueMark\n".toByteArray()) }
                SecretEnv.resolving(mapOf("LAPIS_SMTP_PASSWORD_FILE" to f.toString())::get)("LAPIS_SMTP_PASSWORD") shouldBe valueMark
                val lines = appender.list.map { it.formattedMessage }
                lines.any { it.contains("LAPIS_SMTP_PASSWORD") } shouldBe true
                lines.forEach {
                    it shouldNotContain valueMark
                    it shouldNotContain pathMark
                }
            } finally {
                rootLogger.detachAppender(appender)
                rootLogger.level = oldLevel
            }
        }

        test("toString of settings built from file values hides the secret") {
            val f = root.resolve("pw").also { Files.write(it, "$valueMark\n".toByteArray()) }
            val env =
                SecretEnv.resolving(
                    mapOf(
                        "LAPIS_DB_PASSWORD_FILE" to f.toString(),
                        "LAPIS_BOOTSTRAP_ADMIN_PASSWORD_FILE" to f.toString(),
                        "LAPIS_DB_URL" to "jdbc:h2:mem:x",
                        "LAPIS_DB_USER" to "u",
                        "LAPIS_BOOTSTRAP_ADMIN_EMAIL" to "a@b.c",
                        "LAPIS_STRIPE_SECRET_KEY_FILE" to f.toString(),
                    )::get,
                )
            val db = dbConnectionSettings(env = env, inMemoryName = "x")
            db.password shouldBe valueMark
            db.toString() shouldNotContain valueMark
            flywayRepairSettings(env).toString() shouldNotContain valueMark
            resetPasswordInputs(env).toString() shouldNotContain valueMark
        }
    })
