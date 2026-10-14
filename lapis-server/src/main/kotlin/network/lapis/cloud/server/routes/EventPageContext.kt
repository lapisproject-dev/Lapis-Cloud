package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.branding.ResolvedBranding

private val logger = KotlinLogging.logger {}

/**
 * V1.9.128 -- the two presentations of the public `/veranstaltung/...` page family.
 *
 * - [STANDARD]: the full Lapis header (brand, icon navigation, language switcher), wide layout, footer.
 * - [EMBED]: the slim, header-less page for embedding in an `<iframe>` on a partner website
 *   (`?embed=1`). Framing is only ever allowed for this mode, and only for the operator-configured origin allowlist
 *   (see [applyEventPageHeaders]).
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
