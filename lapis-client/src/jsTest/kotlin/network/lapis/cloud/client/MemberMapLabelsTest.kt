package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberMapRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.8 "Vorstands-Karte: Orientierung" -- covers the pure, DOM-free helpers of
 * `MemberMapLabels.kt`, same scope posture as `MemberMapScreenTest`/`MemberMapMapControllerTest`
 * (no DOM/render harness needed for these).
 */
class MemberMapLabelsTest {
    // ── MEMBER_MAP_LABELS: count, uniqueness, bounds ────────────────────────────────────────

    @Test
    fun memberMapLabels_hasExactlyTwentyFourEntries() {
        assertEquals(24, MEMBER_MAP_LABELS.size)
    }

    @Test
    fun memberMapLabels_namesAreAllUnique() {
        val names = MEMBER_MAP_LABELS.map { it.name }
        assertEquals(
            names.size,
            names.toSet().size,
            "duplicate label name(s): ${names.groupingBy { it }.eachCount().filterValues { it > 1 }}",
        )
    }

    @Test
    fun memberMapLabels_kindCounts_matchThePlan() {
        val byKind = MEMBER_MAP_LABELS.groupingBy { it.kind }.eachCount()
        assertEquals(12, byKind[MemberMapLabelKind.STATE])
        assertEquals(4, byKind[MemberMapLabelKind.SMALL_STATE])
        assertEquals(8, byKind[MemberMapLabelKind.NEIGHBOR])
    }

    @Test
    fun memberMapLabels_everyCoordinateLiesInsideMaxBounds() {
        val (west, south, east, north) = MemberMapRules.MAX_BOUNDS
        MEMBER_MAP_LABELS.forEach { label ->
            assertTrue(
                label.lon in west..east,
                "${label.name}: lon ${label.lon} outside [$west, $east]",
            )
            assertTrue(
                label.lat in south..north,
                "${label.name}: lat ${label.lat} outside [$south, $north]",
            )
        }
    }

    // ── memberMapLabelText ──────────────────────────────────────────────────────────────────

    @Test
    fun memberMapLabelText_shortName_passesThroughUnchanged() {
        assertEquals("Bayern", memberMapLabelText("Bayern"))
    }

    @Test
    fun memberMapLabelText_longHyphenatedName_breaksAfterTheHyphen() {
        assertEquals("Nordrhein-\nWestfalen", memberMapLabelText("Nordrhein-Westfalen"))
    }

    @Test
    fun memberMapLabelText_longNameWithoutHyphen_isReturnedUnchanged() {
        val name = "Mecklenburg Vorpommern Nord" // no hyphen, contrived but exercises the fallback branch
        assertEquals(name, memberMapLabelText(name))
    }

    // ── memberMapZoomBand ───────────────────────────────────────────────────────────────────

    @Test
    fun memberMapZoomBand_belowSix_isLow() {
        assertEquals("low", memberMapZoomBand(4.0))
        assertEquals("low", memberMapZoomBand(5.99))
    }

    @Test
    fun memberMapZoomBand_sixToBelowEight_isMid() {
        assertEquals("mid", memberMapZoomBand(6.0))
        assertEquals("mid", memberMapZoomBand(7.99))
    }

    @Test
    fun memberMapZoomBand_eightAndAbove_isHigh() {
        assertEquals("high", memberMapZoomBand(8.0))
        assertEquals("high", memberMapZoomBand(10.0))
    }

    // ── memberMapPointTooltipLines ──────────────────────────────────────────────────────────

