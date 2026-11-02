package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.255 -- the handle the rest of the server has on the pause feed. The state lives in memory only; the database decides whether a feed is
 * WANTED ([PauseFeedWishes]), this control only starts, watches and stops processes accordingly.
 */
interface PauseFeedControl {
    fun stateOf(streamId: Uuid): ConferenceStreamPauseFeedState

    /** `true` while any feed process exists (the media files must not be deleted under it). */
    fun anyRunning(): Boolean

    /** Idempotent. Re-reads the wanted state from the database right before the start. */
    suspend fun ensureRunning(streamId: Uuid)

    /**
     * Stops the feed of [streamId] and waits until it is gone, then runs [block] STILL holding the stream's lock -- so no new feed can start
     * between "feed gone" and the commit of [block] (the "never two senders on one key" rule). [block] must not call back into this control.
     */
    suspend fun <T> stopThen(
        streamId: Uuid,
        block: suspend () -> T,
    ): T

    suspend fun stop(streamId: Uuid)

    /** Stops every feed whose stream is not in [wantedStreamIds]. */
    suspend fun reconcile(wantedStreamIds: Set<Uuid>)

    /** Blocking: SIGTERM for all feeds, a short wait, then SIGKILL. Safe to call more than once. */
    fun shutdown()

    /** The control of a process without the feature (tests, feature off). */
    object NoOp : PauseFeedControl {
        override fun stateOf(streamId: Uuid) = ConferenceStreamPauseFeedState.OFF

        override fun anyRunning() = false

        override suspend fun ensureRunning(streamId: Uuid) = Unit

        override suspend fun <T> stopThen(
            streamId: Uuid,
            block: suspend () -> T,
        ): T = block()

        override suspend fun stop(streamId: Uuid) = Unit

        override suspend fun reconcile(wantedStreamIds: Set<Uuid>) = Unit

        override fun shutdown() = Unit
    }
}

/** The process-wide control (wired in `Application.kt`). Production code reads it at call time; tests pass their own instance explicitly. */
internal object PauseFeeds {
    @Volatile
    var current: PauseFeedControl = PauseFeedControl.NoOp
}

/** The time constants of the supervisor; a parameter so tests can run in milliseconds. */
internal data class PauseFeedTimings(
    val watchdogInterval: Duration = 5.seconds,
    val livenessTimeout: Duration = 15.seconds,
    val stopGrace: Duration = 5.seconds,
    val killGrace: Duration = 2.seconds,
    val restartBackoff: List<Duration> = listOf(5.seconds, 15.seconds),
)

/**
 * V1.9.255 -- owns every pause-feed ffmpeg process of this server. It starts a feed for a room-paused, confirmed-`PAUSED` stream, watches it,
 * and stops it before the stream's egress is started again. **It never calls LiveKit and never joins a room**: it has no LiveKit client, so
 * nothing from the room can reach the feed and a failure of the feed can never send the room's audio anywhere. (Pinned by a test.)
 *
 * ## Lifecycle rules
 *
 *  * Key: the stream id (a room has at most one non-terminal stream). One [Mutex] per stream serialises start, stop, watchdog and the
 *    claim of the egress restart ([stopThen]).
 *  * Start ([ensureRunning]) only when the database says so, read inside the lock right before the spawn ([PauseFeedDataSource]).
 *  * At most [MAX_FEEDS_PER_SERVER] feeds run at once; the next one becomes `FAILED` without a process (a CPU bound, not a feature).
 *  * Watchdog every [PauseFeedTimings.watchdogInterval]: a dead process, or no progress line for [PauseFeedTimings.livenessTimeout], is a failure; restarts after 5 s and 15 s,
 *    then `FAILED` for the rest of the pause. Every restart re-reads the wanted state.
 *  * Orphans: `-progress` is NOT an orphan guard (ffmpeg ignores SIGPIPE). What ends a leaked process is (1) the container: the JVM is PID 1,
 *    when it dies the kernel ends every process of the PID namespace, (2) `-t` (4 hours), (3) the shutdown hook and `ApplicationStopping`.
 *  * Single-process assumption (like the pollers): the locks only work inside one JVM.
 */
