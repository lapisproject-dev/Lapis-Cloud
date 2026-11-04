package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * V1.9.275 -- THE choke point of re-adding ONE target's output to a RUNNING egress: the only caller of [LiveKitEgressClient.updateStream] in
 * production code (`StreamTargetReconnectorTripwireTest`). It never starts an egress (so "one egress per stream" and the pause-feed rule "never two
 * senders" stay true), never touches the other targets and never logs a URL.
 *
 * [removeUrl] is the former plaintext URL (best effort: LiveKit may already have dropped a failed output, so a failure of the removal is ignored),
 * [addUrl] the new one. Both come from `targetRtmpUrl` only and live as local variables only. Returns `true` when LiveKit accepted the add,
 * `false` on a [LiveKitAdminException] (only the class name is logged).
 */
internal suspend fun reissueStreamTargetOutput(
    liveKitEgressClient: LiveKitEgressClient,
    roomName: String,
    egressId: String,
    removeUrl: String?,
    addUrl: String,
): Boolean {
    if (removeUrl != null) {
        try {
            liveKitEgressClient.updateStream(roomName = roomName, egressId = egressId, removeUrls = listOf(removeUrl))
        } catch (e: LiveKitAdminException) {
            logger.debug { "reissueStreamTargetOutput: removal of the old output failed (${e::class.simpleName}), continuing" }
        }
    }
    return try {
        liveKitEgressClient.updateStream(roomName = roomName, egressId = egressId, addUrls = listOf(addUrl))
        true
    } catch (e: LiveKitAdminException) {
        logger.warn { "reissueStreamTargetOutput: adding the output failed (${e::class.simpleName})" }
        false
    }
}
