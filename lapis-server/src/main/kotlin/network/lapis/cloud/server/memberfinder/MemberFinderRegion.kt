package network.lapis.cloud.server.memberfinder

import network.lapis.cloud.shared.domain.MemberMapRules

/**
 * V1.9.144 -- the region of a member: the first two digits of a German postal code. **Never stored on a person row**: it is derived here on every
 * query from `member.postal_code` plus the country, and the only place a region key is ever written is the aggregate `member_finder_region_band`
 * (no member column). `member.city` is never used.
 */
internal object MemberFinderRegion {
    private const val PREFIX = "DE-"

    /**
     * `"DE-38"` for a German address, else `null` ("not available"). The country decides first: a set [countryCode] (V1.9.121, authoritative)
     * must be `DE`; only without it the legacy free text [legacyCountry] counts -- and it must NOT be blank (the member map treats blank as
     * Germany, the finder does not: the finder is stricter on purpose).
     */
    fun keyOf(
        postalCode: String?,
        countryCode: String?,
        legacyCountry: String?,
    ): String? {
        val german =
            if (countryCode != null) {
                countryCode.trim().equals("DE", ignoreCase = true)
            } else {
                !legacyCountry.isNullOrBlank() && MemberMapRules.isGermanCountry(legacyCountry)
            }
        if (!german || !MemberMapRules.isGermanPostalCode(postalCode)) return null
        return PREFIX + postalCode!!.trim().take(2)
    }

    /** `"38"` for `"DE-38"`. */
    fun labelOf(key: String): String = key.removePrefix(PREFIX)
}

/** The one text of every refusal that must not tell a case from another (unknown, foreign, decided, expired). */
internal object MemberFinderMessages {
    const val NOT_FOUND: String = "Not found"
}
