package network.lapis.cloud.server.theme

enum class ThemeErrorCode {
    THEME_DIR_MISSING,
    THEME_JSON_MISSING,
    THEME_JSON_SYMLINK,
    THEME_JSON_TOO_LARGE,
    THEME_JSON_MALFORMED,
    THEME_TYPE,
    THEME_UNKNOWN_KEY,
    THEME_MISSING_KEY,
    THEME_BAD_VERSION,
    THEME_BAD_NAME,
    THEME_BAD_DESCRIPTION,
    THEME_BAD_HEX,
    THEME_BAD_ENUM,
    THEME_CONTRAST,
    THEME_CONTRAST_ADVISORY,
    THEME_FONT_FAMILY,
    THEME_FONT_PATH,
    THEME_FONT_SYMLINK,
    THEME_FONT_MISSING,
    THEME_FONT_MAGIC,
    THEME_FONT_TOO_LARGE,
    THEME_FONT_COUNT,
    THEME_FONT_CONFLICT,
    THEME_FONT_LICENSE_MISSING,
    THEME_IO,
}

/** What the server does because of a finding. */
enum class ThemeAction(
    val log: String,
) {
    /** The whole theme (colours AND fonts) is discarded, the compiled-in default applies. */
    DEFAULT("default"),

    /** The colours are kept, the fonts are discarded. */
    FONTS_DROPPED("fonts-dropped"),

    /** Informational only. */
    NONE("none"),
}

enum class Severity { ERROR, WARNING }

data class ThemeFinding(
    val code: ThemeErrorCode,
    /** JSON-path style location (`$.colors.dark.accent`) or a file name. */
    val path: String,
    val message: String,
    val severity: Severity,
    val action: ThemeAction,
)

/** A validated, normalised colour: `#RRGGBB` or `#RRGGBBAA`, upper case. Only constructible through [parse]. */
@JvmInline
value class HexColor private constructor(
    val css: String,
) {
    val r: Int get() = css.substring(1, 3).toInt(16)
    val g: Int get() = css.substring(3, 5).toInt(16)
    val b: Int get() = css.substring(5, 7).toInt(16)
    val alpha: Double get() = if (css.length == 9) css.substring(7, 9).toInt(16) / 255.0 else 1.0

    companion object {
        fun parse(raw: String): HexColor? {
            if (!ThemeTokens.HEX_REGEX.matches(raw)) return null
            val digits = raw.substring(1).uppercase()
            val normalised =
                when (digits.length) {
                    3 -> "#" + digits.map { "$it$it" }.joinToString("")
                    else -> "#$digits"
                }
            return HexColor(normalised)
        }

        /** Builds from channel values (used by alpha compositing). */
        fun rgb(
            r: Int,
            g: Int,
            b: Int,
        ): HexColor = HexColor("#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
    }
}

data class FontFileSpec(
    val file: String,
    val weight: Int,
    val style: FontStyle,
)

data class FontRoleSpec(
    val family: String,
    val fallback: FontFallback,
    val files: List<FontFileSpec>,
)

data class ThemeSpec(
    val name: String,
    val light: Map<String, HexColor>,
    val dark: Map<String, HexColor>,
    val radius: RadiusPreset,
    val body: FontRoleSpec?,
    val display: FontRoleSpec?,
)
