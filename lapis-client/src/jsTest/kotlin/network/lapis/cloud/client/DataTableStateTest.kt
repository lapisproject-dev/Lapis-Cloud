package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.25 -- the pure decision logic behind `dataTable`/`dataSection` (sort cycle, ARIA values, icon
 * classes, card layout split, view states). No widgets, no DOM.
 */
class DataTableStateTest {
    private val asc = SortDirection.ASC
    private val desc = SortDirection.DESC

    // ── nextSortState ──

    @Test
    fun nextSortState_anotherColumnOrNoSort_startsWithTheFirstDirection() {
        assertEquals(SortState("name", asc), nextSortState(current = null, clicked = "name"))
        assertEquals(SortState("joined", desc), nextSortState(SortState("name", asc), "joined", firstDirection = desc))
    }

    @Test
    fun nextSortState_sameColumn_reversesTheDirection() {
        assertEquals(SortState("name", desc), nextSortState(SortState("name", asc), "name"))
        assertEquals(SortState("joined", asc), nextSortState(SortState("joined", desc), "joined", firstDirection = desc))
    }

    @Test
    fun nextSortState_thirdClick_fallsBackToTheFirstDirectionWithoutUnsorted() {
        assertEquals(SortState("name", asc), nextSortState(SortState("name", desc), "name", allowUnsorted = false))
        assertEquals(SortState("joined", desc), nextSortState(SortState("joined", asc), "joined", firstDirection = desc))
    }

    @Test
    fun nextSortState_thirdClick_switchesSortingOffWithUnsorted() {
        assertEquals(SortState("name", desc), nextSortState(SortState("name", asc), "name", allowUnsorted = true))
        assertNull(nextSortState(SortState("name", desc), "name", allowUnsorted = true))
    }

    // ── ARIA / icon ──

    @Test
    fun ariaSortValue_exactlyOneColumnIsNotNone() {
        val state = SortState("name", desc)
        assertEquals("descending", ariaSortValue(state, "name"))
        assertEquals("none", ariaSortValue(state, "joined"))
        assertEquals("ascending", ariaSortValue(SortState("name", asc), "name"))
        assertEquals("none", ariaSortValue(null, "name"))
    }

    @Test
    fun sortIconClass_idleIsNeutral_activeIsAnArrow() {
        assertEquals("fa-sort", sortIconClass(null, "name"))
        assertEquals("fa-sort", sortIconClass(SortState("joined", asc), "name"))
        assertEquals("fa-sort-up", sortIconClass(SortState("name", asc), "name"))
        assertEquals("fa-sort-down", sortIconClass(SortState("name", desc), "name"))
    }

    // ── sortButtonTexts ──

    @Test
    fun sortButtonTexts_idleColumn_bothTextsAreTheAction() {
        val texts = sortButtonTexts(current = null, key = "name", columnTitle = "Name")
        assertEquals("Nach Name aufsteigend sortieren", texts.tooltip)
        assertEquals("Nach Name aufsteigend sortieren", texts.ariaLabel)
    }

    @Test
    fun sortButtonTexts_activeColumn_tooltipIsTheNextAction_ariaLabelTheState() {
        val texts = sortButtonTexts(current = SortState("name", asc), key = "name", columnTitle = "Name")
        assertEquals("Nach Name absteigend sortieren", texts.tooltip)
        assertEquals("Aufsteigend sortiert nach Name", texts.ariaLabel)
        val descending = sortButtonTexts(current = SortState("name", desc), key = "name", columnTitle = "Name")
        assertEquals("Absteigend sortiert nach Name", descending.ariaLabel)
    }

    @Test
    fun sortButtonTexts_firstDirectionDescending_startsWithTheDescendingAction() {
        val texts = sortButtonTexts(current = null, key = "joined", columnTitle = "Beitritt", firstDirection = desc)
        assertEquals("Nach Beitritt absteigend sortieren", texts.tooltip)
    }

    @Test
    fun sortButtonTexts_trTitle_neverCarriesTheKvisionMarker() {
        val texts = sortButtonTexts(current = SortState("name", asc), key = "name", columnTitle = tr("Name"))
        assertFalse(texts.tooltip.contains("###KvI18nS###"))
        assertFalse(texts.ariaLabel.contains("###KvI18nS###"))
        assertEquals("Aufsteigend sortiert nach Name", texts.ariaLabel)
    }

