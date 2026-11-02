package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class EventNoticeMailLimiterTest :
    FunSpec({
        fun limiter(globalMax: Int = 100) =
            EventNoticeMailLimiter(
                perKey = FederationInboxRateLimiter(maxRequests = 1, window = 24.hours, maxTrackedKeys = 1000),
                global = FederationInboxRateLimiter(maxRequests = globalMax, window = 1.hours, maxTrackedKeys = 1),
            )

        test("one mail per event and address; a repeat is refused") {
            val l = limiter()
            val event = Uuid.random()
            l.tryAcquire(eventId = event, normalizedEmail = "a@example.org") shouldBe true
            l.tryAcquire(eventId = event, normalizedEmail = "a@example.org") shouldBe false
        }

        test("the key is (event, address): another event or another address is a new key") {
            val l = limiter()
            val event = Uuid.random()
            l.tryAcquire(eventId = event, normalizedEmail = "a@example.org") shouldBe true
            l.tryAcquire(eventId = Uuid.random(), normalizedEmail = "a@example.org") shouldBe true
            l.tryAcquire(eventId = event, normalizedEmail = "b@example.org") shouldBe true
        }

        test("the global cap applies after the per-key check, and a repeated address does not spend global budget") {
            val l = limiter(globalMax = 2)
            val event = Uuid.random()
            l.tryAcquire(eventId = event, normalizedEmail = "a@example.org") shouldBe true
            l.tryAcquire(eventId = event, normalizedEmail = "a@example.org") shouldBe false
            l.tryAcquire(eventId = event, normalizedEmail = "b@example.org") shouldBe true // second global slot
            l.tryAcquire(eventId = event, normalizedEmail = "c@example.org") shouldBe false // global cap reached
        }
    })
