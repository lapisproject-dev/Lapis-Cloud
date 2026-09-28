package network.lapis.cloud.server.membermap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * One searchable, GEOCODED place -- a locality name plus every German postal code the bundled
 * centroid index attributes to it, grouped by [PlaceSearchIndex.build]'s single-linkage step (same
 * name, all within [PlaceSearchIndex.GROUPING_RADIUS_KM] of each other). [lat]/[lon] are the mean of
 * the grouped postal codes' own centroids, never a member coordinate -- this type carries no
 * membership data whatsoever, only GeoNames-derived place/postal-code facts (see [PlaceSearchIndex]
 * class KDoc "no new PII surface").
 */
data class PlaceGroup(
    val placeName: String,
    val lat: Double,
    val lon: Double,
    val postalCodes: List<String>,
)

/**
 * Welle V1.9.9 "Vorstands-Karte: Ortssuche" -- a searchable index over the SAME bundled GeoNames
 * postal-code centroid CSV [PostalCodeCentroidIndex] already loads, built once at JVM startup
 * ([bundled], `lazy`, identical idiom to that class's own `bundled`).
 *
 * **Why grouped, not one row per postal code.** [PostalCodeCentroidIndex] is keyed one-to-one by
 * postal code -- searching "Berlin" against it directly would surface (and force the board member to
 * pick from) ~180 nearly-identical rows, one per Berlin postal code. [build] instead groups postal
 * codes that share the SAME `place_name` (case/diacritic-exact -- see [normalize] for why grouping
 * itself does NOT fold umlauts, only query MATCHING does) and lie within [GROUPING_RADIUS_KM] of each
 * other, so "Berlin" search returns ONE result (mean-centroid, all ~180 postal codes attached) while
 * "Bernau" -- a name genuinely shared by two unrelated German towns roughly 500 km apart (bundled
 * postal codes 16321 near Berlin and 79872 in the Schwarzwald) -- correctly returns TWO.
 *
 * **Institution-name filter, applied BEFORE grouping** ([looksLikeInstitution]). The bundled CSV's own
 * `place_name` field legitimately carries company/authority names for some postal codes (large
 * organizations' own postal codes, e.g. `01053,Commerzbank AG` or `01056,Finanzamt Dresden - Nord` --
 * verified present in `geodata/de-postal-centroids.csv` itself, not merely claimed) -- these are real,
 * useful entries for [PostalCodeCentroidIndex]'s original purpose (resolving a MEMBER's postal code to
 * a place name for the map), but as Ortssuche autocomplete suggestions they would be noise a board
 * member never wants ("Commerzbank AG" is not an event venue). The regex below was tuned against the
 * REAL bundled file (`\bAG\b`/`\bGmbH\b`/`\bKG\b`/`\bSE\b`/`\beG\b` plus a handful of literal
 * institution-type words) -- see `PlaceSearchIndexTest` for the concrete rows it must exclude.
 *
 * **No k-anonymity threshold, no member data of any kind** -- [PlaceGroup] carries only GeoNames place
 * facts. [BoardMemberMapService.searchPlaces]'s caller still needs the SAME `MEMBER_MAP_READ_ROLES`
 * gate as `getMemberMap`, purely because this is still board-internal event-planning tooling, not
 * because a place name itself is sensitive.
 */
