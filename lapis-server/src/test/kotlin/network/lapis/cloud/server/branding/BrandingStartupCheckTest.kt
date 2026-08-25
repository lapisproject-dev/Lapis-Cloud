package network.lapis.cloud.server.branding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
    })
