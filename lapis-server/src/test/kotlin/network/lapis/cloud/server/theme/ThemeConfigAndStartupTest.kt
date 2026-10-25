package network.lapis.cloud.server.theme

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

class ThemeConfigAndStartupTest :
    FunSpec({
        fun cfg(value: String?) = ThemeConfig.load { if (it == ThemeConfig.ENV_PATH) value else null }

        test("config: unset or empty is no theme, relative or NUL is invalid, absolute is valid") {
            cfg(null) shouldBe ThemeConfig(path = null, invalid = false)
            cfg("  ") shouldBe ThemeConfig(path = null, invalid = false)
            cfg("theme") shouldBe ThemeConfig(path = null, invalid = true)
            cfg("/app/th\u0000eme") shouldBe ThemeConfig(path = null, invalid = true)
            cfg("/".padEnd(5000, 'a')) shouldBe ThemeConfig(path = null, invalid = true)
            cfg("/app/theme") shouldBe ThemeConfig(path = Path.of("/app/theme"), invalid = false)
        }

        fun capture(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger("network.lapis.cloud.server.theme.ThemeStartupCheckKt") as ch.qos.logback.classic.Logger
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            val previous = logger.level
            logger.level = Level.DEBUG
            try {
                block()
            } finally {
                root.detachAppender(appender)
                logger.level = previous
            }
            return appender.list.filter { it.formattedMessage.startsWith("theme.") }
        }

        test("a rejected theme logs code, path and action only, never values") {
            val secret = "#NOTAHEXVALUE-SECRET"
            val dir = themeDir(json = themeJsonWithColor(mode = "dark", key = "accent", value = secret))
            val logs = capture { ThemeStartupCheck.resolve(config = ThemeConfig(path = dir, invalid = false)) }
            val warn = logs.first { it.level == Level.WARN }
            warn.formattedMessage shouldBe "theme.rejected code=THEME_BAD_HEX path=$.colors.dark.accent action=default"
            logs.forEach { it.formattedMessage shouldNotContain "SECRET" }
        }
        test("a valid theme logs name, hash and source; no variable logs the default at INFO") {
            val dir = themeDir(json = DEFAULT_THEME_JSON)
            val logs = capture { ThemeStartupCheck.resolve(config = ThemeConfig(path = dir, invalid = false)) }
            logs.single { it.formattedMessage.startsWith("theme.loaded") }.formattedMessage shouldContain "name=Lapis Default"
            val none = capture { ThemeStartupCheck.resolve(config = ThemeConfig(path = null, invalid = false)) }
            none.single().level shouldBe Level.INFO
            none.single().formattedMessage shouldContain "theme.default hash="
        }
        test("never throws, even when the loader throws") {
            val result =
                ThemeStartupCheck.resolve(
                    config = ThemeConfig(path = Path.of("/x"), invalid = false),
                ) { throw IOException("boom") }
            result shouldBe ResolvedTheme.DEFAULT
            ThemeStartupCheck.resolve(config = ThemeConfig(path = null, invalid = true)) shouldBe ResolvedTheme.DEFAULT
        }
    })
