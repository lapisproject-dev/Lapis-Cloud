package network.lapis.cloud.server.partner

/**
 * V1.9.245 -- the "via" chain of a foreign event or server: the hosts between this server and the origin, nearest first (the direct partner
 * is the first entry). Stored space-separated in a VARCHAR(800) (at most [MAX_VIA] hosts of at most 253 characters each fit by construction).
 * Reading re-validates every host: a stored chain is a cache of foreign data and is never trusted blindly.
 */
internal object PartnerProvenance {
    const val MAX_VIA = 3
    const val MAX_VIA_CHARS = 800

    /** `null` for an empty chain. */
    fun encode(via: List<String>): String? {
        require(via.size <= MAX_VIA) { "via chain too long: ${via.size}" }
        if (via.isEmpty()) return null
        val joined = via.joinToString(" ")
        require(joined.length <= MAX_VIA_CHARS) { "via chain too long in characters" }
        return joined
    }

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw.length > MAX_VIA_CHARS) return emptyList()
        return raw
            .split(' ')
            .asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { PartnerHost.normalizeHostOnly(it) }
            .take(MAX_VIA)
            .toList()
    }
}