    @Test
    fun sortButtonTexts_trTitle_isTranslatedInTooltipAndAriaLabel() {
        // Audit V1.4.25 M2: the visible header is translated by KVision, the sort texts must not stay German.
        withTranslations(
            mapOf(
                "Name" to "Name (translated)",
                "Nach %1 aufsteigend sortieren" to "Sort by %1 ascending",
                "Aufsteigend sortiert nach %1" to "Sorted ascending by %1",
            ),
        ) {
            val idle = sortButtonTexts(current = null, key = "name", columnTitle = tr("Name"))
            assertEquals("Sort by Name (translated) ascending", idle.tooltip)
            val active = sortButtonTexts(current = SortState("name", asc), key = "name", columnTitle = tr("Name"))
            assertEquals("Sorted ascending by Name (translated)", active.ariaLabel)
            assertFalse(active.ariaLabel.contains("###KvI18nS###"))
        }
    }

    // ── SortFocusRequest (audit minor 14) ──

    @Test
    fun sortFocus_requestFollowsTheClicksLoadIntoItsRender_exactlyOnce() {
        val focus = SortFocusRequest()
        focus.request("name")
        focus.beginLoad()
        focus.settled(rendersTable = true)
        assertEquals("name", focus.takeForRender())
        assertNull(focus.takeForRender())
    }

    @Test
    fun sortFocus_failedOrEmptyLoad_dropsTheRequest_soALaterRenderDoesNotStealTheFocus() {
        val focus = SortFocusRequest()
        focus.request("joined")
        focus.beginLoad()
        focus.settled(rendersTable = false) // load failed or came back empty: no table is rendered
        focus.beginLoad() // the next, unrelated load (a search keystroke)
        focus.settled(rendersTable = true)
        assertNull(focus.takeForRender())
    }

    @Test
    fun sortFocus_supersededLoad_neverDeliversItsFocusToTheNewerLoad() {
        val focus = SortFocusRequest()
        focus.request("name")
        focus.beginLoad()
        focus.beginLoad() // a newer load takes over before the first one settles
        focus.settled(rendersTable = true)
        assertNull(focus.takeForRender())
    }

    @Test
    fun sortFocus_aRequestMadeAfterALoadStartedBelongsToTheNextLoad() {
        val focus = SortFocusRequest()
        focus.beginLoad()
        focus.request("name")
        focus.settled(rendersTable = true)
        assertNull(focus.takeForRender())
        focus.beginLoad()
        focus.settled(rendersTable = true)
        assertEquals("name", focus.takeForRender())
    }

    // ── cardLayout ──

    @Test
    fun cardLayout_explicitPrimary() {
        assertEquals(CardLayout(primaryIndex = 2, detailIndices = listOf(0, 1, 3)), cardLayout(4, listOf(false, false, true, false)))
    }

    @Test
    fun cardLayout_noPrimary_defaultsToColumnZero() {
        assertEquals(CardLayout(primaryIndex = 0, detailIndices = listOf(1, 2)), cardLayout(3, listOf(false, false, false)))
    }

    @Test
    fun cardLayout_twoPrimaries_isRejected() {
        assertFailsWith<IllegalArgumentException> { cardLayout(3, listOf(true, true, false)) }
    }

    @Test
    fun cardLayout_flagCountMustMatchColumnCount() {
        assertFailsWith<IllegalArgumentException> { cardLayout(3, listOf(true)) }
        assertFailsWith<IllegalArgumentException> { cardLayout(0, emptyList()) }
    }

    // ── resolveDataViewState ──

    @Test
    fun resolveDataViewState_coversAllBranches() {
        val isEmpty: (List<Int>) -> Boolean = { it.isEmpty() }
        assertEquals(DataViewState.Error, resolveDataViewState(null, isEmpty, filterTerm = null))
        assertEquals(DataViewState.Error, resolveDataViewState(null, isEmpty, filterTerm = "abc"))
        assertEquals(DataViewState.Empty, resolveDataViewState(emptyList(), isEmpty, filterTerm = null))
        assertEquals(DataViewState.Empty, resolveDataViewState(emptyList(), isEmpty, filterTerm = "   "))
        assertEquals(DataViewState.NoMatch("abc"), resolveDataViewState(emptyList(), isEmpty, filterTerm = " abc "))
        assertEquals(DataViewState.Content(listOf(1)), resolveDataViewState(listOf(1), isEmpty, filterTerm = "abc"))
    }

    // ── media query / constants ──

    @Test
    fun cardListMediaQuery_matchesTheDocumentedBreakpoint() {
        assertEquals("(max-width: 767.98px)", cardListMediaQuery())
        assertEquals(768, CARD_LIST_BREAKPOINT_PX)
        assertTrue(cardListMediaQuery().contains("767.98px"))
    }

    @Test
    fun screenWidthConstants_matchTheGuideline() {
        assertEquals(480, NARROW_FORM_MAX_WIDTH_PX)
        assertEquals(720, READING_MAX_WIDTH_PX)
        assertEquals(640, DASHBOARD_MAX_WIDTH_PX)
        assertEquals(1440, DATA_SCREEN_MAX_WIDTH_PX)
        assertEquals(16, DATA_SCREEN_SPACING_PX)
    }
}
