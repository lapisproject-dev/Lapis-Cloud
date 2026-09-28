package network.lapis.cloud.server.membermap

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** GeoNames `place_name` plus centroid coordinates for exactly one German 5-digit postal code. */
data class PostalCodeCentroid(
    val placeName: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Welle V1.9.5 "Vorstands-Karte" -- bundled (classpath, `geodata/de-postal-centroids.csv`, GeoNames
 * CC-BY-4.0, see `geodata/LICENSE-GeoNames.txt`) lookup from a 5-digit German postal code to its
 * GeoNames place-name centroid. `internal constructor` -- the only way to obtain an instance is
 * [parse] (pure, testable) or [loadFromClasspath]/[bundled] (the classpath-reading wrapper around it).
 */
class PostalCodeCentroidIndex internal constructor(
    private val byCode: Map<String, PostalCodeCentroid>,
) {
    val size: Int get() = byCode.size

    fun lookup(postalCode: String): PostalCodeCentroid? = byCode[postalCode.trim()]

    /**
     * V1.9.9 "Ortssuche" -- the ONE place in this codebase [PlaceSearchIndex.build] is allowed to
     * iterate every entry (see that class's KDoc for why: it groups postal codes by place, something
     * [lookup]'s single-code interface cannot do). Returns a defensive read-only view, postal code
     * ascending (`byCode`'s own insertion order, i.e. the bundled CSV's row order -- not re-sorted,
     * callers that need a specific order sort themselves).
     */
    fun entries(): List<Pair<String, PostalCodeCentroid>> = byCode.entries.map { it.key to it.value }

    companion object {
        const val RESOURCE = "/geodata/de-postal-centroids.csv"
        private const val EXPECTED_HEADER = "postal_code,place_name,lat,lon"
        const val MIN_EXPECTED_ENTRIES = 8_000

        // Q4/M4: same bounding box as MemberMapRules.MAX_BOUNDS (west, south, east, north) -- a
        // centroid outside it is almost certainly a parsing error (swapped lat/lon, wrong decimal
        // point) rather than a genuine German postal code, so the whole file is rejected rather than
        // silently keeping a corrupted entry (fail-closed, see [parse] KDoc).
        private const val BOUNDS_WEST = 5.5
        private const val BOUNDS_SOUTH = 47.0
        private const val BOUNDS_EAST = 15.6
        private const val BOUNDS_NORTH = 55.2

        private val POSTAL_CODE_REGEX = Regex("^[0-9]{5}$")

        /**
         * Parses the bundled CSV format (see `geodata/LICENSE-GeoNames.txt` for the upstream
         * processing that produced it): header `postal_code,place_name,lat,lon`, LF line endings (a
         * trailing `\r` is tolerated per line so a CRLF-saved copy still parses), `place_name` may
         * carry RFC-4180 double-quoting (`"..."`, with `""` as an escaped quote) because GeoNames
         * place names themselves legitimately contain commas (55 such rows in the bundled file, e.g.
         * postal code 01059 "Deutsche Telekom AG, GSUS").
         *
         * **Fails closed, as a whole file, not per-row**: a wrong header, a malformed row (wrong
         * field count, non-5-digit postal code, non-numeric lat/lon, an out-of-[BOUNDS_*] coordinate),
         * a duplicate postal code, or fewer than [MIN_EXPECTED_ENTRIES] total rows each throw
         * [IllegalArgumentException] -- there is no principled way to silently drop "just the bad
         * rows" and still trust the file is what it claims to be, and this only ever runs against a
         * classpath resource this codebase itself ships, never operator/user input.
         */
        internal fun parse(lines: Sequence<String>): PostalCodeCentroidIndex {
            val iterator = lines.iterator()
            require(iterator.hasNext()) { "PostalCodeCentroidIndex: empty input" }
            val header = iterator.next().trimEnd('\r')
            require(header == EXPECTED_HEADER) {
                "PostalCodeCentroidIndex: unexpected header '$header', expected '$EXPECTED_HEADER'"
            }

            val byCode = LinkedHashMap<String, PostalCodeCentroid>()
            while (iterator.hasNext()) {
                val rawLine = iterator.next().trimEnd('\r')
                if (rawLine.isEmpty()) continue
                val fields = parseCsvLine(rawLine)
                require(fields.size == 4) { "PostalCodeCentroidIndex: expected 4 fields, got ${fields.size}: '$rawLine'" }
                val (postalCode, placeName, latRaw, lonRaw) = fields
                require(POSTAL_CODE_REGEX.matches(postalCode)) { "PostalCodeCentroidIndex: invalid postal code '$postalCode'" }
                val lat =
                    latRaw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("PostalCodeCentroidIndex: invalid lat '$latRaw' for $postalCode")
                val lon =
                    lonRaw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("PostalCodeCentroidIndex: invalid lon '$lonRaw' for $postalCode")
                require(lon in BOUNDS_WEST..BOUNDS_EAST && lat in BOUNDS_SOUTH..BOUNDS_NORTH) {
                    "PostalCodeCentroidIndex: coordinate ($lat, $lon) for $postalCode outside Germany bounds"
                }
                require(!byCode.containsKey(postalCode)) { "PostalCodeCentroidIndex: duplicate postal code '$postalCode'" }
                byCode[postalCode] = PostalCodeCentroid(placeName = placeName, lat = lat, lon = lon)
            }
            require(byCode.size >= MIN_EXPECTED_ENTRIES) {
                "PostalCodeCentroidIndex: only ${byCode.size} entries, expected at least $MIN_EXPECTED_ENTRIES"
            }
            return PostalCodeCentroidIndex(byCode)
        }

        /**
         * A tiny hand-rolled RFC-4180 splitter (not a general CSV library dependency for 4 fixed
         * columns): a field starting with `"` runs until the next unescaped `"`, with `""` decoding
         * to a literal `"` inside it; any other field runs to the next top-level comma.
         */
        private fun parseCsvLine(line: String): List<String> {
            val fields = mutableListOf<String>()
            var i = 0
            while (i <= line.length) {
                if (i < line.length && line[i] == '"') {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (true) {
                        require(j < line.length) { "PostalCodeCentroidIndex: unterminated quoted field in '$line'" }
                        if (line[j] == '"') {
                            if (j + 1 < line.length && line[j + 1] == '"') {
                                sb.append('"')
                                j += 2
                            } else {
                                j += 1
                                break
                            }
                        } else {
                            sb.append(line[j])
                            j += 1
                        }
                    }
                    fields += sb.toString()
                    // j now points just past the closing quote; expect a comma or end of line.
                    i = if (j < line.length && line[j] == ',') j + 1 else j + 1
                    if (i > line.length) i = line.length + 1
                } else {
                    val comma = line.indexOf(',', i)
                    if (comma == -1) {
                        fields += line.substring(i)
                        i = line.length + 1
                    } else {
                        fields += line.substring(i, comma)
                        i = comma + 1
                    }
                }
            }
            return fields
        }

        /**
         * Loads and [parse]s [resource] from the classpath. Never throws -- any I/O or [parse]
         * failure is logged at WARN and answers `null`, degrading `geodataAvailable` to `false` (see
         * `MemberMapAggregation`) rather than breaking every one of the ~700 test applications that
         * boot this module.
         */
        fun loadFromClasspath(resource: String = RESOURCE): PostalCodeCentroidIndex? =
            try {
                val stream =
                    checkNotNull(PostalCodeCentroidIndex::class.java.getResourceAsStream(resource)) {
                        "resource $resource missing from classpath"
                    }
                stream.use { it.bufferedReader(Charsets.UTF_8).lineSequence().let(::parse) }
            } catch (e: Exception) {
                logger.warn(e) { "PostalCodeCentroidIndex: failed to load '$resource' -- member map postal-code lookup disabled." }
                null
            }

        /**
         * Loaded exactly once per JVM ([lazy]) -- without this, every one of the ~700 test
         * applications that call [network.lapis.cloud.server.module] would re-parse the ~406 KB CSV
         * from scratch.
         */
        val bundled: PostalCodeCentroidIndex? by lazy { loadFromClasspath() }
    }
}
