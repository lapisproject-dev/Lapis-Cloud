package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit-Fund M2 -- die Regressionsklammer um [toInput], die dreimal gefehlt hat.
 *
 * Die drei wholesale-replace-Helfer der Bildschirme bauen [OrganizationSettingsInput] nicht mehr
 * selbst; dieser Test prüft die EINE gemeinsame Abbildung Feld für Feld. `kotlin-reflect` gibt es auf
 * JS nicht (Hausregel, siehe `OrganizationSettingsFieldCoverageTest` KDoc), deshalb der Weg über
 * [fullyPopulated]: ein DTO, in dem **jedes** abbildbare Feld einen von seinem Kotlin-Default
 * verschiedenen Wert trägt. Ein vergessenes Feld in [toInput] fällt damit auf den Default zurück und
 * genau diese Abweichung schlägt hier fehl -- anders als bei einem DTO mit lauter Default-Werten,
 * das auch mit Bug grün bliebe.
 */
class OrganizationSettingsInputMappingTest {
    private val fullyPopulated =
        OrganizationSettingsDto(
            id = "org-1",
            name = "Voll belegter Testverein e.V.",
            street = "Vereinsstrasse 1",
            postalCode = "38100",
            city = "Braunschweig",
            country = "Deutschland",
            bankIban = "DE02120300000000202051",
            bankBic = "BYLADEM1001",
            taxExemptionAuthority = "Finanzamt Braunschweig-Wilhelmstrasse",
            taxExemptionDate = LocalDate(2025, 1, 15),
            isPoliticalParty = true,
            postalMailEnabled = true,
            politicianRankingEnabled = true,
            paymentBankAccountId = "bank-1",
            paymentFeeAccountId = "fee-1",
            contributionIncomeAccountId = "contribution-1",
            donationIncomeAccountId = "donation-1",
            eventIncomeAccountId = "event-1",
            // Non-default literal on purpose (the DTO default is ZWECKBETRIEB).
            eventIncomeSphere = GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB,
            datevBeraterNummer = 1001,
            datevMandantNummer = 42,
            travelExpenseAccountId = "travel-1",
            volunteerAllowanceAccountId = "volunteer-1",
            isKleinunternehmer = true,
            receivablesAccountId = "receivables-1",
            payablesAccountId = "payables-1",
            receivableDunningEnabled = true,
        )

    @Test
    fun toInput_carriesEveryMappableFieldThrough() {
        val input = fullyPopulated.toInput()
        assertEquals("Voll belegter Testverein e.V.", input.name)
        assertEquals("Vereinsstrasse 1", input.street)
        assertEquals("38100", input.postalCode)
        assertEquals("Braunschweig", input.city)
        assertEquals("Deutschland", input.country)
        assertEquals("DE02120300000000202051", input.bankIban)
        assertEquals("BYLADEM1001", input.bankBic)
        assertEquals("Finanzamt Braunschweig-Wilhelmstrasse", input.taxExemptionAuthority)
        assertEquals(LocalDate(2025, 1, 15), input.taxExemptionDate)
        assertEquals(true, input.isPoliticalParty)
        assertEquals(true, input.postalMailEnabled)
        assertEquals(true, input.politicianRankingEnabled)
        assertEquals("bank-1", input.paymentBankAccountId)
        assertEquals("fee-1", input.paymentFeeAccountId)
        assertEquals("contribution-1", input.contributionIncomeAccountId)
        assertEquals("donation-1", input.donationIncomeAccountId)
        assertEquals("event-1", input.eventIncomeAccountId)
        assertEquals(GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB, input.eventIncomeSphere)
        assertEquals(1001, input.datevBeraterNummer)
        assertEquals(42, input.datevMandantNummer)
        assertEquals("travel-1", input.travelExpenseAccountId)
        assertEquals("volunteer-1", input.volunteerAllowanceAccountId)
        assertEquals(true, input.isKleinunternehmer)
        assertEquals("receivables-1", input.receivablesAccountId)
        assertEquals("payables-1", input.payablesAccountId)
        assertEquals(true, input.receivableDunningEnabled)
    }

