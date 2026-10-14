package network.lapis.cloud.server.routes

/** A ready-made [ArticlePageContext] for render-level tests (reuses the event test branding/nav fixtures). */
internal fun articleTestContext(
    embed: Boolean = false,
    lang: PublicLanguage = PublicLanguage.DEFAULT,
    baseUrl: String = "https://example.org",
    frameAncestors: List<String> = emptyList(),
): ArticlePageContext =
    ArticlePageContext(
        mode = if (embed) EventPageMode.EMBED else EventPageMode.STANDARD,
        lang = if (embed) PublicLanguage.DEFAULT else lang,
        baseUrl = baseUrl,
        branding = eventTestBranding(),
        nav = PublicNavAvailability.NONE,
        frameAncestors = frameAncestors,
    )
