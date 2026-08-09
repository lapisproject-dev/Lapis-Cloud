package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- covers the pure, DOM-independent functions
 * factored out of `ConferenceScreen.kt` ([conferenceIsModerator]/[conferenceCanRemove], the client
 * mirror of `IConferenceService.endRoom`/`.removeParticipant`'s server-side moderator gate;
 * [conferenceTileKind], the `publication.source` classification that decides grid-tile vs.
 * full-width-stage placement; [conferenceInitials], the D4 camera-off avatar-placeholder text) plus
 * a [ConferenceChatMessage] encode/decode round trip against the same `kotlinx.serialization.json.Json`
 * codec `LiveKitRoomSession.sendChat`/its `DataReceived` handler actually use on the wire. Same
 * DOM-free unit-test posture as [GovernanceAuthzUiTest]/[AuctionScreenTest] -- no rendering harness
 * exists in this module, so `renderConferenceScreen`/`enterCall`/the raw-DOM tile grid are out of
 * scope here, same as every other screen's `*ScreenTest.kt`.
 *
 * **Wave 2 "Aufzeichnung" addition**: [recordingCanStart] (the moderator-start-button gate mirroring
 * `ConferenceRecordingService.startRecording`'s server-side checks), [conferenceRecordingStatusLabel]/
 * [conferenceRecordingAccessLevelLabel] (design review D10/D7 German copy tables),
 * [CONFERENCE_RECORDING_BANNER_TEXT] (D3's exact notice-banner copy), [conferenceRecordingStartedLabel]/
 * [conferenceRecordingDurationLabel]/[conferenceRecordingDocumentTitle]/[conferenceRecordingFileSizeLabel]
 * (formatting helpers), and [conferenceRecordingListSorted] (D12's FAILED-first, otherwise-stable
 * Lobby ordering). `ConferenceRecordingsPanel.kt`'s own DOM rendering is out of scope here for the
 * same "no rendering harness" reason.
 *
 * **Review-round-1 fix (2026-08-09) addition**: [conferenceRecordingNeedsPoll]/
 * [conferenceFindRecordingById], the two pure helpers backing `enterCall`'s own
 * `pollInFlightRecordingStatus` loop -- the fix for the disclosed gap where the in-call moderator
 * button could freeze on a disabled "Aufzeichnung wird beendet ..." label past the recording's
 * actual terminal state (see `ConferenceScreen.kt`'s own KDoc on that loop for the full story).
 */
class ConferenceScreenTest {
    // ---------------------------------------------------------------------------------------
    // conferenceIsModerator -- mirrors IConferenceService's "creator OR global BOARD/ADMIN" gate
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceIsModerator_roomCreator_isModerator() {
        assertTrue(
            conferenceIsModerator(localMemberId = "member-1", creatorMemberId = "member-1", isBoardOrAdmin = false),
        )
    }

    @Test
    fun conferenceIsModerator_boardOrAdmin_isModeratorEvenIfNotCreator() {
        assertTrue(
            conferenceIsModerator(localMemberId = "member-2", creatorMemberId = "member-1", isBoardOrAdmin = true),
        )
    }

    @Test
    fun conferenceIsModerator_plainParticipant_isNotModerator() {
        assertFalse(
            conferenceIsModerator(localMemberId = "member-2", creatorMemberId = "member-1", isBoardOrAdmin = false),
        )
    }

    @Test
    fun conferenceIsModerator_nullLocalMemberId_isNeverModerator() {
        // Session not yet loaded -- must never grant moderator standing, even against a creator id
        // that happens to be null/blank in some degenerate caller.
        assertFalse(
            conferenceIsModerator(localMemberId = null, creatorMemberId = "member-1", isBoardOrAdmin = true),
        )
    }

    // ---------------------------------------------------------------------------------------
    // conferenceCanRemove -- moderator can remove anyone except the creator and except themselves
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceCanRemove_moderatorTargetingPlainParticipant_isAllowed() {
        assertTrue(
            conferenceCanRemove(
                targetMemberId = "member-2",
                localMemberId = "member-1",
                creatorMemberId = "member-1",
                canModerate = true,
            ),
        )
    }

    @Test
    fun conferenceCanRemove_nonModerator_isNeverAllowed() {
        assertFalse(
            conferenceCanRemove(
                targetMemberId = "member-2",
                localMemberId = "member-3",
                creatorMemberId = "member-1",
                canModerate = false,
            ),
        )
    }

    @Test
    fun conferenceCanRemove_targetingTheCreator_isRefused() {
        // Mirrors IConferenceService.removeParticipant's own server-side ConflictException refusal.
        assertFalse(
            conferenceCanRemove(
                targetMemberId = "member-1",
                localMemberId = "member-1",
                creatorMemberId = "member-1",
                canModerate = true,
            ),
        )
    }

    @Test
    fun conferenceCanRemove_targetingSelf_isRefused() {
        // A BOARD/ADMIN moderator (not the creator) must not get a self-removal button either --
        // use "Verlassen" instead.
        assertFalse(
            conferenceCanRemove(
                targetMemberId = "member-2",
                localMemberId = "member-2",
                creatorMemberId = "member-1",
                canModerate = true,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // conferenceTileKind -- publication.source string classification
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceTileKind_screenShare_isClassifiedAsScreenShare() {
        assertEquals(ConferenceTileKind.SCREEN_SHARE, conferenceTileKind("screen_share"))
    }

    @Test
    fun conferenceTileKind_camera_isClassifiedAsCamera() {
        assertEquals(ConferenceTileKind.CAMERA, conferenceTileKind("camera"))
    }

    @Test
    fun conferenceTileKind_microphoneAndUnknownSources_areClassifiedAsOther() {
        assertEquals(ConferenceTileKind.OTHER, conferenceTileKind("microphone"))
        assertEquals(ConferenceTileKind.OTHER, conferenceTileKind("unknown-future-source"))
        assertEquals(ConferenceTileKind.OTHER, conferenceTileKind(""))
    }

    // ---------------------------------------------------------------------------------------
    // conferenceInitials -- D4 camera-off avatar placeholder text
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceInitials_twoWordName_usesFirstLetterOfEach() {
        assertEquals("AM", conferenceInitials("Anna Muster"))
    }

    @Test
    fun conferenceInitials_singleWordName_usesFirstTwoLetters() {
        assertEquals("AN", conferenceInitials("Anna"))
    }

    @Test
    fun conferenceInitials_threeWordName_usesFirstAndLastWordOnly() {
        assertEquals("AM", conferenceInitials("Anna von Muster"))
    }

    @Test
    fun conferenceInitials_blankName_fallsBackToQuestionMark() {
        assertEquals("?", conferenceInitials("   "))
    }

    // ---------------------------------------------------------------------------------------
    // ConferenceChatMessage -- encode/decode round trip against the same codec
    // LiveKitRoomSession.sendChat / its DataReceived handler use on the wire (see that class's own
    // "Chat trust boundary" KDoc for why the RECEIVING side always overwrites senderMemberId/
    // senderDisplayName -- this test only proves the payload itself survives the wire round trip).
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceChatMessage_encodeDecode_roundTripsAllFields() {
        val original =
            ConferenceChatMessage(
                senderMemberId = "member-1",
                senderDisplayName = "Anna Muster",
                text = "Können wir gleich zu TOP 3?",
                sentAtEpochMs = 1_754_700_000_000L,
            )
        val json = Json.encodeToString(ConferenceChatMessage.serializer(), original)
        val decoded = Json.decodeFromString(ConferenceChatMessage.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun conferenceChatMessage_encodeDecode_survivesTextWithQuotesAndNewlines() {
        // A participant could type anything, including characters that would break naive string
        // concatenation -- this is exactly why the payload goes through kotlinx.serialization.json
        // rather than a hand-rolled format.
        val original =
            ConferenceChatMessage(
                senderMemberId = "member-2",
                senderDisplayName = "Test \"Quote\" Müller",
                text = "Zeile 1\nZeile 2 mit \"Anführungszeichen\" und Backslash \\",
                sentAtEpochMs = 0L,
            )
        val json = Json.encodeToString(ConferenceChatMessage.serializer(), original)
        val decoded = Json.decodeFromString(ConferenceChatMessage.serializer(), json)
        assertEquals(original, decoded)
    }

    // ---------------------------------------------------------------------------------------
    // Wave 2 "Aufzeichnung" -- recordingCanStart, status/access-level German labels, banner text,
    // duration/started-at/document-title formatting, FAILED-first sorting. Same DOM-free posture as
    // the rest of this file -- ConferenceRecordingsPanel.kt's own DOM rendering is out of scope here.
    // ---------------------------------------------------------------------------------------

    private fun sampleRecording(
        status: ConferenceRecordingStatus = ConferenceRecordingStatus.RECORDING,
        id: String = "rec-1",
    ) = ConferenceRecordingDto(
        id = id,
        roomId = "room-1",
        roomTitle = "Vorstandssitzung",
        status = status,
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

    @Test
    fun recordingCanStart_moderatorNoActiveRecordingAvailable_isAllowed() {
        assertTrue(recordingCanStart(canModerate = true, recordingAvailable = true, activeRecording = null))
    }

    @Test
    fun recordingCanStart_nonModerator_isNeverAllowed() {
        assertFalse(recordingCanStart(canModerate = false, recordingAvailable = true, activeRecording = null))
    }

    @Test
    fun recordingCanStart_recordingUnavailable_isNeverAllowed() {
        assertFalse(recordingCanStart(canModerate = true, recordingAvailable = false, activeRecording = null))
    }

    @Test
    fun recordingCanStart_alreadyActiveRecording_isRefused() {
        assertFalse(
            recordingCanStart(canModerate = true, recordingAvailable = true, activeRecording = sampleRecording()),
        )
    }

    @Test
    fun conferenceRecordingStatusLabel_mapsEveryStatusToPlainGermanCopy() {
        assertEquals("Wird aufgezeichnet", conferenceRecordingStatusLabel(ConferenceRecordingStatus.RECORDING))
        assertEquals("Wird beendet …", conferenceRecordingStatusLabel(ConferenceRecordingStatus.STOPPING))
        assertEquals("Wird zusammengeführt …", conferenceRecordingStatusLabel(ConferenceRecordingStatus.PROCESSING))
        assertEquals("Bereit", conferenceRecordingStatusLabel(ConferenceRecordingStatus.READY))
        assertEquals("Fehlgeschlagen", conferenceRecordingStatusLabel(ConferenceRecordingStatus.FAILED))
    }

    @Test
    fun conferenceRecordingBannerText_matchesTheLiteralCopyTheUiRenders() {
        assertEquals("Diese Besprechung wird ab jetzt aufgezeichnet.", CONFERENCE_RECORDING_BANNER_TEXT)
    }

    @Test
    fun conferenceRecordingAccessLevelLabel_mapsEveryLevelToItsGermanConsequenceName() {
        assertEquals("Mitglieder", conferenceRecordingAccessLevelLabel(DocumentAccessLevel.PUBLIC_MEMBERS))
        assertEquals("Vorstand", conferenceRecordingAccessLevelLabel(DocumentAccessLevel.BOARD_ONLY))
        assertEquals("Administration", conferenceRecordingAccessLevelLabel(DocumentAccessLevel.ADMIN_ONLY))
    }

    @Test
    fun conferenceRecordingStartedLabel_zeroPadsHourAndMinute() {
        assertEquals(
            "Aufzeichnung gestartet von Anna Muster um 09:05",
            conferenceRecordingStartedLabel("Anna Muster", LocalDateTime(2026, 8, 9, 9, 5)),
        )
    }

    @Test
    fun conferenceRecordingDurationLabel_nullRendersAsEmDash() {
        assertEquals("–", conferenceRecordingDurationLabel(null))
    }

    @Test
    fun conferenceRecordingDurationLabel_underAnHour_rendersMinutesSeconds() {
        assertEquals("0:00", conferenceRecordingDurationLabel(0))
        assertEquals("1:05", conferenceRecordingDurationLabel(65))
    }

    @Test
    fun conferenceRecordingDurationLabel_anHourOrMore_rendersHoursMinutesSeconds() {
        assertEquals("1:01:01", conferenceRecordingDurationLabel(3661))
    }

    @Test
    fun conferenceRecordingDocumentTitle_prefixesOnlyWhileRecording() {
        assertEquals("● Videokonferenz", conferenceRecordingDocumentTitle("Videokonferenz", isRecording = true))
        assertEquals("Videokonferenz", conferenceRecordingDocumentTitle("Videokonferenz", isRecording = false))
    }

    @Test
    fun conferenceRecordingListSorted_pinsFailedRecordingsToTheFront_stablyOtherwise() {
        val ready1 = sampleRecording(status = ConferenceRecordingStatus.READY, id = "ready-1")
        val failed1 = sampleRecording(status = ConferenceRecordingStatus.FAILED, id = "failed-1")
        val ready2 = sampleRecording(status = ConferenceRecordingStatus.READY, id = "ready-2")
        val failed2 = sampleRecording(status = ConferenceRecordingStatus.FAILED, id = "failed-2")
        val sorted = conferenceRecordingListSorted(listOf(ready1, failed1, ready2, failed2))
        assertEquals(listOf("failed-1", "failed-2", "ready-1", "ready-2"), sorted.map { it.id })
    }

    @Test
    fun conferenceRecordingListSorted_noFailedRecordings_leavesOrderUntouched() {
        val recordings =
            listOf(
                sampleRecording(status = ConferenceRecordingStatus.READY, id = "r1"),
                sampleRecording(status = ConferenceRecordingStatus.PROCESSING, id = "r2"),
            )
        assertEquals(recordings, conferenceRecordingListSorted(recordings))
    }

    @Test
    fun conferenceRecordingFileSizeLabel_roundsDownToWholeMegabytes() {
        assertEquals("≈ 0 MB", conferenceRecordingFileSizeLabel(0))
        assertEquals("≈ 1 MB", conferenceRecordingFileSizeLabel(500_000))
        assertEquals("≈ 42 MB", conferenceRecordingFileSizeLabel(42_000_000))
    }

    // ---------------------------------------------------------------------------------------
    // Review-round-1 fix (2026-08-09) -- conferenceRecordingNeedsPoll/conferenceFindRecordingById,
    // the two pure helpers backing enterCall's own pollInFlightRecordingStatus loop (see that
    // function's KDoc for the full story: onRecordingStatusChanged only fires refreshRecordingState()
    // ONCE per LiveKit isRecording transition, well before RecordingPoller's own, often much longer,
    // STOPPING -> PROCESSING -> READY/FAILED composition phase finishes -- without this loop the
    // in-call moderator button could freeze on a disabled "Aufzeichnung wird beendet ..." label past
    // the recording's actual terminal state).
    // ---------------------------------------------------------------------------------------

    @Test
    fun conferenceRecordingNeedsPoll_stoppingAndProcessing_needPolling() {
        assertTrue(conferenceRecordingNeedsPoll(ConferenceRecordingStatus.STOPPING))
        assertTrue(conferenceRecordingNeedsPoll(ConferenceRecordingStatus.PROCESSING))
    }

    @Test
    fun conferenceRecordingNeedsPoll_recordingReadyFailedAndNull_doNotNeedPolling() {
        // RECORDING: LiveKit's own push already keeps the control in sync -- no need to poll.
        assertFalse(conferenceRecordingNeedsPoll(ConferenceRecordingStatus.RECORDING))
        // READY/FAILED: terminal, nothing left to wait for.
        assertFalse(conferenceRecordingNeedsPoll(ConferenceRecordingStatus.READY))
        assertFalse(conferenceRecordingNeedsPoll(ConferenceRecordingStatus.FAILED))
        // null: no tracked recording at all.
        assertFalse(conferenceRecordingNeedsPoll(null))
    }

    @Test
    fun conferenceFindRecordingById_matchFound_returnsIt() {
        val stopping = sampleRecording(status = ConferenceRecordingStatus.STOPPING, id = "rec-1")
        val ready = sampleRecording(status = ConferenceRecordingStatus.READY, id = "rec-2")
        assertEquals(ready, conferenceFindRecordingById(listOf(stopping, ready), "rec-2"))
    }

    @Test
    fun conferenceFindRecordingById_noMatch_returnsNull() {
        val ready = sampleRecording(status = ConferenceRecordingStatus.READY, id = "rec-2")
        assertEquals(null, conferenceFindRecordingById(listOf(ready), "rec-does-not-exist"))
    }

    @Test
    fun conferenceFindRecordingById_emptyList_returnsNull() {
        assertEquals(null, conferenceFindRecordingById(emptyList(), "rec-1"))
    }
}
