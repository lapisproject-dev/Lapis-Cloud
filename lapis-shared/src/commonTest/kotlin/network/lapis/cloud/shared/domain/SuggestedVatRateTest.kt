package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.4.13 "USt-Voranmeldung" -- [suggestedVatRate]/[VatRate] pure logic. */
class SuggestedVatRateTest {
    @Test
    fun suggestedVatRate_ideellerBereich_isNotSubject() {
        assertEquals(VatRate.NOT_SUBJECT, suggestedVatRate(GemeinnuetzigkeitSphere.IDEELLER_BEREICH))
    }

    @Test
    fun suggestedVatRate_vermoegensverwaltung_isNotSubject() {
        assertEquals(VatRate.NOT_SUBJECT, suggestedVatRate(GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG))
    }

    @Test
    fun suggestedVatRate_zweckbetrieb_isReduced() {
        assertEquals(VatRate.REDUCED, suggestedVatRate(GemeinnuetzigkeitSphere.ZWECKBETRIEB))
    }

    @Test
    fun suggestedVatRate_wirtschaftlicherGeschaeftsbetrieb_isStandard() {
        assertEquals(VatRate.STANDARD, suggestedVatRate(GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB))
    }

    @Test
    fun percent_matchesEveryLiteral() {
        assertEquals(0, VatRate.UNCLASSIFIED.percent)
        assertEquals(0, VatRate.NOT_SUBJECT.percent)
        assertEquals(0, VatRate.ZERO.percent)
        assertEquals(7, VatRate.REDUCED.percent)
        assertEquals(19, VatRate.STANDARD.percent)
    }

    @Test
    fun bearsVat_trueOnlyForReducedAndStandard() {
        assertFalse(VatRate.UNCLASSIFIED.bearsVat)
        assertFalse(VatRate.NOT_SUBJECT.bearsVat)
        assertFalse(VatRate.ZERO.bearsVat)
        assertTrue(VatRate.REDUCED.bearsVat)
        assertTrue(VatRate.STANDARD.bearsVat)
    }

    @Test
    fun isTaxable_trueForZeroReducedStandard_falseForUnclassifiedAndNotSubject() {
        assertFalse(VatRate.UNCLASSIFIED.isTaxable)
        assertFalse(VatRate.NOT_SUBJECT.isTaxable)
        assertTrue(VatRate.ZERO.isTaxable)
        assertTrue(VatRate.REDUCED.isTaxable)
        assertTrue(VatRate.STANDARD.isTaxable)
    }

    @Test
    fun entries_orderIsLoadBearing() {
        // Schema-drift test (AccountingSchemaDriftTest) pins this exact order against the kUML
        // model's enumOf literal declaration order -- changing this order is a migration-shaped
        // change, not a refactor.
        assertEquals(
            listOf(VatRate.UNCLASSIFIED, VatRate.NOT_SUBJECT, VatRate.ZERO, VatRate.REDUCED, VatRate.STANDARD),
            VatRate.entries,
        )
    }
}
