package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.FinanceSetupStep
import network.lapis.cloud.shared.domain.FinanceSetupStepState
import network.lapis.cloud.shared.domain.FinanceSetupStepStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.206: the pure parts of the ledger tabs and the setup tab (no DOM). */
class LedgerTabsTest {
    private fun status(open: Boolean) =
        FinanceSetupStatusDto(
            steps =
                FinanceSetupStep.entries.map {
                    FinanceSetupStepStatus(
                        it,
                        if (open && it == FinanceSetupStep.BANK_ACCOUNT) FinanceSetupStepState.OPEN else FinanceSetupStepState.DONE,
                    )
                },
            activeAccountCount = 3,
            mappingComplete = !open,
        )

    private fun acc(
        id: String,
        number: String,
        type: LedgerAccountType,
        active: Boolean = true,
        cash: Boolean = false,
    ) = LedgerAccountDto(id, number, "Konto $number", number.take(1).toInt(), type, active, null, cash)

    @Test
    fun parseLedgerTab_knownUnknownMissing() {
        assertEquals(LedgerTab.SETUP, parseLedgerTab("#/ledger?tab=einrichtung"))
        assertEquals(LedgerTab.JOURNAL, parseLedgerTab("#/ledger?tab=journal&step=bank"))
        assertEquals(LedgerTab.ACCOUNTS, parseLedgerTab("#/ledger?x=1&tab=konten"))
        assertNull(parseLedgerTab("#/ledger?tab=unbekannt"))
        assertNull(parseLedgerTab("#/ledger"))
    }

    @Test
    fun parseSetupStepAnchor_usesTheStepSlug() {
        assertEquals(FinanceSetupStep.BANK_ACCOUNT, parseSetupStepAnchor("#/ledger?tab=einrichtung&step=bank"))
        assertNull(parseSetupStepAnchor("#/ledger?tab=einrichtung&step=nope"))
        assertNull(parseSetupStepAnchor("#/ledger"))
    }

    @Test
    fun defaultLedgerTab_explicitWins_openSetupOpensSetup_boardAndUnknownStatusGoToAccounts() {
        assertEquals(LedgerTab.JOURNAL, defaultLedgerTab(LedgerTab.JOURNAL, status(open = true), hasFinanceRights = true))
        assertEquals(LedgerTab.SETUP, defaultLedgerTab(null, status(open = true), hasFinanceRights = true))
        assertEquals(LedgerTab.ACCOUNTS, defaultLedgerTab(null, status(open = false), hasFinanceRights = true))
        assertEquals(LedgerTab.ACCOUNTS, defaultLedgerTab(null, status(open = true), hasFinanceRights = false))
        assertEquals(LedgerTab.ACCOUNTS, defaultLedgerTab(null, null, hasFinanceRights = true))
    }

    @Test
    fun ledgerHash_hasExactlyOneHash_andAStepOnlyWhenGiven() {
        assertEquals("#/ledger?tab=konten", ledgerHash(LedgerTab.ACCOUNTS))
        assertEquals("#/ledger?tab=einrichtung&step=fee", ledgerHash(LedgerTab.SETUP, FinanceSetupStep.FEE_ACCOUNT))
        assertEquals(1, ledgerHash(LedgerTab.SETUP, FinanceSetupStep.BANK_ACCOUNT).count { it == '#' })
    }

    @Test
    fun setupSelectOptions_offerOnlyWhatTheServerAccepts() {
        val accounts =
            listOf(
                acc("bank", "18000", LedgerAccountType.ASSET),
                acc("cash", "16000", LedgerAccountType.ASSET, cash = true),
                acc("old", "18100", LedgerAccountType.ASSET, active = false),
                acc("inc", "40000", LedgerAccountType.INCOME),
                acc("exp", "68550", LedgerAccountType.EXPENSE),
            )
        assertEquals(listOf("bank"), setupSelectOptions(FinanceSetupStep.BANK_ACCOUNT, accounts).map { it.first })
        assertEquals(listOf("inc"), setupSelectOptions(FinanceSetupStep.DONATION_INCOME, accounts).map { it.first })
        assertEquals(listOf("exp"), setupSelectOptions(FinanceSetupStep.FEE_ACCOUNT, accounts).map { it.first })
        assertTrue(setupSelectOptions(FinanceSetupStep.CHART, accounts).isEmpty())
    }

    @Test
    fun standardAccountIdFor_findsByNumber_andRespectsAccepts() {
        val accounts = listOf(acc("b", "18000", LedgerAccountType.ASSET), acc("w", "40000", LedgerAccountType.ASSET))
        assertEquals("b", standardAccountIdFor(FinanceSetupStep.BANK_ACCOUNT, accounts))
        assertNull(standardAccountIdFor(FinanceSetupStep.CONTRIBUTION_INCOME, accounts), "40000 exists but has the wrong type")
        assertNull(standardAccountIdFor(FinanceSetupStep.EVENT_INCOME, accounts), "42010 missing")
    }

