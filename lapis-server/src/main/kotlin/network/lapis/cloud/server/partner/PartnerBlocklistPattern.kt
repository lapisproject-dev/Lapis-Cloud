package network.lapis.cloud.server.partner

/**
 * V1.9.244 -- the two shapes of a block pattern: an exact host (`beispiel.de`) or all sub-domains of one (`*.beispiel.de`). `*.beispiel.de` does
 * NOT match `beispiel.de` itself and not `xbeispiel.de` (a real label boundary is required); a `*` anywhere else is invalid. Patterns are stored
 * lower-case ASCII (the host part goes through [PartnerHost]).
 */
internal object PartnerBlocklistPattern {
    private const val WILDCARD_PREFIX = "*."

    /** The canonical pattern for [raw], or `null` when it is not `host` / `*.host`. */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        val wildcard = trimmed.startsWith(WILDCARD_PREFIX)
        val hostPart = if (wildcard) trimmed.removePrefix(WILDCARD_PREFIX) else trimmed
        if ('*' in hostPart) return null
        val host = PartnerHost.normalizeHostOnly(hostPart) ?: return null
        return if (wildcard) "$WILDCARD_PREFIX$host" else host
    }

    fun matches(
        pattern: String,
        host: String,
    ): Boolean =
        if (pattern.startsWith(WILDCARD_PREFIX)) {
            host.endsWith("." + pattern.removePrefix(WILDCARD_PREFIX))
        } else {
            host == pattern
        }

    fun isBlocked(
        host: String,
        patterns: Collection<String>,
    ): Boolean = patterns.any { matches(pattern = it, host = host) }
}
