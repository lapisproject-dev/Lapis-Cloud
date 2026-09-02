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
 */
fun buildEmbedSnippet(publicBaseUrl: String): String {
    val base = publicBaseUrl.trimEnd('/')
    return """
        |<script src="$base/embed/v1/lapis-widgets.js" async></script>
        |
        |<div data-lapis-widget="login">
        |  <a href="$base/#/login">Anmelden</a>
        |</div>
        |
        |<div data-lapis-widget="join">
        |  <a href="$base/#/register">Mitglied werden</a>
        |</div>
        """.trimMargin()
}
