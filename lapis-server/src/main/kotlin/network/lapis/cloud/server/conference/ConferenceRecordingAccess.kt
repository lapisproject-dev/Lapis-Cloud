package network.lapis.cloud.server.conference

import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canAccessRecordingAtLevel
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.moreRestrictive
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
    /**
     * Welle V1.9.1, fix round (B1): [documentAccessLevel] is the access level of the `document` row
     * the composed recording was archived into, when there is one. It is a SECOND, independent tier
     * that must be honored here, because `conference_recording.access_level` and
     * `document.access_level` are different columns and only the latter takes part in the document/
     * folder access model: `DocumentService.setFolderAccessLevel`'s cascade and
     * `DocumentArchiving`'s clamp both write `document.access_level` and never touch the recording
     * row. Before this fix, tightening the "Aufzeichnungen" folder to `ADMIN_ONLY` closed the
     * download route, the RPC listings, the AI retriever and the MCP tool — and left the media route
     * below wide open, so any member who still had the `mediaUrl` kept streaming the recording.
     *
     * The effective level is the MORE RESTRICTIVE of the two, and the starter carve-out applies only
     * while the recording's own tier is the binding one. Once the DOCUMENT is stricter than the
     * recording — which can only happen because an admin tightened the containing folder — the
     * carve-out is off: that is a decision about the CONTAINER, which the recording's starter never
     * made and must not be able to override. In the ordinary case the archived document carries
     * exactly the recording's own level, so [documentAccessLevel] is not stricter and the behaviour
     * is unchanged from before this fix, carve-out included.
     *
     * [documentAccessLevel] is `null` when the recording has no archived document yet (still
     * `RECORDING`/`PROCESSING`, or a `FAILED` composition) — then there is nothing to clamp against
     * and only the recording's own tier applies.
     */
    fun mayAccess(
        current: CurrentMember,
        accessLevel: DocumentAccessLevel,
        startedByMemberId: Uuid,
        documentAccessLevel: DocumentAccessLevel? = null,
    ): Boolean {
        val effective = documentAccessLevel?.let { moreRestrictive(a = accessLevel, b = it) } ?: accessLevel
        if (current.canAccessRecordingAtLevel(effective)) return true
        return effective == accessLevel && current.memberId == startedByMemberId
    }
}
