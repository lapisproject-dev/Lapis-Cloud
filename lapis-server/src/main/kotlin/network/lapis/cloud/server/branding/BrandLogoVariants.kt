package network.lapis.cloud.server.branding

import io.ktor.http.ContentType

/** Which theme the logo is drawn for. [LIGHT] is the artwork meant for a light page, [DARK] the one meant for a dark page. */
enum class LogoMode { LIGHT, DARK }

/** The two logo shapes: a compact [SIGNET] (navbar) and a [WORDMARK] (lockup above the sign-in card, public chrome). */
enum class LogoKind { SIGNET, WORDMARK }

/**
 * V1.9.265 -- the four operator-supplied brand logo variants (signet/wordmark x light/dark). Each is configured by its own
 * `LAPIS_BRAND_*_PATH` variable, validated exactly like the legacy `LAPIS_BRAND_LOGO_PATH` (see [BrandConfig]) and served at
 * [routePath]. The [slug] is matched EXACTLY (no trim, no case folding) -- see [fromSlug].
 */
enum class LogoVariant(
    val slug: String,
    val envName: String,
    val kind: LogoKind,
    val mode: LogoMode,
) {
    SIGNET_LIGHT("signet-light", "LAPIS_BRAND_SIGNET_LIGHT_PATH", LogoKind.SIGNET, LogoMode.LIGHT),
    SIGNET_DARK("signet-dark", "LAPIS_BRAND_SIGNET_DARK_PATH", LogoKind.SIGNET, LogoMode.DARK),
    WORDMARK_LIGHT("wordmark-light", "LAPIS_BRAND_WORDMARK_LIGHT_PATH", LogoKind.WORDMARK, LogoMode.LIGHT),
    WORDMARK_DARK("wordmark-dark", "LAPIS_BRAND_WORDMARK_DARK_PATH", LogoKind.WORDMARK, LogoMode.DARK),
    ;

    val routePath: String get() = "${BrandingHtml.LOGO_ROUTE_PATH}/$slug"

    companion object {
        /** Exact match only: no trim, no case folding. Anything else (including `null`) is `null`. */
        fun fromSlug(raw: String?): LogoVariant? = entries.firstOrNull { it.slug == raw }

        fun of(
            kind: LogoKind,
            mode: LogoMode,
        ): LogoVariant = entries.first { it.kind == kind && it.mode == mode }
    }
}

/** A logo file that passed the startup probe. [extension] is lower case and one of `svg`, `png`, `webp`. */
data class ResolvedLogoFile(
    val path: String,
    val extension: String,
)

/** What a (mode, kind) slot shows. Total: [resolveLogo] always returns one of these. */
sealed interface LogoSource {
    data class Variant(
        val variant: LogoVariant,
    ) : LogoSource

    /** The legacy `LAPIS_BRAND_LOGO_PATH` logo, served at `/api/branding/logo`. */
    data object Legacy : LogoSource

    /** The built-in Lapis Cloud mark. */
    data object DefaultMark : LogoSource
}

/**
 * V1.9.284 -- the logo kind the header shows, on the public chrome and in the SPA navbar. The only place deciding it: the
 * `navbar` flag of `wordmarkCarriesName` and the SPA navbar (which reads `logos.wordmark`) follow this constant.
 */
internal val HEADER_LOGO_KIND: LogoKind = LogoKind.WORDMARK

private fun other(mode: LogoMode) = if (mode == LogoMode.LIGHT) LogoMode.DARK else LogoMode.LIGHT

private fun other(kind: LogoKind) = if (kind == LogoKind.SIGNET) LogoKind.WORDMARK else LogoKind.SIGNET

/**
 * Total fallback chain, pure, based ONLY on [ResolvedBranding.variants] and [ResolvedBranding.logoAvailable] (a variant is in the
 * map only if its file was usable at startup, so the chain never points at a broken image):
 * same kind (own mode, other mode) -> other kind (own mode, other mode) -> legacy logo -> default mark.
 */
