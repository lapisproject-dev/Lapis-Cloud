package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val SHARED_SECRET = "lapis-dev-turn-shared-secret-32bytes-min!!"
private const val LABEL = "22222222-2222-2222-2222-222222222222"
private val URLS = listOf("turn:127.0.0.1:3478?transport=udp", "turn:127.0.0.1:3478?transport=tcp")

/** Recomputes the expected `credential` independently of [TurnCredentialMinter] itself -- a test that only re-called the production code under a different name would not actually verify the HMAC scheme. */
private fun expectedCredential(
    username: String,
    secret: String,
): String {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    return Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(Charsets.UTF_8)))
}

class TurnCredentialMinterTest :
    FunSpec({
        test("mint embeds the expiry epoch-seconds as the username prefix, followed by the label") {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val minted = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes, now = now)

            val expiresAt = now + 240.minutes
            minted.username shouldBe "${expiresAt.epochSeconds}:$LABEL"
            minted.expiresAt shouldBe expiresAt
            minted.urls shouldBe URLS
        }

        test("credential matches an independently-computed HMAC-SHA1(sharedSecret, username), base64-encoded") {
            val minted = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes)

            minted.credential shouldBe expectedCredential(minted.username, SHARED_SECRET)
        }

        test("a verifier keyed with the WRONG shared secret computes a different credential") {
            val minted = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes)

            val wrongSecret = "wrong-turn-secret-but-still-at-least-32-bytes!!"
            expectedCredential(minted.username, wrongSecret) shouldNotBe minted.credential
        }

        test("two mints for the same label at the same instant produce byte-identical credentials -- deterministic, not randomized") {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val first = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes, now = now)
            val second = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes, now = now)

            first.username shouldBe second.username
            first.credential shouldBe second.credential
        }

        test("a shorter TTL produces an earlier embedded expiry than a longer one, from the same now") {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val short = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 1.minutes, now = now)
            val long = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes, now = now)

            (short.expiresAt < long.expiresAt) shouldBe true
            short.credential shouldNotBe long.credential
        }

        test("the minted credential never literally contains the raw sharedSecret string") {
            val minted = TurnCredentialMinter.mint(sharedSecret = SHARED_SECRET, urls = URLS, label = LABEL, ttl = 240.minutes)

            minted.credential.shouldNotContain(SHARED_SECRET)
            minted.username.shouldNotContain(SHARED_SECRET)
        }
    })
