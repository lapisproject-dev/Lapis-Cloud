package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.PulpitRecordingDocumentDto
import network.lapis.cloud.shared.domain.PulpitRecordingStateDto
import network.lapis.cloud.shared.domain.PulpitRecordingTextsDto

/**
 * V1.9.143 -- recording of the PULPIT of an encounter room (see `docs/features/pulpit-recording.adoc`). Every method that touches a room
 * answers every refusal -- instance switch off, room option off, room closed, no session, caller not an office holder present in the room --
 * with the same bare [ForbiddenException], so nothing is learned from the type. **Registered second to last** in `Application.kt` (the member finder of V1.9.144 is last) (Kilua numbers routes
 * by declaration order; a service in the middle would shift every later route).
 */
@RpcService
interface IPulpitRecordingService {
    /** PULPIT/STEWARD present in the room. */
    suspend fun pulpitRecordingState(spaceId: String): PulpitRecordingStateDto

    /** PULPIT/STEWARD present in the room. The two texts (the pulpit's consent text and the notice to the room). */
    suspend fun pulpitRecordingTexts(spaceId: String): PulpitRecordingTextsDto

    /**
     * PULPIT/STEWARD present in the room. Starts the recording of the PULPIT person(s) present. If the pulpit has to confirm and nobody has,
     * the pulpit is asked and [PulpitRecordingAwaitingConfirmationException] is thrown.
     */
    suspend fun startPulpitRecording(spaceId: String): PulpitRecordingStateDto

    /** PULPIT/STEWARD present in the room. Stops every recording of the room. */
    suspend fun stopPulpitRecording(spaceId: String): PulpitRecordingStateDto

    /** PULPIT present in the room only. [versionLabel]/[sha256] are those of [PulpitRecordingTextsDto]; a stale pair throws [AgreementTextOutdatedException]. */
    suspend fun confirmPulpitRecording(
        spaceId: String,
        versionLabel: String,
        sha256: String,
    ): PulpitRecordingStateDto

    /** PULPIT present in the room only. Discards an open start request. */
    suspend fun declinePulpitRecording(spaceId: String)

    /** BOARD/ADMIN. The stored pulpit recordings, newest first; [spaceId] `null` = all rooms. */
    suspend fun listPulpitRecordings(spaceId: String?): List<PulpitRecordingDocumentDto>

    /** BOARD/ADMIN. Deletes a pulpit recording irrevocably (bytes and rows). */
    suspend fun deletePulpitRecording(documentId: String): Boolean
}
