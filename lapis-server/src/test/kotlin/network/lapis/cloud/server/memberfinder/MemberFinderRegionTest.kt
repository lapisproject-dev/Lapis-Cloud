package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V1.9.144 -- the region is DERIVED, never stored: German postal code, country decides first (a set `countryCode` is authoritative; only without it the
 * legacy free text counts, and then it must not be blank), the city is never used.
 */
class MemberFinderRegionTest :
    FunSpec({
        test("a German postal code with country code DE gives the key of its first two digits") {
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = "DE", legacyCountry = null) shouldBe "DE-38"
            MemberFinderRegion.keyOf(postalCode = " 10115 ", countryCode = "de", legacyCountry = null) shouldBe "DE-10"
            MemberFinderRegion.labelOf("DE-38") shouldBe "38"
        }

        test("the legacy free text counts only without a country code, and only when it is not blank") {
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = null, legacyCountry = "Deutschland") shouldBe "DE-38"
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = null, legacyCountry = "germany") shouldBe "DE-38"
            // stricter than the member map: blank is NOT Germany here
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = null, legacyCountry = "") shouldBe null
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = null, legacyCountry = "   ") shouldBe null
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = null, legacyCountry = null) shouldBe null
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = null, legacyCountry = "Frankreich") shouldBe null
        }

        test("a set country code beats the legacy free text in both directions") {
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = "DE", legacyCountry = "Frankreich") shouldBe "DE-38"
            MemberFinderRegion.keyOf(postalCode = "38100", countryCode = "FR", legacyCountry = "Deutschland") shouldBe null
            MemberFinderRegion.keyOf(postalCode = "75001", countryCode = "FR", legacyCountry = null) shouldBe null
        }

        test("a postal code that is not exactly five digits gives no region") {
            for (bad in listOf("3810", "381000", "38A00", "", "  ", "38 100")) {
                MemberFinderRegion.keyOf(postalCode = bad, countryCode = "DE", legacyCountry = null) shouldBe null
            }
            MemberFinderRegion.keyOf(postalCode = null, countryCode = "DE", legacyCountry = null) shouldBe null
        }
    })
