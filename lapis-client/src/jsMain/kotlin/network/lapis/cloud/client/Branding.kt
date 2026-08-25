package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The literal shape `network.lapis.cloud.server.branding.BrandingHtml.renderPayloadJson` writes
 * into `#lapis-brand`'s `<script>` body -- see that function's own KDoc. `title` is nullable here
 * purely so a malformed/partial payload (missing `title` key) degrades to [Branding.DEFAULT_TITLE]
 * below rather than failing [Json.decodeFromString] outright.
 */
@Serializable
private data class BrandPayload(
    val title: String? = null,
    val logoUrl: String? = null,
)

private val brandJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * V1.2.5 White-Label-Branding -- reads the operator-supplied title/logo the server injected into
 * `#lapis-brand` (see `network.lapis.cloud.server.branding.BrandingHtml` KDoc), falling back to
 * the hardcoded Lapis Cloud defaults whenever the element is missing or its content is malformed.
 * Never throws.
 *
 * **[title]/[logoUrl] are computed on EVERY read, not cached in a `by lazy`.** A cheap single DOM
 * lookup + JSON parse per access is negligible at this call frequency (a handful of reads per
 * navbar (re)build, see `App.kt`'s `refreshNavbar`) and keeps this object trivially testable
 * against a DOM this test file freely mutates between cases -- see [BrandingTest]. Production
 * behavior is unaffected either way: `#lapis-brand`'s content is server-rendered once per page load
 * and never changes for the lifetime of a single page.
 *
 * **[PLATFORM_NAME]/[PLATFORM_URL] NEVER come from [BrandPayload]** -- they identify the Lapis
 * Cloud platform itself (see [LapisAttribution]), not the white-labeled operator, and must stay
 * fixed regardless of what an operator sets `LAPIS_BRAND_TITLE`/`LAPIS_BRAND_LOGO_PATH` to.
 */
object Branding {
    const val DEFAULT_TITLE: String = "Lapis Cloud"
    const val PLATFORM_NAME: String = "Lapis Cloud"
    const val PLATFORM_URL: String = "https://cloud.lapisproject.dev"

    private const val ELEMENT_ID = "lapis-brand"

    val title: String
        get() = readPayload()?.title?.takeUnless { it.isBlank() } ?: DEFAULT_TITLE

    val logoUrl: String?
        get() = readPayload()?.logoUrl?.takeUnless { it.isBlank() }

    private fun readPayload(): BrandPayload? {
        val raw = document.getElementById(ELEMENT_ID)?.textContent?.takeUnless { it.isBlank() } ?: return null
        return runCatching { brandJson.decodeFromString(BrandPayload.serializer(), raw) }.getOrNull()
    }
}
