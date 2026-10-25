package network.lapis.cloud.server.theme

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `LAPIS_THEME_PATH` -- the operator's theme directory. Pure string validation, no file access. Unset or empty means "no custom
 * theme"; a value that is not an absolute path is [invalid] and the default applies (never fail-fast, like branding).
 */
data class ThemeConfig(
    val path: Path?,
    val invalid: Boolean,
) {
    companion object {
        const val ENV_PATH = "LAPIS_THEME_PATH"
        private const val MAX_LENGTH = 4096

        fun load(env: (String) -> String? = System::getenv): ThemeConfig {
            val raw = env(ENV_PATH)?.trim()
            if (raw.isNullOrEmpty()) return ThemeConfig(path = null, invalid = false)
            if (raw.length > MAX_LENGTH || raw.contains('\u0000') || raw.any { it < ' ' }) return ThemeConfig(path = null, invalid = true)
            val path =
                try {
                    Path.of(raw)
                } catch (_: InvalidPathException) {
                    return ThemeConfig(path = null, invalid = true)
                }
            return if (path.isAbsolute) ThemeConfig(path = path, invalid = false) else ThemeConfig(path = null, invalid = true)
        }
    }
}
