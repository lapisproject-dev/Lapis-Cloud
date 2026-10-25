package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ThemeCssRendererTest :
    FunSpec({
        val allowedProps =
            ThemeTokens.COLOR_KEYS.map { "--lapis-$it" } +
                listOf(
                    "--lapis-font-body",
                    "--lapis-font-display",
                    "--bs-border-radius",
                    "--bs-border-radius-sm",
                    "--bs-border-radius-lg",
                ) +
                listOf("font-family", "src", "font-weight", "font-style", "font-display")

        /** Whitelist walk over the generated CSS: structure, selectors, properties and url() targets. */
        fun assertWhitelisted(css: String) {
            css shouldNotContain "/*"
            css shouldNotContain "\\"
            css shouldNotContain "<"
            css shouldNotContain "@import"
            css shouldNotContain "expression"
            val selectors =
                Regex("""(^|\n)([^{}\n]+)\{""").findAll(css).map { it.groupValues[2].trim() }.toList()
            selectors.forEach { sel ->
                val ok =
                    sel == "html:root" ||
                        sel == "html:root[data-theme=\"dark\"]" ||
                        sel == "@media (prefers-color-scheme: dark)" ||
                        sel == "html:root:not([data-theme=\"light\"])" ||
                        sel == "@font-face"
                (ok) shouldBe true
            }
            // every declaration inside a leaf block: name before the first colon must be whitelisted
            Regex("""\{([^{}]*)}""").findAll(css).forEach { block ->
                block.groupValues[1].split(";").filter { it.isNotBlank() }.forEach { decl ->
                    allowedProps.contains(decl.substringBefore(":").trim()) shouldBe true
                }
            }
            Regex("""url\('([^']*)'\)""").findAll(css).forEach {
                Regex("""^/theme-fonts/[A-Za-z0-9][A-Za-z0-9._-]{0,63}\.woff2\?v=[0-9a-f]{12}$""").matches(it.groupValues[1]) shouldBe true
            }
            css.count { it == '{' } shouldBe css.count { it == '}' }
        }

        test("the default CSS is whitelisted and has the three root blocks") {
            val css = ResolvedTheme.DEFAULT.css
            assertWhitelisted(css)
            css shouldNotContain "@font-face"
            (css.contains("html:root{--lapis-bg:#FFFFFF;")) shouldBe true
            (css.contains("@media (prefers-color-scheme: dark){html:root:not([data-theme=\"light\"]){--lapis-bg:#14181E")) shouldBe true
            (css.contains("html:root[data-theme=\"dark\"]{--lapis-bg:#14181E")) shouldBe true
            (css.contains("--lapis-font-body:${ThemeTokens.DEFAULT_BODY_STACK}")) shouldBe true
            (css.contains("--bs-border-radius:.375rem;--bs-border-radius-sm:.25rem;--bs-border-radius-lg:.5rem")) shouldBe true
        }
        test("rendering is deterministic and a font change changes the hash") {
            val spec = ThemeParser.parse(themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal")) }).spec!!

            fun font(byte: Int) =
                mapOf(
                    "A.woff2" to
                        fakeWoff2().also { it[10] = byte.toByte() }.let { bytes ->
                            val sha =
                                java.security.MessageDigest
                                    .getInstance(
                                        "SHA-256",
                                    ).digest(bytes)
                                    .joinToString("") { "%02x".format(it) }
                            LoadedFont(fileName = "A.woff2", bytes = bytes, sha256Hex = sha)
                        },
                )
            val a1 = ThemeCssRenderer.resolve(name = "T", spec = spec, fonts = font(1))
            val a2 = ThemeCssRenderer.resolve(name = "T", spec = spec, fonts = font(1))
            val b = ThemeCssRenderer.resolve(name = "T", spec = spec, fonts = font(2))
            a1.sha256Hex shouldBe a2.sha256Hex
            (a1.sha256Hex == b.sha256Hex) shouldBe false
            assertWhitelisted(a1.css)
        }
        test("theme name and description never reach the CSS") {
            val spec = ThemeParser.parse(themeJson { }).spec!!
            ThemeCssRenderer.render(spec = spec, fonts = emptyMap()) shouldNotContain spec.name
        }
        test("radius presets") {
            val spec = ThemeParser.parse(themeJson { it["radius"] = kotlinx.serialization.json.JsonPrimitive("large") }).spec!!
            (
                ThemeCssRenderer
                    .render(
                        spec = spec,
                        fonts = emptyMap(),
                    ).contains("--bs-border-radius:.75rem;--bs-border-radius-sm:.5rem;--bs-border-radius-lg:1rem")
            ) shouldBe
                true
        }
    })
