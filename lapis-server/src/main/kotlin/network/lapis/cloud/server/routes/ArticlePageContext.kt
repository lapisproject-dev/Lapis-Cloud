package network.lapis.cloud.server.routes

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * V1.9.130 -- everything a render function of the public article page (`/aktuelles/{slug}`) needs to know about the current request.
 * Built ONCE per request by [articlePageContext], never inside a transaction. Reuses [EventPageMode] (same `?embed=1` rule) instead of
 * duplicating it; deliberately NOT an [EventPageContext], whose URL builder is tied to `/veranstaltung/`.
 */
internal data class ArticlePageContext(
    val mode: EventPageMode,
    /** [PublicLanguage.DEFAULT] in [EventPageMode.EMBED] (no chrome, so nothing to translate). The article content is German in every case. */
    val lang: PublicLanguage,
    val baseUrl: String,
    val branding: ResolvedBranding,
    val nav: PublicNavAvailability,
    /** Origins that may frame the EMBED variant -- empty unless the embed feature is enabled. Already filtered by [sanitizeFrameAncestors]. */
    val frameAncestors: List<String> = emptyList(),
) {
    val isEmbed: Boolean get() = mode == EventPageMode.EMBED

    val brandTitle: String get() = branding.title
}

/** Builds the [ArticlePageContext] of this request. Never throws; call it BEFORE the rate limiter so 429/500 pages get the right shell. */
internal fun ApplicationCall.articlePageContext(
    baseUrl: String,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
    frameAncestors: List<String>,
): ArticlePageContext {
    val mode = EventPageMode.of(this)
    return ArticlePageContext(
        mode = mode,
        lang = if (mode == EventPageMode.EMBED) PublicLanguage.DEFAULT else resolvePublicLanguage(),
        baseUrl = baseUrl,
        branding = branding,
        nav = navAvailability.current(),
        frameAncestors = frameAncestors,
    )
}
