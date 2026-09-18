package network.lapis.cloud.server.conference

import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canAccessRecordingAtLevel
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.uuid.Uuid

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the ONE access predicate for a
 * `conference_recording` row, used identically at THREE call sites (never re-derived
 * independently): [network.lapis.cloud.server.rpc.ConferenceRecordingService.listRecordings]'s
 * filter and [network.lapis.cloud.shared.domain.ConferenceRecordingDto.mediaUrl]'s computation
 * (same class), and [network.lapis.cloud.server.routes.registerConferenceRecordingRoutes]'s media
 * route. See [network.lapis.cloud.shared.rpc.IConferenceRecordingService] KDoc "Storage/access
 * decision" for the fachlich reasoning: [DocumentAccessLevel] (the room's «Sitzungsobjekt» role
 * tier the moderator chose at `startRecording` time) PLUS an explicit "the recording's own starter
 * can always see it" carve-out, so a non-BOARD moderator who recorded their own meeting into
 * `BOARD_ONLY` never loses access to their own recording.
 *
 * Deliberately [CurrentMember.canAccessRecordingAtLevel], NOT
 * [network.lapis.cloud.server.security.canAccessDocumentAtLevel] -- see that function's own KDoc.
 * Recordings are a distinct fachlich concept from documents and must not silently inherit whatever
 * role set a document-access wave happens to widen `BOARD_ONLY` to.
 */
object ConferenceRecordingAccess {
    fun mayAccess(
        current: CurrentMember,
        accessLevel: DocumentAccessLevel,
        startedByMemberId: Uuid,
    ): Boolean = current.canAccessRecordingAtLevel(accessLevel) || current.memberId == startedByMemberId
}