fun ResolvedBranding.resolveLogo(
    mode: LogoMode,
    kind: LogoKind,
): LogoSource {
    val candidates =
        listOf(
            LogoVariant.of(kind = kind, mode = mode),
            LogoVariant.of(kind = kind, mode = other(mode)),
            LogoVariant.of(kind = other(kind), mode = mode),
            LogoVariant.of(kind = other(kind), mode = other(mode)),
        )
    candidates.firstOrNull { it in variants }?.let { return LogoSource.Variant(it) }
    return if (logoAvailable) LogoSource.Legacy else LogoSource.DefaultMark
}

/** The URL a [source] is served at, or `null` for the built-in mark (which is drawn inline, never fetched). */
fun ResolvedBranding.urlFor(source: LogoSource): String? =
    when (source) {
        is LogoSource.Variant -> source.variant.routePath
        LogoSource.Legacy -> BrandingHtml.LOGO_ROUTE_PATH
        LogoSource.DefaultMark -> null
    }

/**
 * The file behind `GET /api/branding/logo`: the legacy logo if it is available, otherwise the file of
 * `resolveLogo(LIGHT, SIGNET)` when that is a variant, otherwise `null`.
 */
fun ResolvedBranding.legacyRouteFile(): ResolvedLogoFile? {
    val legacyPath = logoPath
    if (logoAvailable && legacyPath != null) {
        return ResolvedLogoFile(path = legacyPath, extension = legacyPath.substringAfterLast('.', missingDelimiterValue = "").lowercase())
    }
    val source = resolveLogo(mode = LogoMode.LIGHT, kind = LogoKind.SIGNET)
    return (source as? LogoSource.Variant)?.let { variants[it.variant] }
}

private val RASTER_EXTENSIONS = setOf("png", "webp")

/**
 * Route path of the image for `og:image` / JSON-LD: the first RASTER (png/webp) logo in the order wordmark-light, signet-light,
 * wordmark-dark, signet-dark, legacy. Never an SVG (social networks reject it); `null` if there is no raster logo.
 */
fun ResolvedBranding.rasterShareLogoPath(): String? {
    val order =
        listOf(LogoVariant.WORDMARK_LIGHT, LogoVariant.SIGNET_LIGHT, LogoVariant.WORDMARK_DARK, LogoVariant.SIGNET_DARK)
    for (variant in order) {
        val file = variants[variant] ?: continue
        if (file.extension in RASTER_EXTENSIONS) return variant.routePath
    }
    val legacy = legacyPath()
    if (legacy != null && legacy.substringAfterLast('.', missingDelimiterValue = "").lowercase() in RASTER_EXTENSIONS) {
        return BrandingHtml.LOGO_ROUTE_PATH
    }
    return null
}

private fun ResolvedBranding.legacyPath(): String? = if (logoAvailable) logoPath else null

/**
 * `true` iff BOTH modes of [kind] resolve to a `WORDMARK_*` variant, i.e. the shown image already spells the operator name and a
 * text label next to it would duplicate it.
 */
fun ResolvedBranding.wordmarkCarriesName(kind: LogoKind): Boolean =
    LogoMode.entries.all { mode ->
        val source = resolveLogo(mode = mode, kind = kind)
        source is LogoSource.Variant && source.variant.kind == LogoKind.WORDMARK
    }

/**
 * Fixed allowlist, NOT `ContentType.parse` on an arbitrary extension (config-time acceptance lives in [BrandConfig]; drifting apart
 * would fail closed with a 404, never open). Moved here from `Application.kt` so there is a single source.
 */
internal fun brandingLogoContentType(extension: String): ContentType? =
    when (extension.lowercase()) {
        "svg" -> ContentType.parse("image/svg+xml")
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType.parse("image/webp")
        else -> null
    }
