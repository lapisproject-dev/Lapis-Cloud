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
                    "--lapis-nav-logo-light-display",
                    "--lapis-nav-logo-dark-display",
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

        val darkNavTokens = "--lapis-nav-logo-light-display:none;--lapis-nav-logo-dark-display:block"
        val lightNavTokens = "--lapis-nav-logo-light-display:block;--lapis-nav-logo-dark-display:none"

        fun withNav(
            light: String?,
            dark: String?,
        ): String {
            var json = themeJson { }

            fun set(
                mode: String,
                key: String,
                value: String,
            ) {
                val root =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
                val colors = (root.getValue("colors") as kotlinx.serialization.json.JsonObject).toMutableMap()
                val m = (colors.getValue(mode) as kotlinx.serialization.json.JsonObject).toMutableMap()
                m[key] = kotlinx.serialization.json.JsonPrimitive(value)
                colors[mode] = kotlinx.serialization.json.JsonObject(m)
                val r = root.toMutableMap()
                r["colors"] = kotlinx.serialization.json.JsonObject(colors)
                json =
                    kotlinx.serialization.json
                        .JsonObject(r)
                        .toString()
            }
            if (light != null) {
                set("light", "nav", light)
                set("light", "nav-hover-bg", "#00000010")
            }
            if (dark != null) set("dark", "nav", dark)
            return json
        }

        fun blocks(css: String): Triple<String, String, String> {
            val lines = css.lines().filter { it.isNotBlank() }
            return Triple(
                lines.single { it.startsWith("html:root{") },
                lines.single { it.startsWith("@media") },
                lines.single { it.startsWith("html:root[data-theme") },
            )
        }

        test("the shipped default theme selects the dark logo variant in all three blocks") {
            val spec = ThemeParser.parse(themeJson { }).spec!!
            listOf(ThemeCssRenderer.render(spec = spec, fonts = emptyMap()), ResolvedTheme.DEFAULT.css).forEach { css ->
                assertWhitelisted(css)
                val (root, media, dark) = blocks(css)
                listOf(root, media, dark).forEach { (it.contains(darkNavTokens)) shouldBe true }
            }
        }
        test("a light nav in light mode and a dark nav in dark mode switch per block") {
            val parsed = ThemeParser.parse(withNav(light = "#F2F2F2", dark = "#0B0E12"))
            val spec = parsed.spec ?: error("theme rejected: ${parsed.findings}")
            val css = ThemeCssRenderer.render(spec = spec, fonts = emptyMap())
            assertWhitelisted(css)
            val (root, media, dark) = blocks(css)
            root.endsWith("$lightNavTokens}") shouldBe true
            media.contains(darkNavTokens) shouldBe true
            dark.contains(darkNavTokens) shouldBe true
            root.contains(darkNavTokens) shouldBe false
        }
        test("a black nav in both modes selects the dark variant everywhere") {
            val parsed = ThemeParser.parse(withNav(light = "#000000", dark = "#000000"))
            val spec = parsed.spec ?: error("theme rejected: ${parsed.findings}")
            val (root, media, dark) = blocks(ThemeCssRenderer.render(spec = spec, fonts = emptyMap()))
            listOf(root, media, dark).forEach { (it.contains(darkNavTokens)) shouldBe true }
        }
        test("the nav logo literals are the only extra declarations and no theme text reaches them") {
            ThemeCssRenderer.navLogoDeclarations(dark = true) shouldBe darkNavTokens
            ThemeCssRenderer.navLogoDeclarations(dark = false) shouldBe lightNavTokens
        }
    })
