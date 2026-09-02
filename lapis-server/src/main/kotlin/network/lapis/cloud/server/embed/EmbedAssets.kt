package network.lapis.cloud.server.embed

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- the two static `.js` files this welle ships
 * (`/embed/lapis-widgets.js`, `/embed/login-popup.js` under `src/main/resources/embed/`), loaded
 * once at classloading time via [EmbedAssets]. Real resource files, not Kotlin raw strings: ~250
 * lines of security-critical JavaScript containing `$`-adjacent syntax (template-literal-shaped
 * code, `${...}`-looking patterns) would need constant `${'$'}` escaping as a Kotlin `const val`,
 * losing editor support for no benefit -- `getResourceAsStream` is a new but unremarkable pattern
 * for this server (verified: no prior call site).
 *
 * **Loaded eagerly, not lazily**, so a packaging mistake (a resource missing from the built JAR)
 * fails at STARTUP, not on the first partner request -- `Application.module()` references
 * [EmbedAssets] unconditionally, even when `LAPIS_EMBED_ENABLED` is `false` (see `Application.kt`).
 *
 * **Line endings are normalized (`\r\n` -> `\n`)** -- otherwise both the ETag and the 8 KB size
 * budget test ([widgetJsTemplate]'s consumer, `EmbedAssetTest`) would depend on the git checkout's
 * line-ending mode.
 */
internal object EmbedAssets {
    val widgetJsTemplate: String = loadResource("/embed/lapis-widgets.js")
    val loginPopupJs: String = loadResource("/embed/login-popup.js")

    /**
     * The final `lapis-widgets.js` body served to a partner site: a single prelude line declaring
     * `window.__lapisEmbedAllowedOriginsV1` (the canonical allowlist, JSON-serialized via
     * [kotlinx.serialization.json.Json] -- never string concatenation, so no origin value can ever
     * break out of the array literal), followed by [widgetJsTemplate] unchanged. Computed ONCE when
     * [network.lapis.cloud.server.routes.registerEmbedRoutes] registers its routes, never per
     * request -- see that function's own KDoc.
     *
     * **The allowlist is intentionally public in this bundle** -- see [EmbedConfig] KDoc "The
     * allowlist ends up in the publicly downloadable widget bundle" for the full reasoning.
     */
    fun widgetJs(allowlist: EmbedOriginAllowlist): String {
        val originsJson = Json.encodeToString(ListSerializer(String.serializer()), allowlist.canonicalOrigins)
        return "window.__lapisEmbedAllowedOriginsV1=$originsJson;\n$widgetJsTemplate"
    }

    private fun loadResource(path: String): String {
        val stream = EmbedAssets::class.java.getResourceAsStream(path)
        checkNotNull(stream) { "EmbedAssets: resource $path is missing from the classpath -- packaging error." }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")
    }
}
