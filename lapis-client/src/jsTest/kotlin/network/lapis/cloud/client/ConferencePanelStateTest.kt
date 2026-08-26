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
 */
class ConferencePanelStateTest {
    @Test
    fun conferencePanelReduce_default_rosterVisibleTrue_chatVisibleFalse_fullscreenFalse() {
        val state = ConferencePanelState()
        assertFalse(state.fullscreen)
        assertTrue(state.rosterVisible())
        assertFalse(state.chatVisible())
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
        val state = ConferencePanelState(fullscreenRosterOpen = true, fullscreenChatOpen = true)
        val next = conferencePanelReduce(state, ConferencePanelEvent.FullscreenEntered)
        assertTrue(next.fullscreen)
        assertFalse(next.fullscreenRosterOpen)
        assertFalse(next.fullscreenChatOpen)
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
}
