package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
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
        pauseReason: ConferenceStreamPauseReason? = null,
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
        pauseReason = pauseReason,
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
        // pauseReason = null throughout -- this test is about the per-status phrase table, the
        // PAUSED-specific pauseReason branching gets its own dedicated tests below.
        val phrases = ConferenceStreamStatus.entries.map { conferenceStreamBadgeVerbPhrase(it, null) }
        assertTrue(phrases.all { it.isNotBlank() })
        // ENDED and FAILED deliberately share "ist beendet" (both terminal, activeStream is null by
        // the time either is reachable from real server state) -- every other status is distinct.
        val nonTerminal =
            ConferenceStreamStatus.entries.filterNot {
                it == ConferenceStreamStatus.ENDED ||
                    it == ConferenceStreamStatus.FAILED
            }
        assertEquals(nonTerminal.size, nonTerminal.map { conferenceStreamBadgeVerbPhrase(it, null) }.distinct().size)
    }

    // V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- the two new phrases
    // (conferenceStreamBadgeVerbPhrase KDoc) plus a regression check that MANUAL/null pauseReason
    // still produce the pre-Wave-9 plain "ist unterbrochen" phrase.

    @Test
    fun conferenceStreamBadgeVerbPhrase_pausing_readsWirdAngehalten_regardlessOfPauseReason() {
        assertEquals("wird angehalten", conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSING, null))
        assertEquals(
            "wird angehalten",
            conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.MANUAL),
        )
        assertEquals(
            "wird angehalten",
            conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT),
        )
    }

    @Test
    fun conferenceStreamBadgeVerbPhrase_pausedWithSecretBallotReason_readsSecretBallotSpecificPhrase() {
        assertEquals(
            "ist wegen geheimer Abstimmung unterbrochen",
            conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT),
        )
    }

    @Test
    fun conferenceStreamBadgeVerbPhrase_pausedWithManualOrNullReason_stillReadsPlainIstUnterbrochen() {
        // Regression check -- the new pauseReason parameter must not change the pre-Wave-9 behaviour
        // for a manually paused (or reason-less) stream.
        assertEquals(
            "ist unterbrochen",
            conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL),
        )
        assertEquals("ist unterbrochen", conferenceStreamBadgeVerbPhrase(ConferenceStreamStatus.PAUSED, null))
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
        // V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- the fail-closed PAUSING interim
        // state's own copy, distinct from both LIVE and PAUSED.
        assertEquals("Wird angehalten …", conferenceStreamStatusLabel(ConferenceStreamStatus.PAUSING))
        assertEquals("Unterbrochen", conferenceStreamStatusLabel(ConferenceStreamStatus.PAUSED))
        assertEquals("Wird beendet …", conferenceStreamStatusLabel(ConferenceStreamStatus.STOPPING))
        assertEquals("Beendet", conferenceStreamStatusLabel(ConferenceStreamStatus.ENDED))
        assertEquals("Fehlgeschlagen", conferenceStreamStatusLabel(ConferenceStreamStatus.FAILED))
    }

    @Test
    fun conferenceStreamStatusColor_mapsEveryStatusToItsDesignColor() {
        // V1.0 Wave 9 addition: PAUSING reads "warning" (a fail-closed transitional state, not yet
        // an alarm), same hue as STARTING/STOPPING -- see conferenceStreamStatusColor KDoc.
        assertEquals("warning", conferenceStreamStatusColor(ConferenceStreamStatus.STARTING))
        assertEquals("danger", conferenceStreamStatusColor(ConferenceStreamStatus.LIVE))
        assertEquals("warning", conferenceStreamStatusColor(ConferenceStreamStatus.PAUSING))
        assertEquals("secondary", conferenceStreamStatusColor(ConferenceStreamStatus.PAUSED))
        assertEquals("warning", conferenceStreamStatusColor(ConferenceStreamStatus.STOPPING))
        assertEquals("secondary", conferenceStreamStatusColor(ConferenceStreamStatus.ENDED))
        assertEquals("danger", conferenceStreamStatusColor(ConferenceStreamStatus.FAILED))
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

    // V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- PAUSING always needs polling (same
    // fail-closed crash-recovery reasoning as STARTING/STOPPING), and PAUSED needs polling ONLY when
    // pauseReason == SECRET_BALLOT (the server auto-resumes that case with no push to this client).
    // A manually paused stream (MANUAL/null) must NOT regress to needing polling.

    @Test
    fun conferenceStreamNeedsPoll_pausing_needsPolling_evenWithSettledTargets() {
        val stream =
            sampleStream(
                status = ConferenceStreamStatus.PAUSING,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.FINISHED)),
            )
        assertTrue(conferenceStreamNeedsPoll(stream))
    }

    @Test
    fun conferenceStreamNeedsPoll_pausedWithSecretBallotReason_needsPolling() {
        // The critical Wave 9 case: the server transitions this back to LIVE entirely on its own
        // once the secret ballot closes, with no LiveKit push reaching this client.
        val stream =
            sampleStream(
                status = ConferenceStreamStatus.PAUSED,
                pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.FINISHED)),
            )
        assertTrue(conferenceStreamNeedsPoll(stream))
    }

    @Test
    fun conferenceStreamNeedsPoll_pausedWithManualOrNullReason_stillDoesNotNeedPolling() {
        // Regression check -- only a moderator's own click can resume a manually paused stream, and
        // that click already updates activeStreamDto directly, so this must stay false.
        val manuallyPaused =
            sampleStream(
                status = ConferenceStreamStatus.PAUSED,
                pauseReason = ConferenceStreamPauseReason.MANUAL,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.FINISHED)),
            )
        assertFalse(conferenceStreamNeedsPoll(manuallyPaused))
        val reasonlessPaused =
            sampleStream(
                status = ConferenceStreamStatus.PAUSED,
                pauseReason = null,
                targets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.FINISHED)),
            )
        assertFalse(conferenceStreamNeedsPoll(reasonlessPaused))
    }

    @Test
    fun conferenceStreamNeedsPoll_fullTruthTable_acrossAllStatusesAndPauseReasons() {
        // The whole truth table in one place, so a future regression anywhere in this predicate is
        // caught -- not just the isolated Wave 9 cases above. All targets settled (ACTIVE) throughout,
        // so only the status/pauseReason branching is under test here, not the per-target PENDING path
        // (covered separately by conferenceStreamNeedsPoll_liveWithPendingTarget_stillNeedsPolling).
        data class Case(
            val status: ConferenceStreamStatus,
            val pauseReason: ConferenceStreamPauseReason?,
            val expectedNeedsPoll: Boolean,
        )
        val settledTargets = listOf(sampleTarget(status = ConferenceStreamTargetStatus.ACTIVE))
        val cases =
            listOf(
                Case(ConferenceStreamStatus.STARTING, null, true),
                Case(ConferenceStreamStatus.LIVE, null, false),
                Case(ConferenceStreamStatus.PAUSING, null, true),
                Case(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.MANUAL, true),
                Case(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT, true),
                Case(ConferenceStreamStatus.PAUSED, null, false),
                Case(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL, false),
                Case(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT, true),
                Case(ConferenceStreamStatus.STOPPING, null, true),
                Case(ConferenceStreamStatus.ENDED, null, false),
                Case(ConferenceStreamStatus.FAILED, null, false),
            )
        for (case in cases) {
            val stream = sampleStream(status = case.status, pauseReason = case.pauseReason, targets = settledTargets)
            assertEquals(
                case.expectedNeedsPoll,
                conferenceStreamNeedsPoll(stream),
                "status=${case.status} pauseReason=${case.pauseReason} expected needsPoll=${case.expectedNeedsPoll}",
            )
        }
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
    fun conferenceStreamSecretBallotHinweis_statesAutomaticProtectionExists() {
        // V1.0 Wave 9 "Stream-Pause bei geheimen Abstimmungen" REPLACED the pre-Wave-9 "no automatic
        // protection exists" copy (design review D12/Jobs' verdict item 2 applied to THAT wave) --
        // the server now DOES pause/resume the stream automatically, see
        // CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS KDoc. Pin the new promise and its load-bearing
        // precondition instead of the stale pre-Wave-9 assertions this test used to make.
        assertTrue(CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS.contains("unterbricht der Server den Stream automatisch"))
        assertTrue(
            CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS.contains("setzt ihn nach dem Ende der Stimmabgabe von selbst fort"),
        )
        // Security-audit MAJOR-3 wording fix -- the pre-fix copy overstated "Diese Sperre lässt
        // sich nicht abschalten" (setRoomMeeting's Lösen-Pfad DOES let BOARD/ADMIN change the
        // binding outside a running/imminent secret ballot). Pin the corrected, honest claim
        // instead: the binding cannot be changed WHILE a ballot is running or imminent.
        assertTrue(
            CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS.contains(
                "kann niemand -- auch nicht der Raum-Ersteller -- diese Zuordnung ändern oder lösen",
            ),
        )
        // Load-bearing precondition (KDoc: "without meetingBindingRow's binding this protection is
        // completely inert") -- must not be dropped from the copy.
        assertTrue(CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS.contains("Sitzung zugeordnet"))
    }
}
