package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- covers the pure,
 * DOM-independent functions this wave adds to `ConferenceScreen.kt` and
 * `ConferenceStreamDestinationsScreen.kt`. Same DOM-free unit-test posture as
 * [ConferenceScreenTest]/[GovernanceAuthzUiTest] -- no rendering harness exists in this module, so
 * `renderConferenceStreamDestinationsScreen`/`startStreamDialog`/the streaming control-button DOM
 * wiring in `enterCall` are out of scope here, same as every other screen's own `*ScreenTest.kt`.
 *
 * **The Wave 2 badge fix (D8, launch-blocking) is the highest-priority coverage in this file**:
 * [conferenceStatusBadgeRows_*] tests directly assert that a STREAMING-only active session renders
 * a "Live-Stream läuft" row and NEVER an "Aufzeichnung läuft" row -- the exact false-statement bug
 * finding 7 identified (LiveKit's `active_recording` flag is `true` for any active egress, including
 * a streaming-only one).
 */
class ConferenceStreamingUiTest {
    // ---------------------------------------------------------------------------------------
    // Sample factories
    // ---------------------------------------------------------------------------------------

    private fun sampleRecording(): ConferenceRecordingDto =
        ConferenceRecordingDto(
            id = "rec-1",
            roomId = "room-1",
            roomTitle = "Vorstandssitzung",
            status = ConferenceRecordingStatus.RECORDING,
            startedByMemberId = "member-1",
            startedByDisplayName = "Anna Muster",
            startedAt = LocalDateTime(2026, 8, 9, 14, 5),
            stoppedAt = null,
            readyAt = null,
            durationSeconds = null,
            accessLevel = DocumentAccessLevel.BOARD_ONLY,
            documentId = null,
            mediaUrl = null,
            fileSizeBytes = null,
            trackCount = 0,
            failureReason = null,
        )

    private fun sampleTarget(
        id: String = "target-1",
        label: String = "PdV YouTube-Kanal",
        status: ConferenceStreamTargetStatus = ConferenceStreamTargetStatus.ACTIVE,
        failureReason: String? = null,
    ) = ConferenceStreamTargetStatusDto(
        destinationId = id,
        label = label,
        platform = ConferenceStreamPlatform.YOUTUBE,
        status = status,
        retries = 0,
        failureReason = failureReason,
    )

    private fun sampleStream(
        id: String = "stream-1",
        status: ConferenceStreamStatus = ConferenceStreamStatus.LIVE,
        targets: List<ConferenceStreamTargetStatusDto> = listOf(sampleTarget()),
        failureReason: String? = null,
    ) = ConferenceStreamDto(
        id = id,
        roomId = "room-1",
        roomTitle = "Vorstandssitzung",
        status = status,
        layout = ConferenceStreamLayout.GRID,
        latencyMode = ConferenceStreamLatencyMode.STANDARD,
        startedByMemberId = "member-1",
        startedByDisplayName = "Anna Muster",
        startedAt = LocalDateTime(2026, 8, 9, 14, 5),
        pausedAt = null,
        endedAt = null,
        restartCount = 0,
        targets = targets,
        failureReason = failureReason,
    )

    // ---------------------------------------------------------------------------------------
    // conferenceStatusBadgeRows -- the Wave 2 badge fix (finding 7, design review D3/D8)
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStatusBadgeRows_neitherActive_isEmpty() {
        assertEquals(emptyList(), conferenceStatusBadgeRows(activeRecording = null, activeStream = null))
    }

    @Test
    fun conferenceStatusBadgeRows_recordingOnly_showsOnlyRecordingRow() {
        val rows = conferenceStatusBadgeRows(activeRecording = sampleRecording(), activeStream = null)
        assertEquals(1, rows.size)
        assertEquals("● Aufzeichnung läuft", rows.single().text)
    }

