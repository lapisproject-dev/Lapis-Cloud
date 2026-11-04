package network.lapis.cloud.server.conference

import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.rpc.ConferenceStreamBroadcastKeyRejectedException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.274 -- the stream key of ONE broadcast (a [ConferenceStreamKeyKind.PER_BROADCAST] destination). The key is typed by the moderator at the
 * start, sealed with [SecretBox] on the `conference_stream_target` row of that broadcast (AAD [aad], which differs from the AAD of a destination
 * key so no ciphertext can be moved between the two tables) and set to NULL as soon as the stream reaches `ENDED` or `FAILED`.
 *
 * Rules (the tripwire `BroadcastKeyLifetimeTripwireTest` pins the second):
 *  * Only [targetRtmpUrl] builds a plaintext RTMP URL.
 *  * Every place that writes `ENDED` or `FAILED` to `conference_stream` calls [clearForStream] in the SAME transaction; [clearForTerminalStreams]
 *    (first step of every `StreamPoller` tick, so also after a server restart) is the safety net for any path that was missed.
 *  * A single FAILED target of a running stream keeps its key: Resume resets every target and needs it.
 *  * Nothing here logs; the key is in no message, no DTO, no audit entry and not in a backup (`OrganizationSchemaCatalog.REDACTED_COLUMNS`).
 */
internal object BroadcastKeys {
    /** Longest accepted key; the sealed form (about 4/3 of it plus the wire prefix) fits the 1024-character column. */
    const val MAX_LENGTH = 512

    /** AAD of a broadcast key: the prefix keeps it apart from the destination key (AAD = destination id). */
    fun aad(targetId: Uuid): String = "conference_stream_target:$targetId"

    /** 1..512 printable ASCII characters without space (0x21..0x7E). Never echoes the input. */
    fun validate(key: String) {
        if (key.isEmpty() || key.length > MAX_LENGTH || key.any { it.code !in 0x21..0x7E }) {
            throw ConferenceStreamBroadcastKeyRejectedException()
        }
    }

    fun seal(
        targetId: Uuid,
        key: String,
        box: SecretBox,
    ): String {
        validate(key)
        return box.seal(plaintext = key, aad = aad(targetId))
    }

    /** NULLs the broadcast key of every target of [streamId]. Must run in the caller's transaction. Returns the number of rows cleared. */
    fun clearForStream(streamId: Uuid): Int =
        ConferenceStreamTargetTable.update({
            (ConferenceStreamTargetTable.streamId eq streamId) and ConferenceStreamTargetTable.broadcastKeyCiphertext.isNotNull()
        }) {
            it[broadcastKeyCiphertext] = null
        }

    /** Safety net: NULLs the broadcast key of every target whose stream is already `ENDED`/`FAILED`. Must run in a transaction. */
    fun clearForTerminalStreams(): Int {
        val streamIds =
            (ConferenceStreamTargetTable innerJoin ConferenceStreamTable)
                .select(ConferenceStreamTable.id)
                .where {
                    ConferenceStreamTargetTable.broadcastKeyCiphertext.isNotNull() and
                        (ConferenceStreamTable.status inList listOf(ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED))
                }.map { it[ConferenceStreamTable.id] }
                .distinct()
        if (streamIds.isEmpty()) return 0
        return ConferenceStreamTargetTable.update({
            (ConferenceStreamTargetTable.streamId inList streamIds) and ConferenceStreamTargetTable.broadcastKeyCiphertext.isNotNull()
        }) {
            it[broadcastKeyCiphertext] = null
        }
    }
}

/** The key of a target is not available (constant message, no payload, no stack of interest). */
internal class StreamKeyMissing : RuntimeException("stream key missing") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * THE one place a plaintext RTMP URL (`<base>/<key>`) is built, for the start, every restart (resume, poller, secret ballot) and the pause feed.
 * [destinationRow] must contain the [ConferenceStreamDestinationTable] columns. Throws [StreamKeyMissing] (never a message with content) when the
 * needed key is not there; a [SecretBox] failure surfaces as `SecretBoxException` with its own fixed text.
 */
internal fun targetRtmpUrl(
    destinationRow: ResultRow,
    targetId: Uuid,
    broadcastKeyCiphertext: String?,
    secretBox: SecretBox,
): String {
    val base = destinationRow[ConferenceStreamDestinationTable.rtmpUrl].trimEnd('/')
    val key =
        when (destinationRow[ConferenceStreamDestinationTable.keyKind]) {
            ConferenceStreamKeyKind.PER_BROADCAST ->
                secretBox.open(sealed = broadcastKeyCiphertext ?: throw StreamKeyMissing(), aad = BroadcastKeys.aad(targetId))
            ConferenceStreamKeyKind.PERSISTENT ->
                secretBox.open(
                    sealed = destinationRow[ConferenceStreamDestinationTable.streamKeyCiphertext] ?: throw StreamKeyMissing(),
                    aad = destinationRow[ConferenceStreamDestinationTable.id].toString(),
                )
        }
    return "$base/$key"
}
