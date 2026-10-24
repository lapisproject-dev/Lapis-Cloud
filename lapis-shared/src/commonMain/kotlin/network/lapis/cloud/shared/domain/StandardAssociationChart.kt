package network.lapis.cloud.shared.domain

enum class ChartConfidence { HIGH, MED, LOW }

/**
 * One proposed account of the [StandardAssociationChart]. [sphereHint] is plain explanatory text
 * ("typical area") and deliberately NOT stored on the account: the Gemeinnützigkeit sphere is chosen
 * per posting, never derived from the account. It is shown through `tr()` on the client, so every
 * literal must exist in the message catalog.
 */
data class StandardChartAccount(
    val accountNumber: String,
    val name: String,
    val accountClass: Int,
    val type: LedgerAccountType,
    val confidence: ChartConfidence,
    val sphereHint: String,
    val isCashRegister: Boolean = false,
    val reserveType: ReserveType? = null,
)

/**
 * V1.9.206. A proposal for a small SKR42 chart of accounts for associations. NOT a verified DATEV
 * chart: the confidence marks are assumptions, and a tax advisor must confirm the chart before an
 * association relies on it. Pure data; `DevSeedData.demoLedgerAccounts` shares the numbers, a test
 * pins that both agree on class, type and flags.
 */
object StandardAssociationChart {
    const val VERSION: String = "skr42-verein-2026-10"

    val accounts: List<StandardChartAccount> =
        listOf(
            acct(
                number = "06500",
                name = "Betriebs- und Geschäftsausstattung",
                accountClass = 0,
                type = LedgerAccountType.ASSET,
                confidence = ChartConfidence.LOW,
                hint = "Alle Bereiche",
            ),
            acct(
                number = "12000",
                name = "Forderungen aus Lieferungen und Leistungen",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                confidence = ChartConfidence.MED,
                hint = "Alle Bereiche",
            ),
            acct(
                number = "16000",
                name = "Kasse",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                confidence = ChartConfidence.HIGH,
                hint = "Alle Bereiche",
                isCashRegister = true,
            ),
            acct(
                number = "18000",
                name = "Bank (Girokonto)",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                confidence = ChartConfidence.HIGH,
                hint = "Alle Bereiche",
            ),
            acct(
                number = "20000",
                name = "Vereinsvermögen / Ergebnisvortrag",
                accountClass = 2,
                type = LedgerAccountType.EQUITY,
                confidence = ChartConfidence.LOW,
                hint = "Kein Bereich",
            ),
            acct(
                number = "21000",
                name = "Projektrücklage (§62 Abs. 1 Nr. 1 AO)",
                accountClass = 2,
                type = LedgerAccountType.EQUITY,
                confidence = ChartConfidence.MED,
                hint = "Ideeller Bereich",
                reserveType = ReserveType.PROJEKTRUECKLAGE,
            ),
            acct(
                number = "21500",
                name = "Freie Rücklage (§62 Abs. 1 Nr. 3 AO)",
                accountClass = 2,
                type = LedgerAccountType.EQUITY,
                confidence = ChartConfidence.MED,
                hint = "Vermögensverwaltung",
                reserveType = ReserveType.FREIE_RUECKLAGE,
            ),
            acct(
                number = "34000",
                name = "Verbindlichkeiten aus Lieferungen und Leistungen",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                confidence = ChartConfidence.MED,
                hint = "Alle Bereiche",
            ),
            acct(
                number = "37500",
                name = "Umsatzsteuer",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
                confidence = ChartConfidence.LOW,
                hint = "Zweckbetrieb und wirtschaftlicher Geschäftsbetrieb",
            ),
            acct(
                number = "40000",
                name = "Echte Mitgliedsbeiträge",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                confidence = ChartConfidence.HIGH,
                hint = "Ideeller Bereich",
            ),
            acct(
                number = "40450",
                name = "Geldzuwendungen (Spenden) gegen Zuwendungsbestätigung",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                confidence = ChartConfidence.HIGH,
                hint = "Ideeller Bereich",
            ),
            acct(
                number = "42010",
                name = "Erlöse aus Eintrittsgeldern (Zweckbetrieb)",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                confidence = ChartConfidence.HIGH,
                hint = "Zweckbetrieb",
            ),
            acct(
                number = "44000",
                name = "Erlöse wirtschaftlicher Geschäftsbetrieb",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
                confidence = ChartConfidence.MED,
                hint = "Wirtschaftlicher Geschäftsbetrieb",
            ),
            acct(
                number = "50000",
                name = "Wareneinsatz / Materialaufwand",
                accountClass = 5,
                type = LedgerAccountType.EXPENSE,
                confidence = ChartConfidence.MED,
                hint = "Wirtschaftlicher Geschäftsbetrieb",
            ),
            acct(
                number = "63000",
                name = "Raumkosten (z. B. Miete)",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                confidence = ChartConfidence.LOW,
                hint = "Wird je Buchung gewählt",
            ),
            acct(
                number = "64000",
                name = "Bürobedarf / Verwaltungsaufwand",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                confidence = ChartConfidence.MED,
                hint = "Wird je Buchung gewählt",
            ),
            acct(
                number = "64200",
                name = "Sonstige Aufwendungen",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                confidence = ChartConfidence.LOW,
                hint = "Wird je Buchung gewählt",
            ),
            acct(
                number = "68550",
                name = "Nebenkosten des Geldverkehrs",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
                confidence = ChartConfidence.MED,
                hint = "Wird je Buchung gewählt",
            ),
            acct(
                number = "71100",
                name = "Zinserträge Bankguthaben",
                accountClass = 7,
                type = LedgerAccountType.INCOME,
                confidence = ChartConfidence.MED,
                hint = "Vermögensverwaltung",
            ),
            acct(
                number = "90000",
                name = "Saldenvorträge Sachkonten / Eröffnungsbilanz",
                accountClass = 9,
                type = LedgerAccountType.EQUITY,
                confidence = ChartConfidence.LOW,
                hint = "Kein Bereich",
            ),
        ).sortedBy { it.accountNumber }

    /** Default account number per mapping step, used by "Standard verwenden". */
    val defaultFor: Map<FinanceSetupStep, String> =
        mapOf(
            FinanceSetupStep.BANK_ACCOUNT to "18000",
            FinanceSetupStep.CONTRIBUTION_INCOME to "40000",
            FinanceSetupStep.DONATION_INCOME to "40450",
            FinanceSetupStep.EVENT_INCOME to "42010",
            FinanceSetupStep.FEE_ACCOUNT to "68550",
        )

    fun toInput(a: StandardChartAccount): LedgerAccountInput =
        LedgerAccountInput(
            accountNumber = a.accountNumber,
            name = a.name,
            accountClass = a.accountClass,
            type = a.type,
            active = true,
            reserveType = a.reserveType,
            isCashRegister = a.isCashRegister,
        )

    private fun acct(
        number: String,
        name: String,
        accountClass: Int,
        type: LedgerAccountType,
        confidence: ChartConfidence,
        hint: String,
        isCashRegister: Boolean = false,
        reserveType: ReserveType? = null,
    ) = StandardChartAccount(
        accountNumber = number,
        name = name,
        accountClass = accountClass,
        type = type,
        confidence = confidence,
        sphereHint = hint,
        isCashRegister = isCashRegister,
        reserveType = reserveType,
    )
}