    @Test
    fun memberMapPointTooltipLines_withPlaceName_firstLineContainsBothPostalCodeAndPlace() {
        val lines = memberMapPointTooltipLines(placeName = "Braunschweig", postalCode = "38100", count = 3)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("38100"))
        assertTrue(lines[0].contains("Braunschweig"))
    }

    @Test
    fun memberMapPointTooltipLines_withoutPlaceName_firstLineFallsBackToPlzPlusCode() {
        val lines = memberMapPointTooltipLines(placeName = null, postalCode = "99999", count = 1)
        assertTrue(lines[0].contains("PLZ"))
        assertTrue(lines[0].contains("99999"))
        assertFalse(lines[0].contains("null"))
    }

    @Test
    fun memberMapPointTooltipLines_secondLine_singularVsPlural() {
        assertTrue(memberMapPointTooltipLines("X", "38100", 1)[1].contains("1"))
        assertFalse(memberMapPointTooltipLines("X", "38100", 1)[1].contains("Mitglieder"))
        assertTrue(memberMapPointTooltipLines("X", "38100", 5)[1].contains("Mitglieder"))
    }

    // ── memberMapClusterTooltipLines ────────────────────────────────────────────────────────

    @Test
    fun memberMapClusterTooltipLines_hasThreeLines_pointCountAndMemberSumAndCallToAction() {
        val lines = memberMapClusterTooltipLines(pointCount = 7, memberSum = 42)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("7"))
        assertTrue(lines[1].contains("42"))
        assertTrue(lines[1].contains("Mitglieder"))
    }

    // ── V1.9.9 "Details & Suche": MEMBER_MAP_CAPITALS ───────────────────────────────────────

    @Test
    fun memberMapCapitals_hasExactlySixteenEntries_onePerBundesland() {
        assertEquals(16, MEMBER_MAP_CAPITALS.size)
    }

    @Test
    fun memberMapCapitals_namesAreAllUnique() {
        val names = MEMBER_MAP_CAPITALS.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun memberMapCapitals_everyCoordinateLiesInsideMaxBounds() {
        val (west, south, east, north) = MemberMapRules.MAX_BOUNDS
        MEMBER_MAP_CAPITALS.forEach { capital ->
            assertTrue(capital.lon in west..east, "${capital.name}: lon ${capital.lon} outside [$west, $east]")
            assertTrue(capital.lat in south..north, "${capital.name}: lat ${capital.lat} outside [$south, $north]")
        }
    }

    @Test
    fun memberMapCapitals_containsMagdeburgAndDresden() {
        // Direct user feedback (2026-09-28): these two were missing from the map entirely before
        // V1.9.9 -- MEMBER_MAP_LABELS only ever named the STATE (Sachsen-Anhalt/Sachsen), never its
        // capital.
        val names = MEMBER_MAP_CAPITALS.map { it.name }
        assertTrue("Magdeburg" in names)
        assertTrue("Dresden" in names)
    }

    // ── selectPlaceLabels ────────────────────────────────────────────────────────────────────

    @Test
    fun selectPlaceLabels_dropsDuplicateNamesCaseInsensitive() {
        val candidates =
            listOf(
                PlaceLabelCandidate("Dorf", 10.0, 50.0),
                PlaceLabelCandidate("dorf", 10.01, 50.01),
                PlaceLabelCandidate("DORF", 10.02, 50.02),
            )
        val result = selectPlaceLabels(candidates, excludedNames = emptySet(), maxCount = 10)
        assertEquals(1, result.size)
    }

    @Test
    fun selectPlaceLabels_dropsExcludedNamesCaseInsensitive() {
        val candidates = listOf(PlaceLabelCandidate("Bayern", 11.0, 49.0), PlaceLabelCandidate("Kleindorf", 11.0, 49.0))
        val result = selectPlaceLabels(candidates, excludedNames = setOf("bayern"), maxCount = 10)
        assertEquals(listOf("Kleindorf"), result.map { it.name })
    }

    @Test
    fun selectPlaceLabels_dropsBlankNames() {
        val candidates = listOf(PlaceLabelCandidate("   ", 11.0, 49.0), PlaceLabelCandidate("Kleindorf", 11.0, 49.0))
        val result = selectPlaceLabels(candidates, excludedNames = emptySet(), maxCount = 10)
        assertEquals(listOf("Kleindorf"), result.map { it.name })
    }

    @Test
    fun selectPlaceLabels_respectsMaxCount() {
        val candidates = (1..100).map { PlaceLabelCandidate("Ort $it", 11.0, 49.0) }
        val result = selectPlaceLabels(candidates, excludedNames = emptySet(), maxCount = 40)
        assertEquals(40, result.size)
    }

    // ── haversineKm ──────────────────────────────────────────────────────────────────────────

    @Test
    fun haversineKm_samePoint_isZero() {
        assertEquals(0.0, haversineKm(52.5, 13.4, 52.5, 13.4), 0.0001)
    }

    @Test
    fun haversineKm_berlinToHamburg_isRoughlyTwoHundredFiftyKm() {
        // Berlin (52.52, 13.405) -> Hamburg (53.55, 10.0), real great-circle distance ~255 km.
        val distance = haversineKm(52.52, 13.405, 53.55, 10.0)
        assertTrue(distance in 240.0..270.0, "expected ~255 km, was $distance")
    }
}
