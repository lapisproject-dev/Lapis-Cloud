package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingProtocolPreviewDto
import network.lapis.cloud.shared.domain.MeetingProtocolVersionsDto
import network.lapis.cloud.shared.domain.MyProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolApprovalDto
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolConfirmResultDto
import network.lapis.cloud.shared.domain.ProtocolFinaliseResultDto

/**
 * V1.9.240 "Protokoll fertigstellen" (D2) -- finalising, confirming and correcting the Protokoll of a Sitzung, the approval item of the next
 * Sitzung and the tasks of the caller. THIS is the LAST `registerService` in `Application.kt` (after [IProtocolDraftService]; Kilua numbers
 * routes by declaration order). New methods go to the END, never in between.
 *
 * Access is through the Sitzung (like [IProtocolDraftService]); whatever the caller must not see is one and the same [NotFoundException].
 * Kilua RPC transmits only the exception TYPE: invalid input is a [BadRequestException], a state that forbids the call a [ConflictException].
 * Races on finalising/confirming are ordinary answers (the `outcome` fields), not exceptions.
 */
@RpcService
interface IMeetingProtocolService {
    /** Never writes. */
    suspend fun previewMeetingProtocol(meetingId: String): MeetingProtocolPreviewDto

    suspend fun getMeetingProtocolVersions(meetingId: String): MeetingProtocolVersionsDto

    /** Finalises the draft, but only if its content still has the hash the caller saw ([expectedContentSha256]). */
    suspend fun finaliseMeetingProtocol(
        meetingId: String,
        expectedContentSha256: String,
    ): ProtocolFinaliseResultDto

    suspend fun confirmMeetingProtocolVersion(
        meetingId: String,
        versionId: String,
    ): ProtocolConfirmResultDto

    /** Starts a new draft from the newest version; [note] (20..1000 characters) says what is corrected. */
    suspend fun startMeetingProtocolCorrection(
        meetingId: String,
        note: String,
    ): MeetingProtocolDraftDto

    suspend fun recordProtocolApproval(
        agendaItemId: String,
        result: ProtocolApprovalResult,
    ): ProtocolApprovalDto

    suspend fun getMyProtocolTasks(): List<MyProtocolTaskDto>

    suspend fun setMyProtocolTaskDone(
        taskKey: String,
        done: Boolean,
    ): MyProtocolTaskDto
}
