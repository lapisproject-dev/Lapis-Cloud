package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors `AnniversaryCalendarTest`'s conventions: plain `kotlin.test`, no Kotest (JVM-only in this codebase, see `lapis-shared/build.gradle.kts`). */
class MemberMapRulesTest {
    @Test
    fun radiusPx_oneMemberCapTwentyEight_isSeven() {
        assertEquals(7.0, MemberMapRules.radiusPx(n = 1, capPx = MemberMapRules.POINT_RADIUS_CAP_PX))
    }

    @Test
    fun radiusPx_twentyFiveMembersCapTwentyEight_isNineteen() {
        assertEquals(19.0, MemberMapRules.radiusPx(n = 25, capPx = MemberMapRules.POINT_RADIUS_CAP_PX))
    }

    @Test
    fun radiusPx_neverExceedsPointCap() {
        assertEquals(
            MemberMapRules.POINT_RADIUS_CAP_PX,
            MemberMapRules.radiusPx(n = 100_000, capPx = MemberMapRules.POINT_RADIUS_CAP_PX),
        )
    }

    @Test
    fun radiusPx_neverExceedsClusterCap() {
        assertEquals(
            MemberMapRules.CLUSTER_RADIUS_CAP_PX,
            MemberMapRules.radiusPx(n = 100_000, capPx = MemberMapRules.CLUSTER_RADIUS_CAP_PX),
        )
    }

    @Test
    fun isGermanPostalCode_exactlyFiveDigits_trimmed() {
        assertTrue(MemberMapRules.isGermanPostalCode(" 38100 "))
        assertTrue(MemberMapRules.isGermanPostalCode("38100"))
    }

    @Test
    fun isGermanPostalCode_rejectsWrongShapeOrMissing() {
        assertFalse(MemberMapRules.isGermanPostalCode("3810"))
        assertFalse(MemberMapRules.isGermanPostalCode("381000"))
        assertFalse(MemberMapRules.isGermanPostalCode("38100a"))
        assertFalse(MemberMapRules.isGermanPostalCode(null))
        assertFalse(MemberMapRules.isGermanPostalCode("   "))
    }

    @Test
    fun isGermanCountry_blankOrNull_countsAsGermany() {
        assertTrue(MemberMapRules.isGermanCountry(null))
        assertTrue(MemberMapRules.isGermanCountry(""))
        assertTrue(MemberMapRules.isGermanCountry("   "))
    }

    @Test
    fun isGermanCountry_recognizedSpellings_caseAndWhitespaceInsensitive() {
        for (spelling in listOf("DE", "de", "Deu", "D", "Deutschland", " deutschland ", "GERMANY", "BRD", "Bundesrepublik Deutschland")) {
            assertTrue(MemberMapRules.isGermanCountry(spelling), "expected '$spelling' to count as Germany")
        }
    }

    @Test
    fun isGermanCountry_anyOtherSpelling_countsAsForeign() {
        assertFalse(MemberMapRules.isGermanCountry("Österreich"))
        assertFalse(MemberMapRules.isGermanCountry("France"))
        assertFalse(MemberMapRules.isGermanCountry("Schweiz"))
    }
}
