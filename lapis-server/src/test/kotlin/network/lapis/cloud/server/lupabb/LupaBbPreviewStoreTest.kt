package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.lapis.cloud.server.time.ServerClock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.226 -- the in-memory token store: single use, owner bound, time limited, bounded. */
class LupaBbPreviewStoreTest :
    FunSpec({
        val plan = LupaBbPlan(rows = emptyList(), notices = emptyList(), dataSha256 = "0".repeat(64))
        val now = ServerClock.now()

        fun put(
            store: LupaBbPreviewStore,
            admin: Uuid,
        ) = store.put(adminId = admin, plan = plan, startedAt = now, expiresAt = now)

        test("a token works once, for its admin only") {
            val store = LupaBbPreviewStore()
            val admin = Uuid.random()
            val token = put(store, admin)
            store.take(token = token, adminId = admin) shouldNotBe null
            store.take(token = token, adminId = admin) shouldBe null
        }

        test("a foreign admin's guess burns the token, an unknown or oversized token yields nothing") {
            val store = LupaBbPreviewStore()
            val admin = Uuid.random()
            val token = put(store, admin)
            store.take(token = token, adminId = Uuid.random()) shouldBe null
            store.take(token = token, adminId = admin) shouldBe null
            store.take(token = "unbekannt", adminId = admin) shouldBe null
            store.take(token = "x".repeat(500), adminId = admin) shouldBe null
        }

        test("peek keeps the entry and a foreign peek does not burn it") {
            val store = LupaBbPreviewStore()
            val admin = Uuid.random()
            val token = put(store, admin)
            store.peek(token = token, adminId = admin) { true } shouldBe true
            store.peek(token = token, adminId = Uuid.random()) { true } shouldBe null
            store.peek(token = token, adminId = admin) { true } shouldBe true
            store.take(token = token, adminId = admin) shouldNotBe null
        }

        test("a token expires after fifteen minutes on the monotonic clock") {
            var nanos = 0L
            val store = LupaBbPreviewStore(nanoTime = { nanos }, ttl = 15.minutes)
            val admin = Uuid.random()
            val token = put(store, admin)
            nanos += 14.minutes.inWholeNanoseconds
            store.peek(token = token, adminId = admin) { true } shouldBe true
            nanos += 2.minutes.inWholeNanoseconds
            store.peek(token = token, adminId = admin) { true } shouldBe null
            store.size() shouldBe 0
        }

        test("a new preview replaces the old one of the same admin; at most two entries in total") {
            val store = LupaBbPreviewStore()
            val a = Uuid.random()
            val b = Uuid.random()
            val c = Uuid.random()
            val first = put(store, a)
            val second = put(store, a)
            store.peek(token = first, adminId = a) { true } shouldBe null
            store.peek(token = second, adminId = a) { true } shouldBe true
            put(store, b)
            put(store, c)
            store.size() shouldBe LupaBbPreviewStore.MAX_ENTRIES
            store.peek(token = second, adminId = a) { true } shouldBe null
        }

        test("tokens are random and long enough") {
            val store = LupaBbPreviewStore()
            val tokens = (1..20).map { put(store, Uuid.random()) }.toSet()
            tokens.size shouldBe 20
            tokens.all { it.length >= 43 } shouldBe true
        }
    })
