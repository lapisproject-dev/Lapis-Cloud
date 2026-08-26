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
}
