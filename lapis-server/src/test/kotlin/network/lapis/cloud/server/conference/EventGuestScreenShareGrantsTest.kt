package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** V1.9.282 -- [EventGuestScreenShareGrants]: one grant per room, bound to the participant sid, pruned with the room. */
class EventGuestScreenShareGrantsTest :
    FunSpec({
        test("at most one guest per room holds the grant; the same guest and sid is AlreadyHeld") {
            val grants = EventGuestScreenShareGrants()
            val room = Uuid.random()
            grants.tryGrant(roomId = room, identity = "evg-a", participantSid = "PA_1") shouldBe
                EventGuestScreenShareGrants.TryGrant.Granted
            grants.tryGrant(roomId = room, identity = "evg-a", participantSid = "PA_1") shouldBe
                EventGuestScreenShareGrants.TryGrant.AlreadyHeld
            grants.tryGrant(roomId = room, identity = "evg-b", participantSid = "PA_2") shouldBe EventGuestScreenShareGrants.TryGrant.Taken
            grants.current(room) shouldBe EventGuestScreenShareGrants.Grant(identity = "evg-a", participantSid = "PA_1")
        }

        test("the grant is bound to the sid: a reconnect (new sid) is not granted, and a new grant replaces the stale one") {
            val grants = EventGuestScreenShareGrants()
            val room = Uuid.random()
            grants.tryGrant(roomId = room, identity = "evg-a", participantSid = "PA_1")
            grants.isGranted(roomId = room, identity = "evg-a", participantSid = "PA_1") shouldBe true
            grants.isGranted(roomId = room, identity = "evg-a", participantSid = "PA_2") shouldBe false
            grants.isGranted(roomId = room, identity = "evg-b", participantSid = "PA_1") shouldBe false
            grants.tryGrant(roomId = room, identity = "evg-a", participantSid = "PA_2") shouldBe
                EventGuestScreenShareGrants.TryGrant.Granted
            grants.isGranted(roomId = room, identity = "evg-a", participantSid = "PA_1") shouldBe false
        }

        test("revoke only removes the grant of that guest") {
            val grants = EventGuestScreenShareGrants()
            val room = Uuid.random()
            grants.tryGrant(roomId = room, identity = "evg-a", participantSid = "PA_1")
            grants.revoke(roomId = room, identity = "evg-b") shouldBe false
            grants.current(room)?.identity shouldBe "evg-a"
            grants.revoke(roomId = room, identity = "evg-a") shouldBe true
            grants.current(room) shouldBe null
            grants.revoke(roomId = room, identity = "evg-a") shouldBe false
        }

        test("dropIfAbsent forgets a grant whose sid is gone, and pruneInactiveRooms forgets ended rooms") {
            val grants = EventGuestScreenShareGrants()
            val live = Uuid.random()
            val ended = Uuid.random()
            grants.tryGrant(roomId = live, identity = "evg-a", participantSid = "PA_1")
            grants.tryGrant(roomId = ended, identity = "evg-b", participantSid = "PA_2")
            grants.dropIfAbsent(roomId = live, presentSids = setOf("PA_1", "PA_9"))
            grants.current(live) shouldBe EventGuestScreenShareGrants.Grant(identity = "evg-a", participantSid = "PA_1")
            grants.dropIfAbsent(roomId = live, presentSids = setOf("PA_9"))
            grants.current(live) shouldBe null
            grants.pruneInactiveRooms(setOf(live))
            grants.current(ended) shouldBe null
        }

        test("parallel tryGrant calls grant exactly one guest") {
            val grants = EventGuestScreenShareGrants()
            val room = Uuid.random()
            val results =
                withContext(Dispatchers.Default) {
                    (1..50).map { n -> async { grants.tryGrant(roomId = room, identity = "evg-$n", participantSid = "PA_$n") } }.awaitAll()
                }
            results.count { it == EventGuestScreenShareGrants.TryGrant.Granted } shouldBe 1
            results.count { it == EventGuestScreenShareGrants.TryGrant.Taken } shouldBe 49
        }
    })
