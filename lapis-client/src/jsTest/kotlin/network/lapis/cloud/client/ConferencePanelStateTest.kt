package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.2.9 Vollbildmodus für Videokonferenzen -- covers [conferencePanelReduce]/[conferenceRailLayout],
 * the pure state model behind `ConferenceScreen.kt`'s `enterCall#applyPanelVisibility`. Same DOM-free
 * unit-test posture as [ConferenceGridLayoutTest]/[ConferenceScreenTest] -- no rendering harness
 * exists in this module, so the actual DOM/CSS-class side of `applyPanelVisibility` and the real
 * Fullscreen-API interop (`FullscreenApi.kt`) are out of scope here, covered only by this wave's own
 * live-browser verification (see plan section 8).
 *
 * V1.2.10 mobil-optimierte Steuerleiste -- extends the same DOM-free posture to the two new fields
 * ([ConferencePanelState.controlsVisible]/[ConferencePanelState.moreOpen]) and three new events
 * ([ConferencePanelEvent.MoreToggled]/[ConferencePanelEvent.PointerActivity]/
 * [ConferencePanelEvent.InactivityElapsed]) added to the same reducer.
 */
class ConferencePanelStateTest {
    @Test
    fun conferencePanelReduce_default_rosterVisibleTrue_chatVisibleFalse_fullscreenFalse() {
        val state = ConferencePanelState()
        assertFalse(state.fullscreen)
        assertTrue(state.rosterVisible())
        assertFalse(state.chatVisible())
        // V1.2.10
        assertTrue(state.controlsVisible)
        assertFalse(state.moreOpen)
    }

    @Test
    fun conferenceInitialPanelState_wideViewport_keepsHistoricalDefault_rosterOpen() {
        val state = conferenceInitialPanelState(narrowViewport = false)
        assertEquals(ConferencePanelState(), state)
        assertTrue(state.rosterVisible())
    }

    @Test
    fun conferenceInitialPanelState_narrowViewport_startsWithRosterClosed_soVideoTilesAreVisible() {
        val state = conferenceInitialPanelState(narrowViewport = true)
        assertFalse(state.rosterVisible())
        assertFalse(state.normalRosterOpen)
        // Everything else is untouched: chat closed, controls visible, not fullscreen, "Mehr" closed.
        assertFalse(state.chatVisible())
        assertFalse(state.fullscreen)
        assertTrue(state.controlsVisible)
        assertFalse(state.moreOpen)
    }

    @Test
    fun conferenceInitialPanelState_narrowViewport_rosterStillOpensOnDemandViaTheControlBarToggle() {
        val opened = conferencePanelReduce(conferenceInitialPanelState(narrowViewport = true), ConferencePanelEvent.RosterToggled)
        assertTrue(opened.rosterVisible())
        val closedAgain = conferencePanelReduce(opened, ConferencePanelEvent.RosterToggled)
        assertFalse(closedAgain.rosterVisible())
    }

    @Test
    fun conferenceNarrowViewportBreakpoint_matchesTheThemeCssBottomSheetRule() {
        // theme.css: `@media (max-width: 767.98px)` turns roster/chat into full-screen fixed sheets.
        assertEquals("767.98px", CONFERENCE_NARROW_VIEWPORT_MEDIA_MAX_WIDTH)
    }

    @Test
    fun conferencePanelReduce_rosterToggled_normalMode_flipsOnlyNormalRosterOpen() {
        val state = ConferencePanelState()
        val next = conferencePanelReduce(state, ConferencePanelEvent.RosterToggled)
        assertFalse(next.normalRosterOpen)
        assertEquals(state.normalChatOpen, next.normalChatOpen)
        assertEquals(state.fullscreenRosterOpen, next.fullscreenRosterOpen)
        assertEquals(state.fullscreenChatOpen, next.fullscreenChatOpen)
    }

