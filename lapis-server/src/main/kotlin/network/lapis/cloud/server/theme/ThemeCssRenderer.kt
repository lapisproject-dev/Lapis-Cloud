package network.lapis.cloud.server.theme

import java.security.MessageDigest

/** The theme this process serves, resolved once at startup. [css] is the complete `/theme.css` body. */
class ResolvedTheme(
    val name: String,
    val css: String,
    val sha256Hex: String,
    val fonts: Map<String, LoadedFont>,
) {
    val hash12: String get() = sha256Hex.take(12)

    /** Weak: `Compression` is installed globally and re-encodes the CSS (same reasoning as `respondPublicCacheable`). */
    val etag: String get() = "W/\"${sha256Hex.take(32)}\""

    companion object {
        /** The compiled-in default: built without any file access. */
        val DEFAULT: ResolvedTheme by lazy { ThemeCssRenderer.resolve(name = "Lapis Default", spec = null, fonts = emptyMap()) }
    }
}

/**
 * Generates `/theme.css` from a validated [ThemeSpec]. The output is assembled from fixed templates only: normalised hex values,
 * enum-derived strings, regex-validated family names and hash-versioned font URLs. No free string from the theme file (name,
 * description, anything else) ever reaches the CSS, and the CSS contains no comments.
 *
 * Selectors use `html:root` so each rule beats the client's `:root` token blocks regardless of stylesheet order (the SPA's
 * `theme.css` is injected by the bundler at runtime, after any static link).
 */
object ThemeCssRenderer {
    fun render(
        spec: ThemeSpec?,
        fonts: Map<String, LoadedFont>,
    ): String {
        val light = ThemeTokens.COLOR_KEYS.map { it to (spec?.light?.get(it)?.css ?: ThemeDefaults.LIGHT.getValue(it)) }
        val dark = ThemeTokens.COLOR_KEYS.map { it to (spec?.dark?.get(it)?.css ?: ThemeDefaults.DARK.getValue(it)) }
        val radius = spec?.radius ?: RadiusPreset.DEFAULT
        val body = spec?.body
        val display = spec?.display

        val sb = StringBuilder()
        listOfNotNull(body, display).forEach { role ->
            role.files.forEach { f ->
                val font = fonts[f.file] ?: return@forEach
                sb.append("@font-face{font-family:'").append(role.family).append("';")
                sb
                    .append("src:url('/theme-fonts/")
                    .append(font.fileName)
                    .append("?v=")
                    .append(font.hash12)
                    .append("') format('woff2');")
                sb
                    .append("font-weight:")
                    .append(f.weight)
                    .append(";font-style:")
                    .append(f.style.json)
                    .append(";font-display:swap}\n")
            }
        }

        fun declarations(entries: List<Pair<String, String>>) = entries.joinToString(";") { (k, v) -> "--lapis-$k:$v" }

        val bodyStack =
            if (body ==
                null
            ) {
                ThemeTokens.DEFAULT_BODY_STACK
            } else {
                stack(role = body, defaultStack = ThemeTokens.DEFAULT_BODY_STACK)
            }
        val displayStack = if (display == null) ThemeTokens.DEFAULT_DISPLAY_STACK else stack(role = display, defaultStack = null)
        sb.append("html:root{").append(declarations(light))
        sb.append(";--lapis-font-body:").append(bodyStack)
        sb.append(";--lapis-font-display:").append(displayStack)
        sb.append(";--bs-border-radius:").append(radius.base)
        sb.append(";--bs-border-radius-sm:").append(radius.sm)
        sb.append(";--bs-border-radius-lg:").append(radius.lg).append("}\n")
        sb.append("@media (prefers-color-scheme: dark){html:root:not([data-theme=\"light\"]){").append(declarations(dark)).append("}}\n")
        sb.append("html:root[data-theme=\"dark\"]{").append(declarations(dark)).append("}\n")
        return sb.toString()
    }

    private fun stack(
        role: FontRoleSpec,
        defaultStack: String?,
    ): String {
        val family = "'${role.family}'"
        return when {
            role.fallback == FontFallback.SANS_SERIF && defaultStack != null -> "$family, $defaultStack"
            else -> "$family, ${role.fallback.json}"
        }
    }

    fun resolve(
        name: String,
        spec: ThemeSpec?,
        fonts: Map<String, LoadedFont>,
    ): ResolvedTheme {
        val css = render(spec = spec, fonts = fonts)
        val hash = MessageDigest.getInstance("SHA-256").digest(css.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ResolvedTheme(name = name, css = css, sha256Hex = hash, fonts = fonts)
    }
}
