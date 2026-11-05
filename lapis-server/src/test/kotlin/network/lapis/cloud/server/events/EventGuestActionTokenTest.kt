package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** V1.9.281 -- the stateless guest-action token: bound by MAC to event, room, identity, purpose and expiry; every defect is `null`, nothing throws. */
class EventGuestActionTokenTest :
    FunSpec({
        val secret = "test-livekit-secret-at-least-32-bytes-long!!"
        val now = Instant.parse("2030-06-15T22:30:00Z")
        val event = Uuid.random()
        val room = Uuid.random()
        val identity = "evg-" + Uuid.random()
        val exp = now + 1.hours

        fun token(
            id: String = identity,
            at: Instant = exp,
        ) = EventGuestActionToken.issue(serverSecret = secret, eventId = event, roomId = room, identity = id, expiresAt = at)

        fun verify(
            token: String,
            e: Uuid = event,
            r: Uuid = room,
            at: Instant = now,
            s: String = secret,
        ) = EventGuestActionToken.verify(serverSecret = s, token = token, eventId = e, roomId = r, now = at)

        test("a valid token yields its identity and carries neither the event nor the room id") {
            val t = token()
            verify(t) shouldBe identity
            t shouldNotContain event.toString()
            t shouldNotContain room.toString()
        }

        test("a ticket guest identity (evg- plus 32 hex) is accepted too") {
            val id = "evg-" + "ab".repeat(16)
            verify(token(id = id)) shouldBe id
        }

        test("wrong room, wrong event, wrong secret are refused") {
            val t = token()
            verify(t, r = Uuid.random()) shouldBe null
            verify(t, e = Uuid.random()) shouldBe null
            verify(t, s = "another-secret-another-secret-another-secret") shouldBe null
        }

        test("the identity is bound: a token of A with the identity of B is refused") {
            val a = token()
            val parts = a.split('.').toMutableList()
            parts[1] = "evg-" + Uuid.random()
            verify(parts.joinToString(".")) shouldBe null
        }

        test("another purpose is refused: a MAC over the consent purpose does not verify") {
            val parts = token().split('.')
            val forged =
                EventCaptureAcknowledgments.hmac(
                    key =
                        EventCaptureAcknowledgments.hmac(
                            key = secret.toByteArray(),
                            data = "lapis-event-guest-action-v1".toByteArray(),
                        ),
                    data = "consent|$event|$room|$identity|${exp.epochSeconds}".toByteArray(),
                )
            val b64 =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(forged)
            verify("${parts[0]}.${parts[1]}.${parts[2]}.$b64") shouldBe null
        }

        test("expiry: exp == now is refused, exp just after now is accepted") {
            verify(token(at = now)) shouldBe null
            verify(token(at = now + 1.minutes)) shouldBe identity
            verify(token(at = exp), at = exp) shouldBe null
        }

        test("a changed character in the MAC, the expiry or the identity is refused") {
            val parts = token().split('.')
            val mac = parts[3]
            val flipped = (if (mac[0] == 'A') 'B' else 'A') + mac.substring(1)
            verify("${parts[0]}.${parts[1]}.${parts[2]}.$flipped") shouldBe null
            verify("${parts[0]}.${parts[1]}.${exp.epochSeconds + 1}.$mac") shouldBe null
            verify("${parts[0]}.evg-${Uuid.random()}.${parts[2]}.$mac") shouldBe null
        }

        test("a different version, three or five parts, an oversize token are refused") {
            val parts = token().split('.')
            verify("v2.${parts[1]}.${parts[2]}.${parts[3]}") shouldBe null
            verify("${parts[0]}.${parts[1]}.${parts[3]}") shouldBe null
            verify(token() + ".x") shouldBe null
            verify("v1." + "a".repeat(EventGuestActionToken.MAX_TOKEN_LENGTH)) shouldBe null
        }

        test("an identity without the evg- prefix (a member id) is never accepted, even when signed") {
            val member = Uuid.random().toString()
            verify(token(id = member)) shouldBe null
        }

        test("garbage never throws") {
            listOf(
                "",
                ".",
                "....",
                "v1....",
                "v1.evg-x.9.!!!",
                "v1.evg-${Uuid.random()}.notanumber.AAAA",
                "\u0000",
                "é".repeat(300),
            ).forEach {
                verify(it) shouldBe null
            }
        }

        test("expiryFor: window close plus 15 minutes, at least 1 minute, at most 24 hours from now") {
            EventGuestActionToken.expiryFor(windowClosesAt = now + 2.hours, now = now) shouldBe now + 2.hours + 15.minutes
            EventGuestActionToken.expiryFor(windowClosesAt = now - 5.hours, now = now) shouldBe now + 1.minutes
            EventGuestActionToken.expiryFor(windowClosesAt = now + 100.hours, now = now) shouldBe now + 24.hours
        }
    })
