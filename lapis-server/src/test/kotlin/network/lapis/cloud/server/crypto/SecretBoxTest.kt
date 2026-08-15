package network.lapis.cloud.server.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.security.SecureRandom

/**
 * Hermetic (no I/O) tests for [SecretBox] -- the codebase's first at-rest encryption primitive
 * (see that class's own KDoc). Every test constructs its own key(s) via [SecureRandom] rather than
 * a fixed literal, so nothing in this file's own source accidentally becomes a "real-looking"
 * secret some future grep/leak-scanner would need to special-case.
 */
class SecretBoxTest :
    FunSpec({
        fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

        test("seal then open with the same key and AAD recovers the exact plaintext") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")

            box.open(sealed = sealed, aad = "destination-1") shouldBe "rtmp-super-secret-key-value"
        }

        test("seal output is versioned 'v1:<iv>:<ciphertext>' with exactly two ':' separators") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "some-stream-key", aad = "destination-1")

            sealed shouldStartWith "v1:"
            sealed.split(":").size shouldBe 3
        }

        test("empty plaintext round-trips too") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "", aad = "destination-1")

            box.open(sealed = sealed, aad = "destination-1") shouldBe ""
        }

        test("constructor rejects a key that is not exactly KEY_SIZE_BYTES") {
            shouldThrow<IllegalArgumentException> { SecretBox(ByteArray(31)) }
            shouldThrow<IllegalArgumentException> { SecretBox(ByteArray(33)) }
            shouldThrow<IllegalArgumentException> { SecretBox(ByteArray(0)) }
        }

        test("opening with a DIFFERENT key fails, never leaking anything about the original plaintext") {
            val sealed = SecretBox(randomKey()).seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")
            val wrongBox = SecretBox(randomKey())

            val exception = shouldThrow<SecretBoxException> { wrongBox.open(sealed = sealed, aad = "destination-1") }
            exception.message shouldBe "Stream-Zugangsdaten konnten nicht entschluesselt werden"
        }

        test("opening with a DIFFERENT AAD (e.g. a different destination id) fails") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")

            shouldThrow<SecretBoxException> { box.open(sealed = sealed, aad = "destination-2") }
        }

        test("a single flipped char in the ciphertext segment fails GCM tag verification") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")
            val parts = sealed.split(":")
            val tamperedCiphertext = flipFirstBase64Char(parts[2])
            val tampered = "${parts[0]}:${parts[1]}:$tamperedCiphertext"

            shouldThrow<SecretBoxException> { box.open(sealed = tampered, aad = "destination-1") }
        }

        test("a single flipped char in the IV segment also fails (GCM authenticates the IV implicitly via the tag context)") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")
            val parts = sealed.split(":")
            val tamperedIv = flipFirstBase64Char(parts[1])
            val tampered = "${parts[0]}:$tamperedIv:${parts[2]}"

            shouldThrow<SecretBoxException> { box.open(sealed = tampered, aad = "destination-1") }
        }

        test("two seals of the same plaintext/AAD produce different ciphertexts -- fresh IV every call") {
            val box = SecretBox(randomKey())
            val first = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")
            val second = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")

            first shouldNotBe second
            // ... yet both still decrypt back to the identical plaintext.
            box.open(sealed = first, aad = "destination-1") shouldBe "rtmp-super-secret-key-value"
            box.open(sealed = second, aad = "destination-1") shouldBe "rtmp-super-secret-key-value"
        }

        test("a v2:-prefixed (or otherwise unversioned) value is rejected outright") {
            val box = SecretBox(randomKey())
            val sealed = box.seal(plaintext = "rtmp-super-secret-key-value", aad = "destination-1")
            val parts = sealed.split(":")
            val v2Prefixed = "v2:${parts[1]}:${parts[2]}"

            val exception = shouldThrow<SecretBoxException> { box.open(sealed = v2Prefixed, aad = "destination-1") }
            exception.message shouldBe "Stream-Zugangsdaten konnten nicht entschluesselt werden"
        }

        test("a malformed value (wrong part count) is rejected outright") {
            val box = SecretBox(randomKey())

            shouldThrow<SecretBoxException> { box.open(sealed = "not-even-close-to-the-format", aad = "destination-1") }
            shouldThrow<SecretBoxException> { box.open(sealed = "v1:onlyOnePart", aad = "destination-1") }
            shouldThrow<SecretBoxException> { box.open(sealed = "v1:a:b:c", aad = "destination-1") }
        }

        test("non-base64url segments are rejected outright, never a raw IllegalArgumentException") {
            val box = SecretBox(randomKey())

            shouldThrow<SecretBoxException> { box.open(sealed = "v1:not base64!:also not base64!", aad = "destination-1") }
        }

        test("SecretBoxException never carries a cause -- see class KDoc") {
            val box = SecretBox(randomKey())
            val exception = shouldThrow<SecretBoxException> { box.open(sealed = "garbage", aad = "destination-1") }

            exception.cause shouldBe null
        }
    })

/**
 * Flips the FIRST character of a base64url segment to a different valid base64url character,
 * corrupting the decoded bytes without changing the segment's length. Deliberately the FIRST
 * character, not the last: the first character of any base64 group always encodes fully
 * meaningful bits, whereas the very last character of a segment whose underlying byte count is
 * not a multiple of 3 encodes some discarded zero-padding bits low in its 6-bit value -- flipping
 * only within those padding bits (e.g. toggling between 'A'=000000 and 'B'=000001, which differ
 * only in their lowest bit) can decode back to the identical byte sequence, silently defeating the
 * "tamper" this helper exists to create.
 */
private fun flipFirstBase64Char(segment: String): String {
    val first = segment.first()
    val replacement = if (first == 'A') 'B' else 'A'
    return replacement + segment.drop(1)
}