class PauseFeedSupervisor internal constructor(
    private val launcher: PauseFeedLauncher,
    private val media: PauseMediaSource,
    private val targetPolicy: PauseFeedTargetPolicy,
    private val dataSource: PauseFeedDataSource,
    private val enabled: Boolean,
    private val tlsVerifyArgs: List<String>,
    private val onStateChanged: suspend (roomId: Uuid) -> Unit,
    private val onFeedEnded: () -> Unit,
    private val scope: CoroutineScope,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timings: PauseFeedTimings = PauseFeedTimings(),
) : PauseFeedControl {
    private class Entry(
        val roomId: Uuid,
        state: ConferenceStreamPauseFeedState,
        handle: PauseFeedProcessHandle?,
        var attempts: Int,
        var job: Job?,
        var fingerprint: String? = null,
    ) {
        // read without the stream's lock by stateOf / anyRunning, written only under it
        @Volatile var state: ConferenceStreamPauseFeedState = state

        @Volatile var handle: PauseFeedProcessHandle? = handle
    }

    private val entries = ConcurrentHashMap<Uuid, Entry>()

    private class LockRef {
        val mutex = Mutex()
        var users = 0 // only touched inside ConcurrentHashMap.compute (atomic per key)
    }

    // Reference-counted so the map does not grow with every stream id that was ever seen.
    private val locks = ConcurrentHashMap<Uuid, LockRef>()
    private val limitLock = Any()

    private suspend fun <T> withStreamLock(
        streamId: Uuid,
        block: suspend () -> T,
    ): T {
        val ref = locks.compute(streamId) { _, v -> (v ?: LockRef()).also { it.users++ } }!!
        try {
            return ref.mutex.withLock { block() }
        } finally {
            locks.compute(streamId) { _, v ->
                v!!.users--
                if (v.users == 0) null else v
            }
        }
    }

    override fun stateOf(streamId: Uuid): ConferenceStreamPauseFeedState = entries[streamId]?.state ?: ConferenceStreamPauseFeedState.OFF

    // A STARTING entry without a handle (being launched, or waiting for a restart) counts: its media files must not be deleted under it.
    override fun anyRunning(): Boolean = entries.values.any { it.handle != null || it.state == ConferenceStreamPauseFeedState.STARTING }

    override suspend fun ensureRunning(streamId: Uuid) {
        if (!enabled) return
        var changedRoom: Uuid? = null
        withStreamLock(streamId = streamId) { changedRoom = ensureLocked(streamId) }
        changedRoom?.let { notifySafely(it) }
    }

    override suspend fun <T> stopThen(
        streamId: Uuid,
        block: suspend () -> T,
    ): T =
        withStreamLock(streamId = streamId) {
            stopLocked(streamId)
            block()
        }

    override suspend fun stop(streamId: Uuid) {
        withStreamLock(streamId = streamId) { stopLocked(streamId) }
    }

    override suspend fun reconcile(wantedStreamIds: Set<Uuid>) {
        for (id in entries.keys.toList()) {
            if (id !in wantedStreamIds) stop(id)
        }
    }

    override fun shutdown() {
        val all = entries.values.toList()
        entries.clear()
        all.forEach { it.job?.cancel() }
        val handles = all.mapNotNull { it.handle }
        handles.forEach { runCatching { it.terminate() } }
        val deadline = System.nanoTime() + SHUTDOWN_WAIT.inWholeNanoseconds
        while (handles.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(SHUTDOWN_POLL_MILLIS)
        handles.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
        if (handles.isNotEmpty()) onFeedEnded()
    }

    // ── under the stream's lock ─────────────────────────────────────────────────────────────────────

    /**
     * Starts a feed, or -- when one exists -- re-reads the wish and compares its target fingerprint: a destination that was disabled, deleted
     * or re-pointed since the start ends the old feed (no more picture/music to the old URL/key) and starts one for the new target set.
     * Returns the room id when the visible state changed.
     */
    private suspend fun ensureLocked(streamId: Uuid): Uuid? {
        val existing = entries[streamId] ?: return startLocked(streamId)
        val wish = dataSource.wishFor(streamId)
        if (wish != null && wish.fingerprint == existing.fingerprint) return null
        logger.info { "pause feed: the target set changed -- restarting the feed" }
        stopLocked(streamId)
        return startLocked(streamId) ?: existing.roomId
    }

    /** Returns the room id when the visible state changed. */
    private fun startLocked(streamId: Uuid): Uuid? {
        if (entries.containsKey(streamId)) return null // STARTING, ACTIVE and FAILED all stay as they are for this pause
        val wish = dataSource.wishFor(streamId) ?: return null
        val entry =
            Entry(
                roomId = wish.roomId,
                state = ConferenceStreamPauseFeedState.STARTING,
                handle = null,
                attempts = 0,
                job = null,
                fingerprint = wish.fingerprint,
            )
        // The limit is checked and the slot reserved atomically across streams (each stream has its own lock, this one is global and short).
        val admitted =
            synchronized(limitLock) {
                if (entries.values.count { it.state != ConferenceStreamPauseFeedState.FAILED } >= MAX_FEEDS_PER_SERVER) {
                    false
                } else {
                    entries[streamId] = entry
                    true
                }
            }
        if (!admitted) {
            logger.warn { "pause feed: the limit of $MAX_FEEDS_PER_SERVER simultaneous feeds is reached -- no feed for this pause" }
            entries[streamId] =
                Entry(
                    roomId = wish.roomId,
                    state = ConferenceStreamPauseFeedState.FAILED,
                    handle = null,
                    attempts = 0,
                    job = null,
                    fingerprint = wish.fingerprint,
                )
            return wish.roomId
        }
        if (!launchInto(entry = entry, wish = wish)) {
            entry.state = ConferenceStreamPauseFeedState.FAILED
            return wish.roomId
        }
        entry.job = scope.launch { watchdog(streamId) }
        return wish.roomId
    }

    /** Applies the policy, builds the command and launches. `false` = nothing could be started. */
    private fun launchInto(
        entry: Entry,
        wish: PauseFeedWish,
    ): Boolean {
        val accepted = ArrayList<String>()
        val rejected = HashMap<RejectReason, Int>()
        for (url in wish.targetUrls) {
            when (val check = targetPolicy.check(url)) {
                is PauseFeedTargetCheck.Ok -> accepted += check.url
                is PauseFeedTargetCheck.Rejected -> rejected.merge(check.reason, 1, Int::plus)
            }
        }
        if (rejected.isNotEmpty()) logger.warn { "pause feed: ${rejected.values.sum()} target(s) skipped by policy: $rejected" }
        if (accepted.isEmpty()) return false
        return try {
            val command =
                PauseFeedArgumentBuilder.build(
                    imagePng = media.imageFile(),
                    musicM4a = media.musicFile(),
                    targetUrls = accepted,
                    tlsVerifyArgs = tlsVerifyArgs,
                )
            entry.handle = launcher.launch(command)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "pause feed: could not be started (${e::class.simpleName})" }
            false
        }
    }

    private suspend fun stopLocked(streamId: Uuid) {
        val entry = entries.remove(streamId) ?: return
        entry.job?.cancel()
        val handle = entry.handle ?: return
        stopHandle(handle)
        onFeedEnded()
    }

    /** SIGTERM, then [PauseFeedTimings.stopGrace] to leave; otherwise SIGKILL and [PauseFeedTimings.killGrace]. */
    private suspend fun stopHandle(handle: PauseFeedProcessHandle) {
        runCatching { handle.terminate() }
        if (!handle.awaitExit(timings.stopGrace)) {
            logger.warn { "pause feed: the process ignored SIGTERM -- killing it" }
            runCatching { handle.destroyForcibly() }
            handle.awaitExit(timings.killGrace)
        }
    }

    // ── watchdog ─────────────────────────────────────────────────────────────────────────────────────

    private sealed interface Check {
        data object Idle : Check

        data object Done : Check

        class Changed(
            val roomId: Uuid,
            val backoff: Duration?,
            val done: Boolean,
        ) : Check
    }

    private suspend fun watchdog(streamId: Uuid) {
        while (scope.isActive) {
            delay(timings.watchdogInterval)
            val result = withStreamLock(streamId = streamId) { checkLocked(streamId) }
            when (result) {
                Check.Idle -> Unit
                Check.Done -> return
                is Check.Changed -> {
                    notifySafely(result.roomId)
                    if (result.done) return
                    val backoff = result.backoff
                    if (backoff != null) {
                        delay(backoff)
                        val restarted = withStreamLock(streamId = streamId) { restartLocked(streamId) }
                        if (restarted != null) notifySafely(restarted)
                        if (entries[streamId] == null || entries[streamId]?.state == ConferenceStreamPauseFeedState.FAILED) return
                    }
                }
            }
        }
    }

    private suspend fun checkLocked(streamId: Uuid): Check {
        val entry = entries[streamId] ?: return Check.Done
        if (entry.state == ConferenceStreamPauseFeedState.FAILED) return Check.Done
        val handle = entry.handle ?: return Check.Idle
        val stalled = nanoTime() - handle.lastProgressAtNanos > timings.livenessTimeout.inWholeNanoseconds
        if (handle.isAlive && !stalled) {
            if (entry.state != ConferenceStreamPauseFeedState.ACTIVE && handle.hasProgressed) {
                entry.state = ConferenceStreamPauseFeedState.ACTIVE
                return Check.Changed(roomId = entry.roomId, backoff = null, done = false)
            }
            return Check.Idle
        }
        logger.warn { "pause feed: the process ${if (handle.isAlive) "stalled" else "ended"} (attempt ${entry.attempts + 1})" }
        stopHandle(handle)
        entry.handle = null
        onFeedEnded()
        entry.attempts++
        if (entry.attempts > timings.restartBackoff.size) {
            entry.state = ConferenceStreamPauseFeedState.FAILED
            return Check.Changed(roomId = entry.roomId, backoff = null, done = true)
        }
        entry.state = ConferenceStreamPauseFeedState.STARTING
        return Check.Changed(roomId = entry.roomId, backoff = timings.restartBackoff[entry.attempts - 1], done = false)
    }

    /** A restart after the back-off: the wanted state is read again; not wanted any more = the entry disappears. */
    private fun restartLocked(streamId: Uuid): Uuid? {
        val entry = entries[streamId] ?: return null
        if (entry.handle != null) return null
        val wish = dataSource.wishFor(streamId)
        if (wish == null) {
            entries.remove(streamId)
            return entry.roomId
        }
        entry.fingerprint = wish.fingerprint
        if (!launchInto(entry = entry, wish = wish)) {
            entry.attempts++
            entry.state = ConferenceStreamPauseFeedState.FAILED
            return entry.roomId
        }
        return null
    }

    private suspend fun notifySafely(roomId: Uuid) {
        try {
            onStateChanged(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "pause feed: the state notice failed (${e::class.simpleName})" }
        }
    }

    companion object {
        const val MAX_FEEDS_PER_SERVER = 4
        private val SHUTDOWN_WAIT: Duration = 5.seconds
        private const val SHUTDOWN_POLL_MILLIS = 50L

        /** The CA bundle used to verify rtmps targets. */
        const val CA_BUNDLE_PATH = "/etc/ssl/certs/ca-certificates.crt"

        /** `-tls_verify 1 -ca_file ...` when the CA bundle exists; otherwise empty and rtmps targets get no feed. */
        fun tlsVerifyArgsIfAvailable(caBundle: File = File(CA_BUNDLE_PATH)): List<String> =
            if (caBundle.isFile) listOf("-tls_verify", "1", "-ca_file", caBundle.path) else emptyList()
    }
}
