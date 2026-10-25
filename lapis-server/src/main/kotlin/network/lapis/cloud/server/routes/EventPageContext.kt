package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.embed.EmbedConfig

private val logger = KotlinLogging.logger {}

/**
 * V1.9.128 -- the two presentations of the public `/veranstaltung/...` page family.
 *
 * - [STANDARD]: the full Lapis header (brand, icon navigation, language switcher), wide layout, footer.
 * - [EMBED]: the slim, header-less page for embedding in an `<iframe>` on a partner website
 *   (`?embed=1`). Framing is only ever allowed for this mode, and only for the operator-configured origin allowlist
 *   (see [applyPageModeHeaders]).
 */
internal enum class EventPageMode {
    STANDARD,
    EMBED,
    ;

    companion object {
        /**
         * Exactly one `embed` parameter with the literal value `1` activates [EMBED]; everything else (missing, `0`, `true`,
         * repeated, `<script>`) is [STANDARD]. The raw value is never used for anything else, so it can never be reflected.
         */
        fun of(call: ApplicationCall): EventPageMode =
            if (call.request.queryParameters
                    .getAll("embed")
                    ?.singleOrNull() == "1"
            ) {
                EMBED
            } else {
                STANDARD
            }
    }
}

/**
 * Everything a render function of the `/veranstaltung/...` family needs to know about the current request. Built ONCE per request by
 * [eventPageContext], never inside a transaction (the nav lookup is cached and never throws, see
 * [PublicNavAvailabilityProvider.current]).
 */
internal data class EventPageContext(
    val mode: EventPageMode,
    /** [PublicLanguage.DEFAULT] in [EventPageMode.EMBED] (no chrome, so nothing to translate). The page body is German in every case. */
    val lang: PublicLanguage,
    val baseUrl: String,
    val branding: ResolvedBranding,
    val nav: PublicNavAvailability,
    /** Origins that may frame the EMBED variant -- empty unless the embed feature is enabled. Already filtered by [sanitizeFrameAncestors]. */
    val frameAncestors: List<String> = emptyList(),
) {
    val brandTitle: String get() = branding.title

    val isEmbed: Boolean get() = mode == EventPageMode.EMBED

    /**
     * The ONE place that builds family-internal URLs. [path] is site-relative, starts with `/veranstaltung/` and may already carry
     * a query string. Appends `embed=1` in [EventPageMode.EMBED] and `lang=xx` in [EventPageMode.STANDARD] when the language is not the
     * default, so neither the mode nor the language is lost on the way through the registration flow.
     */
    fun href(path: String): String {
        val extra =
            when {
                isEmbed -> "embed=1"
                lang != PublicLanguage.DEFAULT -> "lang=${lang.code}"
                else -> return path
            }
        return path + (if (path.contains('?')) "&" else "?") + extra
    }
}

/** Builds the [EventPageContext] of this request. Never throws. */
internal fun ApplicationCall.eventPageContext(
    baseUrl: String,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
    frameAncestors: List<String>,
): EventPageContext {
    val mode = EventPageMode.of(this)
    return EventPageContext(
        mode = mode,
        lang = if (mode == EventPageMode.EMBED) PublicLanguage.DEFAULT else resolvePublicLanguage(),
        baseUrl = baseUrl,
        branding = branding,
        nav = navAvailability.current(),
        frameAncestors = frameAncestors,
    )
}

private val FRAME_ANCESTOR_ORIGIN = Regex("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$")

/**
 * Defence in depth for the `frame-ancestors` header value: the origins are already validated by `EmbedOriginAllowlist`, but anything
 * that is not a plain `scheme://host[:port]` (a `;`, a space, a comma, a path ...) is dropped here so it can never reach a header.
 */
internal fun sanitizeFrameAncestors(origins: List<String>): List<String> =
    origins.filter { origin ->
        FRAME_ANCESTOR_ORIGIN.matches(origin).also { ok ->
            if (!ok) logger.warn { "Dropped an invalid embed origin from the event page frame-ancestors directive." }
        }
    }

/** The sanitized origins that may frame an EMBED-mode page; empty while the embed feature is off. Shared by the event and article pages (V1.9.130). */
internal fun embedFrameAncestors(embedConfig: EmbedConfig): List<String> =
    if (embedConfig.enabled) sanitizeFrameAncestors(embedConfig.allowlist.canonicalOrigins) else emptyList()

/**
 * The security headers of every HTML response of a page family with a STANDARD and an EMBED presentation (`/veranstaltung/...` since
 * V1.9.128, `/aktuelles/{slug}` since V1.9.130).
 *
 * - **`img-src`**: STANDARD is `'self' data:` -- `'self'` for cover images, QR codes and the operator logo, `data:` because the
 *   chrome's navigation icons are CSS `mask-image: url("data:...")` (without it they would be silently invisible, same trap
 *   `SocialPublicRoutes.applyPublicPageHeaders` documents). EMBED has no chrome and needs only `'self'`.
 * - **`frame-ancestors`**: `'none'` (plus `X-Frame-Options: DENY`) for every response EXCEPT an EMBED-mode response while the embed
 *   feature is enabled and an allowlist is configured: then `'self'` plus the allowlisted origins, and no `X-Frame-Options` (it cannot
 *   express an allowlist, and browsers that understand `frame-ancestors` ignore it anyway). The STANDARD mode is never frameable, whatever
 *   the allowlist says -- `?embed=1` is the only switch, and it only has an effect where the operator opted in.
 * - No `<script>` on this surface, so `default-src 'none'` and `style-src 'self'` stay maximally strict (no inline styles anywhere).
 *   The one exception (V1.9.217): a detail page that renders the share bar passes [scriptSrcSelf] and gets `script-src 'self'` (only
 *   the same-origin share script; still no inline script, no hashes, no nonces).
 */
internal fun ApplicationCall.applyPageModeHeaders(
    isEmbed: Boolean,
    frameAncestors: List<String>,
    scriptSrcSelf: Boolean = false,
) {
    val imgSrc = if (isEmbed) "'self'" else "'self' data:"
    val frameable = isEmbed && frameAncestors.isNotEmpty()
    val ancestors = if (frameable) "'self' " + frameAncestors.joinToString(" ") else "'none'"
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; img-src $imgSrc; style-src 'self'; font-src 'self'; ${if (scriptSrcSelf) "script-src 'self'; " else ""}" +
            "form-action 'self'; base-uri 'none'; frame-ancestors $ancestors",
    )
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    if (!frameable) response.header("X-Frame-Options", "DENY")
    response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
}