    @Test
    fun conferenceStatusBadgeRows_streamingOnly_showsOnlyStreamingRow_neverRecording() {
        // The exact bug finding 7 describes: a streaming-only egress must NEVER render "Aufzeichnung
        // läuft", even though LiveKit's own active_recording flag would say so.
        val rows = conferenceStatusBadgeRows(activeRecording = null, activeStream = sampleStream())
        assertEquals(1, rows.size)
        assertTrue(rows.single().text.startsWith("◆ Live-Stream läuft"))
        assertTrue(rows.none { it.text.contains("Aufzeichnung") })
    }

    @Test
    fun conferenceStatusBadgeRows_bothActive_rendersTwoDistinctRows_neverMerged() {
        val rows = conferenceStatusBadgeRows(activeRecording = sampleRecording(), activeStream = sampleStream())
        assertEquals(2, rows.size)
        assertEquals("● Aufzeichnung läuft", rows[0].text)
        assertTrue(rows[1].text.startsWith("◆ Live-Stream läuft"))
    }

    @Test
    fun conferenceStatusBadgeRows_streamingRow_namesDestinationLabels_neverUrlOrKey() {
        val stream =
            sampleStream(
                targets =
                    listOf(
                        sampleTarget(id = "t1", label = "PdV YouTube-Kanal"),
                        sampleTarget(id = "t2", label = "Vereins-PeerTube"),
                    ),
            )
        val row = conferenceStatusBadgeRows(activeRecording = null, activeStream = stream).single()
        assertEquals("◆ Live-Stream läuft → PdV YouTube-Kanal, Vereins-PeerTube", row.text)
    }

    // Live-verification fix (2026-08-09, Wave 3 verification step): pausing a real stream against
    // the real Colima stack left the badge reading "◆ Live-Stream läuft" the ENTIRE time the stream
    // was paused -- a real, reproducible false statement (see conferenceStreamBadgeVerbPhrase KDoc
    // for the full live repro). These tests pin the fix: the verb phrase and color both track
    // ConferenceStreamDto.status now, not a single hardcoded "läuft".
    @Test
    fun conferenceStatusBadgeRows_streamingRow_paused_readsUnterbrochen_neverLaeuft() {
        val row =
            conferenceStatusBadgeRows(
                activeRecording = null,
                activeStream = sampleStream(status = ConferenceStreamStatus.PAUSED),
            ).single()
        assertTrue(row.text.startsWith("◆ Live-Stream ist unterbrochen →"))
        assertFalse(row.text.contains("läuft"))
        assertEquals("secondary", row.color)
    }

    @Test
    fun conferenceStatusBadgeRows_streamingRow_starting_readsWirdGestartet() {
        val row =
            conferenceStatusBadgeRows(
                activeRecording = null,
                activeStream = sampleStream(status = ConferenceStreamStatus.STARTING),
            ).single()
        assertTrue(row.text.startsWith("◆ Live-Stream wird gestartet →"))
    }

    @Test
    fun conferenceStatusBadgeRows_streamingRow_stopping_readsWirdBeendet() {
        val row =
            conferenceStatusBadgeRows(
                activeRecording = null,
                activeStream = sampleStream(status = ConferenceStreamStatus.STOPPING),
            ).single()
        assertTrue(row.text.startsWith("◆ Live-Stream wird beendet →"))
    }

    @Test
    fun conferenceStreamBadgeVerbPhrase_everyStatus_hasADistinctNonEmptyPhrase() {
        val phrases = ConferenceStreamStatus.entries.map { conferenceStreamBadgeVerbPhrase(it) }
        assertTrue(phrases.all { it.isNotBlank() })
        // ENDED and FAILED deliberately share "ist beendet" (both terminal, activeStream is null by
        // the time either is reachable from real server state) -- every other status is distinct.
        val nonTerminal =
            ConferenceStreamStatus.entries.filterNot {
                it == ConferenceStreamStatus.ENDED ||
                    it == ConferenceStreamStatus.FAILED
            }
        assertEquals(nonTerminal.size, nonTerminal.map { conferenceStreamBadgeVerbPhrase(it) }.distinct().size)
    }

