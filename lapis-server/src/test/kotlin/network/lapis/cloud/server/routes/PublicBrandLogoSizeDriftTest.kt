package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
