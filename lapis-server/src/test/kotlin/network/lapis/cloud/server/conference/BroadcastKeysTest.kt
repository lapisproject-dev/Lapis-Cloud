package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.shared.rpc.ConferenceStreamBroadcastKeyRejectedException
import kotlin.uuid.Uuid

/** V1.9.274 -- the rules of [BroadcastKeys]: the character/length check, the AAD binding of a sealed broadcast key and the column size. */
class BroadcastKeysTest :
    FunSpec({
        val box = SecretBox(ByteArray(32) { it.toByte() })

        test("a key of 1 and of 512 printable ASCII characters is accepted") {
            BroadcastKeys.validate("a")
            BroadcastKeys.validate("FB-1234567890-0-AbCdEfGhIjKlMn")
            BroadcastKeys.validate("!".repeat(512))
            BroadcastKeys.validate("~".repeat(512))
        }

        test(
            "an empty key, a key of 513 characters and every disallowed character are refused with a fixed text that never echoes the input",
        ) {
            val bad =
                listOf(
                    "",
                    "x".repeat(513),
                    "has space",
                    " lead",
                    "trail ",
                    "line\nbreak",
                    "tab\there",
                    "cr\rhere",
                    "nul\u0000here",
                    "del\u007fhere",
                    "umläut",
                    "emoji😀",
                    "nbsp here",
                )
            bad.forEach { input ->
                val e = shouldThrow<ConferenceStreamBroadcastKeyRejectedException> { BroadcastKeys.validate(input) }
                e.message shouldBe "The key contains invalid characters or is too long"
                (input.isNotBlank() && e.message.contains(input)) shouldBe false
            }
        }

        test("a sealed broadcast key opens only with the AAD of its own target") {
            val targetA = Uuid.random()
            val targetB = Uuid.random()
            val sealed = BroadcastKeys.seal(targetId = targetA, key = "secret-key-A", box = box)
            box.open(sealed = sealed, aad = BroadcastKeys.aad(targetA)) shouldBe "secret-key-A"
            shouldThrow<SecretBoxException> { box.open(sealed = sealed, aad = BroadcastKeys.aad(targetB)) }
        }

        test("the AAD of a broadcast key differs from the AAD of a destination key, so the two ciphertexts cannot be swapped") {
            val id = Uuid.random()
            // A destination key is sealed with the bare id as AAD; a broadcast key with the prefixed form.
            val destinationSealed = box.seal(plaintext = "dest-key", aad = id.toString())
            shouldThrow<SecretBoxException> { box.open(sealed = destinationSealed, aad = BroadcastKeys.aad(id)) }
            val broadcastSealed = BroadcastKeys.seal(targetId = id, key = "bc-key", box = box)
            shouldThrow<SecretBoxException> { box.open(sealed = broadcastSealed, aad = id.toString()) }
        }

        test("the sealed form of a 512 character key fits the 1024 character column") {
            val sealed = BroadcastKeys.seal(targetId = Uuid.random(), key = "k".repeat(512), box = box)
            sealed.length shouldBeLessThanOrEqualTo 1024
        }

        test("sealing refuses an invalid key before anything is produced") {
            shouldThrow<ConferenceStreamBroadcastKeyRejectedException> {
                BroadcastKeys.seal(
                    targetId = Uuid.random(),
                    key = "bad key",
                    box = box,
                )
            }
        }
    })
