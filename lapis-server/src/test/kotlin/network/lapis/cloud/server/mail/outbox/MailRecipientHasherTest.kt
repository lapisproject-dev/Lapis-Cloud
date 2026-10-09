package network.lapis.cloud.server.mail.outbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

class MailRecipientHasherTest :
    FunSpec({
        val key = ByteArray(32) { it.toByte() }

        test("deterministic, normalised (trim + lower case), 64 hex characters, no trace of the address") {
            val hasher = MailRecipientHasher(key)
            val hash = hasher.hash("Max.Mustermann@Example.org")
            hash.length shouldBe 64
            hash.matches(Regex("^[0-9a-f]{64}$")) shouldBe true
            hash shouldBe hasher.hash("  max.mustermann@example.org  ")
            hash shouldNotBe hasher.hash("max.mustermann@example.com")
            hash shouldNotContain "max"
        }

        test("a different master key gives a different hash (the lookup handle is keyed)") {
            MailRecipientHasher(key).hash("a@example.org") shouldNotBe MailRecipientHasher(ByteArray(32) { 9 }).hash("a@example.org")
        }

        test("the sub-key is domain-separated: the hash is NOT a plain HMAC of the address under the master key") {
            val plain =
                javax.crypto.Mac.getInstance("HmacSHA256").let {
                    it.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                    java.util.HexFormat
                        .of()
                        .formatHex(it.doFinal("a@example.org".toByteArray()))
                }
            MailRecipientHasher(key).hash("a@example.org") shouldNotBe plain
        }

        test("toString never shows key material") {
            MailRecipientHasher(key).toString() shouldBe "MailRecipientHasher(<redacted>)"
        }
    })
