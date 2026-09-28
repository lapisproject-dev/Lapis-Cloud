package network.lapis.cloud.client

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.shared.domain.BoardMemberMapResponse
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import network.lapis.cloud.shared.domain.MemberMapRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.6 "Vorstands-Karte" (member map, client half) -- covers the pure, DOM-independent helper
 * functions of `MemberMapScreen.kt`/`MemberMapBasemapStyle.kt`, same scope posture as
 * `MemberAnniversariesScreenTest` (no DOM/render harness needed for these).
 */
class MemberMapScreenTest {
    private fun entry(
        postalCode: String = "38100",
        placeName: String? = "Braunschweig",
        lat: Double? = 52.27,
        lon: Double? = 10.52,
        count: Int = 5,
    ) = MemberMapEntryDto(postalCode = postalCode, placeName = placeName, lat = lat, lon = lon, count = count)

    private fun response(
        entries: List<MemberMapEntryDto> = emptyList(),
        total: Int = 0,
        mappedTotal: Int = 0,
        unresolvableGermanPostalCode: Int = 0,
        noPostalCode: Int = 0,
        foreign: Int = 0,
        tilesAvailable: Boolean = true,
        geodataAvailable: Boolean = true,
    ) = BoardMemberMapResponse(
        entries = entries,
        total = total,
        mappedTotal = mappedTotal,
        unresolvableGermanPostalCode = unresolvableGermanPostalCode,
        noPostalCode = noPostalCode,
        foreign = foreign,
        tilesAvailable = tilesAvailable,
        geodataAvailable = geodataAvailable,
    )

    // ── filterMemberMapEntries ──────────────────────────────────────────────────────────────

    @Test
    fun filterMemberMapEntries_postalCodePrefix_matchesOnlyThatPrefix() {
        val entries =
            listOf(
                entry(postalCode = "38100"),
                entry(postalCode = "38102"),
                entry(postalCode = "39100"),
            )
        val result = filterMemberMapEntries(entries, "38")
        assertEquals(setOf("38100", "38102"), result.map { it.postalCode }.toSet())
    }

    @Test
    fun filterMemberMapEntries_placeNamePrefix_isCaseInsensitive() {
        val entries = listOf(entry(placeName = "Braunschweig"), entry(postalCode = "10115", placeName = "Berlin"))
        val result = filterMemberMapEntries(entries, "braun")
        assertEquals(listOf("Braunschweig"), result.map { it.placeName })
    }

    @Test
    fun filterMemberMapEntries_blankSearch_returnsEverything() {
        val entries = listOf(entry(postalCode = "38100"), entry(postalCode = "10115", placeName = "Berlin"))
        assertEquals(entries, filterMemberMapEntries(entries, "   "))
    }

    // ── checkMemberMapTotalInvariant ────────────────────────────────────────────────────────

    @Test
    fun checkMemberMapTotalInvariant_holds_returnsTrue() {
        val dto = response(total = 100, mappedTotal = 70, unresolvableGermanPostalCode = 10, noPostalCode = 15, foreign = 5)
        assertTrue(checkMemberMapTotalInvariant(dto))
    }

    @Test
    fun checkMemberMapTotalInvariant_violated_returnsFalse_neverThrows() {
        val dto = response(total = 100, mappedTotal = 70, unresolvableGermanPostalCode = 10, noPostalCode = 15, foreign = 999)
        assertFalse(checkMemberMapTotalInvariant(dto))
    }

    // ── memberMapDegradation ────────────────────────────────────────────────────────────────

    @Test
    fun memberMapDegradation_webglMissing_winsOverEverythingElse() {
        val dto = response(tilesAvailable = true, geodataAvailable = true)
        assertEquals(MemberMapDegradation.WEBGL_MISSING, memberMapDegradation(dto, webglAvailable = false))
    }

    @Test
    fun memberMapDegradation_bothAvailable_isNone() {
        val dto = response(tilesAvailable = true, geodataAvailable = true)
        assertEquals(MemberMapDegradation.NONE, memberMapDegradation(dto, webglAvailable = true))
    }

    @Test
    fun memberMapDegradation_onlyTilesMissing_isTilesUnavailable() {
        val dto = response(tilesAvailable = false, geodataAvailable = true)
        assertEquals(MemberMapDegradation.TILES_UNAVAILABLE, memberMapDegradation(dto, webglAvailable = true))
    }