    @Test
    fun proposeStandardDefaults_fillsOnlyEmptySteps() {
        val accounts =
            listOf(
                acc("b", "18000", LedgerAccountType.ASSET),
                acc("m", "40000", LedgerAccountType.INCOME),
                acc("s", "40450", LedgerAccountType.INCOME),
            )
        val result =
            proposeStandardDefaults(
                mapOf(
                    FinanceSetupStep.BANK_ACCOUNT to "",
                    FinanceSetupStep.CONTRIBUTION_INCOME to "own",
                    FinanceSetupStep.DONATION_INCOME to null,
                ),
                accounts,
            )
        assertEquals("b", result[FinanceSetupStep.BANK_ACCOUNT])
        assertEquals("own", result[FinanceSetupStep.CONTRIBUTION_INCOME], "a choice is never overwritten")
        assertEquals("s", result[FinanceSetupStep.DONATION_INCOME])
    }

    @Test
    fun standardChartToCreate_skipsExistingNumbers() {
        val all = standardChartToCreate(emptySet())
        assertEquals(20, all.size)
        assertEquals(19, standardChartToCreate(setOf("18000")).size)
        assertTrue(standardChartToCreate(all.map { it.accountNumber }.toSet()).isEmpty())
    }

    @Test
    fun financeSetupAccess_followsTheRole() {
        assertEquals(FinanceSetupAccess.MANAGE_ALL, financeSetupAccessOf(isAdmin = true, isTreasurer = true))
        assertEquals(FinanceSetupAccess.CHART_ONLY, financeSetupAccessOf(isAdmin = false, isTreasurer = true))
        assertEquals(FinanceSetupAccess.READ_ONLY, financeSetupAccessOf(isAdmin = false, isTreasurer = false))
    }

    @Test
    fun financeMappingStatusText_isAWarningNotAnError() {
        assertEquals("success", financeMappingStatusText(status(open = false)).second)
        val incomplete = financeMappingStatusText(status(open = true))
        assertEquals("warning", incomplete.second)
        assertTrue(incomplete.first.contains("Bankkonto"), incomplete.first)
    }

    @Test
    fun ledgerHashOfMatch_usesTheMatchQuery_notTheStaleBrowserHash() {
        assertEquals("#/ledger?tab=einrichtung&step=bank", ledgerHashOfMatch("tab=einrichtung&step=bank"))
        assertEquals("#/ledger", ledgerHashOfMatch(""))
        assertEquals("#/ledger", ledgerHashOfMatch(null))
        assertEquals(LedgerTab.SETUP, parseLedgerTab(ledgerHashOfMatch("tab=einrichtung")))
        assertEquals(FinanceSetupStep.BANK_ACCOUNT, parseSetupStepAnchor(ledgerHashOfMatch("tab=einrichtung&step=bank")))
    }

    @Test
    fun setupHintText_mentionsUnbookedContributionsOnlyForTheBlockingSteps() {
        val sentence = "bezahlte Beiträge nicht gebucht"
        listOf(FinanceSetupStep.BANK_ACCOUNT, FinanceSetupStep.CONTRIBUTION_INCOME, FinanceSetupStep.FEE_ACCOUNT).forEach {
            assertTrue(setupHintText(it).contains(sentence), "$it")
        }
        listOf(FinanceSetupStep.DONATION_INCOME, FinanceSetupStep.EVENT_INCOME).forEach {
            assertTrue(!setupHintText(it).contains(sentence), "$it")
            assertTrue(setupHintText(it).startsWith("Es fehlt noch:"), "$it")
        }
    }

    @Test
    fun pendingSetupStep_isHandedOnlyToAFirstRender() {
        assertEquals(FinanceSetupStep.BANK_ACCOUNT, pendingSetupStep(false, FinanceSetupStep.BANK_ACCOUNT))
        assertNull(pendingSetupStep(true, FinanceSetupStep.BANK_ACCOUNT))
        assertNull(pendingSetupStep(false, null))
    }

    @Test
    fun enableConflictOutcome_onlyAnIncompleteMappingShowsTheGate() {
        assertEquals(EnableConflictOutcome.MAPPING_GATE, enableConflictOutcome(status(open = true)))
        assertEquals(EnableConflictOutcome.GENERIC_ERROR, enableConflictOutcome(status(open = false)), "e.g. a changed disclaimer")
        assertEquals(EnableConflictOutcome.ALREADY_REPORTED, enableConflictOutcome(null), "no second error toast")
    }
}
