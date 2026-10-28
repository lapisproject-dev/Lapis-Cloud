package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.ProtocolBlockSaveResultDto
import network.lapis.cloud.shared.domain.ProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolTaskInput

/**
 * V1.9.239 "Protokollentwurf in der Sitzung" (D1) -- the draft of the Protokoll of a Sitzung. This is the SECOND TO LAST `registerService` in
 * `Application.kt` (after `ILupaBbSyncService`; Kilua numbers routes by declaration order). New methods go to the END, never in between.
 *
 * **Access** is through the Sitzung itself (not the conference room, so the minute taker can finish after the room ended). Whatever the
 * caller must not see -- an unknown or malformed id, a Sitzung without a seat for the caller, a guest -- is one and the same
 * [NotFoundException]. Reading is open to every role of the Sitzung; writing needs LEAD or MINUTES ([ForbiddenException] otherwise).
 *
 * Kilua RPC transmits only the exception TYPE: invalid input is a [BadRequestException], "not writable right now" and "too many tasks" are a
 * [ConflictException]. A lost race on a text is NOT an exception, see [ProtocolBlockSaveResultDto].
 */
@RpcService
interface IProtocolDraftService {
    /** Never writes. Derives header, attendance, quorum and resolutions from the stored rows of the Sitzung at the moment of the call. */
    suspend fun getMeetingProtocolDraft(meetingId: String): MeetingProtocolDraftDto

    /** Saves the text of one agenda item; [expectedVersion] 0 = "I saw no text yet". Plain text only. */
    suspend fun saveMeetingProtocolBlock(
        meetingId: String,
        agendaItemId: String,
        expectedVersion: Long,
        body: String,
    ): ProtocolBlockSaveResultDto

    suspend fun addMeetingProtocolTask(
        meetingId: String,
        input: ProtocolTaskInput,
    ): ProtocolTaskDto

    suspend fun removeMeetingProtocolTask(
        meetingId: String,
        taskId: String,
    )
}
