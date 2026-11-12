package network.lapis.cloud.server.conference.roompoll

import network.lapis.cloud.shared.domain.RoomPollStatus
import kotlin.uuid.Uuid

/**
 * The cheap change token of [network.lapis.cloud.server.rpc.ConferenceRoomPollService.getRoomPollVersion]. There is no interim result
 * (answers never bump the state version), so the token of a non-leadership caller is `<poll id or none>.<O|C>` and the leadership token adds
 * the state version (`<poll id>.<O|C>.<version>`).
 */
internal object RoomPollVersionToken {
    fun token(
        pollId: Uuid?,
        status: RoomPollStatus?,
        stateVersion: Long,
        leadership: Boolean,
    ): String {
        if (pollId == null || status == null) return "none"
        val letter = if (status == RoomPollStatus.OPEN) "O" else "C"
        return if (leadership) "$pollId.$letter.$stateVersion" else "$pollId.$letter"
    }
}
