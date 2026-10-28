package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.conference.CachedConferenceConnectedMembers
import network.lapis.cloud.server.conference.FakeLiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import kotlin.time.Duration.Companion.seconds

/** A counting LiveKit stand-in: delegates to [FakeLiveKitAdminClient] and can be told to fail. */
private class CountingLiveKit(
    private val delegate: FakeLiveKitAdminClient = FakeLiveKitAdminClient(),
) : LiveKitAdminClient by delegate {
    var calls = 0
    var failing = false

    fun put(
        room: String,
        vararg identities: String,
    ) {
        delegate.participantsByRoom[room] = identities.map { LiveKitParticipantInfo(identity = it) }
    }

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> {
        calls++
        if (failing) error("boom with identity secret-123")
        return delegate.listParticipants(room)
    }
}

/** V1.9.236 -- 10 s TTL (injected clock), the entry cap, and a failure that is an empty set. */
class CachedConferenceConnectedMembersTest :
    FunSpec({
        test("an answer is reused inside the TTL and fetched again after it") {
            var now = 0L
            val livekit = CountingLiveKit().also { it.put(room = "room-a", "u1", "u2") }
            val cache = CachedConferenceConnectedMembers(liveKit = livekit, ttl = 10.seconds) { now }
            cache.connectedIdentities("room-a") shouldBe setOf("u1", "u2")
            now += 9.seconds.inWholeNanoseconds
            cache.connectedIdentities("room-a")
            livekit.calls shouldBe 1
            now += 2.seconds.inWholeNanoseconds
            livekit.put(room = "room-a", "u1")
            cache.connectedIdentities("room-a") shouldBe setOf("u1")
            livekit.calls shouldBe 2
        }

        test("the cache holds at most 256 rooms") {
            val livekit = CountingLiveKit()
            var now = 0L
            val cache = CachedConferenceConnectedMembers(liveKit = livekit, ttl = 10.seconds) { now++ }
            repeat(CachedConferenceConnectedMembers.MAX_ENTRIES + 40) { cache.connectedIdentities("room-$it") }
            cache.sizeForTest() shouldBe CachedConferenceConnectedMembers.MAX_ENTRIES
        }

        test("a failure is an empty set and is not cached") {
            val livekit = CountingLiveKit().also { it.put(room = "room-a", "u1") }
            val cache = CachedConferenceConnectedMembers(liveKit = livekit, ttl = 10.seconds) { 0L }
            livekit.failing = true
            cache.connectedIdentities("room-a") shouldBe emptySet()
            livekit.failing = false
            cache.connectedIdentities("room-a") shouldBe setOf("u1")
            livekit.calls shouldBe 2
        }
    })
