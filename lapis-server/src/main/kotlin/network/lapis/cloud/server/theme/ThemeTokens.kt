package network.lapis.cloud.server.theme

/**
 * V1.9.216 "Themes per Datei" -- the single source (in Kotlin) of every fixed set of the theme file format. A theme can ONLY
 * override these [COLOR_KEYS]; adding one is a schema `version` 2 change (tokens, `theme.schema.json`, docs and tests in lockstep).
 */
object ThemeTokens {
    /** The 16 core colour tokens, each mapped to the CSS custom property `--lapis-<key>`. Order is the order of the generated CSS. */
    val COLOR_KEYS: List<String> =
        listOf(
            "bg",
            "surface",
            "surface-sunken",
            "text",
            "muted",
            "border",
            "border-strong",
            "nav",
            "nav-hover-bg",
            "nav-text",
            "nav-muted",
            "accent",
            "accent-strong",
            "accent-soft",
            "accent-contrast",
            "invalid",
        )

    const val SCHEMA_VERSION = 1
    const val MAX_THEME_JSON_BYTES = 64 * 1024
    const val MAX_FONT_BYTES = 512 * 1024
    const val MAX_FONTS_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_FONT_FILES = 8
    const val MAX_FILES_PER_ROLE = 4
    const val MAX_LICENSE_BYTES = 64 * 1024
    const val MAX_DESCRIPTION = 200
    const val MAX_DIRECTORY_ENTRIES = 256

    val NAME_REGEX = Regex("^[A-Za-z0-9 ._-]{1,64}$")
    val HEX_REGEX = Regex("^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    val FAMILY_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9 -]{0,62}$")
    val FONT_FILE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.woff2$")
    val LICENSE_REGEX = Regex("^(LICENSE|LICENCE|OFL|COPYING)([._-].*)?$", RegexOption.IGNORE_CASE)

    /** The first four bytes of every WOFF2 file: `wOF2`. */
    val WOFF2_MAGIC: ByteArray = byteArrayOf(0x77, 0x4F, 0x46, 0x32)

    const val DEFAULT_BODY_STACK = "'Inter Variable', -apple-system, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif"
    const val DEFAULT_DISPLAY_STACK = "Georgia, serif"

    /**
     * WCAG pairs checked per mode. Blocking pairs need 4.5:1 (text) and reject the whole theme; the advisory pair only warns
     * (the shipped defaults reach just 1.57:1 / 1.92:1 for `border-strong`, so it cannot be a hard rule without changing the
     * look of every existing installation).
     */
    val CONTRAST_PAIRS: List<ContrastPair> =
        listOf(
            ContrastPair(fg = "text", bg = "bg", min = 4.5, blocking = true),
            ContrastPair(fg = "text", bg = "surface", min = 4.5, blocking = true),
            ContrastPair(fg = "text", bg = "surface-sunken", min = 4.5, blocking = true),
            ContrastPair(fg = "text", bg = "accent-soft", min = 4.5, blocking = true),
            ContrastPair(fg = "muted", bg = "bg", min = 4.5, blocking = true),
            ContrastPair(fg = "muted", bg = "surface", min = 4.5, blocking = true),
            ContrastPair(fg = "muted", bg = "surface-sunken", min = 4.5, blocking = true),
            ContrastPair(fg = "nav-text", bg = "nav", min = 4.5, blocking = true),
            ContrastPair(fg = "nav-text", bg = "nav-hover-bg", min = 4.5, blocking = true),
            ContrastPair(fg = "nav-muted", bg = "nav", min = 4.5, blocking = true),
            ContrastPair(fg = "accent", bg = "surface", min = 4.5, blocking = true),
            ContrastPair(fg = "accent-contrast", bg = "accent", min = 4.5, blocking = true),
            ContrastPair(fg = "accent-strong", bg = "surface", min = 4.5, blocking = true),
            ContrastPair(fg = "invalid", bg = "surface", min = 4.5, blocking = true),
            ContrastPair(fg = "border-strong", bg = "surface", min = 3.0, blocking = false),
            // V1.9.247: pairs the public pages use since they follow the tokens; advisory first so an existing theme is never rejected
            ContrastPair(fg = "accent", bg = "bg", min = 4.5, blocking = false),
            ContrastPair(fg = "accent", bg = "surface-sunken", min = 4.5, blocking = false),
            ContrastPair(fg = "accent-strong", bg = "accent-soft", min = 4.5, blocking = false),
            ContrastPair(fg = "bg", bg = "invalid", min = 4.5, blocking = false),
        )
}

enum class RadiusPreset(
    val json: String,
    val base: String,
    val sm: String,
    val lg: String,
) {
    NONE("none", "0", "0", "0"),
    SMALL("small", ".25rem", ".125rem", ".375rem"),
    DEFAULT("default", ".375rem", ".25rem", ".5rem"),
    LARGE("large", ".75rem", ".5rem", "1rem"),
    ;

    companion object {
        fun fromJson(value: String): RadiusPreset? = entries.firstOrNull { it.json == value }
    }
}

enum class FontFallback(
    val json: String,
) {
    SANS_SERIF("sans-serif"),
    SERIF("serif"),
    SYSTEM_UI("system-ui"),
    MONOSPACE("monospace"),
    ;

    companion object {
        fun fromJson(value: String): FontFallback? = entries.firstOrNull { it.json == value }
    }
}

enum class FontStyle(
    val json: String,
) {
    NORMAL("normal"),
    ITALIC("italic"),
    ;

    companion object {
        fun fromJson(value: String): FontStyle? = entries.firstOrNull { it.json == value }
    }
}

data class ContrastPair(
    val fg: String,
    val bg: String,
    val min: Double,
    val blocking: Boolean,
)
