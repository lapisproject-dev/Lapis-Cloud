package network.lapis.cloud.server.conference.roompoll

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.shared.domain.ROOM_POLL_TOPIC

// Privacy rule of this file: the log names nothing but the fact that the nudge failed -- no room id, no name, no text.
private val logger = KotlinLogging.logger {}

/**
 * The server's "a poll started or ended" packet. The payload is empty and clients never read it; they just refresh. Sent ONLY at the start
 * and at the end of a poll -- never after an answer (a packet per answer would be a timing side channel on who answered). Runs OUTSIDE any
 * `transaction {}` (Exposed repeats a block on a `SQLException`, which would repeat the packet).
 */
internal object RoomPollNudge {
    private val PAYLOAD = "{}".toByteArray()

    suspend fun send(
        liveKit: LiveKitAdminClient,
        livekitRoomName: String,
    ) {
        try {
            liveKit.sendData(room = livekitRoomName, topic = ROOM_POLL_TOPIC, payload = PAYLOAD)
        } catch (e: LiveKitAdminException) {
            logger.warn { "room poll: the nudge failed" }
        }
    }
}