class PlaceSearchIndex internal constructor(
    private val groups: List<PlaceGroup>,
) {
    /**
     * Ranked, capped at [MAX_RESULTS]. Three tiers, each internally sorted by postal-code-count
     * descending (a rough "how big is this place" proxy in the absence of any population figure in
     * the bundled data) then place name -- exact [normalize]d name match, then name-prefix match, then
     * "contains" (name substring OR, for an all-digit [query], a postal-code prefix match). A [query]
     * under 2 characters (after trimming) answers empty -- also enforced again, independently, by
     * [network.lapis.cloud.server.rpc.BoardMemberMapService.searchPlaces] before this is ever called
     * (defense in depth, see that method's KDoc).
     */
    fun search(query: String): List<PlaceGroup> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val normalizedQuery = normalize(trimmed)
        val isDigits = trimmed.all { it.isDigit() }

        val exact = mutableListOf<PlaceGroup>()
        val prefix = mutableListOf<PlaceGroup>()
        val contains = mutableListOf<PlaceGroup>()
        for (group in groups) {
            val normalizedName = normalize(group.placeName)
            when {
                normalizedName == normalizedQuery -> exact += group
                normalizedName.startsWith(normalizedQuery) -> prefix += group
                normalizedName.contains(normalizedQuery) -> contains += group
                isDigits && group.postalCodes.any { it.startsWith(trimmed) } -> contains += group
            }
        }
        val byRelevance = compareByDescending<PlaceGroup> { it.postalCodes.size }.thenBy { it.placeName }
        return (exact.sortedWith(byRelevance) + prefix.sortedWith(byRelevance) + contains.sortedWith(byRelevance))
            .take(MAX_RESULTS)
    }

    companion object {
        const val MAX_RESULTS = 8

        /**
         * Two same-name postal-code centroids merge into one [PlaceGroup] when within this distance of
         * EACH OTHER (single-linkage, i.e. transitively -- see [singleLinkageCluster]) -- large enough
         * to fold together a city's many postal-code centroids (Berlin's own centroids span well under
         * 15 km end to end) without merging two distinct same-named towns in different regions.
         */
        private const val GROUPING_RADIUS_KM = 15.0

        /**
         * Verified against the real bundled CSV (`geodata/de-postal-centroids.csv`), see class KDoc.
         * Word-boundary (`\b`) around the bare legal-form abbreviations -- `AG`/`KG`/`SE`/`eG` are all
         * short enough to otherwise collide with real place-name substrings if matched unanchored, but
         * German place names never contain an isolated, capitalized "AG"/"KG"/"SE"/"eG" token on their
         * own.
         */
        private val INSTITUTION_PATTERN =
            Regex(
                "\\b(AG|GmbH|mbH|KG|SE|eG)\\b|Finanzamt|Versicherung|Bundeswehr|Zollamt|Kaserne|" +
                    "Verwaltung|Bundesagentur|Sparkasse|Volksbank|Raiffeisenbank|Genossenschaft|" +
                    "Telekom|HUK-Coburg|Feuerwehr|Verkehrsbetriebe|Wasserbetriebe|Anstalt|" +
                    "Stadtwerke|Landesamt|Bundesamt|Krankenhaus|Klinikum|Universit|Hauptzollamt|" +
                    "Bundesanstalt|Behörde|Dienststelle|Polizei|Justizvollzug",
            )

        internal fun looksLikeInstitution(placeName: String): Boolean = INSTITUTION_PATTERN.containsMatchIn(placeName)

        /**
         * Query/grouping-key normalization -- lowercase plus the standard German ASCII transliteration
         * (`ä`->`ae`, `ö`->`oe`, `ü`->`ue`, `ß`->`ss`), so a board member typing "muenchen" (no umlaut
         * key available/habitual) and one typing "münchen" both normalize to `"muenchen"` and match the
         * same [PlaceGroup]. Used ONLY for matching a [search] query against a group's [PlaceGroup
         * .placeName] -- [build]'s own grouping key is the RAW, un-normalized name (see [build] KDoc
         * "grouping itself does NOT fold umlauts"), so two genuinely different place names that happen
         * to normalize to the same folded form are never silently merged into one group.
         */
        private fun normalize(raw: String): String =
            raw
                .trim()
                .lowercase()
                .replace("ß", "ss")
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")

        /** Great-circle distance in km -- see `network.lapis.cloud.client.haversineKm` (client-side twin used by the radius-count popup) for the identical formula, duplicated rather than shared because `lapis-shared` has no natural home for a server-startup-only helper this small. */
        private fun haversineKm(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val earthRadiusKm = 6371.0
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val a =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadiusKm * c
        }

        /**
         * Single-linkage clustering of [members] (all sharing one exact place name already) by
         * [radiusKm] -- a plain union-find over every pair, `O(n^2)` WITHIN one name bucket only (the
         * bucket sizes stay small: the real bundled file's largest, "Berlin", has ~180 rows, so this is
         * at most a few tens of thousands of haversine calls for that one bucket, entirely acceptable
         * for a `lazy`, once-per-JVM build step).
         */
        private fun singleLinkageCluster(
            members: List<Pair<String, PostalCodeCentroid>>,
            radiusKm: Double,
        ): List<List<Pair<String, PostalCodeCentroid>>> {
            val n = members.size
            val parent = IntArray(n) { it }

            fun find(x: Int): Int {
                var root = x
                while (parent[root] != root) root = parent[root]
                var cursor = x
                while (parent[cursor] != root) {
                    val next = parent[cursor]
                    parent[cursor] = root
                    cursor = next
                }
                return root
            }

            fun union(
                a: Int,
                b: Int,
            ) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }

            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val ci = members[i].second
                    val cj = members[j].second
                    if (haversineKm(lat1 = ci.lat, lon1 = ci.lon, lat2 = cj.lat, lon2 = cj.lon) <= radiusKm) union(i, j)
                }
            }
            return (0 until n).groupBy(::find).values.map { indices -> indices.map { members[it] } }
        }

        /** Pure, testable build step -- [bundled] is the classpath-reading wrapper around this, same split as [PostalCodeCentroidIndex.parse]/[PostalCodeCentroidIndex.loadFromClasspath]. */
        internal fun build(index: PostalCodeCentroidIndex): PlaceSearchIndex {
            val byName = LinkedHashMap<String, MutableList<Pair<String, PostalCodeCentroid>>>()
            var excluded = 0
            index.entries().forEach { (postalCode, centroid) ->
                if (looksLikeInstitution(centroid.placeName)) {
                    excluded++
                    return@forEach
                }
                byName.getOrPut(centroid.placeName.trim()) { mutableListOf() }.add(postalCode to centroid)
            }

            val groups = mutableListOf<PlaceGroup>()
            byName.forEach { (name, members) ->
                singleLinkageCluster(members = members, radiusKm = GROUPING_RADIUS_KM).forEach { cluster ->
                    val lat = cluster.sumOf { it.second.lat } / cluster.size
                    val lon = cluster.sumOf { it.second.lon } / cluster.size
                    groups +=
                        PlaceGroup(
                            placeName = name,
                            lat = lat,
                            lon = lon,
                            postalCodes = cluster.map { it.first }.sorted(),
                        )
                }
            }
            logger.info {
                "PlaceSearchIndex: built ${groups.size} place groups from ${index.size} postal codes " +
                    "($excluded excluded as institution-like names)."
            }
            return PlaceSearchIndex(groups)
        }

        /** Loaded exactly once per JVM, from [PostalCodeCentroidIndex.bundled] -- `null` if that itself failed to load (see its own KDoc), degrading Ortssuche to "no results" rather than breaking map/aggregation. */
        val bundled: PlaceSearchIndex? by lazy { PostalCodeCentroidIndex.bundled?.let(::build) }
    }
}
