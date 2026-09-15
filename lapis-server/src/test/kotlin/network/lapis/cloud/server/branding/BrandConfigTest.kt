package network.lapis.cloud.server.branding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/**
 * Exercises [BrandConfig.load] purely through its injected `env` function -- never
 * `System.getenv`, no filesystem access -- see that function's own KDoc "Pure string validation
 * ONLY". Mirrors `network.lapis.cloud.server.mail.SmtpConfigTest`'s own structure/conventions.
 */
class BrandConfigTest :
    FunSpec({
        test("everything unset -> default title, no logo, no invalid entries, never throws") {
            val config = BrandConfig.load(envOf())
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.logoPath shouldBe null
            config.invalid.shouldBeEmpty()
        }

        test("whitespace-only title -> default") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to "   "))
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.invalid.shouldBeEmpty()
        }

        test("valid title -> trimmed value, no invalid entry") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to "  Partei der Vernunft  "))
            config.title shouldBe "Partei der Vernunft"
            config.invalid.shouldBeEmpty()
        }

        test("title containing \\r -> default, invalid names LAPIS_BRAND_TITLE") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to "Evil\r\nTitle"))
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.invalid shouldContain BrandConfig.ENV_TITLE
        }

        test("title containing \\n only -> default, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to "Line1\nLine2"))
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.invalid shouldContain BrandConfig.ENV_TITLE
        }

        test("title with an embedded tab (other control character) -> default, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to "Tab\tHere"))
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.invalid shouldContain BrandConfig.ENV_TITLE
        }

        test("title exactly 80 characters -> accepted, no invalid entry (boundary)") {
            val title80 = "A".repeat(80)
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to title80))
            config.title shouldBe title80
            config.invalid.shouldBeEmpty()
        }

        test("title exactly 81 characters -> default, invalid (boundary)") {
            val title81 = "A".repeat(81)
            val config = BrandConfig.load(envOf(BrandConfig.ENV_TITLE to title81))
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.invalid shouldContain BrandConfig.ENV_TITLE
        }

        test("logo path unset -> null, no invalid entry") {
            val config = BrandConfig.load(envOf())
            config.logoPath shouldBe null
            config.invalid.shouldBeEmpty()
        }

        test("relative logo path -> null, invalid names LAPIS_BRAND_LOGO_PATH") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "branding/logo.svg"))
            config.logoPath shouldBe null
            config.invalid shouldContain BrandConfig.ENV_LOGO_PATH
        }

        test("logo path with an embedded NUL byte -> null, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/lo\u0000go.svg"))
            config.logoPath shouldBe null
            config.invalid shouldContain BrandConfig.ENV_LOGO_PATH
        }

        test("logo path with a disallowed extension (.exe) -> null, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/logo.exe"))
            config.logoPath shouldBe null
            config.invalid shouldContain BrandConfig.ENV_LOGO_PATH
        }

        test("logo path with no extension at all -> null, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/logo"))
            config.logoPath shouldBe null
            config.invalid shouldContain BrandConfig.ENV_LOGO_PATH
        }

        test("logo path ending in .svg -> accepted") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/logo.svg"))
            config.logoPath shouldBe "/app/branding/logo.svg"
            config.invalid.shouldBeEmpty()
        }

        test("logo path ending in .png -> accepted") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/logo.png"))
            config.logoPath shouldBe "/app/branding/logo.png"
            config.invalid.shouldBeEmpty()
        }

        test("logo path ending in .webp -> accepted") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/logo.webp"))
            config.logoPath shouldBe "/app/branding/logo.webp"
            config.invalid.shouldBeEmpty()
        }

        test("logo path extension check is case-insensitive (.SVG accepted)") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_LOGO_PATH to "/app/branding/logo.SVG"))
            config.logoPath shouldBe "/app/branding/logo.SVG"
            config.invalid.shouldBeEmpty()
        }

        test("both title and logo path independently invalid -> both names present") {
            val config =
                BrandConfig.load(
                    envOf(
                        BrandConfig.ENV_TITLE to "Bad\r\nTitle",
                        BrandConfig.ENV_LOGO_PATH to "relative/logo.svg",
                    ),
                )
            config.title shouldBe BrandConfig.DEFAULT_TITLE
            config.logoPath shouldBe null
            config.invalid shouldContain BrandConfig.ENV_TITLE
            config.invalid shouldContain BrandConfig.ENV_LOGO_PATH
        }

        test("website url unset -> null, no invalid entry") {
            val config = BrandConfig.load(envOf())
            config.websiteUrl shouldBe null
            config.invalid.shouldBeEmpty()
        }

        test("whitespace-only website url -> null, no invalid entry") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to "   "))
            config.websiteUrl shouldBe null
            config.invalid.shouldBeEmpty()
        }

        test("valid https website url -> trimmed value, no invalid entry") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to "  https://parteidervernunft.de  "))
            config.websiteUrl shouldBe "https://parteidervernunft.de"
            config.invalid.shouldBeEmpty()
        }

        test("valid http website url -> accepted") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to "http://example.org"))
            config.websiteUrl shouldBe "http://example.org"
            config.invalid.shouldBeEmpty()
        }

        test("javascript: scheme -> null, invalid names LAPIS_BRAND_WEBSITE_URL (XSS guard)") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to "javascript:alert(1)"))
            config.websiteUrl shouldBe null
            config.invalid shouldContain BrandConfig.ENV_WEBSITE_URL
        }

        test("no scheme at all -> null, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to "parteidervernunft.de"))
            config.websiteUrl shouldBe null
            config.invalid shouldContain BrandConfig.ENV_WEBSITE_URL
        }

        test("website url containing a control character -> null, invalid") {
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to "https://example.org/\r\nEvil"))
            config.websiteUrl shouldBe null
            config.invalid shouldContain BrandConfig.ENV_WEBSITE_URL
        }

        test("website url longer than 200 characters -> null, invalid (boundary)") {
            val tooLong = "https://example.org/" + "a".repeat(200)
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to tooLong))
            config.websiteUrl shouldBe null
            config.invalid shouldContain BrandConfig.ENV_WEBSITE_URL
        }

        test("website url exactly 200 characters -> accepted (boundary)") {
            val exactly200 = "https://example.org/" + "a".repeat(200 - "https://example.org/".length)
            exactly200.length shouldBe 200
            val config = BrandConfig.load(envOf(BrandConfig.ENV_WEBSITE_URL to exactly200))
            config.websiteUrl shouldBe exactly200
            config.invalid.shouldBeEmpty()
        }
    })
