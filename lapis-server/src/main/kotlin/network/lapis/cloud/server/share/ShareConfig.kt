package network.lapis.cloud.server.share

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Which share networks the public share bar offers (V1.9.217). Read ONCE at startup from `LAPIS_SHARE_NETWORKS`.
 *
 * - variable not set: every network. Keyword `all`: every network (Compose passes `${LAPIS_SHARE_NETWORKS-all}`).
 * - variable set but empty / blank (or keyword `none`): the bar is off.
 * - otherwise a comma list of [ShareNetwork.configName]s; unknown names are ignored with ONE warning.
 *
 * [baseUrlShareable] is false when the public base URL is neither https nor a loopback dev URL: sharing a plain-http link of a
 * production instance is never useful, so the bar stays off.
 */
internal data class ShareConfig(
    val networks: Set<ShareNetwork>,
    val baseUrlShareable: Boolean,
) {
    /** At least one entry visible without JavaScript (NATIVE alone is hidden without JS) and a shareable base URL. */
    val rendersBar: Boolean get() = baseUrlShareable && networks.any { it != ShareNetwork.NATIVE }

    /** COPY or NATIVE enabled -> share.js and `script-src 'self'`; otherwise neither. */
    val needsScript: Boolean get() = ShareNetwork.COPY in networks || ShareNetwork.NATIVE in networks

    val orderedNetworks: List<ShareNetwork> get() = ShareNetwork.entries.filter { it in networks }

    internal data class ParseResult(
        val networks: Set<ShareNetwork>,
        val unknown: List<String>,
    )

    companion object {
        const val ENV = "LAPIS_SHARE_NETWORKS"
        private const val LOG_TOKEN_MAX = 32
        private val SAFE_TOKEN = Regex("^[a-z0-9_-]+$")

        val DISABLED = ShareConfig(networks = emptySet(), baseUrlShareable = false)

        fun load(
            baseUrl: String,
            env: (String) -> String? = System::getenv,
        ): ShareConfig {
            val parsed = parse(env(ENV))
            if (parsed.unknown.isNotEmpty()) {
                val shown = parsed.unknown.joinToString(",") { sanitizeForLog(it) }
                logger.warn { "$ENV contains unknown network names which are ignored: $shown" }
            }
            if (parsed.networks == setOf(ShareNetwork.NATIVE)) {
                logger.warn { "$ENV enables only 'native': the share bar is disabled (it needs at least one visible entry)." }
            }
            val shareable = isShareableBaseUrl(baseUrl)
            if (!shareable && parsed.networks.isNotEmpty()) {
                logger.warn { "The public base URL is neither https nor a loopback address: the share bar is disabled." }
            }
            return ShareConfig(networks = parsed.networks, baseUrlShareable = shareable)
        }

        internal fun parse(raw: String?): ParseResult {
            if (raw == null) return ParseResult(networks = ShareNetwork.entries.toSet(), unknown = emptyList())
            val tokens = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return ParseResult(networks = emptySet(), unknown = emptyList())
            val networks = mutableSetOf<ShareNetwork>()
            val unknown = mutableListOf<String>()
            for (token in tokens) {
                when (token) {
                    "all" -> networks += ShareNetwork.entries
                    "none" -> Unit
                    else -> ShareNetwork.parse(token)?.let { networks += it } ?: unknown.add(token)
                }
            }
            return ParseResult(networks = networks, unknown = unknown)
        }

        internal fun sanitizeForLog(token: String): String {
            val cut = token.take(LOG_TOKEN_MAX)
            return if (SAFE_TOKEN.matches(cut)) cut else "<invalid>"
        }

        internal fun isShareableBaseUrl(baseUrl: String): Boolean {
            val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return false
            if (uri.userInfo != null) return false
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return false
            if (uri.rawQuery != null || uri.rawFragment != null) return false
            return when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> host == "localhost" || host == "127.0.0.1" || host == "[::1]"
                else -> false
            }
        }
    }
}