    // ---------------------------------------------------------------------------------------
    // conferenceMediaDocumentTitle -- extends the "● " prefix to cover streaming too
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceMediaDocumentTitle_neitherActive_isUnprefixed() {
        assertEquals("Videokonferenz", conferenceMediaDocumentTitle("Videokonferenz", isRecording = false, isStreaming = false))
    }

    @Test
    fun conferenceMediaDocumentTitle_recordingOnly_isPrefixed() {
        assertEquals("● Videokonferenz", conferenceMediaDocumentTitle("Videokonferenz", isRecording = true, isStreaming = false))
    }

    @Test
    fun conferenceMediaDocumentTitle_streamingOnly_isPrefixed() {
        assertEquals("● Videokonferenz", conferenceMediaDocumentTitle("Videokonferenz", isRecording = false, isStreaming = true))
    }

    @Test
    fun conferenceMediaDocumentTitle_bothActive_isPrefixedOnce_notTwice() {
        assertEquals("● Videokonferenz", conferenceMediaDocumentTitle("Videokonferenz", isRecording = true, isStreaming = true))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceStreamStatusLabel / conferenceStreamTargetStatusLabel -- D7/D10 German copy tables
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamStatusLabel_mapsEveryStatusToPlainGermanCopy() {
        assertEquals("Verbindung wird hergestellt …", conferenceStreamStatusLabel(ConferenceStreamStatus.STARTING))
        assertEquals("Live", conferenceStreamStatusLabel(ConferenceStreamStatus.LIVE))
        assertEquals("Unterbrochen", conferenceStreamStatusLabel(ConferenceStreamStatus.PAUSED))
        assertEquals("Wird beendet …", conferenceStreamStatusLabel(ConferenceStreamStatus.STOPPING))
        assertEquals("Beendet", conferenceStreamStatusLabel(ConferenceStreamStatus.ENDED))
        assertEquals("Fehlgeschlagen", conferenceStreamStatusLabel(ConferenceStreamStatus.FAILED))
    }

    @Test
    fun conferenceStreamTargetStatusLabel_mapsEveryStatusToThreeDistinctStates() {
        // D7: never a binary "streaming: yes/no" -- these four map to distinct, honest copy.
        assertEquals("Verbindung wird hergestellt …", conferenceStreamTargetStatusLabel(ConferenceStreamTargetStatus.PENDING))
        assertEquals("Live", conferenceStreamTargetStatusLabel(ConferenceStreamTargetStatus.ACTIVE))
        assertEquals("Beendet", conferenceStreamTargetStatusLabel(ConferenceStreamTargetStatus.FINISHED))
        assertEquals("Fehlgeschlagen", conferenceStreamTargetStatusLabel(ConferenceStreamTargetStatus.FAILED))
    }

    @Test
    fun conferenceStreamLayoutLabel_mapsEveryLayoutToGermanCopy() {
        assertEquals("Galerie", conferenceStreamLayoutLabel(ConferenceStreamLayout.GRID))
        assertEquals("Sprecher", conferenceStreamLayoutLabel(ConferenceStreamLayout.SPEAKER))
        assertEquals("Einzelne Person", conferenceStreamLayoutLabel(ConferenceStreamLayout.SINGLE_PARTICIPANT))
    }

    @Test
    fun conferenceStreamLatencyModeLabel_mapsBothModesToGermanCopy() {
        assertEquals("Standard", conferenceStreamLatencyModeLabel(ConferenceStreamLatencyMode.STANDARD))
        assertEquals("Niedrige Latenz", conferenceStreamLatencyModeLabel(ConferenceStreamLatencyMode.LOW_LATENCY))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceStreamCanStart / conferenceStreamSelectionValid -- moderator-gate mirrors
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamCanStart_moderatorNoActiveStreamAvailable_isAllowed() {
        assertTrue(conferenceStreamCanStart(canModerate = true, streamingAvailable = true, activeStream = null))
    }

    @Test
    fun conferenceStreamCanStart_nonModerator_isNeverAllowed() {
        assertFalse(conferenceStreamCanStart(canModerate = false, streamingAvailable = true, activeStream = null))
    }

    @Test
    fun conferenceStreamCanStart_streamingUnavailable_isNeverAllowed() {
        assertFalse(conferenceStreamCanStart(canModerate = true, streamingAvailable = false, activeStream = null))
    }

    @Test
    fun conferenceStreamCanStart_alreadyActiveStream_isRefused() {
        assertFalse(conferenceStreamCanStart(canModerate = true, streamingAvailable = true, activeStream = sampleStream()))
    }

    @Test
    fun conferenceStreamSelectionValid_withinBounds_isValid() {
        assertTrue(conferenceStreamSelectionValid(selectedCount = 1, maxDestinations = 3))
        assertTrue(conferenceStreamSelectionValid(selectedCount = 3, maxDestinations = 3))
    }

    @Test
    fun conferenceStreamSelectionValid_zeroOrOverCap_isInvalid() {
        assertFalse(conferenceStreamSelectionValid(selectedCount = 0, maxDestinations = 3))
        assertFalse(conferenceStreamSelectionValid(selectedCount = 4, maxDestinations = 3))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceStreamStartSummary -- design review D2's live confirm-surface copy
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamStartSummary_noneSelected_promptsForSelection() {
        assertEquals("Bitte wählen Sie mindestens ein Ziel aus.", conferenceStreamStartSummary(emptyList()))
    }

    @Test
    fun conferenceStreamStartSummary_selected_namesLabelsAndStatesIrrevocability() {
        val summary = conferenceStreamStartSummary(listOf("PdV YouTube-Kanal", "PdV Twitch"))
        assertTrue(summary.contains("PdV YouTube-Kanal"))
        assertTrue(summary.contains("PdV Twitch"))
        assertTrue(summary.contains("NICHT zurückgeholt"))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceStreamNeedsPoll -- D7's honest-per-target-status polling predicate
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamNeedsPoll_nullStream_doesNotNeedPolling() {
        assertFalse(conferenceStreamNeedsPoll(null))
    }

    @Test
    fun conferenceStreamNeedsPoll_startingOrStopping_needsPolling() {
        assertTrue(conferenceStreamNeedsPoll(sampleStream(status = ConferenceStreamStatus.STARTING)))
        assertTrue(conferenceStreamNeedsPoll(sampleStream(status = ConferenceStreamStatus.STOPPING)))
    }

    @Test
    fun conferenceStreamNeedsPoll_liveWithPendingTarget_stillNeedsPolling() {
        // The key case: top-level status has already settled to LIVE, but a per-target PENDING row
        // (the honest ~12s async LiveKit-connect window) means polling must continue regardless.
        val stream =
            sampleStream(
                status = ConferenceStreamStatus.LIVE,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.PENDING)),
            )
        assertTrue(conferenceStreamNeedsPoll(stream))
    }

    @Test
    fun conferenceStreamNeedsPoll_liveWithAllTargetsSettled_doesNotNeedPolling() {
        val stream =
            sampleStream(
                status = ConferenceStreamStatus.LIVE,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.ACTIVE)),
            )
        assertFalse(conferenceStreamNeedsPoll(stream))
    }

