package network.lapis.cloud.server.theme

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

enum class ThemeSource(
    val log: String,
) {
    DEFAULT("default"),
    CUSTOM("custom"),
    CUSTOM_WITHOUT_FONTS("custom-without-fonts"),
    FALLBACK("fallback"),
}

data class ThemeLoadResult(
    val theme: ResolvedTheme,
    val source: ThemeSource,
    val findings: List<ThemeFinding>,
)

/**
 * Loads a theme directory (`theme.json` plus woff2 files). Shared by the server startup and the validator CLI. NEVER throws and
 * never logs -- a broken theme degrades to the compiled-in default (see `ThemeStartupCheck`).
 */
object ThemeLoader {
    fun load(themeDir: Path?): ThemeLoadResult =
        try {
            doLoad(themeDir)
        } catch (_: IOException) {
            fallback(
                ThemeFinding(
                    code = ThemeErrorCode.THEME_IO,
                    path = "theme",
                    message = "the theme directory could not be read",
                    severity = Severity.ERROR,
                    action = ThemeAction.DEFAULT,
                ),
            )
        } catch (_: SecurityException) {
            fallback(
                ThemeFinding(
                    code = ThemeErrorCode.THEME_IO,
                    path = "theme",
                    message = "the theme directory could not be read",
                    severity = Severity.ERROR,
                    action = ThemeAction.DEFAULT,
                ),
            )
        }

    private fun fallback(finding: ThemeFinding) =
        ThemeLoadResult(theme = ResolvedTheme.DEFAULT, source = ThemeSource.FALLBACK, findings = listOf(finding))

    private fun doLoad(themeDir: Path?): ThemeLoadResult {
        if (themeDir == null) return ThemeLoadResult(theme = ResolvedTheme.DEFAULT, source = ThemeSource.DEFAULT, findings = emptyList())
        // The directory itself may be a symlink or a mount point; only its entries are held to NOFOLLOW.
        val realDir =
            try {
                themeDir.toRealPath()
            } catch (_: java.nio.file.NoSuchFileException) {
                return fallback(missingDir())
            }
        if (!Files.isDirectory(realDir)) return fallback(missingDir())

        val jsonFile = realDir.resolve("theme.json")
        val attrs =
            try {
                Files.readAttributes(jsonFile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) {
                return fallback(finding(code = ThemeErrorCode.THEME_JSON_MISSING, path = "theme.json", message = "theme.json not found"))
            }
        if (attrs.isSymbolicLink) {
            return fallback(
                finding(code = ThemeErrorCode.THEME_JSON_SYMLINK, path = "theme.json", message = "theme.json must not be a symbolic link"),
            )
        }
        if (!attrs.isRegularFile) {
            return fallback(
                finding(code = ThemeErrorCode.THEME_JSON_MISSING, path = "theme.json", message = "theme.json is not a regular file"),
            )
        }
        val bytes =
            ThemeFontLoader.readBounded(file = jsonFile, max = ThemeTokens.MAX_THEME_JSON_BYTES)
                ?: return fallback(
                    finding(
                        code = ThemeErrorCode.THEME_JSON_TOO_LARGE,
                        path = "theme.json",
                        message = "larger than ${ThemeTokens.MAX_THEME_JSON_BYTES} bytes",
                    ),
                )
        val text =
            StrictJson.decodeUtf8(bytes)
                ?: return fallback(finding(code = ThemeErrorCode.THEME_JSON_MALFORMED, path = "$", message = "not valid UTF-8"))

        val parsed = ThemeParser.parse(text)
        val findings = parsed.findings.toMutableList()
        val spec = parsed.spec ?: return ThemeLoadResult(theme = ResolvedTheme.DEFAULT, source = ThemeSource.FALLBACK, findings = findings)

        // Atomic: a contrast failure in EITHER mode rejects colours and fonts together.
        findings += ThemeContrast.check(mode = "light", colors = spec.light)
        findings += ThemeContrast.check(mode = "dark", colors = spec.dark)
        if (findings.any {
                it.action == ThemeAction.DEFAULT
            }
        ) {
            return ThemeLoadResult(theme = ResolvedTheme.DEFAULT, source = ThemeSource.FALLBACK, findings = findings)
        }

        val fontsRequested = spec.body != null || spec.display != null
        val fontsDeclaredButDropped = findings.any { it.action == ThemeAction.FONTS_DROPPED }
        var effective = spec
        var fonts: Map<String, LoadedFont> = emptyMap()
        if (fontsRequested) {
            val loaded = ThemeFontLoader.load(realThemeDir = realDir, spec = spec)
            findings += loaded.findings
            fonts = loaded.fonts
            if (loaded.findings.any { it.action == ThemeAction.FONTS_DROPPED }) effective = spec.copy(body = null, display = null)
        }
        val withoutFonts = fontsDeclaredButDropped || (fontsRequested && effective.body == null && effective.display == null)
        val resolved = ThemeCssRenderer.resolve(name = spec.name, spec = effective, fonts = fonts)
        return ThemeLoadResult(
            theme = resolved,
            source = if (withoutFonts) ThemeSource.CUSTOM_WITHOUT_FONTS else ThemeSource.CUSTOM,
            findings = findings,
        )
    }

    private fun missingDir() =
        finding(
            code = ThemeErrorCode.THEME_DIR_MISSING,
            path = "theme",
            message = "the theme directory does not exist or is not a directory",
        )

    private fun finding(
        code: ThemeErrorCode,
        path: String,
        message: String,
    ) = ThemeFinding(code = code, path = path, message = message, severity = Severity.ERROR, action = ThemeAction.DEFAULT)
}
