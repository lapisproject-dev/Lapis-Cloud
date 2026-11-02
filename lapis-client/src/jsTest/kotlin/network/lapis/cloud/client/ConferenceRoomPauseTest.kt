package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.client.livekit.ConferenceModerationSignal
import network.lapis.cloud.client.livekit.conferenceModerationPacket
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.RoomPauseScope
import network.lapis.cloud.shared.domain.roomPauseScopeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.254 -- the pure client side of the pause mode: scope, texts, button state, the one banner, the dock badges and the packet. */
class ConferenceRoomPauseTest {
    private fun recording(
        paused: Boolean = false,
        status: ConferenceRecordingStatus = ConferenceRecordingStatus.RECORDING,
    ) = ConferenceRecordingDto(
        id = "rec-1",
        roomId = "room-1",
        roomTitle = "Raum",
        status = status,
        startedByMemberId = "m1",
        startedByDisplayName = "A",
        startedAt = LocalDateTime(2026, 10, 30, 10, 0),
        stoppedAt = null,
        readyAt = null,
        durationSeconds = null,
        accessLevel = DocumentAccessLevel.BOARD_ONLY,
        documentId = null,
        mediaUrl = null,
        fileSizeBytes = null,
        trackCount = 0,
        failureReason = null,
        paused = paused,
    )

    private fun stream(
        status: ConferenceStreamStatus,
        reason: ConferenceStreamPauseReason? = null,
    ) = ConferenceStreamDto(
        id = "s1",
        roomId = "room-1",
        roomTitle = "Raum",
        status = status,
        layout = ConferenceStreamLayout.GRID,
        latencyMode = ConferenceStreamLatencyMode.STANDARD,
        startedByMemberId = "m1",
        startedByDisplayName = "A",
        startedAt = LocalDateTime(2026, 10, 30, 10, 0),
        pausedAt = null,
        endedAt = null,
        restartCount = 0,
        targets = emptyList(),
        failureReason = null,
        pauseReason = reason,
    )

    private fun bytes(json: String) = json.encodeToByteArray()

