package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.291 (R6) -- retention (both years must be past), comment rules, year status, the typed number and the fixed declaration text. */
class PartgVersionRulesTest {
    @Test
    fun retentionNeedsBothYearsMoreThanNYearsBack() {
        val today = LocalDate(2037, 3, 1)
        // 2026 + 10 = 2036 < 2037: eligible when kept in 2026 too
        assertTrue(PartgVersionRetention.eligible(fiscalYear = 2026, frozenAtYear = 2026, today = today, n = 10))
        // the report year is old enough but the version was kept recently: NOT eligible
        assertFalse(PartgVersionRetention.eligible(fiscalYear = 2026, frozenAtYear = 2030, today = today, n = 10))
        assertFalse(PartgVersionRetention.eligible(fiscalYear = 2026, frozenAtYear = 2027, today = today, n = 10))
        // exactly N years back is not enough
        assertFalse(PartgVersionRetention.eligible(fiscalYear = 2027, frozenAtYear = 2027, today = today, n = 10))
        assertTrue(PartgVersionRetention.eligible(fiscalYear = 2026, frozenAtYear = 2026, today = LocalDate(2037, 1, 1), n = 10))
        assertFalse(PartgVersionRetention.eligible(fiscalYear = 2026, frozenAtYear = 2026, today = LocalDate(2036, 12, 31), n = 10))
    }

    @Test
    fun retentionRange() {
        assertFalse(PartgVersionRetention.validYears(9))
        assertTrue(PartgVersionRetention.validYears(10))
        assertTrue(PartgVersionRetention.validYears(30))
        assertFalse(PartgVersionRetention.validYears(31))
    }

    @Test
    fun commentIsTrimmedBoundedAndFreeOfControlCharacters() {
        assertEquals(PartgVersionRules.CommentCheck(ok = true, value = null), PartgVersionRules.checkComment(null))
        assertEquals(PartgVersionRules.CommentCheck(ok = true, value = null), PartgVersionRules.checkComment("   "))
        assertEquals(PartgVersionRules.CommentCheck(ok = true, value = "Vorstand"), PartgVersionRules.checkComment("  Vorstand "))
        assertTrue(PartgVersionRules.checkComment("a".repeat(500)).ok)
        assertFalse(PartgVersionRules.checkComment("a".repeat(501)).ok)
        assertFalse(PartgVersionRules.checkComment("zeile1\nzeile2").ok)
        assertFalse(PartgVersionRules.checkComment("tab\there").ok)
        assertFalse(PartgVersionRules.checkComment("nul\u0000").ok)
    }

    @Test
    fun theYearStatusIsTheHighestWholePartyStatus() {
        assertEquals(PartgVersionStatus.DRAFT, PartgVersionRules.yearStatus(emptyList()))
        assertEquals(PartgVersionStatus.FROZEN, PartgVersionRules.yearStatus(listOf(PartgVersionStatus.FROZEN)))
        assertEquals(
            PartgVersionStatus.FINAL,
            PartgVersionRules.yearStatus(listOf(PartgVersionStatus.FROZEN, PartgVersionStatus.FINAL, PartgVersionStatus.DECLARED)),
        )
    }

    @Test
    fun theTypedNumberMustMatchExactly() {
        assertTrue(PartgVersionRules.matchesTypedNumber(typed = 3, versionNumber = 3))
        assertFalse(PartgVersionRules.matchesTypedNumber(typed = 2, versionNumber = 3))
    }

    @Test
    fun hashShape() {
        assertTrue(PartgVersionRules.isHash("a".repeat(64)))
        assertFalse(PartgVersionRules.isHash("A".repeat(64)))
        assertFalse(PartgVersionRules.isHash("a".repeat(63)))
    }

    @Test
    fun theDeclarationTextIsFixedPerCodeAndFillsInVersionAndYear() {
        val text = PartgDeclarationTemplate.text(code = PartgDeclarationTemplate.CODE, versionNumber = 4, year = 2026)!!
        assertTrue("Version 4" in text)
        assertTrue("2026" in text)
        assertTrue("keine qualifizierte" in text)
        assertNull(PartgDeclarationTemplate.text(code = "UNKNOWN", versionNumber = 1, year = 2026))
    }
}