    @Test
    fun memberMapDegradation_onlyGeodataMissing_isGeodataUnavailable() {
        val dto = response(tilesAvailable = true, geodataAvailable = false)
        assertEquals(MemberMapDegradation.GEODATA_UNAVAILABLE, memberMapDegradation(dto, webglAvailable = true))
    }

    @Test
    fun memberMapDegradation_bothMissing_isBothUnavailable() {
        val dto = response(tilesAvailable = false, geodataAvailable = false)
        assertEquals(MemberMapDegradation.BOTH_UNAVAILABLE, memberMapDegradation(dto, webglAvailable = true))
    }

    // ── memberMapSearchHitText ──────────────────────────────────────────────────────────────

    @Test
    fun memberMapSearchHitText_sumsMemberCountsOfVisibleEntriesOnly() {
        val total = listOf(entry(postalCode = "38100", count = 5), entry(postalCode = "10115", placeName = "Berlin", count = 42))
        val shown = total.filter { it.postalCode == "38100" }
        val text = memberMapSearchHitText(shown, total)
        assertTrue(text.contains("1"))
        assertTrue(text.contains("2"))
        assertTrue(text.contains("5"))
    }

    // ── memberMapPopupCountText ─────────────────────────────────────────────────────────────

    @Test
    fun memberMapPopupCountText_singular() {
        assertEquals(true, memberMapPopupCountText(1).contains("1"))
        assertEquals(false, memberMapPopupCountText(1).contains("Mitglieder"))
    }

    @Test
    fun memberMapPopupCountText_plural() {
        val text = memberMapPopupCountText(42)
        assertTrue(text.contains("42"))
        assertTrue(text.contains("Mitglieder"))
    }

    // ── clusterRadiusExpression / evaluateRadiusExpression: parity with MemberMapRules.radiusPx ──

    @Test
    fun evaluateRadiusExpression_matchesMemberMapRulesRadiusPx_forVariousCounts() {
        val cap = MemberMapRules.CLUSTER_RADIUS_CAP_PX
        val expr = clusterRadiusExpression(cap)
        listOf(1, 2, 3, 42, 100, 1000).forEach { n ->
            val expected = MemberMapRules.radiusPx(n, cap)
            val actual = evaluateRadiusExpression(expr, mapOf("memberSum" to n.toDouble()))
            assertEquals(expected, actual, "radius parity for n=$n")
        }
    }

    // ── cluster/basemap style JSON content ──────────────────────────────────────────────────

    private val fixtureColors =
        MemberMapColors(
            land = "#111111",
            water = "#222222",
            countryBorder = "#333333",
            stateBorder = "#444444",
            highway = "#555555",
            pointFill = "#666666",
            pointStroke = "#777777",
            clusterFill = "#888888",
            clusterStroke = "#999999",
        )

    @Test
    fun buildPointsSourceJson_containsMemberSumClusterProperty() {
        val json = buildPointsSourceJson()
        assertTrue(json.contains("memberSum"))
        assertTrue(json.contains("\"cluster\": true") || json.contains("\"cluster\":true"))
    }

    @Test
    fun buildClustersLayerJson_radiusReferencesMemberSum_neverPointCount() {
        val json = buildClustersLayerJson(fixtureColors)
        assertTrue(json.contains("memberSum"))
        // The FILTER legitimately references the built-in `point_count` (to distinguish a cluster from
        // an individual point) -- but the PAINT block (the part after "paint") must never reference it,
        // see the wave's testplan "Pflichttest" #6.
        val paintSection = json.substringAfter("\"paint\"")
        assertFalse(paintSection.contains("point_count"))
    }

    @Test
    fun clusterMaxZoom_isBelowFlyToZoom() {
        // Atkinson's find (Design-Team-Sitzung 3): if this ever regressed, a table-row click on an
        // entry still inside a cluster at FLY_TO_ZOOM would land the view on the cluster bubble, not
        // the individual point.
        assertTrue(
            CLUSTER_MAX_ZOOM < MemberMapRules.FLY_TO_ZOOM,
            "CLUSTER_MAX_ZOOM ($CLUSTER_MAX_ZOOM) must stay below FLY_TO_ZOOM (${MemberMapRules.FLY_TO_ZOOM})",
        )
    }

    // ── buildPointsGeoJson: coordinates + XSS-safe serialization ────────────────────────────

