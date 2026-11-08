package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgAccountMappingRowDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalRowDto
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.289 (R4a) -- the pure logic of the mapping tab, the proposal dialog, the tab visibility and the ledger unit controls. */
class PartyReportR4LogicTest {
    private fun row(
        id: String,
        type: LedgerAccountType = LedgerAccountType.INCOME,
        code: String? = null,
        excluded: Boolean = false,
        suggestion: String? = null,
        suggestExcluded: Boolean = false,
        balance: Long? = null,
        postings: Int = 0,
        active: Boolean = true,
        nameSuggestion: String? = null,
    ) = PartgAccountMappingRowDto(
        accountId = id,
        number = "4$id",
        name = "Konto $id",
        type = type,
        active = active,
        positionCode = code,
        excluded = excluded,
        templateSuggestion = suggestion,
        templateSuggestsExcluded = suggestExcluded,
        balanceCents = balance,
        postingCount = postings,
        nameSuggestion = nameSuggestion,
        nameSuggestionKeyword = nameSuggestion?.let { "wort" },
    )

    @Test
    fun aChoiceBecomesAChangeThatNamesTheOldState() {
        val draft = PartgMappingDraft(listOf(row("1"), row("2", code = "E01"), row("3", excluded = true)))
        draft.choose("1", MappingChoice.Position("E02"))
        draft.choose("2", MappingChoice.Cleared)
        draft.choose("3", MappingChoice.Position("E03"))
        val changes = draft.changes().associateBy { it.accountId }
        assertEquals(false, changes.getValue("1").expectedMapped)
        assertEquals("E02", changes.getValue("1").newPositionCode)
        assertEquals(true, changes.getValue("2").expectedMapped)
        assertEquals("E01", changes.getValue("2").expectedPositionCode)
        assertTrue(changes.getValue("2").clear)
        assertEquals(true, changes.getValue("3").expectedExcluded)
        assertTrue(draft.hasUnsaved)
    }

    @Test
    fun aChoiceThatEqualsTheStoredStateIsNoChange_andAForbiddenTargetIsIgnored() {
        val draft = PartgMappingDraft(listOf(row("1", code = "E01"), row("2", type = LedgerAccountType.EQUITY)))
        draft.choose("1", MappingChoice.Position("E01"))
        draft.choose("2", MappingChoice.Position("E01")) // equity may only be excluded
        draft.choose("1", MappingChoice.Position("A01")) // an expense position for an income account
        draft.choose("1", MappingChoice.Position("E11")) // a SUM
        assertFalse(draft.hasUnsaved)
        assertEquals(emptyList(), draft.changes())
        draft.choose("2", MappingChoice.Excluded)
        assertTrue(draft.changes().single().newExcluded)
    }

    @Test
    fun suggestionsFillOnlyEmptyRows_andAreOnlyMarkedWhenTaken() {
        val draft =
            PartgMappingDraft(
                listOf(
                    row("1", suggestion = "E01"),
                    row("2", code = "E02", suggestion = "E01"), // already mapped: no suggestion
                    row("3", suggestExcluded = true),
                    row("4"), // nothing to suggest
                ),
            )
        assertEquals(2, draft.showSuggestions())
        assertTrue(draft.isSuggestionShown("1") && draft.isSuggestionShown("3"))
        assertFalse(draft.isSuggestionShown("2") || draft.isSuggestionShown("4"))
        assertFalse(draft.hasUnsaved, "showing a suggestion marks nothing")
        draft.toggleTick("1")
        assertEquals(1, draft.acceptTicked())
        assertEquals(listOf("1"), draft.changes().map { it.accountId })
        assertEquals("E01", draft.changes().single().newPositionCode)
        assertTrue(draft.isSuggestionShown("3"), "the unticked suggestion stays a suggestion")
        draft.discard()
        assertFalse(draft.hasUnsaved || draft.suggestionsShown)
    }

    @Test
    fun aNameGuessIsOnlyTheFallback_isNeverTickedInBulk_andNeverOverridesTheChart() {
        val draft =
            PartgMappingDraft(
                listOf(
                    row("1", suggestion = "E01", nameSuggestion = "E02"), // the chart wins
                    row("2", type = LedgerAccountType.EXPENSE, nameSuggestion = "A01"),
                    row("3", type = LedgerAccountType.EXPENSE, nameSuggestion = "E01"), // a position the type may not take: ignored
                ),
            )
        assertEquals(MappingChoice.Position("E01"), draft.suggestionOf(row("1", suggestion = "E01", nameSuggestion = "E02")))
        assertEquals(2, draft.showSuggestions())
        draft.tickAllChart()
        assertTrue(draft.isTicked("1"))
        assertFalse(draft.isTicked("2"), "a guess is never ticked in bulk")
        draft.toggleTick("2")
        assertEquals(2, draft.acceptTicked())
        assertEquals(setOf("E01", "A01"), draft.changes().map { it.newPositionCode }.toSet())
        val empty = PartgMappingDraft(emptyList())
        assertEquals(SuggestionSource.NAME, empty.suggestionSourceOf(row("2", type = LedgerAccountType.EXPENSE, nameSuggestion = "A01")))
        assertEquals(SuggestionSource.CHART, empty.suggestionSourceOf(row("1", suggestion = "E01", nameSuggestion = "E02")))
        assertNull(empty.suggestionSourceOf(row("9")))
    }

