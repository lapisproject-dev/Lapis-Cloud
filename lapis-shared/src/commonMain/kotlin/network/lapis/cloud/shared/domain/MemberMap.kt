package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Welle V1.9.5 "Vorstands-Karte" (member map, second attempt) -- a BOARD/ADMIN-only aggregate view
 * of WHERE the organization's members live, at postal-code granularity, rendered on a self-hosted
 * (PMTiles/MapLibre) basemap. See `docs/architecture/member-map.adoc` for the full design and the
 * Q1-Q7 decisions this wave records.
 *
 * **Privacy is the entire point of this DTO's shape.** There is no member id, no display name, no
 * street, no city (`Member.city` is never read -- only GeoNames' own place name for a postal code
 * is shown, see [MemberMapEntryDto.placeName]), no date. A postal code with a single resident
 * member is still only ever shown as an aggregate count of 1 -- indistinguishable on the wire from
 * a postal code that happens to have exactly one member for an unrelated reason. See
 * `BoardMemberMapRpcWireTest`, which asserts this on the raw RPC payload, not just the UI.
 *
 * Invariant (checked by [aggregateMemberMap] in `lapis-server`, see that function's KDoc):
 * `total == mappedTotal + unresolvableGermanPostalCode + noPostalCode + foreign`.
 */
@Serializable
data class MemberMapEntryDto(
    /** Always exactly 5 digits -- only valid DE postal codes ever become an entry (see [MemberMapRules.isGermanPostalCode]). */
    val postalCode: String,
    /** GeoNames `place_name`, verbatim (including e.g. company-name artifacts such as "Commerzbank AG") -- `null` if this postal code could not be resolved against the bundled centroid index. */
    val placeName: String?,
    /** Both `null` (unresolvable postal code) or both set (resolved) -- never one without the other. */
    val lat: Double?,
    val lon: Double?,
    val count: Int,
)

/**
 * The board-facing aggregate response. Every count field is a SUM over eligible, non-anonymized
 * members ([MemberStatusSets.MEMBER_MAP_ELIGIBLE], `anonymized_at IS NULL`) -- see
 * `MemberMapAggregation.aggregateMemberMap` KDoc for the exact partitioning rule and its ordering
 * (country checked before postal code, so a foreign postal code that happens to look like a valid
 * German one -- e.g. France's `75001` -- never lands in [mappedTotal] or [unresolvableGermanPostalCode]).
 *
 * `entries` is sorted `count desc, postalCode asc` -- the board's actual reading order (largest
 * concentrations first), not raw postal-code order.
 */
@Serializable
data class BoardMemberMapResponse(
    val entries: List<MemberMapEntryDto>,
    val total: Int,
    val mappedTotal: Int,
    /** Valid-shaped DE postal code (`^[0-9]{5}$`), but absent from the bundled GeoNames centroid index -- see [PostalCodeCentroidIndex]. Also the whole bucket when [geodataAvailable] is `false`. */
    val unresolvableGermanPostalCode: Int,
    /** Missing or not a 5-digit string, for an otherwise-German member (see [MemberMapRules.isGermanPostalCode]). */
    val noPostalCode: Int,
    /** `!MemberMapRules.isGermanCountry(country)` -- checked BEFORE postal-code shape, see class KDoc. */
    val foreign: Int,
    /** Basemap PMTiles file is configured, present, and header-valid right now (see `PmtilesBasemap.probe()`) -- re-checked on every call, no caching, so an operator swapping the file takes effect without a restart. */
    val tilesAvailable: Boolean,
    /** Bundled postal-code centroid CSV parsed successfully at JVM startup (see `PostalCodeCentroidIndex.bundled`) -- independent of [tilesAvailable]; either can be true/false regardless of the other. The client-side degradation matrix that reads both flags (see `docs/architecture/member-map.adoc`) is not yet implemented on this branch -- this wave is server+shared only, see that document's own status note. */
    val geodataAvailable: Boolean,
)

/**
 * Pure, dependency-free rules shared between `lapis-server` (aggregation gate) and `lapis-client`
 * (map layer sizing, popup/marker radius) -- so client and server never independently reimplement
 * "what counts as a German postal code/country" and drift apart. See `MemberMapRulesTest`.
 */
object MemberMapRules {
    const val MIN_ZOOM = 4
    const val MAX_ZOOM = 10
    const val FLY_TO_ZOOM = 9

    /** `[west, south, east, north]` -- Germany's bounding box with a small margin, the map's initial view. */
    val START_BOUNDS = listOf(5.87, 47.27, 15.04, 55.06)

    /** `[west, south, east, north]` -- the hard viewport/zoom limit AND the basemap vector source's own `bounds` (Q4/C4). */
    val MAX_BOUNDS = listOf(5.5, 47.0, 15.6, 55.2)

    const val POINT_RADIUS_CAP_PX = 28.0
    const val CLUSTER_RADIUS_CAP_PX = 36.0

    /**
     * `min(cap, 4 + 3*sqrt(n))` -- a sublinear (square-root) growth curve so a hub postal code with
     * an order of magnitude more members does not dominate the map by area, capped so it never
     * obscures neighbouring markers. Same shape for individual postal-code points
     * ([POINT_RADIUS_CAP_PX]) and cluster bubbles ([CLUSTER_RADIUS_CAP_PX]), only the cap differs.
     */
    fun radiusPx(
        n: Int,
        capPx: Double,
    ): Double = min(capPx, 4.0 + 3.0 * sqrt(n.toDouble()))

    private val GERMAN_POSTAL_CODE_REGEX = Regex("^[0-9]{5}$")

    fun isGermanPostalCode(raw: String?): Boolean {
        val trimmed = raw?.trim() ?: return false
        return GERMAN_POSTAL_CODE_REGEX.matches(trimmed)
    }

    /**
     * Q3 decision: `null`/blank counts as Germany (this organization's overwhelming default, and
     * the shape `Member.country` free-text has always defaulted to in every seed/onboarding flow --
     * see `MemberMapAggregationTest` "the decisive part is 'blank country = Germany'"). Everything
     * else recognised here is a case/whitespace-insensitive spelling of Germany itself; anything not
     * on this list (including a genuinely unset-but-foreign address, which this codebase has no way
     * to distinguish from "just never filled in") is treated as foreign -- see
     * `docs/architecture/member-map.adoc` §Q3 for the accepted risk this implies.
     */
    fun isGermanCountry(raw: String?): Boolean {
        val normalized = raw?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return true
        return normalized in GERMAN_COUNTRY_SPELLINGS
    }

    private val GERMAN_COUNTRY_SPELLINGS =
        setOf(
            "de",
            "deu",
            "d",
            "deutschland",
            "germany",
            "brd",
            "bundesrepublik deutschland",
        )
}
