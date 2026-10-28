package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * V1.9.236 -- who is connected to a LiveKit room right now, as raw identities (the Sitzung panel maps them to roster entries). Never called
 * inside a database transaction (an external effect). A failure is an empty set, never an exception: the panel then simply shows nobody as
 * connected.
 */
interface ConferenceConnectedMembers {
    suspend fun connectedIdentities(livekitRoomName: String): Set<String>
}

/** Used where conferencing is switched off or in tests that do not care. */
object DisabledConferenceConnectedMembers : ConferenceConnectedMembers {
    override suspend fun connectedIdentities(livekitRoomName: String): Set<String> = emptySet()
}

/**
 * Ten seconds of cache per room, so that a lead polling every 5 s and a minute taker polling every 5 s cost one LiveKit call, not two. At most
 * [MAX_ENTRIES] rooms are remembered (the oldest entry goes first). A failed lookup is logged WITHOUT any identity and is not cached.
 */
class CachedConferenceConnectedMembers(
    private val liveKit: LiveKitAdminClient,
    private val ttl: Duration = 10.seconds,
    private val nanoClock: () -> Long = System::nanoTime,
) : ConferenceConnectedMembers {
    private class Entry(
        val fetchedAtNanos: Long,
        val identities: Set<String>,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override suspend fun connectedIdentities(livekitRoomName: String): Set<String> {
        val now = nanoClock()
        cache[livekitRoomName]?.let { if (now - it.fetchedAtNanos < ttl.inWholeNanoseconds) return it.identities }
        val fetched =
            try {
                liveKit.listParticipants(livekitRoomName).map { it.identity }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "Connected-members lookup failed (${e::class.simpleName})" }
                return emptySet()
            }
        cache[livekitRoomName] = Entry(fetchedAtNanos = now, identities = fetched)
        evictIfOverCapacity()
        return fetched
    }

    private fun evictIfOverCapacity() {
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.entries.minByOrNull { it.value.fetchedAtNanos } ?: return
            cache.remove(oldest.key, oldest.value)
        }
    }

    internal fun sizeForTest(): Int = cache.size

    companion object {
        const val MAX_ENTRIES = 256
    }
}
