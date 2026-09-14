package network.lapis.cloud.server.payment.fints

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". Pins the exact AAD-binding contract
 * [network.lapis.cloud.server.rpc.BankAccountService.persistVerified]/[FinTsPoller.processOneAccount]
 * both rely on: PIN and user id are sealed with `aad = bankAccountId.toString()`, so a ciphertext
 * copied onto a DIFFERENT `bank_account` row (a crafted UPDATE, or a bug swapping two rows) fails to
 * decrypt rather than silently authorizing FinTS access to someone else's bank account.
 */
class FinTsCredentialSealTest :
    FunSpec({
        fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

        test("PIN/userId seal-then-open with the SAME bankAccountId AAD recovers the exact plaintext") {
            val box = SecretBox(randomKey())
            val accountId = Uuid.random().toString()

            val userIdCiphertext = box.seal(plaintext = "4711123456", aad = accountId)
            val pinCiphertext = box.seal(plaintext = "S3cretPin!", aad = accountId)

            box.open(sealed = userIdCiphertext, aad = accountId) shouldBe "4711123456"
            box.open(sealed = pinCiphertext, aad = accountId) shouldBe "S3cretPin!"
        }

        test("a ciphertext sealed under one bankAccountId fails to open under a DIFFERENT bankAccountId's AAD") {
            val box = SecretBox(randomKey())
            val ownAccountId = Uuid.random().toString()
            val otherAccountId = Uuid.random().toString()
            val pinCiphertext = box.seal(plaintext = "S3cretPin!", aad = ownAccountId)

            shouldThrow<SecretBoxException> { box.open(sealed = pinCiphertext, aad = otherAccountId) }
        }

        test("an empty or malformed ('v2:'-prefixed) ciphertext value never opens, throws SecretBoxException") {
            val box = SecretBox(randomKey())
            val accountId = Uuid.random().toString()

            shouldThrow<SecretBoxException> { box.open(sealed = "", aad = accountId) }
            shouldThrow<SecretBoxException> { box.open(sealed = "v2:abc:def", aad = accountId) }
        }

        test("FinTsCredentials.toString() never contains the plaintext PIN or userId") {
            val credentials =
                FinTsCredentials(
                    bankAccountId = Uuid.random(),
                    blz = "12345678",
                    url = "https://example.com/hbci",
                    userId = "very-secret-user-id",
                    pin = "very-secret-pin",
                    iban = "DE89370400440532013000",
                )

            val text = credentials.toString()
            text shouldBe text // sanity: toString() does not throw
            (text.contains("very-secret-user-id")) shouldBe false
            (text.contains("very-secret-pin")) shouldBe false
        }
    })
