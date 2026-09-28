package network.lapis.cloud.server.membermap

import network.lapis.cloud.shared.domain.BoardMemberMapResponse
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import network.lapis.cloud.shared.domain.MemberMapRules

/**
 * One already-counted (postal code, country) group -- the ONLY shape [aggregateMemberMap] ever
 * sees. [BoardMemberMapService] builds this from a query that selects nothing but `postal_code`,
 * `country` and a row count, grouped by the first two -- no member id, name, street or city is ever
 * loaded into a Kotlin object anywhere in this feature's server-side code (see that service's own
 * KDoc). `count` is the number of ELIGIBLE, non-anonymized members sharing this exact
 * (postalCode, country) pair.
 */
internal data class AddressGroup(
    val postalCode: String?,
    val country: String?,
    val count: Int,
)

/**
 * Welle V1.9.5 "Vorstands-Karte" -- the pure classification/aggregation core, deliberately free of
 * any database or RPC dependency (see `MemberMapAggregationTest`, which exercises exactly this
 * function against hand-built [AddressGroup] lists plus a 200-random-group invariant check).
 *
 * **Partitioning rule, in this exact order** (see `docs/architecture/member-map.adoc` §Q3 for the
 * full reasoning) -- every eligible member's group falls into EXACTLY ONE bucket:
 * 1. [MemberMapRules.isGermanCountry] is `false` -> [BoardMemberMapResponse.foreign]. Checked FIRST,
 *    before postal-code shape, so a foreign postal code that happens to look like a valid German one
 *    (e.g. France's `75001`) never lands in [BoardMemberMapResponse.mappedTotal] or
 *    [BoardMemberMapResponse.unresolvableGermanPostalCode].
 * 2. [MemberMapRules.isGermanPostalCode] is `false` (missing, blank, wrong shape) ->
 *    [BoardMemberMapResponse.noPostalCode].
 * 3. [centroids] resolves the (trimmed) postal code -> a full [MemberMapEntryDto] with place name
 *    and coordinates, counted in [BoardMemberMapResponse.mappedTotal]. Same postal code appearing in
 *    more than one input group (e.g. two different raw `country` spellings that both normalize to
 *    German) is summed into ONE entry, not two.
 * 4. Otherwise (valid-shaped German postal code, but [centroids] is `null` or has no entry for it) ->
 *    an entry with `placeName`/`lat`/`lon` all `null`, counted in
 *    [BoardMemberMapResponse.unresolvableGermanPostalCode].
 *
 * `member.city` is NEVER read here (this function does not even see it -- [AddressGroup] has no
 * such field) -- the only place name ever shown comes from GeoNames via [centroids], see
 * `docs/architecture/member-map.adoc` for why.
 *
 * Invariant, checked before returning: `total == mappedTotal + unresolvableGermanPostalCode +
 * noPostalCode + foreign`.
 */
internal fun aggregateMemberMap(
    groups: List<AddressGroup>,
    centroids: PostalCodeCentroidIndex?,
    tilesAvailable: Boolean,
): BoardMemberMapResponse {
    var total = 0
    var noPostalCode = 0
    var foreign = 0
    // postalCode -> (placeName, lat, lon, count) accumulated across possibly-multiple input groups
    // sharing the same (trimmed) postal code.
    val mapped = LinkedHashMap<String, MemberMapEntryDto>()
    val unresolvable = LinkedHashMap<String, MemberMapEntryDto>()

    for (group in groups) {
        if (group.count <= 0) continue
        total += group.count

        if (!MemberMapRules.isGermanCountry(group.country)) {
            foreign += group.count
            continue
        }
        if (!MemberMapRules.isGermanPostalCode(group.postalCode)) {
            noPostalCode += group.count
            continue
        }
        val postalCode = group.postalCode!!.trim()
        val centroid = centroids?.lookup(postalCode)
        if (centroid != null) {
            val existing = mapped[postalCode]
            mapped[postalCode] =
                MemberMapEntryDto(
                    postalCode = postalCode,
                    placeName = centroid.placeName,
                    lat = centroid.lat,
                    lon = centroid.lon,
                    count = (existing?.count ?: 0) + group.count,
                )
        } else {
            val existing = unresolvable[postalCode]
            unresolvable[postalCode] =
                MemberMapEntryDto(
                    postalCode = postalCode,
                    placeName = null,
                    lat = null,
                    lon = null,
                    count = (existing?.count ?: 0) + group.count,
                )
        }
    }

    val mappedTotal = mapped.values.sumOf { it.count }
    val unresolvableTotal = unresolvable.values.sumOf { it.count }

    val entries =
        (mapped.values + unresolvable.values)
            .sortedWith(compareByDescending<MemberMapEntryDto> { it.count }.thenBy { it.postalCode })

    check(total == mappedTotal + unresolvableTotal + noPostalCode + foreign) {
        "aggregateMemberMap: invariant violated -- total=$total, mapped=$mappedTotal, " +
            "unresolvable=$unresolvableTotal, noPostalCode=$noPostalCode, foreign=$foreign"
    }

    return BoardMemberMapResponse(
        entries = entries,
        total = total,
        mappedTotal = mappedTotal,
        unresolvableGermanPostalCode = unresolvableTotal,
        noPostalCode = noPostalCode,
        foreign = foreign,
        tilesAvailable = tilesAvailable,
        geodataAvailable = centroids != null,
    )
}
