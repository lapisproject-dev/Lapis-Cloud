package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.theme.repoFile

/** V1.9.267 -- the server-side logo constants must equal the client's theme.css values. */
class PublicBrandLogoSizeDriftTest :
    FunSpec({
        val file = repoFile("lapis-client/src/jsMain/resources/theme.css")
        check(file.exists()) { "theme.css not found at ${file.absolutePath}" }
        val css = file.readText()
        val msg = "theme.css and server constant drifted: update both"

        fun first(regex: String): String {
            val found = Regex(regex).find(css) ?: error("$msg (pattern not found: $regex)")
            return found.groupValues[1]
        }

        test("token value") {
            first("""--lapis-brand-logo-size:\s*([^;]+);""") shouldBe PublicBrandLogoSize.SIZE
        }

        test("desktop max width") {
            first("""\.lapis-brand-logo img \{[^}]*max-width:\s*(\d+)px""").toInt() shouldBe PublicBrandLogoSize.MAX_WIDTH_PX
        }

        test("V1.9.284 caps: 240 on desktop, 160 on phones, lockup stays 40px / 240px, in theme.css and the public stylesheet") {
            PublicBrandLogoSize.MAX_WIDTH_PX shouldBe 240
            PublicBrandLogoSize.PHONE_MAX_WIDTH_PX shouldBe 160
            val lockupPattern = Regex("""\.lapis-lockup \.lapis-brand-logo img,\s*\.lapis-lockup \.lapis-brand-mark-svg \{([^}]*)\}""")
            val lockup = lockupPattern.find(css) ?: error("$msg (lockup rule not found)")
            lockup.groupValues[1] shouldContain "height: 40px"
            lockup.groupValues[1] shouldContain "max-width: 240px"
            val publicCss = SocialPublicHtml.STYLESHEET
            publicCss shouldContain "max-width: 240px; object-fit: contain"
            publicCss shouldContain "{ .chrome-logo { max-width: 160px; } }"
            publicCss shouldNotContain "max-width: 180px"
            publicCss shouldNotContain "max-width: 120px"
        }

        test("phone max width and breakpoint") {
            val pattern =
                Regex(
                    """@media \(max-width: ([\d.]+px)\) \{(?:(?!\n\}).)*?""" +
                        """\.lapis-navbar \.lapis-brand-logo img \{\s*max-width:\s*(\d+)px""",
                    RegexOption.DOT_MATCHES_ALL,
                )
            val m = pattern.find(css) ?: error("$msg (phone block not found)")
            m.groupValues[1] shouldBe PublicBrandLogoSize.PHONE_BREAKPOINT
            m.groupValues[2].toInt() shouldBe PublicBrandLogoSize.PHONE_MAX_WIDTH_PX
        }
    })
