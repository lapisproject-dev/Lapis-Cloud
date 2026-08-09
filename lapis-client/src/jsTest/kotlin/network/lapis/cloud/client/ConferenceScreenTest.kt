package network.lapis.cloud.client

import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ConferenceChatMessage
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
}
