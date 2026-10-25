package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ThemeFontLoaderTest :
    FunSpec({
        fun spec(vararg files: String) =
            ThemeParser
                .parse(
                    themeJson { it["fonts"] = fontsJson(*files.mapIndexed { i, f -> Triple(f, 100 + i * 100, "normal") }.toTypedArray()) },
                ).spec!!

        fun load(
            dir: Path,
            vararg files: String,
        ) = ThemeFontLoader.load(realThemeDir = dir.toRealPath(), spec = spec(*files))

        test("happy path loads the font, hashes it and accepts the licence") {
            val dir = themeDir(json = "{}", fonts = mapOf("A.woff2" to fakeWoff2()))
            val out = load(dir, "A.woff2")
            out.findings shouldBe emptyList()
            out.fonts
                .getValue("A.woff2")
                .hash12.length shouldBe 12
        }
        test("a missing font file") {
            load(themeDir(json = "{}"), "A.woff2").findings.single().code shouldBe ThemeErrorCode.THEME_FONT_MISSING
        }
        test("wrong magic bytes (TTF)") {
            val ttf = ByteArray(64).also { it[1] = 1 }
            load(themeDir(json = "{}", fonts = mapOf("A.woff2" to ttf)), "A.woff2").findings.single().code shouldBe
                ThemeErrorCode.THEME_FONT_MAGIC
        }
        test("one byte over the per-file limit") {
            val big = fakeWoff2(ThemeTokens.MAX_FONT_BYTES + 1)
            load(themeDir(json = "{}", fonts = mapOf("A.woff2" to big)), "A.woff2").findings.single().code shouldBe
                ThemeErrorCode.THEME_FONT_TOO_LARGE
            val exact = fakeWoff2(ThemeTokens.MAX_FONT_BYTES)
            load(themeDir(json = "{}", fonts = mapOf("A.woff2" to exact)), "A.woff2").findings shouldBe emptyList()
        }
        test("the sum of the fonts is capped at 2 MiB") {
            val files = (1..5).associate { "F$it.woff2" to fakeWoff2(450 * 1024) }
            val base = spec("F1.woff2")
            // built by hand: the parser caps a role at four files, the loader still enforces the total on its own
            val role =
                FontRoleSpec(
                    family = "Inter",
                    fallback = FontFallback.SANS_SERIF,
                    files =
                        files.keys.map {
                            FontFileSpec(file = it, weight = 400, style = FontStyle.NORMAL)
                        },
                )
            val out = ThemeFontLoader.load(realThemeDir = themeDir(json = "{}", fonts = files).toRealPath(), spec = base.copy(body = role))
            out.fonts shouldBe emptyMap()
            out.findings.single().code shouldBe ThemeErrorCode.THEME_FONT_TOO_LARGE
        }
        test("a missing licence file drops the fonts; lower case names, LICENSE.md and OFL.txt are accepted") {
            load(themeDir(json = "{}", fonts = mapOf("A.woff2" to fakeWoff2()), license = null), "A.woff2").findings.single().code shouldBe
                ThemeErrorCode.THEME_FONT_LICENSE_MISSING
            load(themeDir(json = "{}", fonts = mapOf("A.woff2" to fakeWoff2()), license = "license.md"), "A.woff2").findings shouldBe
                emptyList()
            load(themeDir(json = "{}", fonts = mapOf("A.woff2" to fakeWoff2()), license = "OFL.txt"), "A.woff2").findings shouldBe
                emptyList()
        }
        test("an oversized licence file is rejected") {
            val dir = themeDir(json = "{}", fonts = mapOf("A.woff2" to fakeWoff2()), license = null)
            Files.write(dir.resolve("LICENSE"), ByteArray(ThemeTokens.MAX_LICENSE_BYTES + 1) { 'a'.code.toByte() })
            load(dir, "A.woff2").findings.single().code shouldBe ThemeErrorCode.THEME_FONT_LICENSE_MISSING
        }
        test("symbolic links are rejected, inside and outside the directory") {
            if (!symlinksSupported()) return@test
            val outside = Files.createTempFile("outside", ".woff2").also { Files.write(it, fakeWoff2()) }
            val dir = themeDir(json = "{}", fonts = mapOf("Real.woff2" to fakeWoff2()))
            Files.createSymbolicLink(dir.resolve("Out.woff2"), outside)
            Files.createSymbolicLink(dir.resolve("In.woff2"), dir.resolve("Real.woff2"))
            load(dir, "Out.woff2").findings.single().code shouldBe ThemeErrorCode.THEME_FONT_SYMLINK
            load(dir, "In.woff2").findings.single().code shouldBe ThemeErrorCode.THEME_FONT_SYMLINK
        }
        test("a licence that is a symbolic link does not count") {
            if (!symlinksSupported()) return@test
            val dir = themeDir(json = "{}", fonts = mapOf("A.woff2" to fakeWoff2()), license = null)
            val elsewhere = Files.createTempFile("lic", ".txt")
            Files.createSymbolicLink(dir.resolve("LICENSE"), elsewhere)
            load(dir, "A.woff2").findings.single().code shouldBe ThemeErrorCode.THEME_FONT_LICENSE_MISSING
        }
    })
