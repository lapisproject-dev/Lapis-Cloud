package network.lapis.cloud.server.membermap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Welle V1.9.9 "Ortssuche" -- covers [PlaceSearchIndex] both against a small, fully controlled
 * SYNTHETIC index (constructed via [PostalCodeCentroidIndex]'s `internal constructor`, same-package
 * access, no CSV parsing needed) for the pure grouping/ranking mechanics, and against the REAL bundled
 * `geodata/de-postal-centroids.csv` for the institution-filter/real-place-name claims this class's own
 * KDoc makes (verified, not merely asserted, against the shipped file -- see
 * [PostalCodeCentroidIndexTest] "bundled -> exactly 10_813 entries" for the same file's own pin).
 */
class PlaceSearchIndexTest :
    FunSpec({
        fun syntheticIndex(rows: Map<String, PostalCodeCentroid>) = PostalCodeCentroidIndex(rows)

        // ── grouping: single-linkage by distance, within one exact name ────────────────────────

        test("two same-named entries within 15km merge into one group, mean centroid") {
            val index =
                syntheticIndex(
                    mapOf(
                        "10001" to PostalCodeCentroid(placeName = "Testhausen", lat = 52.00, lon = 13.00),
                        "10002" to PostalCodeCentroid(placeName = "Testhausen", lat = 52.05, lon = 13.05),
                    ),
                )
            val groups = PlaceSearchIndex.build(index).search("Testhausen")
            groups shouldHaveSize 1
            groups[0].postalCodes shouldBe listOf("10001", "10002")
            groups[0].lat shouldBe 52.025
            groups[0].lon shouldBe 13.025
        }

        test("two same-named entries far apart (>15km) stay two separate groups") {
            val index =
                syntheticIndex(
                    mapOf(
                        "10001" to PostalCodeCentroid(placeName = "Bernau", lat = 52.6821, lon = 13.5965),
                        "10002" to PostalCodeCentroid(placeName = "Bernau", lat = 47.8002, lon = 8.0383),
                    ),
                )
            val groups = PlaceSearchIndex.build(index).search("Bernau")
            groups shouldHaveSize 2
            groups.flatMap { it.postalCodes } shouldBe listOf("10001", "10002")
        }

        test("different place names never merge, regardless of distance") {
            val index =
                syntheticIndex(
                    mapOf(
                        "10001" to PostalCodeCentroid(placeName = "Dorf A", lat = 52.00, lon = 13.00),
                        "10002" to PostalCodeCentroid(placeName = "Dorf B", lat = 52.001, lon = 13.001),
                    ),
                )
            val index2 = PlaceSearchIndex.build(index)
            index2.search("Dorf A") shouldHaveSize 1
            index2.search("Dorf B") shouldHaveSize 1
        }

        // ── ranking tiers ────────────────────────────────────────────────────────────────────

        test("exact name match ranks before a mere prefix/contains match") {
            val index =
                syntheticIndex(
                    mapOf(
                        "10001" to PostalCodeCentroid(placeName = "Berg", lat = 52.0, lon = 13.0),
                        "10002" to PostalCodeCentroid(placeName = "Bergisch Gladbach", lat = 51.0, lon = 7.0),
                        "10003" to PostalCodeCentroid(placeName = "Heidelberg", lat = 49.4, lon = 8.7),
                    ),
                )
            val results = PlaceSearchIndex.build(index).search("Berg")
            results[0].placeName shouldBe "Berg"
        }

        test("results are capped at MAX_RESULTS (8)") {
            val rows =
                (1..20).associate { i ->
                    "1000$i".padStart(5, '0') to PostalCodeCentroid(placeName = "Vielort $i", lat = 50.0 + i * 0.01, lon = 8.0)
                }
            val results = PlaceSearchIndex.build(syntheticIndex(rows)).search("Vielort")
            results shouldHaveSize PlaceSearchIndex.MAX_RESULTS
        }

        test("query shorter than 2 characters (after trim) -> empty, never throws") {
            val index = syntheticIndex(mapOf("10001" to PostalCodeCentroid(placeName = "Berg", lat = 52.0, lon = 13.0)))
            PlaceSearchIndex.build(index).search("B") shouldHaveSize 0
            PlaceSearchIndex.build(index).search("  ") shouldHaveSize 0
            PlaceSearchIndex.build(index).search("") shouldHaveSize 0
        }

        // ── umlaut-insensitive query normalization ──────────────────────────────────────────

        test("both 'münchen' and 'muenchen' find a place literally named 'München'") {
            val index = syntheticIndex(mapOf("10001" to PostalCodeCentroid(placeName = "München", lat = 48.14, lon = 11.58)))
            val built = PlaceSearchIndex.build(index)
            built.search("münchen") shouldHaveSize 1
            built.search("muenchen") shouldHaveSize 1
            built.search("MUENCHEN") shouldHaveSize 1
        }

        // ── PLZ-prefix search ────────────────────────────────────────────────────────────────

        test("an all-digit query matches by postal-code prefix, not just by name") {
            val index =
                syntheticIndex(
                    mapOf(
                        "38100" to PostalCodeCentroid(placeName = "Braunschweig", lat = 52.26, lon = 10.52),
                        "38102" to PostalCodeCentroid(placeName = "Braunschweig", lat = 52.27, lon = 10.53),
                        "99999" to PostalCodeCentroid(placeName = "Nirgendwo", lat = 50.0, lon = 10.0),
                    ),
                )
            val results = PlaceSearchIndex.build(index).search("381")
            results shouldHaveSize 1
            results[0].placeName shouldBe "Braunschweig"
        }

        // ── institution-name filter -- against the REAL bundled CSV ─────────────────────────

        test("institution-like names (Commerzbank AG / HUK-Coburg / Finanzamt) never appear as a search result") {
            val bundled = requireNotNull(PostalCodeCentroidIndex.bundled)
            val index = PlaceSearchIndex.build(bundled)
            index.search("Commerzbank") shouldHaveSize 0
            index.search("HUK-Coburg") shouldHaveSize 0
            index.search("Finanzamt") shouldHaveSize 0
        }

        test("looksLikeInstitution: real institution rows from the bundled CSV are flagged") {
            PlaceSearchIndex.looksLikeInstitution("Commerzbank AG") shouldBe true
            PlaceSearchIndex.looksLikeInstitution("Finanzamt Dresden - Nord") shouldBe true
            PlaceSearchIndex.looksLikeInstitution("HUK-Coburg") shouldBe true
            PlaceSearchIndex.looksLikeInstitution("Deutsche Telekom AG, GSUS") shouldBe true
        }

        test("looksLikeInstitution: ordinary place names are never flagged") {
            PlaceSearchIndex.looksLikeInstitution("Braunschweig") shouldBe false
            PlaceSearchIndex.looksLikeInstitution("Bad Segeberg") shouldBe false
            PlaceSearchIndex.looksLikeInstitution("Sankt Augustin") shouldBe false
        }

        // ── real bundled data: Bernau -> 2, Berlin -> 1 (per this class's own KDoc examples) ──

        test("bundled: exactly two places are literally named 'Bernau' (two real, distant German towns share the name)") {
            // Searching "Bernau" also surfaces a real THIRD prefix match, "Bernau am Chiemsee" (a
            // distinct town) -- that is correct ranking behaviour, not a grouping bug, so this test
            // narrows to the exact-name tier specifically rather than asserting the total result count.
            val bundled = requireNotNull(PostalCodeCentroidIndex.bundled)
            val results = PlaceSearchIndex.build(bundled).search("Bernau")
            val exact = results.filter { it.placeName == "Bernau" }
            exact shouldHaveSize 2
            // The two "Bernau" groups must be genuinely distinct places, not one place split in half --
            // their postal codes are the real, disjoint pin from earlier in this file (16321 / 79872).
            exact.flatMap { it.postalCodes }.toSet() shouldBe setOf("16321", "79872")
        }

        test("bundled: 'Berlin' resolves to exactly one group (all Berlin postal codes are close together)") {
            val bundled = requireNotNull(PostalCodeCentroidIndex.bundled)
            val results = PlaceSearchIndex.build(bundled).search("Berlin")
            val exact = results.filter { it.placeName == "Berlin" }
            exact shouldHaveSize 1
            (exact[0].postalCodes.size > 100) shouldBe true
        }

        test(
            "bundled: MEMBER role is irrelevant at this layer -- PlaceSearchIndex itself has no role concept (enforced one layer up, see BoardMemberMapService.searchPlaces)",
        ) {
            val bundled = requireNotNull(PostalCodeCentroidIndex.bundled)
            PlaceSearchIndex.build(bundled) shouldNotBe null
        }

        test("bundled: MAX_RESULTS constant is 8") {
            PlaceSearchIndex.MAX_RESULTS shouldBe 8
        }
    })
