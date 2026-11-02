package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

internal class FakeHandle(
    private val log: MutableList<String>,
    val ignoresTerm: Boolean = false,
    private val onDeath: () -> Unit,
) : PauseFeedProcessHandle {
    @Volatile var alive = true

    @Volatile var progressAt = System.nanoTime()

    @Volatile var progressed = false

    override val isAlive get() = alive
    override val lastProgressAtNanos get() = progressAt
    override val hasProgressed get() = progressed

    fun die() {
        if (alive) {
            alive = false
            onDeath()
        }
    }

    fun beat() {
        progressAt = System.nanoTime()
        progressed = true
    }

    override fun terminate() {
        log += "terminate"
        if (!ignoresTerm) die()
    }

    override fun destroyForcibly() {
        log += "kill"
        die()
    }

    override suspend fun awaitExit(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (alive) {
            if (System.nanoTime() >= deadline) return false
            delay(5)
        }
        return true
    }
}

internal class FakeLauncher(
    val log: MutableList<String> = CopyOnWriteArrayList(),
) : PauseFeedLauncher {
    val handles = CopyOnWriteArrayList<FakeHandle>()
    val aliveNow = AtomicInteger()
    val maxAlive = AtomicInteger()
    val commands = CopyOnWriteArrayList<PauseFeedCommand>()
    var ignoreTerm = false
    var failLaunch = false

    override fun launch(command: PauseFeedCommand): PauseFeedProcessHandle {
        if (failLaunch) throw IllegalStateException("no ffmpeg")
        log += "launch"
        commands += command
        val now = aliveNow.incrementAndGet()
        maxAlive.updateAndGet { maxOf(it, now) }
        return FakeHandle(log = log, ignoresTerm = ignoreTerm) { aliveNow.decrementAndGet() }.also { handles += it }
    }
}

internal class FakeWishes : PauseFeedDataSource {
    val room = Uuid.random()
    val wanted = ConcurrentHashMap<Uuid, List<String>>()

    override fun wishFor(streamId: Uuid): PauseFeedWish? = wanted[streamId]?.let { PauseFeedWish(roomId = room, targetUrls = it) }
}

private val PUBLIC = HostResolver { listOf(InetAddress.getByName("203.0.113.9")) }
private val FAST =
    PauseFeedTimings(
        watchdogInterval = 30.milliseconds,
        livenessTimeout = 400.milliseconds,
        stopGrace = 150.milliseconds,
        killGrace = 100.milliseconds,
        restartBackoff = listOf(40.milliseconds, 80.milliseconds),
    )

private fun eventually(
    timeoutMs: Long = 5_000,
    check: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (check()) return
        Thread.sleep(10)
    }
    check() shouldBe true
}

