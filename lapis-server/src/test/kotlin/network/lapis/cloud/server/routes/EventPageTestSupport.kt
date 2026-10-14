package network.lapis.cloud.server.routes

import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist

/** Shared fixtures for the tests of the public `/veranstaltung/...` page family (V1.9.128: branding, nav availability, embed config). */
internal fun eventTestBranding(logo: Boolean = false): ResolvedBranding =
    ResolvedBranding(title = "Testverein", logoAvailable = logo, logoPath = null)

internal fun eventTestNav(): PublicNavAvailabilityProvider = PublicNavAvailabilityProvider(loader = { PublicNavAvailability.NONE })

/** Embed feature switched off -- the production default. */
internal fun eventTestEmbedConfig(): EmbedConfig = EmbedConfig.DISABLED

/** Embed feature switched on for [origins] (all https). */
internal fun eventTestEmbedConfigEnabled(vararg origins: String): EmbedConfig =
    EmbedConfig(
        enabled = true,
        allowlist = EmbedOriginAllowlist.parse(raw = origins.joinToString(","), allowInsecure = false).allowlist,
        allowInsecureOrigins = false,
    )

/** A ready-made [EventPageContext] for render-level tests. */
internal fun eventTestContext(
    embed: Boolean = false,
    lang: PublicLanguage = PublicLanguage.DEFAULT,
    baseUrl: String = "https://example.org",
    frameAncestors: List<String> = emptyList(),
): EventPageContext =
    EventPageContext(
        mode = if (embed) EventPageMode.EMBED else EventPageMode.STANDARD,
        lang = if (embed) PublicLanguage.DEFAULT else lang,
        baseUrl = baseUrl,
        branding = eventTestBranding(),
        nav = PublicNavAvailability.NONE,
        frameAncestors = frameAncestors,
    )
