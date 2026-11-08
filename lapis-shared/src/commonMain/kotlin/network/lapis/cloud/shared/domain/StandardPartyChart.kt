package network.lapis.cloud.shared.domain

/**
 * V1.9.289 (R4a) -- one proposed account of the [StandardPartyChart] together with the report position it would be mapped to.
 * [suggestedPosition] is a LEAF code of [PartgPositions] (or `null`); [suggestExcluded] marks an account that is usually NOT part of the
 * report (equity carry-forward). Both are only a PROPOSAL shown in the "Kontenzuordnung" tab, labelled "aus Parteien-Kontenplan"; nothing is
 * mapped until a person saves it.
 */
data class StandardPartyChartAccount(
    val account: StandardChartAccount,
    val suggestedPosition: String?,
    val suggestExcluded: Boolean = false,
)

/**
 * V1.9.289 (R4a). A proposal for a small chart of accounts for a political party, built on the SKR42 association chart. NOT a verified DATEV
 * chart and NOT an approved party chart: the numbers, names and position suggestions are assumptions (confidence LOW or MED) and a tax advisor
 * or auditor must confirm them before the party relies on them (open question Q17/Q18 in `docs/features/partg-report.adoc`).
 *
 * Rule: a number that is also in [StandardAssociationChart] has the SAME name, type and class there, so the two templates never collide.
 * Party-specific accounts live in numbers the association chart does not use. Pure data.
 */
object StandardPartyChart {
    const val VERSION: String = "partei-skr42-2026-11"

    private fun shared(
        number: String,
        position: String?,
        excluded: Boolean = false,
    ): StandardPartyChartAccount =
        StandardPartyChartAccount(
            account = StandardAssociationChart.accounts.single { it.accountNumber == number },
            suggestedPosition = position,
            suggestExcluded = excluded,
        )

    private fun own(
        number: String,
        name: String,
        accountClass: Int,
        type: LedgerAccountType,
        position: String?,
        confidence: ChartConfidence = ChartConfidence.LOW,
        hint: String = "Wird je Buchung gewählt",
    ): StandardPartyChartAccount =
        StandardPartyChartAccount(
            account =
                StandardChartAccount(
                    accountNumber = number,
                    name = name,
                    accountClass = accountClass,
                    type = type,
                    confidence = confidence,
                    sphereHint = hint,
                ),
            suggestedPosition = position,
        )

    val accounts: List<StandardPartyChartAccount> =
        listOf(
            own(number = "01000", name = "Haus- und Grundvermögen", accountClass = 0, type = LedgerAccountType.ASSET, position = "BP01"),
            shared(number = "06500", position = "BP02"),
            own(
                number = "07000",
                name = "Beteiligungen an Unternehmen",
                accountClass = 0,
                type = LedgerAccountType.ASSET,
                position = "BP03",
            ),
            own(number = "07500", name = "Sonstige Finanzanlagen", accountClass = 0, type = LedgerAccountType.ASSET, position = "BP04"),
            shared(number = "12000", position = "BP08"),
            own(
                number = "12500",
                name = "Forderungen an Gliederungen",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                position = "BP05",
            ),
            own(
                number = "12600",
                name = "Forderungen aus der staatlichen Teilfinanzierung",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                position = "BP06",
            ),
            own(
                number = "13000",
                name = "Sonstige Vermögensgegenstände",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                position = "BP08",
            ),
            shared(number = "16000", position = "BP07"),
            shared(number = "18000", position = "BP07"),
            shared(number = "20000", position = null, excluded = true),
            own(
                number = "30000",
                name = "Rückstellungen für Pensionsverpflichtungen",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                position = "SP01",
            ),
            own(
                number = "30500",
                name = "Sonstige Rückstellungen",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                position = "SP02",
            ),
            own(
                number = "31000",
                name = "Rückzahlungsverpflichtungen aus der staatlichen Teilfinanzierung",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                position = "SP03",
            ),
            own(
                number = "32000",
                name = "Verbindlichkeiten gegenüber Gebietsverbänden",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                position = "SP04",
            ),
            own(
                number = "33000",
                name = "Verbindlichkeiten gegenüber Kreditinstituten",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                position = "SP05",
            ),
            own(
                number = "33500",
                name = "Verbindlichkeiten gegenüber sonstigen Darlehensgebern",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                position = "SP06",
            ),
            shared(number = "34000", position = "SP07"),
            shared(number = "37500", position = "SP07"),
            shared(number = "40000", position = "E01"),
            own(
                number = "40100",
                name = "Mandatsträgerbeiträge und ähnliche regelmäßige Beiträge",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                position = "E02",
                confidence = ChartConfidence.MED,
            ),
            shared(number = "40450", position = "E03"),
            own(
                number = "40460",
                name = "Spenden von juristischen Personen",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                position = "E04",
                confidence = ChartConfidence.MED,
            ),
            own(
                number = "41000",
                name = "Erlöse aus Veranstaltungen und Vertrieb von Druckschriften",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                position = "E07",
            ),
            shared(number = "42010", position = "E07"),
            shared(number = "44000", position = "E05"),
            own(
                number = "44500",
                name = "Einnahmen aus Beteiligungen",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                position = "E05A",
            ),
            own(
                number = "45000",
                name = "Staatliche Mittel (Teilfinanzierung)",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                position = "E08",
                confidence = ChartConfidence.MED,
            ),
            own(number = "46000", name = "Sonstige Einnahmen", accountClass = 4, type = LedgerAccountType.INCOME, position = "E09"),
            own(number = "47000", name = "Zuschüsse von Gliederungen", accountClass = 4, type = LedgerAccountType.INCOME, position = "E10"),
            shared(number = "50000", position = "A02F"),
            own(
                number = "60000",
                name = "Ausgaben für Personal",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                position = "A01",
                confidence = ChartConfidence.MED,
            ),
            own(
                number = "61000",
                name = "Ausgaben für allgemeine politische Arbeit",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                position = "A02B",
            ),
            own(
                number = "61500",
                name = "Ausgaben für Wahlkämpfe",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                position = "A02C",
                confidence = ChartConfidence.MED,
            ),
            shared(number = "63000", position = "A02A"),
            shared(number = "64000", position = "A02A"),
            shared(number = "64200", position = "A02G"),
            own(
                number = "65000",
                name = "Ausgaben für die Vermögensverwaltung einschließlich Zinsen",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                position = "A02D",
            ),
            own(number = "65500", name = "Sonstige Zinsen", accountClass = 6, type = LedgerAccountType.EXPENSE, position = "A02E"),
            own(number = "66000", name = "Zuschüsse an Gliederungen", accountClass = 6, type = LedgerAccountType.EXPENSE, position = "A03"),
            shared(number = "68550", position = "A02A"),
            shared(number = "71100", position = "E06"),
            shared(number = "90000", position = null, excluded = true),
        )

    fun toInput(a: StandardPartyChartAccount): LedgerAccountInput = StandardAssociationChart.toInput(a.account)
}
