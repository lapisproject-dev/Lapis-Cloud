package network.lapis.cloud.server.theme

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Startup resolution of the theme. NEVER throws and never fail-fasts (a theme is cosmetic; see `BrandingStartupCheck`). Logs only
 * codes, JSON paths, the action, the theme name (regex-checked) and hashes -- no file contents, no raw values, no messages.
 */
object ThemeStartupCheck {
    fun resolve(
        config: ThemeConfig,
        loader: (java.nio.file.Path?) -> ThemeLoadResult = ThemeLoader::load,
    ): ResolvedTheme {
        if (config.invalid) {
            logger.warn {
                "theme.rejected code=THEME_DIR_MISSING path=${ThemeConfig.ENV_PATH} action=default (value is not an absolute path)"
            }
            return ResolvedTheme.DEFAULT
        }
        val result =
            try {
                loader(config.path)
            } catch (e: Exception) {
                logger.warn { "theme.rejected code=THEME_IO path=theme action=default" }
                return ResolvedTheme.DEFAULT
            }
        result.findings.forEach { f ->
            val line = "theme.rejected code=${f.code} path=${f.path} action=${f.action.log}"
            if (f.severity == Severity.WARNING) logger.info { line.replace("theme.rejected", "theme.advisory") } else logger.warn { line }
        }
        when (result.source) {
            ThemeSource.DEFAULT -> logger.info { "theme.default hash=${result.theme.hash12}" }
            ThemeSource.FALLBACK -> logger.info { "theme.fallback hash=${result.theme.hash12}" }
            ThemeSource.CUSTOM, ThemeSource.CUSTOM_WITHOUT_FONTS ->
                logger.info {
                    "theme.loaded name=${result.theme.name} hash=${result.theme.hash12} source=${result.source.log} fonts=${result.theme.fonts.size}"
                }
        }
        return result.theme
    }
}
