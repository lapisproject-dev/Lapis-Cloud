package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.70 -- the pure part of the mini bar: what it paints for a state, and what it announces for a step between two states. */
class ConferenceDockBarTest {
    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = true,
            screenSharing = false,
            recording = false,
            streaming = false,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    private val detachedLive = DockState.Live(attached = false, snapshot = snap)
    private val attachedLive = DockState.Live(attached = true, snapshot = snap)

    @Test
    fun theBarIsHidden_whileIdleOrAttached() {
        assertFalse(dockBarViewOf(DockState.Idle, narrow = false).visible)
        assertFalse(dockBarViewOf(attachedLive, narrow = false).visible)
        assertFalse(dockBarViewOf(DockState.Joining(attached = true), narrow = false).visible)
        assertTrue(dockBarViewOf(detachedLive, narrow = false).visible)
    }

    @Test
    fun live_showsTheDeviceStateAndLeave_noStopShareWithoutASharing() {
        val view = dockBarViewOf(detachedLive.copy(snapshot = snap.copy(micOn = true, cameraOn = false)), narrow = false)
        assertTrue(view.showDevices && view.showLeave)
        assertTrue(view.micPressed)
        assertFalse(view.cameraPressed)
        assertFalse(view.screenShare)
        assertNull(view.stopped)
        assertTrue(view.consentBadges.isEmpty())
    }

    @Test
    fun consentBadges_carryAGlyphAndAWord_andStreamPausedHasItsOwnText() {
        val recording = dockBarViewOf(detachedLive.copy(snapshot = snap.copy(recording = true)), narrow = false)
        assertEquals(listOf("● Aufzeichnung"), recording.consentBadges.map(::resolvedAttributeText))
        val streaming = dockBarViewOf(detachedLive.copy(snapshot = snap.copy(streaming = true)), narrow = false)
        assertEquals(listOf("◆ Live-Stream"), streaming.consentBadges.map(::resolvedAttributeText))
        val paused = dockBarViewOf(detachedLive.copy(snapshot = snap.copy(streaming = true, streamPaused = true)), narrow = false)
        assertEquals(listOf("◆ Live-Stream pausiert"), paused.consentBadges.map(::resolvedAttributeText))
        val both = dockBarViewOf(detachedLive.copy(snapshot = snap.copy(recording = true, streaming = true)), narrow = false)
        assertEquals(2, both.consentBadges.size)
    }

    @Test
    fun voteAndScreenShareBadges_showUp() {
        val view = dockBarViewOf(detachedLive.copy(snapshot = snap.copy(voteOpen = true, screenSharing = true)), narrow = false)
        assertTrue(view.voteBadge)
        assertTrue(view.screenShare)
    }

    @Test
    fun narrow_isTwoRows_onlyWithSomethingToShowAboveTheControls() {
        assertFalse(dockBarViewOf(detachedLive, narrow = true).twoRows)
        assertTrue(dockBarViewOf(detachedLive.copy(snapshot = snap.copy(recording = true, streaming = true)), narrow = true).twoRows)
        assertFalse(dockBarViewOf(detachedLive.copy(snapshot = snap.copy(recording = true)), narrow = false).twoRows)
    }

    @Test
    fun joiningAndResolving_offerNoDeviceControls_joiningOffersLeave() {
        val joining = dockBarViewOf(DockState.Joining(attached = false), narrow = false)
        assertTrue(joining.visible && joining.showLeave)
        assertFalse(joining.showDevices)
        val resolving = dockBarViewOf(DockState.Resolving(attached = false, snapshot = snap), narrow = false)
        assertTrue(resolving.visible)
        assertFalse(resolving.showDevices || resolving.showLeave)
    }

    @Test
    fun everyEndState_hasItsOwnTextAndNoLiveControls() {
        val texts =
            DockStopReason.entries.map { reason ->
                val view = dockBarViewOf(DockState.Stopped(reason, attached = false), narrow = false)
                assertEquals(reason, view.stopped)
                assertFalse(view.showDevices || view.showLeave)
                resolvedAttributeText(view.statusText)
            }
        assertEquals(DockStopReason.entries.size, texts.toSet().size)
    }

    @Test
    fun theAccessibleName_namesTheStatus_withoutARoomTitleOrAName() {
        val view = dockBarViewOf(detachedLive, narrow = false)
        assertEquals("Zur Konferenz: Besprechung läuft", view.accessibleName)
    }

    // ── V1.9.71: the floating window ─────────────────────────────────────────

    @Test
    fun floatShown_hidesTheBar() {
        assertTrue(dockBarViewOf(detachedLive, narrow = false, floatShown = false).visible)
        assertFalse(dockBarViewOf(detachedLive, narrow = false, floatShown = true).visible)
    }