    @Test
    fun conferencePanelReduce_fullscreenEntered_setsFullscreenTrue_andBothFullscreenFlagsFalse() {
        val state =
            ConferencePanelState(fullscreenRosterOpen = true, fullscreenChatOpen = true, moreOpen = true, controlsVisible = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.FullscreenEntered)
        assertTrue(next.fullscreen)
        assertFalse(next.fullscreenRosterOpen)
        assertFalse(next.fullscreenChatOpen)
        // V1.2.10 -- eine offene "Mehr"-Offenlegung darf nicht mit in den Vollbild-Eintritt genommen
        // werden (das Blatt hat kein Vollbild-Pendant), und eine ausgeblendete Leiste muss beim
        // Wechsel wieder sichtbar sein (Fullscreen-Eintritt IST Aktivität).
        assertFalse(next.moreOpen)
        assertTrue(next.controlsVisible)
    }

    @Test
    fun conferencePanelReduce_fullscreenEntered_leavesNormalFlagsUnchanged_evenWhenBothWereTrue() {
        val state = ConferencePanelState(normalRosterOpen = true, normalChatOpen = true)
        val next = conferencePanelReduce(state, ConferencePanelEvent.FullscreenEntered)
        assertTrue(next.normalRosterOpen)
        assertTrue(next.normalChatOpen)
    }

    @Test
    fun conferencePanelReduce_rosterToggled_fullscreenMode_flipsOnlyFullscreenRosterOpen_neverNormalRosterOpen() {
        val state = ConferencePanelState(fullscreen = true, normalRosterOpen = true, fullscreenRosterOpen = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.RosterToggled)
        assertTrue(next.fullscreenRosterOpen)
        assertTrue(next.normalRosterOpen) // untouched
    }

    @Test
    fun conferencePanelReduce_fullscreenExited_restoresExactNormalVisibilityFromBeforeEntry() {
        val before = ConferencePanelState(normalRosterOpen = false, normalChatOpen = true)
        val entered = conferencePanelReduce(before, ConferencePanelEvent.FullscreenEntered)
        val afterRosterToggle = conferencePanelReduce(entered, ConferencePanelEvent.RosterToggled)
        val afterChatToggle = conferencePanelReduce(afterRosterToggle, ConferencePanelEvent.ChatToggled)
        val exited = conferencePanelReduce(afterChatToggle, ConferencePanelEvent.FullscreenExited)
        assertFalse(exited.fullscreen)
        assertEquals(before.normalRosterOpen, exited.normalRosterOpen)
        assertEquals(before.normalChatOpen, exited.normalChatOpen)
        // V1.2.10 -- FullscreenExited setzt controlsVisible=true (Aktivität), moreOpen war während
        // der gesamten Vollbild-Episode nie true und bleibt es.
        assertTrue(exited.controlsVisible)
        assertFalse(exited.moreOpen)
    }

    @Test
    fun conferencePanelReduce_secondFullscreenEntry_startsWithBothRailsClosedAgain() {
        val before = ConferencePanelState()
        val firstEntry = conferencePanelReduce(before, ConferencePanelEvent.FullscreenEntered)
        val opened = conferencePanelReduce(firstEntry, ConferencePanelEvent.ChatToggled)
        val exited = conferencePanelReduce(opened, ConferencePanelEvent.FullscreenExited)
        val secondEntry = conferencePanelReduce(exited, ConferencePanelEvent.FullscreenEntered)
        assertFalse(secondEntry.fullscreenChatOpen)
        assertFalse(secondEntry.fullscreenRosterOpen)
    }

    @Test
    fun conferencePanelReduce_fullscreenEntered_onAlreadyFullscreen_isIdempotent_keepsOpenRails() {
        val state = ConferencePanelState(fullscreen = true, fullscreenRosterOpen = true, fullscreenChatOpen = true)
        val next = conferencePanelReduce(state, ConferencePanelEvent.FullscreenEntered)
        assertEquals(state, next)
    }