    @Test
    fun theUnmappedBlockIsSortedByTheAbsoluteBalance_andSkipsInactiveAccountsWithoutPostings() {
        val rows =
            listOf(
                row("1", balance = 100),
                row("2", balance = -5_000),
                row("3", balance = 0, active = false),
                row("4", balance = 0, active = false, postings = 2),
                row("5", code = "E01", balance = 99_999),
            )
        assertEquals(listOf("2", "1", "4"), partgUnmappedRows(rows).map { it.accountId })
    }

    @Test
    fun theProposalDialogStartsWithTheServersPreselection_andShowsTheDifference() {
        val rows =
            listOf(
                PartgLedgerProposalRowDto("E01", null, null, 100, preselected = true, transferable = true),
                PartgLedgerProposalRowDto("E02", 50, PartgValueSource.MANUAL, 100, preselected = false, transferable = true),
                PartgLedgerProposalRowDto("A01", 10, PartgValueSource.LEDGER, -5, preselected = false, transferable = false),
            )
        assertEquals(setOf("E01"), partgProposalInitialPicks(rows))
        assertNull(partgProposalDifference(rows[0]))
        assertEquals(50L, partgProposalDifference(rows[1]))
    }

    @Test
    fun aChapterTreasurerSeesFourTabs_everyoneElseAll() {
        val scopes = listOf(RegionalChapterRefDto("c1", "Nord"))
        val treasurer = PartyReportDomSupport.session(role = AccountRole.MEMBER, chapterTreasurerScopes = scopes)
        assertTrue(partgChapterTreasurerOnly(treasurer))
        assertEquals(
            listOf(PartyReportTab.OVERVIEW, PartyReportTab.SHEET, PartyReportTab.KONTENZUORDNUNG, PartyReportTab.REPORT),
            partgVisibleTabs(treasurer),
        )
        // a board member with a grant keeps everything
        val board = PartyReportDomSupport.session(role = AccountRole.BOARD, chapterTreasurerScopes = scopes)
        assertFalse(partgChapterTreasurerOnly(board))
        assertEquals(PARTG_TAB_DISPLAY_ORDER, partgVisibleTabs(board))
        assertFalse(partgChapterTreasurerOnly(null))
    }

    @Test
    fun theDeepLinkNamesExactlyOneTab() {
        assertEquals(PartyReportTab.KONTENZUORDNUNG, partgTabOfQuery("tab=KONTENZUORDNUNG"))
        assertEquals(PartyReportTab.KONTENZUORDNUNG, partgTabOfQuery("?x=1&tab=KONTENZUORDNUNG"))
        assertNull(partgTabOfQuery("tab=kontenzuordnung"))
        assertNull(partgTabOfQuery("tab=NOPE"))
        assertNull(partgTabOfQuery(null))
    }

    @Test
    fun theLedgerUnitControls() {
        val units = listOf(RegionalChapterRefDto("c1", "Nord"), RegionalChapterRefDto("c2", "Süd"))
        // finance roles: "Bund" first and the default; a chapter treasurer: only the own chapters, no "Bund"
        assertEquals(listOf("", "c1", "c2"), ledgerUnitFormOptions(units, chapterOnly = false).map { it.first })
        assertEquals(listOf("c1", "c2"), ledgerUnitFormOptions(units, chapterOnly = true).map { it.first })
        assertEquals(emptyList(), ledgerUnitFormOptions(emptyList(), chapterOnly = false))
        assertEquals("", ledgerUnitFormDefault(units, chapterOnly = false))
        assertEquals("c1", ledgerUnitFormDefault(units.take(1), chapterOnly = true))
        assertTrue(ledgerUnitFormLocked(units.take(1), chapterOnly = true))
        assertFalse(ledgerUnitFormLocked(units, chapterOnly = true))
        assertEquals(listOf("", LEDGER_UNIT_FEDERAL, "c1", "c2"), ledgerUnitFilterOptions(units, chapterOnly = false).map { it.first })
        assertEquals(listOf("", "c1", "c2"), ledgerUnitFilterOptions(units, chapterOnly = true).map { it.first })
        assertNull(ledgerUnitFilterArgument(""))
        assertEquals("FEDERAL", ledgerUnitFilterArgument("FEDERAL"))
        assertNull(ledgerUnitInputId(""))
        assertNull(ledgerUnitInputId("FEDERAL"))
        assertEquals("c1", ledgerUnitInputId("c1"))
    }

    @Test
    fun theLedgerChapterOnlyFlagFollowsTheRoleAndTheGrant() {
        val scopes = listOf(RegionalChapterRefDto("c1", "Nord"))
        assertTrue(ledgerChapterOnly(PartyReportDomSupport.session(role = AccountRole.MEMBER, chapterTreasurerScopes = scopes)))
        assertFalse(ledgerChapterOnly(PartyReportDomSupport.session(role = AccountRole.TREASURER, chapterTreasurerScopes = scopes)))
        assertFalse(ledgerChapterOnly(PartyReportDomSupport.session(role = AccountRole.MEMBER)))
        assertFalse(ledgerChapterOnly(null))
    }
}
