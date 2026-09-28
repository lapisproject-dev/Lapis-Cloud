package network.lapis.cloud.server.membermap

import java.io.File

/**
 * Welle V1.9.5 "Vorstands-Karte" -- operator-supplied path to a self-hosted PMTiles basemap file.
 * Pure string validation ONLY, no I/O of any kind -- same posture as [network.lapis.cloud.server
 * .branding.BrandConfig] (see that class' own KDoc): existence/readability/header-validity of
 * [pmtilesPath] is checked separately, per-request, by [PmtilesBasemap.probe] (never cached, so an
 * operator swapping the file takes effect without a restart -- see that class' KDoc).
 *
 * **Never fail-fast.** An unset or invalid `LAPIS_MAP_PMTILES_PATH` degrades to "map feature
 * unavailable" (`tilesAvailable = false` in [network.lapis.cloud.shared.domain.BoardMemberMapResponse]),
 * it never refuses to start this server -- this is an optional operator add-on (a 187 MB file the
 * operator must separately provision, see `deploy/example/README.adoc` §"Member map (optional)"),
 * exactly the same "cosmetic/optional feature never blocks startup" argument [BrandConfig] and
 * [network.lapis.cloud.server.mail.SmtpConfig]'s "never fail-fast" siblings already establish.
 *
 * **`LAPIS_MAP_PMTILES_PATH` is an absolute, deployment-local filesystem path, never a URL** -- same
 * SSRF-avoidance reasoning as [BrandConfig.logoPath]'s own KDoc: no outbound fetch, ever. The
 * operator bind-mounts the file (see `deploy/example/docker-compose.yml`) and points this variable
 * at it.
 */
data class MemberMapConfig(
    /** Absolute path, or `null` if `LAPIS_MAP_PMTILES_PATH` was unset/blank/invalid. */
    val pmtilesPath: String?,
    /** Names of `LAPIS_MAP_PMTILES_PATH`-derived values rejected -- for [MemberMapStartupCheck] logging only, never a reason to throw. */
    val invalid: List<String>,
) {
    companion object {
        const val ENV_PMTILES_PATH = "LAPIS_MAP_PMTILES_PATH"
        private const val REQUIRED_EXTENSION = "pmtiles"

        /** Pure string validation ONLY -- no file I/O, never throws. See class KDoc. */
        fun load(env: (String) -> String? = System::getenv): MemberMapConfig {
            val invalid = mutableListOf<String>()
            val raw = env(ENV_PMTILES_PATH)?.trim()?.takeUnless { it.isBlank() }
            val path =
                when {
                    raw == null -> null
                    !isValidPmtilesPath(raw) -> {
                        invalid += ENV_PMTILES_PATH
                        null
                    }
                    else -> raw
                }
            return MemberMapConfig(pmtilesPath = path, invalid = invalid)
        }

        fun notConfigured(): MemberMapConfig = MemberMapConfig(pmtilesPath = null, invalid = emptyList())

        /**
         * Absolute path, no embedded NUL byte or C0 control character (log-injection guard, same
         * reasoning as [network.lapis.cloud.server.branding.BrandConfig]'s own string checks), and
         * carries the `.pmtiles` extension (case-insensitive) -- an operator-only environment
         * variable, never user/request input, so this is defense-in-depth, not a defense against an
         * untrusted caller.
         */
        private fun isValidPmtilesPath(path: String): Boolean {
            if (path.contains('\u0000')) return false
            if (path.any { it.code < 0x20 }) return false
            if (!File(path).isAbsolute) return false
            val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return extension == REQUIRED_EXTENSION
        }
    }
}