    @Test
    fun theFloatButton_existsOnlyOnAWideViewportWhileTheCallIsLive() {
        assertTrue(dockBarViewOf(detachedLive, narrow = false, wide = true).showFloatButton)
        assertFalse(dockBarViewOf(detachedLive, narrow = false, wide = false).showFloatButton)
        assertTrue(dockBarViewOf(DockState.Resolving(attached = false, snapshot = snap), narrow = false, wide = true).showFloatButton)
        assertFalse(dockBarViewOf(DockState.Joining(attached = false), narrow = false, wide = true).showFloatButton)
        assertFalse(dockBarViewOf(DockState.Stopped(DockStopReason.ENDED, attached = false), narrow = false, wide = true).showFloatButton)
    }

    @Test
    fun theFirstDetachNote_saysFloatingWindow_whenTheWindowStandsForTheCall() {
        val floating = assertNotNull(dockBarAnnouncement(attachedLive, detachedLive, alreadyAnnounced = false, floating = true))
        assertEquals(DockAnnouncementKind.POLITE, floating.kind)
        assertTrue(floating.text.contains("Konferenz als schwebendes Fenster"), floating.text)
        assertTrue(floating.text.contains("Mikrofon oder Kamera sind eingeschaltet."), "the devices note stays: ${floating.text}")
    }

    // ── announcements ─────────────────────────────────────────────────────────

    @Test
    fun noAnnouncement_whileTheViewIsShown_orIdle() {
        assertNull(dockBarAnnouncement(detachedLive, attachedLive.copy(snapshot = snap.copy(recording = true)), alreadyAnnounced = false))
        assertNull(dockBarAnnouncement(detachedLive, DockState.Idle, alreadyAnnounced = false))
    }

    @Test
    fun theFirstUndocking_isOnePoliteNote_withADeviceHintOnlyWhenADeviceIsOn_andOnlyOnce() {
        val polite = assertNotNull(dockBarAnnouncement(attachedLive, detachedLive, alreadyAnnounced = false))
        assertEquals(DockAnnouncementKind.POLITE, polite.kind)
        assertTrue(polite.text.contains("im Hintergrund"))
        assertTrue(polite.text.contains("Mikrofon oder Kamera sind eingeschaltet"))
        val quiet =
            assertNotNull(
                dockBarAnnouncement(attachedLive, detachedLive.copy(snapshot = snap.copy(micOn = false, cameraOn = false)), false),
            )
        assertFalse(quiet.text.contains("Mikrofon oder Kamera"))
        assertNull(dockBarAnnouncement(attachedLive, detachedLive, alreadyAnnounced = true))
    }

    @Test
    fun everyChangeOfRecordingOrStream_isAnAlert_whileUndocked() {
        fun alertFor(
            before: DockSnapshot,
            after: DockSnapshot,
        ) = assertNotNull(
            dockBarAnnouncement(detachedLive.copy(snapshot = before), detachedLive.copy(snapshot = after), alreadyAnnounced = true),
        )
        assertEquals(DockAnnouncementKind.ALERT, alertFor(snap, snap.copy(recording = true)).kind)
        assertTrue(alertFor(snap, snap.copy(recording = true)).text.contains("Aufzeichnung läuft"))
        assertTrue(alertFor(snap.copy(recording = true), snap).text.contains("Aufzeichnung beendet"))
        assertTrue(alertFor(snap, snap.copy(streaming = true)).text.contains("Live-Stream läuft"))
        assertTrue(alertFor(snap.copy(streaming = true), snap).text.contains("Live-Stream beendet"))
        assertTrue(alertFor(snap.copy(streaming = true), snap.copy(streaming = true, streamPaused = true)).text.contains("pausiert"))
        assertTrue(
            alertFor(snap.copy(streaming = true, streamPaused = true), snap.copy(streaming = true)).text.contains("fortgesetzt"),
        )
    }

    @Test
    fun switchingMicOrCamera_isNeverAnnounced() {
        assertNull(
            dockBarAnnouncement(detachedLive, detachedLive.copy(snapshot = snap.copy(micOn = false)), alreadyAnnounced = true),
        )
        assertNull(
            dockBarAnnouncement(detachedLive, detachedLive.copy(snapshot = snap.copy(cameraOn = false)), alreadyAnnounced = true),
        )
    }

    @Test
    fun anInvoluntaryEnd_isAnAlertOrAStatus_byItsReason() {
        fun kindOf(reason: DockStopReason) =
            assertNotNull(dockBarAnnouncement(detachedLive, DockState.Stopped(reason, attached = false), alreadyAnnounced = true)).kind
        assertEquals(DockAnnouncementKind.ALERT, kindOf(DockStopReason.DUPLICATE_IDENTITY))
        assertEquals(DockAnnouncementKind.ALERT, kindOf(DockStopReason.REJOIN_EXHAUSTED))
        assertEquals(DockAnnouncementKind.POLITE, kindOf(DockStopReason.ENDED))
        assertEquals(DockAnnouncementKind.POLITE, kindOf(DockStopReason.CONNECT_FAILED))
    }
}
