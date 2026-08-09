package network.lapis.cloud.server.conference

import com.nimbusds.jose.KeyLengthException
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

private const val API_KEY = "devkey"
private const val API_SECRET = "lapis-dev-livekit-secret-32bytes-min!!"
private const val ROOM_NAME = "lc-11111111-1111-1111-1111-111111111111"
private const val MEMBER_ID = "22222222-2222-2222-2222-222222222222"
private const val DISPLAY_NAME = "Ada Lovelace"

private fun verify(jwt: String): SignedJWT {
    val signed = SignedJWT.parse(jwt)
    signed.verify(MACVerifier(API_SECRET.toByteArray(Charsets.UTF_8))).shouldBeTrue()
    return signed
}

private fun videoGrant(signed: SignedJWT): Map<String, Any> = signed.jwtClaimsSet.getJSONObjectClaim("video")

class LiveKitAccessTokenTest :
    FunSpec({
        test("mintParticipantToken produces a valid HS256 JWT with the room-pinned participant grant") {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val minted =
                LiveKitAccessToken.mintParticipantToken(
                    apiKey = API_KEY,
                    apiSecret = API_SECRET,
                    roomName = ROOM_NAME,
                    identity = MEMBER_ID,
                    displayName = DISPLAY_NAME,
                    ttl = 240.minutes,
                    now = now,
                )

            val signed = verify(minted.jwt)
            val claims = signed.jwtClaimsSet
            claims.issuer shouldBe API_KEY
            claims.subject shouldBe MEMBER_ID
            claims.getStringClaim("name") shouldBe DISPLAY_NAME
            claims.notBeforeTime.toInstant().toKotlinInstant() shouldBe now
            claims.expirationTime.toInstant().toKotlinInstant() shouldBe (now + 240.minutes)
            minted.expiresAt shouldBe (now + 240.minutes)

            val grant = videoGrant(signed)
            grant["room"] shouldBe ROOM_NAME
            grant["roomJoin"] shouldBe true
            grant["canPublish"] shouldBe true
            grant["canSubscribe"] shouldBe true
            grant["canPublishData"] shouldBe true
            grant["canUpdateOwnMetadata"] shouldBe false
            // The participant token must NEVER carry any admin grant -- see LiveKitAccessToken KDoc
            // "the two shapes are never mixed".
            grant.containsKey("roomCreate").shouldBeFalse()
            grant.containsKey("roomAdmin").shouldBeFalse()
            grant.containsKey("roomList").shouldBeFalse()
        }

        test("mintAdminToken without a room: no room claim, all three admin grants, never roomJoin") {
            val jwt = LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = API_SECRET, room = null)

            val signed = verify(jwt)
            signed.jwtClaimsSet.issuer shouldBe API_KEY
            val grant = videoGrant(signed)
            grant["roomCreate"] shouldBe true
            grant["roomAdmin"] shouldBe true
            grant["roomList"] shouldBe true
            grant.containsKey("room").shouldBeFalse()
            grant.containsKey("roomJoin").shouldBeFalse()
        }

        test("mintAdminToken with a room: the room claim is set -- required for ListParticipants/RemoveParticipant, see class KDoc") {
            val jwt = LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = API_SECRET, room = ROOM_NAME)

            val grant = videoGrant(verify(jwt))
            grant["room"] shouldBe ROOM_NAME
        }

        // ── mintEgressToken (V1.0 Wave 2 "Aufzeichnung") ────────────────────

        test("mintEgressToken grants EXACTLY roomRecord + room, never roomJoin/roomCreate/roomAdmin/roomList") {
            val jwt = LiveKitAccessToken.mintEgressToken(apiKey = API_KEY, apiSecret = API_SECRET, room = ROOM_NAME)

            val signed = verify(jwt)
            signed.jwtClaimsSet.issuer shouldBe API_KEY
            val grant = videoGrant(signed)
            grant["roomRecord"] shouldBe true
            grant["room"] shouldBe ROOM_NAME
            grant.containsKey("roomJoin").shouldBeFalse()
            grant.containsKey("roomCreate").shouldBeFalse()
            grant.containsKey("roomAdmin").shouldBeFalse()
            grant.containsKey("roomList").shouldBeFalse()
        }

        test("mintEgressToken expires after ADMIN_TOKEN_TTL_SECONDS (60s) by default, same as mintAdminToken") {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val jwt = LiveKitAccessToken.mintEgressToken(apiKey = API_KEY, apiSecret = API_SECRET, room = ROOM_NAME, now = now)

            val claims = verify(jwt).jwtClaimsSet
            claims.expirationTime.toInstant().toKotlinInstant() shouldBe (now + LiveKitAccessToken.ADMIN_TOKEN_TTL_SECONDS.seconds)
        }

        test("mintEgressToken carries a unique jti per call") {
            val first = LiveKitAccessToken.mintEgressToken(apiKey = API_KEY, apiSecret = API_SECRET, room = ROOM_NAME)
            val second = LiveKitAccessToken.mintEgressToken(apiKey = API_KEY, apiSecret = API_SECRET, room = ROOM_NAME)

            verify(first).jwtClaimsSet.jwtid shouldNotBe verify(second).jwtClaimsSet.jwtid
        }

        test("mintEgressToken never literally contains the raw apiSecret string") {
            val jwt = LiveKitAccessToken.mintEgressToken(apiKey = API_KEY, apiSecret = API_SECRET, room = ROOM_NAME)
            jwt.shouldNotContain(API_SECRET)
        }

        test("mintEgressToken with a secret shorter than 32 bytes propagates nimbus's own KeyLengthException") {
            shouldThrow<KeyLengthException> {
                LiveKitAccessToken.mintEgressToken(apiKey = API_KEY, apiSecret = "too-short", room = ROOM_NAME)
            }
        }

        test("mintAdminToken expires after ADMIN_TOKEN_TTL_SECONDS (60s) by default") {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val jwt = LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = API_SECRET, now = now)

            val claims = verify(jwt).jwtClaimsSet
            claims.expirationTime.toInstant().toKotlinInstant() shouldBe (now + LiveKitAccessToken.ADMIN_TOKEN_TTL_SECONDS.seconds)
            LiveKitAccessToken.ADMIN_TOKEN_TTL_SECONDS shouldBe 60L
        }

        test("every mint carries a unique jti") {
            val first = LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = API_SECRET)
            val second = LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = API_SECRET)

            verify(first).jwtClaimsSet.jwtid shouldNotBe verify(second).jwtClaimsSet.jwtid
        }

        test("tamper test: flipping a byte in the signature segment fails verification") {
            val minted =
                LiveKitAccessToken.mintParticipantToken(
                    apiKey = API_KEY,
                    apiSecret = API_SECRET,
                    roomName = ROOM_NAME,
                    identity = MEMBER_ID,
                    displayName = DISPLAY_NAME,
                    ttl = 240.minutes,
                )
            val parts = minted.jwt.split(".")
            val tamperedSignature = parts[2].reversed()
            val tampered = "${parts[0]}.${parts[1]}.$tamperedSignature"

            SignedJWT.parse(tampered).verify(MACVerifier(API_SECRET.toByteArray(Charsets.UTF_8))).shouldBeFalse()
        }

        test("tamper test: a payload edited after signing (grant escalation attempt) fails verification") {
            val minted =
                LiveKitAccessToken.mintParticipantToken(
                    apiKey = API_KEY,
                    apiSecret = API_SECRET,
                    roomName = ROOM_NAME,
                    identity = MEMBER_ID,
                    displayName = DISPLAY_NAME,
                    ttl = 240.minutes,
                )
            val parts = minted.jwt.split(".")
            // Attempt to smuggle in an admin grant by editing the (unsigned) payload segment --
            // this must be rejected by signature verification, not merely "not acted upon".
            val forgedPayload =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    """{"iss":"$API_KEY","video":{"roomCreate":true,"roomAdmin":true}}""".toByteArray(),
                )
            val forged = "${parts[0]}.$forgedPayload.${parts[2]}"

            SignedJWT.parse(forged).verify(MACVerifier(API_SECRET.toByteArray(Charsets.UTF_8))).shouldBeFalse()
        }

        test("a verifier keyed with the WRONG secret rejects an otherwise-valid token") {
            val minted =
                LiveKitAccessToken.mintParticipantToken(
                    apiKey = API_KEY,
                    apiSecret = API_SECRET,
                    roomName = ROOM_NAME,
                    identity = MEMBER_ID,
                    displayName = DISPLAY_NAME,
                    ttl = 240.minutes,
                )
            val wrongSecret = "wrong-secret-but-still-at-least-32-bytes-long!!"
            SignedJWT.parse(minted.jwt).verify(MACVerifier(wrongSecret.toByteArray(Charsets.UTF_8))).shouldBeFalse()
        }

        test(
            "a secret shorter than 32 bytes propagates nimbus's own KeyLengthException -- belt-and-suspenders behind ConferenceConfig's own fail-fast check",
        ) {
            shouldThrow<KeyLengthException> {
                LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = "too-short")
            }
        }

        test("mintParticipantToken never uses Clock.System when now: is supplied -- deterministic for a fixed now") {
            val fixedNow = Instant.fromEpochMilliseconds(0)
            val minted =
                LiveKitAccessToken.mintParticipantToken(
                    apiKey = API_KEY,
                    apiSecret = API_SECRET,
                    roomName = ROOM_NAME,
                    identity = MEMBER_ID,
                    displayName = DISPLAY_NAME,
                    ttl = 5.minutes,
                    now = fixedNow,
                )
            minted.expiresAt shouldBe Instant.fromEpochMilliseconds(0) + 5.minutes
        }

        test("default now: parameter uses roughly the real clock") {
            val before = Clock.System.now()
            val minted =
                LiveKitAccessToken.mintAdminToken(apiKey = API_KEY, apiSecret = API_SECRET)
            val after = Clock.System.now()

            val claims = verify(minted).jwtClaimsSet
            val notBefore = claims.notBeforeTime.toInstant().toKotlinInstant()
            // JWT NumericDate claims (RFC 7519 §2) are whole SECONDS -- nimbus truncates sub-second
            // precision on serialization, so `notBefore` can legitimately land up to ~1s before
            // `before` even though `now: Instant = Clock.System.now()` was evaluated strictly
            // between `before` and `after`.
            (notBefore >= before - 1.seconds && notBefore <= after).shouldBeTrue()
        }

        test("the minted JWT never literally contains the raw apiSecret string") {
            val minted =
                LiveKitAccessToken.mintParticipantToken(
                    apiKey = API_KEY,
                    apiSecret = API_SECRET,
                    roomName = ROOM_NAME,
                    identity = MEMBER_ID,
                    displayName = DISPLAY_NAME,
                    ttl = 240.minutes,
                )
            minted.jwt.shouldNotContain(API_SECRET)
        }
    })