    /**
     * Zweite, unabhängige Absicherung derselben Invariante, die einen Feld-Verlust auch dann findet,
     * wenn die Liste oben beim nächsten neuen Feld nicht erweitert wird: das vollständige Ergebnis von
     * [toInput] muss Wert für Wert dem entsprechen, was ein von Hand geschriebenes
     * [OrganizationSettingsInput] mit denselben Werten ergibt. `data class`-`equals` vergleicht ALLE
     * Felder -- auch die, die dieser Test nicht namentlich kennt.
     */
    @Test
    fun toInput_equalsTheHandWrittenInputWithTheSameValues() {
        val expected =
            OrganizationSettingsInput(
                name = "Voll belegter Testverein e.V.",
                street = "Vereinsstrasse 1",
                postalCode = "38100",
                city = "Braunschweig",
                country = "Deutschland",
                bankIban = "DE02120300000000202051",
                bankBic = "BYLADEM1001",
                taxExemptionAuthority = "Finanzamt Braunschweig-Wilhelmstrasse",
                taxExemptionDate = LocalDate(2025, 1, 15),
                isPoliticalParty = true,
                postalMailEnabled = true,
                politicianRankingEnabled = true,
                paymentBankAccountId = "bank-1",
                paymentFeeAccountId = "fee-1",
                contributionIncomeAccountId = "contribution-1",
                donationIncomeAccountId = "donation-1",
                eventIncomeAccountId = "event-1",
                eventIncomeSphere = GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB,
                datevBeraterNummer = 1001,
                datevMandantNummer = 42,
                travelExpenseAccountId = "travel-1",
                volunteerAllowanceAccountId = "volunteer-1",
                isKleinunternehmer = true,
                receivablesAccountId = "receivables-1",
                payablesAccountId = "payables-1",
                receivableDunningEnabled = true,
            )
        assertEquals(expected, fullyPopulated.toInput())
    }

    /** Keine stille Umdeutung von `null`/`false`: ein leeres DTO bleibt leer. */
    @Test
    fun toInput_onAnEmptyDto_keepsEveryFieldAtItsDefault() {
        val empty =
            OrganizationSettingsDto(
                id = "org-2",
                name = "Leerer Verein e.V.",
                street = null,
                postalCode = null,
                city = null,
                country = null,
                bankIban = null,
                bankBic = null,
                taxExemptionAuthority = null,
                taxExemptionDate = null,
            )
        assertEquals(OrganizationSettingsInput(name = "Leerer Verein e.V."), empty.toInput())
    }

    // ── Die drei Aufrufer: genau EIN Feld ändert sich, alles andere bleibt ───────────────────────

    @Test
    fun toInputWithPoliticianRankingEnabled_changesOnlyThatFlag() {
        val input = fullyPopulated.toInputWithPoliticianRankingEnabled(newValue = false)
        assertEquals(false, input.politicianRankingEnabled)
        assertEquals(fullyPopulated.toInput().copy(politicianRankingEnabled = false), input)
    }

    @Test
    fun toInputWithKleinunternehmerFlag_changesOnlyThatFlag() {
        val input = fullyPopulated.toInputWithKleinunternehmerFlag(newValue = false)
        assertEquals(false, input.isKleinunternehmer)
        assertEquals(fullyPopulated.toInput().copy(isKleinunternehmer = false), input)
    }

    @Test
    fun toInputWithPaymentAccountMapping_changesOnlyTheFieldsThatScreenEdits() {
        val input =
            fullyPopulated.toInputWithPaymentAccountMapping(
                paymentBankAccountId = "bank-9",
                paymentFeeAccountId = "fee-9",
                contributionIncomeAccountId = "contribution-9",
                donationIncomeAccountId = "donation-9",
                eventIncomeAccountId = "event-9",
                eventIncomeSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                datevBeraterNummer = 2002,
                datevMandantNummer = 7,
                travelExpenseAccountId = "travel-9",
                volunteerAllowanceAccountId = "volunteer-9",
                receivablesAccountId = "receivables-9",
                payablesAccountId = "payables-9",
            )
        assertEquals(
            fullyPopulated.toInput().copy(
                paymentBankAccountId = "bank-9",
                paymentFeeAccountId = "fee-9",
                contributionIncomeAccountId = "contribution-9",
                donationIncomeAccountId = "donation-9",
                eventIncomeAccountId = "event-9",
                eventIncomeSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                datevBeraterNummer = 2002,
                datevMandantNummer = 7,
                travelExpenseAccountId = "travel-9",
                volunteerAllowanceAccountId = "volunteer-9",
                receivablesAccountId = "receivables-9",
                payablesAccountId = "payables-9",
            ),
            input,
        )
        // Die zwei Felder ohne Formularfeld auf diesem Bildschirm -- genau die, die der Bug verlor.
        assertEquals(true, input.isKleinunternehmer)
        assertEquals(true, input.receivableDunningEnabled)
    }
}
