package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- covers
 * [NoteBlockBroadcastDto.isStructurallyValid], the shared bounds-check function that gates BOTH the
 * server's `ConferenceNotesService.createBlock`/`commitBlockEdit` RPC paths (via the local
 * `validateBlockId`/`validateContent` helpers, same bounds) AND the client's `LiveKitRoomSession`
 * data-channel receive path for the `lapis-notes-commit` topic. Mirrors
 * [WhiteboardStrokeWireDtoValidationTest]'s own structure and DoS-payload-shape posture.
 */
class NoteBlockBroadcastDtoValidationTest {
    private fun validBroadcast(
        blockId: String = "member-1-1700000000000-abc",
        content: String = "Ein Notizblock mit etwas Inhalt.",
        position: Int = 1,
        version: Int = 1,
    ) = NoteBlockBroadcastDto(blockId = blockId, content = content, position = position, version = version)

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    fun wellFormedBlock_isValid() {
        assertTrue(validBroadcast().isStructurallyValid())
    }

    @Test
    fun contentAtLengthCap_isValid() {
        assertTrue(validBroadcast(content = "x".repeat(NOTES_MAX_CONTENT_LENGTH)).isStructurallyValid())
    }

    @Test
    fun blockIdAtLengthCap_isValid() {
        assertTrue(validBroadcast(blockId = "x".repeat(NOTES_MAX_BLOCK_ID_LENGTH)).isStructurallyValid())
    }

    @Test
    fun versionOne_isValid() {
        assertTrue(validBroadcast(version = 1).isStructurallyValid())
    }

    @Test
    fun anyPositionValue_isValid_positionIsNotBounded() {
        // See class KDoc / NoteBlockBroadcastDto.isStructurallyValid KDoc -- position is a cosmetic
        // sort key, deliberately not bounds-checked, unlike a whiteboard point's canvas coordinates.
        assertTrue(validBroadcast(position = -999_999).isStructurallyValid())
        assertTrue(validBroadcast(position = Int.MAX_VALUE).isStructurallyValid())
    }

    // ── tamper cases: the DoS payload shapes this bound exists to close ────

    @Test
    fun contentAboveCap_isRejected_theCoreDoSPayloadShape() {
        assertFalse(validBroadcast(content = "x".repeat(NOTES_MAX_CONTENT_LENGTH + 1)).isStructurallyValid())
    }

    @Test
    fun blankContent_isRejected() {
        assertFalse(validBroadcast(content = "").isStructurallyValid())
        assertFalse(validBroadcast(content = "   ").isStructurallyValid())
    }

    @Test
    fun blankOrOversizedBlockId_isRejected() {
        assertFalse(validBroadcast(blockId = "").isStructurallyValid())
        assertFalse(validBroadcast(blockId = "   ").isStructurallyValid())
        assertFalse(validBroadcast(blockId = "x".repeat(NOTES_MAX_BLOCK_ID_LENGTH + 1)).isStructurallyValid())
    }

    @Test
    fun versionBelowOne_isRejected() {
        assertFalse(validBroadcast(version = 0).isStructurallyValid())
        assertFalse(validBroadcast(version = -1).isStructurallyValid())
    }
}