    @Test
    fun conferenceStreamNeedsPoll_pausedWithSettledTargets_doesNotNeedPolling() {
        val stream =
            sampleStream(
                status = ConferenceStreamStatus.PAUSED,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.FINISHED)),
            )
        assertFalse(conferenceStreamNeedsPoll(stream))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceFindStreamById
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceFindStreamById_matchFound_returnsIt() {
        val a = sampleStream(id = "stream-a")
        val b = sampleStream(id = "stream-b")
        assertEquals(b, conferenceFindStreamById(listOf(a, b), "stream-b"))
    }

    @Test
    fun conferenceFindStreamById_noMatch_returnsNull() {
        val a = sampleStream(id = "stream-a")
        assertEquals(null, conferenceFindStreamById(listOf(a), "does-not-exist"))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceStreamStartedLabel -- in-call detail line formatting
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamStartedLabel_zeroPadsHourAndMinute_andNamesLayout() {
        assertEquals(
            "Live-Stream gestartet von Anna Muster um 09:05 · Sprecher",
            conferenceStreamStartedLabel("Anna Muster", LocalDateTime(2026, 8, 9, 9, 5), ConferenceStreamLayout.SPEAKER),
        )
    }

    // ---------------------------------------------------------------------------------------
    // ConferenceStreamDestinationsScreen.kt pure helpers
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamPlatformLabel_mapsEveryPlatformToGermanCopy() {
        assertEquals("YouTube Live", conferenceStreamPlatformLabel(ConferenceStreamPlatform.YOUTUBE))
        assertEquals("Twitch", conferenceStreamPlatformLabel(ConferenceStreamPlatform.TWITCH))
        assertEquals("PeerTube", conferenceStreamPlatformLabel(ConferenceStreamPlatform.PEERTUBE))
        assertEquals("Owncast", conferenceStreamPlatformLabel(ConferenceStreamPlatform.OWNCAST))
        assertEquals("Generisches RTMP", conferenceStreamPlatformLabel(ConferenceStreamPlatform.GENERIC_RTMP))
    }

    @Test
    fun conferenceStreamPresetUrl_youtubeAndTwitch_havePrefilledUrls() {
        assertEquals("rtmp://a.rtmp.youtube.com/live2", conferenceStreamPresetUrl(ConferenceStreamPlatform.YOUTUBE))
        assertEquals("rtmp://live.twitch.tv/app", conferenceStreamPresetUrl(ConferenceStreamPlatform.TWITCH))
    }

    @Test
    fun conferenceStreamPresetUrl_selfHostedAndGeneric_haveNoPreset() {
        // Self-hosted platforms have no canonical ingest URL -- hard-coding one would be actively
        // wrong (see the wave's own scope-decisions doc).
        assertEquals(null, conferenceStreamPresetUrl(ConferenceStreamPlatform.PEERTUBE))
        assertEquals(null, conferenceStreamPresetUrl(ConferenceStreamPlatform.OWNCAST))
        assertEquals(null, conferenceStreamPresetUrl(ConferenceStreamPlatform.GENERIC_RTMP))
    }

    @Test
    fun conferenceStreamUrlLooksValid_rtmpAndRtmps_areValid() {
        assertTrue(conferenceStreamUrlLooksValid("rtmp://a.rtmp.youtube.com/live2"))
        assertTrue(conferenceStreamUrlLooksValid("rtmps://example.org/live"))
    }

    @Test
    fun conferenceStreamUrlLooksValid_otherSchemesAndBlank_areInvalid() {
        assertFalse(conferenceStreamUrlLooksValid("https://example.org"))
        assertFalse(conferenceStreamUrlLooksValid(""))
        assertFalse(conferenceStreamUrlLooksValid("   "))
    }

    @Test
    fun conferenceStreamDestinationDateLabel_zeroPadsYearMonthDay() {
        assertEquals("2026-08-09", conferenceStreamDestinationDateLabel(LocalDateTime(2026, 8, 9, 14, 5)))
    }

    // ---------------------------------------------------------------------------------------
    // CONFERENCE_STREAM_BANNER_TEXT / CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS -- exact rendered copy
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceStreamBannerText_matchesTheLiteralCopyTheUiRenders() {
        assertEquals("Diese Besprechung wird ab jetzt live gestreamt.", CONFERENCE_STREAM_BANNER_TEXT)
    }

    @Test
    fun conferenceStreamSecretBallotHinweis_neverClaimsAutomaticProtection() {
        // Design review D12/Jobs' verdict item 2 -- no UI copy anywhere may imply automatic pause
        // protection exists this wave.
        assertTrue(CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS.contains("manuell"))
        assertFalse(CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS.contains("automatisch unterbrochen"))
    }
}
