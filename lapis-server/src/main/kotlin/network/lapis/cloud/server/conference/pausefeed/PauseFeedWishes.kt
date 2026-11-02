package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.RoomPauseDb
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.rpc.restartRtmpUrl
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** What a pause feed should send to: the room of the stream and the plaintext target URLs (never logged, never persisted). */
internal class PauseFeedWish(
    val roomId: Uuid,
    val targetUrls: List<String>,
) {
    /**
     * A digest of the (sorted) target URLs: changes whenever a destination is disabled, deleted, or gets another URL/key. The supervisor
     * compares it every tick and restarts a feed whose target set changed. Never reversible to the URLs.
     */
    val fingerprint: String by lazy {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        targetUrls.sorted().forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String = "PauseFeedWish(targets=${targetUrls.size})"
}

/** The seam to the database: is a feed wanted for a stream right now? Read FRESH before every start. */
internal fun interface PauseFeedDataSource {
    /** `null` = not wanted (or nothing to send to). */
    fun wishFor(streamId: Uuid): PauseFeedWish?
}

/**
 * V1.9.255 -- the single definition of "a pause feed is wanted for this stream":
 *
 *  * the stream is `PAUSED` (never `PAUSING`: a still running egress would compete with the feed on the same key),
 *  * with the reason `ROOM_PAUSE` (a pause for a secret ballot or by hand never gets a feed, Z6) and the room is still paused,
 *  * the room has not ended.
 *
 * A secret ballot that begins WHILE the room is paused leaves the reason `ROOM_PAUSE`, so the feed keeps running: it shows a still picture from
 * a file and nothing from the room, so no ballot content can become visible. Destinations an operator disabled are skipped (the point of
 * `enabled = false` is "stop sending here").
 *
 * Every function must run inside a `transaction {}` except [DbPauseFeedDataSource.wishFor], which opens its own.
 */
internal object PauseFeedWishes {
    /** The ids of all streams a feed is wanted for. */
    fun wantedStreamIds(): Set<Uuid> =
        ConferenceStreamTable
            .selectAll()
            .where {
                (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSED) and
                    (ConferenceStreamTable.pauseReason eq ConferenceStreamPauseReason.ROOM_PAUSE)
            }.map { it[ConferenceStreamTable.id] to it[ConferenceStreamTable.roomId] }
            .filter { (_, roomId) -> isWanted(roomId) }
            .map { it.first }
            .toSet()

    private fun isWanted(roomId: Uuid): Boolean {
        val room = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull() ?: return false
        return room[ConferenceRoomTable.endedAt] == null && RoomPauseDb.isRoomPaused(roomId)
    }

    fun wish(
        streamId: Uuid,
        secretBox: SecretBox,
    ): PauseFeedWish? {
        val stream = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull() ?: return null
        if (stream[ConferenceStreamTable.status] != ConferenceStreamStatus.PAUSED) return null
        if (stream[ConferenceStreamTable.pauseReason] != ConferenceStreamPauseReason.ROOM_PAUSE) return null
        val roomId = stream[ConferenceStreamTable.roomId]
        if (!isWanted(roomId)) return null
        val rows =
            (ConferenceStreamTargetTable innerJoin ConferenceStreamDestinationTable)
                .selectAll()
                .where { ConferenceStreamTargetTable.streamId eq streamId }
                .toList()
        val urls =
            rows
                .filter { it[ConferenceStreamDestinationTable.enabled] }
                .mapNotNull { row ->
                    try {
                        restartRtmpUrl(row = row, secretBox = secretBox)
                    } catch (e: Exception) {
                        // Never the message (a sealed box error must not leak anything).
                        logger.warn { "pause feed: a target URL could not be opened (${e::class.simpleName})" }
                        null
                    }
                }
        if (urls.isEmpty()) return null
        return PauseFeedWish(roomId = roomId, targetUrls = urls)
    }
}

internal class DbPauseFeedDataSource(
    streamingConfig: ConferenceStreamingConfig,
) : PauseFeedDataSource {
    private val secretBox: SecretBox? = streamingConfig.secretEncryptionKey?.let { SecretBox(it) }

    override fun wishFor(streamId: Uuid): PauseFeedWish? {
        val box = secretBox ?: return null
        return transaction { PauseFeedWishes.wish(streamId = streamId, secretBox = box) }
    }
}
