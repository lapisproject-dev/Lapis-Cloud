package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- mirrors `network.lapis.cloud.server.routes
 * .EmbedRoutes.kt`'s `GET /api/embed/v1/admin/status` 1:1, same `BackupHttp.kt`-precedent: a raw
 * HTTP route with a client-local `@Serializable` mirror, deliberately NOT a Kilua RPC service --
 * this stays a pure display fetch, no `lapis-shared` DTO, no `IOrganizationSettingsService`
 * signature change, no KSP regeneration.
 */
private const val STATUS_URL = "/api/embed/v1/admin/status"

@Serializable
data class EmbedAdminStatus(
    val enabled: Boolean,
    val allowedOrigins: List<String>,
    val publicBaseUrl: String,
    val allowInsecureOrigins: Boolean,
    /** Welle V1.4.1b. */
    val donationWidgetAvailable: Boolean,
    val donationWidgetUnavailableReason: String?,
)

object EmbedIntegrationHttp {
    private val json = Json { ignoreUnknownKeys = true }

    /** `null` on any non-200 response (including 401/403 -- the caller has already passed the ADMIN route gate to even render this screen; a failure here is treated as "nothing to show"). */
    suspend fun fetchStatus(): EmbedAdminStatus? {
        val response =
            window
                .fetch(STATUS_URL, RequestInit(method = "GET", credentials = RequestCredentials.INCLUDE))
                .await()
        if (!response.ok) return null
        val text = response.text().await()
        return runCatching { json.decodeFromString(EmbedAdminStatus.serializer(), text) }.getOrNull()
    }
}

/**
 * Pure, DOM-free snippet generator -- deterministic given [publicBaseUrl], directly unit-testable
 * (`EmbedIntegrationScreenTest.kt`) without a browser. Both `<div data-lapis-widget="...">` blocks
 * carry a No-JS fallback `<a>` -- the exact host of the vertragspflicht in `docs/api/embed-widgets
 * .adoc`: a visitor whose browser never runs `lapis-widgets.js` (JS disabled, script blocked, this
 * origin not yet allowlisted) still sees a WORKING link, not an empty `<div>`. The widget script
 * REPLACES this fallback content on successful hydration (see `lapis-widgets.js`'s own `mount()`);
 * it never touches it if hydration does not happen at all.
 *
 * Welle V1.4.6: the three fallback anchors point at `$base/app#/...`, not `$base/#/...` -- the member
 * SPA moved off `/` to `/app` (see `network.lapis.cloud.server.routes.PublicLandingRoutes` KDoc).
 * Operators who already copied this snippet onto their own site BEFORE that deploy keep the old
 * `$base/#/...` links -- those are not remotely correctable (this codebase does not control
 * third-party sites), and are documented as a known, accepted residual in the wave's release notes;
 * see the optional hash-bridge asset (`PublicLandingHtml`/`/s/assets/hash-bridge.js`) for the mitigation
 * that DOES cover them.
 *
 * Welle V1.4.3.3: a fourth block, `data-lapis-widget="event"`, carries the operator's own
 * `data-lapis-event-slug` placeholder (`"ihre-veranstaltung"`) instead of a `$base/app#/...` link
 * -- the fallback anchor points at the server-rendered `/veranstaltung/{slug}` page, the SAME
 * unauthenticated public route the widget itself falls back to on failure. No
 * `data-lapis-fallback-url` on this block (unlike `donate`) -- the event widget derives its own
 * fallback from the slug, see `lapis-widgets.js`'s own `hydrateEvent()`. Leaving the placeholder
 * slug unreplaced is not silently wrong: the widget's `hydrateEvent()` logs a `console.error` and
 * never mounts, leaving this very fallback link visible instead.
 */
fun buildEmbedSnippet(publicBaseUrl: String): String {
    val base = publicBaseUrl.trimEnd('/')
    return """
        |<script src="$base/embed/v1/lapis-widgets.js" async></script>
        |
        |<div data-lapis-widget="login">
        |  <a href="$base/app#/login">Anmelden</a>
        |</div>
        |
        |<div data-lapis-widget="join">
        |  <a href="$base/app#/register">Mitglied werden</a>
        |</div>
        |
        |<div data-lapis-widget="donate" data-lapis-fallback-url="">
        |  <a href="$base/app#/donate">Spenden</a>
        |</div>
        |
        |<div data-lapis-widget="event" data-lapis-event-slug="ihre-veranstaltung">
        |  <a href="$base/veranstaltung/ihre-veranstaltung">Zur Anmeldung</a>
        |</div>
        """.trimMargin()
}
