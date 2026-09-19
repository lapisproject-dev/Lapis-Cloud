package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput

/**
 * Die EINE Stelle, an der ein bereits geladenes [OrganizationSettingsDto] in das
 * wholesale-replace-[OrganizationSettingsInput] übersetzt wird (Audit-Fund M2).
 *
 * **Warum das eine eigene Funktion ist und keine drei handgepflegten Kopien:**
 * `IOrganizationSettingsService.updateOrganizationSettings` ersetzt die einzige
 * `organization_settings`-Zeile vollständig -- es gibt kein Teil-Update. Jeder Bildschirm, der genau
 * EIN Feld ändern will, muss deshalb alle übrigen Felder unverändert mitschicken. Das wurde dreimal
 * von Hand abgeschrieben (`LedgerScreen.toInputWithPaymentAccountMapping`,
 * `NonprofitComplianceReportsScreen.toInputWithKleinunternehmerFlag`,
 * `PoliticianScreen.toInputWithPoliticianRankingEnabled`), und genau diese Duplikation hat
 * **fünfmal** dieselbe Fehlerklasse erzeugt: ein neues Feld wurde in einer der Kopien vergessen und
 * beim nächsten unbeteiligten Speichern still auf seinen Kotlin-Default zurückgesetzt
 * (`donationIncomeAccountId`, `eventIncomeAccountId`/`eventIncomeSphere`, `travelExpenseAccountId`,
 * `isKleinunternehmer`, zuletzt `receivablesAccountId`/`payablesAccountId`/
 * `receivableDunningEnabled` in V1.4.21). Mit dieser Funktion gibt es nur noch eine Stelle, die ein
 * neues Feld kennen muss -- die drei Bildschirme schreiben `toInput().copy(<das eine Feld>)`.
 *
 * **`vatEnabled`, `dunningEnabled`, `postalMailEnabled`-Nachbarn:** [OrganizationSettingsInput] hat
 * für `vatEnabled`/`dunningEnabled` bewusst KEIN Feld (siehe deren KDoc -- nur über `IVatService`/
 * `IDunningService` schaltbar). Was diese Funktion nicht abbilden kann, kann der generische
 * Update-Pfad auch nicht überschreiben; das ist Absicht und keine Lücke.
 *
 * Liegt in `lapis-client` (nicht in `lapis-shared`), weil ausschließlich die drei Client-Bildschirme
 * wholesale ersetzen: der Server baut `OrganizationSettingsInput` nie selbst.
 *
 * Geprüft von `OrganizationSettingsInputMappingTest`: ein [OrganizationSettingsDto], in dem JEDES
 * abbildbare Feld einen von seinem Default verschiedenen Wert trägt, muss Feld für Feld
 * durchkommen -- `kotlin-reflect` gibt es auf JS nicht, deshalb die vollständig belegte Vorlage
 * statt einer reflektiven Feldaufzählung (gleiche Hausregel wie
 * `OrganizationSettingsFieldCoverageTest` auf der Serverseite).
 */
internal fun OrganizationSettingsDto.toInput(): OrganizationSettingsInput =
    OrganizationSettingsInput(
        name = name,
        street = street,
        postalCode = postalCode,
        city = city,
        country = country,
        bankIban = bankIban,
        bankBic = bankBic,
        taxExemptionAuthority = taxExemptionAuthority,
        taxExemptionDate = taxExemptionDate,
        isPoliticalParty = isPoliticalParty,
        postalMailEnabled = postalMailEnabled,
        politicianRankingEnabled = politicianRankingEnabled,
        paymentBankAccountId = paymentBankAccountId,
        paymentFeeAccountId = paymentFeeAccountId,
        contributionIncomeAccountId = contributionIncomeAccountId,
        donationIncomeAccountId = donationIncomeAccountId,
        eventIncomeAccountId = eventIncomeAccountId,
        eventIncomeSphere = eventIncomeSphere,
        datevBeraterNummer = datevBeraterNummer,
        datevMandantNummer = datevMandantNummer,
        travelExpenseAccountId = travelExpenseAccountId,
        volunteerAllowanceAccountId = volunteerAllowanceAccountId,
        isKleinunternehmer = isKleinunternehmer,
        receivablesAccountId = receivablesAccountId,
        payablesAccountId = payablesAccountId,
        receivableDunningEnabled = receivableDunningEnabled,
    )