class PauseFeedSupervisorTest :
    FunSpec({
        val image = Files.createTempFile("pause", ".png").toFile()
        val media =
            object : PauseMediaSource {
                override fun imageFile(): File = image

                override fun musicFile(): File? = null
            }

        class Rig(
            enabled: Boolean = true,
            allowRtmps: Boolean = true,
        ) {
            val launcher = FakeLauncher()
            val wishes = FakeWishes()
            val changes = CopyOnWriteArrayList<Uuid>()
            val ended = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val supervisor =
                PauseFeedSupervisor(
                    launcher = launcher,
                    media = media,
                    targetPolicy = PauseFeedTargetPolicy(resolver = PUBLIC, allowRtmps = allowRtmps),
                    dataSource = wishes,
                    enabled = enabled,
                    tlsVerifyArgs =
                        if (allowRtmps) {
                            listOf(
                                "-tls_verify",
                                "1",
                                "-ca_file",
                                "/etc/ssl/certs/ca-certificates.crt",
                            )
                        } else {
                            emptyList()
                        },
                    onStateChanged = { changes += it },
                    onFeedEnded = { ended.incrementAndGet() },
                    scope = scope,
                    timings = FAST,
                )

            fun want(vararg urls: String): Uuid = Uuid.random().also { wishes.wanted[it] = urls.toList() }

            fun want(): Uuid = want("rtmp://a.example.org/live/KEYAAAA1")

            fun close() {
                supervisor.shutdown()
                scope.cancel()
            }
        }

        test("a wanted stream starts exactly one feed (idempotent), becomes ACTIVE after the first progress line and notifies the room") {
            val rig = Rig()
            val id = rig.want()
            runBlocking {
                rig.supervisor.ensureRunning(id)
                rig.supervisor.ensureRunning(id)
                rig.supervisor.ensureRunning(id)
            }
            rig.launcher.handles.size shouldBe 1
            rig.supervisor.stateOf(id) shouldBe ConferenceStreamPauseFeedState.STARTING
            rig.supervisor.anyRunning() shouldBe true
            rig.changes.size shouldBe 1
            rig.launcher.handles
                .single()
                .beat()
            eventually { rig.supervisor.stateOf(id) == ConferenceStreamPauseFeedState.ACTIVE }
            // The state is set before the room is notified: wait for the notification, do not race it.
            eventually { rig.changes.size == 2 }
            rig.changes.size shouldBe 2
            rig.close()
        }

        test("a stream the database does not want gets no feed; a disabled supervisor never starts one") {
            val rig = Rig()
            runBlocking { rig.supervisor.ensureRunning(Uuid.random()) }
            rig.launcher.handles.size shouldBe 0
            rig.close()
            val off = Rig(enabled = false)
            val id = off.want()
            runBlocking { off.supervisor.ensureRunning(id) }
            off.launcher.handles.size shouldBe 0
            off.supervisor.stateOf(id) shouldBe ConferenceStreamPauseFeedState.OFF
            off.close()
        }

        test(
            "stopThen terminates the feed BEFORE the block runs and removes it; a process that ignores SIGTERM is killed after the grace time",
        ) {
            val rig = Rig()
            val id = rig.want()
            runBlocking { rig.supervisor.ensureRunning(id) }
            runBlocking { rig.supervisor.stopThen(streamId = id) { rig.launcher.log += "block" } }
            rig.launcher.log shouldContainExactly listOf("launch", "terminate", "block")
            rig.supervisor.stateOf(id) shouldBe ConferenceStreamPauseFeedState.OFF
            rig.ended.get() shouldBe 1

            val stubborn = Rig()
            stubborn.launcher.ignoreTerm = true
            val sid = stubborn.want()
            runBlocking { stubborn.supervisor.ensureRunning(sid) }
            val started = System.nanoTime()
            runBlocking { stubborn.supervisor.stopThen(streamId = sid) { stubborn.launcher.log += "block" } }
            ((System.nanoTime() - started) / 1_000_000 >= 140) shouldBe true
            stubborn.launcher.log shouldContainExactly listOf("launch", "terminate", "kill", "block")
            rig.close()
            stubborn.close()
        }

        test("RACE: ensureRunning against the claim of a restart never yields two senders, nor a feed after the claim (100 rounds)") {
            runBlocking {
                repeat(100) {
                    val rig = Rig()
                    val id = rig.want()
                    rig.supervisor.ensureRunning(id)
                    val claimed =
                        java.util.concurrent.atomic
                            .AtomicBoolean(false)
                    val jobs =
                        listOf(
                            async(Dispatchers.Default) {
                                repeat(5) { rig.supervisor.ensureRunning(id) }
                            },
                            async(Dispatchers.Default) {
                                rig.supervisor.stopThen(streamId = id) {
                                    // the DB claim: from here on the stream is no longer PAUSED, so no feed is wanted
                                    rig.wishes.wanted.remove(id)
                                    claimed.set(true)
                                    rig.launcher.aliveNow.get() shouldBe 0
                                }
                            },
                        )
                    jobs.awaitAll()
                    claimed.get() shouldBe true
                    rig.launcher.maxAlive.get() shouldBe 1
                    rig.launcher.aliveNow.get() shouldBe 0
                    rig.supervisor.stateOf(id) shouldBe ConferenceStreamPauseFeedState.OFF
                    rig.close()
                }
            }
        }

        test("a crash restarts the feed after the back-off, twice, then FAILED for good -- no fourth launch") {
            val rig = Rig()
            val id = rig.want()
            runBlocking { rig.supervisor.ensureRunning(id) }
            rig.launcher.handles[0].beat()
            rig.launcher.handles[0].die()
            eventually { rig.launcher.handles.size == 2 }
            rig.launcher.handles[1].die()
            eventually { rig.launcher.handles.size == 3 }
            rig.launcher.handles[2].die()
            eventually { rig.supervisor.stateOf(id) == ConferenceStreamPauseFeedState.FAILED }
            Thread.sleep(300)
            rig.launcher.handles.size shouldBe 3
            runBlocking { rig.supervisor.ensureRunning(id) } // FAILED stays for this pause
            rig.launcher.handles.size shouldBe 3
            rig.changes.last() shouldBe rig.wishes.room
            rig.close()
        }

        test("no progress line for the liveness time is a failure like a crash: the stalled process is stopped and replaced") {
            val rig = Rig()
            val id = rig.want()
            runBlocking { rig.supervisor.ensureRunning(id) }
            eventually(timeoutMs = 3_000) { rig.launcher.handles.size >= 2 }
            rig.launcher.log shouldContain "terminate"
            rig.close()
        }

        test("a restart reads the wanted state again: not wanted any more means no relaunch and no entry") {
            val rig = Rig()
            val id = rig.want()
            runBlocking { rig.supervisor.ensureRunning(id) }
            rig.launcher.handles[0].beat()
            rig.wishes.wanted.remove(id)
            rig.launcher.handles[0].die()
            eventually { rig.supervisor.stateOf(id) == ConferenceStreamPauseFeedState.OFF }
            Thread.sleep(150)
            rig.launcher.handles.size shouldBe 1
            rig.close()
        }

        test("at most four feeds run; the fifth stream is FAILED and the launcher is not called for it") {
            val rig = Rig()
            val ids = (1..5).map { rig.want("rtmp://a.example.org/live/KEY$it$it$it$it") }
            runBlocking { ids.forEach { rig.supervisor.ensureRunning(it) } }
            rig.launcher.handles.size shouldBe 4
            rig.supervisor.stateOf(ids[4]) shouldBe ConferenceStreamPauseFeedState.FAILED
            // one at most per stream: asking again changes nothing
            runBlocking { ids.forEach { rig.supervisor.ensureRunning(it) } }
            rig.launcher.handles.size shouldBe 4
            rig.close()
        }

        test(
            "targets the policy refuses get no feed; if none is left the feed is FAILED without a process; rtmps without a CA bundle is skipped",
        ) {
            val rig = Rig(allowRtmps = false)
            val onlyTls = rig.want("rtmps://a.example.org/live/KEYAAAA1")
            val mixed = rig.want("rtmps://a.example.org/live/KEYBBBB2", "rtmp://c.example.org/live/KEYCCCC3")
            runBlocking {
                rig.supervisor.ensureRunning(onlyTls)
                rig.supervisor.ensureRunning(mixed)
            }
            rig.supervisor.stateOf(onlyTls) shouldBe ConferenceStreamPauseFeedState.FAILED
            rig.launcher.handles.size shouldBe 1
            rig.launcher.commands
                .single()
                .args
                .last() shouldBe "rtmp://c.example.org/live/KEYCCCC3"
            rig.close()

            val priv = Rig()
            val pid = priv.want("rtmp://10.0.0.5/live/KEYDDDD4")
            val privateOnly =
                PauseFeedSupervisor(
                    launcher = priv.launcher,
                    media = media,
                    targetPolicy = PauseFeedTargetPolicy(resolver = { listOf(InetAddress.getByName("10.0.0.5")) }, allowRtmps = true),
                    dataSource = priv.wishes,
                    enabled = true,
                    tlsVerifyArgs = emptyList(),
                    onStateChanged = {
                    },
                    onFeedEnded = {},
                    scope = priv.scope,
                    timings = FAST,
                )
            runBlocking { privateOnly.ensureRunning(pid) }
            privateOnly.stateOf(pid) shouldBe ConferenceStreamPauseFeedState.FAILED
            priv.launcher.handles.size shouldBe 0
            priv.close()
        }

        test("a launch failure is FAILED, not an exception") {
            val rig = Rig()
            rig.launcher.failLaunch = true
            val id = rig.want()
            runBlocking { rig.supervisor.ensureRunning(id) }
            rig.supervisor.stateOf(id) shouldBe ConferenceStreamPauseFeedState.FAILED
            rig.close()
        }

        test("reconcile stops every feed that is not wanted; shutdown ends all and may be called twice") {
            val rig = Rig()
            val keep = rig.want("rtmp://a.example.org/live/KEYAAAA1")
            val drop = rig.want("rtmp://a.example.org/live/KEYBBBB2")
            runBlocking {
                rig.supervisor.ensureRunning(keep)
                rig.supervisor.ensureRunning(drop)
                rig.supervisor.reconcile(setOf(keep))
            }
            rig.supervisor.stateOf(drop) shouldBe ConferenceStreamPauseFeedState.OFF
            rig.supervisor.stateOf(keep) shouldBe ConferenceStreamPauseFeedState.STARTING
            rig.launcher.aliveNow.get() shouldBe 1
            rig.supervisor.shutdown()
            rig.supervisor.shutdown()
            rig.launcher.aliveNow.get() shouldBe 0
            rig.scope.cancel()
        }

        test("the supervisor has no LiveKit client and cannot join a room: no LiveKit type in any constructor or field") {
            val types =
                (
                    PauseFeedSupervisor::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() } +
                        PauseFeedSupervisor::class.java.declaredFields.map { it.type }
                ).map { it.name }
            types.filter { it.contains("LiveKit", ignoreCase = true) } shouldBe emptyList()
        }
    })
