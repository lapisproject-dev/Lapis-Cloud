package network.lapis.cloud.server.embed

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.1a "Öffentliche Website-Integration -- Fundament + Login-Widget" -- opt-in
 * configuration for embedding the public "Login"/"Mitglied werden" widgets on a partner (e.g.
 * party/association) website. Same fail-fast posture as
 * [network.lapis.cloud.server.webhook.WebhookConfig]: [enabled] gates every embed route
 * ([network.lapis.cloud.server.routes.registerEmbedRoutes] registers only the ADMIN-gated
 * `GET /api/embed/v1/admin/status` route when `false`, see that function's own KDoc), and a
 * misconfigured allowlist refuses the server start rather than silently serving a widget nobody
 * can actually use.
 *
 * **Origin allowlist is env-only, never persisted to the database.** A compromised ADMIN account
 * must never be able to authorize a new embedding origin at runtime -- the admin screen
 * ([network.lapis.cloud.client.EmbedIntegrationScreen], V1.4.1a §7) is READ-ONLY, it only
 * displays what this configuration loaded at startup.
 *
 * **The allowlist ends up in the publicly downloadable widget bundle** (see
 * [network.lapis.cloud.server.embed.EmbedAssets.widgetJs] KDoc) -- every entry in
 * [ENV_ALLOWED_ORIGINS] is visible to anyone who loads the script. This is a deliberate, documented
 * trade-off (see `docs/api/embed-widgets.adoc`), not an oversight: the client-side origin check
 * this enables is pure developer-experience (an early, silent-refusal-to-render for a
 * misconfigured embed), never a security control -- [network.lapis.cloud.server.embed.EmbedCors]
 * and the popup login page re-validate every origin server-side regardless.
 *
 * **[allowInsecureOrigins] -- "O4" doctrine (mirrors [network.lapis.cloud.server.webhook.WebhookConfig]):**
 * has NO effect unless explicitly set to exactly `"true"`. Production `.env`/`docker-compose.yml`
 * deliberately OMIT [ENV_ALLOW_INSECURE] rather than setting it to `"false"` -- what is absent
 * cannot be flipped on by accident. A WARN is logged whenever the variable is set at all (any
 * value), so a forgotten development override is visible in the logs.
 */
data class EmbedConfig(
    val enabled: Boolean,
    val allowlist: EmbedOriginAllowlist,
    val allowInsecureOrigins: Boolean,
) {
    companion object {
        const val ENV_ENABLED = "LAPIS_EMBED_ENABLED"
        const val ENV_ALLOWED_ORIGINS = "LAPIS_EMBED_ALLOWED_ORIGINS"
        const val ENV_ALLOW_INSECURE = "LAPIS_EMBED_ALLOW_INSECURE"

        /** For tests, and for any caller that needs the feature turned off outright. */
        val DISABLED = EmbedConfig(enabled = false, allowlist = EmbedOriginAllowlist.EMPTY, allowInsecureOrigins = false)

        fun load(env: (String) -> String? = System::getenv): EmbedConfig {
            val enabled = env(ENV_ENABLED)?.trim().equals("true", ignoreCase = true)

            val allowInsecureRaw = env(ENV_ALLOW_INSECURE)?.trim()
            if (allowInsecureRaw != null) {
                logger.warn {
                    "$ENV_ALLOW_INSECURE is set (value='$allowInsecureRaw') -- embed origins using plain " +
                        "http:// will be accepted. This must never be set in production (see EmbedConfig " +
                        "KDoc \"O4\")."
                }
            }
            val allowInsecureOrigins = allowInsecureRaw.equals("true", ignoreCase = true)

            val rawOrigins = env(ENV_ALLOWED_ORIGINS)
            val parseResult = EmbedOriginAllowlist.parse(raw = rawOrigins, allowInsecure = allowInsecureOrigins)

            if (enabled) {
                check(parseResult.allowlist.isNotEmpty()) {
                    "$ENV_ENABLED=true but $ENV_ALLOWED_ORIGINS is unset or empty -- Welle V1.4.1a " +
                        "\"Öffentliche Website-Integration\" needs at least one allowed embedding origin. " +
                        "Example: $ENV_ALLOWED_ORIGINS=https://partei.example,https://www.partei.example. " +
                        "See EmbedConfig.load KDoc."
                }
                check(parseResult.rejected.isEmpty()) {
                    "$ENV_ENABLED=true but $ENV_ALLOWED_ORIGINS contains ${parseResult.rejected.size} " +
                        "invalid or excess entr${if (parseResult.rejected.size == 1) "y" else "ies"}: " +
                        parseResult.rejected.joinToString { "\"$it\"" } +
                        " -- each entry must be exactly scheme://host[:port] (https only unless " +
                        "$ENV_ALLOW_INSECURE=true), no path/query/fragment/userinfo, at most " +
                        "${EmbedOriginAllowlist.MAX_ORIGINS} origins total. See EmbedConfig.load KDoc."
                }
            } else {
                logger.info { "Embed widgets disabled ($ENV_ENABLED is not \"true\")." }
            }

            return EmbedConfig(
                enabled = enabled,
                allowlist = parseResult.allowlist,
                allowInsecureOrigins = allowInsecureOrigins,
            )
        }
    }
}
