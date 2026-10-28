package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService

/**
 * V1.9.236 -- the RPCs of the Sitzung panel behind one thin seam, so a test can replace them (same shape as [OperatorRpc]).
 * Every mutation answers with the fresh state; the panel renders it directly.
 */
internal class ConferenceMeetingRpc(
    val getState: suspend (
        roomId: String,
    ) -> ConferenceMeetingStateDto? = { rpcService<IGovernanceService>().getConferenceMeetingState(it).state },
    val open: suspend (roomId: String, meetingId: String) -> ConferenceMeetingStateDto = { room, meeting ->
        rpcService<IGovernanceService>().openConferenceMeeting(room, meeting)
    },
    val setAttendance: suspend (
        roomId: String,
        meetingId: String,
        input: SessionAttendanceInput,
    ) -> ConferenceMeetingStateDto = { room, meeting, input ->
        rpcService<IGovernanceService>().setConferenceAttendance(room, meeting, input)
    },
    val adopt: suspend (roomId: String, meetingId: String) -> Int = { room, meeting ->
        rpcService<IGovernanceService>().adoptConnectedAttendance(room, meeting)
    },
    val establish: suspend (
        roomId: String,
        meetingId: String,
        acknowledgeNotMet: Boolean,
    ) -> ConferenceMeetingStateDto = { room, meeting, ack ->
        rpcService<IGovernanceService>().establishConferenceQuorum(room, meeting, ack)
    },
    val move: suspend (
        roomId: String,
        meetingId: String,
        from: String?,
        to: String,
    ) -> ConferenceMeetingStateDto = { room, meeting, from, to ->
        rpcService<IGovernanceService>().moveConferenceAgenda(room, meeting, from, to)
    },
    // V1.9.238 -- attach a motion to the current agenda item, open a vote on it (election + board + opening in one call), close that vote.
    val attach: suspend (
        roomId: String,
        meetingId: String,
        motionId: String,
        expectedAgendaItemId: String,
    ) -> ConferenceMeetingStateDto = { room, meeting, motion, item ->
        rpcService<IGovernanceService>().attachConferenceMotion(room, meeting, motion, item)
    },
    val openVote: suspend (
        roomId: String,
        meetingId: String,
        motionId: String,
        expectedAgendaItemId: String,
        secret: Boolean,
        boardMemberIds: List<String>,
    ) -> ConferenceMeetingStateDto = { room, meeting, motion, item, secret, board ->
        rpcService<IGovernanceService>().openConferenceMotionVote(room, meeting, motion, item, secret, board)
    },
    val closeVoting: suspend (electionId: String) -> Unit = { rpcService<IElectionService>().closeVoting(it) },
)