    @Test
    fun buildPointsGeoJson_dropsEntriesWithoutCoordinates() {
        val entries =
            listOf(
                entry(postalCode = "38100", lat = 52.0, lon = 10.0),
                entry(postalCode = "99999", placeName = null, lat = null, lon = null),
            )
        val json = buildPointsGeoJson(entries)
        assertTrue(json.contains("38100"))
        assertFalse(json.contains("99999"))
    }

    /**
     * XSS-relevant safety property of [buildPointsGeoJson]: a `placeName` containing a double quote
     * (the character that would let a naive string-concatenation build break out of the JSON string
     * boundary and inject a sibling key/value) round-trips correctly through the real JSON parser --
     * proof this is a proper serializer, not manual concatenation. `<img ...>` markup itself needs no
     * special HTML-escaping here: this string is only ever consumed as JSON DATA (via `JSON.parse`,
     * then `Popup.setDOMContent`'s text nodes or a KVision `span`'s text content, never `innerHTML`).
     */
    @Test
    fun buildPointsGeoJson_placeNameWithQuote_roundTripsThroughRealJsonParser() {
        val malicious = """<img src=x onerror=alert(1)> "quoted" & injected"""
        val entries = listOf(entry(placeName = malicious))
        val json = buildPointsGeoJson(entries)
        val parsed =
            kotlinx.serialization.json.Json
                .parseToJsonElement(json)
        val placeName =
            parsed.jsonObject["features"]!!
                .jsonArray[0]
                .jsonObject["properties"]!!
                .jsonObject["placeName"]!!
                .jsonPrimitive.content
        assertEquals(malicious, placeName)
    }

    // ── V1.9.9 Ortssuche: memberCountsWithinRadii ───────────────────────────────────────────

    @Test
    fun memberCountsWithinRadii_entryExactlyAtSearchPoint_countsInEveryRadius() {
        val entries = listOf(entry(lat = 52.5, lon = 13.4, count = 10))
        val result = memberCountsWithinRadii(entries, lat = 52.5, lon = 13.4, radiiKm = listOf(5, 10, 25))
        assertEquals(10, result.countsByRadiusKm[5])
        assertEquals(10, result.countsByRadiusKm[10])
        assertEquals(10, result.countsByRadiusKm[25])
        assertEquals(0, result.skippedWithoutCoordinates)
    }

    @Test
    fun memberCountsWithinRadii_entryOutsideEveryRadius_countsInNone() {
        // Berlin center vs. an entry roughly 255 km away (Hamburg) -- outside all three default radii.
        val entries = listOf(entry(lat = 53.55, lon = 10.0, count = 7))
        val result = memberCountsWithinRadii(entries, lat = 52.5, lon = 13.4, radiiKm = listOf(5, 10, 25))
        assertEquals(0, result.countsByRadiusKm[5])
        assertEquals(0, result.countsByRadiusKm[10])
        assertEquals(0, result.countsByRadiusKm[25])
    }

    @Test
    fun memberCountsWithinRadii_entryWithoutCoordinates_isSkippedNotSilentlyDropped() {
        val entries = listOf(entry(lat = null, lon = null, count = 3))
        val result = memberCountsWithinRadii(entries, lat = 52.5, lon = 13.4, radiiKm = listOf(5, 10, 25))
        assertEquals(3, result.skippedWithoutCoordinates)
        assertEquals(0, result.countsByRadiusKm[5])
    }

    @Test
    fun memberCountsWithinRadii_isCumulative_notBanded() {
        // An entry within 5km is ALSO counted in the 10km and 25km buckets -- see this function's own KDoc.
        val entries = listOf(entry(lat = 52.501, lon = 13.401, count = 4))
        val result = memberCountsWithinRadii(entries, lat = 52.5, lon = 13.4, radiiKm = listOf(5, 10, 25))
        assertEquals(4, result.countsByRadiusKm[5])
        assertEquals(4, result.countsByRadiusKm[10])
        assertEquals(4, result.countsByRadiusKm[25])
    }

    @Test
    fun memberCountsWithinRadii_sumsMultipleEntriesWithinTheSameRadius() {
        val entries =
            listOf(
                entry(postalCode = "10001", lat = 52.501, lon = 13.401, count = 3),
                entry(postalCode = "10002", lat = 52.502, lon = 13.402, count = 5),
            )
        val result = memberCountsWithinRadii(entries, lat = 52.5, lon = 13.4, radiiKm = listOf(5))
        assertEquals(8, result.countsByRadiusKm[5])
    }
}
