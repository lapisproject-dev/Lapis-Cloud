package network.lapis.cloud.server.clientversion

import network.lapis.cloud.server.branding.BrandingHtml
import network.lapis.cloud.server.branding.ResolvedBranding
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
) {
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
        ): ClientShell {
            val buildId = ClientBuildId.compute(clientDistRoot = clientDistRoot)
            val indexFile = File(clientDistRoot, "index.html")
            val html =
                if (!indexFile.exists()) {
                    null
                } else {
                    ClientVersionHtml.inject(
                        html =
                            BrandingHtml.inject(
                                html = indexFile.readText(),
                                brand = branding,
                                keycloakMode = keycloakEnabled,
                                emergencyAdminLoginEnabled = emergencyAdminLoginEnabled,
                            ),
                        buildId = buildId,
                    )
                }
            return ClientShell(indexHtml = html, buildId = buildId)
        }
    }
}
