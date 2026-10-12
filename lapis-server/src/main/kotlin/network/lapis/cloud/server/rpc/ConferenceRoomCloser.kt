package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.113 -- the ONE teardown of a conference room, extracted from [ConferenceService.endRoom] so that the explicit "end room"
 * action and the event-conference poller close a room through exactly the same steps (there is no second truth):
 * delete the LiveKit room, delete the open breakout LiveKit rooms (best effort), stamp `ended_at` and every open participation, close the
 * breakout bookkeeping, stop active recordings (must stay the LAST lock-taking statement of its transaction, see
 * [ConferenceRecordingCoordinator]), then drop the in-memory whiteboard / notes / released-microphone state.
 *
 * Every LiveKit call runs OUTSIDE any transaction. A failing MAIN `deleteRoom` is thrown to the caller (so the room stays open and the
 * action can be retried); a failing breakout deletion is only logged (an orphaned breakout room dies with LiveKit's own empty timeout).
 */
internal class ConferenceRoomCloser(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val whiteboardState: ConferenceWhiteboardState,
    private val notesState: ConferenceNotesState,
    private val audioGrantState: ConferenceAudioGrantState,
) {
    suspend fun close(
        roomId: Uuid,
        livekitRoomName: String,
        now: LocalDateTime,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ) {
        liveKitAdminClient.deleteRoom(livekitRoomName)
        val openBreakoutLiveKitNames = transaction { ConferenceBreakoutCoordinator.openLiveKitRoomNames(roomId) }
        openBreakoutLiveKitNames.forEach { name ->
            runCatching { liveKitAdminClient.deleteRoom(name) }
                .onFailure {
                    logger.warn {
                        "room closer: failed to delete a breakout LiveKit room of parent $roomId -- " +
                            "will self-clean via LiveKit's own empty_timeout"
                    }
                }
        }
        transaction {
            ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[endedAt] = now }
            ConferenceParticipationTable.update({
                (ConferenceParticipationTable.roomId eq roomId) and ConferenceParticipationTable.leftAt.isNull()
            }) {
                it[leftAt] = now
            }
            ConferenceBreakoutCoordinator.closeAllBreakoutRoomsForRoom(roomId = roomId, now = now)
            ConferenceRecordingCoordinator.stopActiveRecordingsForRoom(
                roomId = roomId,
                actorMemberId = actorMemberId,
                actorRole = actorRole,
            )
        }
        whiteboardState.clear(roomId)
        notesState.clear(roomId)
        audioGrantState.clear(roomId)
    }
}
