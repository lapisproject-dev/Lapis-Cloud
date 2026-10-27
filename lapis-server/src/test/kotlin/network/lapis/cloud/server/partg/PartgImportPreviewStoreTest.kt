package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PartgUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class PartgImportPreviewStoreTest :
    FunSpec({
        var now = 0L
        val member = Uuid.random()

        fun put(
            store: PartgImportPreviewStore,
            who: Uuid = member,
        ) = store.put(
            memberId = who,
            year = 2025,
            unit = PartgUnit.Federal,
            current = mapOf("E01" to 1L),
            prior = emptyMap(),
            sha256 = "sha",
            fileName = "f.xlsx",
            revision = 0,
            priorRevision = 0,
        )

        test("tokens are 32 random bytes, unique, and single use") {
            val store = PartgImportPreviewStore(nanoTime = { now })
            val a = put(store)
            a.length shouldBe 43
            val b = put(store, Uuid.random())
            (a != b) shouldBe true
            (store.take(token = a, memberId = member) != null) shouldBe true
            store.take(token = a, memberId = member) shouldBe null
        }

        test("one entry per member; the newer replaces the older") {
            val store = PartgImportPreviewStore(nanoTime = { now })
            val a = put(store)
            val b = put(store)
            store.size() shouldBe 1
            store.take(token = a, memberId = member) shouldBe null
            (store.take(token = b, memberId = member) != null) shouldBe true
        }

        test("at most 64 entries: the oldest falls out") {
            val store = PartgImportPreviewStore(nanoTime = { now })
            val first = put(store, Uuid.random())
            val ids = (1..70).map { Uuid.random() }
            val tokens = ids.map { put(store, it) }
            store.size() shouldBe 64
            store.take(token = first, memberId = member) shouldBe null
            (store.take(token = tokens.last(), memberId = ids.last()) != null) shouldBe true
        }

        test("an entry expires after 15 minutes on the injected monotonic clock") {
            now = 0L
            val store = PartgImportPreviewStore(nanoTime = { now })
            val t = put(store)
            now += 14.minutes.inWholeNanoseconds
            val ok = store.take(token = t, memberId = member)
            (ok != null) shouldBe true
            val t2 = put(store)
            now += 15.minutes.inWholeNanoseconds
            store.take(token = t2, memberId = member) shouldBe null
        }

        test("a foreign member burns the token; garbage and over-long tokens are refused") {
            val store = PartgImportPreviewStore(nanoTime = { now })
            val t = put(store)
            store.take(token = t, memberId = Uuid.random()) shouldBe null
            store.take(token = t, memberId = member) shouldBe null
            store.take(token = "x".repeat(500), memberId = member) shouldBe null
            store.take(token = "", memberId = member) shouldBe null
        }
    })
