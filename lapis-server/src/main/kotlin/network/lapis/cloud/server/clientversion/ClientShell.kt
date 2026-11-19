package network.lapis.cloud.server.clientversion

import network.lapis.cloud.server.branding.BrandingHtml
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.theme.ResolvedTheme
import network.lapis.cloud.server.theme.ThemeHtml
import java.io.File

/**
 * Welle V1.4.20 -- the SPA shell this process serves, resolved ONCE (from `Application.kt`'s
 * `by lazy`): the fully injected `index.html` AND the build id that was stamped into it. Both come
 * from one value on purpose -- were the `<meta>` content and the `GET /api/client-version` answer
 * computed separately they could drift apart and produce a permanent false "new version" banner.
 *
 * Retains the V1.2.5 property that neither the shell nor the (multi-MB) bundle hash is recomputed
 * per request.
 */
internal data class ClientShell(
    /** Branding- AND version-injected `index.html`, or `null` when no client build is present. */
    val indexHtml: String?,
    /** The build id stamped into [indexHtml] (also served by the version route), or `null` without a bundle. */
    val buildId: String?,
    /**
     * V1.9.315 -- the same shell with the payload field `membershipApplicationEnabled:false`. The shell is built once per process but the
     * switch changes at runtime, so both variants are rendered up front (cheap strings; the build-id hash runs once) and the request picks
     * one. `null` (hand-built shells in tests) falls back to [indexHtml].
     */
    val indexHtmlMembershipClosed: String? = null,
) {
    /** The shell matching the current state of the switch "Mitglied werden". */
    fun indexHtmlFor(membershipApplicationEnabled: Boolean): String? =
        if (membershipApplicationEnabled) indexHtml else indexHtmlMembershipClosed ?: indexHtml

    companion object {
        /**
         * Reads `index.html` under [clientDistRoot], injects branding first and the build id second.
         * [buildId] is computed even without an `index.html` when a bundle exists (the version route
         * then answers sensibly although `/app` 404s -- not a real deployment shape, but defined).
         */
        fun load(
            clientDistRoot: File,
            branding: ResolvedBranding,
            /**
             * V1.7.2 sub-wave 2a -- forwarded to [BrandingHtml.inject]'s `keycloakMode` payload
             * field, `false` by default so every pre-existing caller/test stays source-compatible.
             */
            keycloakEnabled: Boolean = false,
            /**
             * Review fix (MINOR 3) -- forwarded to [BrandingHtml.inject]'s
             * `emergencyAdminLoginEnabled` payload field, `false` by default so every pre-existing
             * caller/test stays source-compatible.
             */
            emergencyAdminLoginEnabled: Boolean = false,
            /** V1.9.216 -- the theme whose content hash is stamped into the shell's stylesheet link (default: the compiled-in theme). */
            theme: ResolvedTheme = ResolvedTheme.DEFAULT,
        ): ClientShell {
            val buildId = ClientBuildId.compute(clientDistRoot = clientDistRoot)
            val indexFile = File(clientDistRoot, "index.html")
            val raw = if (indexFile.exists()) indexFile.readText() else null

            fun render(membershipApplicationEnabled: Boolean): String? =
                raw?.let {
                    ClientVersionHtml.inject(
                        html =
                            ThemeHtml.inject(
                                html =
                                    BrandingHtml.inject(
                                        html = it,
                                        brand = branding,
                                        keycloakMode = keycloakEnabled,
                                        emergencyAdminLoginEnabled = emergencyAdminLoginEnabled,
                                        membershipApplicationEnabled = membershipApplicationEnabled,
                                    ),
                                theme = theme,
                            ),
                        buildId = buildId,
                    )
                }
            return ClientShell(
                indexHtml = render(membershipApplicationEnabled = true),
                buildId = buildId,
                indexHtmlMembershipClosed = render(membershipApplicationEnabled = false),
            )
        }
    }
}