    @Test
    fun conferencePanelReduce_fullscreenExited_onAlreadyNotFullscreen_isNoop() {
        val state = ConferencePanelState()
        val next = conferencePanelReduce(state, ConferencePanelEvent.FullscreenExited)
        assertEquals(state, next)
    }

    @Test
    fun conferenceRailLayout_noneOpen_railOccupiedFalse() {
        val layout = conferenceRailLayout(ConferencePanelState(fullscreen = true))
        assertFalse(layout.railOccupied)
        assertFalse(layout.rosterCapped)
        assertFalse(layout.chatFlexible)
    }

    @Test
    fun conferenceRailLayout_onlyRosterOpen_rosterCappedFalse() {
        val layout = conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenRosterOpen = true))
        assertTrue(layout.railOccupied)
        assertFalse(layout.rosterCapped)
    }

    // Nur Chat offen (kein Roster): das eine offene Panel nimmt die volle Höhe ein, kein "flexibler"
    // Stapel-Modus -- der ist ausschließlich für "BEIDE offen" reserviert (D10: "nur eines offen:
    // dieses nimmt 100% Höhe"). Siehe Plan Klärungsfrage 3 -- dieser Test folgt D10s eigentlicher
    // Absicht und dem tatsächlich implementierten conferenceRailLayout, nicht der abweichenden
    // Formulierung im D15-Testfall-Text des Design-Reviews.
    @Test
    fun conferenceRailLayout_onlyChatOpen_chatFlexibleFalse() {
        val layout = conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenChatOpen = true))
        assertTrue(layout.railOccupied)
        assertFalse(layout.chatFlexible)
    }

    @Test
    fun conferenceRailLayout_bothOpen_rosterCappedTrue_andChatFlexibleTrue() {
        val layout =
            conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenRosterOpen = true, fullscreenChatOpen = true))
        assertTrue(layout.railOccupied)
        assertTrue(layout.rosterCapped)
        assertTrue(layout.chatFlexible)
    }

    @Test
    fun conferencePanelState_rosterVisible_chatVisible_readOnlyFullscreenFlagsWhenFullscreen() {
        val state =
            ConferencePanelState(
                fullscreen = true,
                normalRosterOpen = true,
                normalChatOpen = true,
                fullscreenRosterOpen = false,
                fullscreenChatOpen = false,
            )
        assertFalse(state.rosterVisible())
        assertFalse(state.chatVisible())
    }

    // --- V1.2.10 mobil-optimierte Steuerleiste ------------------------------------------------------

    @Test
    fun conferencePanelReduce_moreToggled_opensSheet_andSetsControlsVisible() {
        val state = ConferencePanelState(controlsVisible = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.MoreToggled)
        assertTrue(next.moreOpen)
        assertTrue(next.controlsVisible)
    }

    @Test
    fun conferencePanelReduce_moreToggled_twice_closesSheetAgain() {
        val state = ConferencePanelState()
        val opened = conferencePanelReduce(state, ConferencePanelEvent.MoreToggled)
        val closed = conferencePanelReduce(opened, ConferencePanelEvent.MoreToggled)
        assertFalse(closed.moreOpen)
    }

    @Test
    fun conferencePanelReduce_inactivityElapsed_hidesControls_whenMoreClosed() {
        val state = ConferencePanelState(controlsVisible = true, moreOpen = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.InactivityElapsed)
        assertFalse(next.controlsVisible)
    }

    @Test
    fun conferencePanelReduce_inactivityElapsed_isNoop_whenMoreOpen() {
        val state = ConferencePanelState(controlsVisible = true, moreOpen = true)
        val next = conferencePanelReduce(state, ConferencePanelEvent.InactivityElapsed)
        assertEquals(state, next)
    }

    @Test
    fun conferencePanelReduce_inactivityElapsed_isIdempotent_whenAlreadyHidden() {
        val state = ConferencePanelState(controlsVisible = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.InactivityElapsed)
        assertEquals(state, next)
    }

    @Test
    fun conferencePanelReduce_pointerActivity_showsControls_whenHidden() {
        val state = ConferencePanelState(controlsVisible = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.PointerActivity)
        assertTrue(next.controlsVisible)
    }

    @Test
    fun conferencePanelReduce_pointerActivity_isIdempotent_whenAlreadyVisible() {
        val state = ConferencePanelState(controlsVisible = true)
        val next = conferencePanelReduce(state, ConferencePanelEvent.PointerActivity)
        assertEquals(state, next)
    }

    @Test
    fun conferencePanelReduce_rosterToggled_fromHiddenControls_setsControlsVisible() {
        val state = ConferencePanelState(controlsVisible = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.RosterToggled)
        assertTrue(next.controlsVisible)
    }

    @Test
    fun conferencePanelReduce_chatToggled_fromHiddenControls_setsControlsVisible() {
        val state = ConferencePanelState(controlsVisible = false)
        val next = conferencePanelReduce(state, ConferencePanelEvent.ChatToggled)
        assertTrue(next.controlsVisible)
    }

    @Test
    fun conferencePanelReduce_rosterAndChatToggled_leaveMoreOpenUntouched() {
        val opened = ConferencePanelState(moreOpen = true)
        val afterRoster = conferencePanelReduce(opened, ConferencePanelEvent.RosterToggled)
        val afterChat = conferencePanelReduce(afterRoster, ConferencePanelEvent.ChatToggled)
        assertTrue(afterChat.moreOpen)
    }

    // conferenceRailLayout() is a pure function of rosterVisible()/chatVisible() alone -- neither
    // controlsVisible nor moreOpen must influence it (Alan Kay: the panel-state fields that steer the
    // control bar's own chrome are not the rails' business). Regression guard: a future edit that
    // accidentally threads either field into conferenceRailLayout would flip these assertions.
    @Test
    fun conferenceRailLayout_unaffectedBy_controlsVisibleAndMoreOpen() {
        val hiddenControlsClosedMore =
            conferenceRailLayout(
                ConferencePanelState(fullscreen = true, fullscreenRosterOpen = true, fullscreenChatOpen = true, controlsVisible = false),
            )
        val visibleControlsOpenMore =
            conferenceRailLayout(
                ConferencePanelState(fullscreen = true, fullscreenRosterOpen = true, fullscreenChatOpen = true, moreOpen = true),
            )
        assertEquals(hiddenControlsClosedMore, visibleControlsOpenMore)
    }

    // The reducer itself carries no fullscreen gate on MoreToggled -- `applyPanelVisibility()` (the
    // rendering layer) is what hides the "Mehr" button and empties the sheet in fullscreen (Alan Kay:
    // "the viewport does not belong in the state"). This test documents that boundary deliberately,
    // rather than leaving it as an unstated assumption.
    @Test
    fun conferencePanelReduce_moreToggled_whileFullscreen_stillTogglesReducerState() {
        val state = ConferencePanelState(fullscreen = true)
        val next = conferencePanelReduce(state, ConferencePanelEvent.MoreToggled)
        assertTrue(next.moreOpen)
    }

    @Test
    fun conferencePanelReduce_pointerActivity_thenInactivityElapsed_reproducesHiddenState() {
        val hidden = ConferencePanelState(controlsVisible = false)
        val shown = conferencePanelReduce(hidden, ConferencePanelEvent.PointerActivity)
        assertTrue(shown.controlsVisible)
        val hiddenAgain = conferencePanelReduce(shown, ConferencePanelEvent.InactivityElapsed)
        assertFalse(hiddenAgain.controlsVisible)
    }

    // ── V1.9.25 "Abstimmen im Konferenzraum": the voting panel in the same reducer ─────────────────────────────────

    private fun reduce(
        state: ConferencePanelState,
        vararg events: ConferencePanelEvent,
    ): ConferencePanelState = events.fold(state) { current, event -> conferencePanelReduce(current, event) }

    private val narrow = ConferencePanelState(narrow = true, normalRosterOpen = false)

    @Test
    fun voting_default_isClosed_unlocked_andRemembersNoElection() {
        val state = ConferencePanelState()
        assertFalse(state.votingVisible())
        assertEquals(ConferenceVotingLock.NONE, state.votingLock)
        assertTrue(state.autoOpenedVotingIds.isEmpty())
        assertFalse(state.narrow)
        assertTrue(conferenceInitialPanelState(narrowViewport = true).narrow)
        assertFalse(conferenceInitialPanelState(narrowViewport = false).narrow)
    }

    @Test
    fun votingToggled_opensAndClosesInTheActiveContext() {
        val opened = reduce(ConferencePanelState(), ConferencePanelEvent.VotingToggled)
        assertTrue(opened.votingVisible())
        assertTrue(opened.normalVotingOpen)
        assertFalse(opened.fullscreenVotingOpen)
        assertFalse(reduce(opened, ConferencePanelEvent.VotingToggled).votingVisible())
        val fullscreen = reduce(ConferencePanelState(fullscreen = true), ConferencePanelEvent.VotingToggled)
        assertTrue(fullscreen.fullscreenVotingOpen)
        assertFalse(fullscreen.normalVotingOpen)
    }

    @Test
    fun votingToggled_closing_isANoop_underABoothOrAReceiptLock() {
        for (lock in listOf(ConferenceVotingLock.BOOTH, ConferenceVotingLock.RECEIPT)) {
            val state = ConferencePanelState(normalVotingOpen = true, votingLock = lock)
            assertEquals(state, reduce(state, ConferencePanelEvent.VotingToggled), "a click must not close the panel under $lock")
        }
    }

    @Test
    fun votingToggled_opening_onANarrowScreen_closesTheChat_andChatOpeningClosesTheVotingPanel() {
        val chatOpen = narrow.copy(normalChatOpen = true)
        val votingOpened = reduce(chatOpen, ConferencePanelEvent.VotingToggled)
        assertTrue(votingOpened.votingVisible())
        assertFalse(votingOpened.chatVisible())
        val chatAgain = reduce(votingOpened, ConferencePanelEvent.ChatToggled)
        assertTrue(chatAgain.chatVisible())
        assertFalse(chatAgain.votingVisible())
    }

    @Test
    fun chatToggled_opening_onANarrowScreen_isANoop_whileTheVotingPanelIsLocked() {
        for (lock in listOf(ConferenceVotingLock.BOOTH, ConferenceVotingLock.RECEIPT)) {
            val state = narrow.copy(normalVotingOpen = true, votingLock = lock)
            val next = reduce(state, ConferencePanelEvent.ChatToggled)
            assertEquals(state, next)
            assertFalse(next.chatVisible())
        }
    }

    @Test
    fun onAWideScreen_votingPanel_chatAndRoster_coexist() {
        val all = reduce(ConferencePanelState(), ConferencePanelEvent.ChatToggled, ConferencePanelEvent.VotingToggled)
        assertTrue(all.rosterVisible())
        assertTrue(all.chatVisible())
        assertTrue(all.votingVisible())
    }

    @Test
    fun rosterAndMoreToggled_opening_onANarrowScreen_isANoop_withAReceiptOnScreen() {
        val state = narrow.copy(normalVotingOpen = true, votingLock = ConferenceVotingLock.RECEIPT)
        assertEquals(state, reduce(state, ConferencePanelEvent.RosterToggled))
        assertEquals(state, reduce(state, ConferencePanelEvent.MoreToggled))
        // closing is never blocked, and a booth (no receipt yet) does not block opening them
        val rosterOpen = state.copy(normalRosterOpen = true)
        assertFalse(reduce(rosterOpen, ConferencePanelEvent.RosterToggled).rosterVisible())
        val booth = narrow.copy(normalVotingOpen = true, votingLock = ConferenceVotingLock.BOOTH)
        assertTrue(reduce(booth, ConferencePanelEvent.RosterToggled).rosterVisible())
        // on a wide screen a receipt blocks neither
        val wide = ConferencePanelState(normalVotingOpen = true, votingLock = ConferenceVotingLock.RECEIPT, normalRosterOpen = false)
        assertTrue(reduce(wide, ConferencePanelEvent.RosterToggled).rosterVisible())
    }

    @Test
    fun fullscreen_carriesTheVotingPanelIn_andBackOut_withoutLosingABoothOpenedThere() {
        val before = ConferencePanelState(normalVotingOpen = true)
        val entered = reduce(before, ConferencePanelEvent.FullscreenEntered)
        assertTrue(entered.fullscreenVotingOpen, "the right to vote does not vanish in fullscreen")
        assertTrue(entered.votingVisible())
        assertFalse(entered.rosterVisible())
        assertFalse(entered.chatVisible())
        val closedInFullscreen = reduce(entered, ConferencePanelEvent.VotingToggled)
        assertTrue(before.normalVotingOpen, "the normal flag is untouched during the episode")
        assertFalse(reduce(closedInFullscreen, ConferencePanelEvent.FullscreenExited).votingVisible())
        val openedInFullscreen = reduce(ConferencePanelState(), ConferencePanelEvent.FullscreenEntered, ConferencePanelEvent.VotingToggled)
        assertTrue(reduce(openedInFullscreen, ConferencePanelEvent.FullscreenExited).votingVisible(), "a booth opened in fullscreen stays")
    }

    @Test
    fun votingAutoOpen_opensOnce_perElectionId() {
        val opened = reduce(ConferencePanelState(), ConferencePanelEvent.VotingAutoOpen("e1"))
        assertTrue(opened.votingVisible())
        assertEquals(setOf("e1"), opened.autoOpenedVotingIds)
        // the member closes it: the same election does not open it again, another one does
        val closed = reduce(opened, ConferencePanelEvent.VotingToggled)
        assertFalse(closed.votingVisible())
        assertEquals(closed, reduce(closed, ConferencePanelEvent.VotingAutoOpen("e1")))
        val second = reduce(closed, ConferencePanelEvent.VotingAutoOpen("e2"))
        assertTrue(second.votingVisible())
        assertEquals(setOf("e1", "e2"), second.autoOpenedVotingIds)
    }

    @Test
    fun votingAutoOpen_isANoop_onANarrowScreen_andUnderALock_andRemembersNothingThen() {
        assertEquals(narrow, reduce(narrow, ConferencePanelEvent.VotingAutoOpen("e1")))
        for (lock in listOf(ConferenceVotingLock.BOOTH, ConferenceVotingLock.RECEIPT)) {
            val locked = ConferencePanelState(votingLock = lock)
            assertEquals(locked, reduce(locked, ConferencePanelEvent.VotingAutoOpen("e1")))
        }
    }

    @Test
    fun votingAutoOpen_whenThePanelIsOpenAnyway_changesNothingButRemembersTheId() {
        val open = ConferencePanelState(normalVotingOpen = true)
        val next = reduce(open, ConferencePanelEvent.VotingAutoOpen("e1"))
        assertEquals(open.copy(autoOpenedVotingIds = setOf("e1")), next)
    }

    @Test
    fun votingLockChanged_setsOnlyTheLock_andIsIdempotent() {
        val locked =
            reduce(ConferencePanelState(controlsVisible = false), ConferencePanelEvent.VotingLockChanged(ConferenceVotingLock.RECEIPT))
        assertEquals(ConferenceVotingLock.RECEIPT, locked.votingLock)
        assertTrue(locked.controlsVisible, "the bar with the blocked leave button must show")
        assertEquals(locked, reduce(locked, ConferencePanelEvent.VotingLockChanged(ConferenceVotingLock.RECEIPT)))
        val released = reduce(locked, ConferencePanelEvent.VotingLockChanged(ConferenceVotingLock.NONE))
        assertEquals(ConferenceVotingLock.NONE, released.votingLock)
    }

    @Test
    fun inactivityElapsed_isANoop_whileAReceiptIsOnScreen() {
        val state = ConferencePanelState(votingLock = ConferenceVotingLock.RECEIPT)
        assertEquals(state, reduce(state, ConferencePanelEvent.InactivityElapsed))
        val booth = ConferencePanelState(votingLock = ConferenceVotingLock.BOOTH)
        assertFalse(reduce(booth, ConferencePanelEvent.InactivityElapsed).controlsVisible)
    }

    @Test
    fun viewportChanged_toNarrow_makesChatAndVotingExclusive_theVotingPanelWins() {
        val both =
            ConferencePanelState(normalChatOpen = true, normalVotingOpen = true, fullscreenChatOpen = true, fullscreenVotingOpen = true)
        val next = reduce(both, ConferencePanelEvent.ViewportChanged(narrow = true))
        assertTrue(next.narrow)
        assertFalse(next.normalChatOpen)
        assertFalse(next.fullscreenChatOpen)
        assertTrue(next.normalVotingOpen)
        assertTrue(next.fullscreenVotingOpen)
        // widening again changes only the flag; the same event twice is a no-op
        val wide = reduce(next, ConferencePanelEvent.ViewportChanged(narrow = false))
        assertFalse(wide.narrow)
        assertEquals(next, reduce(next, ConferencePanelEvent.ViewportChanged(narrow = true)))
    }

    @Test
    fun railLayout_withoutTheVotingPanel_isTheOldOne() {
        val layout = conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenRosterOpen = true, fullscreenChatOpen = true))
        assertTrue(layout.rosterCapped)
        assertTrue(layout.chatFlexible)
        assertEquals(ConferenceVotingRailShare.NONE, layout.votingShare)
        assertFalse(layout.rosterCap30)
    }

    @Test
    fun railLayout_votingAlone_takesTheWholeRail() {
        val layout = conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenVotingOpen = true))
        assertEquals(ConferenceVotingRailShare.FULL, layout.votingShare)
        assertTrue(layout.railOccupied)
        assertFalse(layout.rosterCapped)
        assertFalse(layout.chatFlexible)
        assertFalse(layout.rosterCap30)
    }

    @Test
    fun railLayout_votingWithOnePanel_takesSixtyPercent_andTheOtherIsNotCapped() {
        val withRoster =
            conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenVotingOpen = true, fullscreenRosterOpen = true))
        val withChat = conferenceRailLayout(ConferencePanelState(fullscreen = true, fullscreenVotingOpen = true, fullscreenChatOpen = true))
        for (layout in listOf(withRoster, withChat)) {
            assertEquals(ConferenceVotingRailShare.MAJOR_60, layout.votingShare)
            assertFalse(layout.rosterCapped)
            assertFalse(layout.rosterCap30)
        }
    }

    @Test
    fun railLayout_allThreeOpen_capsTheRosterAtThirty_andGivesTheVotingPanelAtLeastForty() {
        val layout =
            conferenceRailLayout(
                ConferencePanelState(
                    fullscreen = true,
                    fullscreenVotingOpen = true,
                    fullscreenRosterOpen = true,
                    fullscreenChatOpen = true,
                ),
            )
        assertEquals(ConferenceVotingRailShare.MIN_40, layout.votingShare)
        assertTrue(layout.rosterCap30)
        assertFalse(layout.rosterCapped, "the 40% cap of the two-panel layout is replaced, not added")
    }

    @Test
    fun railLayout_readsTheActiveContext_only() {
        val layout = conferenceRailLayout(ConferencePanelState(fullscreen = false, normalVotingOpen = true, normalRosterOpen = false))
        assertEquals(ConferenceVotingRailShare.FULL, layout.votingShare)
        val other = conferenceRailLayout(ConferencePanelState(fullscreen = true, normalVotingOpen = true, fullscreenVotingOpen = false))
        assertEquals(ConferenceVotingRailShare.NONE, other.votingShare)
    }
}
