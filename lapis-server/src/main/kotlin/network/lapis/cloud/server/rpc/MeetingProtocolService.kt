package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.governance.session.MeetingProtocolOperations
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingProtocolPreviewDto
import network.lapis.cloud.shared.domain.MeetingProtocolVersionsDto
import network.lapis.cloud.shared.domain.MyProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolApprovalDto
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolConfirmResultDto
import network.lapis.cloud.shared.domain.ProtocolFinaliseResultDto
import network.lapis.cloud.shared.rpc.IMeetingProtocolService
import java.io.File

/**
 * V1.9.240 "Protokoll fertigstellen" (D2) -- see [IMeetingProtocolService]. Constructed anew for every RPC call; it authenticates the caller and
 * delegates everything else to [MeetingProtocolOperations] (access, state machine, hash, audit all live there).
 */
internal class MeetingProtocolService(
    private val call: ApplicationCall,
    storageRoot: File?,
    private val operations: MeetingProtocolOperations = MeetingProtocolOperations(storageRoot = storageRoot),
) : IMeetingProtocolService {
    override suspend fun previewMeetingProtocol(meetingId: String): MeetingProtocolPreviewDto =
        operations.preview(current = resolveCurrentMember(call), meetingId = meetingId)

    override suspend fun getMeetingProtocolVersions(meetingId: String): MeetingProtocolVersionsDto =
        operations.versions(current = resolveCurrentMember(call), meetingId = meetingId)

    override suspend fun finaliseMeetingProtocol(
        meetingId: String,
        expectedContentSha256: String,
    ): ProtocolFinaliseResultDto =
        operations.finalise(current = resolveCurrentMember(call), meetingId = meetingId, expectedContentSha256 = expectedContentSha256)

    override suspend fun confirmMeetingProtocolVersion(
        meetingId: String,
        versionId: String,
    ): ProtocolConfirmResultDto = operations.confirm(current = resolveCurrentMember(call), meetingId = meetingId, versionId = versionId)

    override suspend fun startMeetingProtocolCorrection(
        meetingId: String,
        note: String,
    ): MeetingProtocolDraftDto = operations.startCorrection(current = resolveCurrentMember(call), meetingId = meetingId, note = note)

    override suspend fun recordProtocolApproval(
        agendaItemId: String,
        result: ProtocolApprovalResult,
    ): ProtocolApprovalDto = operations.recordApproval(current = resolveCurrentMember(call), agendaItemId = agendaItemId, result = result)

    override suspend fun getMyProtocolTasks(): List<MyProtocolTaskDto> = operations.myTasks(current = resolveCurrentMember(call))

    override suspend fun setMyProtocolTaskDone(
        taskKey: String,
        done: Boolean,
    ): MyProtocolTaskDto = operations.setTaskDone(current = resolveCurrentMember(call), taskKey = taskKey, done = done)
}
