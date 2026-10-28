package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.governance.session.MeetingProtocolDraftOperations
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.ProtocolBlockSaveResultDto
import network.lapis.cloud.shared.domain.ProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.rpc.IProtocolDraftService

/**
 * V1.9.239 "Protokollentwurf in der Sitzung" (D1) -- see [IProtocolDraftService]. Constructed anew for every RPC call; it authenticates the
 * caller and delegates everything else to [MeetingProtocolDraftOperations] (access, state machine, sanitising, audit all live there).
 */
internal class ProtocolDraftService(
    private val call: ApplicationCall,
    private val operations: MeetingProtocolDraftOperations = MeetingProtocolDraftOperations(),
) : IProtocolDraftService {
    override suspend fun getMeetingProtocolDraft(meetingId: String): MeetingProtocolDraftDto =
        operations.get(current = resolveCurrentMember(call), meetingId = meetingId)

    override suspend fun saveMeetingProtocolBlock(
        meetingId: String,
        agendaItemId: String,
        expectedVersion: Long,
        body: String,
    ): ProtocolBlockSaveResultDto =
        operations.saveBlock(
            current = resolveCurrentMember(call),
            meetingId = meetingId,
            agendaItemId = agendaItemId,
            expectedVersion = expectedVersion,
            body = body,
        )

    override suspend fun addMeetingProtocolTask(
        meetingId: String,
        input: ProtocolTaskInput,
    ): ProtocolTaskDto = operations.addTask(current = resolveCurrentMember(call), meetingId = meetingId, input = input)

    override suspend fun removeMeetingProtocolTask(
        meetingId: String,
        taskId: String,
    ) = operations.removeTask(current = resolveCurrentMember(call), meetingId = meetingId, taskId = taskId)
}
