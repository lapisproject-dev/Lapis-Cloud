package network.lapis.cloud.server.branding

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

private fun brandConfig(
    title: String? = null,
    logoPath: String? = null,
): BrandConfig {
    val env: (String) -> String? =
        { key ->
            when (key) {
                BrandConfig.ENV_TITLE -> title
                BrandConfig.ENV_LOGO_PATH -> logoPath
                else -> null
            }
        }
    return BrandConfig.load(env)
}

private fun neverProbe(): (String) -> LogoProbe? = { throw AssertionError("probe should never be called when logoPath is null") }

/**
 * Exercises [BrandingStartupCheck.resolve] purely through its injectable `probe` lambda -- never
 * real file I/O -- see that function's own KDoc. Mirrors `network.lapis.cloud.server.mail
 * .SmtpStartupCheckTest`'s own structure/conventions, except every case here expects [resolve] to
 * simply degrade `logoAvailable` to `false`, NEVER throw (see class KDoc "why branding is never
 * fail-fast").
 */
class BrandingStartupCheckTest :
    FunSpec({
        test("no logo path configured -> logoAvailable false, logoPath null, title passed through") {
            val config = brandConfig(title = "Partei der Vernunft", logoPath = null)
            val resolved = BrandingStartupCheck.resolve(config = config, probe = neverProbe())
            resolved.title shouldBe "Partei der Vernunft"
            resolved.logoAvailable shouldBe false
            resolved.logoPath shouldBe null
        }

        test("probe returns null (file missing) -> logoAvailable false, no throw") {
            val config = brandConfig(logoPath = "/app/branding/logo.svg")
            val resolved = BrandingStartupCheck.resolve(config = config) { null }
            resolved.logoAvailable shouldBe false
            resolved.logoPath shouldBe null
        }

        test("probe reports a directory (not a regular file) -> logoAvailable false") {
            val config = brandConfig(logoPath = "/app/branding/logo.svg")
            val resolved =
                BrandingStartupCheck.resolve(config = config) {
                    LogoProbe(readable = true, isRegularFile = false, sizeBytes = 1_024L)
                }
            resolved.logoAvailable shouldBe false
            resolved.logoPath shouldBe null
        }

        test("probe reports not readable -> logoAvailable false") {
            val config = brandConfig(logoPath = "/app/branding/logo.svg")
            val resolved =
                BrandingStartupCheck.resolve(config = config) {
                    LogoProbe(readable = false, isRegularFile = true, sizeBytes = 1_024L)
                }
            resolved.logoAvailable shouldBe false
            resolved.logoPath shouldBe null
        }

        test("probe reports a file larger than 512 KiB -> logoAvailable false") {
            val config = brandConfig(logoPath = "/app/branding/logo.png")
            val resolved =
                BrandingStartupCheck.resolve(config = config) {
                    LogoProbe(readable = true, isRegularFile = true, sizeBytes = 512 * 1024L + 1)
                }
            resolved.logoAvailable shouldBe false
            resolved.logoPath shouldBe null
        }

        test("probe reports exactly 512 KiB -> logoAvailable true (boundary)") {
            val config = brandConfig(logoPath = "/app/branding/logo.png")
            val resolved =
                BrandingStartupCheck.resolve(config = config) {
                    LogoProbe(readable = true, isRegularFile = true, sizeBytes = 512 * 1024L)
                }
            resolved.logoAvailable shouldBe true
            resolved.logoPath shouldBe "/app/branding/logo.png"
        }

        test("probe reports a valid, readable, small logo -> logoAvailable true, path passed through") {
            val config = brandConfig(title = "ELB", logoPath = "/app/branding/logo.webp")
            val resolved =
                BrandingStartupCheck.resolve(config = config) {
                    LogoProbe(readable = true, isRegularFile = true, sizeBytes = 4_096L)
                }
            resolved.title shouldBe "ELB"
            resolved.logoAvailable shouldBe true
            resolved.logoPath shouldBe "/app/branding/logo.webp"
        }

        context("V1.9.265 logo variants") {
            fun config(vararg pairs: Pair<LogoVariant, String>): BrandConfig {
                val map = pairs.associate { it.first.envName to it.second }
                return BrandConfig.load { key -> map[key] }
            }

            for (variant in LogoVariant.entries) {
                val path = "/app/branding/${variant.slug}.png"
                val cfg = config(variant to path)
                val ok = LogoProbe(readable = true, isRegularFile = true, sizeBytes = 1_024L)

                test("${variant.envName}: usable file -> in variants with its lower-case extension") {
                    val resolved = BrandingStartupCheck.resolve(config = config(variant to "/app/branding/X.WEBP")) { ok }
                    resolved.variants[variant] shouldBe ResolvedLogoFile(path = "/app/branding/X.WEBP", extension = "webp")
                    resolved.servesAnyLogo shouldBe true
                    resolved.logoAvailable shouldBe false
                }

                val bad =
                    mapOf(
                        "missing" to null,
                        "directory" to LogoProbe(readable = true, isRegularFile = false, sizeBytes = 1L),
                        "too large (512 KiB + 1)" to LogoProbe(readable = true, isRegularFile = true, sizeBytes = 512 * 1024L + 1),
                        "unreadable" to LogoProbe(readable = false, isRegularFile = true, sizeBytes = 1L),
                    )
                for ((name, probe) in bad) {
                    test("${variant.envName}: $name -> not in variants, never throws") {
                        val resolved = BrandingStartupCheck.resolve(config = cfg) { probe }
                        resolved.variants.isEmpty() shouldBe true
                        resolved.servesAnyLogo shouldBe false
                    }
                }

                test("${variant.envName}: exactly 512 KiB is accepted (boundary)") {
                    val resolved =
                        BrandingStartupCheck.resolve(config = cfg) {
                            LogoProbe(
                                readable = true,
                                isRegularFile = true,
                                sizeBytes =
                                    512 * 1024L,
                            )
                        }
                    (variant in resolved.variants) shouldBe true
                }
            }

            test("an unusable variant logs a WARN with the variable name but never the path") {
                val appender = ListAppender<ILoggingEvent>().also { it.start() }
                val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                logger.addAppender(appender)
                try {
                    val secretPath = "/very/secret/dir/signet.png"
                    BrandingStartupCheck.resolve(config = config(LogoVariant.SIGNET_LIGHT to secretPath)) { null }
                    val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                    warnings.any { it.contains("LAPIS_BRAND_SIGNET_LIGHT_PATH") } shouldBe true
                    warnings.none { it.contains(secretPath) || it.contains("/very/secret") } shouldBe true
                    appender.list.none { it.formattedMessage.contains("/very/secret") } shouldBe true
                } finally {
                    logger.detachAppender(appender)
                }
            }

            test("variants and the legacy logo are independent; the legacy logo keeps its own behaviour") {
                val cfg =
                    BrandConfig.load { key ->
                        mapOf(
                            BrandConfig.ENV_LOGO_PATH to "/app/branding/logo.svg",
                            LogoVariant.WORDMARK_DARK.envName to "/app/branding/w.svg",
                        )[key]
                    }
                val resolved =
                    BrandingStartupCheck.resolve(config = cfg) { path ->
                        if (path.endsWith("logo.svg")) null else LogoProbe(readable = true, isRegularFile = true, sizeBytes = 10L)
                    }
                resolved.logoAvailable shouldBe false
                resolved.variants.keys shouldBe setOf(LogoVariant.WORDMARK_DARK)
            }

            test("no variants configured -> variants empty, probe is only called for the legacy path") {
                val resolved =
                    BrandingStartupCheck.resolve(
                        config = brandConfig(logoPath = "/app/branding/logo.png"),
                    ) { LogoProbe(readable = true, isRegularFile = true, sizeBytes = 1L) }
                resolved.variants.isEmpty() shouldBe true
                resolved.logoAvailable shouldBe true
            }
        }
    })
