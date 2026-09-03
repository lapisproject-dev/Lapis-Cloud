package network.lapis.cloud.server.embed

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

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
     *
     * [donationRange], when non-null, is `MIN_AMOUNT_EUR to effectiveMaxAmountEur(pspMax)` (see
     * [EmbedDonationLimits]) at the SAME registration-time snapshot as [allowlist] -- see
     * `registerEmbedRoutes` in `EmbedRoutes.kt` for the call site. Fix (Review MINOR, Welle
     * V1.4.1b, "the widget renders fixed 5-500 EUR presets/hint no matter what the operator
     * configured"): a second prelude line, `window.__lapisEmbedDonationRangeV1`, lets the widget's
     * own `hydrateDonate()` render the OPERATOR's actual range from the first paint -- filtering out
     * any preset amount above the effective maximum and showing the real bounds in the hint --
     * instead of only correcting itself after a guaranteed-to-fail first submit. `null` (PSP not
     * configured, or the operator's own maximum sits below the widget's floor -- see
     * [EmbedDonationLimits.rangeIsUsable]) omits the line entirely; the widget's hardcoded
     * 5-500-EUR defaults are then no worse than before this fix, and the donation form is unusable
     * either way (`registerEmbedDonationRoutes` itself 503s in that state).
     */
    fun widgetJs(
        allowlist: EmbedOriginAllowlist,
        donationRange: Pair<BigDecimal, BigDecimal>? = null,
    ): String {
        val originsJson = Json.encodeToString(ListSerializer(String.serializer()), allowlist.canonicalOrigins)
        val prelude = StringBuilder("window.__lapisEmbedAllowedOriginsV1=$originsJson;\n")
        if (donationRange != null) {
            val (min, max) = donationRange
            // stripTrailingZeros().toPlainString(), not toPlainString() alone -- min/max arrive as
            // BigDecimal("5.00")/BigDecimal("500.00") (see EmbedDonationLimits), and toPlainString()
            // preserves that scale verbatim ("5.00"/"500.00"). The widget's own hint text
            // (hydrateDonate() in lapis-widgets.js) concatenates these strings directly into what the
            // donor reads ("5–500 €"), so an un-stripped scale regresses that to "5.00–500.00 €" (Review
            // TRIVIAL, Round 3). toPlainString() never emits exponential notation regardless of scale
            // (verified: BigDecimal("500.00").stripTrailingZeros().toPlainString() == "500", not "5E+2"
            // -- that exponential form only appears from the bare, non-plain toString()), so no
            // additional scale guard is needed here.
            val rangeObj =
                JsonObject(
                    mapOf(
                        "min" to JsonPrimitive(min.stripTrailingZeros().toPlainString()),
                        "max" to JsonPrimitive(max.stripTrailingZeros().toPlainString()),
                    ),
                )
            val rangeJson = Json.encodeToString(JsonObject.serializer(), rangeObj)
            prelude.append("window.__lapisEmbedDonationRangeV1=$rangeJson;\n")
        }
        return "$prelude$widgetJsTemplate"
    }

    private fun loadResource(path: String): String {
        val stream = EmbedAssets::class.java.getResourceAsStream(path)
        checkNotNull(stream) { "EmbedAssets: resource $path is missing from the classpath -- packaging error." }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")
    }
}