    // ── V1.9.255 pause feed line ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFeedLine_isShownOnlyForAnActiveOrFailedFeedOfARoomPausedStream() {
        fun line(
            state: ConferenceStreamPauseFeedState,
            status: ConferenceStreamStatus = ConferenceStreamStatus.PAUSED,
            reason: ConferenceStreamPauseReason? = ConferenceStreamPauseReason.ROOM_PAUSE,
        ) = roomPauseFeedLine(stream(status, reason).copy(pauseFeed = state))
        assertEquals("Im Stream läuft das Pausenbild.", line(ConferenceStreamPauseFeedState.ACTIVE))
        assertEquals(
            "Das Pausenbild konnte nicht gesendet werden. Der Stream ist bis zum Fortsetzen unterbrochen.",
            line(ConferenceStreamPauseFeedState.FAILED),
        )
        assertNull(line(ConferenceStreamPauseFeedState.OFF))
        assertNull(line(ConferenceStreamPauseFeedState.STARTING))
        // never for a stream that is not paused by the room, and never without a stream
        assertNull(line(ConferenceStreamPauseFeedState.ACTIVE, reason = ConferenceStreamPauseReason.SECRET_BALLOT))
        assertNull(line(ConferenceStreamPauseFeedState.ACTIVE, status = ConferenceStreamStatus.LIVE, reason = null))
        assertNull(roomPauseFeedLine(null))
    }

    // ── scope ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theSharedScopeFunction_coversTheFourCombinations() {
        assertNull(roomPauseScopeOf(recordingPaused = false, streamPaused = false))
        assertEquals(RoomPauseScope.RECORDING, roomPauseScopeOf(recordingPaused = true, streamPaused = false))
        assertEquals(RoomPauseScope.STREAM, roomPauseScopeOf(recordingPaused = false, streamPaused = true))
        assertEquals(RoomPauseScope.BOTH, roomPauseScopeOf(recordingPaused = true, streamPaused = true))
    }

    @Test
    fun theClientScope_isDerivedFromTheDtos_andOnlyTheRoomPauseReasonCounts() {
        assertNull(clientRoomPauseScope(null, null))
        assertEquals(RoomPauseScope.RECORDING, clientRoomPauseScope(recording(paused = true), null))
        assertEquals(
            RoomPauseScope.STREAM,
            clientRoomPauseScope(null, stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)),
        )
        assertEquals(
            RoomPauseScope.STREAM,
            clientRoomPauseScope(null, stream(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.ROOM_PAUSE)),
        )
        assertEquals(
            RoomPauseScope.BOTH,
            clientRoomPauseScope(recording(paused = true), stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)),
        )
        // a stream paused for a secret ballot or by hand is NOT a room pause
        assertNull(clientRoomPauseScope(null, stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)))
        assertNull(clientRoomPauseScope(null, stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL)))
        assertNull(clientRoomPauseScope(recording(paused = false), stream(ConferenceStreamStatus.LIVE)))
    }

    // ── texts ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theBannerSentence_namesWhatIsStopped_andThatPeopleMayKeepTalking() {
        assertEquals(
            "⏸ Pause: Aufzeichnung und Live-Stream sind angehalten. Sie können weiter miteinander sprechen.",
            roomPauseSentence(RoomPauseScope.BOTH),
        )
        assertEquals(
            "⏸ Pause: Die Aufzeichnung ist angehalten. Sie können weiter miteinander sprechen.",
            roomPauseSentence(RoomPauseScope.RECORDING),
        )
        assertEquals(
            "⏸ Pause: Der Live-Stream ist angehalten. Sie können weiter miteinander sprechen.",
            roomPauseSentence(RoomPauseScope.STREAM),
        )
    }

    // ── the button ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theButton_isUnavailableWithAReason_whenNothingRuns() {
        val state = pauseButtonStateOf(null, null, null, null)
        assertEquals("⏸ Pause", state.label)
        assertFalse(state.available)
        assertFalse(state.pressed)
        assertEquals("Weder Aufzeichnung noch Live-Stream laufen.", state.reason)
        // a recording that is already stopping, a stream paused by a ballot: nothing to pause
        assertFalse(pauseButtonStateOf(null, recording(status = ConferenceRecordingStatus.STOPPING), null, null).available)
        assertFalse(
            pauseButtonStateOf(
                null,
                null,
                stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT),
                null,
            ).available,
        )
    }

    @Test
    fun theButton_isAvailable_whenARecordingOrAStreamRuns() {
        assertTrue(pauseButtonStateOf(null, recording(), null, null).available)
        assertTrue(pauseButtonStateOf(null, null, stream(ConferenceStreamStatus.LIVE), null).available)
        assertTrue(pauseButtonStateOf(null, null, stream(ConferenceStreamStatus.STARTING), null).available)
        assertNull(pauseButtonStateOf(null, recording(), null, null).reason)
    }

    @Test
    fun whilePaused_theButtonResumes_pressed_andAlwaysAvailable() {
        val state = pauseButtonStateOf(RoomPauseScope.BOTH, recording(paused = true), null, null)
        assertEquals("▶ Fortsetzen", state.label)
        assertTrue(state.pressed)
        assertTrue(state.available)
        assertNull(state.reason)
    }

    @Test
    fun whileARequestRuns_theButtonIsBusy_withTheMatchingText() {
        val pausing = pauseButtonStateOf(null, recording(), null, PauseBusy.PAUSING)
        assertEquals("Wird pausiert …", pausing.label)
        assertTrue(pausing.busy)
        assertFalse(pausing.available)
        val resuming = pauseButtonStateOf(RoomPauseScope.RECORDING, recording(paused = true), null, PauseBusy.RESUMING)
        assertEquals("Wird fortgesetzt …", resuming.label)
        assertTrue(resuming.busy && !resuming.available)
    }

    // ── ONE banner for the secret ballot and the pause ───────────────────────────────────────────────────────

    @Test
    fun aPauseWithoutABallot_isTheOnlySentence() {
        val text = secretBallotBannerText(null, StreamMirrorState(), true, RoomPauseScope.RECORDING)
        assertEquals(roomPauseSentence(RoomPauseScope.RECORDING), text)
        assertNull(secretBallotBannerText(null, StreamMirrorState(), true, null))
    }

    @Test
    fun aBallotAndAPause_shareOneBanner_ballotFirst_andTheRecordingDoesNotRunOnWhileItIsPaused() {
        val live = StreamMirrorState(ConferenceStreamStatus.LIVE)
        val running = assertNotNull(secretBallotBannerText(true, live, true, null))
        assertTrue(running.endsWith("Die Aufzeichnung der Sitzung läuft weiter."))
        for (scope in listOf(RoomPauseScope.RECORDING, RoomPauseScope.BOTH)) {
            val text = assertNotNull(secretBallotBannerText(true, live, true, scope))
            assertTrue(text.startsWith("Geheime Wahl läuft"), "ballot sentence first: $text")
            assertFalse(text.contains("Die Aufzeichnung der Sitzung läuft weiter."), "$scope: the recording is stopped")
            assertTrue(text.endsWith(roomPauseSentence(scope)), "$text")
        }
        // a pause of the stream alone leaves the recording running, so the ballot sentence keeps its note
        val streamOnly = assertNotNull(secretBallotBannerText(true, live, true, RoomPauseScope.STREAM))
        assertTrue(streamOnly.contains("Die Aufzeichnung der Sitzung läuft weiter."))
    }

    // ── the dock ────────────────────────────────────────────────────────────────────────────────────────────

    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = true,
            screenSharing = false,
            recording = true,
            streaming = true,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    @Test
    fun whilePaused_oneBadgeReplacesTheBadgesOfWhatIsStopped() {
        fun badges(s: DockSnapshot) = captureBadges(s).map(::resolvedAttributeText)
        assertEquals(listOf("● Aufzeichnung", "◆ Live-Stream"), badges(snap))
        assertEquals(listOf("⏸ Pause"), badges(snap.copy(roomPause = RoomPauseScope.BOTH)))
        assertEquals(listOf("⏸ Pause", "◆ Live-Stream"), badges(snap.copy(roomPause = RoomPauseScope.RECORDING)))
        assertEquals(listOf("⏸ Pause", "● Aufzeichnung"), badges(snap.copy(roomPause = RoomPauseScope.STREAM)))
    }

    @Test
    fun theSecretBallotPause_keepsItsOwnBadge_andTheRecordingStaysListed() {
        val ballot = snap.copy(streamPaused = true)
        assertEquals(listOf("● Aufzeichnung", "◆ Live-Stream pausiert"), captureBadges(ballot).map(::resolvedAttributeText))
    }

    @Test
    fun theBarAndTheFloatingWindow_paintTheSameBadges() {
        val state = DockState.Live(attached = false, snapshot = snap.copy(roomPause = RoomPauseScope.BOTH))
        val bar = dockBarViewOf(state, narrow = false)
        val window = floatWindowViewOf(state)
        assertEquals(listOf("⏸ Pause"), bar.consentBadges.map(::resolvedAttributeText))
        assertEquals(bar.consentBadges, window.consentBadges)
    }

    @Test
    fun theStartOfAPause_isAnnouncedWithWhatItStops_andItsEndToo() {
        val before = DockState.Live(attached = false, snapshot = snap)
        val paused = DockState.Live(attached = false, snapshot = snap.copy(roomPause = RoomPauseScope.BOTH))
        val start = assertNotNull(dockBarAnnouncement(before, paused, alreadyAnnounced = true))
        assertTrue(start.text.contains("Pause: Aufzeichnung und Live-Stream angehalten"), start.text)
        val end = assertNotNull(dockBarAnnouncement(paused, before, alreadyAnnounced = true))
        assertTrue(end.text.contains("Pause beendet"), end.text)
    }

    // ── the packet ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCapturePackets_areAcceptedOnlyFromTheServer_andOnlyInTheirExactShape() {
        assertEquals(
            ConferenceModerationSignal.CapturePauseChanged,
            conferenceModerationPacket(false, bytes("""{"v":1,"k":"capture_pause"}""")),
        )
        assertEquals(
            ConferenceModerationSignal.CapturePauseChanged,
            conferenceModerationPacket(false, bytes("""{"v":1,"k":"capture_resume"}""")),
        )
        // a participant has can_publish_data: a packet with a sender is a forgery
        assertNull(conferenceModerationPacket(true, bytes("""{"v":1,"k":"capture_pause"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"capture_pause","x":1}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":2,"k":"capture_pause"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"capture_stop"}""")))
    }

    // ── the stream badge text ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aStreamPausedByTheRoom_hasItsOwnVerbPhrase() {
        assertEquals(
            "ist wegen der Pause angehalten",
            conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE),
        )
        assertEquals("ist unterbrochen", conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL))
        assertTrue(conferenceStreamNeedsPoll(stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)))
        assertFalse(conferenceStreamNeedsPoll(stream(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL)))
    }
}
