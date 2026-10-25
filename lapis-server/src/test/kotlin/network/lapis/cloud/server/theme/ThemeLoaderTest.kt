package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class ThemeLoaderTest :
    FunSpec({
        test("no directory configured is the default theme") {
            val r = ThemeLoader.load(null)
            r.source shouldBe ThemeSource.DEFAULT
            r.theme shouldBe ResolvedTheme.DEFAULT
        }
        test("a missing directory falls back") {
            val r = ThemeLoader.load(Path.of("/nonexistent/lapis-theme-dir"))
            r.source shouldBe ThemeSource.FALLBACK
            r.findings.single().code shouldBe ThemeErrorCode.THEME_DIR_MISSING
        }
        test("theme.json missing, too large and malformed") {
            val empty = Files.createTempDirectory("lapis-theme-empty")
            ThemeLoader
                .load(empty)
                .findings
                .single()
                .code shouldBe ThemeErrorCode.THEME_JSON_MISSING
            val big = themeDir(json = " ".repeat(ThemeTokens.MAX_THEME_JSON_BYTES + 1))
            ThemeLoader
                .load(big)
                .findings
                .single()
                .code shouldBe ThemeErrorCode.THEME_JSON_TOO_LARGE
            ThemeLoader
                .load(themeDir(json = "{not json"))
                .findings
                .single()
                .code shouldBe ThemeErrorCode.THEME_JSON_MALFORMED
        }
        test("theme.json as a symbolic link is rejected; the directory itself may be a link") {
            if (!symlinksSupported()) return@test
            val real = themeDir(json = DEFAULT_THEME_JSON)
            val holder = Files.createTempDirectory("lapis-theme-link")
            Files.createSymbolicLink(holder.resolve("theme.json"), real.resolve("theme.json"))
            ThemeLoader
                .load(holder)
                .findings
                .single()
                .code shouldBe ThemeErrorCode.THEME_JSON_SYMLINK
            val dirLink = Files.createTempDirectory("lapis-theme-dirlink").resolve("link")
            Files.createSymbolicLink(dirLink, real)
            ThemeLoader.load(dirLink).source shouldBe ThemeSource.CUSTOM
        }
        test("a valid theme without fonts is custom") {
            val r = ThemeLoader.load(themeDir(json = DEFAULT_THEME_JSON))
            r.source shouldBe ThemeSource.CUSTOM
            r.theme.name shouldBe "Lapis Default"
        }
        test("a contrast failure in one mode rejects colours and valid fonts together") {
            val json = themeJsonWithColor(mode = "dark", key = "text", value = "#14181E")
            val withFonts = ThemeParser.parse(json) // sanity: structurally fine
            withFonts.spec!!.name shouldBe "Lapis Default"
            val fontJson =
                themeJson {
                    it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal"))
                }.replace("\"text\":\"#E8E6E0\"", "\"text\":\"#14181E\"")
            val dir = themeDir(json = fontJson, fonts = mapOf("A.woff2" to fakeWoff2()))
            val r = ThemeLoader.load(dir)
            r.source shouldBe ThemeSource.FALLBACK
            r.theme.fonts shouldBe emptyMap()
            r.findings.map { it.code } shouldContain ThemeErrorCode.THEME_CONTRAST
        }
        test("a licence error keeps the theme colours and drops the fonts") {
            val json =
                themeJson {
                    it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal"))
                    it["name"] = JsonPrimitive("Keep Colours")
                }
            val r = ThemeLoader.load(themeDir(json = json, fonts = mapOf("A.woff2" to fakeWoff2()), license = null))
            r.source shouldBe ThemeSource.CUSTOM_WITHOUT_FONTS
            r.theme.name shouldBe "Keep Colours"
            r.theme.fonts shouldBe emptyMap()
            r.findings
                .filter { it.severity == Severity.ERROR }
                .single()
                .code shouldBe ThemeErrorCode.THEME_FONT_LICENSE_MISSING
        }
        test("a theme with valid fonts exposes them and links them in the CSS") {
            val json = themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal")) }
            val r = ThemeLoader.load(themeDir(json = json, fonts = mapOf("A.woff2" to fakeWoff2())))
            r.source shouldBe ThemeSource.CUSTOM
            r.theme.fonts.keys shouldBe setOf("A.woff2")
            r.theme.css shouldContain "/theme-fonts/A.woff2?v=${r.theme.fonts.getValue("A.woff2").hash12}"
        }
        test("a display-only fonts section is custom, not custom-without-fonts") {
            val json =
                themeJson {
                    val body = fontsJson(Triple("A.woff2", 400, "normal")).getValue("body")
                    it["fonts"] = JsonObject(mapOf("display" to body))
                }
            val r = ThemeLoader.load(themeDir(json = json, fonts = mapOf("A.woff2" to fakeWoff2())))
            r.source shouldBe ThemeSource.CUSTOM
            r.theme.fonts.keys shouldBe setOf("A.woff2")
        }
    })
